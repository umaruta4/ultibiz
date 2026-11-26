package com.ultibiz.core.hrm;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;

/**
 * The leave rules: the days of a request are the working days between the two
 * dates, a request must not overlap another open one, and the balance of the
 * leave type must cover it.
 */
public final class LeavePolicy {

    private LeavePolicy() {
    }

    /** The working days (Mon–Fri) from start to end, both included. */
    public static BigDecimal workingDays(LocalDate start, LocalDate end) {
        if (start == null || end == null || end.isBefore(start)) {
            throw new HrmException("The leave range is empty or backwards");
        }
        int days = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days++;
            }
        }
        if (days == 0) {
            throw new HrmException("The leave range covers no working day");
        }
        return BigDecimal.valueOf(days);
    }

    /** A leave window. */
    public record Window(LocalDate start, LocalDate end, String status) {
    }

    /** True when the new range overlaps an open (submitted or approved) request. */
    public static boolean overlaps(LocalDate start, LocalDate end, List<Window> existing) {
        for (Window window : existing) {
            if ("REJECTED".equals(window.status()) || "CANCELLED".equals(window.status())) {
                continue;
            }
            if (!start.isAfter(window.end()) && !end.isBefore(window.start())) {
                return true;
            }
        }
        return false;
    }

    /** The balance must cover the requested days. */
    public static void checkBalance(BigDecimal allowance, BigDecimal used, BigDecimal requested) {
        if (allowance.subtract(used).compareTo(requested) < 0) {
            throw new HrmException("The leave balance does not cover " + requested
                    + " days (" + allowance.subtract(used) + " left)");
        }
    }
}
