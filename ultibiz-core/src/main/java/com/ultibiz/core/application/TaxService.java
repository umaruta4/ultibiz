package com.ultibiz.core.application;

import com.ultibiz.core.infrastructure.TaxRuleRepository;
import com.ultibiz.spi.tax.TaxLine;
import com.ultibiz.spi.tax.TaxQuery;
import com.ultibiz.spi.tax.TaxRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Computes tax lines for a document and snapshots the rule that was applied,
 * so a later rate change never rewrites a document that was already issued.
 */
@Service
public class TaxService {

    private final TaxRuleRepository rules;

    public TaxService(TaxRuleRepository rules) {
        this.rules = rules;
    }

    public List<TaxLine> compute(long organizationId, String countryCode,
                                 List<TaxableLine> lines, LocalDate onDate, boolean sale) {
        List<TaxLine> result = new ArrayList<>();
        if (lines == null) {
            return result;
        }
        for (TaxableLine line : lines) {
            BigDecimal base = line.baseAmount() == null ? BigDecimal.ZERO : line.baseAmount();
            Optional<TaxRule> rule = rules.resolve(organizationId, countryCode, line.categoryCode(), onDate);
            if (rule.isEmpty()) {
                result.add(new TaxLine(line.categoryCode(), countryCode, "Non-taxable",
                        BigDecimal.ZERO, base, BigDecimal.ZERO, null));
                continue;
            }
            TaxRule taxRule = rule.get();
            BigDecimal tax = base.multiply(taxRule.rate()).setScale(2, RoundingMode.HALF_UP);
            result.add(new TaxLine(line.categoryCode(), countryCode, taxRule.name(),
                    taxRule.rate(), base, tax, taxRule));
        }
        return result;
    }

    /** Persists the rule snapshot of every computed line. */
    public void snapshot(long organizationId, List<TaxLine> lines, String countryCode) {
        if (lines == null) {
            return;
        }
        for (TaxLine line : lines) {
            if (line.rule() == null) {
                continue;
            }
            rules.snapshot(organizationId, line.rule(), line.categoryCode(), countryCode);
        }
    }
}
