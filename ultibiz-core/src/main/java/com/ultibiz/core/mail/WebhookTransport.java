package com.ultibiz.core.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A generic webhook: POST any URL with the message as JSON (the attachment
 * base64-encoded) and an optional shared secret in the X-Email-Secret header.
 * This is the fallback for every provider that is not built in.
 */
public final class WebhookTransport implements EmailTransport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String url;
    private final String secret;
    private final String from;

    public WebhookTransport(String url, String secret, String from) {
        this.url = url == null ? "" : url.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.from = from;
    }

    @Override
    public String name() {
        return "webhook";
    }

    @Override
    public boolean isConfigured() {
        return !url.isBlank();
    }

    /** The JSON body (package-visible for the tests). */
    static String payload(String from, EmailMessage message, byte[] attachment) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("from", from);
            body.put("to", message.to());
            body.put("subject", message.subject());
            body.put("text", message.text() == null ? "" : message.text());
            if (attachment != null) {
                body.put("attachment_name", message.attachmentName() == null
                        ? "attachment" : message.attachmentName());
                body.put("attachment_base64", Base64.getEncoder().encodeToString(attachment));
            }
            return JSON.writeValueAsString(body);
        } catch (Exception ex) {
            throw new IllegalStateException("The webhook payload could not be built", ex);
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
            Map<String, String> headers = secret.isBlank()
                    ? Map.of() : Map.of("X-Email-Secret", secret);
            return HttpJson.postJson(url, payload(from, message, attachment), headers);
        } catch (Exception ex) {
            return false;
        }
    }
}
