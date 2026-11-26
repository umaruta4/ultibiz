package com.ultibiz.core.mail;

/**
 * The e-mail configuration of an organisation. The secrets come from the
 * database already decrypted — they are never shown in the interface.
 */
public record EmailSettings(long organizationId, String provider, String fromAddress,
                            String smtpHost, int smtpPort, String smtpUser, String smtpPassword,
                            String apiKey, String mailgunDomain, String mailgunRegion,
                            String webhookUrl, String webhookSecret, boolean active) {

    /** The provider of the settings (in upper case). */
    public String providerName() {
        return provider == null ? "NONE" : provider.trim().toUpperCase();
    }

    /** Whether a transport is selected and usable. */
    public boolean isUsable() {
        if (!active) {
            return false;
        }
        return switch (providerName()) {
            case "SMTP" -> smtpHost != null && !smtpHost.isBlank();
            case "MANDRILL", "SENDGRID" -> apiKey != null && !apiKey.isBlank();
            case "MAILGUN" -> mailgunDomain != null && !mailgunDomain.isBlank()
                    && apiKey != null && !apiKey.isBlank();
            case "WEBHOOK" -> webhookUrl != null && !webhookUrl.isBlank();
            default -> false;
        };
    }
}
