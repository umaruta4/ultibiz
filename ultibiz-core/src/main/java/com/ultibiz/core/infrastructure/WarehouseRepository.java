package com.ultibiz.core.infrastructure;

import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.SiteScopedSql;
import com.ultibiz.core.domain.Dock;
import com.ultibiz.core.domain.Rack;
import com.ultibiz.core.domain.StorageLocation;
import com.ultibiz.core.domain.Warehouse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** Warehouses and their structure: racks, storage locations (bins) and docks. */
@Repository
public class WarehouseRepository {

    private static final String COLUMNS = """
            id, organization_id, site_id, code, name, warehouse_type, country_code, address,
            is_active, row_version
            """;

    private final DSLContext dsl;

    public WarehouseRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<Warehouse> list(SiteScope scope, String search, boolean activeOnly, int limit, int offset) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(scope, search, activeOnly, params);
        params.add(limit);
        params.add(offset);
        return dsl.fetch("SELECT " + COLUMNS + " FROM warehouse WHERE " + where
                + " ORDER BY code LIMIT ? OFFSET ?", params.toArray()).map(WarehouseRepository::map);
    }

    public long count(SiteScope scope, String search, boolean activeOnly) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(scope, search, activeOnly, params);
        Number n = (Number) dsl.fetchValue("SELECT COUNT(*) FROM warehouse WHERE " + where, params.toArray());
        return n == null ? 0L : n.longValue();
    }

    public Optional<Warehouse> findById(SiteScope scope, long id) {
        List<Object> params = new ArrayList<>();
        String where = SiteScopedSql.whereClause("organization_id", "site_id", scope, params);
        params.add(id);
        Record r = dsl.fetchOne("SELECT " + COLUMNS + " FROM warehouse WHERE " + where
                + " AND id = ? AND deleted_at IS NULL", params.toArray());
        return r == null ? Optional.empty() : Optional.of(map(r));
    }

    public List<Rack> racks(long warehouseId) {
        return dsl.fetch("SELECT id, warehouse_id, code, name FROM rack WHERE warehouse_id = ? ORDER BY code",
                warehouseId).map(r -> new Rack(
                RowValues.asLong(r.get("id")),
                RowValues.asLong(r.get("warehouse_id")),
                RowValues.asString(r.get("code")),
                RowValues.asString(r.get("name"))));
    }

    public List<StorageLocation> locations(long warehouseId) {
        return dsl.fetch("""
                SELECT id, warehouse_id, rack_id, code, name, location_type, capacity
                FROM storage_location WHERE warehouse_id = ? ORDER BY code
                """, warehouseId).map(r -> new StorageLocation(
                RowValues.asLong(r.get("id")),
                RowValues.asLong(r.get("warehouse_id")),
                RowValues.asNullableLong(r.get("rack_id")),
                RowValues.asString(r.get("code")),
                RowValues.asString(r.get("name")),
                RowValues.asString(r.get("location_type")),
                r.get("capacity") == null ? null
                        : new java.math.BigDecimal(String.valueOf(r.get("capacity")))));
    }

    public List<Dock> docks(long warehouseId) {
        return dsl.fetch("SELECT id, warehouse_id, code, name, dock_type FROM dock WHERE warehouse_id = ? ORDER BY code",
                warehouseId).map(r -> new Dock(
                RowValues.asLong(r.get("id")),
                RowValues.asLong(r.get("warehouse_id")),
                RowValues.asString(r.get("code")),
                RowValues.asString(r.get("name")),
                RowValues.asString(r.get("dock_type"))));
    }

    private String buildWhere(SiteScope scope, String search, boolean activeOnly, List<Object> params) {
        StringBuilder sb = new StringBuilder("deleted_at IS NULL AND ");
        sb.append(SiteScopedSql.whereClause("organization_id", "site_id", scope, params));
        if (search != null && !search.isBlank()) {
            sb.append(" AND (code LIKE ? OR name LIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
        }
        if (activeOnly) {
            sb.append(" AND is_active = 1");
        }
        return sb.toString();
    }

    private static Warehouse map(Record r) {
        return new Warehouse(
                RowValues.asLong(r.get("id")),
                RowValues.asLong(r.get("organization_id")),
                RowValues.asLong(r.get("site_id")),
                RowValues.asString(r.get("code")),
                RowValues.asString(r.get("name")),
                RowValues.asString(r.get("warehouse_type")),
                RowValues.asString(r.get("country_code")),
                RowValues.asString(r.get("address")),
                RowValues.asBoolean(r.get("is_active")),
                RowValues.asLong(r.get("row_version")));
    }
}
