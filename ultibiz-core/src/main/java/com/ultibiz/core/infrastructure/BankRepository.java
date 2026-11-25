package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.BankAccount;
import com.ultibiz.core.domain.BankStatementLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Bank/cash accounts and imported statement lines. */
@Repository
public class BankRepository {

    private final JdbcTemplate jdbc;

    public BankRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BankAccount> accounts(long organizationId) {
        return jdbc.query("""
                SELECT id, organization_id, site_id, code, name, bank_name, account_number, gl_account_id,
                       currency, is_active
                FROM bank_account WHERE organization_id = ? ORDER BY code
                """, (rs, n) -> mapAccount(rs), organizationId);
    }

    public BankAccount findAccount(long organizationId, long id) {
        List<BankAccount> rows = jdbc.query("""
                SELECT id, organization_id, site_id, code, name, bank_name, account_number, gl_account_id,
                       currency, is_active
                FROM bank_account WHERE organization_id = ? AND id = ?
                """, (rs, n) -> mapAccount(rs), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insertStatementLine(long bankAccountId, String importBatch, LocalDate statementDate,
                                    String description, String reference, BigDecimal amount, String currency) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO bank_statement_line (bank_account_id, import_batch, statement_date, description,
                        reference, amount, currency)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, bankAccountId);
            ps.setString(2, importBatch);
            ps.setObject(3, statementDate);
            ps.setString(4, description);
            ps.setString(5, reference);
            ps.setBigDecimal(6, amount);
            ps.setString(7, currency);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public List<BankStatementLine> statementLines(long bankAccountId, Boolean reconciled) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, bank_account_id, import_batch, statement_date, description, reference, amount,
                       currency, reconciled, matched_movement_id
                FROM bank_statement_line WHERE bank_account_id = ?
                """);
        List<Object> params = new java.util.ArrayList<>();
        params.add(bankAccountId);
        if (reconciled != null) {
            sql.append(" AND reconciled = ?");
            params.add(reconciled ? 1 : 0);
        }
        sql.append(" ORDER BY statement_date DESC, id DESC");
        return jdbc.query(sql.toString(), (rs, n) -> mapLine(rs), params.toArray());
    }

    public List<BankStatementLine> unreconciledLines(long bankAccountId) {
        return statementLines(bankAccountId, Boolean.FALSE);
    }

    public void markReconciled(long statementLineId, Long matchedMovementId) {
        jdbc.update("UPDATE bank_statement_line SET reconciled = 1, matched_movement_id = ? WHERE id = ?",
                matchedMovementId, statementLineId);
    }

    public long deleteBatch(long bankAccountId, String importBatch) {
        return jdbc.update("DELETE FROM bank_statement_line WHERE bank_account_id = ? AND import_batch = ?",
                bankAccountId, importBatch);
    }

    private static BankAccount mapAccount(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BankAccount(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("site_id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("bank_name"),
                rs.getString("account_number"),
                rs.getLong("gl_account_id"),
                rs.getString("currency"),
                rs.getBoolean("is_active"));
    }

    private static BankStatementLine mapLine(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new BankStatementLine(
                rs.getLong("id"),
                rs.getLong("bank_account_id"),
                rs.getString("import_batch"),
                rs.getDate("statement_date").toLocalDate(),
                rs.getString("description"),
                rs.getString("reference"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getBoolean("reconciled"),
                rs.getObject("matched_movement_id") == null ? null
                        : ((Number) rs.getObject("matched_movement_id")).longValue());
    }
}
