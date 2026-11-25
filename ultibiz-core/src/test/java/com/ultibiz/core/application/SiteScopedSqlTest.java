package com.ultibiz.core.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SiteScopedSqlTest {

    @Test
    void restrictedScopeBuildsOrgAndSiteClause() {
        List<Object> params = new ArrayList<>();
        String sql = SiteScopedSql.whereClause("organization_id", "site_id",
                new SiteScope(5L, Set.of(2L, 3L), true), params);

        assertTrue(sql.startsWith("organization_id = ? AND site_id IN ("));
        assertTrue(sql.endsWith("?)") || sql.endsWith(")"));
        assertEquals(3, params.size());
        assertEquals(5L, params.get(0));
        assertTrue(params.subList(1, 3).containsAll(List.of(2L, 3L)));
    }

    @Test
    void unrestrictedScopeBuildsOrgOnlyClause() {
        List<Object> params = new ArrayList<>();
        String sql = SiteScopedSql.whereClause("organization_id", "site_id",
                new SiteScope(5L, Set.of(), false), params);

        assertEquals("organization_id = ?", sql);
        assertEquals(List.of(5L), params);
    }

    @Test
    void emptyRestrictedScopeIsDenyAll() {
        List<Object> params = new ArrayList<>();
        String sql = SiteScopedSql.whereClause("organization_id", "site_id",
                new SiteScope(5L, Set.of(), true), params);

        assertEquals("1 = 0", sql);
        assertTrue(params.isEmpty());
    }

    @Test
    void nullOrZeroOrgIsDenyAll() {
        List<Object> params = new ArrayList<>();
        assertEquals("1 = 0",
                SiteScopedSql.whereClause("organization_id", "site_id", null, params));
        assertEquals("1 = 0",
                SiteScopedSql.whereClause("organization_id", "site_id",
                        SiteScope.anonymous(), params));
        assertTrue(params.isEmpty());
    }
}
