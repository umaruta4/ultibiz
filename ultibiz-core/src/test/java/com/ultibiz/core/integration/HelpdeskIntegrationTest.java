package com.ultibiz.core.integration;

import com.ultibiz.core.ai.KnowledgeService;
import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.HelpdeskService;
import com.ultibiz.core.application.KnowledgeVaultService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.infrastructure.AuditRepository;
import com.ultibiz.core.infrastructure.HelpdeskRepository;
import com.ultibiz.core.infrastructure.KnowledgeVaultRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.LocalDateTime;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Helpdesk against a real MariaDB: the intake with the SLA of the queue,
 * the triage, the assignment, the public reply, the resolution, the customer
 * portal link, the knowledge suggestions and the SLA monitor.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HelpdeskIntegrationTest {

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static JdbcTemplate jdbc;
    private static SiteScope scope;
    private static HelpdeskService helpdesk;
    private static KnowledgeVaultService vault;
    private static long ticketId;

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

        AuditService audit = new AuditService(new AuditRepository(dsl));
        vault = new KnowledgeVaultService(new KnowledgeVaultRepository(jdbc),
                new KnowledgeService(jdbc), audit);
        helpdesk = new HelpdeskService(new HelpdeskRepository(jdbc), vault, audit);
    }

    /** Is the gap between the two moments the expected number of hours (± 5 minutes)? */
    private static boolean withinHours(LocalDateTime from, LocalDateTime to, long hours) {
        long minutes = java.time.Duration.between(from, to).toMinutes();
        return Math.abs(minutes - hours * 60) <= 5;
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @Order(1)
    void theIntakeSetsTheSlaOfTheQueue() {
        long queueId = helpdesk.queues(scope).stream()
                .filter(queue -> "Q-SUPPORT".equals(queue.code())).findFirst().orElseThrow().id();
        ticketId = helpdesk.intake(scope, "it", "Printer on floor 2 is offline",
                "The printer does not respond since this morning.", "EQUIPMENT", queueId,
                "Dewi Ananda", "dewi@nusantara.example", null, "EMAIL", null);

        var ticket = helpdesk.ticket(scope, ticketId);
        assertEquals("NEW", ticket.status());
        assertEquals("NORMAL", ticket.priority());
        assertNotNull(ticket.slaResponseDue(), "the response deadline is set");
        assertNotNull(ticket.slaResolveDue(), "the resolution deadline is set");
        // the support queue answers in 8 hours and resolves in 72 (the deadline
        // is set by the database clock, so a small drift is allowed)
        assertTrue(withinHours(ticket.createdAt(), ticket.slaResponseDue(), 8),
                "the response deadline is 8 hours");
        assertTrue(withinHours(ticket.createdAt(), ticket.slaResolveDue(), 72),
                "the resolution deadline is 72 hours");
    }

    @Test
    @Order(2)
    void theUrgentWordsRaiseThePriority() {
        long queueId = helpdesk.queues(scope).get(0).id();
        long urgent = helpdesk.intake(scope, "it", "Server down in Jakarta",
                "Our server is down and the warehouse cannot work.", "INFRA", queueId,
                "Budi", "budi@nusantara.example", null, "EMAIL", null);
        assertEquals("URGENT", helpdesk.ticket(scope, urgent).priority());
    }

    @Test
    @Order(3)
    void theTicketIsTriagedAndAssigned() {
        long employeeId = ((Number) dsl.fetchValue(
                "SELECT id FROM hr_employee WHERE employee_no = 'EMP-0001'")).longValue();
        helpdesk.triage(scope, "it", ticketId, "HIGH", "EQUIPMENT", null);
        assertEquals("TRIAGED", helpdesk.ticket(scope, ticketId).status());
        assertEquals("HIGH", helpdesk.ticket(scope, ticketId).priority());

        helpdesk.assign(scope, "it", ticketId, employeeId);
        var ticket = helpdesk.ticket(scope, ticketId);
        assertEquals("ASSIGNED", ticket.status());
        assertEquals(employeeId, ticket.assigneeId());
    }

    @Test
    @Order(4)
    void thePublicReplyAnswersTheCustomer() {
        helpdesk.reply(scope, "it", ticketId, "We replaced the cable, the printer works again.",
                true);
        helpdesk.reply(scope, "it", ticketId, "Internal note: spare cable in cabinet 3.", false);

        var ticket = helpdesk.ticket(scope, ticketId);
        assertNotNull(ticket.firstResponseAt(), "the first response is stamped");

        var publicActivities = helpdesk.activities(scope, ticketId, true);
        assertEquals(1, publicActivities.size(), "the customer sees only the public reply");
        assertTrue(publicActivities.get(0).body().contains("cable"));
    }

    @Test
    @Order(5)
    void theTicketIsResolvedAndClosed() {
        helpdesk.resolve(scope, "it", ticketId);
        assertEquals("RESOLVED", helpdesk.ticket(scope, ticketId).status());
        helpdesk.close(scope, "it", ticketId);
        assertEquals("CLOSED", helpdesk.ticket(scope, ticketId).status());
        assertNotNull(helpdesk.ticket(scope, ticketId).closedAt());
    }

    @Test
    @Order(6)
    void theCustomerPortalShowsThePublicThread() {
        String token = helpdesk.portalToken(scope, ticketId);
        assertEquals(32, token.length());
        var portalTicket = helpdesk.portalTicket(token);
        assertEquals(ticketId, portalTicket.id());
        var publicActivities = helpdesk.activities(scope, ticketId, true);
        assertTrue(publicActivities.size() >= 1);
        assertFalse(helpdesk.activities(scope, ticketId, true).stream()
                .anyMatch(activity -> activity.body() != null
                        && activity.body().contains("Internal note")));
    }

    @Test
    @Order(7)
    void theKnowledgeSuggestionsComeFromTheVault() {
        long folderId = vault.createFolder(scope, "it", "HELPDESK", "Helpdesk", null);
        long documentId = vault.createDocument(scope, "it", "KV-HD-1", "Printer troubleshooting",
                "https://kb.example/printer", "id",
                "When the printer is offline, check the network cable and the power supply first.",
                folderId, null, List.of("procedure"));

        long queueId = helpdesk.queues(scope).get(0).id();
        long printerTicket = helpdesk.intake(scope, "it", "Printer is offline",
                "The printer does not respond, check the cable and the power.", "EQUIPMENT",
                queueId, "Dewi", "dewi@nusantara.example", null, "EMAIL", null);

        int suggestions = helpdesk.suggestKnowledge(scope, printerTicket);
        assertTrue(suggestions >= 1, "the vault document is suggested");
        assertTrue(helpdesk.suggestions(scope, printerTicket).stream()
                .anyMatch(suggestion -> suggestion.documentId() == documentId));
    }

    @Test
    @Order(8)
    void theSlaMonitorEscalatesTheOverdueTickets() {
        // make the ticket overdue
        jdbc.update("""
                UPDATE hd_ticket SET sla_resolve_due = DATE_SUB(NOW(), INTERVAL 1 DAY)
                WHERE id = ?
                """, ticketId);
        // reopen it so the monitor picks it up
        jdbc.update("UPDATE hd_ticket SET status = 'IN_PROGRESS', closed_at = NULL, "
                + "resolved_at = NULL WHERE id = ?", ticketId);

        int escalated = helpdesk.runSlaMonitor(scope, "it");
        assertTrue(escalated >= 1, "the overdue ticket is escalated");
        assertNotNull(helpdesk.ticket(scope, ticketId).escalatedAt());

        var state = helpdesk.slaState(scope, ticketId, LocalDateTime.now());
        assertTrue(state.resolveBreached(), "the SLA state shows the breach");
    }
}
