package com.ultibiz.core.application;

import java.util.Set;

/**
 * The org/site scope of the current principal. Every transactional query must
 * be filtered through this scope: a user scoped to site A must never see
 * site B rows.
 *
 * @param organizationId the tenant the scope belongs to
 * @param siteIds        sites the principal may see; empty means <b>no</b>
 *                       site access (deny-all), never "all sites"
 * @param restricted     {@code false} for org-level roles that may see every
 *                       site in the organization
 */
public record SiteScope(long organizationId, Set<Long> siteIds, boolean restricted) {

    public SiteScope {
        siteIds = siteIds == null ? Set.of() : Set.copyOf(siteIds);
    }

    /** Anonymous / unauthenticated scope — denies everything. */
    public static SiteScope anonymous() {
        return new SiteScope(0L, Set.of(), true);
    }

    public boolean canSeeSite(long siteId) {
        return restricted ? siteIds.contains(siteId) : true;
    }

    /**
     * @return {@code true} when the scope is guaranteed to match zero rows and
     *         the query can be short-circuited
     */
    public boolean isDenyAll() {
        return restricted && siteIds.isEmpty();
    }
}
