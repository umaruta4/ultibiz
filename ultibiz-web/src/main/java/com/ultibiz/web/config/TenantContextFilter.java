package com.ultibiz.web.config;

import com.ultibiz.core.tenant.TenantContext;
import com.ultibiz.core.tenant.TenantService;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the tenant of the logged-in user on the thread: the connection of the
 * request goes to the database of its organisation. The tenant is removed at
 * the end of the request (a pooled thread must not keep a tenant).
 *
 * <p>The filter runs after the security chain — the login itself still reads
 * the control database (the identity lives there).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class TenantContextFilter extends OncePerRequestFilter {

    private final org.springframework.beans.factory.ObjectProvider<TenantService> tenants;

    public TenantContextFilter(org.springframework.beans.factory.ObjectProvider<TenantService> tenants) {
        this.tenants = tenants;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            TenantService service = tenants.getIfAvailable();
            if (service != null) {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null
                        && authentication.getPrincipal() instanceof UltiBizUserPrincipal user) {
                    String database = service.databaseOf(user.organizationId());
                    if (database != null) {
                        TenantContext.set(database);
                    }
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
