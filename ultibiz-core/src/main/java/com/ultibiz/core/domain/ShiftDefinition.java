package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.LocalTime;

/** A shift of the capacity calendar. */
public record ShiftDefinition(
        long id,
        long organizationId,
        String code,
        String name,
        LocalTime startTime,
        LocalTime endTime,
        BigDecimal hours) {
}
