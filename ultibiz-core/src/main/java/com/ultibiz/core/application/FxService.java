package com.ultibiz.core.application;

import com.ultibiz.core.domain.FxRate;
import com.ultibiz.core.infrastructure.FxRateRepository;
import com.ultibiz.spi.fx.FxRateProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Multi-currency support: the functional currency is the organization's base
 * currency; a transaction in another currency carries an exchange rate that
 * converts it into the functional currency.
 */
@Service
public class FxService {

    private final FxRateRepository rates;
    private final List<FxRateProvider> providers;

    public FxService(FxRateRepository rates, List<FxRateProvider> providers) {
        this.rates = rates;
        this.providers = providers;
    }

    /** The rate to convert {@code quote} into {@code base}, or 1 for the same currency. */
    public BigDecimal rate(long organizationId, String base, String quote, LocalDate onDate) {
        if (base == null || quote == null || base.equalsIgnoreCase(quote)) {
            return BigDecimal.ONE;
        }
        FxRate stored = rates.find(organizationId, base, quote, onDate);
        if (stored != null) {
            return stored.rate();
        }
        for (FxRateProvider provider : providers) {
            var found = provider.rate(organizationId, base, quote, onDate);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new AccountingException("No exchange rate for " + base + "/" + quote + " on " + onDate);
    }

    /** Converts an amount from the transaction currency into the functional one. */
    public BigDecimal convert(BigDecimal amount, BigDecimal rate) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
    }

    /** Stores a rate (manual maintenance, or a provider feed). */
    public void storeRate(long organizationId, String base, String quote, BigDecimal rate,
                          LocalDate effectiveDate, String source) {
        rates.insert(organizationId, base, quote, rate, effectiveDate, source);
    }
}
