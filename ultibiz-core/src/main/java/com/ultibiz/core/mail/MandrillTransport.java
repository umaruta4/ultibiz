package com.ultibiz.core.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Mailchimp Transactional (Mandrill): POST /api/1.0/messages/send.json with
 * the API key in the body, the attachment as base64.
 */
public final class MandrillTransport implements EmailTransport {

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String ENDPOINT = "https://mandrillapp.com/api/1.0/messages/send.json";

    private final String apiKey;
    private final String from;

    public MandrillTransport(String apiKey, String from) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.from = from;
    }

    @Override
    public String name() {
        return "mandrill";
    }

    @Override
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /** The JSON body (package-visible for the tests). */
    static String payload(String apiKey, String from, EmailMessage message, byte[] attachment) {
        try {
            Map<String, Object> body = Map.of(
                    "key", apiKey,
                    "message", Map.of(
                            "from_email", from,
                            "to", List.of(Map.of("email", message.to(), "type", "to")),
                            "subject", message.subject(),
                            "text", message.text() == null ? "" : message.text(),
                            "attachments", attachment == null ? List.of() : List.of(Map.of(
                                    "type", "application/octet-stream",
                                    "name", message.attachmentName() == null
                                            ? "attachment" : message.attachmentName(),
                                    "content", Base64.getEncoder().encodeToString(attachment)))));
            return JSON.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException("The Mandrill payload could not be built", ex);
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
            return HttpJson.postJson(ENDPOINT, payload(apiKey, from, message, attachment),
                    Map.of());
        } catch (Exception ex) {
            return false;
        }
    }
}
