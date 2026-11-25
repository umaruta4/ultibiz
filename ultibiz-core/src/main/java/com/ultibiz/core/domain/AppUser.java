package com.ultibiz.core.domain;

/**
 * Application user. {@code passwordHash} is a Spring Security
 * {@code {id}encoded} string; the bootstrap value {@code {locked}} matches
 * nothing and is replaced on first boot by the admin bootstrap service.
 */
public record AppUser(
        long id,
        long organizationId,
        String username,
        String passwordHash,
        String displayName,
        String email,
        String defaultLocale,
        String theme,
        boolean enabled) {

    /** Special marker stored by the baseline migration; never a valid hash. */
    public static final String LOCKED_HASH = "{locked}";

    public boolean isLocked() {
        return LOCKED_HASH.equals(passwordHash);
    }
}
