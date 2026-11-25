package com.ultibiz.core.domain;

/**
 * ISO-3166-1 alpha-2 country, enabled per organization. Drives currency,
 * locale, tax pack and statutory formats.
 */
public record Country(
        String code,
        String name,
        String currency,
        String defaultLocale,
        String timezone) {
}
