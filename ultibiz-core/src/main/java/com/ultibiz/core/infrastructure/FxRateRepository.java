package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.FxRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Exchange rates (functional currency -> transaction currency). */
@Repository
public class FxRateRepository {

    private final JdbcTemplate jdbc;

    public FxRateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The latest rate valid on or before the given date. */
    public FxRate find(long organizationId, String base, String quote, LocalDate onDate) {
        List<FxRate> rows = jdbc.query("""
                SELECT id, organization_id, base_currency, quote_currency, rate, effective_date, source
                FROM fx_rate
                WHERE organization_id = ? AND base_currency = ? AND quote_currency = ? AND effective_date <= ?
                ORDER BY effective_date DESC, id DESC LIMIT 1
                """, (rs, n) -> map(rs), organizationId, base, quote, onDate);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insert(long organizationId, String base, String quote, BigDecimal rate,
                       LocalDate effectiveDate, String source) {
        jdbc.update("""
                INSERT INTO fx_rate (organization_id, base_currency, quote_currency, rate, effective_date, source)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE rate = VALUES(rate), source = VALUES(source)
                """, organizationId, base, quote, rate, effectiveDate, source);
    }

    private static FxRate map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FxRate(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getString("base_currency"),
                rs.getString("quote_currency"),
                rs.getBigDecimal("rate"),
                rs.getDate("effective_date").toLocalDate(),
                rs.getString("source"));
    }
}
