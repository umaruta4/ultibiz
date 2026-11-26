package com.ultibiz.web.web;

import com.ultibiz.core.ai.KnowledgeService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.infrastructure.KnowledgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Indexes the knowledge documents that have no chunks yet, so the assistant
 * can answer with citations right after the deployment.
 */
@Component
public class KnowledgeIndexer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeIndexer.class);

    private final KnowledgeService knowledge;
    private final KnowledgeRepository documents;
    private final JdbcTemplate jdbc;

    public KnowledgeIndexer(KnowledgeService knowledge, KnowledgeRepository documents,
                            JdbcTemplate jdbc) {
        this.knowledge = knowledge;
        this.documents = documents;
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Long organizationId = jdbc.queryForObject(
                    "SELECT id FROM organization WHERE code = 'ACME'", Long.class);
            if (organizationId == null) {
                return;
            }
            for (var document : documents.unindexed(organizationId)) {
                int chunks = knowledge.index(new SiteScope(organizationId, java.util.Set.of(), true),
                        document.code(), document.title(), document.source(), document.language(),
                        document.content());
                if (chunks > 0) {
                    LOG.info("Indexed the knowledge document {} ({} chunks)", document.code(), chunks);
                }
            }
        } catch (Exception ex) {
            LOG.warn("The knowledge vault could not be indexed: {}", ex.getMessage());
        }
    }
}
