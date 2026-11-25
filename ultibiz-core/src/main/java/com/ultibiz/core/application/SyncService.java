package com.ultibiz.core.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.infrastructure.SyncRepository;
import com.ultibiz.core.infrastructure.SyncRepository.Change;
import com.ultibiz.core.infrastructure.SyncRepository.Conflict;
import com.ultibiz.core.infrastructure.SyncRepository.SyncEntity;
import com.ultibiz.core.sync.ConflictPolicy;
import com.ultibiz.core.sync.OfflinePolicy;
import com.ultibiz.core.sync.SyncOperation;
import com.ultibiz.core.sync.SyncOutcome;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The sync engine: the clients push their outbox, the server applies every
 * operation once (the idempotency key), resolves the conflicts with the
 * conflict policy and publishes every applied change into the delta feed.
 */
@Service
public class SyncService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SyncRepository sync;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public SyncService(SyncRepository sync, AuditService audit, TransactionTemplate tx) {
        this.sync = sync;
        this.audit = audit;
        this.tx = tx;
    }

    /**
     * Applies a batch of pushed operations. Every operation gets its own
     * answer: APPLIED, CONFLICT, REJECTED or DUPLICATE.
     */
    public List<SyncOutcome> push(SiteScope scope, String actor, List<SyncOperation> operations) {
        List<SyncOutcome> outcomes = new ArrayList<>();
        if (scope.isDenyAll() || operations == null) {
            return outcomes;
        }
        for (SyncOperation operation : operations) {
            outcomes.add(tx.execute(status -> apply(scope, actor, operation)));
        }
        return outcomes;
    }

    private SyncOutcome apply(SiteScope scope, String actor, SyncOperation operation) {
        SyncRepository.IdempotentRecord known = sync.findIdempotent(scope.organizationId(),
                operation.clientId(), operation.opId());
        if (known != null) {
            SyncOutcome previous = readOutcome(known.response());
            return new SyncOutcome(operation.opId(), operation.entity(), operation.entityId(),
                    SyncOutcome.Result.DUPLICATE, previous == null ? 0 : previous.rowVersion(),
                    previous == null ? "The operation was already processed" : previous.message(),
                    previous == null ? Map.of() : previous.merged(),
                    previous == null ? List.of() : previous.conflictingFields());
        }

        if (OfflinePolicy.isOnlineOnly(operation.entity())) {
            SyncOutcome rejected = SyncOutcome.rejected(operation.opId(), operation.entity(),
                    operation.entityId(), OfflinePolicy.rejectionMessage(operation.entity()));
            remember(scope, operation, rejected);
            return rejected;
        }

        SyncEntity current = sync.find(scope.organizationId(), operation.entity(),
                operation.entityId());
        try {
            if (SyncOperation.DELETE.equalsIgnoreCase(operation.operation())) {
                if (current == null) {
                    SyncOutcome rejected = SyncOutcome.rejected(operation.opId(), operation.entity(),
                            operation.entityId(), "The document does not exist on the server");
                    remember(scope, operation, rejected);
                    return rejected;
                }
                sync.lockOrganization(scope.organizationId());
                sync.delete(scope.organizationId(), operation.entity(), operation.entityId());
                sync.appendChange(scope.organizationId(), operation.entity(), operation.entityId(),
                        SyncOperation.DELETE, current.rowVersion(), Map.of(), actor,
                        operation.clientId());
                SyncOutcome applied = SyncOutcome.applied(operation.opId(), operation.entity(),
                        operation.entityId(), current.rowVersion());
                remember(scope, operation, applied);
                return applied;
            }

            if (current == null) {
                if (SyncOperation.UPDATE.equalsIgnoreCase(operation.operation())) {
                    SyncOutcome rejected = SyncOutcome.rejected(operation.opId(), operation.entity(),
                            operation.entityId(),
                            "The document does not exist on the server; create it instead");
                    remember(scope, operation, rejected);
                    return rejected;
                }
                sync.lockOrganization(scope.organizationId());
                sync.insert(scope.organizationId(), operation.entity(), operation.entityId(),
                        operation.payload(), operation.clientId(), actor);
                sync.appendChange(scope.organizationId(), operation.entity(), operation.entityId(),
                        SyncOperation.INSERT, 1, operation.payload(), actor, operation.clientId());
                SyncOutcome applied = SyncOutcome.applied(operation.opId(), operation.entity(),
                        operation.entityId(), 1);
                remember(scope, operation, applied);
                return applied;
            }

            // update of an existing row: the conflict policy decides
            Set<String> serverChanged = changedFields(scope.organizationId(), operation.entity(),
                    operation.entityId(), operation.baseVersion());
            ConflictPolicy.Decision decision = ConflictPolicy.decide(current.data(),
                    operation.payload(), serverChanged, operation.baseVersion(), current.rowVersion());

            if (decision.conflict()) {
                Map<String, String> differing = new LinkedHashMap<>();
                for (String field : decision.conflictingFields()) {
                    differing.put(field, current.data().get(field));
                }
                sync.insertConflict(scope.organizationId(), operation.entity(), operation.entityId(),
                        operation.clientId(), operation.opId(), operation.baseVersion(),
                        current.rowVersion(), current.data(), operation.payload(), differing);
                SyncOutcome conflict = new SyncOutcome(operation.opId(), operation.entity(),
                        operation.entityId(), SyncOutcome.Result.CONFLICT, current.rowVersion(),
                        "The server changed the same fields; the server value was kept",
                        decision.merged(), decision.conflictingFields());
                remember(scope, operation, conflict);
                return conflict;
            }

            sync.lockOrganization(scope.organizationId());
            sync.update(scope.organizationId(), operation.entity(), operation.entityId(),
                    decision.merged(), actor);
            sync.appendChange(scope.organizationId(), operation.entity(), operation.entityId(),
                    SyncOperation.UPDATE, current.rowVersion() + 1, operation.payload(), actor,
                    operation.clientId());
            SyncOutcome applied = new SyncOutcome(operation.opId(), operation.entity(),
                    operation.entityId(), SyncOutcome.Result.APPLIED, current.rowVersion() + 1,
                    null, decision.merged(), List.of());
            remember(scope, operation, applied);
            return applied;
        } catch (RuntimeException ex) {
            // a transient failure: the operation goes to the retry queue
            sync.enqueue(scope.organizationId(), operation, ex.getMessage(),
                    Instant.now().plus(1, ChronoUnit.MINUTES));
            SyncOutcome rejected = SyncOutcome.rejected(operation.opId(), operation.entity(),
                    operation.entityId(), "The operation was queued for a retry: " + ex.getMessage());
            remember(scope, operation, rejected);
            return rejected;
        }
    }

    /** The fields the server changed since the row version the client saw. */
    private Set<String> changedFields(long organizationId, String entity, String entityId,
                                      long sinceRowVersion) {
        Set<String> fields = new LinkedHashSet<>();
        for (Change change : sync.changes(organizationId, 0, 1000)) {
            if (!change.entity().equals(entity) || !change.entityId().equals(entityId)) {
                continue;
            }
            if (change.rowVersion() <= sinceRowVersion) {
                continue;
            }
            fields.addAll(change.payload().keySet());
        }
        return fields;
    }

    private void remember(SiteScope scope, SyncOperation operation, SyncOutcome outcome) {
        sync.insertIdempotent(scope.organizationId(), operation.clientId(), operation.opId(),
                operation.entity(), operation.entityId(), outcome.result().name(), writeOutcome(outcome));
        audit.record(AuditEvent.of(scope.organizationId(), "sync", "SYNC_" + outcome.result(),
                operation.entity(), operation.entityId(), "{\"opId\":\"" + operation.opId() + "\"}", null));
    }

    // -- the delta feed ---------------------------------------------------------

    /** The changes after the given sequence number. */
    public List<Change> changes(SiteScope scope, long since, int limit) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return sync.changes(scope.organizationId(), since, Math.max(1, Math.min(limit, 500)));
    }

    /** The newest sequence number of the organisation. */
    public long cursor(SiteScope scope) {
        if (scope.isDenyAll()) {
            return 0L;
        }
        return sync.latestSequence(scope.organizationId());
    }

    /** Publishes a change of an ERP entity into the delta feed (read-only for the clients). */
    public void publish(SiteScope scope, String actor, String entity, String entityId,
                        String operation, long rowVersion, Map<String, String> payload) {
        if (scope.isDenyAll()) {
            return;
        }
        tx.executeWithoutResult(status -> {
            sync.lockOrganization(scope.organizationId());
            sync.appendChange(scope.organizationId(), entity, entityId, operation, rowVersion,
                    payload, actor, null);
        });
    }

    // -- conflicts ----------------------------------------------------------------

    public List<Conflict> conflicts(SiteScope scope, String status) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return sync.conflicts(scope.organizationId(), status);
    }

    /**
     * Resolves a conflict: KEEP_MINE takes the client data, KEEP_THEIRS keeps
     * the server data and MERGED takes the fields the client changed that the
     * server did not touch.
     */
    public void resolveConflict(SiteScope scope, String actor, long conflictId, String resolution) {
        Conflict conflict = sync.conflict(scope.organizationId(), conflictId);
        if (conflict == null) {
            throw new OrderException("Conflict not found: " + conflictId);
        }
        if (!"OPEN".equals(conflict.status())) {
            throw new OrderException("The conflict is already resolved");
        }
        Map<String, String> merged = new LinkedHashMap<>(conflict.serverData());
        switch (resolution) {
            case "KEEP_MINE" -> merged.putAll(conflict.clientData());
            case "KEEP_THEIRS" -> merged.putAll(conflict.serverData());
            case "MERGED" -> {
                for (Map.Entry<String, String> entry : conflict.clientData().entrySet()) {
                    if (!conflict.differingFields().containsKey(entry.getKey())) {
                        merged.put(entry.getKey(), entry.getValue());
                    }
                }
            }
            default -> throw new OrderException("Unknown resolution: " + resolution);
        }
        tx.executeWithoutResult(status -> {
            sync.lockOrganization(scope.organizationId());
            sync.update(scope.organizationId(), conflict.entity(), conflict.entityId(), merged, actor);
            sync.appendChange(scope.organizationId(), conflict.entity(), conflict.entityId(),
                    SyncOperation.UPDATE, conflict.serverVersion() + 1, merged, actor, null);
            sync.resolveConflict(conflictId, resolution);
        });
        audit.record(AuditEvent.of(scope.organizationId(), actor, "SYNC_CONFLICT_RESOLVED",
                conflict.entity(), conflict.entityId(), "{\"resolution\":\"" + resolution + "\"}", null));
    }

    // -- the retry queue --------------------------------------------------------------

    /** Retries the queued operations. */
    public int retryQueued(SiteScope scope, String actor) {
        List<SyncRepository.QueuedOperation> due = sync.due(scope.organizationId(), Instant.now(), 50);
        int handled = 0;
        for (SyncRepository.QueuedOperation queued : due) {
            SyncOutcome outcome = apply(scope, actor, queued.operation());
            if (outcome.result() != SyncOutcome.Result.REJECTED) {
                sync.dequeue(queued.id());
            }
            handled++;
        }
        return handled;
    }

    /** The offline documents of the organisation. */
    public List<SyncEntity> documents(SiteScope scope, String entity) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return sync.list(scope.organizationId(), entity);
    }

    // -- helpers ---------------------------------------------------------------------

    private static String writeOutcome(SyncOutcome outcome) {
        try {
            return JSON.writeValueAsString(outcome);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static SyncOutcome readOutcome(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<SyncOutcome>() {
            });
        } catch (Exception ex) {
            return null;
        }
    }
}
