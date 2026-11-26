package com.ultibiz.core.ai;

import com.ultibiz.core.application.SiteScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The knowledge vault: the documents are chunked, embedded into a 768
 * dimension vector and stored in MariaDB. The retrieval is hybrid: the vector
 * distance and the fulltext rank are combined, and every answer carries the
 * documents it used as citations. When nothing matches, the service says so
 * instead of inventing an answer.
 *
 * <p>The embedding is computed locally (a hashed, normalised bag of words):
 * the data never leaves the site and the index works without a model server.
 * A model server can replace it later through the same interface.
 */
@Service
public class KnowledgeService {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeService.class);
    private static final int DIMENSIONS = 768;
    private boolean nativeVectorWarned;
    private static final int CHUNK_SIZE = 480;

    /** One chunk with its document. */
    public record Chunk(long documentId, String documentCode, String documentTitle, String source,
                         int chunkNo, String content, double distance, String fulltextRank) {
    }

    /** The answer of a retrieval. */
    public record Retrieval(boolean found, List<Chunk> chunks) {
    }

    private final JdbcTemplate jdbc;

    public KnowledgeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Indexes a document: the content is chunked and every chunk is embedded. */
    public int index(SiteScope scope, String code, String title, String source, String language,
                     String content) {
        jdbc.update("""
                INSERT INTO knowledge_document (organization_id, code, title, source, language, content)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE title = VALUES(title), source = VALUES(source),
                    language = VALUES(language), content = VALUES(content)
                """, scope.organizationId(), code, title, source, language, content);
        Long documentId = jdbc.queryForObject("""
                SELECT id FROM knowledge_document WHERE organization_id = ? AND code = ?
                """, Long.class, scope.organizationId(), code);
        if (documentId == null) {
            return 0;
        }
        jdbc.update("DELETE FROM knowledge_chunk WHERE document_id = ?", documentId);
        List<String> chunks = chunk(content);
        int number = 0;
        for (String chunk : chunks) {
            String vector = vector(chunk);
            jdbc.update("""
                    INSERT INTO knowledge_chunk (organization_id, document_id, chunk_no, content,
                        embedding_text) VALUES (?, ?, ?, ?, ?)
                    """, scope.organizationId(), documentId, number, chunk, vector);
            // the native vector column is filled when the server supports it
            fillNativeVector(documentId, number, vector);
            number++;
        }
        return number;
    }

    /**
     * Writes the vector into the native MariaDB vector column. The server
     * ships the type but not always the vector functions, so a failure here
     * only means the retrieval uses the text copy — it never breaks the index.
     */
    private void fillNativeVector(long documentId, int chunkNo, String vector) {
        try {
            jdbc.update("UPDATE knowledge_chunk SET embedding = '" + vector
                    + "' WHERE document_id = ? AND chunk_no = ?", documentId, chunkNo);
        } catch (RuntimeException ex) {
            if (!nativeVectorWarned) {
                nativeVectorWarned = true;
                LOG.info("The MariaDB vector functions are not available here; "
                        + "the retrieval uses the text copy of the embedding");
            }
        }
    }

    /** Splits the content into chunks of roughly {@link #CHUNK_SIZE} characters. */
    public List<String> chunk(String content) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }
        StringBuilder current = new StringBuilder();
        for (String sentence : content.split("(?<=[.!?])\\s+")) {
            if (current.length() + sentence.length() > CHUNK_SIZE && current.length() > 0) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            current.append(sentence).append(' ');
        }
        if (current.length() > 0) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    /**
     * The embedding of a text: the tokens are hashed into the 768 dimensions,
     * weighted by the term frequency and normalised. The same text always
     * produces the same vector.
     */
    public String vector(String text) {
        double[] values = new double[DIMENSIONS];
        for (String token : tokenize(text)) {
            add(values, token, 1.0);
            // the character trigrams make the embedding robust against the
            // morphology ("penyusutan" and "disusutkan" still meet)
            String padded = "^" + token + "$";
            for (int i = 0; i + 3 <= padded.length(); i++) {
                add(values, padded.substring(i, i + 3), 0.35);
            }
        }
        double norm = 0;
        for (double value : values) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        if (norm == 0) {
            norm = 1;
        }
        StringBuilder vector = new StringBuilder("[");
        for (int i = 0; i < DIMENSIONS; i++) {
            if (i > 0) {
                vector.append(',');
            }
            vector.append(BigDecimal.valueOf(values[i] / norm).setScale(6, RoundingMode.HALF_UP)
                    .stripTrailingZeros().toPlainString());
        }
        return vector.append(']').toString();
    }

    /**
     * The hybrid retrieval: the vector distance and the fulltext rank decide
     * together. Below the threshold the answer is "not found".
     */
    public Retrieval search(SiteScope scope, String question, int limit, double threshold) {
        String fulltext = fulltextQuery(question);
        double[] queryVector = values(vector(question));
        List<Chunk> rows = jdbc.query("""
                SELECT d.id, d.code, d.title, d.source, c.chunk_no, c.content, c.embedding_text,
                       IFNULL(MATCH(c.content) AGAINST (? IN BOOLEAN MODE), 0) AS rank
                FROM knowledge_chunk c
                JOIN knowledge_document d ON d.id = c.document_id
                WHERE c.organization_id = ? AND d.is_active = TRUE
                LIMIT 500
                """, (rs, n) -> {
                    double distance = cosine(queryVector, values(rs.getString("embedding_text")));
                    // the fulltext hit pulls a chunk that is close to the query
                    double rank = rs.getDouble("rank");
                    double score = distance - Math.min(rank, 5) * 0.02;
                    return new Chunk(rs.getLong("id"), rs.getString("code"), rs.getString("title"),
                            rs.getString("source"), rs.getInt("chunk_no"), rs.getString("content"),
                            score, rs.getString("rank"));
                }, fulltext, scope.organizationId());
        rows = rows.stream()
                .sorted(java.util.Comparator.comparingDouble(Chunk::distance))
                .limit(Math.max(1, limit))
                .toList();
        // a chunk counts as an answer when the fulltext index matched it or
        // when the vector is close enough to the question
        boolean found = !rows.isEmpty()
                && (rows.get(0).distance() <= threshold || fulltextHit(rows.get(0)));
        return new Retrieval(found, found ? rows : List.of());
    }

    /** The cosine distance between two vectors. */
    static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || b.length != a.length) {
            return 1.0;
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return 1.0 - dot;   // both vectors are normalised
    }

    /** Did the fulltext index match the chunk? */
    private static boolean fulltextHit(Chunk chunk) {
        if (chunk.fulltextRank() == null) {
            return false;
        }
        try {
            return Double.parseDouble(chunk.fulltextRank()) > 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** The values of a vector string. */
    static double[] values(String vector) {
        if (vector == null || vector.isBlank()) {
            return new double[0];
        }
        String trimmed = vector.trim();
        if (trimmed.startsWith("[")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("]")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        String[] parts = trimmed.split(",");
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                values[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException ex) {
                values[i] = 0;
            }
        }
        return values;
    }

    /** The answer when the knowledge base has nothing to say. */
    public static final String NO_ANSWER =
            "I could not find an answer in the knowledge base.";

    /** Adds one feature to the vector. */
    private static void add(double[] values, String feature, double weight) {
        int bucket = Math.floorMod(feature.hashCode(), values.length);
        int sign = Math.floorMod(feature.hashCode() * 31, 2) == 0 ? 1 : -1;
        values[bucket] += sign * weight;
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() > 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    /**
     * The fulltext query: the tokens as prefix terms. In BOOLEAN mode a space
     * is an OR, so a question that names one word of a document still finds
     * it — a mandatory term ('+') would hide the document completely.
     */
    private static String fulltextQuery(String question) {
        StringBuilder query = new StringBuilder();
        for (String token : tokenize(question)) {
            if (query.length() > 0) {
                query.append(' ');
            }
            query.append(token).append('*');
        }
        return query.toString();
    }
}
