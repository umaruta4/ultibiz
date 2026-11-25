package com.ultibiz.core.integration;

import com.ultibiz.core.application.AccountService;
import com.ultibiz.core.application.ApService;
import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.ExchangeService;
import com.ultibiz.core.application.FxService;
import com.ultibiz.core.application.GoodsReceiptService;
import com.ultibiz.core.application.JournalService;
import com.ultibiz.core.application.NumberingService;
import com.ultibiz.core.application.SalesOrderService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.StockService;
import com.ultibiz.core.application.TaxEngine;
import com.ultibiz.core.exchange.CanonicalDocument;
import com.ultibiz.core.exchange.ExchangeLine;
import com.ultibiz.core.exchange.ExchangeMessage;
import com.ultibiz.core.exchange.ExchangeParty;
import com.ultibiz.core.exchange.csv.CsvAdapter;
import com.ultibiz.core.exchange.edi.EdifactAdapter;
import com.ultibiz.core.exchange.edi.X12Adapter;
import com.ultibiz.core.exchange.peppol.PeppolAdapter;
import com.ultibiz.core.exchange.rest.RestJsonAdapter;
import com.ultibiz.core.infrastructure.AccountRepository;
import com.ultibiz.core.infrastructure.ApRepository;
import com.ultibiz.core.infrastructure.AuditRepository;
import com.ultibiz.core.infrastructure.BankRepository;
import com.ultibiz.core.infrastructure.CountryConfigRepository;
import com.ultibiz.core.infrastructure.ExchangeRepository;
import com.ultibiz.core.infrastructure.FiscalPeriodRepository;
import com.ultibiz.core.infrastructure.FxRateRepository;
import com.ultibiz.core.infrastructure.GoodsReceiptRepository;
import com.ultibiz.core.infrastructure.JournalRepository;
import com.ultibiz.core.infrastructure.MatchRuleRepository;
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
import java.time.LocalDateTime;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The data-exchange engine against a real MariaDB: the idempotent reception,
 * the validation gate with its rule ids, the retry/backoff and the dead-letter
 * queue, the operator replay and the outbound loopback.
 */
class ExchangeIntegrationTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 25);

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static long orgId;
    private static long siteId;
    private static SiteScope scope;
    private static ExchangeService exchange;
    private static TransactionTemplate tx;
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

        orgId = ((Number) dsl.fetchValue("SELECT id FROM organization WHERE code = 'ACME'")).longValue();
        siteId = ((Number) dsl.fetchValue("SELECT id FROM site WHERE code = 'JKT1'")).longValue();
        scope = new SiteScope(orgId, Set.of(siteId), true);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        jdbc = new JdbcTemplate(dataSource);
        NumberingService numbering = new NumberingService(new NumberingRepository(jdbc), tx);
        AuditService audit = new AuditService(new AuditRepository(dsl));
        JournalService journals = new JournalService(new JournalRepository(jdbc),
                new FiscalPeriodRepository(jdbc), new AccountRepository(jdbc),
                new CountryConfigRepository(dsl), numbering,
                audit, new FxService(new FxRateRepository(jdbc), List.of()), tx);
        AccountService accounts = new AccountService(new AccountRepository(jdbc));
        StockService stock = new StockService(new StockRepository(jdbc), tx);
        GoodsReceiptService receipts = new GoodsReceiptService(new GoodsReceiptRepository(jdbc), stock,
                numbering, audit);
        TaxRuleRepository taxRules = new TaxRuleRepository(dsl);
        var lookup = new DatabaseTaxRateLookup(taxRules);
        TaxEngine taxEngine = new TaxEngine(List.of(new IndonesiaTaxPack(lookup), new EuVatTaxPack(lookup),
                new DefaultTaxPack(lookup)));
        PartyRepository parties = new PartyRepository(dsl);
        SalesOrderService sales = new SalesOrderService(new SalesOrderRepository(jdbc), parties,
                taxRules, taxEngine, numbering, receipts, audit);
        ApService ap = new ApService(new ApRepository(jdbc), accounts, journals,
                new PurchaseOrderRepository(jdbc), new GoodsReceiptRepository(jdbc),
                new MatchRuleRepository(jdbc), numbering, new BankRepository(jdbc), audit);

        exchange = new ExchangeService(new ExchangeRepository(jdbc),
                List.of(new RestJsonAdapter(), new CsvAdapter(), new PeppolAdapter(),
                        new X12Adapter(), new EdifactAdapter()),
                audit, parties, ap, sales, tx);
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private static String invoiceJson() {
        return """
                {
                  "type": "INVOICE",
                  "documentNumber": "EXT-INV-001",
                  "issueDate": "2026-11-25",
                  "dueDate": "2026-12-25",
                  "currency": "IDR",
                  "seller": { "partyCode": "SUPP-0001", "name": "CV Baja Nusantara" },
                  "buyer": { "name": "Acme" },
                  "lines": [
                    { "productCode": "BRG-0001", "description": "Bearing",
                      "quantity": 10, "unitPrice": 45000, "taxCode": "EXEMPT", "taxPercent": 0 }
                  ]
                }
                """;
    }

    private long countMessages(String idempotencyKey) {
        Number n = ((Number) dsl.fetchValue(
                "SELECT COUNT(*) FROM exchange_message WHERE idempotency_key = ?", idempotencyKey))
                .longValue();
        return n == null ? 0L : n.longValue();
    }

    private long countBills() {
        Number n = ((Number) dsl.fetchValue("SELECT COUNT(*) FROM ap_bill")).longValue();
        return n == null ? 0L : n.longValue();
    }

    @Test
    void aDuplicateDeliveryAppliesTheDocumentExactlyOnce() {
        ExchangeService.ReceiveResult first =
                exchange.receive(scope, "it", "SIM-REST", "KEY-1", invoiceJson(), "JSON");
        assertFalse(first.duplicate());

        ExchangeService.ReceiveResult second =
                exchange.receive(scope, "it", "SIM-REST", "KEY-1", invoiceJson(), "JSON");
        assertTrue(second.duplicate(), "the same idempotency key must not create a second message");
        assertEquals(first.messageId(), second.messageId());
        assertEquals(1, countMessages("KEY-1"));

        exchange.processInbound(scope, "it", first.messageId());
        ExchangeMessage applied = exchange.byId(scope, first.messageId());
        assertEquals(ExchangeMessage.APPLIED, applied.status(),
                "the message must reach APPLIED, was " + applied.status());
        assertEquals("ap_bill", applied.appliedDocumentType());
        assertEquals(1, countBills(), "exactly one bill for a duplicated delivery");

        // processing it again changes nothing
        exchange.processInbound(scope, "it", first.messageId());
        assertEquals(1, countBills());
        assertEquals(1, countMessages("KEY-1"));
    }

    @Test
    void anInvalidDocumentIsRejectedWithTheRuleId() {
        String broken = """
                { "type": "INVOICE", "documentNumber": "EXT-INV-BAD", "issueDate": "2026-11-25",
                  "currency": "IDR",
                  "seller": { "partyCode": "SUPP-0001", "name": "CV Baja Nusantara" },
                  "buyer": { "name": "Acme" },
                  "lines": [] }
                """;
        var result = exchange.receive(scope, "it", "SIM-REST", "KEY-BAD", broken, "JSON");
        exchange.processInbound(scope, "it", result.messageId());
        ExchangeMessage message = exchange.byId(scope, result.messageId());
        assertEquals(ExchangeMessage.REJECTED, message.status());
        assertTrue(message.validationReport().contains("BR-16"),
                "the report must name the rule, was " + message.validationReport());
    }

    @Test
    void aBrokenPayloadIsRetriedThenDiesAndCanBeReplayed() {
        var result = exchange.receive(scope, "it", "SIM-REST", "KEY-BROKEN", "{not json", "JSON");
        exchange.processInbound(scope, "it", result.messageId());
        ExchangeMessage message = exchange.byId(scope, result.messageId());
        assertEquals(ExchangeMessage.FAILED, message.status());
        assertEquals(1, message.attempts());

        // the backoff is in the future: nothing is due yet
        assertEquals(0, exchange.retryDue(scope, "it"));

        // make it due and retry until the message reaches the dead-letter queue
        for (int attempt = 0; attempt < 6; attempt++) {
            jdbc.update("UPDATE exchange_message SET next_retry_at = ? WHERE id = ?",
                    java.sql.Timestamp.valueOf(LocalDateTime.now().minusMinutes(5)),
                    result.messageId());
            exchange.retryDue(scope, "it");
            message = exchange.byId(scope, result.messageId());
            if (ExchangeMessage.DEAD.equals(message.status())) {
                break;
            }
        }
        assertEquals(ExchangeMessage.DEAD, message.status(),
                "after the maximum attempts the message must be dead, was " + message.status());

        exchange.replay(scope, "it", result.messageId());
        assertEquals(ExchangeMessage.RECEIVED, exchange.byId(scope, result.messageId()).status());
    }

    @Test
    void anOutboundDocumentLoopsBackIntoTheInbox() {
        CanonicalDocument document = new CanonicalDocument(CanonicalDocument.ORDER, "SO-OUT-1",
                DATE, null, "IDR",
                new ExchangeParty(null, "Acme", "0192:987654321", null, null, null, null, "ID"),
                new ExchangeParty("CUST-0002", "PT Samudera Distribusi", "0192:123456789", null,
                        null, null, null, "ID"),
                List.of(new ExchangeLine("BRG-0001", "Bearing", new BigDecimal("10"),
                        new BigDecimal("45000"), "S", new BigDecimal("11"))),
                null, List.of());

        long id = exchange.queueOutbound(scope, "it", "SIM-REST", document);
        assertEquals(ExchangeMessage.QUEUED, exchange.byId(scope, id).status());

        exchange.sendOutbound(scope, "it", id);
        assertEquals(ExchangeMessage.SENT, exchange.byId(scope, id).status());

        ExchangeMessage inbound = exchange.search(scope, null, "INBOUND", null, 0, 10).items()
                .stream()
                .filter(m -> "ORDER:SO-OUT-1-loop".equals(m.idempotencyKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the loopback did not create an inbound message"));
        assertEquals(ExchangeMessage.RECEIVED, inbound.status());

        // and it becomes the outbound document once it is processed
        exchange.processInbound(scope, "it", inbound.id());
        assertEquals("SO-OUT-1", exchange.byId(scope, inbound.id()).documentNumber());
    }

    @Test
    void aCsvPayloadBecomesADraftBill() {
        String csv = "INVOICE;EXT-CSV-1;2026-11-25;IDR;SUPP-0001;CV Baja Nusantara;;Acme;"
                + "BRG-0001;Bearing;4;25000;EXEMPT;0\n";
        var result = exchange.receive(scope, "it", "SIM-CSV", "KEY-CSV", csv, "CSV");
        exchange.processInbound(scope, "it", result.messageId());
        ExchangeMessage message = exchange.byId(scope, result.messageId());
        assertEquals(ExchangeMessage.APPLIED, message.status(), "was " + message.status()
                + " / " + message.error());
        assertEquals("ap_bill", message.appliedDocumentType());
    }
}
