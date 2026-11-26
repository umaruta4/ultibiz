package com.ultibiz.core.projects;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The resource rules: an employee is never allocated for more than their
 * weekly hours across all the assignments that overlap in time.
 */
public final class ResourcePolicy {

    private ResourcePolicy() {
    }

    /** One assignment window. */
    public record Assignment(long projectId, BigDecimal hoursPerWeek,
                             LocalDate startDate, LocalDate endDate) {
        public boolean overlaps(LocalDate start, LocalDate end) {
            LocalDate ownEnd = endDate == null ? LocalDate.MAX : endDate;
            LocalDate otherEnd = end == null ? LocalDate.MAX : end;
            return !start.isAfter(ownEnd) && !otherEnd.isBefore(startDate);
        }
    }

    /** The weekly capacity: the contract's weekly hours. */
    public static void check(BigDecimal weeklyCapacity, List<Assignment> existing,
                             long newProjectId, BigDecimal newHours, LocalDate start, LocalDate end) {
        if (newHours == null || newHours.signum() <= 0) {
            throw new ProjectException("An assignment needs weekly hours");
        }
        if (newHours.compareTo(weeklyCapacity) > 0) {
            throw new ProjectException("One assignment cannot exceed the weekly capacity ("
                    + weeklyCapacity + " h)");
        }
        BigDecimal total = newHours;
        for (Assignment assignment : existing) {
            if (assignment.projectId() == newProjectId) {
                continue;   // replacing an assignment of the same project
            }
            if (assignment.overlaps(start, end)) {
                total = total.add(assignment.hoursPerWeek());
            }
        }
        if (total.compareTo(weeklyCapacity) > 0) {
            throw new ProjectException("The employee would be allocated " + total
                    + " h/week, over the capacity of " + weeklyCapacity + " h");
        }
    }
}
