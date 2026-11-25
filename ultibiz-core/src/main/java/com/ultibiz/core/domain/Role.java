package com.ultibiz.core.domain;

/**
 * Role within an organization (or global when {@code organizationId} is
 * {@code null}). Permissions attach to roles; users get roles per scope.
 */
public record Role(
        long id,
        Long organizationId,
        String code,
        String name) {
}
