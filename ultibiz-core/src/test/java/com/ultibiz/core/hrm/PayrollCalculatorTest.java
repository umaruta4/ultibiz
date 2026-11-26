package com.ultibiz.core.hrm;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The payroll arithmetic. */
class PayrollCalculatorTest {

    @Test
    void theNetIsBasePlusAllowancesMinusDeductions() {
        assertEquals(new BigDecimal("10500.00"), PayrollCalculator.net(
                new BigDecimal("10000"), new BigDecimal("1000"), new BigDecimal("500")));
    }

    @Test
    void theNetNeverGoesNegativeAndTheBaseMustNotBeNegative() {
        assertEquals(new BigDecimal("0.00"), PayrollCalculator.net(
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("500")));
        assertThrows(HrmException.class, () -> PayrollCalculator.net(
                new BigDecimal("-1"), BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    void theHoursShareAllocatesProportionally() {
        assertEquals(new BigDecimal("0.250000"), PayrollCalculator.hoursShare(
                new BigDecimal("40"), new BigDecimal("160")));
        assertEquals(new BigDecimal("1"), PayrollCalculator.hoursShare(
                new BigDecimal("0"), BigDecimal.ZERO), "no hours at all keeps the own cost centre");
    }
}
