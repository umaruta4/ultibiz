package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.AppUser;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private final DSLContext dsl;

    public UserRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long count() {
        Number n = (Number) dsl.fetchValue("SELECT COUNT(*) FROM app_user WHERE deleted_at IS NULL");
        return n.longValue();
    }

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

    public void updatePasswordHash(long userId, String encodedHash) {
        dsl.execute("UPDATE app_user SET password_hash = ?, row_version = row_version + 1 WHERE id = ?",
                encodedHash, userId);
    }

    /** All permission codes granted to the user through their roles. */
    public Set<String> findPermissionCodes(long userId) {
        return new LinkedHashSet<>(dsl.resultQuery("""
                        SELECT DISTINCT rp.permission_code
                          FROM user_role ur
                          JOIN role_permission rp ON rp.role_id = ur.role_id
                         WHERE ur.user_id = ?
                        """, userId)
                .fetch("permission_code", String.class));
    }

    /** Site ids the user is explicitly scoped to (may be empty). */
    public Set<Long> findSiteScopeIds(long userId) {
        return new LinkedHashSet<>(dsl.resultQuery(
                        "SELECT site_id FROM user_site_scope WHERE user_id = ?", userId)
                .fetch("site_id", Long.class));
    }

    /** True when the user holds a global (org-unrestricted) role. */
    public boolean hasGlobalRole(long userId) {
        Number n = (Number) dsl.fetchValue("""
                        SELECT COUNT(*)
                          FROM user_role ur
                          JOIN role r ON r.id = ur.role_id
                         WHERE ur.user_id = ? AND r.organization_id IS NULL AND r.code = 'ADMIN'
                        """, userId);
        return n.longValue() > 0;
    }
}
