package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.AppUser;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** The lookups the single sign-on needs: the user, the organisation and the roles. */
@Repository
public class SsoRepository {

    private final DSLContext dsl;

    public SsoRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** The user with this username. */
    public Optional<AppUser> findByUsername(String username) {
        return dsl.resultQuery("""
                        SELECT id, organization_id, username, password_hash, display_name,
                               email, default_locale, theme, enabled
                          FROM app_user
                         WHERE username = ? AND deleted_at IS NULL
                        """, username)
                .fetchOptional(r -> new AppUser(
                        r.get("id", Long.class),
                        r.get("organization_id", Long.class),
                        r.get("username", String.class),
                        r.get("password_hash", String.class),
                        r.get("display_name", String.class),
                        r.get("email", String.class),
                        r.get("default_locale", String.class),
                        r.get("theme", String.class),
                        r.get("enabled", Boolean.class)));
    }

    /** The user with this e-mail (the SSO provider may only know the e-mail). */
    public Optional<AppUser> findByEmail(String email) {
        return dsl.resultQuery("""
                        SELECT id, organization_id, username, password_hash, display_name,
                               email, default_locale, theme, enabled
                          FROM app_user
                         WHERE LOWER(email) = LOWER(?) AND deleted_at IS NULL
                        """, email)
                .fetchOptional(r -> new AppUser(
                        r.get("id", Long.class),
                        r.get("organization_id", Long.class),
                        r.get("username", String.class),
                        r.get("password_hash", String.class),
                        r.get("display_name", String.class),
                        r.get("email", String.class),
                        r.get("default_locale", String.class),
                        r.get("theme", String.class),
                        r.get("enabled", Boolean.class)));
    }

    /** The id of the organisation with this code (null when there is none). */
    public Long organizationIdByCode(String code) {
        Number id = (Number) dsl.fetchValue(
                "SELECT id FROM organization WHERE code = ?", code);
        return id == null ? null : id.longValue();
    }

    /** The id of the role with this code in the organisation (null when there is none). */
    public Long roleIdByCode(long organizationId, String code) {
        Number id = (Number) dsl.fetchValue(
                "SELECT id FROM role WHERE code = ? AND (organization_id = ? OR organization_id IS NULL)"
                + " ORDER BY organization_id IS NULL LIMIT 1", code, organizationId);
        return id == null ? null : id.longValue();
    }

    /** The id of the global role (organization_id IS NULL) with this code. */
    public Long globalRoleIdByCode(String code) {
        Number id = (Number) dsl.fetchValue(
                "SELECT id FROM role WHERE code = ? AND organization_id IS NULL", code);
        return id == null ? null : id.longValue();
    }

    /** Creates a user with a random password (the SSO is the only way in). */
    public long createUser(long organizationId, String username, String email,
                           String displayName, String passwordHash) {
        dsl.execute("""
                        INSERT INTO app_user (organization_id, username, password_hash, display_name,
                                              email, default_locale, enabled)
                        VALUES (?,?,?,?,?,'en',TRUE)
                        """, organizationId, username, passwordHash, displayName, email);
        Number id = (Number) dsl.fetchValue("SELECT id FROM app_user WHERE username = ?", username);
        return id.longValue();
    }

    /** Gives the user a role. */
    public void assignRole(long userId, long roleId) {
        dsl.execute("INSERT IGNORE INTO user_role (user_id, role_id) VALUES (?,?)", userId, roleId);
    }

    /** Gives the user the access to every site of the organisation. */
    public void assignAllSites(long userId, long organizationId) {
        dsl.execute("""
                        INSERT IGNORE INTO user_site_scope (user_id, site_id)
                        SELECT ?, id FROM site WHERE organization_id = ?
                        """, userId, organizationId);
    }

    /** Whether the user already carries the role. */
    public boolean hasRole(long userId, long roleId) {
        Number count = (Number) dsl.fetchValue(
                "SELECT COUNT(*) FROM user_role WHERE user_id = ? AND role_id = ?", userId, roleId);
        return count != null && count.longValue() > 0;
    }
}
