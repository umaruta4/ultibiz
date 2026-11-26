package com.ultibiz.core.application;

import com.ultibiz.core.ai.KnowledgeService;
import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.infrastructure.KnowledgeVaultRepository;
import com.ultibiz.core.knowledge.KnowledgeException;
import com.ultibiz.core.knowledge.RetentionPolicy;
import com.ultibiz.core.knowledge.VersionPolicy;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Knowledge Vault: a document store with folders, tags and versions. The
 * current version is always the RAG corpus of the assistant — every new
 * version re-indexes it, and the citations point back at the document.
 */
@Service
public class KnowledgeVaultService {

    private final KnowledgeVaultRepository vault;
    private final KnowledgeService corpus;
    private final AuditService audit;

    public KnowledgeVaultService(KnowledgeVaultRepository vault, KnowledgeService corpus,
                                 AuditService audit) {
        this.vault = vault;
        this.corpus = corpus;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ documents

    /** Creates a document with its first version and indexes it into the corpus. */
    @Transactional
    public long createDocument(SiteScope scope, String actor, String code, String title,
                               String source, String language, String content, Long folderId,
                               Long retentionPolicyId, List<String> tags) {
        VersionPolicy.checkContent(title, content);
        long documentId = vault.createDocument(scope.organizationId(), code, title,
                source, language == null || language.isBlank() ? "id" : language, content,
                folderId, retentionPolicyId, actor);
        vault.addVersion(scope.organizationId(), documentId, 1, title, content, "First version",
                actor);
        applyTags(scope.organizationId(), documentId, tags);
        corpus.index(scope, code, title, source, language == null ? "id" : language, content);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "KV_DOCUMENT_CREATE",
                "knowledge_document", String.valueOf(documentId),
                "{\"code\":\"" + code + "\"}", null));
        return documentId;
    }

    /** Adds a new version: the corpus is re-indexed with the new content. */
    @Transactional
    public int addVersion(SiteScope scope, String actor, long documentId, String title,
                          String content, String note) {
        KnowledgeVaultRepository.VaultDocument document = document(scope, documentId);
        VersionPolicy.checkContent(title, content);
        int version = VersionPolicy.nextVersion(document.currentVersion());
        vault.addVersion(scope.organizationId(), documentId, version, title, content, note, actor);
        vault.bumpVersion(documentId, title, content, version);
        corpus.index(scope, document.code(), title, document.source(), "id", content);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "KV_DOCUMENT_VERSION",
                "knowledge_document", String.valueOf(documentId),
                "{\"version\":" + version + "}", null));
        return version;
    }

    /** The versions of a document, newest first. */
    public List<KnowledgeVaultRepository.Version> versions(SiteScope scope, long documentId) {
        return vault.versions(documentId);
    }

    /** The documents of the vault (optionally per folder). */
    public List<KnowledgeVaultRepository.VaultDocument> documents(SiteScope scope, Long folderId) {
        return vault.documents(scope.organizationId(), folderId);
    }

    public KnowledgeVaultRepository.VaultDocument document(SiteScope scope, long documentId) {
        return vault.document(scope.organizationId(), documentId)
                .orElseThrow(() -> new KnowledgeException("Unknown document: " + documentId));
    }

    public List<KnowledgeVaultRepository.Tag> tags(SiteScope scope) {
        return vault.tags(scope.organizationId());
    }

    public List<KnowledgeVaultRepository.Tag> tagsOf(long documentId) {
        return vault.tagsOfDocument(documentId);
    }

    // ------------------------------------------------------------------ search

    /** The fulltext search over the document versions. */
    public List<KnowledgeVaultRepository.Version> search(SiteScope scope, String query, int limit) {
        String fulltext = fulltextQuery(query);
        if (fulltext.isBlank()) {
            return List.of();
        }
        return vault.searchVersions(scope.organizationId(), fulltext, limit);
    }

    /** The query in the MariaDB boolean mode: the tokens as prefix terms. */
    static String fulltextQuery(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        StringBuilder fulltext = new StringBuilder();
        for (String token : query.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (token.isBlank() || token.length() < 2) {
                continue;
            }
            if (fulltext.length() > 0) {
                fulltext.append(' ');
            }
            fulltext.append(token).append('*');
        }
        return fulltext.toString();
    }

    // ------------------------------------------------------------------ folders

    @Transactional
    public long createFolder(SiteScope scope, String actor, String code, String name,
                             Long parentId) {
        long id = vault.createFolder(scope.organizationId(), code, name, parentId);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "KV_FOLDER_CREATE", "kv_folder",
                String.valueOf(id), "{\"code\":\"" + code + "\"}", null));
        return id;
    }

    public List<KnowledgeVaultRepository.Folder> folders(SiteScope scope) {
        return vault.folders(scope.organizationId());
    }

    // ------------------------------------------------------------------ legal hold

    @Transactional
    public void setLegalHold(SiteScope scope, String actor, long documentId, boolean hold) {
        document(scope, documentId);
        vault.setLegalHold(documentId, hold);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "KV_LEGAL_HOLD",
                "knowledge_document", String.valueOf(documentId),
                "{\"hold\":" + hold + "}", null));
    }

    // ------------------------------------------------------------------ retention

    /** The retention policies of the organisation. */
    public List<KnowledgeVaultRepository.RetentionPolicy> retentionPolicies(SiteScope scope) {
        return vault.retentionPolicies(scope.organizationId());
    }

    @Transactional
    public long createRetentionPolicy(SiteScope scope, String actor, String code, String name,
                                      Long folderId, int retainMonths, String action) {
        long id = vault.createRetentionPolicy(scope.organizationId(), code, name, folderId,
                retainMonths, RetentionPolicy.action(action).name());
        audit.record(AuditEvent.of(scope.organizationId(), actor, "KV_RETENTION_CREATE",
                "kv_retention_policy", String.valueOf(id), "{\"code\":\"" + code + "\"}", null));
        return id;
    }

    /**
     * The retention run: the documents that outlived their policy are archived
     * (or deleted). A legal hold always wins.
     */
    @Transactional
    public RetentionResult runRetention(SiteScope scope, String actor) {
        int archived = 0;
        int deleted = 0;
        int held = 0;
        for (KnowledgeVaultRepository.RetentionPolicy policy
                : vault.retentionPolicies(scope.organizationId())) {
            if (!policy.active()) {
                continue;
            }
            for (KnowledgeVaultRepository.VaultDocument document
                    : vault.documentsForRetention(scope.organizationId(), policy.folderId(),
                            policy.retainMonths())) {
                if (document.legalHold()) {
                    held++;
                    continue;
                }
                if (RetentionPolicy.Action.DELETE
                        .equals(RetentionPolicy.action(policy.action()))) {
                    vault.deleteDocument(document.id());
                    deleted++;
                } else {
                    vault.archive(document.id());
                    archived++;
                }
            }
        }
        audit.record(AuditEvent.of(scope.organizationId(), actor, "KV_RETENTION_RUN",
                "kv_retention_policy", "0",
                "{\"archived\":" + archived + ",\"deleted\":" + deleted + ",\"held\":" + held + "}",
                null));
        return new RetentionResult(archived, deleted, held);
    }

    /** What the retention run did. */
    public record RetentionResult(int archived, int deleted, int held) {
    }

    // ------------------------------------------------------------------ helpers

    private void applyTags(long organizationId, long documentId, List<String> tags) {
        if (tags == null) {
            return;
        }
        for (String tag : tags) {
            if (tag == null || tag.isBlank()) {
                continue;
            }
            vault.tagDocument(documentId, vault.tagId(organizationId, tag.trim()));
        }
    }

    /** The metric map of a document (for the assistant context). */
    public Map<String, Object> describe(SiteScope scope, long documentId) {
        KnowledgeVaultRepository.VaultDocument document = document(scope, documentId);
        return Map.of("code", document.code(), "title", document.title(),
                "version", document.currentVersion(), "legalHold", document.legalHold());
    }
}
