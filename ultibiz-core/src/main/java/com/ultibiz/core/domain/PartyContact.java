package com.ultibiz.core.domain;

/** A contact person of a party. */
public record PartyContact(
        long id,
        long partyId,
        String contactName,
        String contactRole,
        String email,
        String phone,
        boolean primaryContact) {
}
