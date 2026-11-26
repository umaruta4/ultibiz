package com.ultibiz.core.integration;

import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.NumberingService;
import com.ultibiz.core.application.ProductDraft;
import com.ultibiz.core.application.ProductService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.domain.NumberingSequence;
import com.ultibiz.core.domain.Product;
import com.ultibiz.core.infrastructure.AuditRepository;
import com.ultibiz.core.infrastructure.NumberingRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ultibiz.core.infrastructure.ProductRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests against a real MariaDB. They are skipped unless a
 * database URL is supplied:
 *
 * <pre>
 * mvn -B verify -Dultibiz.it.jdbcUrl=jdbc:mariadb://localhost:3311/ultibiz_test \
 *               -Dultibiz.it.user=ultibiz -Dultibiz.it.password=...
 * </pre>
 */
class MasterDataIntegrationTest {

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static long orgId;
    private static long siteA;
    private static long siteB;
    private static long otherOrgId;
    private static long otherOrgSite;

    @BeforeAll
    static void prepareDatabase() {
        String url = System.getProperty("ultibiz.it.jdbcUrl");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
                "integration tests need -Dultibiz.it.jdbcUrl (skipped)");
        String user = System.getProperty("ultibiz.it.user", "ultibiz");
        String password = System.getProperty("ultibiz.it.password", "");

        org.flywaydb.core.Flyway flyway = org.flywaydb.core.Flyway.configure()
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
        config.setMaximumPoolSize(30);
        dataSource = new HikariDataSource(config);
        dsl = DSL.using(dataSource, SQLDialect.MARIADB);

        orgId = ((Number) dsl.fetchValue("SELECT id FROM organization WHERE code = 'ACME'")).longValue();
        siteA = ((Number) dsl.fetchValue("SELECT id FROM site WHERE code = 'JKT1'")).longValue();
        siteB = ((Number) dsl.fetchValue("SELECT id FROM site WHERE code = 'SBY1'")).longValue();

