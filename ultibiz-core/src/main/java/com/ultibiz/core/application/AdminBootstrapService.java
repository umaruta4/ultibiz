package com.ultibiz.core.application;

import com.ultibiz.core.domain.AppUser;
import com.ultibiz.core.infrastructure.UserRepository;
import java.security.SecureRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Replaces the placeholder {@code {locked}} admin password hash on first
 * boot. The password comes from the {@code ULTIBIZ_ADMIN_PASSWORD}
 * environment variable; when unset, a random one is generated and printed to
 * the log exactly once. No credential is ever hard-coded.
 */
@Service
public class AdminBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);
    private static final String GENERATED_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String configuredPassword;

    public AdminBootstrapService(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            @Value("${ultibiz.admin.password:}") String configuredPassword) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.configuredPassword = configuredPassword;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        users.findByUsername("admin").ifPresentOrElse(this::unlockIfNeeded,
                () -> log.warn("No 'admin' user found — bootstrap skipped"));
    }

    private void unlockIfNeeded(AppUser admin) {
        if (!admin.isLocked()) {
            return;
        }
        String raw;
        if (configuredPassword != null && !configuredPassword.isBlank()) {
            raw = configuredPassword;
            log.info("Admin password initialized from ULTIBIZ_ADMIN_PASSWORD");
        } else {
            raw = generatePassword();
            log.warn("=== Generated initial admin password (change immediately): {} ===", raw);
        }
        users.updatePasswordHash(admin.id(), passwordEncoder.encode(raw));
    }

    static String generatePassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(GENERATED_ALPHABET.charAt(random.nextInt(GENERATED_ALPHABET.length())));
        }
        return sb.toString();
    }
}
