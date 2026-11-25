package com.ultibiz.core.infrastructure;

import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.SiteScopedSql;
import com.ultibiz.core.domain.ProductionLine;
import com.ultibiz.core.domain.WorkCenter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** Production lines and the work centers they run on. */
@Repository
public class ProductionLineRepository {

    private static final String COLUMNS = """
            id, organization_id, site_id, code, name, warehouse_id, work_center_id,
            capacity_per_day, is_active, row_version
            """;

    private final DSLContext dsl;

    public ProductionLineRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<ProductionLine> list(SiteScope scope, String search, boolean activeOnly, int limit, int offset) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(scope, search, activeOnly, params);
        params.add(limit);
        params.add(offset);
        return dsl.fetch("SELECT " + COLUMNS + " FROM production_line WHERE " + where
                + " ORDER BY code LIMIT ? OFFSET ?", params.toArray()).map(ProductionLineRepository::map);
    }

    public long count(SiteScope scope, String search, boolean activeOnly) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(scope, search, activeOnly, params);
        Number n = (Number) dsl.fetchValue("SELECT COUNT(*) FROM production_line WHERE " + where, params.toArray());
        return n == null ? 0L : n.longValue();
    }

    public Optional<ProductionLine> findById(SiteScope scope, long id) {
        List<Object> params = new ArrayList<>();
        String where = SiteScopedSql.whereClause("organization_id", "site_id", scope, params);
        params.add(id);
        Record r = dsl.fetchOne("SELECT " + COLUMNS + " FROM production_line WHERE " + where
                + " AND id = ? AND deleted_at IS NULL", params.toArray());
        return r == null ? Optional.empty() : Optional.of(map(r));
    }

    public List<WorkCenter> workCenters(SiteScope scope) {
        List<Object> params = new ArrayList<>();
        String where = SiteScopedSql.whereClause("organization_id", "site_id", scope, params);
        return dsl.fetch("SELECT id, organization_id, site_id, code, name, work_center_type, "
                + "capacity_hours_per_day, is_active, row_version FROM work_center WHERE " + where
                + " AND deleted_at IS NULL ORDER BY code", params.toArray()).map(r -> new WorkCenter(
                RowValues.asLong(r.get("id")),
                RowValues.asLong(r.get("organization_id")),
                RowValues.asLong(r.get("site_id")),
                RowValues.asString(r.get("code")),
                RowValues.asString(r.get("name")),
                RowValues.asString(r.get("work_center_type")),
                r.get("capacity_hours_per_day") == null ? null
                        : new java.math.BigDecimal(String.valueOf(r.get("capacity_hours_per_day"))),
                RowValues.asBoolean(r.get("is_active")),
                RowValues.asLong(r.get("row_version"))));
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

    private static ProductionLine map(Record r) {
        return new ProductionLine(
                RowValues.asLong(r.get("id")),
                RowValues.asLong(r.get("organization_id")),
                RowValues.asLong(r.get("site_id")),
                RowValues.asString(r.get("code")),
                RowValues.asString(r.get("name")),
                RowValues.asNullableLong(r.get("warehouse_id")),
                RowValues.asNullableLong(r.get("work_center_id")),
                r.get("capacity_per_day") == null ? null
                        : new java.math.BigDecimal(String.valueOf(r.get("capacity_per_day"))),
                RowValues.asBoolean(r.get("is_active")),
                RowValues.asLong(r.get("row_version")));
    }
}
