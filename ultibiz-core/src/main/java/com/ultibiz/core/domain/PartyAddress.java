package com.ultibiz.core.domain;

/** A postal address of a party. */
public record PartyAddress(
        long id,
        long partyId,
        String addressType,
        String addressLine,
        String city,
        String postalCode,
        String countryCode,
        boolean primaryAddress) {
}
