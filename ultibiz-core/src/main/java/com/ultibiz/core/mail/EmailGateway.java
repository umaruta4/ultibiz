package com.ultibiz.core.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Picks the API e-mail provider and sends through it. The gateway is empty
 * when no provider is configured (the SMTP path in the web layer stays the
 * default then).
 */
public class EmailGateway {

    private static final Logger LOG = LoggerFactory.getLogger(EmailGateway.class);

    private final EmailTransport transport;

    public EmailGateway(String provider, String from,
                        String mandrillKey, String sendGridKey,
                        String mailgunDomain, String mailgunKey, String mailgunRegion,
                        String webhookUrl, String webhookSecret) {
        String name = provider == null ? "" : provider.trim().toLowerCase();
        this.transport = switch (name) {
            case "mandrill", "mailchimp" -> new MandrillTransport(mandrillKey, from);
            case "sendgrid" -> new SendGridTransport(sendGridKey, from);
            case "mailgun" -> new MailgunTransport(mailgunDomain, mailgunKey, mailgunRegion, from);
            case "webhook" -> new WebhookTransport(webhookUrl, webhookSecret, from);
            default -> null;
        };
        if (transport != null && !transport.isConfigured()) {
            LOG.warn("The e-mail provider {} is selected but not configured — the e-mail is inactive",
                    transport.name());
        }
    }

    /** The gateway of the settings of an organisation (the secrets come decrypted). */
    public static EmailGateway from(EmailSettings settings) {
        if (settings == null) {
            return new EmailGateway("", "", "", "", "", "", "us", "", "");
        }
        return new EmailGateway(settings.providerName(), settings.fromAddress(),
                settings.apiKey(), settings.apiKey(), settings.mailgunDomain(),
                settings.apiKey(), settings.mailgunRegion(), settings.webhookUrl(),
                settings.webhookSecret());
    }

    /** Whether an API provider is selected and ready. */
    public boolean isEnabled() {
        return transport != null && transport.isConfigured();
    }

    /** The selected provider (empty when none). */
    public String providerName() {
        return transport == null ? "" : transport.name();
    }

    /** Sends the message through the selected provider. */
    public boolean send(EmailMessage message) {
        if (!isEnabled()) {
            return false;
        }
        boolean sent = transport.send(message);
        if (sent) {
            LOG.info("The e-mail to {} was sent via {}", message.to(), transport.name());
        } else {
            LOG.warn("The e-mail to {} failed via {}", message.to(), transport.name());
        }
        return sent;
    }
}
