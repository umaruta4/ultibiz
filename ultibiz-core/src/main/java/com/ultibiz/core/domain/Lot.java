package com.ultibiz.core.domain;

import java.time.LocalDate;

/** A lot (or serial) of a product for traceability. */
public record Lot(
        long id,
        long organizationId,
        long productId,
        String lotCode,
        String serialNumber,
        LocalDate producedAt,
        LocalDate expiresAt) {
}
