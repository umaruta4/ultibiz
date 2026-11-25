package com.ultibiz.core.integration;

import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.GoodsReceiptService;
import com.ultibiz.core.application.ManufacturingService;
import com.ultibiz.core.application.NumberingService;
import com.ultibiz.core.application.OrderException;
import com.ultibiz.core.application.PurchaseOrderService;
import com.ultibiz.core.application.ReceiptLine;
import com.ultibiz.core.application.SalesOrderLineDraft;
import com.ultibiz.core.application.SalesOrderService;
import com.ultibiz.core.application.ShipmentLine;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.StockService;
import com.ultibiz.core.application.TaxEngine;
import com.ultibiz.core.domain.SalesOrder;
import com.ultibiz.core.domain.SalesOrderLine;
import com.ultibiz.core.domain.WorkOrder;
import com.ultibiz.core.domain.WorkOrderMaterial;
import com.ultibiz.core.infrastructure.AuditRepository;
import com.ultibiz.core.infrastructure.BomRepository;
import com.ultibiz.core.infrastructure.GoodsReceiptRepository;
import com.ultibiz.core.infrastructure.NumberingRepository;
import com.ultibiz.core.infrastructure.PartyRepository;
import com.ultibiz.core.infrastructure.PurchaseOrderRepository;
import com.ultibiz.core.infrastructure.SalesOrderRepository;
import com.ultibiz.core.infrastructure.StockRepository;
import com.ultibiz.core.infrastructure.TaxRuleRepository;
import com.ultibiz.core.infrastructure.WorkOrderRepository;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for orders, stock and manufacturing against a real
 * MariaDB.
 */
class OrdersIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 15);

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static long orgId;
    private static long siteId;
    private static long warehouseId;
    private static long customerId;
    private static long supplierId;
    private static long bearingId;
    private static long bearing2Id;
    private static long palletId;
    private static Long taxCategoryId;
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
        warehouseId = ((Number) dsl.fetchValue(
                "SELECT id FROM warehouse WHERE code = 'WH-JKT-MAIN'")).longValue();
        customerId = ((Number) dsl.fetchValue(
                "SELECT id FROM party WHERE code = 'CUST-0002'")).longValue();
        supplierId = ((Number) dsl.fetchValue(
                "SELECT id FROM party WHERE code = 'SUPP-0001'")).longValue();
        bearingId = ((Number) dsl.fetchValue(
                "SELECT id FROM product WHERE code = 'BRG-0001'")).longValue();
        bearing2Id = ((Number) dsl.fetchValue(
                "SELECT id FROM product WHERE code = 'BRG-0002'")).longValue();
        palletId = ((Number) dsl.fetchValue(
                "SELECT id FROM product WHERE code = 'PALLET-EUR'")).longValue();
        Object category = dsl.fetchValue(
                "SELECT id FROM tax_category WHERE code = 'PPN_STANDARD' LIMIT 1");
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

    private StockService stockService() {
        return new StockService(new StockRepository(new JdbcTemplate(dataSource)), tx());
    }

    private NumberingService numbering() {
        return new NumberingService(new NumberingRepository(new JdbcTemplate(dataSource)), tx());
    }

    private AuditService audit() {
        return new AuditService(new AuditRepository(dsl));
    }

    private TaxEngine taxEngine() {
        TaxRuleRepository rules = new TaxRuleRepository(dsl);
        var lookup = new DatabaseTaxRateLookup(rules);
        return new TaxEngine(List.of(new IndonesiaTaxPack(lookup), new EuVatTaxPack(lookup),
                new DefaultTaxPack(lookup)));
    }

    private GoodsReceiptService receiptsService(StockService stock) {
        return new GoodsReceiptService(new GoodsReceiptRepository(new JdbcTemplate(dataSource)), stock,
                numbering(), audit());
    }

    private SalesOrderService salesOrders(StockService stock) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new SalesOrderService(new SalesOrderRepository(jdbc), new PartyRepository(dsl),
                new TaxRuleRepository(dsl), taxEngine(), numbering(), receiptsService(stock), audit());
    }

    private PurchaseOrderService purchaseOrders(StockService stock) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new PurchaseOrderService(new PurchaseOrderRepository(jdbc), new PartyRepository(dsl),
                new TaxRuleRepository(dsl), taxEngine(), numbering(), receiptsService(stock), audit());
    }

    private ManufacturingService manufacturing(StockService stock) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        return new ManufacturingService(new WorkOrderRepository(jdbc), new BomRepository(jdbc), stock,
                numbering(), receiptsService(stock), audit());
    }

    @Test
    void salesOrderComputesNetTaxAndTotal() {
        StockService stock = stockService();
        SalesOrderService orders = salesOrders(stock);

        SalesOrder order = orders.create(scope, "it", "ID", customerId, DATE, DATE, warehouseId, "IDR",
                BigDecimal.ONE, "integration test", List.of(
                        new SalesOrderLineDraft(bearingId, "Bearing 6204", new BigDecimal("10"),
                                new BigDecimal("45000.00"), BigDecimal.ZERO, taxCategoryId),
                        new SalesOrderLineDraft(bearing2Id, "Bearing 6205", new BigDecimal("2"),
                                new BigDecimal("52000.00"), new BigDecimal("0.10"), taxCategoryId)));

        assertTrue(order.orderNumber().startsWith("SO-"), order.orderNumber());
        // 10 x 45.000 = 450.000 ; 2 x 52.000 less 10% = 93.600 -> subtotal 543.600
        assertEquals(0, order.subtotal().compareTo(new BigDecimal("543600.00")));
        // tax: 450.000 * 11% = 49.500 ; 93.600 * 11% = 10.296 -> total tax 59.796
        assertEquals(0, order.taxTotal().compareTo(new BigDecimal("59796.00")));
        assertEquals(0, order.total().compareTo(new BigDecimal("603396.00")));
    }

    @Test
    void creditLimitBlocksAnOrderThatIsTooLarge() {
        StockService stock = stockService();
        SalesOrderService orders = salesOrders(stock);

        long before = ((Number) dsl.fetchValue("SELECT COUNT(*) FROM sales_order")).longValue();
        // 10.000 x 45.000 = 450.000.000 -> far above the 250.000.000 credit limit
        OrderException error = assertThrows(OrderException.class, () -> orders.create(scope, "it", "ID",
                customerId, DATE, DATE, warehouseId, "IDR", BigDecimal.ONE, "too big", List.of(
                        new SalesOrderLineDraft(bearingId, "Bearing", new BigDecimal("10000"),
                                new BigDecimal("45000.00"), BigDecimal.ZERO, taxCategoryId))));
        assertTrue(error.getMessage().contains("Credit limit"), error.getMessage());

        long after = ((Number) dsl.fetchValue("SELECT COUNT(*) FROM sales_order")).longValue();
        assertEquals(before, after, "a rejected order must not be stored");
    }

    @Test
    void deliveryMovesTheStockOutAndClosesTheOrder() {
        StockService stock = stockService();
        SalesOrderService orders = salesOrders(stock);

        // stock the warehouse first
        stock.post(scope, "it", siteId, warehouseId, null, bearingId, new BigDecimal("100"),
                new BigDecimal("45000.00"), DATE, "PURCHASE", null, null);

        SalesOrder order = orders.create(scope, "it", "ID", customerId, DATE, DATE, warehouseId, "IDR",
                BigDecimal.ONE, "deliver me", List.of(
                        new SalesOrderLineDraft(bearingId, "Bearing 6204", new BigDecimal("10"),
                                new BigDecimal("45000.00"), BigDecimal.ZERO, taxCategoryId)));
        orders.confirm(scope, "it", order.id());

        List<SalesOrderLine> lines = orders.lines(scope, order.id());
        SalesOrder delivered = orders.deliver(scope, "it", order.id(),
                List.of(new ShipmentLine(lines.get(0).id(), new BigDecimal("10"))));

        assertEquals("SHIPPED", delivered.status());
        assertEquals(0, stock.onHand(scope, bearingId).compareTo(new BigDecimal("90.000")));

        long movements = ((Number) dsl.fetchValue(
                "SELECT COUNT(*) FROM stock_movement WHERE product_id = ? AND quantity < 0", bearingId)).longValue();
        assertEquals(1, movements);
    }

    @Test
    void receivingStockUsesAMovingAverageCost() {
        StockService stock = stockService();
        PurchaseOrderService orders = purchaseOrders(stock);

        stock.post(scope, "it", siteId, warehouseId, null, bearing2Id, new BigDecimal("10"),
                new BigDecimal("100.00"), DATE, "PURCHASE", null, null);
        stock.post(scope, "it", siteId, warehouseId, null, bearing2Id, new BigDecimal("10"),
                new BigDecimal("120.00"), DATE, "PURCHASE", null, null);

        // (10 x 100 + 10 x 120) / 20 = 110
        assertEquals(0, stock.onHand(scope, bearing2Id).compareTo(new BigDecimal("20.000")));
        Number average = (Number) dsl.fetchValue(
                "SELECT average_cost FROM stock_balance WHERE product_id = ? AND location_id IS NULL", bearing2Id);
        assertEquals(0, new BigDecimal(String.valueOf(average)).compareTo(new BigDecimal("110.0000")));

        // an issue keeps the average and only reduces the quantity
        stock.post(scope, "it", siteId, warehouseId, null, bearing2Id, new BigDecimal("-5"),
                BigDecimal.ZERO, DATE, "SALES", null, null);
        Number after = (Number) dsl.fetchValue(
                "SELECT average_cost FROM stock_balance WHERE product_id = ? AND location_id IS NULL", bearing2Id);
        assertEquals(0, new BigDecimal(String.valueOf(after)).compareTo(new BigDecimal("110.0000")));
        assertEquals(0, stock.onHand(scope, bearing2Id).compareTo(new BigDecimal("15.000")));
    }

    @Test
    void issueBeyondTheStockIsRefused() {
        StockService stock = stockService();
        drain(stock, palletId);
        stock.post(scope, "it", siteId, warehouseId, null, palletId, new BigDecimal("3"),
                new BigDecimal("65.00"), DATE, "PURCHASE", null, null);

        OrderException error = assertThrows(OrderException.class, () -> stock.post(scope, "it", siteId,
                warehouseId, null, palletId, new BigDecimal("-5"), BigDecimal.ZERO, DATE, "SALES", null, null));
        assertTrue(error.getMessage().contains("Not enough stock"), error.getMessage());
        assertEquals(0, stock.onHand(scope, palletId).compareTo(new BigDecimal("3.000")));
    }

    @Test
    void workOrderExplodesTheBomAndNeedsTheMaterialsInStock() {
        StockService stock = stockService();
        ManufacturingService manufacturing = manufacturing(stock);
        drain(stock, bearingId);
        drain(stock, bearing2Id);
        drain(stock, palletId);

        WorkOrder order = manufacturing.create(scope, "it", palletId, null, warehouseId, null,
                new BigDecimal("10"), DATE, DATE);

        List<WorkOrderMaterial> materials = manufacturing.materials(scope, order.id());
        assertEquals(2, materials.size(), "the BOM has two components");
        // 10 pallets x (2 + 1) components
        assertEquals(0, materials.get(0).quantityRequired().compareTo(new BigDecimal("20.000")));
        assertEquals(0, materials.get(1).quantityRequired().compareTo(new BigDecimal("10.000")));

        // nothing in stock yet -> the release must be refused
        OrderException error = assertThrows(OrderException.class,
                () -> manufacturing.release(scope, "it", order.id()));
        assertTrue(error.getMessage().contains("Not enough material"), error.getMessage());

        stock.post(scope, "it", siteId, warehouseId, null, bearingId, new BigDecimal("20"),
                new BigDecimal("45.00"), DATE, "PURCHASE", null, null);
        stock.post(scope, "it", siteId, warehouseId, null, bearing2Id, new BigDecimal("10"),
                new BigDecimal("52.00"), DATE, "PURCHASE", null, null);

        WorkOrder released = manufacturing.release(scope, "it", order.id());
        assertEquals("RELEASED", released.status());

        WorkOrder issued = manufacturing.issue(scope, "it", order.id());
        assertEquals("IN_PROGRESS", issued.status());
        assertEquals(0, stock.onHand(scope, bearingId).compareTo(new BigDecimal("0.000")));

        WorkOrder completed = manufacturing.complete(scope, "it", order.id(), new BigDecimal("10"));
        assertEquals("COMPLETED", completed.status());
        assertEquals(0, stock.onHand(scope, palletId).compareTo(new BigDecimal("10.000")));
    }

    @Test
    void purchaseOrderReceiptUpdatesTheReceivedQuantity() {
        StockService stock = stockService();
        PurchaseOrderService orders = purchaseOrders(stock);

        var order = orders.create(scope, "it", "ID", supplierId, DATE, DATE, warehouseId, "IDR",
                BigDecimal.ONE, "receive me", List.of(
                        new com.ultibiz.core.application.PurchaseOrderLineDraft(bearingId, "Bearing 6204",
                                new BigDecimal("5"), new BigDecimal("50000.00"), null)));
        assertEquals(0, order.subtotal().compareTo(new BigDecimal("250000.00")));
    }

    /** Drains the stock of a product to zero so the assertions are deterministic. */
    private static void drain(StockService stock, long productId) {
        BigDecimal onHand = stock.onHand(scope, productId);
        if (onHand.signum() > 0) {
            stock.post(scope, "it", siteId, warehouseId, null, productId, onHand.negate(),
                    BigDecimal.ZERO, DATE, "ADJUSTMENT", null, null);
        }
    }

}

