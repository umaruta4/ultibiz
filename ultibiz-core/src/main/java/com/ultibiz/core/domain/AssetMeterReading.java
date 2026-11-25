package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A meter reading of an asset (drives units-of-production depreciation). */
public record AssetMeterReading(
        long id,
        long assetId,
        LocalDate readingDate,
        BigDecimal meterValue,
        BigDecimal previousValue,
        BigDecimal consumption) {
}
