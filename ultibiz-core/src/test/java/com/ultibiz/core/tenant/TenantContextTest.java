package com.ultibiz.core.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The tenant of the thread: it is set, it is given back and it is removed. */
class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void withoutTenantTheControlDatabaseIsUsed() {
        assertNull(TenantContext.current());
    }

    @Test
    void theTenantIsSetAndCleared() {
        TenantContext.set("ultibiz_tenant_acme");
        assertEquals("ultibiz_tenant_acme", TenantContext.current());
        TenantContext.clear();
        assertNull(TenantContext.current());
    }

    @Test
    void anEmptyTenantRemovesTheCurrent() {
        TenantContext.set("ultibiz_tenant_acme");
        TenantContext.set("  ");
        assertNull(TenantContext.current());
    }

    @Test
    void theActionRunsInTheTenantAndThePreviousIsGivenBack() {
        TenantContext.set("ultibiz_control");
        String result = TenantContext.callInTenant("ultibiz_tenant_acme",
                () -> TenantContext.current());
        assertEquals("ultibiz_tenant_acme", result);
        assertEquals("ultibiz_control", TenantContext.current());
    }
}
