package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.Asset;
import com.ultibiz.core.domain.AssetCategory;
import com.ultibiz.core.domain.AssetDepreciation;
import com.ultibiz.core.domain.AssetDisposal;
import com.ultibiz.core.domain.AssetMaintenance;
import com.ultibiz.core.domain.AssetMeterReading;
import com.ultibiz.core.domain.MaintenancePlan;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Asset register, categories, depreciation runs, disposals and maintenance. */
@Repository
public class AssetRepository {

    private static final String ASSET_COLUMNS = """
            id, organization_id, site_id, category_id, code, name, acquisition_date, acquisition_cost,
            salvage_value, accumulated_depreciation, book_value, units_produced, location_id, serial_number,
            supplier_party_id, purchase_order_id, warranty_end, status, last_maintenance_date,
            next_maintenance_date, row_version
            """;

    private static final String CATEGORY_COLUMNS = """
            id, organization_id, code, name, depreciation_method, useful_life_months,
            salvage_percent, total_production_units, asset_account_id,
            accumulated_depreciation_account_id, depreciation_expense_account_id, is_active
            """;

    private final JdbcTemplate jdbc;

    public AssetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AssetCategory> categories(long organizationId) {
        return jdbc.query("SELECT " + CATEGORY_COLUMNS
                        + " FROM asset_category WHERE organization_id = ? ORDER BY code",
                (rs, n) -> mapCategory(rs), organizationId);
    }

