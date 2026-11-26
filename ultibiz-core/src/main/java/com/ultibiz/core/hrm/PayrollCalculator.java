package com.ultibiz.core.hrm;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The payroll arithmetic: the net is the base plus the allowances minus the
 * deductions, and the cost is allocated to the cost centres in proportion to
 * the approved timesheet hours.
 */
public final class PayrollCalculator {

    private PayrollCalculator() {
    }

    /** The net pay of one employee. */
    public static BigDecimal net(BigDecimal base, BigDecimal allowances, BigDecimal deductions) {
        if (base == null || base.signum() < 0) {
            throw new HrmException("The base salary cannot be negative");
        }
        BigDecimal net = base.add(allowances == null ? BigDecimal.ZERO : allowances)
                .subtract(deductions == null ? BigDecimal.ZERO : deductions);
        return net.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * The share of the pay that belongs to a cost centre, proportional to the
     * hours booked on it. No hours at all means everything stays on the
     * employee's own cost centre (share 1).
     */
    public static BigDecimal hoursShare(BigDecimal centreHours, BigDecimal totalHours) {
        if (totalHours == null || totalHours.signum() == 0 || centreHours == null) {
            return BigDecimal.ONE;
        }
        return centreHours.divide(totalHours, 6, RoundingMode.HALF_UP).min(BigDecimal.ONE);
    }
}
