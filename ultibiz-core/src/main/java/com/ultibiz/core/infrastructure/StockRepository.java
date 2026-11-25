package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.StockBalance;
import com.ultibiz.core.domain.StockMovement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Stock balances (with moving average cost) and the immutable movement ledger. */
@Repository
public class StockRepository {

    private final JdbcTemplate jdbc;

    public StockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the balance row of a product in a warehouse (or at one location). */
    public StockBalance lockBalance(long organizationId, long warehouseId, Long locationId, long productId) {
        List<StockBalance> rows = jdbc.query("""
                SELECT id, organization_id, site_id, warehouse_id, location_id, product_id, quantity,
                       average_cost, total_value
                FROM stock_balance
                WHERE organization_id = ? AND warehouse_id = ? AND product_id = ?
                  AND (location_id = ? OR (? IS NULL AND location_id IS NULL))
                FOR UPDATE
                """, (rs, n) -> map(rs),
                organizationId, warehouseId, productId, locationId, locationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void insertBalance(long organizationId, long siteId, long warehouseId, Long locationId,
                               long productId, BigDecimal quantity, BigDecimal averageCost, BigDecimal totalValue) {
        jdbc.update("""
                INSERT INTO stock_balance (organization_id, site_id, warehouse_id, location_id, product_id,
                    quantity, average_cost, total_value)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, organizationId, siteId, warehouseId, locationId, productId, quantity, averageCost, totalValue);
    }

    public void updateBalance(long id, BigDecimal quantity, BigDecimal averageCost, BigDecimal totalValue) {
        jdbc.update("""
                UPDATE stock_balance SET quantity = ?, average_cost = ?, total_value = ?,
                    row_version = row_version + 1 WHERE id = ?
                """, quantity, averageCost, totalValue, id);
    }

    public long insertMovement(long organizationId, long siteId, long warehouseId, Long locationId,
                               long productId, LocalDate movementDate, BigDecimal quantity, BigDecimal unitCost,
                               BigDecimal totalValue, BigDecimal balanceAfter, String sourceType, Long sourceId,
                               Integer sourceLine, String actor) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO stock_movement (organization_id, site_id, warehouse_id, location_id, product_id,
                        movement_date, quantity, unit_cost, total_value, balance_after, source_type, source_id,
                        source_line, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, siteId);
            ps.setLong(3, warehouseId);
            ps.setObject(4, locationId);
            ps.setLong(5, productId);
            ps.setObject(6, movementDate);
            ps.setBigDecimal(7, quantity);
            ps.setBigDecimal(8, unitCost);
            ps.setBigDecimal(9, totalValue);
            ps.setBigDecimal(10, balanceAfter);
            ps.setString(11, sourceType);
            ps.setObject(12, sourceId);
            ps.setObject(13, sourceLine);
            ps.setString(14, actor);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public List<StockMovement> movements(long organizationId, long productId, int limit) {
        return jdbc.query("""
                SELECT id, organization_id, site_id, warehouse_id, location_id, product_id, movement_date,
                       quantity, unit_cost, total_value, balance_after, source_type, source_id, source_line,
                       created_by
                FROM stock_movement WHERE organization_id = ? AND product_id = ?
                ORDER BY movement_date DESC, id DESC LIMIT ?
                """, (rs, n) -> new StockMovement(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("site_id"),
                rs.getLong("warehouse_id"),
                nullableLong(rs, "location_id"),
                rs.getLong("product_id"),
                rs.getDate("movement_date").toLocalDate(),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("unit_cost"),
                rs.getBigDecimal("total_value"),
                rs.getBigDecimal("balance_after"),
                rs.getString("source_type"),
                nullableLong(rs, "source_id"),
                nullableInt(rs, "source_line"),
                rs.getString("created_by")), organizationId, productId, limit);
    }

    /** Stock of a product across all warehouses (no location split). */
    public List<StockBalance> balances(long organizationId, Long productId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, organization_id, site_id, warehouse_id, location_id, product_id, quantity,
                       average_cost, total_value
                FROM stock_balance WHERE organization_id = ? AND location_id IS NULL
                """);
        List<Object> params = new java.util.ArrayList<>();
        params.add(organizationId);
        if (productId != null) {
            sql.append(" AND product_id = ?");
            params.add(productId);
        }
        sql.append(" ORDER BY product_id");
        return jdbc.query(sql.toString(), (rs, n) -> map(rs), params.toArray());
    }

    private static StockBalance map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new StockBalance(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("site_id"),
                rs.getLong("warehouse_id"),
                nullableLong(rs, "location_id"),
                rs.getLong("product_id"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("average_cost"),
                rs.getBigDecimal("total_value"));
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
