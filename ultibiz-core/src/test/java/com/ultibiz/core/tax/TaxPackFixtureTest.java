package com.ultibiz.core.tax;

import com.ultibiz.spi.tax.TaxLine;
import com.ultibiz.spi.tax.TaxPack;
import com.ultibiz.spi.tax.TaxPackContext;
import com.ultibiz.spi.tax.TaxRateLookup;
import com.ultibiz.spi.tax.TaxRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixture table for the country tax packs: the expected amounts below are the
 * contract every country pack must satisfy.
 *
 * <pre>
 * pack      category        net        gross      inclusive  tax
 * ID PPN    PPN_STANDARD    100000.00  100000.00  no         11000.00
 * ID PPN    PPN_STANDARD    0          111000.00  yes        11000.00
 * ID PPN    PPN_REDUCED     100000.00  100000.00  no          2000.00
 * ID PPN    PPN_EXEMPT      100000.00  100000.00  no             0.00
 * EU VAT    VAT_STANDARD    100000.00  100000.00  no         20000.00
 * EU VAT    VAT_STANDARD    0          120000.00  yes        20000.00
 * </pre>
 */
class TaxPackFixtureTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 15);

    /** No database rules: the packs fall back to their built-in country rates. */
    private final TaxRateLookup noRules = (org, category, date) -> Optional.empty();

    private TaxPackContext context(String country, boolean inclusive) {
        return new TaxPackContext(1L, country, "IDR", DATE, inclusive,
                RoundingMode.HALF_UP, TaxPackContext.RoundingLevel.LINE, 2);
    }

    @Test
    void indonesiaPackMatchesTheFixtureTable() {
        TaxPack pack = new IndonesiaTaxPack(noRules);

        TaxLine exclusive = pack.compute(
                List.of(new TaxPack.TaxableAmount("PPN_STANDARD", new BigDecimal("100000.00"),
                        new BigDecimal("100000.00"), "item")), context("ID", false)).get(0);
        assertEquals(new BigDecimal("100000.00"), exclusive.baseAmount());
        assertEquals(new BigDecimal("11000.00"), exclusive.taxAmount());

        TaxLine inclusive = pack.compute(
                List.of(new TaxPack.TaxableAmount("PPN_STANDARD", BigDecimal.ZERO,
                        new BigDecimal("111000.00"), "item")), context("ID", true)).get(0);
        assertEquals(new BigDecimal("100000.00"), inclusive.baseAmount());
        assertEquals(new BigDecimal("11000.00"), inclusive.taxAmount());

        TaxLine reduced = pack.compute(
                List.of(new TaxPack.TaxableAmount("PPN_REDUCED", new BigDecimal("100000.00"),
                        new BigDecimal("100000.00"), "item")), context("ID", false)).get(0);
        assertEquals(new BigDecimal("2000.00"), reduced.taxAmount());

        assertTrue(pack.compute(
                List.of(new TaxPack.TaxableAmount("PPN_EXEMPT", new BigDecimal("100000.00"),
                        new BigDecimal("100000.00"), "item")), context("ID", false)).isEmpty(),
                "an exempt category produces no tax line");
    }

    @Test
    void euVatPackMatchesTheFixtureTable() {
        TaxPack pack = new EuVatTaxPack(noRules);

        TaxLine exclusive = pack.compute(
                List.of(new TaxPack.TaxableAmount("VAT_STANDARD", new BigDecimal("100000.00"),
                        new BigDecimal("100000.00"), "item")), context("DE", false)).get(0);
        assertEquals(new BigDecimal("100000.00"), exclusive.baseAmount());
        assertEquals(new BigDecimal("20000.00"), exclusive.taxAmount());

        TaxLine inclusive = pack.compute(
                List.of(new TaxPack.TaxableAmount("VAT_STANDARD", BigDecimal.ZERO,
                        new BigDecimal("120000.00"), "item")), context("DE", true)).get(0);
        assertEquals(new BigDecimal("100000.00"), inclusive.baseAmount());
        assertEquals(new BigDecimal("20000.00"), inclusive.taxAmount());

        assertTrue(EuVatTaxPack.isEuCountry("DE"));
        assertTrue(EuVatTaxPack.isEuCountry("nl"));
        assertTrue(!EuVatTaxPack.isEuCountry("ID"));
    }

    @Test
    void databaseRulesAreUsedWhenTheCategoryIsNotBuiltIn() {
        TaxRateLookup lookup = (org, category, date) -> Optional.of(
                new TaxRule(1L, category, "ID", "Custom 7%", new BigDecimal("0.070000"),
                        LocalDate.of(2020, 1, 1), null, 10));
        TaxPack pack = new DefaultTaxPack(lookup);

        TaxLine line = pack.compute(
                List.of(new TaxPack.TaxableAmount("CUSTOM", new BigDecimal("10000.00"),
                        new BigDecimal("10000.00"), "item")), context("ID", false)).get(0);

        assertEquals(new BigDecimal("700.00"), line.taxAmount());
    }
}
