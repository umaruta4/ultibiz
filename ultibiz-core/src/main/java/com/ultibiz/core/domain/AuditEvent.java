package com.ultibiz.core.domain;

import java.time.Instant;

/**
 * Append-only audit record. Written for every mutating action; never updated
 * or deleted by application code.
 */
public record AuditEvent(
        long organizationId,
        String actor,
        String action,
        String entityType,
        String entityId,
        String beforeJson,
        String afterJson,
        String ip,
        String userAgent,
        String correlationId,
        Instant createdAt) {

    public static AuditEvent of(long organizationId, String actor, String action,
                                String entityType, String entityId, String afterJson,
                                String correlationId) {
        return new AuditEvent(organizationId, actor, action, entityType, entityId,
                null, afterJson, null, null, correlationId, Instant.now());
    }
}
