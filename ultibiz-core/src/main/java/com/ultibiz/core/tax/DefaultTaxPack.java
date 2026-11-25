package com.ultibiz.core.tax;

import com.ultibiz.spi.tax.TaxLine;
import com.ultibiz.spi.tax.TaxPack;
import com.ultibiz.spi.tax.TaxPackContext;
import com.ultibiz.spi.tax.TaxRateLookup;
import com.ultibiz.spi.tax.TaxRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Generic country pack: the rates come from the effective-dated rules in the
 * database, the rounding is the one the caller asks for. Country packs
 * (Indonesia, EU) specialise this behaviour.
 */
@Component
public class DefaultTaxPack implements TaxPack {

    protected final TaxRateLookup lookup;

    public DefaultTaxPack(TaxRateLookup lookup) {
        this.lookup = lookup;
    }

    @Override
    public String countryCode() {
        return "*";
    }

    @Override
    public String name() {
        return "Database tax rules";
    }

    @Override
    public List<TaxLine> compute(List<TaxableAmount> taxableLines, TaxPackContext context) {
        List<TaxLine> result = new ArrayList<>();
        if (taxableLines == null) {
            return result;
        }
        RoundingMode mode = context.roundingMode() == null ? RoundingMode.HALF_UP : context.roundingMode();
        int scale = context.scale() <= 0 ? 2 : context.scale();
        for (TaxableAmount amount : taxableLines) {
            if (amount == null || amount.categoryCode() == null) {
                continue;
            }
            Optional<TaxRule> rule = resolveRule(context, amount.categoryCode());
            if (rule.isEmpty()) {
                continue;  // non-taxable category
            }
            TaxRule taxRule = rule.get();
            result.add(TaxMath.line(amount.categoryCode(), context.countryCode(), taxRule.name(),
                    taxRule.rate(), amount, context.taxInclusive(), mode, scale, taxRule));
        }
        if (context.roundingLevel() == TaxPackContext.RoundingLevel.DOCUMENT) {
            result = TaxMath.applyDocumentRounding(result, mode, scale);
        }
        return result;
    }

    /**
     * The rule for a category, empty when the category is not taxed.
     * Subclasses override this to add country-specific categories.
     */
    protected Optional<TaxRule> resolveRule(TaxPackContext context, String categoryCode) {
        return lookup.rule(context.organizationId(), categoryCode, context.onDate());
    }

    /** Builds a rule for a built-in country rate. */
    protected static TaxRule countryRule(String categoryCode, String countryCode, String name,
                                          BigDecimal rate, LocalDateHolder from) {
        return new TaxRule(null, categoryCode, countryCode, name, rate, from.date(), null, 100);
    }

    /** Tiny holder so the helper stays readable. */
    public record LocalDateHolder(java.time.LocalDate date) {
    }
}
