package com.ultibiz.core.infrastructure;

import com.ultibiz.spi.tax.TaxQuery;
import com.ultibiz.spi.tax.TaxRule;
import com.ultibiz.spi.tax.TaxRuleProvider;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The default tax rule provider: effective-dated rules kept in the database.
 * A plugin may contribute a different provider with higher precedence.
 */
@Component
public class DatabaseTaxRuleProvider implements TaxRuleProvider {

    private final TaxRuleRepository rules;

    public DatabaseTaxRuleProvider(TaxRuleRepository rules) {
        this.rules = rules;
    }

    @Override
    public Optional<TaxRule> resolve(TaxQuery query) {
        if (query == null || query.countryCode() == null || query.categoryCode() == null) {
            return Optional.empty();
        }
        LocalDate onDate = query.onDate() == null ? LocalDate.now() : query.onDate();
        return rules.resolve(0L, query.countryCode(), query.categoryCode(), onDate);
    }

    @Override
    public String providerId() {
        return "database";
    }

    /** Resolve within a concrete organization (the SPI-level lookup is org-less). */
    public Optional<TaxRule> resolve(long organizationId, TaxQuery query) {
        if (query == null || query.countryCode() == null || query.categoryCode() == null) {
            return Optional.empty();
        }
        LocalDate onDate = query.onDate() == null ? LocalDate.now() : query.onDate();
        return rules.resolve(organizationId, query.countryCode(), query.categoryCode(), onDate);
    }
}
