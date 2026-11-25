package com.ultibiz.web.security;

import com.ultibiz.core.domain.AppUser;
import com.ultibiz.core.infrastructure.UserRepository;
import java.util.Set;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads users from the {@code app_user} table together with their role-derived
 * permission codes and site scope.
 */
@Service
public class UltiBizUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    public UltiBizUserDetailsService(UserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = users.findByUsername(username)
                .filter(u -> !u.isLocked())
                .orElseThrow(() -> new UsernameNotFoundException("unknown user: " + username));

        Set<String> permissions = users.findPermissionCodes(user.id());
        Set<Long> siteIds = users.findSiteScopeIds(user.id());
        boolean globalRole = users.hasGlobalRole(user.id());

        return new UltiBizUserPrincipal(user.id(), user.organizationId(), user.username(),
                user.displayName(), user.passwordHash(), user.enabled(),
                permissions, siteIds, globalRole);
    }
}
