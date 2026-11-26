package com.ultibiz.web.web;

import com.ultibiz.core.application.EmailSettingsService;
import com.ultibiz.core.infrastructure.DashboardRepository;
import com.ultibiz.core.mail.EmailGateway;
import com.ultibiz.core.mail.EmailMessage;
import com.ultibiz.core.mail.EmailSettings;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.file.Path;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mails the scheduled reports to the subscriber.
 *
 * <p>The transport comes from the settings of the organisation (the administrator
 * keeps them in the interface): an API provider (Mailchimp/Mandrill, SendGrid,
 * Mailgun or a generic webhook) or an SMTP relay. Without a configuration in the
 * interface the environment variables are the fallback, and without both the
 * mailer is inactive and the report stays on the disk.
 */
@Component
public class ReportMailer {

    private static final Logger LOG = LoggerFactory.getLogger(ReportMailer.class);

    private final EmailSettingsService settingsService;
    private final EmailGateway environmentGateway;
    private final EmailSettings environmentSettings;
    private final boolean environmentSmtp;

    public ReportMailer(EmailSettingsService settingsService,
                        @Value("${spring.mail.host:}") String smtpHost,
                        @Value("${spring.mail.port:587}") int smtpPort,
                        @Value("${spring.mail.username:}") String smtpUser,
                        @Value("${spring.mail.password:}") String smtpPassword,
                        @Value("${ultibiz.mail.from:ultibiz@localhost}") String from,
                        @Value("${ultibiz.mail.provider:}") String provider,
                        @Value("${ultibiz.mail.mandrill.api-key:}") String mandrillKey,
                        @Value("${ultibiz.mail.sendgrid.api-key:}") String sendGridKey,
                        @Value("${ultibiz.mail.mailgun.domain:}") String mailgunDomain,
                        @Value("${ultibiz.mail.mailgun.api-key:}") String mailgunKey,
                        @Value("${ultibiz.mail.mailgun.region:us}") String mailgunRegion,
                        @Value("${ultibiz.mail.webhook.url:}") String webhookUrl,
                        @Value("${ultibiz.mail.webhook.secret:}") String webhookSecret) {
        this.settingsService = settingsService;
        this.environmentGateway = new EmailGateway(provider, from, mandrillKey, sendGridKey,
                mailgunDomain, mailgunKey, mailgunRegion, webhookUrl, webhookSecret);
        this.environmentSmtp = smtpHost != null && !smtpHost.isBlank();
        this.environmentSettings = new EmailSettings(0, environmentSmtp ? "SMTP" : "NONE", from,
                smtpHost, smtpPort, smtpUser, smtpPassword, null, null, "us", null, null,
                environmentSmtp);
    }

    /** Mails a generated report to the subscriber. */
    public boolean send(DashboardRepository.ReportFile file, String email, long organizationId) {
        if (email == null || email.isBlank()) {
            return false;
        }
        EmailMessage message = new EmailMessage(email,
                "UltiBiz report " + file.reportCode() + " — " + file.period(),
                "The report " + file.reportCode() + " (" + file.period() + ") is attached.",
                Path.of(file.filePath()), file.fileName());
        return send(message, organizationId);
    }

    /** Sends a message with the transport of the organisation (or of the environment). */
    public boolean send(EmailMessage message, long organizationId) {
        EmailSettings settings = settingsService.load(organizationId);
        if (settings.isUsable()) {
            if ("SMTP".equals(settings.providerName())) {
                return sendSmtp(settings, message);
            }
            return EmailGateway.from(settings).send(message);
        }
        if (environmentGateway.isEnabled()) {
            return environmentGateway.send(message);
        }
        if (environmentSmtp) {
            return sendSmtp(environmentSettings, message);
        }
        return false;
    }

    /** Sends through an SMTP relay (the host, the port and the password come from the settings). */
    private boolean sendSmtp(EmailSettings settings, EmailMessage message) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", settings.smtpHost());
            props.put("mail.smtp.port", String.valueOf(settings.smtpPort()));
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "20000");
            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(settings.smtpUser(), settings.smtpPassword());
                }
            });
            MimeMessage mime = new MimeMessage(session);
            mime.setFrom(new InternetAddress(settings.fromAddress()));
            mime.setRecipients(Message.RecipientType.TO, InternetAddress.parse(message.to()));
            mime.setSubject(message.subject(), "UTF-8");
            jakarta.mail.internet.MimeBodyPart textPart = new jakarta.mail.internet.MimeBodyPart();
            textPart.setText(message.text(), "UTF-8");
            jakarta.mail.internet.MimeMultipart multipart = new jakarta.mail.internet.MimeMultipart();
            multipart.addBodyPart(textPart);
            if (message.attachment() != null) {
                byte[] bytes = java.nio.file.Files.readAllBytes(message.attachment());
                jakarta.mail.internet.MimeBodyPart filePart =
                        new jakarta.mail.internet.MimeBodyPart();
                filePart.setDataHandler(new jakarta.activation.DataHandler(
                        new jakarta.mail.util.ByteArrayDataSource(bytes, "application/octet-stream")));
                filePart.setFileName(message.attachmentName());
                multipart.addBodyPart(filePart);
            }
            mime.setContent(multipart);
            Transport.send(mime);
            LOG.info("The e-mail to {} was sent via SMTP ({})", message.to(), settings.smtpHost());
            return true;
        } catch (MessagingException ex) {
            LOG.warn("The e-mail to {} failed via SMTP: {}", message.to(), ex.getMessage());
            return false;
        } catch (Exception ex) {
            LOG.warn("The e-mail to {} failed: {}", message.to(), ex.getMessage());
            return false;
        }
    }

    public boolean isEnabled() {
        return environmentGateway.isEnabled() || environmentSmtp;
    }
}
