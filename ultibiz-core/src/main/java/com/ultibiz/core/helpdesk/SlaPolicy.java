package com.ultibiz.core.helpdesk;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * The SLA rules: the response and the resolution deadlines come from the
 * queue tier, and a ticket that passes its deadline is breached.
 */
public final class SlaPolicy {

    private SlaPolicy() {
    }

    /** The deadline of the first response. */
    public static LocalDateTime responseDue(LocalDateTime intake, int responseHours) {
        return intake.plusHours(responseHours);
    }

    /** The deadline of the resolution. */
    public static LocalDateTime resolveDue(LocalDateTime intake, int resolveHours) {
        return intake.plusHours(resolveHours);
    }

    /** Is the response overdue? */
    public static boolean responseBreached(LocalDateTime due, LocalDateTime firstResponseAt,
                                           LocalDateTime now) {
        if (due == null) {
            return false;
        }
        if (firstResponseAt != null) {
            return firstResponseAt.isAfter(due);
        }
        return now.isAfter(due);
    }

    /** Is the resolution overdue? */
    public static boolean resolveBreached(LocalDateTime due, LocalDateTime resolvedAt,
                                          LocalDateTime now) {
        if (due == null) {
            return false;
        }
        if (resolvedAt != null) {
            return resolvedAt.isAfter(due);
        }
        return now.isAfter(due);
    }

    /** The escalation level of a ticket that is overdue by the given hours. */
    public static int escalationLevel(LocalDateTime due, LocalDateTime now, int escalateHours) {
        if (due == null || !now.isAfter(due)) {
            return 0;
        }
        Duration overdue = Duration.between(due, now);
        long hours = overdue.toHours();
        // level 1 = overdue, then one level per escalation window (max 3)
        int level = (int) Math.min(3, hours / Math.max(1, escalateHours));
        return Math.max(1, level);
    }
}
