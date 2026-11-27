package com.ultibiz.core.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.domain.Party;
import com.ultibiz.core.exchange.CanonicalDocument;
import com.ultibiz.core.exchange.CanonicalJson;
import com.ultibiz.core.exchange.ExchangeAdapter;
import com.ultibiz.core.exchange.ExchangeException;
import com.ultibiz.core.exchange.ExchangeMessage;
import com.ultibiz.core.exchange.ExchangePartner;
import com.ultibiz.core.exchange.ExchangeLine;
import com.ultibiz.core.exchange.ValidationIssue;
import com.ultibiz.core.infrastructure.ExchangeRepository;
import com.ultibiz.core.infrastructure.PartyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The data-exchange engine: inbox/outbox with idempotent reception, the
 * RECEIVED → PARSED → VALIDATED → MAPPED → APPLIED pipeline, retry with
 * exponential backoff, the dead-letter queue and the operator replay.
 *
 * <p>Inbound documents are applied as <b>draft</b> business documents — never
 * auto-posted. A supplier invoice becomes a draft AP bill, an inbound order a
 * draft sales order. If the partner party cannot be resolved the message stops
 * at MAPPED with an explanatory error.
 */
@Service
public class ExchangeService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExchangeRepository exchange;
    private final Map<String, ExchangeAdapter> adapters;
    private final AuditService audit;
    private final PartyRepository parties;
    private final ApService ap;
    private final SalesOrderService sales;
    private final TransactionTemplate tx;

    public ExchangeService(ExchangeRepository exchange, List<ExchangeAdapter> adapters,
                           AuditService audit, PartyRepository parties, ApService ap,
                           SalesOrderService sales, TransactionTemplate tx) {
        this.exchange = exchange;
        this.adapters = new HashMap<>();
        for (ExchangeAdapter adapter : adapters) {
            this.adapters.put(adapter.protocol(), adapter);
        }
        this.audit = audit;
        this.parties = parties;
        this.ap = ap;
        this.sales = sales;
        this.tx = tx;
    }

    /** The result of a reception: the message id and whether it was a duplicate. */
    public record ReceiveResult(long messageId, boolean duplicate) {
    }

    /**
     * Receives an inbound payload. The idempotency key is unique per
     * (organization, partner, direction): a re-delivery returns the existing
     * message and never creates a second document.
     */
    public ReceiveResult receive(SiteScope scope, String actor, String partnerCode,
                                 String idempotencyKey, String payload, String payloadFormat) {
        if (scope.isDenyAll()) {
            throw new OrderException("No site access");
        }
        ExchangePartner partner = exchange.partnerByCode(scope.organizationId(), partnerCode);
        if (partner == null || !partner.active()) {
            throw new OrderException("Unknown or inactive exchange partner: " + partnerCode);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new OrderException("The idempotency key is required");
        }
        try {
            long id = exchange.insertMessage(scope.organizationId(), partner.id(), "INBOUND",
                    partner.protocol(), "UNKNOWN", null, idempotencyKey, payload, payloadFormat,
                    ExchangeMessage.RECEIVED, null);
            audit.record(AuditEvent.of(scope.organizationId(), actor, "EXCHANGE_RECEIVE",
                    "exchange_message", String.valueOf(id), "{\"partner\":\"" + partnerCode + "\"}", null));
            return new ReceiveResult(id, false);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            ExchangeMessage existing = exchange.messageByIdempotencyKey(scope.organizationId(),
                    partner.id(), "INBOUND", idempotencyKey);
            return new ReceiveResult(existing.id(), true);
        }
    }

    /**
     * The scope of an inbound partner: the organisation of the partner, with
     * the access of the organisation (a partner is a machine, not a user).
     *
     * The code of a partner must be unique in the installation: a code that
     * exists in two organisations is refused, never guessed.
     */
    public SiteScope scopeOfPartner(String partnerCode) {
        List<ExchangePartner> partners = exchange.partnersByCode(partnerCode);
        if (partners.isEmpty()) {
            throw new OrderException("Unknown exchange partner: " + partnerCode);
        }
        if (partners.size() > 1) {
            throw new OrderException("The partner code is not unique: " + partnerCode);
        }
        ExchangePartner partner = partners.get(0);
        if (!partner.active()) {
            throw new OrderException("The exchange partner is not active: " + partnerCode);
        }
        return new SiteScope(partner.organizationId(), java.util.Set.of(), false);
    }

    /** Runs the inbound pipeline for one message. */
    public void processInbound(SiteScope scope, String actor, long messageId) {
        tx.executeWithoutResult(status -> doProcessInbound(scope, actor, messageId));
    }

    private void doProcessInbound(SiteScope scope, String actor, long messageId) {
        ExchangeMessage message = exchange.messageById(scope.organizationId(), messageId);
        if (message == null) {
            throw new OrderException("Exchange message not found: " + messageId);
        }
        if (!ExchangeMessage.RECEIVED.equals(message.status())
                && !ExchangeMessage.FAILED.equals(message.status())) {
            return; // already processed or in a terminal state
        }
        ExchangePartner partner = exchange.partnerById(scope.organizationId(), message.partnerId());
        ExchangeAdapter adapter = partner == null ? null : adapters.get(partner.protocol());
        if (adapter == null) {
            failOrKill(message, "No adapter for protocol "
                    + (partner == null ? "?" : partner.protocol()));
            return;
        }
        try {
            CanonicalDocument document = adapter.parse(message.payload());
            exchange.markParsed(messageId, document.type(), document.documentNumber(),
                    CanonicalJson.toJson(document));

            List<ValidationIssue> issues = adapter.validate(document);
            String report = issuesJson(issues);
            boolean fatal = issues.stream().anyMatch(i -> ValidationIssue.FATAL.equals(i.severity()));
            if (fatal) {
                exchange.updateStatus(messageId, ExchangeMessage.REJECTED, null, report);
                audit.record(AuditEvent.of(scope.organizationId(), actor, "EXCHANGE_REJECTED",
                        "exchange_message", String.valueOf(messageId), report, null));
                return;
            }
            exchange.updateStatus(messageId, ExchangeMessage.VALIDATED, null, report);
            exchange.updateStatus(messageId, ExchangeMessage.MAPPED, null, report);

            Applied applied = apply(scope, actor, message, document);
            if (applied == null) {
                exchange.updateStatus(messageId, ExchangeMessage.MAPPED, null,
                        report == null ? "no party mapping" : report);
            } else {
                exchange.markApplied(messageId, applied.type(), applied.id());
            }
            audit.record(AuditEvent.of(scope.organizationId(), actor, "EXCHANGE_PROCESSED",
                    "exchange_message", String.valueOf(messageId), null, null));
        } catch (Exception ex) {
            failOrKill(message, ex.getMessage());
        }
    }

    /** Queues an outbound document: the payload is rendered now, sent later. */
    public long queueOutbound(SiteScope scope, String actor, String partnerCode,
                              CanonicalDocument document) {
        ExchangePartner partner = exchange.partnerByCode(scope.organizationId(), partnerCode);
        if (partner == null || !partner.active()) {
            throw new OrderException("Unknown or inactive exchange partner: " + partnerCode);
        }
        ExchangeAdapter adapter = adapters.get(partner.protocol());
        if (partner == null || adapter == null) {
            throw new OrderException("No adapter for protocol "
                    + (partner == null ? "?" : partner.protocol()));
        }
        List<ValidationIssue> issues = adapter.validate(document);
        boolean fatal = issues.stream().anyMatch(i -> ValidationIssue.FATAL.equals(i.severity()));
        if (fatal) {
            throw new OrderException("The document fails validation: " + issuesJson(issues));
        }
        String payload = adapter.write(document);
        String correlationId = document.type() + ":" + document.documentNumber();
        long id = exchange.insertMessage(scope.organizationId(), partner.id(), "OUTBOUND",
                partner.protocol(), document.type(), document.documentNumber(), correlationId,
                payload, payloadFormat(partner.protocol()), ExchangeMessage.QUEUED, correlationId);
        exchange.updateStatus(id, ExchangeMessage.QUEUED, null, null);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "EXCHANGE_QUEUE",
                "exchange_message", String.valueOf(id), null, null));
        return id;
    }

    /**
     * Delivers one queued outbound message. A loopback partner (endpoint
     * inside this instance, like the simulators) re-enters the payload as an
     * inbound message; a real endpoint is delivered over HTTP.
     */
    public void sendOutbound(SiteScope scope, String actor, long messageId) {
        tx.executeWithoutResult(status -> {
            ExchangeMessage message = exchange.messageById(scope.organizationId(), messageId);
            if (message == null || !ExchangeMessage.QUEUED.equals(message.status())) {
                return;
            }
            ExchangePartner partner = exchange.partnerById(scope.organizationId(), message.partnerId());
            try {
                if (partner != null && partner.endpointUrl() != null
                        && partner.endpointUrl().startsWith("/api/exchange/sim")) {
                    // loopback: the simulator is this instance — the payload
                    // re-enters as an inbound document of the same partner
                    receive(scope, actor, partner.code(),
                            message.correlationId() + "-loop", message.payload(),
                            message.payloadFormat());
                } else if (partner != null && partner.endpointUrl() != null) {
                    deliverHttp(partner, message);
                }
                exchange.updateStatus(messageId, ExchangeMessage.SENT, null, null);
                audit.record(AuditEvent.of(scope.organizationId(), actor, "EXCHANGE_SENT",
                        "exchange_message", String.valueOf(messageId), null, null));
            } catch (Exception ex) {
                failOrKill(message, ex.getMessage());
            }
        });
    }

    /** Retries every failed message whose backoff has expired. */
    public int retryDue(SiteScope scope, String actor) {
        List<ExchangeMessage> due = exchange.retryable(scope.organizationId(), Instant.now(), 100);
        for (ExchangeMessage message : due) {
            if ("INBOUND".equals(message.direction())) {
                processInbound(scope, actor, message.id());
            } else {
                // outbound retry: back to QUEUED, then send
                exchange.resetForReplay(message.id(), ExchangeMessage.QUEUED);
                sendOutbound(scope, actor, message.id());
            }
        }
        return due.size();
    }

    /** Operator replay: a DEAD or REJECTED message goes back to the start. */
    public void replay(SiteScope scope, String actor, long messageId) {
        ExchangeMessage message = exchange.messageById(scope.organizationId(), messageId);
        if (message == null) {
            throw new OrderException("Exchange message not found: " + messageId);
        }
        if (!ExchangeMessage.DEAD.equals(message.status())
                && !ExchangeMessage.REJECTED.equals(message.status())
                && !ExchangeMessage.FAILED.equals(message.status())) {
            throw new OrderException("Only a failed, rejected or dead message can be replayed");
        }
        exchange.resetForReplay(messageId, "INBOUND".equals(message.direction())
                ? ExchangeMessage.RECEIVED : ExchangeMessage.QUEUED);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "EXCHANGE_REPLAY",
                "exchange_message", String.valueOf(messageId), null, null));
    }

    public PageResult<ExchangeMessage> search(SiteScope scope, String status, String direction,
                                              Long partnerId, int page, int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        if (scope.isDenyAll()) {
            return new PageResult<>(List.of(), safePage, safeSize, 0);
        }
        long total = exchange.count(scope.organizationId(), status, direction, partnerId);
        return new PageResult<>(
                exchange.search(scope.organizationId(), status, direction, partnerId, safeSize,
                        safePage * safeSize),
                safePage, safeSize, total);
    }

    public ExchangeMessage byId(SiteScope scope, long id) {
        if (scope.isDenyAll()) {
            return null;
        }
        return exchange.messageById(scope.organizationId(), id);
    }

    public List<ExchangePartner> partners(SiteScope scope) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return exchange.partners(scope.organizationId());
    }

    public List<ExchangeRepository.StatusCount> statusCounts(SiteScope scope) {
        if (scope.isDenyAll()) {
            return List.of();
        }
        return exchange.statusCounts(scope.organizationId());
    }

    public long registerPartner(SiteScope scope, String actor, String code, String name,
                                String protocol, String direction, String endpointUrl,
                                String authType, String authReference, String peppolParticipantId,
                                String ediInterchangeId, String ediQualifier, String mappingConfig) {
        long id = exchange.insertPartner(scope.organizationId(), code, name, protocol, direction,
                endpointUrl, authType, authReference, peppolParticipantId, ediInterchangeId,
                ediQualifier, mappingConfig);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "EXCHANGE_PARTNER",
                "exchange_partner", String.valueOf(id), "{\"code\":\"" + code + "\"}", null));
        return id;
    }

    // -- internals -----------------------------------------------------------

    private record Applied(String type, Long id) {
    }

    /** Applies a validated inbound document as a draft business document. */
    private Applied apply(SiteScope scope, String actor, ExchangeMessage message,
                          CanonicalDocument document) {
        return switch (document.type()) {
            case CanonicalDocument.PURCHASE_INVOICE, CanonicalDocument.INVOICE -> {
                Long partyId = resolveParty(scope, document.seller());
                if (partyId == null) {
                    yield null;
                }
                List<PurchaseOrderLineDraft> lines = new ArrayList<>();
                for (ExchangeLine line : document.lines()) {
                    lines.add(new PurchaseOrderLineDraft(null, line.description(), line.quantity(),
                            line.unitPrice(), null));
                }
                LocalDate billDate = document.issueDate() == null
                        ? LocalDate.now() : document.issueDate();
                LocalDate dueDate = document.dueDate() == null
                        ? billDate.plusDays(30) : document.dueDate();
                var bill = ap.issueBill(scope, actor, "ID", partyId, null, billDate,
                        dueDate, document.currency(), lines);
                yield new Applied("ap_bill", bill.id());
            }
            case CanonicalDocument.ORDER -> {
                Long partyId = resolveParty(scope, document.seller());
                if (partyId == null) {
                    yield null;
                }
                List<SalesOrderLineDraft> lines = new ArrayList<>();
                for (ExchangeLine line : document.lines()) {
                    lines.add(new SalesOrderLineDraft(null, line.description(), line.quantity(),
                            line.unitPrice(), BigDecimal.ZERO, null));
                }
                var order = sales.create(scope, actor, "ID", partyId, document.issueDate(),
                        document.dueDate(), null, document.currency(), BigDecimal.ONE,
                        "via exchange " + document.documentNumber(), lines);
                yield new Applied("sales_order", order.id());
            }
            default -> null;
        };
    }

    private Long resolveParty(SiteScope scope, com.ultibiz.core.exchange.ExchangeParty party) {
        if (party == null || party.partyCode() == null) {
            return null;
        }
        Optional<Party> found = parties.findByCode(scope, party.partyCode());
        return found.map(Party::id).orElse(null);
    }

    private void failOrKill(ExchangeMessage message, String error) {
        int attempts = message.attempts() + 1;
        String shortError = error == null ? "unknown error"
                : error.substring(0, Math.min(error.length(), 900));
        if (attempts >= message.maxAttempts()) {
            exchange.markDead(message.id(), attempts, shortError);
        } else {
            long backoffMinutes = 1L << Math.min(attempts, 10);
            exchange.markFailed(message.id(), attempts,
                    Instant.now().plus(backoffMinutes, ChronoUnit.MINUTES), shortError);
        }
    }

    private void deliverHttp(ExchangePartner partner, ExchangeMessage message) throws Exception {
        var request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(partner.endpointUrl()))
                .header("Content-Type", contentType(message.payloadFormat()))
                .header("X-Idempotency-Key", message.idempotencyKey())
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(message.payload()))
                .timeout(java.time.Duration.ofSeconds(30))
                .build();
        try (var client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10)).build()) {
            var response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 400) {
                throw new ExchangeException("HTTP " + response.statusCode() + " from "
                        + partner.endpointUrl());
            }
        }
    }

    private static String contentType(String format) {
        return switch (format == null ? "JSON" : format) {
            case "XML" -> "application/xml";
            case "CSV" -> "text/csv";
            case "EDI" -> "application/edi-x12";
            default -> "application/json";
        };
    }

    private static String payloadFormat(String protocol) {
        return switch (protocol) {
            case "PEPPOL" -> "XML";
            case "X12", "EDIFACT" -> "EDI";
            case "CSV" -> "CSV";
            default -> "JSON";
        };
    }

    static String issuesJson(List<ValidationIssue> issues) {
        if (issues.isEmpty()) {
            return null;
        }
        ArrayNode array = JSON.createArrayNode();
        for (ValidationIssue issue : issues) {
            ObjectNode node = JSON.createObjectNode();
            node.put("rule", issue.ruleId());
            node.put("severity", issue.severity());
            node.put("message", issue.message());
            array.add(node);
        }
        return array.toString();
    }

    /** Parses a payload with the partner adapter without storing anything (mapping console). */
    public CanonicalDocument preview(SiteScope scope, String partnerCode, String payload) {
        ExchangePartner partner = exchange.partnerByCode(scope.organizationId(), partnerCode);
        if (partner == null) {
            throw new OrderException("Unknown exchange partner: " + partnerCode);
        }
        ExchangeAdapter adapter = adapters.get(partner.protocol());
        if (adapter == null) {
            throw new OrderException("No adapter for protocol " + partner.protocol());
        }
        return adapter.parse(payload);
    }

    /** The validation issues of a parsed document (mapping console). */
    public List<ValidationIssue> previewIssues(SiteScope scope, String partnerCode,
                                               CanonicalDocument document) {
        ExchangePartner partner = exchange.partnerByCode(scope.organizationId(), partnerCode);
        if (partner == null) {
            throw new OrderException("Unknown exchange partner: " + partnerCode);
        }
        ExchangeAdapter adapter = adapters.get(partner.protocol());
        if (adapter == null) {
            throw new OrderException("No adapter for protocol " + partner.protocol());
        }
        return adapter.validate(document);
    }
}
