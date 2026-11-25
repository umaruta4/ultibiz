package com.ultibiz.core.exchange;

/** A trading partner of the exchange layer. */
public record ExchangePartner(
        long id,
        long organizationId,
        String code,
        String name,
        String protocol,
        String direction,
        String endpointUrl,
        String authType,
        String authReference,
        String peppolParticipantId,
        String ediInterchangeId,
        String ediQualifier,
        String mappingConfig,
        boolean active) {
}
