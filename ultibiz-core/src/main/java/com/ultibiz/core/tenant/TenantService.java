package com.ultibiz.core.tenant;

import com.ultibiz.core.infrastructure.TenantRegistryRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The tenant of the organisation: which database its queries belong to. The
 * mode "single" keeps everything in the control database (the behaviour of the
 * single-tenant installation), the mode "database" routes the queries of a
 * tenant to its own database.
 */
@Service
public class TenantService {

    private final TenantRegistryRepository registry;
    private final boolean perTenantDatabase;
    private final Map<Long, Tenant> cache = new ConcurrentHashMap<>();

    public TenantService(TenantRegistryRepository registry,
                         @Value("${ultibiz.tenant.mode:single}") String mode) {
        this.registry = registry;
        this.perTenantDatabase = "database".equalsIgnoreCase(mode == null ? "" : mode.trim());
    }

    /** Whether every tenant has its own database. */
    public boolean isPerTenantDatabase() {
        return perTenantDatabase;
    }

    /** All the tenants. */
    public List<Tenant> all() {
        return registry.all();
    }

    /** The tenant of an organisation (null when there is none). */
    public Tenant find(long organizationId) {
        return registry.findByOrganization(organizationId);
    }

    /**
     * The database of the organisation — null when the queries belong to the
     * control database (the mode "single" or an unknown organisation).
     */
    public String databaseOf(long organizationId) {
        if (!perTenantDatabase) {
            return null;
        }
        Tenant tenant = cache.computeIfAbsent(organizationId, registry::findByOrganization);
        if (tenant == null || !tenant.isActive()) {
            return null;
        }
        return tenant.databaseName();
    }

    /** Forgets the cached tenant (after a change in the registry). */
    public void invalidate(long organizationId) {
        cache.remove(organizationId);
    }
}
