package com.ultibiz.web.security;

import com.ultibiz.core.application.PermissionGate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Permission check based on the current authentication. The seeded admin role
 * carries every permission of the {@code permission} table, so an admin can
 * reopen a closed period while a normal user cannot.
 */
@Component
public class SecurityContextPermissionGate implements PermissionGate {

    @Override
    public boolean allowed(String permissionCode) {
        if (permissionCode == null) {
            return false;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        for (var authority : auth.getAuthorities()) {
            if (permissionCode.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
