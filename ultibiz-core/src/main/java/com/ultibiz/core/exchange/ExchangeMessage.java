package com.ultibiz.core.exchange;

import java.time.Instant;

/** One exchanged document in the inbox or outbox. */
public record ExchangeMessage(
        long id,
        long organizationId,
        long partnerId,
        String direction,
        String protocol,
        String documentType,
        String documentNumber,
        String idempotencyKey,
        String payload,
        String payloadFormat,
        String canonical,
        String status,
        String error,
        String validationReport,
        int attempts,
        int maxAttempts,
        Instant nextRetryAt,
        String appliedDocumentType,
        Long appliedDocumentId,
        String correlationId,
        Instant createdAt) {

    public static final String RECEIVED = "RECEIVED";
    public static final String PARSED = "PARSED";
    public static final String VALIDATED = "VALIDATED";
    public static final String MAPPED = "MAPPED";
    public static final String APPLIED = "APPLIED";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";
    public static final String DEAD = "DEAD";
    public static final String QUEUED = "QUEUED";
    public static final String SENT = "SENT";
}
