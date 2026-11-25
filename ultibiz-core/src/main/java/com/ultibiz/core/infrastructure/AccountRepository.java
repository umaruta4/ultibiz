package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.Account;
import com.ultibiz.core.domain.AccountType;
import com.ultibiz.core.domain.NormalBalance;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Chart of accounts. */
@Repository
public class AccountRepository {

    private static final String COLUMNS = """
            id, organization_id, country_code, code, name, account_type, parent_id,
            normal_balance, currency, is_postable, is_active, row_version
            """;

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Account> list(long organizationId, boolean activeOnly) {
        String sql = "SELECT " + COLUMNS + " FROM account WHERE organization_id = ?"
                + (activeOnly ? " AND is_active = 1" : "") + " ORDER BY code";
        return jdbc.query(sql, (rs, n) -> map(rs), organizationId);
    }

    public Account findByCode(long organizationId, String code) {
        List<Account> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM account WHERE organization_id = ? AND code = ? AND deleted_at IS NULL",
                (rs, n) -> map(rs), organizationId, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Account findById(long organizationId, long id) {
        List<Account> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM account WHERE organization_id = ? AND id = ? AND deleted_at IS NULL",
                (rs, n) -> map(rs), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Accounts that may be posted to (leaf accounts). */
    public List<Account> postable(long organizationId) {
        return jdbc.query("SELECT " + COLUMNS
                + " FROM account WHERE organization_id = ? AND is_postable = 1 AND is_active = 1"
                + " AND deleted_at IS NULL ORDER BY code", (rs, n) -> map(rs), organizationId);
    }

    private static Account map(java.sql.ResultSet rs) throws java.sql.SQLException {
        long parent = rs.getLong("parent_id");
        return new Account(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getString("country_code"),
                rs.getString("code"),
                rs.getString("name"),
                AccountType.valueOf(rs.getString("account_type")),
                rs.wasNull() ? null : parent,
                NormalBalance.valueOf(rs.getString("normal_balance")),
                rs.getString("currency"),
                rs.getBoolean("is_postable"),
                rs.getBoolean("is_active"),
                rs.getLong("row_version"));
    }
}
