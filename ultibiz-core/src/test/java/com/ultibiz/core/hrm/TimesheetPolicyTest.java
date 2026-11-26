package com.ultibiz.core.hrm;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The timesheet rules. */
class TimesheetPolicyTest {

    @Test
    void aValidEntryPasses() {
        assertDoesNotThrow(() -> TimesheetPolicy.check(LocalDate.now(), new BigDecimal("7.5")));
    }

    @Test
    void theFutureZeroAndOverflowAreRefused() {
        assertThrows(HrmException.class, () -> TimesheetPolicy.check(
                LocalDate.now().plusDays(1), new BigDecimal("8")));
        assertThrows(HrmException.class, () -> TimesheetPolicy.check(
                LocalDate.now(), BigDecimal.ZERO));
        assertThrows(HrmException.class, () -> TimesheetPolicy.check(
                LocalDate.now(), new BigDecimal("24.5")));
        assertThrows(HrmException.class, () -> TimesheetPolicy.check(null, new BigDecimal("8")));
    }
}
