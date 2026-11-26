package com.ultibiz.core.dashboard;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The threshold rules. */
class ThresholdPolicyTest {

    private static ThresholdPolicy.Threshold threshold(String comparison, String severity) {
        return new ThresholdPolicy.Threshold("AR_OVERDUE_AMOUNT", "label", comparison,
                new BigDecimal("1000"), severity);
    }

    @Test
    void theComparisonDecidesTheBreach() {
        assertTrue(ThresholdPolicy.breached(threshold("GT", "WARNING"), new BigDecimal("1001")));
        assertFalse(ThresholdPolicy.breached(threshold("GT", "WARNING"), new BigDecimal("1000")));
        assertTrue(ThresholdPolicy.breached(threshold("GTE", "WARNING"), new BigDecimal("1000")));
        assertTrue(ThresholdPolicy.breached(threshold("LT", "INFO"), new BigDecimal("999")));
        assertFalse(ThresholdPolicy.breached(threshold("LT", "INFO"), new BigDecimal("1000")));
        assertTrue(ThresholdPolicy.breached(threshold("LTE", "INFO"), new BigDecimal("1000")));
    }

    @Test
    void anUnknownComparisonIsRefused() {
        assertThrows(DashboardException.class,
                () -> ThresholdPolicy.breached(threshold("BETWEEN", "INFO"), BigDecimal.ONE));
    }

    @Test
    void theSeverityIsOrdered() {
        assertTrue(ThresholdPolicy.severityRank("CRITICAL")
                > ThresholdPolicy.severityRank("WARNING"));
        assertTrue(ThresholdPolicy.severityRank("WARNING")
                > ThresholdPolicy.severityRank("INFO"));
    }
}
