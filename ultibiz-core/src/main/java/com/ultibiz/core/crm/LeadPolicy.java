package com.ultibiz.core.crm;

/**
 * The lead rules: a lead qualifies with a way to reach them, and converts
 * only once — the converted lead keeps the party it created.
 */
public final class LeadPolicy {

    private LeadPolicy() {
    }

    /** A lead can be qualified when it has company and a contact channel. */
    public static void checkQualifiable(String company, String email, String phone, String status) {
        if (!"NEW".equals(status)) {
            throw new CrmException("Only a NEW lead can be qualified (it is " + status + ")");
        }
        if (company == null || company.isBlank()) {
            throw new CrmException("A lead needs a company");
        }
        if ((email == null || email.isBlank()) && (phone == null || phone.isBlank())) {
            throw new CrmException("A lead needs an e-mail or a phone to be qualified");
        }
    }

    /** A lead converts only from QUALIFIED. */
    public static void checkConvertible(String status) {
        if (!"QUALIFIED".equals(status)) {
            throw new CrmException("Only a QUALIFIED lead converts (it is " + status + ")");
        }
    }
}
