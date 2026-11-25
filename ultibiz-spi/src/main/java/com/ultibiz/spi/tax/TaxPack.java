package com.ultibiz.spi.tax;

import java.util.List;

/**
 * SPI for a country tax pack: the rules of one country's tax system
 * (Indonesia PPN, an EU VAT pack, …). A pack knows how to turn taxable lines
 * into tax lines and how the country rounds.
 */
public interface TaxPack {

    /** ISO-3166 alpha-2 country code this pack serves. */
    String countryCode();

    /** Human-readable name, e.g. "Indonesia PPN 2024+". */
    String name();

    /**
     * Computes the tax lines for the given taxable lines.
     *
     * @param context country, currency, document date and the rounding rules
     * @return one tax line per taxable line that is taxable
     */
    List<TaxLine> compute(List<TaxableAmount> taxableLines, TaxPackContext context);

    /** A taxable amount before tax. */
    record TaxableAmount(String categoryCode, java.math.BigDecimal netAmount,
                         java.math.BigDecimal grossAmount, String description) {
    }
}
