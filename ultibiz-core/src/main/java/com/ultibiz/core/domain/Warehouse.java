package com.ultibiz.core.domain;

/** A physical warehouse, always owned by exactly one site. */
public record Warehouse(
        long id,
        long organizationId,
        long siteId,
        String code,
        String name,
        String warehouseType,
        String countryCode,
        String address,
        boolean active,
        long rowVersion) {
}
