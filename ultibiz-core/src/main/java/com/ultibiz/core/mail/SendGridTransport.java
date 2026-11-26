package com.ultibiz.core.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * SendGrid: POST /v3/mail/send with a Bearer token, the attachment as base64.
 */
public final class SendGridTransport implements EmailTransport {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String ENDPOINT = "https://api.sendgrid.com/v3/mail/send";

    private final String apiKey;
    private final String from;

    public SendGridTransport(String apiKey, String from) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.from = from;
    }

    @Override
    public String name() {
        return "sendgrid";
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /** The JSON body (package-visible for the tests). */
    static String payload(String from, EmailMessage message, byte[] attachment) {
        try {
            Map<String, Object> body = Map.of(
                    "personalizations", List.of(Map.of(
                            "to", List.of(Map.of("email", message.to())))),
                    "from", Map.of("email", from),
                    "subject", message.subject(),
                    "content", List.of(Map.of(
                            "type", "text/plain",
                            "value", message.text() == null ? "" : message.text())),
                    "attachments", attachment == null ? List.of() : List.of(Map.of(
                            "content", Base64.getEncoder().encodeToString(attachment),
                            "filename", message.attachmentName() == null
                                    ? "attachment" : message.attachmentName(),
                            "type", "application/octet-stream")));
            return JSON.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException("The SendGrid payload could not be built", ex);
        }
    }

    @Override
    public boolean send(EmailMessage message) {
        if (!isConfigured()) {
            return false;
        }
        try {
            byte[] attachment = message.attachment() == null
                    ? null : Files.readAllBytes(message.attachment());
            return HttpJson.postJson(ENDPOINT, payload(from, message, attachment),
                    Map.of("Authorization", "Bearer " + apiKey));
        } catch (Exception ex) {
            return false;
        }
    }
}
