package com.ultibiz.core.sso;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The rules of the single sign-on: how the claims of the identity provider
 * become a local user. The policy is pure — no database, no Spring — so it can
 * be reasoned about and tested on its own.
 */
public class SsoUserPolicy {

    /** The claims of the identity provider, flattened. */
    public record Claims(String subject, String username, String email, String displayName,
                         Set<String> roles) {

        public Claims {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }

        /** The claims as a map (the format the Spring handlers use). */
        public static Claims of(Map<String, Object> attributes) {
            Map<String, Object> flat = new LinkedHashMap<>(attributes == null ? Map.of() : attributes);
            String subject = string(flat.get("sub"));
            String username = string(flat.get("preferred_username"));
            String email = string(flat.get("email"));
            String name = string(flat.get("name"));
            if (username == null) {
                username = string(flat.get("username"));
            }
            if (email == null) {
                email = string(flat.get("emailAddress"));
            }
            if (name == null) {
                name = username;
            }
            return new Claims(subject, username, email, name, rolesOf(flat));
        }

        @SuppressWarnings("unchecked")
        private static Set<String> rolesOf(Map<String, Object> flat) {
            Object realmAccess = flat.get("realm_access");
            if (realmAccess instanceof Map<?, ?> map) {
                Object roles = map.get("roles");
                if (roles instanceof Iterable<?> iterable) {
                    java.util.Set<String> out = new java.util.LinkedHashSet<>();
                    for (Object role : iterable) {
                        if (role != null) {
                            out.add(role.toString());
                        }
                    }
                    return out;
                }
            }
            Object roles = flat.get("roles");
            if (roles instanceof Iterable<?> iterable) {
                java.util.Set<String> out = new java.util.LinkedHashSet<>();
                for (Object role : iterable) {
                    if (role != null) {
                        out.add(role.toString());
                    }
                }
                return out;
            }
            return Set.of();
        }

        private static String string(Object value) {
            if (value == null) {
                return null;
            }
            String text = value.toString().trim();
            return text.isEmpty() ? null : text;
        }
    }

    private final boolean autoProvision;
    private final String defaultOrganizationCode;
    private final String defaultRoleCode;
    private final Set<String> adminRoles;

    public SsoUserPolicy(boolean autoProvision, String defaultOrganizationCode,
                         String defaultRoleCode, Set<String> adminRoles) {
        this.autoProvision = autoProvision;
        this.defaultOrganizationCode = defaultOrganizationCode;
        this.defaultRoleCode = defaultRoleCode;
        this.adminRoles = adminRoles == null ? Set.of() : Set.copyOf(adminRoles);
    }

    /**
     * The local username of the claims: the preferred username, lower case and
     * without the spaces; the e-mail is the fallback.
     */
    public String usernameOf(Claims claims) {
        String candidate = claims.username() != null ? claims.username() : claims.email();
        if (candidate == null || candidate.isBlank()) {
            throw new SsoException("The identity provider sent neither a user name nor an e-mail");
        }
        String normalized = candidate.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._@-]", "");
        if (normalized.isBlank()) {
            throw new SsoException("The user name of the identity provider is not usable: " + candidate);
        }
        return normalized;
    }

    /** Whether a user that does not exist yet may be created. */
    public boolean mayProvision() {
        return autoProvision;
    }

    /** The organisation of a new user (by code). */
    public String defaultOrganizationCode() {
        return defaultOrganizationCode;
    }

    /** The role of a new user (by code). */
    public String defaultRoleCode() {
        return defaultRoleCode;
    }

    /** Whether the claims carry one of the administrator roles. */
    public boolean isAdministrator(Claims claims) {
        if (adminRoles.isEmpty()) {
            return false;
        }
        return claims.roles().stream().anyMatch(adminRoles::contains);
    }
}
