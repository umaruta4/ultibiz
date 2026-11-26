package com.ultibiz.web.web;

import com.ultibiz.core.application.EmailSettingsService;
import com.ultibiz.core.mail.EmailMessage;
import com.ultibiz.core.mail.EmailSettings;
import com.ultibiz.web.security.UltiBizUserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The e-mail settings of the organisation: the administrator chooses the
 * transport (SMTP or an API provider), fills the credentials and sends a
 * test e-mail — without touching the environment of the server.
 */
@Controller
@RequestMapping("/admin/settings/email")
public class EmailSettingsController {

    private static final java.util.List<String> PROVIDERS = java.util.List.of(
            "NONE", "SMTP", "MANDRILL", "SENDGRID", "MAILGUN", "WEBHOOK");

    private final EmailSettingsService settings;
    private final ReportMailer mailer;

    public EmailSettingsController(EmailSettingsService settings, ReportMailer mailer) {
        this.settings = settings;
        this.mailer = mailer;
    }

    @GetMapping
    public String page(@AuthenticationPrincipal UltiBizUserPrincipal principal, Model model) {
        long organizationId = principal.organizationId();
        EmailSettings current = settings.masked(organizationId);
        model.addAttribute("settings", current);
        model.addAttribute("providers", PROVIDERS);
        model.addAttribute("smtpKeySet", current.smtpPassword() != null);
        model.addAttribute("apiKeySet", current.apiKey() != null);
        model.addAttribute("webhookSecretSet", current.webhookSecret() != null);
        return "admin/settings_email";
    }

    @PostMapping
    public String save(@AuthenticationPrincipal UltiBizUserPrincipal principal,
                       @RequestParam String provider,
                       @RequestParam(required = false) String fromAddress,
                       @RequestParam(required = false) String smtpHost,
                       @RequestParam(required = false, defaultValue = "587") String smtpPort,
                       @RequestParam(required = false) String smtpUser,
                       @RequestParam(required = false) String smtpPassword,
                       @RequestParam(required = false) String apiKey,
                       @RequestParam(required = false) String mailgunDomain,
                       @RequestParam(required = false, defaultValue = "us") String mailgunRegion,
                       @RequestParam(required = false) String webhookUrl,
                       @RequestParam(required = false) String webhookSecret,
                       @RequestParam(required = false, defaultValue = "false") boolean clearSecrets,
                       @RequestParam(required = false, defaultValue = "true") boolean active,
                       RedirectAttributes redirect) {
        try {
            settings.save(principal.organizationId(), new EmailSettingsService.Form(
                    provider, fromAddress, smtpHost, smtpPort, smtpUser, smtpPassword, apiKey,
                    mailgunDomain, mailgunRegion, webhookUrl, webhookSecret, active, clearSecrets),
                    principal.getUsername());
            redirect.addFlashAttribute("flash", "mail.settingsSaved");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/settings/email";
    }

    @PostMapping("/test")
    public String test(@AuthenticationPrincipal UltiBizUserPrincipal principal,
                       @RequestParam String email,
                       RedirectAttributes redirect) {
        try {
            boolean sent = mailer.send(new EmailMessage(email,
                    "UltiBiz — the test e-mail",
                    "This e-mail proves that the transport of the organisation works.",
                    null, null), principal.organizationId());
            redirect.addFlashAttribute(sent ? "flash" : "error",
                    sent ? "mail.testSent" : "mail.testFailed");
        } catch (RuntimeException ex) {
            redirect.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:/admin/settings/email";
    }
}
