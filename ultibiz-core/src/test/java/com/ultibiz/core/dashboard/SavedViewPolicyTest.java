package com.ultibiz.core.dashboard;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The saved view rules. */
class SavedViewPolicyTest {

    @Test
    void aViewNeedsCodeTitleAndModule() {
        assertDoesNotThrow(() -> SavedViewPolicy.check("AR-OPEN", "Open receivables",
                "accounting"));
        assertThrows(DashboardException.class,
                () -> SavedViewPolicy.check(" ", "title", "accounting"));
        assertThrows(DashboardException.class,
                () -> SavedViewPolicy.check("CODE", " ", "accounting"));
        assertThrows(DashboardException.class,
                () -> SavedViewPolicy.check("CODE", "title", null));
    }
}
