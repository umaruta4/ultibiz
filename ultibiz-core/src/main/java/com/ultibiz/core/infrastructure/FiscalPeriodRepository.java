package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.FiscalPeriod;
import com.ultibiz.core.domain.PeriodStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Fiscal periods. The locking read ({@link #lock}) must be the first statement
 * of the transaction that posts into the period.
 */
@Repository
public class FiscalPeriodRepository {

    private static final String COLUMNS = """
            id, organization_id, country_code, fiscal_year, fiscal_month, period_start, period_end,
            status, closed_at, closed_by, reopened_at, reopened_by
            """;

    private final JdbcTemplate jdbc;

    public FiscalPeriodRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public FiscalPeriod find(long organizationId, String countryCode, int fiscalYear, int fiscalMonth) {
        List<FiscalPeriod> rows = jdbc.query("SELECT " + COLUMNS
                        + " FROM fiscal_period WHERE organization_id = ? AND country_code = ?"
                        + " AND fiscal_year = ? AND fiscal_month = ?",
                (rs, n) -> map(rs), organizationId, countryCode, fiscalYear, fiscalMonth);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The period that contains the given date. */
    public FiscalPeriod findByDate(long organizationId, String countryCode, LocalDate date) {
        List<FiscalPeriod> rows = jdbc.query("SELECT " + COLUMNS
                        + " FROM fiscal_period WHERE organization_id = ? AND country_code = ?"
                        + " AND period_start <= ? AND period_end >= ? ORDER BY period_start DESC LIMIT 1",
                (rs, n) -> map(rs), organizationId, countryCode, date, date);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<FiscalPeriod> list(long organizationId, String countryCode) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM fiscal_period WHERE organization_id = ? AND country_code = ?"
                        + " ORDER BY fiscal_year, fiscal_month",
                (rs, n) -> map(rs), organizationId, countryCode);
    }

    /** Locks the period row for the rest of the transaction. */
    public FiscalPeriod lock(long id) {
        List<FiscalPeriod> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM fiscal_period WHERE id = ? FOR UPDATE", (rs, n) -> map(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void markClosed(long id, String actor) {
        jdbc.update("UPDATE fiscal_period SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP(6),"
                + " closed_by = ?, row_version = row_version + 1 WHERE id = ?", actor, id);
    }

    public void markReopened(long id, String actor) {
        jdbc.update("UPDATE fiscal_period SET status = 'REOPENED', reopened_at = CURRENT_TIMESTAMP(6),"
                + " reopened_by = ?, row_version = row_version + 1 WHERE id = ?", actor, id);
    }

    private static FiscalPeriod map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FiscalPeriod(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getString("country_code"),
                rs.getInt("fiscal_year"),
                rs.getInt("fiscal_month"),
                rs.getDate("period_start").toLocalDate(),
                rs.getDate("period_end").toLocalDate(),
                PeriodStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("closed_at") == null ? null : rs.getTimestamp("closed_at").toInstant(),
                rs.getString("closed_by"),
                rs.getTimestamp("reopened_at") == null ? null : rs.getTimestamp("reopened_at").toInstant(),
                rs.getString("reopened_by"));
    }
}
