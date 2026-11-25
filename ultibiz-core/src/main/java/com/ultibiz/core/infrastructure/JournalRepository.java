package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.JournalEntry;
import com.ultibiz.core.domain.JournalLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Journal headers and their lines. */
@Repository
public class JournalRepository {

    private static final String COLUMNS = """
            id, organization_id, site_id, country_code, journal_number, journal_type, posting_date,
            fiscal_period_id, currency, exchange_rate, reference, source_type, source_id,
            reverses_journal_id, description, total_debit, total_credit, status, posted_by, posted_at
            """;

    private final JdbcTemplate jdbc;

    public JournalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertHeader(long organizationId, Long siteId, String countryCode, String journalNumber,
                             String journalType, LocalDate postingDate, long fiscalPeriodId, String currency,
                             BigDecimal exchangeRate, String reference, String sourceType, Long sourceId,
                             Long reversesJournalId, String description, BigDecimal totalDebit,
                             BigDecimal totalCredit, String actor, String correlationId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO journal_entry (organization_id, site_id, country_code, journal_number,
                        journal_type, posting_date, fiscal_period_id, currency, exchange_rate, reference,
                        source_type, source_id, reverses_journal_id, description, total_debit, total_credit,
                        status, posted_by, correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setObject(2, siteId);
            ps.setString(3, countryCode);
            ps.setString(4, journalNumber);
            ps.setString(5, journalType);
            ps.setObject(6, postingDate);
            ps.setLong(7, fiscalPeriodId);
            ps.setString(8, currency);
            ps.setBigDecimal(9, exchangeRate);
            ps.setString(10, reference);
            ps.setString(11, sourceType);
            ps.setObject(12, sourceId);
            ps.setObject(13, reversesJournalId);
            ps.setString(14, description);
            ps.setBigDecimal(15, totalDebit);
            ps.setBigDecimal(16, totalCredit);
            ps.setString(17, actor);
            ps.setString(18, correlationId);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void insertLine(long journalId, int lineNo, long accountId, Long partyId, Long siteId,
                            String description, BigDecimal debit, BigDecimal credit, String currency,
                            BigDecimal exchangeRate, BigDecimal baseDebit, BigDecimal baseCredit,
                            Long taxCategoryId, BigDecimal taxRate) {
        jdbc.update("""
                INSERT INTO journal_line (journal_id, line_no, account_id, party_id, site_id, description,
                    debit, credit, currency, exchange_rate, base_debit, base_credit, tax_category_id, tax_rate)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                journalId, lineNo, accountId, partyId, siteId, description, debit, credit, currency,
                exchangeRate, baseDebit, baseCredit, taxCategoryId, taxRate);
    }

    public JournalEntry findByNumber(long organizationId, String journalNumber) {
        List<JournalEntry> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM journal_entry WHERE organization_id = ? AND journal_number = ?",
                (rs, n) -> map(rs), organizationId, journalNumber);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public JournalEntry findById(long organizationId, long id) {
        List<JournalEntry> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM journal_entry WHERE organization_id = ? AND id = ?",
                (rs, n) -> map(rs), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<JournalLine> lines(long journalId) {
        return jdbc.query("""
                SELECT id, journal_id, line_no, account_id, party_id, site_id, description, debit, credit,
                       currency, exchange_rate, base_debit, base_credit, tax_category_id, tax_rate
                FROM journal_line WHERE journal_id = ? ORDER BY line_no
                """, (rs, n) -> new JournalLine(
                rs.getLong("id"),
                rs.getLong("journal_id"),
                rs.getInt("line_no"),
                rs.getLong("account_id"),
                nullableLong(rs, "party_id"),
                nullableLong(rs, "site_id"),
                rs.getString("description"),
                rs.getBigDecimal("debit"),
                rs.getBigDecimal("credit"),
                rs.getString("currency"),
                rs.getBigDecimal("exchange_rate"),
                rs.getBigDecimal("base_debit"),
                rs.getBigDecimal("base_credit"),
                nullableLong(rs, "tax_category_id"),
                rs.getBigDecimal("tax_rate")), journalId);
    }

    public List<JournalEntry> list(long organizationId, String countryCode, LocalDate from, LocalDate to,
                                    int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM journal_entry WHERE organization_id = ?");
        List<Object> params = new java.util.ArrayList<>();
        params.add(organizationId);
        if (countryCode != null && !countryCode.isBlank()) {
            sql.append(" AND country_code = ?");
            params.add(countryCode);
        }
        if (from != null) {
            sql.append(" AND posting_date >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND posting_date <= ?");
            params.add(to);
        }
        sql.append(" ORDER BY posting_date DESC, id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbc.query(sql.toString(), (rs, n) -> map(rs), params.toArray());
    }

    public long count(long organizationId, String countryCode, LocalDate from, LocalDate to) {
        StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) FROM journal_entry WHERE organization_id = ?");
        List<Object> params = new java.util.ArrayList<>();
        params.add(organizationId);
        if (countryCode != null && !countryCode.isBlank()) {
            sql.append(" AND country_code = ?");
            params.add(countryCode);
        }
        if (from != null) {
            sql.append(" AND posting_date >= ?");
            params.add(from);
        }
        if (to != null) {
            sql.append(" AND posting_date <= ?");
            params.add(to);
        }
        Number n = (Number) jdbc.queryForObject(sql.toString(), Number.class, params.toArray());
        return n == null ? 0L : n.longValue();
    }

    /** Trial balance per account in the functional currency. */
    public List<TrialBalanceRow> trialBalance(long organizationId, LocalDate from, LocalDate to) {
        // The date filter must restrict the journal lines, not the account
        // join: otherwise lines of journals outside the period are counted too.
        return jdbc.query("""
                SELECT a.id AS account_id, a.code AS account_code, a.name AS account_name,
                       a.account_type AS account_type, a.normal_balance AS normal_balance,
                       COALESCE(t.total_debit, 0) AS total_debit,
                       COALESCE(t.total_credit, 0) AS total_credit
                FROM account a
                LEFT JOIN (
                    SELECT l.account_id AS account_id,
                           SUM(l.base_debit) AS total_debit,
                           SUM(l.base_credit) AS total_credit
                      FROM journal_line l
                      JOIN journal_entry j ON j.id = l.journal_id
                     WHERE j.organization_id = ?
                       AND (? IS NULL OR j.posting_date >= ?)
                       AND (? IS NULL OR j.posting_date <= ?)
                     GROUP BY l.account_id
                ) t ON t.account_id = a.id
                WHERE a.organization_id = ? AND a.deleted_at IS NULL
                ORDER BY a.code
                """, (rs, n) -> new TrialBalanceRow(
                rs.getLong("account_id"),
                rs.getString("account_code"),
                rs.getString("account_name"),
                rs.getString("account_type"),
                rs.getString("normal_balance"),
                rs.getBigDecimal("total_debit"),
                rs.getBigDecimal("total_credit")),
                organizationId, from, from, to, to, organizationId);
    }

    public void markReversed(long id) {
        jdbc.update("UPDATE journal_entry SET status = 'REVERSED', row_version = row_version + 1 WHERE id = ?", id);
    }

    /** One row of the trial balance. */
    public record TrialBalanceRow(long accountId, String accountCode, String accountName,
                                   String accountType, String normalBalance,
                                   BigDecimal totalDebit, BigDecimal totalCredit) {
    }

    private static JournalEntry map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new JournalEntry(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                nullableLong(rs, "site_id"),
                rs.getString("country_code"),
                rs.getString("journal_number"),
                rs.getString("journal_type"),
                rs.getDate("posting_date").toLocalDate(),
                rs.getLong("fiscal_period_id"),
                rs.getString("currency"),
                rs.getBigDecimal("exchange_rate"),
                rs.getString("reference"),
                rs.getString("source_type"),
                nullableLong(rs, "source_id"),
                nullableLong(rs, "reverses_journal_id"),
                rs.getString("description"),
                rs.getBigDecimal("total_debit"),
                rs.getBigDecimal("total_credit"),
                rs.getString("status"),
                rs.getString("posted_by"),
                rs.getTimestamp("posted_at").toInstant());
    }

    /** MariaDB returns BIGINT UNSIGNED as BigInteger, not Long. */
    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }
}
