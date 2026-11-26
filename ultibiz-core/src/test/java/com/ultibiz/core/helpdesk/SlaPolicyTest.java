package com.ultibiz.core.helpdesk;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SLA rules. */
class SlaPolicyTest {

    private static final LocalDateTime INTAKE = LocalDateTime.parse("2026-11-26T09:00");

    @Test
    void theDeadlinesComeFromTheQueueTier() {
        assertEquals(LocalDateTime.parse("2026-11-26T17:00"),
                SlaPolicy.responseDue(INTAKE, 8));
        assertEquals(LocalDateTime.parse("2026-11-29T09:00"),
                SlaPolicy.resolveDue(INTAKE, 72));
    }

    @Test
    void anOpenTicketBreachesItsDeadline() {
        LocalDateTime due = SlaPolicy.resolveDue(INTAKE, 8);
        assertFalse(SlaPolicy.resolveBreached(due, null, INTAKE.plusHours(4)));
        assertTrue(SlaPolicy.resolveBreached(due, null, INTAKE.plusHours(9)));
        // answered in time, even checked later
        assertFalse(SlaPolicy.resolveBreached(due, INTAKE.plusHours(2),
                INTAKE.plusHours(20)));
        // answered too late
        assertTrue(SlaPolicy.resolveBreached(due, INTAKE.plusHours(9), INTAKE.plusHours(20)));
    }

    @Test
    void theEscalationGrowsWithTheOverdueTime() {
        LocalDateTime due = INTAKE;
        assertEquals(0, SlaPolicy.escalationLevel(due, INTAKE, 2));
        assertEquals(1, SlaPolicy.escalationLevel(due, INTAKE.plusHours(3), 2));
        assertEquals(2, SlaPolicy.escalationLevel(due, INTAKE.plusHours(5), 2));
        assertEquals(3, SlaPolicy.escalationLevel(due, INTAKE.plusHours(20), 2));
    }
}
