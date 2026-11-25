package com.ultibiz.core.tax;

import com.ultibiz.spi.tax.TaxPackContext;
import com.ultibiz.spi.tax.TaxRateLookup;
import com.ultibiz.spi.tax.TaxRule;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Indonesia (PPN): 11 % standard, 2 % reduced, exempt categories. Indonesian
 * prices are usually tax-inclusive, and rounding is per line (half-up).
 */
@Component
public class IndonesiaTaxPack extends DefaultTaxPack {

    private static final String STANDARD = "PPN_STANDARD";
    private static final String REDUCED = "PPN_REDUCED";

    public IndonesiaTaxPack(TaxRateLookup lookup) {
        super(lookup);
    }

    @Override
    public String countryCode() {
        return "ID";
    }

    @Override
    public String name() {
        return "Indonesia PPN (11% standard / 2% reduced)";
    }

    @Override
    protected Optional<TaxRule> resolveRule(TaxPackContext context, String categoryCode) {
        if (categoryCode == null) {
            return Optional.empty();
        }
        String code = categoryCode.toUpperCase();
        if ("PPN_EXEMPT".equals(code) || "PPN_0".equals(code)) {
            return Optional.empty();
        }
        if (REDUCED.equals(code)) {
            return Optional.of(countryRule(code, "ID", "VAT 2% reduced",
                    new BigDecimal("0.020000"), new LocalDateHolder(context.onDate())));
        }
        if (STANDARD.equals(code)) {
            return Optional.of(countryRule(code, "ID", "VAT 11% standard",
                    new BigDecimal("0.110000"), new LocalDateHolder(context.onDate())));
        }
        return super.resolveRule(context, categoryCode);
    }
}
