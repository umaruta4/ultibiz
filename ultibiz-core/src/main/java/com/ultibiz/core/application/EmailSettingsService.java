package com.ultibiz.core.application;

import com.ultibiz.core.ai.AiSecretCipher;
import com.ultibiz.core.infrastructure.EmailSettingsRepository;
import com.ultibiz.core.mail.EmailSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The e-mail configuration of an organisation, kept by the administrator in
 * the interface. The secrets (the SMTP password, the API key, the webhook
 * secret) are sealed with the deployment key — they are never stored in clear
 * text and never sent back to the browser.
 */
@Service
public class EmailSettingsService {

    private static final Logger LOG = LoggerFactory.getLogger(EmailSettingsService.class);

    private final EmailSettingsRepository repository;
    private final AiSecretCipher cipher;

    public EmailSettingsService(EmailSettingsRepository repository, AiSecretCipher cipher) {
        this.repository = repository;
        this.cipher = cipher;
    }

    /** What the form carries (the secrets are optional: an empty one keeps the stored value). */
    public record Form(String provider, String fromAddress, String smtpHost, String smtpPort,
                        String smtpUser, String smtpPassword, String apiKey,
                        String mailgunDomain, String mailgunRegion, String webhookUrl,
                        String webhookSecret, boolean active, boolean clearSecrets) {
    }

    /** The configuration with the secrets opened (for the sending). */
    public EmailSettings load(long organizationId) {
        EmailSettings stored = repository.find(organizationId);
        if (stored == null) {
            return new EmailSettings(organizationId, "NONE", "ultibiz@localhost", null, 587, null,
                    null, null, null, "us", null, null, false);
        }
        return new EmailSettings(stored.organizationId(), stored.provider(),
                stored.fromAddress(), stored.smtpHost(), stored.smtpPort(), stored.smtpUser(),
                cipher.decrypt(stored.smtpPassword()), cipher.decrypt(stored.apiKey()),
                stored.mailgunDomain(), stored.mailgunRegion(), stored.webhookUrl(),
                cipher.decrypt(stored.webhookSecret()), stored.active());
    }

    /** The configuration for the interface: the secrets are only "set" or "not set". */
    public EmailSettings masked(long organizationId) {
        EmailSettings settings = load(organizationId);
        return new EmailSettings(settings.organizationId(), settings.provider(),
                settings.fromAddress(), settings.smtpHost(), settings.smtpPort(), settings.smtpUser(),
                mask(repository.hasSecret(organizationId, "smtp_password_encrypted")),
                mask(repository.hasSecret(organizationId, "api_key_encrypted")),
                settings.mailgunDomain(), settings.mailgunRegion(), settings.webhookUrl(),
                mask(repository.hasSecret(organizationId, "webhook_secret_encrypted")),
                settings.active());
    }

    /** Saves the configuration; the empty secrets keep the stored ones. */
    @Transactional
    public EmailSettings save(long organizationId, Form form, String actor) {
        if (!cipher.isAvailable()) {
            throw new IllegalStateException(
                    "The deployment key is missing — a secret cannot be stored");
        }
        EmailSettings current = load(organizationId);
        String smtpPassword = pick(form.smtpPassword(), current.smtpPassword(),
                form.clearSecrets());
        String apiKey = pick(form.apiKey(), current.apiKey(), form.clearSecrets());
        String webhookSecret = pick(form.webhookSecret(), current.webhookSecret(),
                form.clearSecrets());
        int port = 587;
        try {
            if (form.smtpPort() != null && !form.smtpPort().isBlank()) {
                port = Integer.parseInt(form.smtpPort().trim());
            }
        } catch (NumberFormatException ex) {
            port = 587;
        }
        EmailSettings settings = new EmailSettings(organizationId,
                form.provider() == null ? "NONE" : form.provider(),
                form.fromAddress() == null || form.fromAddress().isBlank()
                        ? current.fromAddress() : form.fromAddress().trim(),
                blankToNull(form.smtpHost()), port, blankToNull(form.smtpUser()),
                cipher.encrypt(smtpPassword), cipher.encrypt(apiKey),
                blankToNull(form.mailgunDomain()),
                form.mailgunRegion() == null || form.mailgunRegion().isBlank()
                        ? "us" : form.mailgunRegion().trim().toLowerCase(),
                blankToNull(form.webhookUrl()), cipher.encrypt(webhookSecret), form.active());
        repository.save(settings, actor);
        LOG.info("The e-mail configuration of the organisation {} was changed by {} ({})",
                organizationId, actor, settings.providerName());
        return settings;
    }

    private static String pick(String input, String stored, boolean clear) {
        if (clear) {
            return null;
        }
        if (input == null || input.isBlank()) {
            return stored;
        }
        return input.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String mask(boolean isSet) {
        return isSet ? "********" : null;
    }
}
