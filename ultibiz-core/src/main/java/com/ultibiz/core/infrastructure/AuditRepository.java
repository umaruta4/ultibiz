package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.AuditEvent;
import java.time.Instant;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {

    private final DSLContext dsl;

    public AuditRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void insert(AuditEvent event) {
        dsl.execute("""
                        INSERT INTO audit_log (organization_id, actor, action, entity_type, entity_id,
                                               before_json, after_json, ip, user_agent, correlation_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                event.organizationId(), event.actor(), event.action(), event.entityType(),
                event.entityId(), event.beforeJson(), event.afterJson(), event.ip(),
                event.userAgent(), event.correlationId(), java.sql.Timestamp.from(event.createdAt()));
    }

    public List<AuditEvent> recent(long organizationId, int limit) {
        return dsl.resultQuery("""
                        SELECT organization_id, actor, action, entity_type, entity_id,
                               before_json, after_json, ip, user_agent, correlation_id, created_at
                          FROM audit_log
                         WHERE organization_id = ?
                         ORDER BY id DESC
                         LIMIT ?
                        """, organizationId, limit)
                .fetch(r -> new AuditEvent(
                        r.get("organization_id", Long.class),
                        r.get("actor", String.class),
                        r.get("action", String.class),
                        r.get("entity_type", String.class),
                        r.get("entity_id", String.class),
                        r.get("before_json", String.class),
                        r.get("after_json", String.class),
                        r.get("ip", String.class),
                        r.get("user_agent", String.class),
                        r.get("correlation_id", String.class),
                        r.get("created_at", Instant.class)));
    }
}
