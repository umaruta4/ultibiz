package com.ultibiz.core.integration;

import com.ultibiz.core.ai.AiProvider;
import com.ultibiz.core.ai.AiProviderConfig;
import com.ultibiz.core.ai.AiSecretCipher;
import com.ultibiz.core.ai.AiGateway;
import com.ultibiz.core.ai.AnthropicProvider;
import com.ultibiz.core.ai.EchoProvider;
import com.ultibiz.core.ai.KnowledgeService;
import com.ultibiz.core.ai.OllamaProvider;
import com.ultibiz.core.ai.OpenAiCompatibleProvider;
import com.ultibiz.core.application.AccountService;
import com.ultibiz.core.application.AiService;
import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.FxService;
import com.ultibiz.core.application.JournalService;
import com.ultibiz.core.application.NumberingService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.infrastructure.AccountRepository;
import com.ultibiz.core.infrastructure.AiRepository;
import com.ultibiz.core.infrastructure.AuditRepository;
import com.ultibiz.core.infrastructure.CountryConfigRepository;
import com.ultibiz.core.infrastructure.FiscalPeriodRepository;
import com.ultibiz.core.infrastructure.FxRateRepository;
import com.ultibiz.core.infrastructure.JournalRepository;
import com.ultibiz.core.infrastructure.KnowledgeRepository;
import com.ultibiz.core.infrastructure.NumberingRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
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
 * The assistant against a real MariaDB: the answer with its citations, the
 * write action that waits for a confirmation, the degraded mode when the
 * provider is unreachable and the "not found in the knowledge base" case.
 */
class AiIntegrationTest {

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static long orgId;
    private static long siteId;
    private static SiteScope scope;
    private static AiService assistant;
    private static KnowledgeService knowledge;
    private static AiRepository ai;

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
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        orgId = ((Number) dsl.fetchValue("SELECT id FROM organization WHERE code = 'ACME'")).longValue();
        siteId = ((Number) dsl.fetchValue("SELECT id FROM site WHERE code = 'JKT1'")).longValue();
        scope = new SiteScope(orgId, Set.of(siteId), true);

        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        AuditService audit = new AuditService(new AuditRepository(dsl));
        NumberingService numbering = new NumberingService(new NumberingRepository(jdbc), tx);
        JournalService journals = new JournalService(new JournalRepository(jdbc),
                new FiscalPeriodRepository(jdbc), new AccountRepository(jdbc),
                new CountryConfigRepository(dsl), numbering, audit,
                new FxService(new FxRateRepository(jdbc), List.of()), tx);
        AccountService accounts = new AccountService(new AccountRepository(jdbc));

        ai = new AiRepository(jdbc);
        knowledge = new KnowledgeService(jdbc);
        AiGateway gateway = new AiGateway(ai, new AiSecretCipher("test-key"), List.of(
                new EchoProvider(), new OpenAiCompatibleProvider(), new AnthropicProvider(),
                new OllamaProvider()));

        assistant = new AiService(gateway, ai, knowledge,
                List.of(new com.ultibiz.core.application.AccountingAiToolProvider(jdbc)),
                new com.ultibiz.core.application.AccountingAiToolProvider(jdbc),
                journals, accounts, audit);
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void theAssistantAnswersWithCitations() {
        // the seeded knowledge documents are indexed first
        KnowledgeRepository documents = new KnowledgeRepository(
                new JdbcTemplate(dataSource));
        for (var document : documents.documents(orgId)) {
            knowledge.index(scope, document.code(), document.title(), document.source(),
                    document.language(), document.content());
        }

        AiService.ChatResult result = assistant.chat(scope, "it", "admin",
                "Bagaimana penyusutan aset dihitung?", "/accounting/journals", null,
                Map.of(), null);

        assertFalse(result.degraded(), "the echo provider must answer");
        assertTrue(result.answer().contains("Echo:"), "the answer comes from the provider");
        assertTrue(result.citations().size() > 0, "the answer must cite the knowledge base");
        assertEquals("KB-0002", result.citations().get(0).documentCode());
    }

    @Test
    void aQuestionWithoutAnAnswerSaysSo() {
        KnowledgeService.Retrieval retrieval = knowledge.search(scope,
                "quantum entanglement of the ledger postings", 5, 0.62);
        assertFalse(retrieval.found(), "the knowledge base has no answer for this");
        assertEquals("I could not find an answer in the knowledge base.", KnowledgeService.NO_ANSWER);
    }

