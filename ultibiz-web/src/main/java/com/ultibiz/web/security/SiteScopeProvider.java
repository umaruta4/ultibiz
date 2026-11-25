package com.ultibiz.web.security;

import com.ultibiz.core.application.SiteScope;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link SiteScope} of the current request from the security
 * context. Anything unauthenticated resolves to a deny-all scope.
 */
@Component
public class SiteScopeProvider {

    public SiteScope current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UltiBizUserPrincipal principal) {
            return principal.siteScope();
        }
        return SiteScope.anonymous();
    }
}
