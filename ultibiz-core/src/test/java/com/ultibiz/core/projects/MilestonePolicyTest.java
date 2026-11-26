package com.ultibiz.core.projects;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The milestone rules. */
class MilestonePolicyTest {

    @Test
    void aMilestoneNeedsNameDateAndANonNegativeAmount() {
        assertDoesNotThrow(() -> MilestonePolicy.check("Go-live",
                LocalDate.parse("2026-12-15"), new BigDecimal("20000000")));
        assertThrows(ProjectException.class, () -> MilestonePolicy.check(" ",
                LocalDate.parse("2026-12-15"), BigDecimal.ONE));
        assertThrows(ProjectException.class, () -> MilestonePolicy.check("Go-live",
                null, BigDecimal.ONE));
        assertThrows(ProjectException.class, () -> MilestonePolicy.check("Go-live",
                LocalDate.parse("2026-12-15"), new BigDecimal("-1")));
    }

    @Test
    void theFlowNeverGoesBackwards() {
        assertDoesNotThrow(() -> MilestonePolicy.checkTransition("PENDING", "REACHED"));
        assertDoesNotThrow(() -> MilestonePolicy.checkTransition("REACHED", "INVOICED"));
        assertThrows(ProjectException.class, () -> MilestonePolicy.checkTransition(
                "PENDING", "INVOICED"));
        assertThrows(ProjectException.class, () -> MilestonePolicy.checkTransition(
                "INVOICED", "REACHED"));
        assertThrows(ProjectException.class, () -> MilestonePolicy.checkTransition(
                "REACHED", "PENDING"));
    }
}
