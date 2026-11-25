package com.ultibiz.core.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ultibiz.core.sync.SyncOperation;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** The sync store: offline documents, the change log, idempotency, conflicts and the queue. */
@Repository
public class SyncRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public SyncRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -- offline documents ---------------------------------------------------

    /** One offline-capable document. */
    public record SyncEntity(String entity, String entityId, Map<String, String> data, long rowVersion,
                             String originClient, String updatedBy, Instant updatedAt) {
    }

    public SyncEntity find(long organizationId, String entity, String entityId) {
        List<SyncEntity> rows = jdbc.query("""
                SELECT entity, entity_id, data, row_version, origin_client, updated_by, updated_at
                FROM ub_sync_entity WHERE organization_id = ? AND entity = ? AND entity_id = ?
                """, (rs, n) -> new SyncEntity(rs.getString("entity"), rs.getString("entity_id"),
                        readMap(rs.getString("data")), rs.getLong("row_version"),
                        rs.getString("origin_client"), rs.getString("updated_by"),
                        rs.getTimestamp("updated_at").toInstant()),
                organizationId, entity, entityId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<SyncEntity> list(long organizationId, String entity) {
        StringBuilder sql = new StringBuilder("""
                SELECT entity, entity_id, data, row_version, origin_client, updated_by, updated_at
                FROM ub_sync_entity WHERE organization_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        if (entity != null && !entity.isBlank()) {
            sql.append(" AND entity = ?");
            params.add(entity);
        }
        sql.append(" ORDER BY entity, entity_id");
        return jdbc.query(sql.toString(), (rs, n) -> new SyncEntity(rs.getString("entity"),
                rs.getString("entity_id"), readMap(rs.getString("data")), rs.getLong("row_version"),
                rs.getString("origin_client"), rs.getString("updated_by"),
                rs.getTimestamp("updated_at").toInstant()), params.toArray());
    }

    public void insert(long organizationId, String entity, String entityId, Map<String, String> data,
                       String clientId, String actor) {
        jdbc.update("""
                INSERT INTO ub_sync_entity (organization_id, entity, entity_id, data, row_version,
                    origin_client, updated_by)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                """, organizationId, entity, entityId, writeMap(data), clientId, actor);
    }

    public void update(long organizationId, String entity, String entityId, Map<String, String> data,
                       String actor) {
        jdbc.update("""
                UPDATE ub_sync_entity SET data = ?, row_version = row_version + 1, updated_by = ?
                WHERE organization_id = ? AND entity = ? AND entity_id = ?
                """, writeMap(data), actor, organizationId, entity, entityId);
    }

    public void delete(long organizationId, String entity, String entityId) {
        jdbc.update("DELETE FROM ub_sync_entity WHERE organization_id = ? AND entity = ? AND entity_id = ?",
                organizationId, entity, entityId);
    }

    // -- change log ----------------------------------------------------------

    /**
     * Appends a change to the log. The sequence number is monotonic per
     * organisation: the caller holds the organisation row lock, so the
     * allocation is serialized.
     */
    public long appendChange(long organizationId, String entity, String entityId, String operation,
                             long rowVersion, Map<String, String> payload, String actor, String clientId) {
        Number next = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) + 1 FROM ub_change_log WHERE organization_id = ?",
                Number.class, organizationId);
        long sequence = next == null ? 1L : next.longValue();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO ub_change_log (organization_id, sequence_no, entity, entity_id, operation,
                        row_version, payload, actor, client_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, sequence);
            ps.setString(3, entity);
            ps.setString(4, entityId);
            ps.setString(5, operation);
            ps.setLong(6, rowVersion);
            ps.setString(7, payload == null ? null : writeMap(payload));
            ps.setString(8, actor);
            ps.setString(9, clientId);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    /** One entry of the change feed. */
    public record Change(long sequence, String entity, String entityId, String operation, long rowVersion,
                          Map<String, String> payload, String actor, String clientId, Instant createdAt) {
    }

    public List<Change> changes(long organizationId, long since, int limit) {
        return jdbc.query("""
                SELECT sequence_no, entity, entity_id, operation, row_version, payload, actor, client_id,
                       created_at
                FROM ub_change_log WHERE organization_id = ? AND sequence_no > ?
                ORDER BY sequence_no LIMIT ?
                """, (rs, n) -> new Change(rs.getLong("sequence_no"), rs.getString("entity"),
                rs.getString("entity_id"), rs.getString("operation"), rs.getLong("row_version"),
                readMap(rs.getString("payload")), rs.getString("actor"), rs.getString("client_id"),
                rs.getTimestamp("created_at").toInstant()), organizationId, since, limit);
    }

    public long latestSequence(long organizationId) {
        Number n = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) FROM ub_change_log WHERE organization_id = ?",
                Number.class, organizationId);
        return n == null ? 0L : n.longValue();
    }

    /** Locks the organisation row so the change sequence stays monotonic. */
    public void lockOrganization(long organizationId) {
        jdbc.queryForObject("SELECT id FROM organization WHERE id = ? FOR UPDATE", Long.class, organizationId);
    }

    // -- idempotency ----------------------------------------------------------

    /** The stored answer of an operation a client already pushed. */
    public record IdempotentRecord(String result, String response) {
    }

    public IdempotentRecord findIdempotent(long organizationId, String clientId, String opId) {
        List<IdempotentRecord> rows = jdbc.query("""
                SELECT result, response FROM ub_idempotency
                WHERE organization_id = ? AND client_id = ? AND op_id = ?
                """, (rs, n) -> new IdempotentRecord(rs.getString("result"), rs.getString("response")),
                organizationId, clientId, opId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertIdempotent(long organizationId, String clientId, String opId, String entity,
                                 String entityId, String result, String response) {
        jdbc.update("""
                INSERT INTO ub_idempotency (organization_id, client_id, op_id, entity, entity_id, result,
                    response) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, organizationId, clientId, opId, entity, entityId, result, response);
    }

    // -- conflicts -------------------------------------------------------------

    /** A conflict waiting for a decision. */
    public record Conflict(long id, String entity, String entityId, String clientId, String opId,
                            long baseVersion, long serverVersion, Map<String, String> serverData,
                            Map<String, String> clientData, Map<String, String> differingFields,
                            String status, Instant createdAt) {
    }

    public long insertConflict(long organizationId, String entity, String entityId, String clientId,
                                String opId, long baseVersion, long serverVersion,
                                Map<String, String> serverData, Map<String, String> clientData,
                                Map<String, String> differingFields) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO ub_sync_conflict (organization_id, entity, entity_id, client_id, op_id,
                        base_version, server_version, server_data, client_data, differing_fields)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setString(2, entity);
            ps.setString(3, entityId);
            ps.setString(4, clientId);
            ps.setString(5, opId);
            ps.setLong(6, baseVersion);
            ps.setLong(7, serverVersion);
            ps.setString(8, writeMap(serverData));
            ps.setString(9, writeMap(clientData));
            ps.setString(10, writeMap(differingFields));
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public List<Conflict> conflicts(long organizationId, String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, entity, entity_id, client_id, op_id, base_version, server_version,
                       server_data, client_data, differing_fields, status, created_at
                FROM ub_sync_conflict WHERE organization_id = ?
                """);
        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY id DESC");
        return jdbc.query(sql.toString(), (rs, n) -> new Conflict(rs.getLong("id"),
                rs.getString("entity"), rs.getString("entity_id"), rs.getString("client_id"),
                rs.getString("op_id"), rs.getLong("base_version"), rs.getLong("server_version"),
                readMap(rs.getString("server_data")), readMap(rs.getString("client_data")),
                readMap(rs.getString("differing_fields")), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()), params.toArray());
    }

    public Conflict conflict(long organizationId, long id) {
        List<Conflict> rows = jdbc.query("""
                SELECT id, entity, entity_id, client_id, op_id, base_version, server_version,
                       server_data, client_data, differing_fields, status, created_at
                FROM ub_sync_conflict WHERE organization_id = ? AND id = ?
                """, (rs, n) -> new Conflict(rs.getLong("id"), rs.getString("entity"),
                rs.getString("entity_id"), rs.getString("client_id"), rs.getString("op_id"),
                rs.getLong("base_version"), rs.getLong("server_version"),
                readMap(rs.getString("server_data")), readMap(rs.getString("client_data")),
                readMap(rs.getString("differing_fields")), rs.getString("status"),
                rs.getTimestamp("created_at").toInstant()), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void resolveConflict(long id, String status) {
        jdbc.update("UPDATE ub_sync_conflict SET status = ?, resolved_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                status, id);
    }

    // -- retry queue ------------------------------------------------------------

    public void enqueue(long organizationId, SyncOperation operation, String error, Instant nextAttempt) {
        jdbc.update("""
                INSERT INTO ub_sync_queue (organization_id, client_id, op_id, entity, entity_id,
                    operation, base_version, payload, attempts, last_error, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                ON DUPLICATE KEY UPDATE attempts = attempts + 1, last_error = ?, next_attempt_at = ?
                """, organizationId, operation.clientId(), operation.opId(), operation.entity(),
                operation.entityId(), operation.operation(), operation.baseVersion(),
                writeMap(operation.payload()), error, Timestamp.from(nextAttempt),
                error, Timestamp.from(nextAttempt));
    }

    /** A queued operation that is due. */
    public record QueuedOperation(long id, SyncOperation operation, int attempts, String lastError) {
    }

    public List<QueuedOperation> due(long organizationId, Instant now, int limit) {
        return jdbc.query("""
                SELECT id, client_id, op_id, entity, entity_id, operation, base_version, payload,
                       attempts, last_error
                FROM ub_sync_queue WHERE organization_id = ? AND next_attempt_at IS NOT NULL
                  AND next_attempt_at <= ? ORDER BY next_attempt_at LIMIT ?
                """, (rs, n) -> new QueuedOperation(rs.getLong("id"),
                new SyncOperation(rs.getString("client_id"), rs.getString("op_id"),
                        rs.getString("entity"), rs.getString("entity_id"), rs.getString("operation"),
                        rs.getLong("base_version"), readMap(rs.getString("payload"))),
                rs.getInt("attempts"), rs.getString("last_error")),
                organizationId, Timestamp.from(now), limit);
    }

    public void dequeue(long id) {
        jdbc.update("DELETE FROM ub_sync_queue WHERE id = ?", id);
    }

    // -- helpers -------------------------------------------------------------------

    static Map<String, String> readMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(JSON.readValue(json, new TypeReference<>() {
            }));
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    static String writeMap(Map<String, String> data) {
        try {
            return JSON.writeValueAsString(data == null ? Map.of() : data);
        } catch (Exception ex) {
            return "{}";
        }
    }
}
