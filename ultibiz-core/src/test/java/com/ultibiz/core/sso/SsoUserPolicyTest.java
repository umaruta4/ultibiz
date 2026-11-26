package com.ultibiz.core.sso;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The rules of the single sign-on: the claims become a local user. */
class SsoUserPolicyTest {

    private final SsoUserPolicy policy = new SsoUserPolicy(true, "ACME", "VIEWER",
            Set.of("ultibiz-admin"));

    @Test
    void thePreferredUsernameBecomesTheLocalUsername() {
        var claims = SsoUserPolicy.Claims.of(Map.of(
                "sub", "abc-123",
                "preferred_username", "Budi.Santoso",
                "email", "budi@example.com",
                "name", "Budi Santoso"));
        assertEquals("budi.santoso", policy.usernameOf(claims));
    }

    @Test
    void theEmailIsTheFallback() {
        var claims = SsoUserPolicy.Claims.of(Map.of("email", "Admin@Example.com"));
        assertEquals("admin@example.com", policy.usernameOf(claims));
    }

    @Test
    void theUnusableNameIsRefused() {
        var claims = SsoUserPolicy.Claims.of(Map.of("preferred_username", "///"));
        assertThrows(SsoException.class, () -> policy.usernameOf(claims));
    }

    @Test
    void theRealmRolesMakeAnAdministrator() {
        var claims = SsoUserPolicy.Claims.of(Map.of(
                "preferred_username", "budi",
                "realm_access", Map.of("roles", java.util.List.of("ultibiz-admin", "user"))));
        assertTrue(policy.isAdministrator(claims), "the realm role is read");
        var without = SsoUserPolicy.Claims.of(Map.of(
                "preferred_username", "budi",
                "realm_access", Map.of("roles", java.util.List.of("user"))));
        assertFalse(without.roles().isEmpty(), "the roles are read");
        assertFalse(policy.isAdministrator(without), "a normal user is not an administrator");
    }

    @Test
    void theProvisioningFollowsTheConfiguration() {
        assertTrue(policy.mayProvision(), "the automatic provisioning is on");
        SsoUserPolicy off = new SsoUserPolicy(false, "ACME", "VIEWER", Set.of());
        assertFalse(off.mayProvision(), "the automatic provisioning can be switched off");
        assertEquals("ACME", off.defaultOrganizationCode());
        assertEquals("VIEWER", off.defaultRoleCode());
    }
}
