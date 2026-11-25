package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.WorkOrder;
import com.ultibiz.core.domain.WorkOrderMaterial;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Work orders and their material requirements. */
@Repository
public class WorkOrderRepository {

    private static final String COLUMNS = """
            id, organization_id, site_id, order_number, product_id, bom_id, production_line_id, warehouse_id,
            quantity, quantity_produced, status, planned_start, planned_end, actual_start, actual_end,
            material_cost, created_by, row_version
            """;

    private final JdbcTemplate jdbc;

    public WorkOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertHeader(long organizationId, long siteId, String orderNumber, long productId, Long bomId,
                             Long productionLineId, long warehouseId, BigDecimal quantity,
                             LocalDate plannedStart, LocalDate plannedEnd, String actor) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO work_order (organization_id, site_id, order_number, product_id, bom_id,
                        production_line_id, warehouse_id, quantity, quantity_produced, status,
                        planned_start, planned_end, material_cost, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 'PLANNED', ?, ?, 0, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, siteId);
            ps.setString(3, orderNumber);
            ps.setLong(4, productId);
            ps.setObject(5, bomId);
            ps.setObject(6, productionLineId);
            ps.setLong(7, warehouseId);
            ps.setBigDecimal(8, quantity);
            ps.setObject(9, plannedStart);
            ps.setObject(10, plannedEnd);
            ps.setString(11, actor);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void insertMaterial(long workOrderId, int lineNo, long productId, BigDecimal quantityRequired) {
        jdbc.update("""
                INSERT INTO work_order_material (work_order_id, line_no, product_id, quantity_required,
                    quantity_issued)
                VALUES (?, ?, ?, ?, 0)
                """, workOrderId, lineNo, productId, quantityRequired);
    }

    public WorkOrder findById(long organizationId, long id) {
        List<WorkOrder> rows = jdbc.query("SELECT " + COLUMNS
                + " FROM work_order WHERE organization_id = ? AND id = ?",
                (rs, n) -> map(rs), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<WorkOrder> list(long organizationId, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM work_order WHERE organization_id = ?");
        List<Object> params = new java.util.ArrayList<>();
        params.add(organizationId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbc.query(sql.toString(), (rs, n) -> map(rs), params.toArray());
    }

    public long count(long organizationId, String status) {
        if (status != null && !status.isBlank()) {
            Number n = jdbc.queryForObject("SELECT COUNT(*) FROM work_order WHERE organization_id = ? AND status = ?",
                    Number.class, organizationId, status);
            return n == null ? 0L : n.longValue();
        }
        Number n = jdbc.queryForObject("SELECT COUNT(*) FROM work_order WHERE organization_id = ?",
                Number.class, organizationId);
        return n == null ? 0L : n.longValue();
    }

    public List<WorkOrderMaterial> materials(long workOrderId) {
        return jdbc.query("""
                SELECT id, work_order_id, line_no, product_id, quantity_required, quantity_issued
                FROM work_order_material WHERE work_order_id = ? ORDER BY line_no
                """, (rs, n) -> new WorkOrderMaterial(
                rs.getLong("id"),
                rs.getLong("work_order_id"),
                rs.getInt("line_no"),
                rs.getLong("product_id"),
                rs.getBigDecimal("quantity_required"),
                rs.getBigDecimal("quantity_issued")), workOrderId);
    }

    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE work_order SET status = ?, row_version = row_version + 1 WHERE id = ?", status, id);
    }

    public void updateMaterialIssued(long materialId, BigDecimal quantity) {
        jdbc.update("UPDATE work_order_material SET quantity_issued = quantity_issued + ? WHERE id = ?",
                quantity, materialId);
    }

    public void updateProducedAndCost(long id, BigDecimal produced, BigDecimal materialCost,
                                      LocalDate actualEnd, String status) {
        jdbc.update("""
                UPDATE work_order SET quantity_produced = ?, material_cost = ?, actual_end = ?,
                    status = ?, row_version = row_version + 1 WHERE id = ?
                """, produced, materialCost, actualEnd, status, id);
    }

    private static WorkOrder map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new WorkOrder(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("site_id"),
                rs.getString("order_number"),
                rs.getLong("product_id"),
                nullableLong(rs, "bom_id"),
                nullableLong(rs, "production_line_id"),
                rs.getLong("warehouse_id"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("quantity_produced"),
                rs.getString("status"),
                rs.getDate("planned_start") == null ? null : rs.getDate("planned_start").toLocalDate(),
                rs.getDate("planned_end") == null ? null : rs.getDate("planned_end").toLocalDate(),
                rs.getDate("actual_start") == null ? null : rs.getDate("actual_start").toLocalDate(),
                rs.getDate("actual_end") == null ? null : rs.getDate("actual_end").toLocalDate(),
                rs.getBigDecimal("material_cost"),
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
}
