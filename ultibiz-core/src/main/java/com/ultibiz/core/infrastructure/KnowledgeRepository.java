package com.ultibiz.core.infrastructure;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The documents of the knowledge vault. */
@Repository
public class KnowledgeRepository {

    /** One knowledge document. */
    public record Document(long id, String code, String title, String source, String language,
                           String content) {
    }

    private final JdbcTemplate jdbc;

    public KnowledgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Document> documents(long organizationId) {
        return jdbc.query("""
                SELECT id, code, title, source, language, content
                FROM knowledge_document WHERE organization_id = ? AND is_active = TRUE
                ORDER BY code
                """, (rs, n) -> new Document(rs.getLong("id"), rs.getString("code"),
                rs.getString("title"), rs.getString("source"), rs.getString("language"),
                rs.getString("content")), organizationId);
    }

    /** The documents that have no chunk yet. */
    public List<Document> unindexed(long organizationId) {
        return jdbc.query("""
                SELECT d.id, d.code, d.title, d.source, d.language, d.content
                FROM knowledge_document d
                WHERE d.organization_id = ? AND d.is_active = TRUE
                  AND NOT EXISTS (SELECT 1 FROM knowledge_chunk c WHERE c.document_id = d.id)
                ORDER BY d.code
                """, (rs, n) -> new Document(rs.getLong("id"), rs.getString("code"),
                rs.getString("title"), rs.getString("source"), rs.getString("language"),
                rs.getString("content")), organizationId);
    }
}
