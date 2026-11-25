package com.ultibiz.core.sync;

import java.util.Map;

/**
 * One mutation a client pushed. {@code opId} identifies the operation inside
 * the client, {@code baseVersion} the row version the client had seen.
 */
public record SyncOperation(
        String clientId,
        String opId,
        String entity,
        String entityId,
        String operation,
        long baseVersion,
        Map<String, String> payload) {

    public SyncOperation {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }

    public static final String INSERT = "INSERT";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";
}
