package com.ultibiz.core.domain;

import java.time.LocalDate;

/** An engineering change notice for a bill of material. */
public record Ecn(
        long id,
        long organizationId,
        String code,
        String description,
        Long bomId,
        String status,
        LocalDate effectiveDate,
        String createdBy) {
}
