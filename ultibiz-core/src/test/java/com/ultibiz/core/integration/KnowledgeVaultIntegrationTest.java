package com.ultibiz.core.integration;

import com.ultibiz.core.ai.KnowledgeService;
import com.ultibiz.core.application.AuditService;
import com.ultibiz.core.application.KnowledgeVaultService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.infrastructure.AuditRepository;
import com.ultibiz.core.infrastructure.KnowledgeVaultRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Knowledge Vault against a real MariaDB: a document with its versions and
 * tags, the fulltext search, the RAG corpus (the assistant cites the
 * document) and the retention run with the legal hold.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeVaultIntegrationTest {

    private static HikariDataSource dataSource;
    private static DSLContext dsl;
    private static JdbcTemplate jdbc;
    private static SiteScope scope;
    private static KnowledgeVaultService vault;
    private static long documentId;

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
        vault = new KnowledgeVaultService(new KnowledgeVaultRepository(jdbc),
                new KnowledgeService(jdbc), new AuditService(new AuditRepository(dsl)));
    }

    @AfterAll
    static void closeDatabase() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    @Order(1)
    void aDocumentGetsItsVersionAndItsCorpus() {
        long folderId = vault.createFolder(scope, "it", "OPS-IT", "Operations", null);
        documentId = vault.createDocument(scope, "it", "KV-0001", "Cash handling",
                "https://kb.example/cash", "id",
                "The cash count is done twice a day by two employees.", folderId, null,
                List.of("procedure"));

        assertEquals(1, vault.document(scope, documentId).currentVersion());
        assertEquals(1, vault.versions(scope, documentId).size());
        assertTrue(vault.tagsOf(documentId).stream().anyMatch(tag -> "procedure".equals(tag.name())));

        // the corpus holds the chunks of the document (the assistant cites them)
        Number chunks = (Number) dsl.fetchValue(
                "SELECT COUNT(*) FROM knowledge_chunk WHERE document_id = ?", documentId);
        assertTrue(chunks.intValue() >= 1, "the document is chunked into the RAG corpus");
    }

    @Test
    @Order(2)
    void aNewVersionReIndexesTheCorpus() {
        int version = vault.addVersion(scope, "it", documentId, "Cash handling",
                "The cash count is done twice a day by two employees. The second count is "
                        + "confirmed by the supervisor in the cash book.", "clarified");
        assertEquals(2, version);
        assertEquals(2, vault.versions(scope, documentId).size());
        assertEquals(2, vault.document(scope, documentId).currentVersion());

        Number chunks = (Number) dsl.fetchValue(
                "SELECT COUNT(*) FROM knowledge_chunk WHERE document_id = ?", documentId);
        assertTrue(chunks.intValue() >= 1, "the corpus was re-indexed");
    }

    @Test
    @Order(3)
    void theFulltextSearchFindsTheDocument() {
        var hits = vault.search(scope, "cash count", 10);
        assertTrue(hits.stream().anyMatch(version -> version.documentId() == documentId),
                "the search finds the document");
        assertTrue(vault.search(scope, "zzzznotfound", 10).isEmpty(),
                "a query without a hit answers nothing");
    }

    @Test
    @Order(4)
    void theRetentionRespectsTheLegalHold() {
        // a policy that expires everything at once
        vault.createRetentionPolicy(scope, "it", "RET-ALL", "Everything", null, 0, "ARCHIVE");
        vault.setLegalHold(scope, "it", documentId, true);

        var result = vault.runRetention(scope, "it");
        assertEquals(1, result.held(), "the legal hold kept the document");
        assertTrue(vault.document(scope, documentId).archivedAt() == null,
                "a document on legal hold is not archived");

        // without the hold the policy archives it
        vault.setLegalHold(scope, "it", documentId, false);
        var second = vault.runRetention(scope, "it");
        assertTrue(second.archived() >= 1, "the expired document is archived");
        assertTrue(vault.document(scope, documentId).archivedAt() != null);
        assertFalse(vault.document(scope, documentId).legalHold());
    }

    @Test
    @Order(5)
    void anArchivedDocumentLeavesTheSearch() {
        assertTrue(vault.search(scope, "cash count", 10).stream()
                .noneMatch(version -> version.documentId() == documentId),
                "an archived document is no longer searchable");
    }
}
