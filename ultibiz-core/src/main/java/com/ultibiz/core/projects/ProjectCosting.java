package com.ultibiz.core.projects;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The project arithmetic: the hourly cost of an employee comes from the
 * contract, the cost of the project is the approved hours times that rate,
 * the revenue is the billable hours times the billing rate plus the invoiced
 * milestones, and the profitability is the revenue minus the cost.
 */
public final class ProjectCosting {

    /** The average weeks of a month (365.25 / 12 / 7). */
    public static final BigDecimal WEEKS_PER_MONTH = new BigDecimal("4.348125");

    private ProjectCosting() {
    }

    /** The hourly cost of an employee from the monthly base and the weekly hours. */
    public static BigDecimal hourlyCost(BigDecimal monthlyBase, BigDecimal weeklyHours) {
        if (weeklyHours == null || weeklyHours.signum() <= 0) {
            throw new ProjectException("The contract needs weekly hours");
        }
        if (monthlyBase == null || monthlyBase.signum() < 0) {
            throw new ProjectException("The base salary cannot be negative");
        }
        return monthlyBase.divide(weeklyHours.multiply(WEEKS_PER_MONTH), 4, RoundingMode.HALF_UP);
    }

    /** The labour cost of a number of hours at a rate. */
    public static BigDecimal labourCost(BigDecimal hours, BigDecimal hourlyRate) {
        return hours.multiply(hourlyRate).setScale(2, RoundingMode.HALF_UP);
    }

    /** The revenue: billable hours at the billing rate plus the invoiced milestones. */
    public static BigDecimal revenue(BigDecimal billableHours, BigDecimal billingRate,
                                     BigDecimal invoicedMilestones) {
        BigDecimal hours = billableHours == null ? BigDecimal.ZERO : billableHours;
        BigDecimal rate = billingRate == null ? BigDecimal.ZERO : billingRate;
        BigDecimal milestones = invoicedMilestones == null ? BigDecimal.ZERO : invoicedMilestones;
        return labourCost(hours, rate).add(milestones);
    }

    /** The profitability: revenue minus cost. */
    public static BigDecimal profitability(BigDecimal revenue, BigDecimal cost) {
        return revenue.subtract(cost);
    }

    /** The budget usage in percent (0 when there is no budget). */
    public static BigDecimal budgetUsage(BigDecimal cost, BigDecimal budget) {
        if (budget == null || budget.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return cost.multiply(BigDecimal.valueOf(100)).divide(budget, 2, RoundingMode.HALF_UP);
    }
}
