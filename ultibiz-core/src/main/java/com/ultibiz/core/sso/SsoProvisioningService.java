package com.ultibiz.core.sso;

import com.ultibiz.core.infrastructure.SsoRepository;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Brings the user of the identity provider into UltiBiz: an existing user is
 * taken as it is, a new one is created (when the policy allows it) with the
 * default organisation and role.
 */
@Service
public class SsoProvisioningService {

    private static final Logger LOG = LoggerFactory.getLogger(SsoProvisioningService.class);

    private final SsoRepository repository;
    private final SsoUserPolicy policy;
    private final PasswordEncoder passwords;

    public SsoProvisioningService(SsoRepository repository, SsoUserPolicy policy,
                                   PasswordEncoder passwords) {
        this.repository = repository;
        this.policy = policy;
        this.passwords = passwords;
    }

    /** The local user name of the claims (the user is created when needed). */
    @Transactional
    public String resolve(SsoUserPolicy.Claims claims) {
        String username = policy.usernameOf(claims);
        var existing = repository.findByUsername(username);
        if (existing.isPresent()) {
            return existing.get().username();
        }
        if (claims.email() != null) {
            var byEmail = repository.findByEmail(claims.email());
            if (byEmail.isPresent()) {
                LOG.info("The SSO user {} is linked to the local user {}", claims.email(),
                        byEmail.get().username());
                return byEmail.get().username();
            }
        }
        if (!policy.mayProvision()) {
            throw new SsoException("The user " + username + " is unknown and the automatic"
                    + " provisioning is off");
        }
        Long organizationId = repository.organizationIdByCode(policy.defaultOrganizationCode());
        if (organizationId == null) {
            throw new SsoException("The organisation " + policy.defaultOrganizationCode()
                    + " of the SSO provisioning does not exist");
        }
        String email = claims.email() == null ? username + "@sso.local" : claims.email();
        String displayName = claims.displayName() == null ? username : claims.displayName();
        long userId = repository.createUser(organizationId, username, email, displayName,
                passwords.encode(randomPassword()));
        Long roleId = repository.roleIdByCode(organizationId, policy.defaultRoleCode());
        if (roleId != null) {
            repository.assignRole(userId, roleId);
        } else {
            LOG.warn("The SSO user {} was created, but the role {} does not exist: the user has"
                    + " no permission. Set ULTIBIZ_SSO_ROLE to a role of the base scheme.",
                    username, policy.defaultRoleCode());
        }
        if (policy.isAdministrator(claims)) {
            Long adminRole = repository.globalRoleIdByCode("ADMIN");
            if (adminRole != null) {
                repository.assignRole(userId, adminRole);
            } else {
                LOG.warn("The SSO user {} has an administrator role of the provider, but the local"
                        + " ADMIN role does not exist", username);
            }
        }
        repository.assignAllSites(userId, organizationId);
        LOG.info("The SSO user {} was provisioned (organization {})", username, organizationId);
        return username;
    }

    /** A password nobody knows: the SSO is the only way in. */
    private static String randomPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
