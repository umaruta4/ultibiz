package com.ultibiz.core.integration;

import com.ultibiz.core.application.AccountService;
import com.ultibiz.core.application.AssetService;
import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.FxService;
import com.ultibiz.core.application.JournalService;
import com.ultibiz.core.application.MrpService;
import com.ultibiz.core.application.NumberingService;
import com.ultibiz.core.application.OrderException;
import com.ultibiz.core.application.SalesOrderLineDraft;
import com.ultibiz.core.application.SalesOrderService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.StockService;
import com.ultibiz.core.application.TaxEngine;
import com.ultibiz.core.domain.Asset;
import com.ultibiz.core.domain.AssetCategory;
import com.ultibiz.core.domain.AssetDepreciation;
import com.ultibiz.core.domain.Product;
import com.ultibiz.core.infrastructure.AccountRepository;
import com.ultibiz.core.infrastructure.AssetRepository;
import com.ultibiz.core.infrastructure.AuditRepository;
import com.ultibiz.core.infrastructure.CountryConfigRepository;
import com.ultibiz.core.infrastructure.FiscalPeriodRepository;
import com.ultibiz.core.infrastructure.FxRateRepository;
import com.ultibiz.core.infrastructure.JournalRepository;
import com.ultibiz.core.infrastructure.NumberingRepository;
import com.ultibiz.core.infrastructure.PartyRepository;
import com.ultibiz.core.infrastructure.ProductRepository;
import com.ultibiz.core.infrastructure.PurchaseOrderRepository;
import com.ultibiz.core.infrastructure.SalesOrderRepository;
import com.ultibiz.core.infrastructure.StockRepository;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the asset register (depreciation, disposal) and the
 * MRP suggestions against a real MariaDB.
 */
class AssetsIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 15);

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static long orgId;
    private static long siteId;
    private static long customerId;
    private static long bearingId;
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
        bearingId = ((Number) dsl.fetchValue("SELECT id FROM product WHERE code = 'BRG-0001'")).longValue();
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

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private JournalService journalService() {
        JdbcTemplate jdbc = jdbc();
        NumberingService numbering = new NumberingService(new NumberingRepository(jdbc), tx());
        FxService fx = new FxService(new FxRateRepository(jdbc), List.of());
        return new JournalService(new JournalRepository(jdbc), new FiscalPeriodRepository(jdbc),
                new AccountRepository(jdbc), new CountryConfigRepository(dsl), numbering,
                new AuditService(new AuditRepository(dsl)), fx, tx());
    }

    private AccountService accountService() {
        return new AccountService(new AccountRepository(jdbc()));
    }

    private AssetService assetService() {
        return new AssetService(new AssetRepository(jdbc()), accountService(), journalService(),
                new AuditService(new AuditRepository(dsl)));
    }

    private StockService stockService() {
        return new StockService(new StockRepository(jdbc()), tx());
    }

    private TaxEngine taxEngine() {
        TaxRuleRepository rules = new TaxRuleRepository(dsl);
        var lookup = new DatabaseTaxRateLookup(rules);
        return new TaxEngine(List.of(new IndonesiaTaxPack(lookup), new EuVatTaxPack(lookup),
                new DefaultTaxPack(lookup)));
    }

    private SalesOrderService salesOrderService(StockService stock) {
        JdbcTemplate jdbc = jdbc();
        return new SalesOrderService(new SalesOrderRepository(jdbc), new PartyRepository(dsl),
                new TaxRuleRepository(dsl), taxEngine(), new NumberingService(new NumberingRepository(jdbc), tx()),
                new com.ultibiz.core.application.GoodsReceiptService(
                        new com.ultibiz.core.infrastructure.GoodsReceiptRepository(jdbc), stock,
                        new NumberingService(new NumberingRepository(jdbc), tx()),
                        new AuditService(new AuditRepository(dsl))),
                new AuditService(new AuditRepository(dsl)));
    }

    private MrpService mrpService(StockService stock) {
        JdbcTemplate jdbc = jdbc();
        return new MrpService(new ProductRepository(dsl), new SalesOrderRepository(jdbc),
                new PurchaseOrderRepository(jdbc), stock);
    }

    private AssetCategory category(String code) {
        for (AssetCategory c : assetService().categories(scope)) {
            if (c.code().equals(code)) {
                return c;
            }
        }
        throw new IllegalStateException("missing category " + code);
    }


    private AssetDepreciation rowOf(List<AssetDepreciation> rows, long assetId) {
        return rows.stream()
                .filter(r -> r.assetId() == assetId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no depreciation row for asset " + assetId));
    }

    @Test
    void straightLineDepreciationMatchesTheFixture() {
        AssetService assets = assetService();
        AssetCategory machine = category("MACHINE");
        // 120.000.000 cost, 10 % salvage, 60 months -> (120.000.000 - 12.000.000) / 60
        Asset asset = assets.register(scope, "it", machine.id(), "AST-SL-1", "Straight line asset",
                LocalDate.of(2024, 1, 15), new BigDecimal("120000000.00"), null, null, null, null, null);

        List<AssetDepreciation> rows = assets.depreciate(scope, "it", 2026, 11);

        AssetDepreciation row = rowOf(rows, asset.id());
        assertEquals(0, row.depreciationAmount().compareTo(new BigDecimal("1800000.00")),
                "straight line: (120.000.000 - 12.000.000) / 60 = 1.800.000");
        assertEquals(0, row.accumulatedAmount().compareTo(new BigDecimal("1800000.00")));
        assertEquals(0, row.bookValueAfter().compareTo(new BigDecimal("118200000.00")));

        Asset reloaded = assets.byId(scope, asset.id());
        assertEquals(0, reloaded.bookValue().compareTo(new BigDecimal("118200000.00")));

        // the journal must be balanced and reference the depreciation
        Number debits = (Number) dsl.fetchValue(
                "SELECT SUM(base_debit) FROM journal_line WHERE journal_id = ?", row.journalId());
        Number credits = (Number) dsl.fetchValue(
                "SELECT SUM(base_credit) FROM journal_line WHERE journal_id = ?", row.journalId());
        assertNotNull(debits);
        assertEquals(0, debits.longValue() - credits.longValue(), "the depreciation journal must balance");
        BigDecimal posted = rows.stream()
                .map(AssetDepreciation::depreciationAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal(String.valueOf(debits)).compareTo(posted.setScale(2,
                java.math.RoundingMode.HALF_UP)),
                "the journal must carry exactly the depreciation of the run");
    }

    @Test
    void decliningBalanceDepreciationMatchesTheFixture() {
        AssetService assets = assetService();
        AssetCategory vehicle = category("VEHICLE");
        // 90.000.000 cost, 25 % salvage, 48 months
        // rate = 1 - (0.25)^(1/48) = 0.0284685... -> 2.562.165 for the first month
        Asset asset = assets.register(scope, "it", vehicle.id(), "AST-DB-1", "Declining balance asset",
                LocalDate.of(2025, 1, 15), new BigDecimal("90000000.00"), null, null, null, null, null);

        List<AssetDepreciation> rows = assets.depreciate(scope, "it", 2026, 10);

        double rate = 1.0 - Math.pow(0.25, 1.0 / 48.0);
        BigDecimal expected = new BigDecimal("90000000.00").multiply(BigDecimal.valueOf(rate))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        AssetDepreciation row = rowOf(rows, asset.id());
        assertTrue(row.depreciationAmount().compareTo(expected) == 0,
                "declining balance: expected " + expected + " but was " + row.depreciationAmount());
    }

    @Test
    void unitsOfProductionUsesTheMeterReadings() {
        AssetService assets = assetService();
        AssetCategory tool = category("TOOL");
        // 10.000.000 cost, 5 % salvage, 100.000 units -> 95,00 per unit
        Asset asset = assets.register(scope, "it", tool.id(), "AST-UOP-1", "Tool with a meter",
                LocalDate.of(2026, 1, 1), new BigDecimal("10000000.00"), null, null, null, null, null);

        assets.addMeterReading(scope, "it", asset.id(), LocalDate.of(2026, 11, 5), new BigDecimal("1000.000"));

        List<AssetDepreciation> rows = assets.depreciate(scope, "it", 2026, 11);

        AssetDepreciation row = rowOf(rows, asset.id());
        assertEquals(0, row.depreciationAmount().compareTo(new BigDecimal("95000.00")),
                "units of production: (10.000.000 - 500.000) / 100.000 * 1.000 = 95.000");
    }

    @Test
    void theSamePeriodIsNotDepreciatedTwice() {
        AssetService assets = assetService();
        AssetCategory machine = category("MACHINE");
        Asset asset = assets.register(scope, "it", machine.id(), "AST-SL-2", "Second straight line asset",
                LocalDate.of(2024, 1, 15), new BigDecimal("120000000.00"), null, null, null, null, null);

        long first = assets.depreciate(scope, "it", 2026, 11).stream()
                .filter(r -> r.assetId() == asset.id()).count();
        long second = assets.depreciate(scope, "it", 2026, 11).stream()
                .filter(r -> r.assetId() == asset.id()).count();
        assertEquals(1, first);
        assertEquals(0, second, "the second run must not depreciate the same period again");
    }

    @Test
    void disposalComparesTheProceedsWithTheBookValue() {
        AssetService assets = assetService();
        AssetCategory machine = category("MACHINE");
        Asset asset = assets.register(scope, "it", machine.id(), "AST-DISP-1", "Asset to dispose",
                LocalDate.of(2024, 1, 15), new BigDecimal("100000000.00"), null, null, null, null, null);
        assets.depreciate(scope, "it", 2026, 11);

        var disposal = assets.dispose(scope, "it", asset.id(), LocalDate.of(2026, 11, 20),
                new BigDecimal("100000000.00"), "sold");

        // book value after one month = 100.000.000 - 1.500.000 -> the proceeds are above it
        assertTrue(disposal.gainLoss().signum() > 0, "selling above the book value is a gain");
        Asset reloaded = assets.byId(scope, asset.id());
        assertEquals("DISPOSED", reloaded.status());
        assertEquals(0, reloaded.bookValue().compareTo(BigDecimal.ZERO));

        Number debits = (Number) dsl.fetchValue(
                "SELECT SUM(base_debit) FROM journal_line WHERE journal_id = ?", disposal.journalId());
        Number credits = (Number) dsl.fetchValue(
                "SELECT SUM(base_credit) FROM journal_line WHERE journal_id = ?", disposal.journalId());
        assertEquals(0, debits.longValue() - credits.longValue(), "the disposal journal must balance");
    }

    @Test
    void mrpSuggestsTheNetRequirement() {
        StockService stock = stockService();
        SalesOrderService orders = salesOrderService(stock);
        MrpService mrp = mrpService(stock);

        // 100 units confirmed, nothing in stock, safety stock 20, min order qty 10
        var order = orders.create(scope, "it", "ID", customerId, DATE, DATE, null, "IDR",
                BigDecimal.ONE, "mrp demand", List.of(
                        new SalesOrderLineDraft(bearingId, "Bearing", new BigDecimal("100"),
                                new BigDecimal("45000.00"), BigDecimal.ZERO, null)));
        orders.confirm(scope, "it", order.id());

        List<MrpService.Suggestion> suggestions = mrp.suggestions(scope, DATE);

        MrpService.Suggestion bearing = suggestions.stream()
                .filter(s -> s.productCode().equals("BRG-0001"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no suggestion for BRG-0001"));
        assertEquals(0, bearing.demand().compareTo(new BigDecimal("100.000")));
        assertEquals(0, bearing.onHand().compareTo(BigDecimal.ZERO));
        assertEquals(0, bearing.netRequirement().compareTo(new BigDecimal("120.000")),
                "100 demand + 20 safety stock = 120");
        assertEquals(DATE.plusDays(30), bearing.suggestedDate());
    }
}
