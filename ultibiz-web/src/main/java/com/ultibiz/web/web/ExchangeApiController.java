package com.ultibiz.web.web;

import com.ultibiz.core.application.ExchangeService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.exchange.ExchangeMessage;
import com.ultibiz.core.settings.SystemSettingsService;
import com.ultibiz.web.security.SiteScopeProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The machine-to-machine side of the exchange layer: the inbound webhook of a
 * partner and the local simulators used by the CI and by the loopback of the
 * outbound documents.
 */
@RestController
@RequestMapping("/api/exchange")
public class ExchangeApiController {

    private final ExchangeService exchange;
    private final SiteScopeProvider scopeProvider;
    private final String webhookSecret;

    /** The settings of the deployment: the secret can be changed at runtime. */
    @Autowired(required = false)
    private SystemSettingsService settings;

    public ExchangeApiController(ExchangeService exchange, SiteScopeProvider scopeProvider,
                                 @Value("${ultibiz.exchange.webhook-secret:}") String webhookSecret) {
        this.exchange = exchange;
        this.scopeProvider = scopeProvider;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
    }

    /** The shared secret of the exchange: the setting of the deployment wins. */
    private String effectiveSecret() {
        if (settings == null) {
            return webhookSecret;
        }
        String configured = settings.value("exchange.webhook.secret");
        if (configured == null || configured.isBlank()) {
            return webhookSecret;
        }
        return configured.trim();
    }

    /**
     * The inbound webhook. The partner sends its payload with the idempotency
     * key in the {@code X-Idempotency-Key} header; a repeated delivery with the
     * same key returns the message that already exists.
     */
    @PostMapping("/inbound/{partnerCode}")
    public ResponseEntity<Map<String, Object>> inbound(
            @PathVariable String partnerCode,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Exchange-Secret", required = false) String secret,
            @RequestBody(required = false) String payload) {
        String expected = effectiveSecret();
        if (expected.isEmpty()) {
            // closed by default: without a configured secret no partner may push
            Map<String, Object> refused = new LinkedHashMap<>();
            refused.put("error", "The inbound webhook is not configured");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(refused);
        }
        if (!expected.equals(secret)) {
            Map<String, Object> refused = new LinkedHashMap<>();
            refused.put("error", "Invalid webhook secret");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(refused);
        }
        SiteScope scope = scopeProvider.current();
        String body = payload == null ? "" : payload;
        String key = idempotencyKey;
        if (key == null || key.isBlank()) {
            key = "sha-" + Integer.toHexString(body.hashCode());
        }
        ExchangeService.ReceiveResult result = exchange.receive(scope, "api", partnerCode, key,
                body, contentType(body));
        if (!result.duplicate()) {
            exchange.processInbound(scope, "api", result.messageId());
        }
        ExchangeMessage message = exchange.byId(scope, result.messageId());
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("messageId", result.messageId());
        answer.put("duplicate", result.duplicate());
        answer.put("status", message == null ? null : message.status());
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(answer);
    }

    /**
     * The local simulator: it accepts a message the way a partner endpoint
     * would and answers with a short acknowledgement. Used by the CI and by
     * the outbound loopback.
     */
    @PostMapping("/sim/{protocol}")
    public Map<String, Object> simulate(@PathVariable String protocol,
                                        @RequestBody(required = false) String payload) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("accepted", true);
        answer.put("protocol", protocol.toUpperCase());
        answer.put("bytes", payload == null ? 0 : payload.length());
        answer.put("receivedAt", java.time.Instant.now().toString());
        return answer;
    }

    /** The monitoring counters of the exchange layer. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        SiteScope scope = scopeProvider.current();
        Map<String, Object> answer = new LinkedHashMap<>();
        for (var count : exchange.statusCounts(scope)) {
            answer.put(count.status().toLowerCase(), count.count());
        }
        return answer;
    }

    private static String contentType(String body) {
        String trimmed = body == null ? "" : body.stripLeading();
        if (trimmed.startsWith("<")) {
            return "XML";
        }
        if (trimmed.startsWith("ISA") || trimmed.startsWith("UNB")) {
            return "EDI";
        }
        return "JSON";
    }
}
