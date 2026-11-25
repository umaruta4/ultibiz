package com.ultibiz.core.domain;

import java.time.Instant;

/**
 * Tenant root. All business data carries an organization id; queries are
 * always org-scoped.
 */
public record Organization(
        long id,
        String code,
        String name,
        String baseCurrency,
        String defaultLocale,
        String timezone,
        Instant createdAt,
        long rowVersion) {
}
