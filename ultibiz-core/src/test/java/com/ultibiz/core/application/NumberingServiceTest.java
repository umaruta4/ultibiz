package com.ultibiz.core.application;

import com.ultibiz.core.domain.NumberingSequence;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NumberingServiceTest {

    private static NumberingSequence sequence(String periodType, int padding, int fiscalStartMonth) {
        return new NumberingSequence(1L, 1L, null, "SO", "Sales Order", "SO-", "", padding,
                1L, 0L, periodType, fiscalStartMonth, null);
    }

    @Test
    void nonPeriodicSequenceHasNoPeriodKey() {
        assertNull(NumberingService.periodKey(sequence("NONE", 6, 1), LocalDate.of(2026, 11, 25)));
    }

    @Test
    void monthlySequenceUsesYearMonth() {
        assertEquals("2026-11",
                NumberingService.periodKey(sequence("MONTHLY", 6, 1), LocalDate.of(2026, 11, 25)));
    }

    @Test
    void yearlySequenceUsesCalendarYearWhenFiscalYearStartsInJanuary() {
        assertEquals("2026",
                NumberingService.periodKey(sequence("YEARLY", 6, 1), LocalDate.of(2026, 1, 1)));
        assertEquals("2026",
                NumberingService.periodKey(sequence("YEARLY", 6, 1), LocalDate.of(2026, 12, 31)));
    }

    @Test
    void yearlySequenceShiftsToFiscalYearWhenFiscalYearStartsLater() {
        // fiscal year starts in April: March belongs to the previous fiscal year
        assertEquals("2025",
                NumberingService.periodKey(sequence("YEARLY", 6, 4), LocalDate.of(2026, 3, 31)));
        assertEquals("2026",
                NumberingService.periodKey(sequence("YEARLY", 6, 4), LocalDate.of(2026, 4, 1)));
    }

    @Test
    void formatRendersPrefixPeriodAndPaddedNumber() {
        assertEquals("SO-2026-000001",
                NumberingService.format(sequence("YEARLY", 6, 1), 1L, "2026"));
        assertEquals("SO-000042",
                NumberingService.format(sequence("NONE", 6, 1), 42L, null));
    }
}
