package com.ultibiz.core.hrm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The leave rules: working days, overlaps and the balance. */
class LeavePolicyTest {

    @Test
    void theWorkingDaysSkipTheWeekend() {
        // 2026-11-23 is a Monday, 2026-11-29 a Sunday
        assertEquals(new BigDecimal("5"),
                LeavePolicy.workingDays(LocalDate.parse("2026-11-23"), LocalDate.parse("2026-11-29")));
        assertEquals(new BigDecimal("1"),
                LeavePolicy.workingDays(LocalDate.parse("2026-11-27"), LocalDate.parse("2026-11-27")));
    }

    @Test
    void aBackwardsOrWeekendOnlyRangeIsRefused() {
        assertThrows(HrmException.class, () -> LeavePolicy.workingDays(
                LocalDate.parse("2026-11-29"), LocalDate.parse("2026-11-23")));
        assertThrows(HrmException.class, () -> LeavePolicy.workingDays(
                LocalDate.parse("2026-11-28"), LocalDate.parse("2026-11-29"))); // Sat-Sun only
    }

    @Test
    void anOverlapWithAnOpenRequestIsDetected() {
        List<LeavePolicy.Window> existing = List.of(
                new LeavePolicy.Window(LocalDate.parse("2026-11-23"), LocalDate.parse("2026-11-25"),
                        "APPROVED"),
                new LeavePolicy.Window(LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-02"),
                        "REJECTED"));
        assertTrue(LeavePolicy.overlaps(LocalDate.parse("2026-11-25"),
                LocalDate.parse("2026-11-27"), existing), "touching an approved request overlaps");
        assertFalse(LeavePolicy.overlaps(LocalDate.parse("2026-12-01"),
                LocalDate.parse("2026-12-02"), existing), "a rejected request does not block");
        assertFalse(LeavePolicy.overlaps(LocalDate.parse("2026-11-26"),
                LocalDate.parse("2026-11-27"), existing));
    }

    @Test
    void theBalanceMustCoverTheRequest() {
        LeavePolicy.checkBalance(new BigDecimal("12.0"), new BigDecimal("4.0"), new BigDecimal("8"));
        assertThrows(HrmException.class, () -> LeavePolicy.checkBalance(
                new BigDecimal("12.0"), new BigDecimal("4.0"), new BigDecimal("8.5")));
    }
}
