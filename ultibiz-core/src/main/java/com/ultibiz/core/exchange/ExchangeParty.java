package com.ultibiz.core.exchange;

/**
 * A party of an exchanged document. {@code partyCode} maps the party to the
 * internal master data when the partner config carries it; the electronic
 * address is the Peppol EndpointID (scheme:value) for Peppol traffic.
 */
public record ExchangeParty(
        String partyCode,
        String name,
        String electronicAddress,
        String vatNumber,
        String street,
        String city,
        String postalCode,
        String countryCode) {
}
