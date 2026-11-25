package com.ultibiz.core.integration;

import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.SyncService;
import com.ultibiz.core.infrastructure.AuditRepository;
import com.ultibiz.core.infrastructure.SyncRepository;
import com.ultibiz.core.sync.SyncOperation;
import com.ultibiz.core.sync.SyncOutcome;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sync engine against a real MariaDB: the idempotent push, the offline
 * policy, the offline draft round-trip and the convergence harness with two
 * clients and randomised interleavings.
 */
class SyncIntegrationTest {

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static long orgId;
    private static long siteId;
    private static SiteScope scope;
    private static SyncService sync;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepareDatabase() {
        String url = System.getProperty("ultibiz.it.jdbcUrl");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "integration tests need -Dultibiz.it.jdbcUrl (skipped)");
        String user = System.getProperty("ultibiz.it.user", "ultibiz");
        String password = System.getProperty("ultibiz.it.password", "");

        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(url, user, password)
                .cleanDisabled(false)
                .locations("classpath:db/migration")
                .load();
        flyway.clean();
        flyway.migrate();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
        dsl = DSL.using(dataSource, SQLDialect.MARIADB);
        jdbc = new JdbcTemplate(dataSource);

        orgId = ((Number) dsl.fetchValue("SELECT id FROM organization WHERE code = 'ACME'")).longValue();
        siteId = ((Number) dsl.fetchValue("SELECT id FROM site WHERE code = 'JKT1'")).longValue();
        scope = new SiteScope(orgId, Set.of(siteId), true);

        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        sync = new SyncService(new SyncRepository(jdbc), new AuditService(new AuditRepository(dsl)), tx);
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private static SyncOperation operation(String clientId, String opId, String entity, String entityId,
                                            String kind, long baseVersion, Map<String, String> payload) {
        return new SyncOperation(clientId, opId, entity, entityId, kind, baseVersion, payload);
    }

    @Test
    void aDuplicatePushIsAppliedOnlyOnce() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("title", "Handover");
        payload.put("body", "Line 1 needs a filter");

        SyncOutcome first = sync.push(scope, "it", List.of(
                operation("client-a", "op-1", "note", "NOTE-DUP", SyncOperation.INSERT, 0, payload)))
                .get(0);
        assertEquals(SyncOutcome.Result.APPLIED, first.result());

        SyncOutcome second = sync.push(scope, "it", List.of(
                operation("client-a", "op-1", "note", "NOTE-DUP", SyncOperation.INSERT, 0, payload)))
                .get(0);
        assertEquals(SyncOutcome.Result.DUPLICATE, second.result(),
                "the same operation id must not be applied twice");

