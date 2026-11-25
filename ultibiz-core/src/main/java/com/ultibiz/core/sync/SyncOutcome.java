package com.ultibiz.core.sync;

import java.util.List;
import java.util.Map;

/** The answer of one pushed operation. */
public record SyncOutcome(
        String opId,
        String entity,
        String entityId,
        Result result,
        long rowVersion,
        String message,
        Map<String, String> merged,
        List<String> conflictingFields) {

    public enum Result {
        APPLIED,
        CONFLICT,
        REJECTED,
        DUPLICATE
    }

    public static SyncOutcome applied(String opId, String entity, String entityId, long rowVersion) {
        return new SyncOutcome(opId, entity, entityId, Result.APPLIED, rowVersion, null, Map.of(), List.of());
    }

    public static SyncOutcome rejected(String opId, String entity, String entityId, String message) {
        return new SyncOutcome(opId, entity, entityId, Result.REJECTED, 0, message, Map.of(), List.of());
    }
}
