package com.ultibiz.web.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Web security: form login + DB-backed sessions, CSRF on everything
 * state-changing, OWASP-minded headers. Actuator health is public (probes);
 * everything else requires authentication.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * A stale CSRF token (expired session, tab left open across a restart)
     * must not show the user a bare 403 JSON page: bounce back to the login
     * form with an explanatory message instead.
     */
    @Bean
    AccessDeniedHandler csrfFriendlyAccessDeniedHandler() {
        return (request, response, accessDeniedException) -> {
            if (accessDeniedException instanceof AccessDeniedException
                    && accessDeniedException.getMessage() != null
                    && accessDeniedException.getMessage().toLowerCase().contains("csrf")) {
                response.sendRedirect(request.getContextPath() + "/login?error=csrf");
                return;
            }
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            org.springframework.beans.factory.ObjectProvider<
                                                    org.springframework.security.oauth2.client.registration
                                                            .ClientRegistrationRepository> registrations,
                                            org.springframework.beans.factory.ObjectProvider<
                                                    com.ultibiz.web.security.SsoSuccessHandler> ssoHandler)
            throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/error", "/favicon.ico",
                        "/css/**", "/js/**", "/vendor/**", "/images/**",
                        "/actuator/health", "/actuator/health/**").permitAll()
                // the inbound webhook is called by partners, not by users:
                // it carries its own shared secret (checked in the controller)
                .requestMatchers("/api/exchange/inbound/**").permitAll()
                // the customer portal: the token in the link is the secret
                .requestMatchers("/portal/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/me/theme").authenticated()
                // the administration pages are for the administrators
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            // a partner calls the inbound webhook without a browser: there is no
            // CSRF token in a machine request, the shared secret is the proof
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/exchange/inbound/**"))
            .formLogin(login -> login
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .failureUrl("/login?error")
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("SESSION"))
            .sessionManagement(session -> session
                .sessionFixation(fixation -> fixation.migrateSession()))
            .exceptionHandling(ex -> ex
                .accessDeniedHandler(csrfFriendlyAccessDeniedHandler()))
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; "
                        + "script-src 'self'; "
                        + "style-src 'self' 'unsafe-inline'; "
                        + "img-src 'self' data:; "
                        + "font-src 'self'; "
                        + "connect-src 'self'; "
                        + "frame-ancestors 'self'; "
                        + "base-uri 'self'; "
                        + "form-action 'self'"))
                .referrerPolicy(referrer -> referrer.policy(
                        org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
                                .ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                .frameOptions(frame -> frame.sameOrigin()));
        // the single sign-on is only configured with the profile "sso":
        // without it there is no client registration and the login is unchanged
        if (registrations.getIfAvailable() != null && ssoHandler.getIfAvailable() != null) {
            http.oauth2Login(login -> login
                    .loginPage("/login")
                    .successHandler(ssoHandler.getObject())
                    .failureUrl("/login?error=sso"));
            http.oidcLogout();
        }
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
