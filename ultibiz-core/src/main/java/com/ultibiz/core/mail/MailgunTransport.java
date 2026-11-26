package com.ultibiz.core.mail;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.UUID;

/**
 * Mailgun: POST /v3/{domain}/messages with basic auth (api:{key}) and a
 * multipart body — the file goes as the "attachment" field.
 */
public final class MailgunTransport implements EmailTransport {

    private final String domain;
    private final String apiKey;
    private final String from;
    private final String endpoint;

    public MailgunTransport(String domain, String apiKey, String region, String from) {
        this.domain = domain == null ? "" : domain.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.from = from;
        String host = "eu".equalsIgnoreCase(region == null ? "" : region)
                ? "api.eu.mailgun.net" : "api.mailgun.net";
        this.endpoint = "https://" + host + "/v3/" + this.domain + "/messages";
    }

    @Override
    public String name() {
        return "mailgun";
    }

    @Override
    public boolean isConfigured() {
        return !domain.isBlank() && !apiKey.isBlank();
    }

    /** The multipart body (package-visible for the tests). */
    static byte[] body(String from, EmailMessage message, byte[] attachment,
                       String fileName, String boundary) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeField(out, boundary, "from", from);
        writeField(out, boundary, "to", message.to());
        writeField(out, boundary, "subject", message.subject());
        writeField(out, boundary, "text", message.text() == null ? "" : message.text());
        if (attachment != null) {
            String header = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"attachment\";"
                    + " filename=\"" + fileName + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
            try {
                out.write(header.getBytes(StandardCharsets.UTF_8));
                out.write(attachment);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            } catch (java.io.IOException ex) {
                throw new IllegalStateException("The Mailgun body could not be built", ex);
            }
        }
        try {
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("The Mailgun body could not be built", ex);
        }
        return out.toByteArray();
    }

    private static void writeField(ByteArrayOutputStream out, String boundary,
                                   String name, String value) {
        String field = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
                + "\"\r\n\r\n" + value + "\r\n";
        try {
            out.write(field.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("The Mailgun body could not be built", ex);
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
            String boundary = "ultibiz-" + UUID.randomUUID();
            String basicAuth = Base64.getEncoder()
                    .encodeToString(("api:" + apiKey).getBytes(StandardCharsets.UTF_8));
            return HttpJson.postMultipart(endpoint,
                    body(from, message, attachment, message.attachmentName(), boundary),
                    boundary, basicAuth);
        } catch (Exception ex) {
            return false;
        }
    }
}