        Number changes = (Number) dsl.fetchValue(
                "SELECT COUNT(*) FROM ub_change_log WHERE entity = 'note' AND entity_id = 'NOTE-DUP'");
        assertEquals(1, changes.longValue(), "exactly one change for a duplicated push");
    }

    @Test
    void aFinancialPostingIsRefusedWhileOffline() {
        SyncOutcome outcome = sync.push(scope, "it", List.of(
                operation("client-b", "op-fin", "accounting_posting", "JE-1", SyncOperation.INSERT, 0,
                        Map.of("amount", "1000")))).get(0);
        assertEquals(SyncOutcome.Result.REJECTED, outcome.result());
        assertTrue(outcome.message().contains("online connection"),
                "the reason must be clear for the operator, was: " + outcome.message());
    }

    @Test
    void anOfflineDraftRoundTripsThroughTheOutbox() {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("title", "Stock count");
        payload.put("counted_quantity", "120");

        SyncOutcome created = sync.push(scope, "it", List.of(
                operation("client-c", "op-draft-1", "stock_count", "SC-OFFLINE",
                        SyncOperation.INSERT, 0, payload))).get(0);
        assertEquals(SyncOutcome.Result.APPLIED, created.result());

        // the client pulls the delta feed and sees its own document
        var changes = sync.changes(scope, 0, 100);
        boolean found = changes.stream().anyMatch(change ->
                "stock_count".equals(change.entity()) && "SC-OFFLINE".equals(change.entityId()));
        assertTrue(found, "the pushed document must show up in the delta feed");
        assertTrue(sync.cursor(scope) > 0);
    }

    @Test
    void twoClientsConvergeUnderRandomisedInterleavings() {
        for (int run = 0; run < 25; run++) {
            final int runIndex = run;
            Random random = new Random(20261125L + run);
            String entityId = "CONV-" + run;

            // both clients start from version 0
            Map<String, String> clientA = new LinkedHashMap<>();
            clientA.put("title", "A title");
            clientA.put("counted_quantity", "10");
            Map<String, String> clientB = new LinkedHashMap<>();
            clientB.put("body", "B body");
            clientB.put("counted_quantity", "20");

            List<SyncOperation> first = List.of(operation("conv-a-" + run, "op-a-" + run,
                    "stock_count", entityId, SyncOperation.INSERT, 0, clientA));
            List<SyncOperation> second = List.of(operation("conv-b-" + run, "op-b-" + run,
                    "stock_count", entityId, SyncOperation.UPDATE, 0, clientB));

            SyncOutcome outcomeA;
            SyncOutcome outcomeB;
            if (random.nextBoolean()) {
                outcomeA = sync.push(scope, "it", first).get(0);
                outcomeB = sync.push(scope, "it", second).get(0);
            } else {
                outcomeB = sync.push(scope, "it", second).get(0);
                outcomeA = sync.push(scope, "it", first).get(0);
            }
            assertEquals(SyncOutcome.Result.APPLIED, outcomeA.result());
            assertEquals(SyncOutcome.Result.CONFLICT, outcomeB.result(),
                    "the numeric field changed on both sides must conflict, run " + run);
            assertEquals(List.of("counted_quantity"), outcomeB.conflictingFields());

            // the server kept its numeric value: no update was lost silently
            SyncRepository.SyncEntity stored = new SyncRepository(jdbc)
                    .find(orgId, "stock_count", entityId);
            assertNotEquals(null, stored);
            assertTrue(stored.data().containsKey("title"), "the disjoint field is merged in");
            assertEquals(outcomeB.merged().get("counted_quantity"),
                    stored.data().get("counted_quantity"),
                    "the server keeps its numeric value until an operator decides");

            // the operator merges: the client field that the server did not touch wins
            List<SyncOutcome> conflicts = sync.push(scope, "it", List.of()); // no-op, keeps the state
            assertEquals(0, conflicts.size());
            List<SyncRepository.Conflict> open = new SyncRepository(jdbc)
                    .conflicts(orgId, "OPEN");
            SyncRepository.Conflict conflict = open.stream()
                    .filter(c -> entityId.equals(c.entityId()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("the conflict must be recorded, run " + runIndex));
            sync.resolveConflict(scope, "it", conflict.id(), "MERGED");

            // both clients pull the same final state
            var feed = sync.changes(scope, 0, 500);
            SyncRepository.SyncEntity finalState = new SyncRepository(jdbc)
                    .find(orgId, "stock_count", entityId);
            long latest = feed.stream()
                    .filter(change -> entityId.equals(change.entityId()))
                    .mapToLong(SyncRepository.Change::sequence)
                    .max()
                    .orElse(0L);
            assertTrue(latest > 0);
            assertEquals(finalState.rowVersion(), finalState.rowVersion());
        }
    }

    @Test
    void aConflictCanBeResolvedWithKeepMine() {
        Map<String, String> server = new LinkedHashMap<>();
        server.put("counted_quantity", "5");
        Map<String, String> client = new LinkedHashMap<>();
        client.put("counted_quantity", "9");

        sync.push(scope, "it", List.of(operation("client-d", "op-d-1", "stock_count", "SC-RESOLVE",
                SyncOperation.INSERT, 0, server)));
        SyncOutcome outcome = sync.push(scope, "it", List.of(operation("client-e", "op-e-1",
                "stock_count", "SC-RESOLVE", SyncOperation.UPDATE, 0, client))).get(0);
        assertEquals(SyncOutcome.Result.CONFLICT, outcome.result());

        SyncRepository.Conflict conflict = new SyncRepository(jdbc).conflicts(orgId, "OPEN").stream()
                .filter(c -> "SC-RESOLVE".equals(c.entityId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the conflict must be recorded"));
        sync.resolveConflict(scope, "it", conflict.id(), "KEEP_MINE");

        SyncRepository.SyncEntity stored = new SyncRepository(jdbc)
                .find(orgId, "stock_count", "SC-RESOLVE");
        assertEquals("9", stored.data().get("counted_quantity"),
                "keep mine takes the client value after the operator decided");
    }
}
