package com.ultibiz.core.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The API e-mail providers: the payloads and the selection (no network). */
class EmailProvidersTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EmailMessage MESSAGE = new EmailMessage(
            "ops@example.com", "Report HEADCOUNT", "The report is attached.",
            Path.of("/tmp/report.pdf"), "report.pdf");
    private static final byte[] BYTES = "%PDF-1.5 test".getBytes(StandardCharsets.UTF_8);

    @Test
    void theMandrillPayloadCarriesTheKeyAndTheAttachment() throws Exception {
        JsonNode body = JSON.readTree(MandrillTransport.payload("KEY", "from@x.com", MESSAGE, BYTES));
        assertEquals("KEY", body.get("key").asText());
        assertEquals("from@x.com", body.at("/message/from_email").asText());
        assertEquals("ops@example.com", body.at("/message/to/0/email").asText());
        assertEquals("Report HEADCOUNT", body.at("/message/subject").asText());
        assertEquals("report.pdf", body.at("/message/attachments/0/name").asText());
        assertFalse(body.at("/message/attachments/0/content").asText().isBlank(),
                "the attachment is base64");
    }

    @Test
    void theSendGridPayloadHasPersonalizations() throws Exception {
        JsonNode body = JSON.readTree(SendGridTransport.payload("from@x.com", MESSAGE, BYTES));
        assertEquals("ops@example.com", body.at("/personalizations/0/to/0/email").asText());
        assertEquals("from@x.com", body.at("/from/email").asText());
        assertEquals("text/plain", body.at("/content/0/type").asText());
        assertEquals("report.pdf", body.at("/attachments/0/filename").asText());
    }

    @Test
    void theWebhookPayloadHasTheMessage() throws Exception {
        JsonNode body = JSON.readTree(WebhookTransport.payload("from@x.com", MESSAGE, BYTES));
        assertEquals("ops@example.com", body.get("to").asText());
        assertEquals("report.pdf", body.get("attachment_name").asText());
        assertTrue(body.get("attachment_base64").asText().startsWith("JVBERi0"),
                "%PDF- in base64");
    }

    @Test
    void theMailgunBodyIsMultipart() {
        byte[] body = MailgunTransport.body("from@x.com", MESSAGE, BYTES, "report.pdf", "BOUNDARY");
        String text = new String(body, StandardCharsets.UTF_8);
        assertTrue(text.contains("name=\"from\""), "the fields");
        assertTrue(text.contains("name=\"attachment\"; filename=\"report.pdf\""), "the file");
        assertTrue(text.endsWith("--BOUNDARY--\r\n"), "the closing boundary");
        assertTrue(text.contains("ops@example.com"), "the recipient");
    }

    @Test
    void theGatewaySelectsTheProvider() {
        EmailGateway mandrill = new EmailGateway("mandrill", "from@x.com", "KEY", "", "", "", "us", "", "");
        assertTrue(mandrill.isEnabled(), "mandrill with a key is ready");
        assertEquals("mandrill", mandrill.providerName());

        EmailGateway mailchimp = new EmailGateway("mailchimp", "from@x.com", "KEY", "", "", "", "us", "", "");
        assertEquals("mandrill", mailchimp.providerName(), "mailchimp is the alias of mandrill");

        EmailGateway sendgrid = new EmailGateway("sendgrid", "from@x.com", "", "KEY", "", "", "us", "", "");
        assertEquals("sendgrid", sendgrid.providerName());

        EmailGateway mailgun = new EmailGateway("mailgun", "from@x.com", "", "", "mg.example.com", "KEY", "eu", "", "");
        assertTrue(mailgun.isEnabled(), "mailgun with domain and key is ready");

        EmailGateway webhook = new EmailGateway("webhook", "from@x.com", "", "", "", "", "us",
                "https://example.com/email", "secret");
        assertTrue(webhook.isEnabled(), "the webhook with a URL is ready");
    }

    @Test
    void theGatewayIsOffWithoutAProviderOrWithoutCredentials() {
        EmailGateway none = new EmailGateway("", "from@x.com", "", "", "", "", "us", "", "");
        assertFalse(none.isEnabled(), "no provider is inactive");
        assertFalse(none.send(MESSAGE), "sending is refused");

        EmailGateway unconfigured = new EmailGateway("mandrill", "from@x.com", "", "", "", "", "us", "", "");
        assertFalse(unconfigured.isEnabled(), "a provider without a key is inactive");
    }
}
