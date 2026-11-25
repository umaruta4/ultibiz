package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.Bom;
import com.ultibiz.core.domain.BomLine;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Bills of material. */
@Repository
public class BomRepository {

    private final JdbcTemplate jdbc;

    public BomRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Bom findById(long organizationId, long id) {
        List<Bom> rows = jdbc.query("""
                SELECT id, organization_id, site_id, product_id, code, name, quantity, is_active, row_version
                FROM bom WHERE organization_id = ? AND id = ?
                """, (rs, n) -> new Bom(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                nullableLong(rs, "site_id"),
                rs.getLong("product_id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBigDecimal("quantity"),
                rs.getBoolean("is_active"),
                rs.getLong("row_version")), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Bom findByProduct(long organizationId, long productId) {
        List<Bom> rows = jdbc.query("""
                SELECT id, organization_id, site_id, product_id, code, name, quantity, is_active, row_version
                FROM bom WHERE organization_id = ? AND product_id = ? AND is_active = 1
                ORDER BY id LIMIT 1
                """, (rs, n) -> new Bom(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                nullableLong(rs, "site_id"),
                rs.getLong("product_id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBigDecimal("quantity"),
                rs.getBoolean("is_active"),
                rs.getLong("row_version")), organizationId, productId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<BomLine> lines(long bomId) {
        return jdbc.query("""
                SELECT id, bom_id, line_no, component_product_id, quantity, scrap_percent,
                       parent_line_id, is_phantom
                FROM bom_line WHERE bom_id = ? ORDER BY line_no
                """, (rs, n) -> new BomLine(
                rs.getLong("id"),
                rs.getLong("bom_id"),
                rs.getInt("line_no"),
                rs.getLong("component_product_id"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("scrap_percent"),
                nullableLong(rs, "parent_line_id"),
                rs.getBoolean("is_phantom")), bomId);
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
