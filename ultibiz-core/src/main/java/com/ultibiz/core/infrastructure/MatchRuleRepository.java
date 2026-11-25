package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.MatchRule;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Matching tolerances (three-way match, receipt, payment). */
@Repository
public class MatchRuleRepository {

    private final JdbcTemplate jdbc;

    public MatchRuleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public MatchRule find(long organizationId, String ruleType) {
        List<MatchRule> rows = jdbc.query("""
                SELECT id, organization_id, rule_type, tolerance_percent, tolerance_amount, is_active
                FROM match_rule WHERE organization_id = ? AND rule_type = ? AND is_active = 1
                """, (rs, n) -> map(rs), organizationId, ruleType);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<MatchRule> list(long organizationId) {
        return jdbc.query("""
                SELECT id, organization_id, rule_type, tolerance_percent, tolerance_amount, is_active
                FROM match_rule WHERE organization_id = ? ORDER BY rule_type
                """, (rs, n) -> map(rs), organizationId);
    }

    private static MatchRule map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MatchRule(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getString("rule_type"),
                rs.getBigDecimal("tolerance_percent"),
                rs.getBigDecimal("tolerance_amount"),
                rs.getBoolean("is_active"));
    }
}
