package com.ultibiz.core.domain;

/** A recurring maintenance plan for a category of assets. */
public record MaintenancePlan(
        long id,
        long organizationId,
        Long categoryId,
        String code,
        String name,
        int intervalMonths,
        String description,
        boolean active) {
}
