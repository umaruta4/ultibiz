package com.ultibiz.web.web;

import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.SyncService;
import com.ultibiz.core.infrastructure.SyncRepository.Change;
import com.ultibiz.core.sync.SyncOperation;
import com.ultibiz.core.sync.SyncOutcome;
import com.ultibiz.web.security.SiteScopeProvider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The sync API of the offline clients: push the outbox, pull the delta feed.
 * Every pushed operation gets its own answer (APPLIED, CONFLICT, REJECTED or
 * DUPLICATE).
 */
@RestController
@RequestMapping("/api/sync")
public class SyncApiController {

    private final SyncService sync;
    private final SiteScopeProvider scopeProvider;

    public SyncApiController(SyncService sync, SiteScopeProvider scopeProvider) {
        this.sync = sync;
        this.scopeProvider = scopeProvider;
    }

    /** The body of a push: the client id and its operations. */
    public record PushRequest(String clientId, List<PushOperation> operations) {
    }

    /** One operation of the outbox. */
    public record PushOperation(String opId, String entity, String entityId, String operation,
                                Long baseVersion, Map<String, String> payload) {
    }

    @PostMapping("/push")
    public Map<String, Object> push(@RequestBody PushRequest request) {
        SiteScope scope = scopeProvider.current();
        List<SyncOperation> operations = new ArrayList<>();
        String clientId = request.clientId() == null ? "unknown" : request.clientId();
        if (request.operations() != null) {
            for (PushOperation operation : request.operations()) {
                operations.add(new SyncOperation(clientId, operation.opId(), operation.entity(),
                        operation.entityId(),
                        operation.operation() == null ? SyncOperation.UPDATE : operation.operation(),
                        operation.baseVersion() == null ? 0L : operation.baseVersion(),
                        operation.payload()));
            }
        }
        List<SyncOutcome> outcomes = sync.push(scope, clientId, operations);
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("results", outcomes);
        answer.put("cursor", sync.cursor(scope));
        return answer;
    }

    /** The delta feed after the given cursor. */
    @GetMapping("/changes")
    public Map<String, Object> changes(@RequestParam(defaultValue = "0") long since,
                                       @RequestParam(defaultValue = "200") int limit) {
        SiteScope scope = scopeProvider.current();
        List<Map<String, Object>> items = new ArrayList<>();
        for (Change change : sync.changes(scope, since, limit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sequence", change.sequence());
            item.put("entity", change.entity());
            item.put("entityId", change.entityId());
            item.put("operation", change.operation());
            item.put("rowVersion", change.rowVersion());
            item.put("payload", change.payload());
            item.put("actor", change.actor());
            item.put("createdAt", change.createdAt());
            items.add(item);
        }
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("changes", items);
        answer.put("cursor", sync.cursor(scope));
        return answer;
    }

    /** The same feed under the pull name, with the cursor of the caller. */
    @GetMapping("/pull")
    public Map<String, Object> pull(@RequestParam(defaultValue = "0") long cursor,
                                    @RequestParam(defaultValue = "200") int limit) {
        SiteScope scope = scopeProvider.current();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.putAll(changes(cursor, limit));
        answer.put("cursor", sync.cursor(scope));
        return answer;
    }

    /** The counters the client shows in its status bar. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        SiteScope scope = scopeProvider.current();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("cursor", sync.cursor(scope));
        answer.put("openConflicts", sync.conflicts(scope, "OPEN").size());
        answer.put("serverTime", java.time.Instant.now().toString());
        return answer;
    }
}
