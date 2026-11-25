package com.ultibiz.core.tax;

import com.ultibiz.spi.tax.TaxLine;
import com.ultibiz.spi.tax.TaxPack;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaxMathTest {

    @Test
    void exclusivePricingAddsTaxOnTopOfTheNet() {
        TaxLine line = TaxMath.line("PPN_STANDARD", "ID", "VAT 11%",
                new BigDecimal("0.110000"),
                new TaxPack.TaxableAmount("PPN_STANDARD", new BigDecimal("100000.00"),
                        new BigDecimal("100000.00"), "item"),
                false, RoundingMode.HALF_UP, 2, null);

        assertEquals(new BigDecimal("100000.00"), line.baseAmount());
        assertEquals(new BigDecimal("11000.00"), line.taxAmount());
    }

    @Test
    void inclusivePricingDerivesTheNetFromTheGross() {
        TaxLine line = TaxMath.line("PPN_STANDARD", "ID", "VAT 11%",
                new BigDecimal("0.110000"),
                new TaxPack.TaxableAmount("PPN_STANDARD", BigDecimal.ZERO,
                        new BigDecimal("111000.00"), "item"),
                true, RoundingMode.HALF_UP, 2, null);

        assertEquals(new BigDecimal("100000.00"), line.baseAmount());
        assertEquals(new BigDecimal("11000.00"), line.taxAmount());
    }

    @Test
    void documentLevelRoundingPushesTheDifferenceIntoTheLastLine() {
        TaxLine first = TaxMath.line("VAT_STANDARD", "DE", "VAT 20%",
                new BigDecimal("0.200000"),
                new TaxPack.TaxableAmount("VAT_STANDARD", new BigDecimal("33.33"), null, "a"),
                false, RoundingMode.HALF_UP, 2, null);
        TaxLine second = TaxMath.line("VAT_STANDARD", "DE", "VAT 20%",
                new BigDecimal("0.200000"),
                new TaxPack.TaxableAmount("VAT_STANDARD", new BigDecimal("33.33"), null, "b"),
                false, RoundingMode.HALF_UP, 2, null);

        List<TaxLine> adjusted = TaxMath.applyDocumentRounding(List.of(first, second),
                RoundingMode.HALF_UP, 2);

        // 33.33 + 33.33 = 66.66 * 20% = 13.332 -> 13.33 at document level;
        // the per-line rounding gave 6.67 + 6.67, so the last line absorbs -0.01
        assertEquals(new BigDecimal("6.66"), adjusted.get(1).taxAmount());
        assertEquals(new BigDecimal("13.33"),
                adjusted.stream().map(TaxLine::taxAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