    public AssetCategory findCategory(long organizationId, long id) {
        List<AssetCategory> rows = jdbc.query("SELECT " + CATEGORY_COLUMNS
                        + " FROM asset_category WHERE organization_id = ? AND id = ?",
                (rs, n) -> mapCategory(rs), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Asset> list(long organizationId, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + ASSET_COLUMNS
                + " FROM asset WHERE organization_id = ?");
        List<Object> params = new java.util.ArrayList<>();
        params.add(organizationId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY code LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return jdbc.query(sql.toString(), (rs, n) -> map(rs), params.toArray());
    }

    public long count(long organizationId, String status) {
        if (status != null && !status.isBlank()) {
            Number n = jdbc.queryForObject("SELECT COUNT(*) FROM asset WHERE organization_id = ? AND status = ?",
                    Number.class, organizationId, status);
            return n == null ? 0L : n.longValue();
        }
        Number n = jdbc.queryForObject("SELECT COUNT(*) FROM asset WHERE organization_id = ?",
                Number.class, organizationId);
        return n == null ? 0L : n.longValue();
    }

    public Asset findById(long organizationId, long id) {
        List<Asset> rows = jdbc.query("SELECT " + ASSET_COLUMNS
                        + " FROM asset WHERE organization_id = ? AND id = ?",
                (rs, n) -> map(rs), organizationId, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Asset findByCode(long organizationId, String code) {
        List<Asset> rows = jdbc.query("SELECT " + ASSET_COLUMNS
                        + " FROM asset WHERE organization_id = ? AND code = ?",
                (rs, n) -> map(rs), organizationId, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long insert(long organizationId, long siteId, long categoryId, String code, String name,
                       LocalDate acquisitionDate, BigDecimal cost, BigDecimal salvageValue, Long locationId,
                       String serialNumber, Long supplierPartyId, Long purchaseOrderId, LocalDate warrantyEnd,
                       LocalDate nextMaintenanceDate) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO asset (organization_id, site_id, category_id, code, name, acquisition_date,
                        acquisition_cost, salvage_value, accumulated_depreciation, book_value, units_produced,
                        location_id, serial_number, supplier_party_id, purchase_order_id, warranty_end, status,
                        next_maintenance_date)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, 0, ?, ?, ?, ?, ?, 'IN_SERVICE', ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, siteId);
            ps.setLong(3, categoryId);
            ps.setString(4, code);
            ps.setString(5, name);
            ps.setObject(6, acquisitionDate);
            ps.setBigDecimal(7, cost);
            ps.setBigDecimal(8, salvageValue);
            ps.setBigDecimal(9, cost);
            ps.setObject(10, locationId);
            ps.setString(11, serialNumber);
            ps.setObject(12, supplierPartyId);
            ps.setObject(13, purchaseOrderId);
            ps.setObject(14, warrantyEnd);
            ps.setObject(15, nextMaintenanceDate);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public void updateDepreciation(long assetId, BigDecimal accumulated, BigDecimal bookValue,
                                    BigDecimal unitsProduced) {
        jdbc.update("""
                UPDATE asset SET accumulated_depreciation = ?, book_value = ?, units_produced = ?,
                    row_version = row_version + 1 WHERE id = ?
                """, accumulated, bookValue, unitsProduced, assetId);
    }

    public void updateStatus(long assetId, String status) {
        jdbc.update("UPDATE asset SET status = ?, row_version = row_version + 1 WHERE id = ?", status, assetId);
    }

    public void updateSite(long assetId, long siteId) {
        jdbc.update("UPDATE asset SET site_id = ?, row_version = row_version + 1 WHERE id = ?", siteId, assetId);
    }

    public void updateMaintenanceDates(long assetId, LocalDate last, LocalDate next) {
        jdbc.update("""
                UPDATE asset SET last_maintenance_date = ?, next_maintenance_date = ?,
                    row_version = row_version + 1 WHERE id = ?
                """, last, next, assetId);
    }

    public long insertDepreciation(long organizationId, long assetId, int year, int month, String method,
                                    BigDecimal amount, BigDecimal accumulated, BigDecimal bookValue,
                                    Long journalId) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO asset_depreciation (organization_id, asset_id, period_year, period_month,
                        method, depreciation_amount, accumulated_amount, book_value_after, journal_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, assetId);
            ps.setInt(3, year);
            ps.setInt(4, month);
            ps.setString(5, method);
            ps.setBigDecimal(6, amount);
            ps.setBigDecimal(7, accumulated);
            ps.setBigDecimal(8, bookValue);
            ps.setObject(9, journalId);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public boolean depreciationExists(long assetId, int year, int month) {
        Number n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM asset_depreciation WHERE asset_id = ? AND period_year = ? AND period_month = ?",
                Number.class, assetId, year, month);
        return n != null && n.longValue() > 0;
    }

    public List<AssetDepreciation> depreciationOf(long assetId) {
        return jdbc.query("""
                SELECT id, organization_id, asset_id, period_year, period_month, method,
                       depreciation_amount, accumulated_amount, book_value_after, journal_id
                FROM asset_depreciation WHERE asset_id = ? ORDER BY period_year DESC, period_month DESC
                """, (rs, n) -> new AssetDepreciation(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("asset_id"),
                rs.getInt("period_year"),
                rs.getInt("period_month"),
                rs.getString("method"),
                rs.getBigDecimal("depreciation_amount"),
                rs.getBigDecimal("accumulated_amount"),
                rs.getBigDecimal("book_value_after"),
                nullableLong(rs, "journal_id")), assetId);
    }

    public long insertDisposal(long organizationId, long assetId, LocalDate disposalDate, BigDecimal proceeds,
                               BigDecimal bookValue, BigDecimal gainLoss, String reason, Long journalId,
                               String actor) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO asset_disposal (organization_id, asset_id, disposal_date, proceeds, book_value,
                        gain_loss, reason, journal_id, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, assetId);
            ps.setObject(3, disposalDate);
            ps.setBigDecimal(4, proceeds);
            ps.setBigDecimal(5, bookValue);
            ps.setBigDecimal(6, gainLoss);
            ps.setString(7, reason);
            ps.setObject(8, journalId);
            ps.setString(9, actor);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public List<MaintenancePlan> plans(long organizationId) {
        return jdbc.query("""
                SELECT id, organization_id, category_id, code, name, interval_months, description, is_active
                FROM maintenance_plan WHERE organization_id = ? ORDER BY code
                """, (rs, n) -> new MaintenancePlan(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                nullableLong(rs, "category_id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getInt("interval_months"),
                rs.getString("description"),
                rs.getBoolean("is_active")), organizationId);
    }

    public long insertMaintenance(long organizationId, long assetId, Long planId, LocalDate dueDate,
                                  String note, String actor) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO asset_maintenance (organization_id, asset_id, plan_id, due_date, status,
                        cost, note, created_by)
                    VALUES (?, ?, ?, ?, 'OPEN', 0, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, organizationId);
            ps.setLong(2, assetId);
            ps.setObject(3, planId);
            ps.setObject(4, dueDate);
            ps.setString(5, note);
            ps.setString(6, actor);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    public List<AssetMaintenance> maintenance(long organizationId, String status) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, organization_id, asset_id, plan_id, due_date, done_date, status, cost, note, created_by
                FROM asset_maintenance WHERE organization_id = ?
                """);
        List<Object> params = new java.util.ArrayList<>();
        params.add(organizationId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY due_date");
        return jdbc.query(sql.toString(), (rs, n) -> new AssetMaintenance(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("asset_id"),
                nullableLong(rs, "plan_id"),
                rs.getDate("due_date").toLocalDate(),
                rs.getDate("done_date") == null ? null : rs.getDate("done_date").toLocalDate(),
                rs.getString("status"),
                rs.getBigDecimal("cost"),
                rs.getString("note"),
                rs.getString("created_by")), params.toArray());
    }

    public void completeMaintenance(long id, LocalDate doneDate, BigDecimal cost) {
        jdbc.update("UPDATE asset_maintenance SET done_date = ?, status = 'DONE', cost = ? WHERE id = ?",
                doneDate, cost, id);
    }

    public long insertMeterReading(long assetId, LocalDate readingDate, BigDecimal meterValue,
                                   BigDecimal previousValue, BigDecimal consumption) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement("""
                    INSERT INTO asset_meter_reading (asset_id, reading_date, meter_value, previous_value, consumption)
                    VALUES (?, ?, ?, ?, ?)
                    """, new String[] {"id"});
            ps.setLong(1, assetId);
            ps.setObject(2, readingDate);
            ps.setBigDecimal(3, meterValue);
            ps.setBigDecimal(4, previousValue);
            ps.setBigDecimal(5, consumption);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? 0L : key.longValue();
    }

    /** Units produced in a period (meter readings) — drives units-of-production depreciation. */
    public BigDecimal unitsProducedIn(long assetId, LocalDate from, LocalDate to) {
        Number n = jdbc.queryForObject("""
                SELECT COALESCE(SUM(consumption), 0) FROM asset_meter_reading
                WHERE asset_id = ? AND reading_date >= ? AND reading_date <= ?
                """, Number.class, assetId, from, to);
        return n == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(n));
    }

    public List<AssetMeterReading> meterReadings(long assetId) {
        return jdbc.query("""
                SELECT id, asset_id, reading_date, meter_value, previous_value, consumption
                FROM asset_meter_reading WHERE asset_id = ? ORDER BY reading_date DESC
                """, (rs, n) -> new AssetMeterReading(
                rs.getLong("id"),
                rs.getLong("asset_id"),
                rs.getDate("reading_date").toLocalDate(),
                rs.getBigDecimal("meter_value"),
                rs.getBigDecimal("previous_value"),
                rs.getBigDecimal("consumption")), assetId);
    }

    private static AssetCategory mapCategory(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AssetCategory(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("depreciation_method"),
                rs.getInt("useful_life_months"),
                rs.getBigDecimal("salvage_percent"),
                rs.getBigDecimal("total_production_units"),
                nullableLong(rs, "asset_account_id"),
                nullableLong(rs, "accumulated_depreciation_account_id"),
                nullableLong(rs, "depreciation_expense_account_id"),
                rs.getBoolean("is_active"));
    }

    private static Asset map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Asset(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("site_id"),
                rs.getLong("category_id"),
                rs.getString("code"),
                rs.getString("name"),
                rs.getDate("acquisition_date").toLocalDate(),
                rs.getBigDecimal("acquisition_cost"),
                rs.getBigDecimal("salvage_value"),
                rs.getBigDecimal("accumulated_depreciation"),
                rs.getBigDecimal("book_value"),
                rs.getBigDecimal("units_produced"),
                nullableLong(rs, "location_id"),
                rs.getString("serial_number"),
                nullableLong(rs, "supplier_party_id"),
                nullableLong(rs, "purchase_order_id"),
                rs.getDate("warranty_end") == null ? null : rs.getDate("warranty_end").toLocalDate(),
                rs.getString("status"),
                rs.getDate("last_maintenance_date") == null ? null
                        : rs.getDate("last_maintenance_date").toLocalDate(),
                rs.getDate("next_maintenance_date") == null ? null
                        : rs.getDate("next_maintenance_date").toLocalDate(),
                rs.getLong("row_version"));
    }

    /** MariaDB returns BIGINT UNSIGNED as BigInteger, not Long. */
    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    /** All depreciation rows of one period, newest asset first. */
    public List<AssetDepreciation> depreciationForPeriod(long organizationId, int year, int month) {
        return jdbc.query("""
                SELECT id, organization_id, asset_id, period_year, period_month, method,
                       depreciation_amount, accumulated_amount, book_value_after, journal_id
                FROM asset_depreciation
                WHERE organization_id = ? AND period_year = ? AND period_month = ?
                ORDER BY asset_id
                """, (rs, n) -> new AssetDepreciation(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("asset_id"),
                rs.getInt("period_year"),
                rs.getInt("period_month"),
                rs.getString("method"),
                rs.getBigDecimal("depreciation_amount"),
                rs.getBigDecimal("accumulated_amount"),
                rs.getBigDecimal("book_value_after"),
                nullableLong(rs, "journal_id")), organizationId, year, month);
    }

    /** The maintenance jobs of one asset. */
    public List<AssetMaintenance> maintenanceOf(long assetId) {
        return jdbc.query("""
                SELECT id, organization_id, asset_id, plan_id, due_date, done_date, status, cost, note, created_by
                FROM asset_maintenance WHERE asset_id = ? ORDER BY due_date
                """, (rs, n) -> new AssetMaintenance(
                rs.getLong("id"),
                rs.getLong("organization_id"),
                rs.getLong("asset_id"),
                nullableLong(rs, "plan_id"),
                rs.getDate("due_date").toLocalDate(),
                rs.getDate("done_date") == null ? null : rs.getDate("done_date").toLocalDate(),
                rs.getString("status"),
                rs.getBigDecimal("cost"),
                rs.getString("note"),
                rs.getString("created_by")), assetId);
    }
}
