package com.ultibiz.core.projects;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The milestone rules: the flow is PENDING → REACHED → INVOICED, never backwards. */
public final class MilestonePolicy {

    private MilestonePolicy() {
    }

    /** Validates a new milestone. */
    public static void check(String name, LocalDate dueDate, BigDecimal amount) {
        if (name == null || name.isBlank()) {
            throw new ProjectException("A milestone needs a name");
        }
        if (dueDate == null) {
            throw new ProjectException("A milestone needs a due date");
        }
        if (amount == null || amount.signum() < 0) {
            throw new ProjectException("The milestone amount cannot be negative");
        }
    }

    /** The next status of a milestone; anything else is refused. */
    public static void checkTransition(String from, String to) {
        boolean allowed = ("PENDING".equals(from) && "REACHED".equals(to))
                || ("REACHED".equals(from) && "INVOICED".equals(to));
        if (!allowed) {
            throw new ProjectException("A milestone cannot move from " + from + " to " + to);
        }
    }
}
