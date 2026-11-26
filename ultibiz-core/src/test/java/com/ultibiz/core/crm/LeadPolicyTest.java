package com.ultibiz.core.crm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The lead rules. */
class LeadPolicyTest {

    @Test
    void aLeadQualifiesWithAContactChannel() {
        assertDoesNotThrow(() -> LeadPolicy.checkQualifiable(
                "PT X", "info@x.example", null, "NEW"));
        assertDoesNotThrow(() -> LeadPolicy.checkQualifiable(
                "PT X", null, "+62-21", "NEW"));
        assertThrows(CrmException.class, () -> LeadPolicy.checkQualifiable(
                "PT X", null, null, "NEW"), "no way to reach them");
        assertThrows(CrmException.class, () -> LeadPolicy.checkQualifiable(
                " ", "info@x.example", null, "NEW"), "no company");
        assertThrows(CrmException.class, () -> LeadPolicy.checkQualifiable(
                "PT X", "info@x.example", null, "QUALIFIED"), "only NEW qualifies");
    }

    @Test
    void aLeadConvertsOnlyFromQualified() {
        assertDoesNotThrow(() -> LeadPolicy.checkConvertible("QUALIFIED"));
        assertThrows(CrmException.class, () -> LeadPolicy.checkConvertible("NEW"));
        assertThrows(CrmException.class, () -> LeadPolicy.checkConvertible("CONVERTED"));
    }
}
