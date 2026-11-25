package com.ultibiz.web.security;

import com.ultibiz.core.application.SiteScope;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Authenticated principal: wraps the UltiBiz user record plus the permission
 * codes and site scope resolved at login.
 */
public class UltiBizUserPrincipal implements UserDetails {

    private final long userId;
    private final long organizationId;
    private final String username;
    private final String displayName;
    private final String passwordHash;
    private final boolean enabled;
    private final Set<String> permissionCodes;
    private final Set<Long> siteIds;
    private final boolean globalRole;

    public UltiBizUserPrincipal(long userId, long organizationId, String username,
                                String displayName, String passwordHash, boolean enabled,
                                Set<String> permissionCodes, Set<Long> siteIds, boolean globalRole) {
        this.userId = userId;
        this.organizationId = organizationId;
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.permissionCodes = Set.copyOf(permissionCodes);
        this.siteIds = Set.copyOf(siteIds);
        this.globalRole = globalRole;
    }

    public long userId() {
        return userId;
    }

    public long organizationId() {
        return organizationId;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * @return the site scope used to filter every transactional query
     */
    public SiteScope siteScope() {
        return new SiteScope(organizationId, siteIds, !globalRole);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        for (String code : permissionCodes) {
            authorities.add(new SimpleGrantedAuthority(code));
        }
        if (globalRole) {
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        }
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
