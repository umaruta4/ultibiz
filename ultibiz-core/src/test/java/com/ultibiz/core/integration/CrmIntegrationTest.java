package com.ultibiz.core.integration;

import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.CrmService;
import com.ultibiz.core.application.GoodsReceiptService;
import com.ultibiz.core.application.NumberingService;
import com.ultibiz.core.application.PartyService;
import com.ultibiz.core.application.SalesOrderLineDraft;
import com.ultibiz.core.application.SalesOrderService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.StockService;
import com.ultibiz.core.application.TaxEngine;
import com.ultibiz.core.crm.CrmException;
import com.ultibiz.core.infrastructure.AuditRepository;
import com.ultibiz.core.infrastructure.CrmRepository;
import com.ultibiz.core.infrastructure.GoodsReceiptRepository;
import com.ultibiz.core.infrastructure.NumberingRepository;
import com.ultibiz.core.infrastructure.PartyRepository;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CRM plugin against a real MariaDB, end to end: a lead is qualified and
 * converted (the customer party is created in the master data), the
 * opportunity moves along the pipeline with its rules, and the quote hand-off
 * creates a real draft sales order — then the deal is won.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrmIntegrationTest {

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static JdbcTemplate jdbc;
    private static SiteScope scope;
    private static CrmService crm;
    private static long leadId;
    private static long opportunityId;

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
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        dsl = DSL.using(dataSource, SQLDialect.MARIADB);
        jdbc = new JdbcTemplate(dataSource);

        long orgId = ((Number) dsl.fetchValue(
                "SELECT id FROM organization WHERE code = 'ACME'")).longValue();
        long siteId = ((Number) dsl.fetchValue(
                "SELECT id FROM site WHERE code = 'JKT1'")).longValue();
        scope = new SiteScope(orgId, Set.of(siteId), true);

        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        AuditService audit = new AuditService(new AuditRepository(dsl));
        PartyRepository partyRepository = new PartyRepository(dsl);
        PartyService parties = new PartyService(partyRepository, audit);
        TaxRuleRepository taxRules = new TaxRuleRepository(dsl);
        var lookup = new DatabaseTaxRateLookup(taxRules);
        TaxEngine taxEngine = new TaxEngine(List.of(new IndonesiaTaxPack(lookup),
                new EuVatTaxPack(lookup), new DefaultTaxPack(lookup)));
        NumberingService numbering = new NumberingService(new NumberingRepository(jdbc), tx);
        StockService stock = new StockService(new StockRepository(jdbc), tx);
        GoodsReceiptService receipts = new GoodsReceiptService(
                new GoodsReceiptRepository(jdbc), stock, numbering, audit);
        SalesOrderService salesOrders = new SalesOrderService(new SalesOrderRepository(jdbc),
                partyRepository, taxRules, taxEngine, numbering, receipts, audit);
        crm = new CrmService(new CrmRepository(jdbc), parties, salesOrders, audit);
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @Order(1)
    void aLeadQualifiesAndConverts() {
        leadId = crm.createLead(scope, "it", "LEAD-IT1", "PT Maju Bersama", "Pak Andi",
                "andi@majubersama.example", null, "WEB", null, null);

        // no contact channel → cannot qualify
        long emptyLead = crm.createLead(scope, "it", "LEAD-IT2", "PT Sepi", null, null,
                null, "WEB", null, null);
        assertThrows(CrmException.class, () -> crm.qualifyLead(scope, "it", emptyLead));
        crm.loseLead(scope, "it", emptyLead, "no contact possible");

        // converting before qualifying is refused
        assertThrows(CrmException.class, () -> crm.convertLead(scope, "it", leadId,
                "rollout", new BigDecimal("25000000"), null));

        crm.qualifyLead(scope, "it", leadId);
        opportunityId = crm.convertLead(scope, "it", leadId, "ERP rollout",
                new BigDecimal("25000000"), LocalDate.parse("2026-12-31"));

        var lead = crm.lead(scope, leadId);
        assertEquals("CONVERTED", lead.status());
        assertNotNull(lead.partyId(), "the conversion created the customer party");
        // the party really exists in the master data
        Number parties = (Number) dsl.fetchValue(
                "SELECT COUNT(*) FROM party WHERE id = ? AND party_type = 'CUSTOMER'",
                lead.partyId());
        assertEquals(1, parties.intValue());

        var opportunity = crm.opportunity(scope, opportunityId);
        assertEquals("OPEN", opportunity.status());
        assertEquals("NEW", crm.stages(scope).stream()
                .filter(s -> s.id() == opportunity.stageId()).findFirst().orElseThrow().code());
    }

    @Test
    @Order(2)
    void thePipelineMovesForwardOnly() {
        crm.moveOpportunity(scope, "it", opportunityId, "QUALIFIED", null);
        assertThrows(CrmException.class, () -> crm.moveOpportunity(scope, "it", opportunityId,
                "NEW", null), "the pipeline does not move backwards");
        crm.moveOpportunity(scope, "it", opportunityId, "PROPOSAL", null);

        // the weighted pipeline carries 25,000,000 at 50 %
        assertEquals(new BigDecimal("12500000.00"), crm.weightedPipeline(scope));

        // losing needs a reason
        assertThrows(CrmException.class, () -> crm.moveOpportunity(scope, "it", opportunityId,
                "LOST", null));
    }

    @Test
    @Order(3)
    void theQuoteHandOffCreatesASalesOrder() {
        long orderId = crm.handOffToSales(scope, "it", opportunityId, null);

        // a real draft sales order with one line at the opportunity amount
        Number lines = (Number) dsl.fetchValue("""
                SELECT COUNT(*) FROM sales_order_line l JOIN sales_order o ON o.id = l.order_id
                WHERE o.id = ? AND l.unit_price = 25000000.00
                """, orderId);
        assertEquals(1, lines.intValue());
        assertEquals("DRAFT", dsl.fetchValue(
                "SELECT status FROM sales_order WHERE id = ?", orderId));

        var opportunity = crm.opportunity(scope, opportunityId);
        assertEquals(orderId, opportunity.salesOrderId());

        // a second hand-off is refused
        assertThrows(CrmException.class, () -> crm.handOffToSales(scope, "it", opportunityId,
                null));
    }

    @Test
    @Order(4)
    void theDealIsWonAndThePipelineCloses() {
        crm.moveOpportunity(scope, "it", opportunityId, "NEGOTIATION", null);
        crm.moveOpportunity(scope, "it", opportunityId, "WON", null);
        assertEquals("WON", crm.opportunity(scope, opportunityId).status());
        assertEquals(BigDecimal.ZERO, crm.weightedPipeline(scope),
                "a won deal leaves the open pipeline");
        assertThrows(CrmException.class, () -> crm.moveOpportunity(scope, "it", opportunityId,
                "NEW", null), "a closed opportunity does not move");
    }

    @Test
    @Order(5)
    void theActivitiesAreTracked() {
        long activityId = crm.logActivity(scope, "it", opportunityId, null, null, "MEETING",
                "Go-live planning", LocalDate.now().plusDays(7), "bring the rollout plan");
        assertTrue(crm.activities(scope, true).stream().anyMatch(a -> a.id() == activityId));
        crm.completeActivity(scope, "it", activityId);
        assertTrue(crm.activities(scope, true).stream().noneMatch(a -> a.id() == activityId));
    }
}
