package com.ultibiz.core.projects;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The resource rules. */
class ResourcePolicyTest {

    private static final BigDecimal CAPACITY = new BigDecimal("40");

    @Test
    void anOverallocatedEmployeeIsRefused() {
        List<ResourcePolicy.Assignment> existing = List.of(
                new ResourcePolicy.Assignment(1, new BigDecimal("30"),
                        LocalDate.parse("2026-11-01"), null));
        // 30 + 20 > 40 on an overlapping window
        assertThrows(ProjectException.class, () -> ResourcePolicy.check(CAPACITY, existing,
                2, new BigDecimal("20"), LocalDate.parse("2026-11-15"), null));
        // 30 + 10 = 40 is exactly at capacity
        assertDoesNotThrow(() -> ResourcePolicy.check(CAPACITY, existing,
                2, new BigDecimal("10"), LocalDate.parse("2026-11-15"), null));
    }

    @Test
    void nonOverlappingWindowsDoNotAddUp() {
        List<ResourcePolicy.Assignment> existing = List.of(
                new ResourcePolicy.Assignment(1, new BigDecimal("40"),
                        LocalDate.parse("2026-01-01"), LocalDate.parse("2026-06-30")));
        assertDoesNotThrow(() -> ResourcePolicy.check(CAPACITY, existing,
                2, new BigDecimal("40"), LocalDate.parse("2026-07-01"), null));
    }

    @Test
    void anAssignmentNeverExceedsTheCapacityAlone() {
        assertThrows(ProjectException.class, () -> ResourcePolicy.check(CAPACITY, List.of(),
                1, new BigDecimal("41"), LocalDate.now(), null));
        assertThrows(ProjectException.class, () -> ResourcePolicy.check(CAPACITY, List.of(),
                1, BigDecimal.ZERO, LocalDate.now(), null));
    }
}
