package com.ultibiz.core.hrm;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The expense rules. */
class ExpensePolicyTest {

    @Test
    void theTotalIsTheSumOfTheLines() {
        assertEquals(new BigDecimal("350.00"), ExpensePolicy.total(List.of(
                new ExpensePolicy.Line(new BigDecimal("200.00"), "TRAVEL"),
                new ExpensePolicy.Line(new BigDecimal("150.00"), "MEAL"))));
    }

    @Test
    void anEmptyClaimOrABadLineIsRefused() {
        assertThrows(HrmException.class, () -> ExpensePolicy.total(List.of()));
        assertThrows(HrmException.class, () -> ExpensePolicy.total(List.of(
                new ExpensePolicy.Line(BigDecimal.ZERO, "TRAVEL"))));
        assertThrows(HrmException.class, () -> ExpensePolicy.total(List.of(
                new ExpensePolicy.Line(new BigDecimal("10"), " "))));
    }
}
