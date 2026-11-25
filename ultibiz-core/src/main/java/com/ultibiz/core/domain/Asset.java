package com.ultibiz.core.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A fixed asset in the register. */
public record Asset(
        long id,
        long organizationId,
        long siteId,
        long categoryId,
        String code,
        String name,
        LocalDate acquisitionDate,
        BigDecimal acquisitionCost,
        BigDecimal salvageValue,
        BigDecimal accumulatedDepreciation,
        BigDecimal bookValue,
        BigDecimal unitsProduced,
        Long locationId,
        String serialNumber,
        Long supplierPartyId,
        Long purchaseOrderId,
        LocalDate warrantyEnd,
        String status,
        LocalDate lastMaintenanceDate,
        LocalDate nextMaintenanceDate,
        long rowVersion) {
}