    @Test
    void aWriteActionWaitsForTheConfirmation() {
        var conversation = assistant.chat(scope, "it", "admin", "catat jurnal 1000",
                "/accounting/journals", null, Map.of(), null);

        var proposal = assistant.propose(scope, "it", conversation.conversationId(), "draft_journal",
                Map.of("description", "Adjustment from the assistant", "date", "2026-11-25",
                        "amount", "1000", "debitAccount", "1300", "creditAccount", "2100"));
        assertEquals("PROPOSED", proposal.status());
        assertTrue(proposal.diff().contains("1300"), "the diff shows the accounts");

        // nothing is written before the confirmation
        Number before = (Number) dsl.fetchValue("SELECT COUNT(*) FROM journal_entry");
        assertEquals(0, before.longValue());

        var confirmed = assistant.confirm(scope, "it", proposal.id());
        assertEquals("CONFIRMED", confirmed.status());

        Number after = (Number) dsl.fetchValue("SELECT COUNT(*) FROM journal_entry");
        assertEquals(1, after.longValue(), "the confirmed action posts one journal");

        // the audit carries the conversation id
        Number audited = (Number) dsl.fetchValue("""
                SELECT COUNT(*) FROM audit_log WHERE action = 'AI_ACTION_EXECUTED'
                  AND after_json LIKE ?
                """, "%conversationId%" + conversation.conversationId() + "%");
        assertEquals(1, audited.longValue(), "the audit must carry the AI conversation id");
    }

    @Test
    void aRejectedActionWritesNothing() {
        var conversation = assistant.chat(scope, "it", "admin", "catat jurnal lagi",
                "/accounting/journals", null, Map.of(), null);
        var proposal = assistant.propose(scope, "it", conversation.conversationId(), "draft_journal",
                Map.of("description", "Never posted", "amount", "500", "debitAccount", "1300",
                        "creditAccount", "2100"));
        assistant.reject(scope, "it", proposal.id());
        assertEquals("REJECTED", ai.toolCall(orgId, proposal.id()).status());
        Number journals = (Number) dsl.fetchValue(
                "SELECT COUNT(*) FROM journal_entry WHERE description = 'Never posted'");
        assertEquals(0, journals.longValue());
    }

    @Test
    void anUnreachableProviderDegradesTheAssistantOnly() {
        AiProviderConfig before = ai.config(orgId);
        ai.saveConfig(orgId, "OPENAI_COMPATIBLE", "http://localhost:9", "some-model", null,
                0.2, 1500, 256, false, null);
        try {
            AiService.ChatResult result = assistant.chat(scope, "it", "admin",
                    "apa kabar?", "/", null, Map.of(), null);
            assertTrue(result.degraded(), "the assistant must degrade, not fail");
            assertTrue(result.answer().contains("not available"), "the message is readable");
        } finally {
            ai.saveConfig(orgId, before.provider(), before.baseUrl(), before.model(),
                    before.apiKey(), before.temperature(), before.timeoutMs(), before.maxTokens(),
                    before.allowExternal(), before.systemPrompt());
        }
    }

    @Test
    void anExternalProviderIsRefusedWhenTheOrganisationSaysNo() {
        AiProviderConfig before = ai.config(orgId);
        ai.saveConfig(orgId, "OPENAI_COMPATIBLE", "http://localhost:9", "some-model", null,
                0.2, 1500, 256, false, null);
        try {
            AiService.ChatResult result = assistant.chat(scope, "it", "admin", "halo", "/", null,
                    Map.of(), null);
            assertTrue(result.degraded());
            assertTrue(result.answer().contains("external AI provider"),
                    "the policy message must be readable, was: " + result.answer());
        } finally {
            ai.saveConfig(orgId, before.provider(), before.baseUrl(), before.model(),
                    before.apiKey(), before.temperature(), before.timeoutMs(), before.maxTokens(),
                    before.allowExternal(), before.systemPrompt());
        }
    }

    @Test
    void theConversationIsStoredWithTheTokens() {
        var result = assistant.chat(scope, "it", "admin", "ringkasan stok", "/inventory", null,
                Map.of(), null);
        var messages = assistant.messages(scope, result.conversationId());
        assertTrue(messages.size() >= 2, "the question and the answer are stored");
        assertEquals("user", messages.get(0).role());
        assertEquals("assistant", messages.get(messages.size() - 1).role());
        assertTrue(messages.get(messages.size() - 1).promptTokens() > 0,
                "the token usage is tracked");
    }
}
