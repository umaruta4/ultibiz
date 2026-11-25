package com.ultibiz.core.exchange;

import java.util.List;

/**
 * One exchange protocol. An adapter parses a raw payload into the canonical
 * document, validates a canonical document against the rules of the protocol
 * (fatal issues reject the document) and renders a canonical document for
 * outbound traffic.
 */
public interface ExchangeAdapter {

    /** The protocol code: REST_JSON, PEPPOL, X12, EDIFACT or CSV. */
    String protocol();

    /** Parses a raw payload into the canonical model. */
    CanonicalDocument parse(String payload) throws ExchangeException;

    /** Validates a canonical document; fatal issues reject it. */
    List<ValidationIssue> validate(CanonicalDocument document);

    /** Renders a canonical document into the wire format. */
    String write(CanonicalDocument document) throws ExchangeException;
}
