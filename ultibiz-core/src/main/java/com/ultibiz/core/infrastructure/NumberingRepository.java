package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.NumberingSequence;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Storage for document-numbering sequences.
 *
 * <p>It uses Spring JDBC on purpose: inside a Spring transaction every
 * statement of the thread runs on the same physical connection, so the
 * {@code SELECT ... FOR UPDATE} row lock is held until the transaction
 * commits. That is what makes the counter safe under concurrency.
 */
@Repository
public class NumberingRepository {

    private static final String COLUMNS = """
            id, organization_id, site_id, document_type, name, prefix, suffix, padding,
            start_at, counter, period_type, fiscal_year_start_month, last_period_key
            """;

    private final JdbcTemplate jdbc;

    public NumberingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Site-scoped sequence wins over the organization-wide one for the same
     * document type.
     */
    public NumberingSequence findCandidate(long organizationId, Long siteId, String documentType) {
        List<NumberingSequence> rows = jdbc.query(
                "SELECT " + COLUMNS + """
                        FROM numbering_sequence
                        WHERE organization_id = ? AND document_type = ? AND is_active = 1
                          AND (site_id = ? OR site_id IS NULL)
                        ORDER BY (site_id IS NULL) ASC, id ASC
                        LIMIT 1
                        """,
                (rs, rowNum) -> map(rs),
                organizationId, documentType, siteId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Finds the sequence for a scope and locks it in the same statement.
     *
     * <p>It must be the first statement of the transaction: under REPEATABLE
     * READ a plain SELECT would create a snapshot, and a later locking read of
     * a row that another transaction already changed fails with MariaDB
     * error 1020 ("record has changed since last read").
     */
    public NumberingSequence lockCandidate(long organizationId, Long siteId, String documentType) {
        List<NumberingSequence> rows = jdbc.query(
                "SELECT " + COLUMNS + """
                        FROM numbering_sequence
                        WHERE organization_id = ? AND document_type = ? AND is_active = 1
                          AND (site_id = ? OR site_id IS NULL)
                        ORDER BY (site_id IS NULL) ASC, id ASC
                        LIMIT 1
                        FOR UPDATE
                        """,
                (rs, rowNum) -> map(rs),
                organizationId, documentType, siteId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void updateCounter(long id, long counter, String periodKey) {
        jdbc.update("UPDATE numbering_sequence SET counter = ?, last_period_key = ?, "
                + "row_version = row_version + 1 WHERE id = ?", counter, periodKey, id);
    }

    public List<NumberingSequence> list(long organizationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM numbering_sequence WHERE organization_id = ? "
                + "ORDER BY document_type", (rs, rowNum) -> map(rs), organizationId);
    }

    private static NumberingSequence map(java.sql.ResultSet rs) throws java.sql.SQLException {
        long siteId = rs.getLong("site_id");
        return new NumberingSequence(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.wasNull() ? null : siteId,
                rs.getString("document_type"),
                rs.getString("name"),
                rs.getString("prefix"),
                rs.getString("suffix"),
                rs.getInt("padding"),
                rs.getLong("start_at"),
                rs.getLong("counter"),
                rs.getString("period_type"),
                rs.getInt("fiscal_year_start_month"),
                rs.getString("last_period_key"));
    }
}
