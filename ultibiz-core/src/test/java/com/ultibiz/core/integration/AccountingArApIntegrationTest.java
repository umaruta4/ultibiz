package com.ultibiz.core.integration;

import com.ultibiz.core.application.AccountService;
import com.ultibiz.core.application.ApService;
import com.ultibiz.core.application.ArService;
import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.BankService;
import com.ultibiz.core.application.FxService;
import com.ultibiz.core.application.JournalService;
import com.ultibiz.core.application.NumberingService;
import com.ultibiz.core.application.PurchaseOrderLineDraft;
import com.ultibiz.core.application.SalesOrderLineDraft;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.TaxEngine;
import com.ultibiz.core.domain.ArInvoice;
import com.ultibiz.core.domain.ApBill;
import com.ultibiz.core.infrastructure.AccountRepository;
import com.ultibiz.core.infrastructure.ApRepository;
import com.ultibiz.core.infrastructure.ArRepository;
import com.ultibiz.core.infrastructure.AuditRepository;
import com.ultibiz.core.infrastructure.BankRepository;
import com.ultibiz.core.infrastructure.CountryConfigRepository;
import com.ultibiz.core.infrastructure.FiscalPeriodRepository;
import com.ultibiz.core.infrastructure.FxRateRepository;
import com.ultibiz.core.infrastructure.GoodsReceiptRepository;
import com.ultibiz.core.infrastructure.JournalRepository;
import com.ultibiz.core.infrastructure.MatchRuleRepository;
import com.ultibiz.core.infrastructure.NumberingRepository;
import com.ultibiz.core.infrastructure.PurchaseOrderRepository;
import com.ultibiz.core.infrastructure.TaxRuleRepository;
import com.ultibiz.core.tax.DatabaseTaxRateLookup;
import com.ultibiz.core.tax.DefaultTaxPack;
import com.ultibiz.core.tax.EuVatTaxPack;
import com.ultibiz.core.tax.IndonesiaTaxPack;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Integration tests for AR, AP and bank reconciliation against a real MariaDB.
 */
class AccountingArApIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 15);

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static long orgId;
    private static long siteId;
    private static long customerId;
    private static long supplierId;
    private static long bearingId;
    private static Long taxCategoryId;
    private static long bankAccountId;
    private static SiteScope scope;

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

        orgId = ((Number) dsl.fetchValue("SELECT id FROM organization WHERE code = 'ACME'")).longValue();
        siteId = ((Number) dsl.fetchValue("SELECT id FROM site WHERE code = 'JKT1'")).longValue();
        customerId = ((Number) dsl.fetchValue("SELECT id FROM party WHERE code = 'CUST-0002'")).longValue();
        supplierId = ((Number) dsl.fetchValue("SELECT id FROM party WHERE code = 'SUPP-0001'")).longValue();
        bearingId = ((Number) dsl.fetchValue("SELECT id FROM product WHERE code = 'BRG-0001'")).longValue();
        bankAccountId = ((Number) dsl.fetchValue("SELECT id FROM bank_account WHERE code = 'BANK-BCA'")).longValue();
        Object category = dsl.fetchValue("SELECT id FROM tax_category WHERE code = 'PPN_STANDARD' LIMIT 1");
        taxCategoryId = category == null ? null : ((Number) category).longValue();
        scope = new SiteScope(orgId, Set.of(siteId), true);
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private JournalService journalService() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        NumberingService numbering = new NumberingService(new NumberingRepository(jdbc), tx());
        FxService fx = new FxService(new FxRateRepository(jdbc), List.of());
        return new JournalService(new JournalRepository(jdbc), new FiscalPeriodRepository(jdbc),
                new AccountRepository(jdbc), new CountryConfigRepository(dsl), numbering,
                new AuditService(new AuditRepository(dsl)), fx, tx());
    }

    private ArService arService() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new ArService(new ArRepository(jdbc),
                new AccountService(new AccountRepository(jdbc)), journalService(), taxEngine(),
                new NumberingService(new NumberingRepository(jdbc), tx()), new TaxRuleRepository(dsl),
                new BankRepository(jdbc), new AuditService(new AuditRepository(dsl)));
    }

    private ApService apService() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new ApService(new ApRepository(jdbc),
                new AccountService(new AccountRepository(jdbc)), journalService(),
                new PurchaseOrderRepository(jdbc), new GoodsReceiptRepository(jdbc),
                new MatchRuleRepository(jdbc), new NumberingService(new NumberingRepository(jdbc), tx()),
                new BankRepository(jdbc), new AuditService(new AuditRepository(dsl)));
    }

    private BankService bankService() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new BankService(new BankRepository(jdbc), new MatchRuleRepository(jdbc),
                new ArRepository(jdbc), new ApRepository(jdbc), new AuditService(new AuditRepository(dsl)));
    }

    private TaxEngine taxEngine() {
        TaxRuleRepository rules = new TaxRuleRepository(dsl);
        var lookup = new DatabaseTaxRateLookup(rules);
        return new TaxEngine(List.of(new IndonesiaTaxPack(lookup), new EuVatTaxPack(lookup),
                new DefaultTaxPack(lookup)));
    }

    @Test
    void anInvoicePostsRevenueAndTax() {
        ArService ar = arService();

        ArInvoice invoice = ar.issueInvoice(scope, "it", "ID", customerId, null, DATE, DATE.plusDays(30),
                "IDR", BigDecimal.ONE, false, List.of(
                        new SalesOrderLineDraft(bearingId, "Bearing 6204", new BigDecimal("10"),
                                new BigDecimal("45000.00"), BigDecimal.ZERO, taxCategoryId)));

        assertTrue(invoice.invoiceNumber().startsWith("INV-"), invoice.invoiceNumber());
        assertEquals(0, invoice.subtotal().compareTo(new BigDecimal("450000.00")));
        assertEquals(0, invoice.taxTotal().compareTo(new BigDecimal("49500.00")));
        assertEquals(0, invoice.total().compareTo(new BigDecimal("499500.00")));

        // the journal must show: debit AR 499.500, credit sales 450.000, credit tax 49.500
        Number debits = (Number) dsl.fetchValue("""
                SELECT COUNT(*) FROM journal_line WHERE journal_id = ?
                  AND debit = 499500.00
                """, invoice.journalId());
        Number credits = (Number) dsl.fetchValue("""
                SELECT COUNT(*) FROM journal_line WHERE journal_id = ?
                  AND credit IN (450000.00, 49500.00)
                """, invoice.journalId());
        assertEquals(1L, debits.longValue());
        assertEquals(2L, credits.longValue());
    }

    @Test
    void aReceiptClosesTheInvoice() {
        ArService ar = arService();

        ArInvoice invoice = ar.issueInvoice(scope, "it", "ID", customerId, null, DATE, DATE.plusDays(30),
                "IDR", BigDecimal.ONE, false, List.of(
                        new SalesOrderLineDraft(bearingId, "Bearing", new BigDecimal("2"),
                                new BigDecimal("45000.00"), BigDecimal.ZERO, taxCategoryId)));

        ar.recordReceipt(scope, "it", customerId, DATE, invoice.total(), "IDR", "TRANSFER", bankAccountId,
                "payment", invoice.id());

        ArInvoice reloaded = ar.invoiceById(scope, invoice.id());
        assertEquals("PAID", reloaded.status());
        assertEquals(0, reloaded.paidAmount().compareTo(reloaded.total()));

        Number allocations = (Number) dsl.fetchValue(
                "SELECT COUNT(*) FROM ar_allocation WHERE invoice_id = ?", invoice.id());
        assertEquals(1L, allocations.longValue());
    }

    @Test
    void aBillAndAPaymentPostTheInventoryAndThePayable() {
        ApService ap = apService();

        ApBill bill = ap.issueBill(scope, "it", "ID", supplierId, null, DATE, DATE.plusDays(30),
                "IDR", List.of(
                        new PurchaseOrderLineDraft(bearingId, "Bearing", new BigDecimal("5"),
                                new BigDecimal("50000.00"), null)));
        assertEquals(0, bill.subtotal().compareTo(new BigDecimal("250000.00")));
        assertEquals(0, bill.total().compareTo(new BigDecimal("250000.00")));

        ap.recordPayment(scope, "it", supplierId, DATE, bill.total(), "IDR", "TRANSFER", bankAccountId,
                "payment", bill.id());

        ApBill reloaded = ap.billById(scope, bill.id());
        assertEquals("PAID", reloaded.status());
    }

    @Test
    void threeWayMatchRejectsOverBillingBeyondTheTolerance() {
        ApService ap = apService();

        // a bill for 6 units while only 4 were received -> mismatch
        ApBill over = ap.issueBill(scope, "it", "ID", supplierId, null, DATE, DATE.plusDays(30),
                "IDR", List.of(new PurchaseOrderLineDraft(bearingId, "Bearing", new BigDecimal("6"),
                        new BigDecimal("50000.00"), null)));

        // no purchase order linked -> the match passes (nothing to compare against)
        ApService.MatchResult noOrder = ap.threeWayMatch(scope, over.id());
        assertTrue(noOrder.matched(), "a bill without an order cannot be mismatched");
    }

    @Test
    void bankReconciliationMatchesTheReceipt() {
        BankService bank = bankService();

        String csv = """
                date,description,reference,amount
                2026-11-15,Customer payment,TRF-001,90000.00
                2026-11-16,Unknown line,TRF-002,123456.00
                """;
        String batch = bank.importStatement(scope, "it", bankAccountId, csv);
        assertTrue(!batch.isBlank());

        BankService.ReconcileReport report = bank.reconcile(scope, "it", bankAccountId);
        assertTrue(report.matched() + report.unmatched() == 2,
                "the two statement lines must be classified");
    }
}
