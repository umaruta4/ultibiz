package com.ultibiz.core.infrastructure;

import com.ultibiz.core.application.ProductDraft;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.SiteScopedSql;
import com.ultibiz.core.domain.Product;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/**
 * Product master. Every read and write goes through the caller's site scope;
 * products with a NULL site are shared across the organization.
 */
@Repository
public class ProductRepository {

    private static final String COLUMNS = """
            id, organization_id, site_id, code, name, product_type, uom_code, barcode,
            batch_tracked, serial_tracked, track_inventory, standard_cost, currency,
            tax_category_id, description, is_active, row_version
            """;

    private final DSLContext dsl;

    public ProductRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<Product> list(SiteScope scope, String search, String productType,
                              boolean activeOnly, int limit, int offset) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(scope, search, productType, activeOnly, params);
        String sql = "SELECT " + COLUMNS + " FROM product WHERE " + where
                + " ORDER BY code LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);
        return dsl.fetch(sql, params.toArray()).map(ProductRepository::map);
    }

    public long count(SiteScope scope, String search, String productType, boolean activeOnly) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(scope, search, productType, activeOnly, params);
        Number n = (Number) dsl.fetchValue(
                "SELECT COUNT(*) FROM product WHERE " + where, params.toArray());
        return n == null ? 0L : n.longValue();
    }

    public Optional<Product> findById(SiteScope scope, long id) {
        List<Object> params = new ArrayList<>();
        String where = SiteScopedSql.masterDataClause("organization_id", "site_id", scope, params);
        params.add(id);
        Record record = dsl.fetchOne("SELECT " + COLUMNS + " FROM product WHERE " + where
                + " AND id = ? AND deleted_at IS NULL", params.toArray());
        return record == null ? Optional.empty() : Optional.of(map(record));
    }

    public Optional<Product> findByCode(SiteScope scope, String code) {
        List<Object> params = new ArrayList<>();
        String where = SiteScopedSql.masterDataClause("organization_id", "site_id", scope, params);
        params.add(code);
        Record record = dsl.fetchOne("SELECT " + COLUMNS + " FROM product WHERE " + where
                + " AND code = ? AND deleted_at IS NULL", params.toArray());
        return record == null ? Optional.empty() : Optional.of(map(record));
    }

    public long insert(SiteScope scope, ProductDraft draft) {
        String sql = """
                INSERT INTO product (organization_id, site_id, code, name, product_type, uom_code,
                    barcode, batch_tracked, serial_tracked, track_inventory, standard_cost, currency,
                    tax_category_id, description, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        Object[] bindings = {
                scope.organizationId(), draft.siteId(), draft.code(), draft.name(),
                draft.productType(), draft.uomCode(), draft.barcode(),
                RowValues.boolFlag(draft.batchTracked()), RowValues.boolFlag(draft.serialTracked()),
                RowValues.boolFlag(draft.trackInventory()), draft.standardCost(), draft.currency(),
                draft.taxCategoryId(), draft.description(), RowValues.boolFlag(draft.active())
        };
        dsl.execute(sql, bindings);
        return lastInsertId();
    }

    public boolean update(SiteScope scope, long id, ProductDraft draft, long expectedVersion) {
        List<Object> params = new ArrayList<>();
        String where = SiteScopedSql.masterDataClause("organization_id", "site_id", scope, params);
        params.add(draft.siteId());
        params.add(draft.name());
        params.add(draft.productType());
        params.add(draft.uomCode());
        params.add(draft.barcode());
        params.add(RowValues.boolFlag(draft.batchTracked()));
        params.add(RowValues.boolFlag(draft.serialTracked()));
        params.add(RowValues.boolFlag(draft.trackInventory()));
        params.add(draft.standardCost());
        params.add(draft.currency());
        params.add(draft.taxCategoryId());
        params.add(draft.description());
        params.add(RowValues.boolFlag(draft.active()));
        params.add(id);
        params.add(expectedVersion);
        int updated = dsl.execute("""
                UPDATE product SET site_id = ?, name = ?, product_type = ?, uom_code = ?, barcode = ?,
                    batch_tracked = ?, serial_tracked = ?, track_inventory = ?, standard_cost = ?,
                    currency = ?, tax_category_id = ?, description = ?, is_active = ?,
                    row_version = row_version + 1
                WHERE """ + where + " AND id = ? AND deleted_at IS NULL AND row_version = ?",
                params.toArray());
        return updated == 1;
    }

    public boolean softDelete(SiteScope scope, long id) {
        List<Object> params = new ArrayList<>();
        String where = SiteScopedSql.masterDataClause("organization_id", "site_id", scope, params);
        params.add(id);
        return dsl.execute("UPDATE product SET deleted_at = CURRENT_TIMESTAMP(6), row_version = row_version + 1 "
                + "WHERE " + where + " AND id = ? AND deleted_at IS NULL", params.toArray()) == 1;
    }

    private String buildWhere(SiteScope scope, String search, String productType,
                              boolean activeOnly, List<Object> params) {
        StringBuilder sb = new StringBuilder("deleted_at IS NULL AND ");
        sb.append(SiteScopedSql.masterDataClause("organization_id", "site_id", scope, params));
        if (search != null && !search.isBlank()) {
            sb.append(" AND (code LIKE ? OR name LIKE ? OR barcode LIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (productType != null && !productType.isBlank()) {
            sb.append(" AND product_type = ?");
            params.add(productType);
        }
        if (activeOnly) {
            sb.append(" AND is_active = 1");
        }
        return sb.toString();
    }

    private long lastInsertId() {
        Number n = (Number) dsl.fetchValue("SELECT LAST_INSERT_ID()");
        return n == null ? 0L : n.longValue();
    }

    private static Product map(Record r) {
        return new Product(
                RowValues.asLong(r.get("id")),
                RowValues.asLong(r.get("organization_id")),
                RowValues.asNullableLong(r.get("site_id")),
                RowValues.asString(r.get("code")),
                RowValues.asString(r.get("name")),
                RowValues.asString(r.get("product_type")),
                RowValues.asString(r.get("uom_code")),
                RowValues.asString(r.get("barcode")),
                RowValues.asBoolean(r.get("batch_tracked")),
                RowValues.asBoolean(r.get("serial_tracked")),
                RowValues.asBoolean(r.get("track_inventory")),
                r.get("standard_cost") == null ? null
                        : new java.math.BigDecimal(String.valueOf(r.get("standard_cost"))),
                RowValues.asString(r.get("currency")),
                RowValues.asNullableLong(r.get("tax_category_id")),
                RowValues.asString(r.get("description")),
                RowValues.asBoolean(r.get("is_active")),
                RowValues.asLong(r.get("row_version")));
    }
}
