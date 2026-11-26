package com.ultibiz.core.infrastructure;

import com.ultibiz.core.tenant.Tenant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The tenant registry. It always lives in the **control** database — the
 * routing must be able to read it before the tenant of the request is known.
 */
@Repository
public class TenantRegistryRepository {

    private final JdbcTemplate jdbc;

    public TenantRegistryRepository(@Qualifier("controlJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All the tenants. */
    public List<Tenant> all() {
        return jdbc.query("""
                SELECT id, organization_id, organization_code, database_name, status
                  FROM tenant_registry ORDER BY organization_code
                """, (rs, row) -> new Tenant(rs.getLong("id"), rs.getLong("organization_id"),
                rs.getString("organization_code"), rs.getString("database_name"),
                rs.getString("status")));
    }

    /** The tenant of an organisation (null when there is none). */
    public Tenant findByOrganization(long organizationId) {
        List<Tenant> rows = jdbc.query("""
                SELECT id, organization_id, organization_code, database_name, status
                  FROM tenant_registry WHERE organization_id = ?
                """, (rs, row) -> new Tenant(rs.getLong("id"), rs.getLong("organization_id"),
                rs.getString("organization_code"), rs.getString("database_name"),
                rs.getString("status")), organizationId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** The tenant with this database name (null when there is none). */
    public Tenant findByDatabase(String databaseName) {
        List<Tenant> rows = jdbc.query("""
                SELECT id, organization_id, organization_code, database_name, status
                  FROM tenant_registry WHERE database_name = ?
                """, (rs, row) -> new Tenant(rs.getLong("id"), rs.getLong("organization_id"),
                rs.getString("organization_code"), rs.getString("database_name"),
                rs.getString("status")), databaseName);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Registers a tenant (the row is updated when it exists). */
    public void register(Tenant tenant, String actor) {
        int updated = jdbc.update("""
                UPDATE tenant_registry SET organization_code = ?, database_name = ?, status = ?,
                       provisioned_at = CURRENT_TIMESTAMP(6), updated_by = ?,
                       row_version = row_version + 1
                 WHERE organization_id = ?
                """, tenant.organizationCode(), tenant.databaseName(), tenant.status(), actor,
                tenant.organizationId());
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO tenant_registry (organization_id, organization_code, database_name,
                                                status, provisioned_at, updated_by)
                    VALUES (?,?,?,?,CURRENT_TIMESTAMP(6),?)
                    """, tenant.organizationId(), tenant.organizationCode(), tenant.databaseName(),
                    tenant.status(), actor);
        }
    }

    /** The state of a tenant. */
    public void updateStatus(long organizationId, String status, String actor) {
        jdbc.update("""
                UPDATE tenant_registry SET status = ?, updated_by = ?,
                       row_version = row_version + 1 WHERE organization_id = ?
                """, status, actor, organizationId);
    }
}
