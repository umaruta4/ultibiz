package com.ultibiz.core.domain;

/**
 * Physical operating location: factory, distribution centre or office.
 */
public record Site(
        long id,
        long organizationId,
        String countryCode,
        String code,
        String name,
        SiteType siteType,
        String timezone) {

    public enum SiteType { FACTORY, DISTRIBUTION, OFFICE }
}
