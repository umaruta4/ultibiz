package com.ultibiz.core.sync;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The conflict policy: merge, last writer wins and the server-wins fields. */
class ConflictPolicyTest {

    private static Map<String, String> map(String... pairs) {
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            data.put(pairs[i], pairs[i + 1]);
        }
        return data;
    }

    @Test
    void anUpToDateClientWins() {
        ConflictPolicy.Decision decision = ConflictPolicy.decide(
                map("name", "a", "quantity", "10"),
                map("name", "b"),
                Set.of("name"), 3, 3);
        assertFalse(decision.conflict());
        assertEquals("b", decision.merged().get("name"));
    }

    @Test
    void disjointEditsAreMerged() {
        ConflictPolicy.Decision decision = ConflictPolicy.decide(
                map("name", "a", "body", "server"),
                map("title", "client"),
                Set.of("body"), 1, 2);
        assertFalse(decision.conflict());
        assertEquals("client", decision.merged().get("title"));
        assertEquals("server", decision.merged().get("body"));
    }

    @Test
    void lowRiskFieldsUseTheLastWriter() {
        ConflictPolicy.Decision decision = ConflictPolicy.decide(
                map("name", "server", "description", "old"),
                map("name", "client"),
                Set.of("name"), 1, 2);
        assertFalse(decision.conflict());
        assertEquals("client", decision.merged().get("name"));
    }

    @Test
    void numericFieldsAreNeverSilentlyOverwritten() {
        ConflictPolicy.Decision decision = ConflictPolicy.decide(
                map("counted_quantity", "10"),
                map("counted_quantity", "20"),
                Set.of("counted_quantity"), 1, 2);
        assertTrue(decision.conflict());
        assertEquals(List.of("counted_quantity"), decision.conflictingFields());
        assertEquals("10", decision.merged().get("counted_quantity"),
                "the server keeps the numeric value until an operator decides");
    }

    @Test
    void statusFieldsConflictToo() {
        ConflictPolicy.Decision decision = ConflictPolicy.decide(
                map("status", "OPEN"),
                map("status", "DONE"),
                Set.of("status"), 1, 2);
        assertTrue(decision.conflict());
        assertEquals(List.of("status"), decision.conflictingFields());
    }

    @Test
    void theSameValueOnBothSidesIsNoConflict() {
        ConflictPolicy.Decision decision = ConflictPolicy.decide(
                map("counted_quantity", "10"),
                map("counted_quantity", "10"),
                new LinkedHashSet<>(Set.of("counted_quantity")), 1, 2);
        assertFalse(decision.conflict());
    }

    @Test
    void theOfflinePolicySeparatesTheOnlineOnlyOperations() {
        assertTrue(OfflinePolicy.isOnlineOnly("accounting_posting"));
        assertTrue(OfflinePolicy.isOnlineOnly("stock_transfer"));
        assertTrue(OfflinePolicy.isOnlineOnly("invoice"));
        assertFalse(OfflinePolicy.isOnlineOnly("draft"));
        assertTrue(OfflinePolicy.isOfflineCapable("timesheet"));
        assertTrue(OfflinePolicy.isOfflineCapable("stock_count"));
        assertTrue(OfflinePolicy.rejectionMessage("accounting_posting").contains("online"));
    }
}
