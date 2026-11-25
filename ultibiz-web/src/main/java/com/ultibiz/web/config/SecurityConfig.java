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
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/error", "/favicon.ico",
                        "/css/**", "/js/**", "/vendor/**", "/images/**",
                        "/actuator/health", "/actuator/health/**").permitAll()
                // the inbound webhook is called by partners, not by users:
                // it carries its own shared secret (checked in the controller)
                .requestMatchers("/api/exchange/inbound/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/me/theme").authenticated()
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .anyRequest().authenticated())
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
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
