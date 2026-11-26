package com.ultibiz.core.dashboard;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * The scheduling rules: the next run of a daily, weekly or monthly
 * subscription, always at the same local time.
 */
public final class ReportSchedulePolicy {

    public static final LocalTime RUN_TIME = LocalTime.of(6, 0);

    private ReportSchedulePolicy() {
    }

    /** The next run after the given moment. */
    public static LocalDateTime nextRun(String frequency, LocalDateTime after) {
        LocalDateTime base = after.withNano(0);
        return switch (frequency == null ? "MONTHLY" : frequency) {
            case "DAILY" -> base.with(RUN_TIME).isAfter(after)
                    ? base.with(RUN_TIME) : base.plusDays(1).with(RUN_TIME);
            case "WEEKLY" -> {
                LocalDateTime candidate = base.with(RUN_TIME);
                // the weekly run is on the Monday of the next week
                int days = (8 - base.getDayOfWeek().getValue()) % 7;
                candidate = candidate.plusDays(days);
                yield candidate.isAfter(after) ? candidate : candidate.plusWeeks(1);
            }
            case "MONTHLY" -> {
                LocalDateTime candidate = base.withDayOfMonth(1).with(RUN_TIME);
                yield candidate.isAfter(after) ? candidate : candidate.plusMonths(1);
            }
            default -> throw new DashboardException("Unknown frequency: " + frequency);
        };
    }
}
