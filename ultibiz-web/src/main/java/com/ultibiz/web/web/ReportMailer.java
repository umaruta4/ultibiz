package com.ultibiz.web.web;

import com.ultibiz.core.infrastructure.DashboardRepository;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Mails the scheduled reports to the subscriber. The mailer is inactive when
 * no SMTP host is configured (the report stays on the disk and the webhook).
 */
@Component
public class ReportMailer {

    private static final Logger LOG = LoggerFactory.getLogger(ReportMailer.class);

    private final JavaMailSender mail;
    private final boolean enabled;
    private final String from;

    public ReportMailer(JavaMailSender mail,
                        @Value("${spring.mail.host:}") String host,
                        @Value("${ultibiz.mail.from:ultibiz@localhost}") String from) {
        this.mail = mail;
        this.enabled = host != null && !host.isBlank();
        this.from = from;
        if (!enabled) {
            LOG.info("No SMTP host is configured — the report e-mail is inactive");
        }
    }

    /** Mails a generated report to the subscriber. */
    public boolean send(DashboardRepository.ReportFile file, String email) {
        if (!enabled || email == null || email.isBlank()) {
            return false;
        }
        try {
            var message = mail.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("UltiBiz report " + file.reportCode() + " — " + file.period());
            helper.setText("The report " + file.reportCode() + " (" + file.period() + ") is attached.");
            helper.addAttachment(file.fileName(), Path.of(file.filePath()).toFile());
            mail.send(message);
            LOG.info("The report {} was mailed to {}", file.fileName(), email);
            return true;
        } catch (Exception ex) {
            LOG.warn("The report e-mail failed for {}: {}", email, ex.getMessage());
            return false;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
