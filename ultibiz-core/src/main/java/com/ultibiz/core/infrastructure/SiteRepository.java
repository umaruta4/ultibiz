package com.ultibiz.core.infrastructure;

import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.SiteScopedSql;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class SiteRepository {

    private final DSLContext dsl;

    public SiteRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long count() {
        Number n = (Number) dsl.fetchValue("SELECT COUNT(*) FROM site WHERE deleted_at IS NULL");
        return n.longValue();
    }

    /**
     * Counts sites visible inside the caller's scope. The scope fragment is
     * built by {@link SiteScopedSql} — parameterized, deny-all when the scope
     * is empty.
     */
    public long countInScope(SiteScope scope) {
        List<Object> params = new ArrayList<>();
        String where = SiteScopedSql.whereClause("organization_id", "id", scope, params);
        Number n = (Number) dsl.fetchValue(
                "SELECT COUNT(*) FROM site WHERE deleted_at IS NULL AND " + where,
                params.toArray());
        return n.longValue();
    }
}
