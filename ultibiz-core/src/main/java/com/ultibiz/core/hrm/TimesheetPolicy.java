package com.ultibiz.core.hrm;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The timesheet rules: a day never holds more than 24 hours and never the future. */
public final class TimesheetPolicy {

    public static final BigDecimal MAX_HOURS_PER_ENTRY = new BigDecimal("24");

    private TimesheetPolicy() {
    }

    /** Validates one entry. */
    public static void check(LocalDate workDate, BigDecimal hours) {
        if (workDate == null) {
            throw new HrmException("The timesheet needs a work date");
        }
        if (workDate.isAfter(LocalDate.now())) {
            throw new HrmException("The timesheet cannot be in the future");
        }
        if (hours == null || hours.signum() <= 0) {
            throw new HrmException("The timesheet hours must be positive");
        }
        if (hours.compareTo(MAX_HOURS_PER_ENTRY) > 0) {
            throw new HrmException("One timesheet entry cannot exceed 24 hours");
        }
    }
}
