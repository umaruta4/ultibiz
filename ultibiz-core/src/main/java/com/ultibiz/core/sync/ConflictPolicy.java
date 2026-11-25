package com.ultibiz.core.sync;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides what happens when a client pushes a change to a row that moved on
 * since the client saw it:
 *
 * <ul>
 *   <li>the client is up to date — its change is applied;</li>
 *   <li>the fields are disjoint — the changes are merged field by field;</li>
 *   <li>the field is low risk (a name, a note) — last writer wins, no conflict;</li>
 *   <li>the field is numeric, financial or a status — the server value is kept
 *       and the change goes to the conflict centre for a human decision. A
 *       numeric field is never silently overwritten, so an update can not be
 *       lost.</li>
 * </ul>
 */
public final class ConflictPolicy {

    /** The fields the server owns: the client never overwrites them silently. */
    public static final Set<String> SERVER_WINS = Set.of(
            "price", "cost", "amount", "quantity", "counted_quantity", "hours", "tax",
            "tax_percent", "total", "balance", "status", "state", "quantity_on_hand",
            "unit_price", "net_amount");

    /** The low-risk fields: the last writer wins, without bothering the operator. */
    public static final Set<String> LOW_RISK = Set.of(
            "name", "description", "note", "body", "title", "comment", "address", "remark");

    private ConflictPolicy() {
    }

    /** The decision for one push. */
    public record Decision(boolean conflict, Map<String, String> merged, List<String> conflictingFields) {
    }

    /**
     * @param server the current server data
     * @param client the data the client sent
     * @param serverChanged the fields the server changed since the client saw the row
     * @param baseVersion the row version the client based its change on
     * @param currentVersion the current row version of the row
     */
    public static Decision decide(Map<String, String> server, Map<String, String> client,
                                  Set<String> serverChanged, long baseVersion, long currentVersion) {
        Map<String, String> merged = new LinkedHashMap<>(server);
        merged.putAll(client);
        if (baseVersion >= currentVersion) {
            // the client is up to date (or ahead): its change wins
            return new Decision(false, merged, List.of());
        }
        Map<String, String> differing = new LinkedHashMap<>();
        Set<String> conflicts = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : client.entrySet()) {
            String field = entry.getKey();
            String clientValue = entry.getValue();
            String serverValue = server.get(field);
            if (equals(serverValue, clientValue)) {
                continue; // both sides agree
            }
            if (!serverChanged.contains(field)) {
                continue; // the server did not touch this field: no conflict
            }
            if (LOW_RISK.contains(field)) {
                continue; // last writer wins
            }
            if (SERVER_WINS.contains(field)) {
                conflicts.add(field);
                differing.put(field, serverValue);
                merged.put(field, serverValue); // the server value is kept
            } else {
                // an unknown field is treated conservatively like a status field
                conflicts.add(field);
                differing.put(field, serverValue);
                merged.put(field, serverValue);
            }
        }
        if (conflicts.isEmpty()) {
            return new Decision(false, merged, List.of());
        }
        return new Decision(true, merged, List.copyOf(conflicts));
    }

    private static boolean equals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
