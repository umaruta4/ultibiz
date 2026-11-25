package com.ultibiz.core.application;

import java.util.List;

/**
 * Builds the org/site scoping SQL fragment that every repository must append
 * to transactional queries. The fragment is always parameterized — column
 * names are fixed identifiers supplied by the caller, never user input.
 */
public final class SiteScopedSql {

    private SiteScopedSql() {
    }

    /**
     * Appends {@code org_id = ? AND site_id IN (?, ...)} to {@code params}.
     *
     * @return the WHERE fragment; {@code "1 = 0"} when the scope is deny-all
     */
    public static String whereClause(String orgColumn, String siteColumn,
                                     SiteScope scope, List<Object> params) {
        if (scope == null || scope.organizationId() <= 0 || scope.isDenyAll()) {
            return "1 = 0";
        }
        params.add(scope.organizationId());
        if (!scope.restricted()) {
            return orgColumn + " = ?";
        }
        String placeholders = String.join(", ", scope.siteIds().stream().map(id -> "?").toList());
        params.addAll(scope.siteIds());
        return orgColumn + " = ? AND " + siteColumn + " IN (" + placeholders + ")";
    }

    /**
     * Same as {@link #whereClause} but for master data, where {@code site_id}
     * may be NULL: a NULL site means "shared by every site of the organization"
     * and is therefore visible to any site-scoped user.
     *
     * @return the WHERE fragment; {@code "1 = 0"} when the scope is deny-all
     */
    public static String masterDataClause(String orgColumn, String siteColumn,
                                          SiteScope scope, List<Object> params) {
        if (scope == null || scope.organizationId() <= 0 || scope.isDenyAll()) {
            return "1 = 0";
        }
        params.add(scope.organizationId());
        if (!scope.restricted()) {
            return orgColumn + " = ?";
        }
        String placeholders = String.join(", ", scope.siteIds().stream().map(id -> "?").toList());
        params.addAll(scope.siteIds());
        return orgColumn + " = ? AND (" + siteColumn + " IS NULL OR " + siteColumn
                + " IN (" + placeholders + "))";
    }
}