        dsl.execute("INSERT INTO organization (code, name, base_currency) VALUES ('SECOND', 'Second Org', 'IDR')");
        otherOrgId = ((Number) dsl.fetchValue("SELECT id FROM organization WHERE code = 'SECOND'")).longValue();
        dsl.execute("INSERT INTO site (organization_id, country_code, code, name, site_type) "
                + "VALUES (?, 'ID', 'OTH1', 'Other Org Site', 'FACTORY')", otherOrgId);
        otherOrgSite = ((Number) dsl.fetchValue("SELECT id FROM site WHERE code = 'OTH1'")).longValue();
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private NumberingService numberingService() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        return new NumberingService(new NumberingRepository(jdbc), tx);
    }

    private ProductService productService() {
        ProductRepository repository = new ProductRepository(dsl);
        return new ProductService(repository, new AuditService(new AuditRepository(dsl)));
    }

    @Test
    void siteScopedUserCannotSeeAnotherSite() {
        ProductService service = productService();
        SiteScope scopeA = new SiteScope(orgId, Set.of(siteA), true);
        SiteScope scopeB = new SiteScope(orgId, Set.of(siteB), true);

        service.create(scopeA, "it", new ProductDraft(siteA, "SITE-A-ONLY", "Product of site A",
                "GOODS", "PCS", null, false, false, true, new BigDecimal("1000"), "IDR", null, null, true));

        assertTrue(service.search(scopeA, "SITE-A-ONLY", null, false, 0, 20).total() == 1,
                "product of site A must be visible in site A");
        assertTrue(service.search(scopeB, "SITE-A-ONLY", null, false, 0, 20).total() == 0,
                "product of site A must NOT be visible in site B");
        assertTrue(service.search(scopeB, null, null, false, 0, 20).items().stream()
                .noneMatch(p -> "SITE-A-ONLY".equals(p.code())));
    }

    @Test
    void organizationIsolationHoldsInOneSchema() {
        ProductService service = productService();
        SiteScope scopeA = new SiteScope(orgId, Set.of(siteA), true);
        SiteScope scopeOther = new SiteScope(otherOrgId, Set.of(otherOrgSite), true);

        service.create(scopeOther, "it", new ProductDraft(otherOrgSite, "OTHER-ORG-1", "Foreign product",
                "GOODS", "PCS", null, false, false, true, new BigDecimal("1000"), "IDR", null, null, true));

        assertTrue(service.search(scopeA, "OTHER-ORG-1", null, false, 0, 20).total() == 0,
                "a product of another organization must never be visible");
        assertTrue(service.search(scopeOther, "OTHER-ORG-1", null, false, 0, 20).total() == 1);
    }

    @Test
    void sharedProductIsVisibleFromEverySiteOfTheOrganization() {
        ProductService service = productService();
        SiteScope scopeA = new SiteScope(orgId, Set.of(siteA), true);
        SiteScope scopeB = new SiteScope(orgId, Set.of(siteB), true);

        service.create(scopeA, "it", new ProductDraft(null, "SHARED-1", "Shared product",
                "GOODS", "PCS", null, false, false, true, new BigDecimal("1000"), "IDR", null, null, true));

        assertTrue(service.search(scopeA, "SHARED-1", null, false, 0, 20).total() == 1);
        assertTrue(service.search(scopeB, "SHARED-1", null, false, 0, 20).total() == 1,
                "an organization-wide product must be visible from every site");
    }

    @Test
    void deleteIsSoftAndHidesTheRow() {
        ProductService service = productService();
        SiteScope scope = new SiteScope(orgId, Set.of(siteA), true);
        Product created = service.create(scope, "it", new ProductDraft(siteA, "SOFT-1", "To be deleted",
                "GOODS", "PCS", null, false, false, true, new BigDecimal("1000"), "IDR", null, null, true));

        service.delete(scope, "it", created.id());

        assertTrue(service.search(scope, "SOFT-1", null, false, 0, 20).total() == 0);
        Number rowCount = (Number) dsl.fetchValue(
                "SELECT COUNT(*) FROM product WHERE id = ? AND deleted_at IS NOT NULL", created.id());
        assertEquals(1L, rowCount.longValue(), "the row must still exist, only soft-deleted");
    }

    @Test
    void numberingIsContiguousAndUniqueUnderTwoHundredParallelCalls() throws Exception {
        dsl.execute("""
                INSERT INTO numbering_sequence (organization_id, site_id, document_type, name, prefix, suffix,
                    padding, start_at, counter, period_type)
                VALUES (?, NULL, 'IT-DOC', 'IT sequence', 'IT-', '', 8, 1, 0, 'NONE')
                """, orgId);

        NumberingService numbering = numberingService();
        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(25);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<String> numbers = ConcurrentHashMap.newKeySet();
        List<String> failures = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    String number = numbering.next("IT-DOC", orgId, null);
                    if (!numbers.add(number)) {
                        failures.add("duplicate number: " + number);
                    }
                } catch (Exception ex) {
                    failures.add(ex.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "numbering did not finish in time");
        pool.shutdown();

        assertTrue(failures.isEmpty(), "numbering failures: " + failures);
        assertEquals(threads, numbers.size(), "every number must be unique");

        Set<Long> numeric = new java.util.HashSet<>();
        for (String number : numbers) {
            numeric.add(Long.parseLong(number.substring(number.lastIndexOf('-') + 1)));
        }
        assertEquals(threads, numeric.size());
        for (long i = 1; i <= threads; i++) {
            assertTrue(numeric.contains(i), "gap in numbering: " + i + " is missing");
        }
    }

    @Test
    void siteScopedSequenceWinsOverOrganizationWideOne() {
        dsl.execute("""
                INSERT INTO numbering_sequence (organization_id, site_id, document_type, name, prefix, suffix,
                    padding, start_at, counter, period_type)
                VALUES (?, ?, 'SITE-DOC', 'Site sequence', 'S-', '', 4, 1, 0, 'NONE')
                """, orgId, siteA);
        dsl.execute("""
                INSERT INTO numbering_sequence (organization_id, site_id, document_type, name, prefix, suffix,
                    padding, start_at, counter, period_type)
                VALUES (?, NULL, 'SITE-DOC', 'Org sequence', 'O-', '', 4, 1, 0, 'NONE')
                """, orgId);

        NumberingService numbering = numberingService();
        String siteNumber = numbering.next("SITE-DOC", orgId, siteA);
        String orgNumber = numbering.next("SITE-DOC", orgId, null);

        assertTrue(siteNumber.startsWith("S-"), "site-scoped sequence must win: " + siteNumber);
        assertTrue(orgNumber.startsWith("O-"), "organization-wide sequence must be used without a site: " + orgNumber);
        assertFalse(siteNumber.equals(orgNumber));
    }

    @Test
    void periodRestartResetsTheCounter() {
        dsl.execute("""
                INSERT INTO numbering_sequence (organization_id, site_id, document_type, name, prefix, suffix,
                    padding, start_at, counter, period_type, fiscal_year_start_month, last_period_key)
                VALUES (?, NULL, 'PERIOD-DOC', 'Period sequence', 'PD-', '', 4, 1, 41, 'YEARLY', 1, '2025')
                """, orgId);

        NumberingService numbering = numberingService();
        String number = numbering.next("PERIOD-DOC", orgId, null);

        assertEquals("PD-" + java.time.LocalDate.now().getYear() + "-0001", number,
                "the counter must restart when the period key changes");
    }

    @Test
    void numberingSequenceIsReadBackWithItsConfiguration() {
        NumberingRepository repository = new NumberingRepository(new JdbcTemplate(dataSource));
        NumberingSequence sequence = repository.findCandidate(orgId, siteA, "SO");

        assertTrue(sequence != null);
        assertEquals("SO-", sequence.prefix());
        assertEquals(6, sequence.padding());
        assertEquals("YEARLY", sequence.periodType());
    }
}
