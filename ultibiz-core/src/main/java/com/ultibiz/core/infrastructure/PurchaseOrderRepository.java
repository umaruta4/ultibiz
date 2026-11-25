package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.PurchaseOrder;
import com.ultibiz.core.domain.PurchaseOrderLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Supplier orders and their lines. */
@Repository
public class PurchaseOrderRepository {

    private static final String COLUMNS = """
            id, organization_id, site_id, country_code, order_number, party_id, order_date,
            expected_date, warehouse_id, currency, exchange_rate, status, subtotal, tax_total,
            total, notes, created_by, row_version
            """;

    private final JdbcTemplate jdbc;

    public PurchaseOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertHeader(long organizationId, long siteId, String countryCode, String orderNumber,
                             long partyId, LocalDate orderDate, LocalDate expectedDate, Long warehouseId,
                             String currency, BigDecimal exchangeRate, BigDecimal subtotal, BigDecimal taxTotal,
                             BigDecimal total, String notes, String actor) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO purchase_order (organization_id, site_id, country_code, order_number, party_id,
                        order_date, expected_date, warehouse_id, currency, exchange_rate, status,
                        subtotal, tax_total, total, notes, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, siteId);
            ps.setString(3, countryCode);
            ps.setString(4, orderNumber);
            ps.setLong(5, partyId);
            ps.setObject(6, orderDate);
            ps.setObject(7, expectedDate);
            ps.setObject(8, warehouseId);
            ps.setString(9, currency);
            ps.setBigDecimal(10, exchangeRate);
            ps.setBigDecimal(11, subtotal);
            ps.setBigDecimal(12, taxTotal);
            ps.setBigDecimal(13, total);
            ps.setString(14, notes);
            ps.setString(15, actor);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void insertLine(long orderId, int lineNo, Long productId, String description, BigDecimal quantity,
                            BigDecimal unitPrice, Long taxCategoryId, BigDecimal taxRate,
                            BigDecimal netAmount, BigDecimal taxAmount, BigDecimal totalAmount) {
        jdbc.update("""
                INSERT INTO purchase_order_line (order_id, line_no, product_id, description, quantity,
                    unit_price, tax_category_id, tax_rate, net_amount, tax_amount, total_amount)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, orderId, lineNo, productId, description, quantity, unitPrice, taxCategoryId, taxRate,
                netAmount, taxAmount, totalAmount);
    }

    public PurchaseOrder findById(long organizationId, long id) {
        List<PurchaseOrder> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM purchase_order WHERE organization_id = ? AND id = ?",
                (rs, n) -> map(rs), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<PurchaseOrder> list(long organizationId, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM purchase_order WHERE organization_id = ?");
        List<Object> params = new java.util.ArrayList<>();
        params.add(organizationId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY order_date DESC, id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbc.query(sql.toString(), (rs, n) -> map(rs), params.toArray());
    }

    public long count(long organizationId, String status) {
        if (status != null && !status.isBlank()) {
            Number n = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE organization_id = ? AND status = ?",
                    Number.class, organizationId, status);
            return n == null ? 0L : n.longValue();
        }
        Number n = jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE organization_id = ?",
                Number.class, organizationId);
        return n == null ? 0L : n.longValue();
    }

    public List<PurchaseOrderLine> lines(long orderId) {
        return jdbc.query("""
                SELECT id, order_id, line_no, product_id, description, quantity, unit_price,
                       tax_category_id, tax_rate, net_amount, tax_amount, total_amount, quantity_received
                FROM purchase_order_line WHERE order_id = ? ORDER BY line_no
                """, (rs, n) -> new PurchaseOrderLine(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getInt("line_no"),
                nullableLong(rs, "product_id"),
                rs.getString("description"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_price"),
                nullableLong(rs, "tax_category_id"),
                rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("net_amount"),
                rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("total_amount"),
                rs.getBigDecimal("quantity_received")), orderId);
    }

    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE purchase_order SET status = ?, row_version = row_version + 1 WHERE id = ?", status, id);
    }

    public void addReceivedQuantity(long lineId, BigDecimal quantity) {
        jdbc.update("UPDATE purchase_order_line SET quantity_received = quantity_received + ? WHERE id = ?",
                quantity, lineId);
    }

    private static PurchaseOrder map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PurchaseOrder(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("site_id"),
                rs.getString("country_code"),
                rs.getString("order_number"),
                rs.getLong("party_id"),
                rs.getDate("order_date").toLocalDate(),
                rs.getDate("expected_date") == null ? null : rs.getDate("expected_date").toLocalDate(),
                nullableLong(rs, "warehouse_id"),
                rs.getString("currency"),
                rs.getBigDecimal("exchange_rate"),
                rs.getString("status"),
                rs.getBigDecimal("subtotal"),
                rs.getBigDecimal("tax_total"),
                rs.getBigDecimal("total"),
                rs.getString("notes"),
                rs.getString("created_by"),
                rs.getLong("row_version"));
    }

    /** MariaDB returns BIGINT UNSIGNED as BigInteger, not Long. */
    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }

    /** On-order quantity per product from the open purchase orders. */
    public List<Demand> openSupply(long organizationId) {
        return jdbc.query("""
                SELECT l.product_id AS product_id,
                       SUM(l.quantity - l.quantity_received) AS quantity
                FROM purchase_order_line l
                JOIN purchase_order o ON o.id = l.order_id
                WHERE o.organization_id = ? AND o.status IN ('CONFIRMED', 'PARTIALLY_RECEIVED')
                  AND l.product_id IS NOT NULL
                GROUP BY l.product_id
                """, (rs, n) -> new Demand(rs.getLong("product_id"), rs.getBigDecimal("quantity")),
                organizationId);
    }

    /** A demand or supply quantity per product. */
    public record Demand(long productId, java.math.BigDecimal quantity) {
    }
}
