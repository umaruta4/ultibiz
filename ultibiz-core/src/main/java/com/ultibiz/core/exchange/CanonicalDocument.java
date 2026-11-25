package com.ultibiz.core.exchange;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * The canonical business document model (BDM) every adapter maps into and out
 * of. Totals are derived from the lines, so every adapter renders the same
 * document from the same data.
 */
public record CanonicalDocument(
        String type,
        String documentNumber,
        LocalDate issueDate,
        LocalDate dueDate,
        String currency,
        ExchangeParty seller,
        ExchangeParty buyer,
        List<ExchangeLine> lines,
        String paymentTerms,
        List<String> references) {

    public CanonicalDocument {
        lines = lines == null ? List.of() : List.copyOf(lines);
        references = references == null ? List.of() : List.copyOf(references);
    }

    public static final String INVOICE = "INVOICE";
    public static final String CREDIT_NOTE = "CREDIT_NOTE";
    public static final String ORDER = "ORDER";
    public static final String DESPATCH = "DESPATCH";
    public static final String PURCHASE_INVOICE = "PURCHASE_INVOICE";
    public static final String STATEMENT = "STATEMENT";

    /** The net total: the sum of the line amounts. */
    public BigDecimal netTotal() {
        return lines.stream().map(ExchangeLine::netAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    /** The tax total: the sum of the line taxes. */
    public BigDecimal taxTotal() {
        return lines.stream().map(ExchangeLine::taxAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    /** The gross total (net + tax). */
    public BigDecimal grossTotal() {
        return netTotal().add(taxTotal());
    }

    /** The tax breakdown, one entry per (code, percent). */
    public List<ExchangeTax> taxBreakdown() {
        var grouped = new java.util.TreeMap<String, ExchangeTax>();
        for (ExchangeLine line : lines) {
            grouped.compute(key(line), (k, old) -> old == null
                    ? new ExchangeTax(line.taxCode(), line.taxPercent(), line.netAmount(), line.taxAmount())
                    : new ExchangeTax(line.taxCode(), line.taxPercent(),
                            old.taxableAmount().add(line.netAmount()),
                            old.taxAmount().add(line.taxAmount())));
        }
        return List.copyOf(grouped.values());
    }

    private static String key(ExchangeLine line) {
        return line.taxCode() + "|" + line.taxPercent().stripTrailingZeros().toPlainString();
    }
}
