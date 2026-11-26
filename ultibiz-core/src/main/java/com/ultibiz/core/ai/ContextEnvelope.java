package com.ultibiz.core.ai;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the assistant knows about the screen the user is on: the route, the
 * entity and the filters. Never raw HTML — only the structured fields the
 * assistant may use.
 */
public record ContextEnvelope(String route, String entity, String entityId, Map<String, String> filters) {

    public ContextEnvelope {
        filters = filters == null ? Map.of() : Map.copyOf(filters);
    }

    public static ContextEnvelope of(String route) {
        return new ContextEnvelope(route, null, null, Map.of());
    }

    /** The envelope as the compact text the model sees. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append("route=").append(route == null ? "" : route);
        if (entity != null) {
            text.append(", entity=").append(entity);
        }
        if (entityId != null) {
            text.append(", entityId=").append(entityId);
        }
        if (!filters.isEmpty()) {
            Map<String, String> sorted = new LinkedHashMap<>(filters);
            text.append(", filters=").append(sorted);
        }
        return text.toString();
    }
}
