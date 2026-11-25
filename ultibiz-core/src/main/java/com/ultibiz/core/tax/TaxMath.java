package com.ultibiz.core.tax;

import com.ultibiz.spi.tax.TaxLine;
import com.ultibiz.spi.tax.TaxPack;
import com.ultibiz.spi.tax.TaxRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * The arithmetic of tax computation, shared by all country packs.
 *
 * <p>Inclusive pricing: the entered amount already contains the tax, so the
 * net is derived from the gross. Exclusive pricing: the tax is added on top.
 * Rounding happens per line or once for the whole document, depending on the
 * country's rules.
 */
public final class TaxMath {

    private TaxMath() {
    }

    /** Computes one tax line from a taxable amount. */
    public static TaxLine line(String categoryCode, String countryCode, String name, BigDecimal rate,
                                TaxPack.TaxableAmount amount, boolean inclusive,
                                RoundingMode mode, int scale, TaxRule rule) {
        BigDecimal net = amount.netAmount() == null ? BigDecimal.ZERO : amount.netAmount();
        BigDecimal gross = amount.grossAmount() == null ? net : amount.grossAmount();
        BigDecimal base;
        BigDecimal tax;
        if (inclusive) {
            base = gross.divide(BigDecimal.ONE.add(rate), scale, mode);
            tax = gross.subtract(base).setScale(scale, mode);
        } else {
            base = net;
            tax = net.multiply(rate).setScale(scale, mode);
        }
        return new TaxLine(categoryCode, countryCode, name, rate, base, tax, rule);
    }

    /**
     * Adjusts the tax of the last line so the document total matches the
     * sum of the unrounded line taxes (document-level rounding).
     */
    public static List<TaxLine> applyDocumentRounding(List<TaxLine> lines, RoundingMode mode, int scale) {
        if (lines == null || lines.size() < 2) {
            return lines;
        }
        BigDecimal raw = BigDecimal.ZERO;
        BigDecimal roundedLines = BigDecimal.ZERO;
        for (TaxLine line : lines) {
            raw = raw.add(line.baseAmount().multiply(line.rate()));
            roundedLines = roundedLines.add(line.taxAmount());
        }
        BigDecimal target = raw.setScale(scale, mode);
        BigDecimal diff = target.subtract(roundedLines);
        if (diff.signum() == 0) {
            return lines;
        }
        List<TaxLine> adjusted = new ArrayList<>(lines);
        TaxLine last = adjusted.get(adjusted.size() - 1);
        adjusted.set(adjusted.size() - 1, new TaxLine(last.categoryCode(), last.countryCode(), last.name(),
                last.rate(), last.baseAmount(), last.taxAmount().add(diff), last.rule()));
        return adjusted;
    }
}
