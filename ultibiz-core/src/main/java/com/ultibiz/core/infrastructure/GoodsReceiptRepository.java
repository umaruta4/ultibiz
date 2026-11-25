package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.GoodsReceipt;
import com.ultibiz.core.domain.GoodsReceiptLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Goods receipts / issues and their lines. */
@Repository
public class GoodsReceiptRepository {

    private static final String COLUMNS = """
            id, organization_id, site_id, receipt_number, source_type, purchase_order_id, sales_order_id,
            work_order_id, party_id, warehouse_id, receipt_date, currency, status, notes, created_by
            """;

    private final JdbcTemplate jdbc;

    public GoodsReceiptRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertHeader(long organizationId, long siteId, String receiptNumber, String sourceType,
                             Long purchaseOrderId, Long salesOrderId, Long workOrderId, Long partyId,
                             long warehouseId, LocalDate receiptDate, String currency, String notes, String actor) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO goods_receipt (organization_id, site_id, receipt_number, source_type,
                        purchase_order_id, sales_order_id, work_order_id, party_id, warehouse_id,
                        receipt_date, currency, status, notes, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, siteId);
            ps.setString(3, receiptNumber);
            ps.setString(4, sourceType);
            ps.setObject(5, purchaseOrderId);
            ps.setObject(6, salesOrderId);
            ps.setObject(7, workOrderId);
            ps.setObject(8, partyId);
            ps.setLong(9, warehouseId);
            ps.setObject(10, receiptDate);
            ps.setString(11, currency);
            ps.setString(12, notes);
            ps.setString(13, actor);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void insertLine(long receiptId, int lineNo, long productId, Long locationId, BigDecimal quantity,
                            BigDecimal unitCost, BigDecimal totalCost) {
        jdbc.update("""
                INSERT INTO goods_receipt_line (receipt_id, line_no, product_id, location_id, quantity,
                    unit_cost, total_cost)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, receiptId, lineNo, productId, locationId, quantity, unitCost, totalCost);
    }

    public GoodsReceipt findById(long organizationId, long id) {
        List<GoodsReceipt> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM goods_receipt WHERE organization_id = ? AND id = ?",
                (rs, n) -> map(rs), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<GoodsReceipt> list(long organizationId, String sourceType, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM goods_receipt WHERE organization_id = ?");
        List<Object> params = new java.util.ArrayList<>();
        params.add(organizationId);
        if (sourceType != null && !sourceType.isBlank()) {
            sql.append(" AND source_type = ?");
            params.add(sourceType);
        }
        sql.append(" ORDER BY receipt_date DESC, id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbc.query(sql.toString(), (rs, n) -> map(rs), params.toArray());
    }

    public List<GoodsReceiptLine> lines(long receiptId) {
        return jdbc.query("""
                SELECT id, receipt_id, line_no, product_id, location_id, quantity, unit_cost, total_cost
                FROM goods_receipt_line WHERE receipt_id = ? ORDER BY line_no
                """, (rs, n) -> new GoodsReceiptLine(
                rs.getLong("id"),
                rs.getLong("receipt_id"),
                rs.getInt("line_no"),
                rs.getLong("product_id"),
                nullableLong(rs, "location_id"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("total_cost")), receiptId);
    }

    private static GoodsReceipt map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new GoodsReceipt(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("site_id"),
                rs.getString("receipt_number"),
                rs.getString("source_type"),
                nullableLong(rs, "purchase_order_id"),
                nullableLong(rs, "sales_order_id"),
                nullableLong(rs, "work_order_id"),
                nullableLong(rs, "party_id"),
                rs.getLong("warehouse_id"),
                rs.getDate("receipt_date").toLocalDate(),
                rs.getString("currency"),
                rs.getString("status"),
                rs.getString("notes"),
                rs.getString("created_by"));
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
}
