package com.ultibiz.core.tenant;

/** One tenant: the organisation and the database it owns. */
public record Tenant(long id, long organizationId, String organizationCode, String databaseName,
                     String status) {

    /** The tenant is usable (the routing may go to its database). */
    public boolean isActive() {
        return "ACTIVE".equalsIgnoreCase(status);
    }
}
