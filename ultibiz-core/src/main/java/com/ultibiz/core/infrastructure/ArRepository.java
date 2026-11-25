package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.ArAllocation;
import com.ultibiz.core.domain.ArInvoice;
import com.ultibiz.core.domain.ArInvoiceLine;
import com.ultibiz.core.domain.ArReceipt;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Customer invoices, receipts and allocations. */
@Repository
public class ArRepository {

    private final JdbcTemplate jdbc;

    public ArRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertInvoice(long organizationId, long siteId, String countryCode, String invoiceNumber,
                              long partyId, Long salesOrderId, LocalDate invoiceDate, LocalDate dueDate,
                              String currency, BigDecimal exchangeRate, boolean taxInclusive, BigDecimal subtotal,
                              BigDecimal taxTotal, BigDecimal total, long journalId, String actor) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO ar_invoice (organization_id, site_id, country_code, invoice_number, party_id,
                        sales_order_id, invoice_date, due_date, currency, exchange_rate, tax_inclusive,
                        subtotal, tax_total, total, paid_amount, status, journal_id, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 'OPEN', ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, siteId);
            ps.setString(3, countryCode);
            ps.setString(4, invoiceNumber);
            ps.setLong(5, partyId);
            ps.setObject(6, salesOrderId);
            ps.setObject(7, invoiceDate);
            ps.setObject(8, dueDate);
            ps.setString(9, currency);
            ps.setBigDecimal(10, exchangeRate);
            ps.setBoolean(11, taxInclusive);
            ps.setBigDecimal(12, subtotal);
            ps.setBigDecimal(13, taxTotal);
            ps.setBigDecimal(14, total);
            ps.setLong(15, journalId);
            ps.setString(16, actor);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void insertInvoiceLine(long invoiceId, int lineNo, Long productId, String description,
                                   BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount,
                                   Long taxCategoryId, BigDecimal taxRate, BigDecimal netAmount,
                                   BigDecimal taxAmount, BigDecimal totalAmount) {
        jdbc.update("""
                INSERT INTO ar_invoice_line (invoice_id, line_no, product_id, description, quantity, unit_price,
                    discount, tax_category_id, tax_rate, net_amount, tax_amount, total_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, invoiceId, lineNo, productId, description, quantity, unitPrice, discount, taxCategoryId,
                taxRate, netAmount, taxAmount, totalAmount);
    }

    public ArInvoice findInvoice(long organizationId, long id) {
        List<ArInvoice> rows = jdbc.query("SELECT id, organization_id, site_id, country_code, invoice_number,"
                        + " party_id, sales_order_id, invoice_date, due_date, currency, exchange_rate,"
                        + " tax_inclusive, subtotal, tax_total, total, paid_amount, status, journal_id,"
                        + " created_by, row_version FROM ar_invoice WHERE organization_id = ? AND id = ?",
                (rs, n) -> mapInvoice(rs), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<ArInvoiceLine> invoiceLines(long invoiceId) {
        return jdbc.query("""
                SELECT id, invoice_id, line_no, product_id, description, quantity, unit_price, discount,
                       tax_category_id, tax_rate, net_amount, tax_amount, total_amount
                FROM ar_invoice_line WHERE invoice_id = ? ORDER BY line_no
                """, (rs, n) -> new ArInvoiceLine(
                rs.getLong("id"),
                rs.getLong("invoice_id"),
                rs.getInt("line_no"),
                nullableLong(rs, "product_id"),
                rs.getString("description"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("discount"),
                nullableLong(rs, "tax_category_id"),
                rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("net_amount"),
                rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total_amount")), invoiceId);
    }

    public List<ArInvoice> listInvoices(long organizationId, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT id, organization_id, site_id, country_code, invoice_number,"
                + " party_id, sales_order_id, invoice_date, due_date, currency, exchange_rate, tax_inclusive,"
                + " subtotal, tax_total, total, paid_amount, status, journal_id, created_by, row_version"
                + " FROM ar_invoice WHERE organization_id = ?");
        List<Object> params = new java.util.ArrayList<>();
        params.add(organizationId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY invoice_date DESC, id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbc.query(sql.toString(), (rs, n) -> mapInvoice(rs), params.toArray());
    }

    public long countInvoices(long organizationId, String status) {
        if (status != null && !status.isBlank()) {
            Number n = jdbc.queryForObject("SELECT COUNT(*) FROM ar_invoice WHERE organization_id = ? AND status = ?",
                    Number.class, organizationId, status);
            return n == null ? 0L : n.longValue();
        }
        Number n = jdbc.queryForObject("SELECT COUNT(*) FROM ar_invoice WHERE organization_id = ?",
                Number.class, organizationId);
        return n == null ? 0L : n.longValue();
    }

    /** Open invoices of a party, oldest due first (used by the ageing report). */
    public List<ArInvoice> openInvoices(long organizationId) {
        return jdbc.query("""
                SELECT id, organization_id, site_id, country_code, invoice_number, party_id, sales_order_id,
                       invoice_date, due_date, currency, exchange_rate, tax_inclusive, subtotal, tax_total,
                       total, paid_amount, status, journal_id, created_by, row_version
                FROM ar_invoice WHERE organization_id = ? AND status IN ('OPEN', 'PARTIAL')
                ORDER BY due_date
                """, (rs, n) -> mapInvoice(rs), organizationId);
    }

    public void addPaidAmount(long invoiceId, BigDecimal amount) {
        jdbc.update("""
                UPDATE ar_invoice SET paid_amount = paid_amount + ?,
                    status = CASE WHEN paid_amount + ? >= total THEN 'PAID' ELSE 'PARTIAL' END,
                    row_version = row_version + 1 WHERE id = ?
                """, amount, amount, invoiceId);
    }

    public long insertReceipt(long organizationId, long siteId, String receiptNumber, long partyId,
                              LocalDate receiptDate, BigDecimal amount, String currency, String method,
                              Long bankAccountId, long journalId, String reference, String actor) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO ar_receipt (organization_id, site_id, receipt_number, party_id, receipt_date,
                        amount, currency, method, bank_account_id, journal_id, reference, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, siteId);
            ps.setString(3, receiptNumber);
            ps.setLong(4, partyId);
            ps.setObject(5, receiptDate);
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

    public void insertAllocation(long receiptId, long invoiceId, BigDecimal amount) {
        jdbc.update("""
                INSERT INTO ar_allocation (receipt_id, invoice_id, amount)
                VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE amount = amount + VALUES(amount)
                """, receiptId, invoiceId, amount);
    }

    public List<ArAllocation> allocationsOfInvoice(long invoiceId) {
        return jdbc.query("SELECT id, receipt_id, invoice_id, amount FROM ar_allocation WHERE invoice_id = ?",
                (rs, n) -> new ArAllocation(rs.getLong("id"), rs.getLong("receipt_id"),
                        rs.getLong("invoice_id"), rs.getBigDecimal("amount")), invoiceId);
    }

    private static ArInvoice mapInvoice(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ArInvoice(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("site_id"),
                rs.getString("country_code"),
                rs.getString("invoice_number"),
                rs.getLong("party_id"),
                nullableLong(rs, "sales_order_id"),
                rs.getDate("invoice_date").toLocalDate(),
                rs.getDate("due_date").toLocalDate(),
                rs.getString("currency"),
                rs.getBigDecimal("exchange_rate"),
                rs.getBoolean("tax_inclusive"),
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
    /** Receipts of a party within an amount range and a date window (reconciliation). */
    public List<com.ultibiz.core.domain.ArReceipt> receiptsByAmountRange(long organizationId,
                java.math.BigDecimal min, java.math.BigDecimal max, java.time.LocalDate from,
                java.time.LocalDate to) {
        return jdbc.query("""
                SELECT id, organization_id, site_id, receipt_number, party_id, receipt_date, amount, currency,
                       method, bank_account_id, journal_id, reference, created_by
                FROM ar_receipt WHERE organization_id = ? AND amount >= ? AND amount <= ?
                  AND receipt_date >= ? AND receipt_date <= ? ORDER BY receipt_date
                """, (rs, n) -> new com.ultibiz.core.domain.ArReceipt(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("site_id"),
                rs.getString("receipt_number"),
                rs.getLong("party_id"),
                rs.getDate("receipt_date").toLocalDate(),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("method"),
                nullableLong(rs, "bank_account_id"),
                rs.getLong("journal_id"),
                rs.getString("reference"),
                rs.getString("created_by")), organizationId, min, max, from, to);
    }

}
