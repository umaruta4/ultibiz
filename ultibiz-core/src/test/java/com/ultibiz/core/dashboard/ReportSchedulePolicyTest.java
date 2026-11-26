package com.ultibiz.core.dashboard;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scheduling rules. */
class ReportSchedulePolicyTest {

    @Test
    void theDailyRunIsTheNextMorning() {
        // 2026-11-26 is a Thursday, 09:00
        LocalDateTime next = ReportSchedulePolicy.nextRun("DAILY",
                LocalDateTime.parse("2026-11-26T09:00"));
        assertEquals(LocalDateTime.parse("2026-11-27T06:00"), next);

        LocalDateTime beforeSix = ReportSchedulePolicy.nextRun("DAILY",
                LocalDateTime.parse("2026-11-26T05:00"));
        assertEquals(LocalDateTime.parse("2026-11-26T06:00"), beforeSix);
    }

    @Test
    void theWeeklyRunIsTheNextMonday() {
        LocalDateTime next = ReportSchedulePolicy.nextRun("WEEKLY",
                LocalDateTime.parse("2026-11-26T09:00"));
        assertTrue(next.getDayOfWeek().getValue() == 1, "the weekly run is on a Monday");
        assertTrue(next.isAfter(LocalDateTime.parse("2026-11-26T09:00")));
    }

    @Test
    void theMonthlyRunIsTheFirstOfTheNextMonth() {
        LocalDateTime next = ReportSchedulePolicy.nextRun("MONTHLY",
                LocalDateTime.parse("2026-11-26T09:00"));
        assertEquals(LocalDateTime.parse("2026-12-01T06:00"), next);
    }

    @Test
    void anUnknownFrequencyIsRefused() {
        assertThrows(DashboardException.class, () -> ReportSchedulePolicy.nextRun("HOURLY",
                LocalDateTime.now()));
    }
}
