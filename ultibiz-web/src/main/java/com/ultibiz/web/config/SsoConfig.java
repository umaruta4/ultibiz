package com.ultibiz.web.config;

import com.ultibiz.core.sso.SsoUserPolicy;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The beans of the single sign-on. The policy always exists (the automatic
 * provisioning is off by default); the Keycloak login itself only switches on
 * with the profile "sso" (`SPRING_PROFILES_ACTIVE=sso`).
 */
@Configuration
public class SsoConfig {

    @Bean
    SsoUserPolicy ssoUserPolicy(
            @Value("${ultibiz.sso.auto-provision:false}") boolean autoProvision,
            @Value("${ultibiz.sso.default-organization:ACME}") String organization,
            @Value("${ultibiz.sso.default-role:VIEWER}") String role,
            @Value("${ultibiz.sso.admin-roles:ultibiz-admin,admin}") String adminRoles) {
        Set<String> admins = Set.of(adminRoles.split(",")).stream()
                .map(String::trim).filter(name -> !name.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        return new SsoUserPolicy(autoProvision, organization, role, admins);
    }
}
