package com.ultibiz.core.tax;

import com.ultibiz.core.infrastructure.TaxRuleRepository;
import com.ultibiz.spi.tax.TaxRateLookup;
import com.ultibiz.spi.tax.TaxRule;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Rate lookup backed by the effective-dated tax rules in the database. */
@Component
public class DatabaseTaxRateLookup implements TaxRateLookup {

    private final TaxRuleRepository rules;

    public DatabaseTaxRateLookup(TaxRuleRepository rules) {
        this.rules = rules;
    }

    @Override
    public Optional<TaxRule> rule(long organizationId, String categoryCode, LocalDate onDate) {
        if (organizationId <= 0) {
            return Optional.empty();
        }
        return rules.resolve(organizationId, "ID", categoryCode, onDate);
    }
}
