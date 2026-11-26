package com.ultibiz.web.web;

import com.ultibiz.core.infrastructure.DashboardRepository;
import com.ultibiz.core.mail.EmailGateway;
import com.ultibiz.core.mail.EmailMessage;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Mails the scheduled reports to the subscriber. The transport is either an
 * API provider (Mailchimp/Mandrill, SendGrid, Mailgun or a generic webhook —
 * selected with `ultibiz.mail.provider`) or, without a provider, the SMTP
 * relay when one is configured. Without both the mailer is inactive and the
 * report stays on the disk.
 */
@Component
public class ReportMailer {

    private static final Logger LOG = LoggerFactory.getLogger(ReportMailer.class);

    private final JavaMailSender mail;
    private final EmailGateway gateway;
    private final String from;
    private final boolean smtpEnabled;

    public ReportMailer(JavaMailSender mail,
                        @Value("${spring.mail.host:}") String smtpHost,
                        @Value("${ultibiz.mail.from:ultibiz@localhost}") String from,
                        @Value("${ultibiz.mail.provider:}") String provider,
                        @Value("${ultibiz.mail.mandrill.api-key:}") String mandrillKey,
                        @Value("${ultibiz.mail.sendgrid.api-key:}") String sendGridKey,
                        @Value("${ultibiz.mail.mailgun.domain:}") String mailgunDomain,
                        @Value("${ultibiz.mail.mailgun.api-key:}") String mailgunKey,
                        @Value("${ultibiz.mail.mailgun.region:us}") String mailgunRegion,
                        @Value("${ultibiz.mail.webhook.url:}") String webhookUrl,
                        @Value("${ultibiz.mail.webhook.secret:}") String webhookSecret) {
        this.mail = mail;
        this.from = from;
        this.gateway = new EmailGateway(provider, from, mandrillKey, sendGridKey,
                mailgunDomain, mailgunKey, mailgunRegion, webhookUrl, webhookSecret);
        this.smtpEnabled = smtpHost != null && !smtpHost.isBlank();
        if (gateway.isEnabled()) {
            LOG.info("The report e-mail goes via the API provider {}", gateway.providerName());
        } else if (smtpEnabled) {
            LOG.info("The report e-mail goes via SMTP");
        } else {
            LOG.info("No e-mail transport is configured — the report e-mail is inactive");
        }
    }

    /** Mails a generated report to the subscriber. */
    public boolean send(DashboardRepository.ReportFile file, String email) {
        if (email == null || email.isBlank()) {
            return false;
        }
        EmailMessage message = new EmailMessage(email,
                "UltiBiz report " + file.reportCode() + " — " + file.period(),
                "The report " + file.reportCode() + " (" + file.period() + ") is attached.",
                Path.of(file.filePath()), file.fileName());
        if (gateway.isEnabled()) {
            return gateway.send(message);
        }
        if (smtpEnabled) {
            return sendSmtp(message);
        }
        return false;
    }

    private boolean sendSmtp(EmailMessage message) {
        try {
            var mime = mail.createMimeMessage();
            var helper = new MimeMessageHelper(mime, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.text());
            helper.addAttachment(message.attachmentName(), message.attachment().toFile());
            mail.send(mime);
            LOG.info("The report was mailed to {} via SMTP", message.to());
            return true;
        } catch (Exception ex) {
            LOG.warn("The report e-mail failed for {}: {}", message.to(), ex.getMessage());
            return false;
        }
    }

    public boolean isEnabled() {
        return gateway.isEnabled() || smtpEnabled;
    }
}
