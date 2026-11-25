package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.ApAllocation;
import com.ultibiz.core.domain.ApBill;
import com.ultibiz.core.domain.ApBillLine;
import com.ultibiz.core.domain.ApPayment;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Supplier bills, payments and allocations. */
@Repository
public class ApRepository {

    private final JdbcTemplate jdbc;

    public ApRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertBill(long organizationId, long siteId, String countryCode, String billNumber,
                           long partyId, Long purchaseOrderId, LocalDate billDate, LocalDate dueDate,
                           String currency, BigDecimal subtotal, BigDecimal taxTotal, BigDecimal total,
                           long journalId, String actor) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO ap_bill (organization_id, site_id, country_code, bill_number, party_id,
                        purchase_order_id, bill_date, due_date, currency, subtotal, tax_total, total,
                        paid_amount, status, journal_id, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'OPEN', ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, siteId);
            ps.setString(3, countryCode);
            ps.setString(4, billNumber);
            ps.setLong(5, partyId);
            ps.setObject(6, purchaseOrderId);
            ps.setObject(7, billDate);
            ps.setObject(8, dueDate);
            ps.setString(9, currency);
            ps.setBigDecimal(10, subtotal);
            ps.setBigDecimal(11, taxTotal);
            ps.setBigDecimal(12, total);
            ps.setLong(13, journalId);
            ps.setString(14, actor);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void insertBillLine(long billId, int lineNo, Long productId, String description, BigDecimal quantity,
                                BigDecimal unitPrice, Long taxCategoryId, BigDecimal taxRate, BigDecimal netAmount,
                                BigDecimal taxAmount, BigDecimal totalAmount) {
        jdbc.update("""
                INSERT INTO ap_bill_line (bill_id, line_no, product_id, description, quantity, unit_price,
                    tax_category_id, tax_rate, net_amount, tax_amount, total_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, billId, lineNo, productId, description, quantity, unitPrice, taxCategoryId, taxRate,
                netAmount, taxAmount, totalAmount);
    }

    public ApBill findBill(long organizationId, long id) {
        List<ApBill> rows = jdbc.query("""
                SELECT id, organization_id, site_id, country_code, bill_number, party_id, purchase_order_id,
                       bill_date, due_date, currency, subtotal, tax_total, total, paid_amount, status,
                       journal_id, created_by, row_version
                FROM ap_bill WHERE organization_id = ? AND id = ?
                """, (rs, n) -> mapBill(rs), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ApBillLine> billLines(long billId) {
        return jdbc.query("""
                SELECT id, bill_id, line_no, product_id, description, quantity, unit_price, tax_category_id,
                       tax_rate, net_amount, tax_amount, total_amount
                FROM ap_bill_line WHERE bill_id = ? ORDER BY line_no
                """, (rs, n) -> new ApBillLine(
                rs.getLong("id"),
                rs.getLong("bill_id"),
                rs.getInt("line_no"),
                nullableLong(rs, "product_id"),
                rs.getString("description"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price"),
                nullableLong(rs, "tax_category_id"),
                rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("net_amount"),
                rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total_amount")), billId);
    }

    public List<ApBill> listBills(long organizationId, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, organization_id, site_id, country_code, bill_number, party_id, purchase_order_id,
                       bill_date, due_date, currency, subtotal, tax_total, total, paid_amount, status,
                       journal_id, created_by, row_version
                FROM ap_bill WHERE organization_id = ?
                """);
        List<Object> params = new java.util.ArrayList<>();
        params.add(organizationId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY bill_date DESC, id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbc.query(sql.toString(), (rs, n) -> mapBill(rs), params.toArray());
    }

    public long countBills(long organizationId, String status) {
        if (status != null && !status.isBlank()) {
            Number n = jdbc.queryForObject("SELECT COUNT(*) FROM ap_bill WHERE organization_id = ? AND status = ?",
                    Number.class, organizationId, status);
            return n == null ? 0L : n.longValue();
        }
        Number n = jdbc.queryForObject("SELECT COUNT(*) FROM ap_bill WHERE organization_id = ?",
                Number.class, organizationId);
        return n == null ? 0L : n.longValue();
    }

    /** Open bills of a party, oldest due first (used by the ageing report). */
    public List<ApBill> openBills(long organizationId) {
        return jdbc.query("""
                SELECT id, organization_id, site_id, country_code, bill_number, party_id, purchase_order_id,
                       bill_date, due_date, currency, subtotal, tax_total, total, paid_amount, status,
                       journal_id, created_by, row_version
                FROM ap_bill WHERE organization_id = ? AND status IN ('OPEN', 'PARTIAL')
                ORDER BY due_date
                """, (rs, n) -> mapBill(rs), organizationId);
    }

    public void addPaidAmount(long billId, BigDecimal amount) {
        jdbc.update("""
                UPDATE ap_bill SET paid_amount = paid_amount + ?,
                    status = CASE WHEN paid_amount + ? >= total THEN 'PAID' ELSE 'PARTIAL' END,
                    row_version = row_version + 1 WHERE id = ?
                """, amount, amount, billId);
    }

    public long insertPayment(long organizationId, long siteId, String paymentNumber, long partyId,
                              LocalDate paymentDate, BigDecimal amount, String currency, String method,
                              Long bankAccountId, long journalId, String reference, String actor) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO ap_payment (organization_id, site_id, payment_number, party_id, payment_date,
                        amount, currency, method, bank_account_id, journal_id, reference, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, siteId);
            ps.setString(3, paymentNumber);
            ps.setLong(4, partyId);
            ps.setObject(5, paymentDate);
            ps.setBigDecimal(6, amount);
            ps.setString(7, currency);
            ps.setString(8, method);
            ps.setObject(9, bankAccountId);
            ps.setLong(10, journalId);
            ps.setString(11, reference);
            ps.setString(12, actor);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void insertAllocation(long paymentId, long billId, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO ap_allocation (payment_id, bill_id, amount)
                VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount)
                """, paymentId, billId, amount);
    }

    public List<ApAllocation> allocationsOfBill(long billId) {
        return jdbc.query("SELECT id, payment_id, bill_id, amount FROM ap_allocation WHERE bill_id = ?",
                (rs, n) -> new ApAllocation(rs.getLong("id"), rs.getLong("payment_id"),
                        rs.getLong("bill_id"), rs.getBigDecimal("amount")), billId);
    }

    private static ApBill mapBill(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApBill(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("site_id"),
                rs.getString("country_code"),
                rs.getString("bill_number"),
                rs.getLong("party_id"),
                nullableLong(rs, "purchase_order_id"),
                rs.getDate("bill_date").toLocalDate(),
                rs.getDate("due_date").toLocalDate(),
                rs.getString("currency"),
                rs.getBigDecimal("subtotal"),
                rs.getBigDecimal("tax_total"),
                rs.getBigDecimal("total"),
                rs.getBigDecimal("paid_amount"),
                rs.getString("status"),
                rs.getLong("journal_id"),
                rs.getString("created_by"),
                rs.getLong("row_version"));
    }

    /** MariaDB returns BIGINT UNSIGNED as BigInteger, not Long. */
    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }
    /** Payments of a party within an amount range and a date window (reconciliation). */
    public List<com.ultibiz.core.domain.ApPayment> paymentsByAmountRange(long organizationId,
                java.math.BigDecimal min, java.math.BigDecimal max, java.time.LocalDate from,
                java.time.LocalDate to) {
        return jdbc.query("""
                SELECT id, organization_id, site_id, payment_number, party_id, payment_date, amount, currency,
                       method, bank_account_id, journal_id, reference, created_by
                FROM ap_payment WHERE organization_id = ? AND amount >= ? AND amount <= ?
                  AND payment_date >= ? AND payment_date <= ? ORDER BY payment_date
                """, (rs, n) -> new com.ultibiz.core.domain.ApPayment(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("site_id"),
                rs.getString("payment_number"),
                rs.getLong("party_id"),
                rs.getDate("payment_date").toLocalDate(),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("method"),
                nullableLong(rs, "bank_account_id"),
                rs.getLong("journal_id"),
                rs.getString("reference"),
                rs.getString("created_by")), organizationId, min, max, from, to);
    }

}
