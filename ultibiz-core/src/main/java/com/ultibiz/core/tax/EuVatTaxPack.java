package com.ultibiz.core.tax;

import com.ultibiz.spi.tax.TaxLine;
import com.ultibiz.spi.tax.TaxPack;
import com.ultibiz.spi.tax.TaxPackContext;
import com.ultibiz.spi.tax.TaxRateLookup;
import com.ultibiz.spi.tax.TaxRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * European VAT pack: 20 % standard rate, exclusive pricing by default, and
 * document-level rounding (the tax is rounded once for the invoice, as the
 * EU VAT directive requires).
 */
@Component
public class EuVatTaxPack extends DefaultTaxPack {

    private static final Set<String> EU_COUNTRIES = Set.of(
            "AT", "BE", "BG", "CY", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GR", "HR",
            "HU", "IE", "IT", "LT", "LU", "LV", "MT", "NL", "PL", "PT", "RO", "SE", "SI", "SK");

    public EuVatTaxPack(TaxRateLookup lookup) {
        super(lookup);
    }

    @Override
    public String countryCode() {
        return "EU";
    }

    @Override
    public String name() {
        return "European VAT (20% standard)";
    }

    public static boolean isEuCountry(String countryCode) {
        return countryCode != null && EU_COUNTRIES.contains(countryCode.toUpperCase());
    }

    @Override
    protected Optional<TaxRule> resolveRule(TaxPackContext context, String categoryCode) {
        if (categoryCode == null) {
            return Optional.empty();
        }
        String code = categoryCode.toUpperCase();
        if ("VAT_EXEMPT".equals(code) || "PPN_EXEMPT".equals(code) || "PPN_0".equals(code)) {
            return Optional.empty();
        }
        if ("VAT_STANDARD".equals(code) || "VAT_REDUCED".equals(code)) {
            return Optional.of(countryRule(code, context.countryCode(), "VAT 20% standard",
                    new BigDecimal("0.200000"), new LocalDateHolder(context.onDate())));
        }
        return super.resolveRule(context, categoryCode);
    }

    @Override
    public List<TaxLine> compute(List<TaxableAmount> taxableLines, TaxPackContext context) {
        RoundingMode mode = context.roundingMode() == null ? RoundingMode.HALF_UP : context.roundingMode();
        int scale = context.scale() <= 0 ? 2 : context.scale();
        TaxPackContext euContext = new TaxPackContext(context.organizationId(), context.countryCode(),
                context.currency(), context.onDate(), context.taxInclusive(), mode,
                TaxPackContext.RoundingLevel.DOCUMENT, scale);
        return super.compute(taxableLines, euContext);
    }
}
