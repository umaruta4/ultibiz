package com.ultibiz.core.infrastructure;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The Knowledge Vault persistence: folders, tags, versions and retention policies. */
@Repository
public class KnowledgeVaultRepository {

    private final JdbcTemplate jdbc;

    public KnowledgeVaultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static long id(Object value) {
        return ((Number) value).longValue();
    }

    // ------------------------------------------------------------------ records

    public record Folder(long id, String code, String name, Long parentId) {
    }

    public record Tag(long id, String name) {
    }

    public record Version(long id, long documentId, int versionNo, String title, String content,
                          String note, String author, LocalDate createdAt) {
    }

    public record RetentionPolicy(long id, String code, String name, Long folderId,
                                  int retainMonths, String action, boolean active) {
    }

    /** A document of the vault with its version counter. */
    public record VaultDocument(long id, String code, String title, String source,
                                Long folderId, int currentVersion, boolean legalHold,
                                LocalDate archivedAt, LocalDate updatedAt) {
    }

    // ------------------------------------------------------------------ folders

    public long createFolder(long organizationId, String code, String name, Long parentId) {
        jdbc.update("""
                INSERT INTO kv_folder (organization_id, code, name, parent_id) VALUES (?, ?, ?, ?)
                """, organizationId, code, name, parentId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<Folder> folders(long organizationId) {
        return jdbc.query("""
                SELECT * FROM kv_folder WHERE organization_id = ? ORDER BY code
                """, (rs, n) -> new Folder(id(rs.getObject("id")), rs.getString("code"),
                        rs.getString("name"),
                        rs.getObject("parent_id") == null ? null : id(rs.getObject("parent_id"))),
                organizationId);
    }

    // ------------------------------------------------------------------ tags

    public long tagId(long organizationId, String name) {
        jdbc.update("""
                INSERT IGNORE INTO kv_tag (organization_id, name) VALUES (?, ?)
                """, organizationId, name);
        Long tagId = jdbc.queryForObject("""
                SELECT id FROM kv_tag WHERE organization_id = ? AND name = ?
                """, Long.class, organizationId, name);
        return tagId;
    }

    public void tagDocument(long documentId, long tagId) {
        jdbc.update("""
                INSERT IGNORE INTO kv_document_tag (document_id, tag_id) VALUES (?, ?)
                """, documentId, tagId);
    }

    public List<Tag> tagsOfDocument(long documentId) {
        return jdbc.query("""
                SELECT t.* FROM kv_tag t JOIN kv_document_tag dt ON dt.tag_id = t.id
                WHERE dt.document_id = ? ORDER BY t.name
                """, (rs, n) -> new Tag(id(rs.getObject("id")), rs.getString("name")), documentId);
    }

    public List<Tag> tags(long organizationId) {
        return jdbc.query("""
                SELECT * FROM kv_tag WHERE organization_id = ? ORDER BY name
                """, (rs, n) -> new Tag(id(rs.getObject("id")), rs.getString("name")),
                organizationId);
    }

    // ------------------------------------------------------------------ documents

    public long createDocument(long organizationId, String code, String title,
                               String source, String language, String content, Long folderId,
                               Long retentionPolicyId, String author) {
        jdbc.update("""
                INSERT INTO knowledge_document (organization_id, code, title, source,
                    language, content, folder_id, retention_policy_id, current_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1)
                """, organizationId, code, title, source, language, content, folderId,
                retentionPolicyId);
        return jdbc.queryForObject("""
                SELECT id FROM knowledge_document WHERE organization_id = ? AND code = ?
                """, Long.class, organizationId, code);
    }

    public Optional<VaultDocument> document(long organizationId, long documentId) {
        List<VaultDocument> rows = jdbc.query("""
                SELECT * FROM knowledge_document WHERE organization_id = ? AND id = ?
                """, (rs, n) -> new VaultDocument(id(rs.getObject("id")), rs.getString("code"),
                        rs.getString("title"), rs.getString("source"),
                        rs.getObject("folder_id") == null ? null : id(rs.getObject("folder_id")),
                        rs.getInt("current_version"), rs.getBoolean("legal_hold"),
                        rs.getDate("archived_at") == null ? null
                                : rs.getDate("archived_at").toLocalDate(),
                        rs.getTimestamp("updated_at") == null ? null
                                : rs.getTimestamp("updated_at").toLocalDateTime().toLocalDate()),
                organizationId, documentId);
        return rows.stream().findFirst();
    }

    public List<VaultDocument> documents(long organizationId, Long folderId) {
        if (folderId == null) {
            return jdbc.query("""
                    SELECT * FROM knowledge_document WHERE organization_id = ?
                    ORDER BY code
                    """, (rs, n) -> mapDocument(rs), organizationId);
        }
        return jdbc.query("""
                SELECT * FROM knowledge_document WHERE organization_id = ? AND folder_id = ?
                ORDER BY code
                """, (rs, n) -> mapDocument(rs), organizationId, folderId);
    }

    private VaultDocument mapDocument(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new VaultDocument(id(rs.getObject("id")), rs.getString("code"), rs.getString("title"),
                rs.getString("source"),
                rs.getObject("folder_id") == null ? null : id(rs.getObject("folder_id")),
                rs.getInt("current_version"), rs.getBoolean("legal_hold"),
                rs.getDate("archived_at") == null ? null : rs.getDate("archived_at").toLocalDate(),
                rs.getTimestamp("updated_at") == null ? null
                        : rs.getTimestamp("updated_at").toLocalDateTime().toLocalDate());
    }

    public void bumpVersion(long documentId, String title, String content, int version) {
        jdbc.update("""
                UPDATE knowledge_document SET title = ?, content = ?, current_version = ?
                WHERE id = ?
                """, title, content, version, documentId);
    }

    public void setLegalHold(long documentId, boolean hold) {
        jdbc.update("UPDATE knowledge_document SET legal_hold = ? WHERE id = ?", hold, documentId);
    }

    public void archive(long documentId) {
        jdbc.update("UPDATE knowledge_document SET archived_at = CURRENT_TIMESTAMP(6) WHERE id = ?",
                documentId);
    }

    public void deleteDocument(long documentId) {
        jdbc.update("DELETE FROM knowledge_chunk WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM kv_version WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM kv_document_tag WHERE document_id = ?", documentId);
        jdbc.update("DELETE FROM knowledge_document WHERE id = ?", documentId);
    }

    /** The documents a retention policy touches; the legal hold is decided by the service. */
    public List<VaultDocument> documentsForRetention(long organizationId, Long folderId,
                                                      int retainMonths) {
        String filter = folderId == null ? "" : " AND (folder_id = ? OR folder_id IS NULL)";
        Object[] args = folderId == null
                ? new Object[]{organizationId, retainMonths}
                : new Object[]{organizationId, retainMonths, folderId};
        return jdbc.query("""
                SELECT * FROM knowledge_document
                WHERE organization_id = ? AND archived_at IS NULL
                  AND TIMESTAMPDIFF(MONTH, updated_at, NOW()) >= ?
                """ + filter + " ORDER BY code", (rs, n) -> mapDocument(rs), args);
    }

    // ------------------------------------------------------------------ versions

    public void addVersion(long organizationId, long documentId, int versionNo, String title,
                           String content, String note, String author) {
        jdbc.update("""
                INSERT INTO kv_version (organization_id, document_id, version_no, title, content,
                    note, author)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, organizationId, documentId, versionNo, title, content, note, author);
    }

    public List<Version> versions(long documentId) {
        return jdbc.query("""
                SELECT * FROM kv_version WHERE document_id = ? ORDER BY version_no DESC
                """, (rs, n) -> new Version(id(rs.getObject("id")), id(rs.getObject("document_id")),
                        rs.getInt("version_no"), rs.getString("title"), rs.getString("content"),
                        rs.getString("note"), rs.getString("author"),
                        rs.getDate("created_at").toLocalDate()), documentId);
    }

    /** The fulltext search over the versions (MariaDB boolean mode). */
    public List<Version> searchVersions(long organizationId, String fulltextQuery, int limit) {
        return jdbc.query("""
                SELECT v.* FROM kv_version v
                JOIN knowledge_document d ON d.id = v.document_id
                WHERE v.organization_id = ? AND d.archived_at IS NULL
                  AND MATCH(v.title, v.content) AGAINST (? IN BOOLEAN MODE)
                ORDER BY MATCH(v.title, v.content) AGAINST (? IN BOOLEAN MODE) DESC
                LIMIT ?
                """, (rs, n) -> new Version(id(rs.getObject("id")), id(rs.getObject("document_id")),
                        rs.getInt("version_no"), rs.getString("title"), rs.getString("content"),
                        rs.getString("note"), rs.getString("author"),
                        rs.getDate("created_at").toLocalDate()),
                organizationId, fulltextQuery, fulltextQuery, Math.max(1, limit));
    }

    // ------------------------------------------------------------------ retention

    public List<RetentionPolicy> retentionPolicies(long organizationId) {
        return jdbc.query("""
                SELECT * FROM kv_retention_policy WHERE organization_id = ? ORDER BY code
                """, (rs, n) -> new RetentionPolicy(id(rs.getObject("id")), rs.getString("code"),
                        rs.getString("name"),
                        rs.getObject("folder_id") == null ? null : id(rs.getObject("folder_id")),
                        rs.getInt("retain_months"), rs.getString("action"),
                        rs.getBoolean("is_active")), organizationId);
    }

    public long createRetentionPolicy(long organizationId, String code, String name,
                                      Long folderId, int retainMonths, String action) {
        jdbc.update("""
                INSERT INTO kv_retention_policy (organization_id, code, name, folder_id,
                    retain_months, action)
                VALUES (?, ?, ?, ?, ?, ?)
                """, organizationId, code, name, folderId, retainMonths, action);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
