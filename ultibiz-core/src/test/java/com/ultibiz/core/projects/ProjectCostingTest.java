package com.ultibiz.core.projects;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The project arithmetic. */
class ProjectCostingTest {

    @Test
    void theHourlyCostComesFromTheContract() {
        // 10,000,000 a month at 40 h/week: 10,000,000 / (40 * 4.348125) = 57,496.0471
        assertEquals(new BigDecimal("57496.0471"),
                ProjectCosting.hourlyCost(new BigDecimal("10000000"), new BigDecimal("40")));
        assertThrows(ProjectException.class, () -> ProjectCosting.hourlyCost(
                new BigDecimal("10000000"), BigDecimal.ZERO));
    }

    @Test
    void theRevenueCombinesHoursAndMilestones() {
        assertEquals(new BigDecimal("7000000.00"), ProjectCosting.revenue(
                new BigDecimal("20"), new BigDecimal("250000"), new BigDecimal("2000000")));
    }

    @Test
    void theProfitabilityAndTheBudgetUsage() {
        assertEquals(0, new BigDecimal("100").compareTo(
                ProjectCosting.profitability(new BigDecimal("300"), new BigDecimal("200"))));
        assertEquals(new BigDecimal("40.00"), ProjectCosting.budgetUsage(
                new BigDecimal("200"), new BigDecimal("500")));
        assertEquals(BigDecimal.ZERO, ProjectCosting.budgetUsage(
                new BigDecimal("200"), BigDecimal.ZERO), "no budget, no usage");
    }
}
