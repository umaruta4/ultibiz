package com.ultibiz.web.security;

import com.ultibiz.core.sso.SsoProvisioningService;
import com.ultibiz.core.sso.SsoUserPolicy;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * The end of the Keycloak login: the claims of the identity provider become a
 * local user (created when the policy allows it) and the session continues with
 * the usual local principal — the rest of the application does not know that
 * the login came from an identity provider.
 */
@Component
public class SsoSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SsoSuccessHandler.class);

    private final SsoProvisioningService provisioning;
    private final SsoUserPolicy policy;
    private final UltiBizUserDetailsService users;

    public SsoSuccessHandler(SsoProvisioningService provisioning, SsoUserPolicy policy,
                             UltiBizUserDetailsService users) {
        this.provisioning = provisioning;
        this.policy = policy;
        this.users = users;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException,
            ServletException {
        try {
            OAuth2User principal = (OAuth2User) authentication.getPrincipal();
            Map<String, Object> attributes = principal.getAttributes();
            SsoUserPolicy.Claims claims = SsoUserPolicy.Claims.of(attributes);
            String username = provisioning.resolve(claims);
            var user = users.loadUserByUsername(username);
            Authentication local = new org.springframework.security.authentication
                    .UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(local);
            LOG.info("The SSO login of {} was accepted", username);
            response.sendRedirect(request.getContextPath() + "/");
        } catch (RuntimeException ex) {
            LOG.warn("The SSO login was refused: {}", ex.getMessage());
            SecurityContextHolder.clearContext();
            request.getSession().invalidate();
            response.sendError(HttpServletResponse.SC_FORBIDDEN, ex.getMessage());
        }
    }
}
