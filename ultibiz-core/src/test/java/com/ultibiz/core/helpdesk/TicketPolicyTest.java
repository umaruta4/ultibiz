package com.ultibiz.core.helpdesk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The ticket rules. */
class TicketPolicyTest {

    @Test
    void theStatusMovesForward() {
        assertDoesNotThrow(() -> TicketPolicy.checkTransition("NEW", "TRIAGED"));
        assertDoesNotThrow(() -> TicketPolicy.checkTransition("IN_PROGRESS", "RESOLVED"));
        assertDoesNotThrow(() -> TicketPolicy.checkTransition("RESOLVED", "CLOSED"));
        assertDoesNotThrow(() -> TicketPolicy.checkTransition("RESOLVED", "IN_PROGRESS"));
        assertThrows(HelpdeskException.class,
                () -> TicketPolicy.checkTransition("CLOSED", "NEW"));
        assertThrows(HelpdeskException.class,
                () -> TicketPolicy.checkTransition("NEW", "CLOSED"));
    }

    @Test
    void aTicketNeedsASubjectAndADescription() {
        assertThrows(HelpdeskException.class,
                () -> TicketPolicy.checkIntake(" ", "description"));
        assertThrows(HelpdeskException.class,
                () -> TicketPolicy.checkIntake("subject", " "));
        assertDoesNotThrow(() -> TicketPolicy.checkIntake("subject", "description"));
    }

    @Test
    void thePriorityIsReadFromTheWords() {
        assertEquals("URGENT", TicketPolicy.detectPriority("Server down",
                "Our server is down since morning"));
        assertEquals("HIGH", TicketPolicy.detectPriority("Urgent request",
                "Please help immediately"));
        assertEquals("LOW", TicketPolicy.detectPriority("Question about the invoice",
                "How to print the invoice"));
        assertEquals("NORMAL", TicketPolicy.detectPriority("Printer offline",
                "The printer does not work"));
    }
}
