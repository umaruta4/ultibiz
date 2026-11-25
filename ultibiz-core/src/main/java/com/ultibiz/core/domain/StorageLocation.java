package com.ultibiz.core.domain;

import java.math.BigDecimal;

/** A bin / pallet / floor location inside a warehouse, optionally on a rack. */
public record StorageLocation(
        long id,
        long warehouseId,
        Long rackId,
        String code,
        String name,
        String locationType,
        BigDecimal capacity) {
}
