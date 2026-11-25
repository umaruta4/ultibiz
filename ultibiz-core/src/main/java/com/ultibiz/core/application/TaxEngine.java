package com.ultibiz.core.application;

import com.ultibiz.core.tax.EuVatTaxPack;
import com.ultibiz.spi.tax.TaxLine;
import com.ultibiz.spi.tax.TaxPack;
import com.ultibiz.spi.tax.TaxPackContext;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Picks the country tax pack and computes the tax lines.
 */
@Service
public class TaxEngine {

    private final List<TaxPack> packs;

    public TaxEngine(List<TaxPack> packs) {
        this.packs = packs;
    }

    /** The pack that serves a country (exact match first, then the generic one). */
    public TaxPack packFor(String countryCode) {
        if (countryCode != null && !countryCode.isBlank()) {
            String code = countryCode.toUpperCase();
            for (TaxPack pack : packs) {
                if (pack.countryCode().equals(code)) {
                    return pack;
                }
            }
            if (EuVatTaxPack.isEuCountry(code)) {
                for (TaxPack pack : packs) {
                    if (pack instanceof EuVatTaxPack) {
                        return pack;
                    }
                }
            }
        }
        for (TaxPack pack : packs) {
            if (pack.countryCode().equals("*")) {
                return pack;
            }
        }
        return packs.isEmpty() ? null : packs.get(0);
    }

    public List<TaxLine> compute(long organizationId, String countryCode,
                                 List<TaxPack.TaxableAmount> lines, TaxPackContext context) {
        TaxPack pack = packFor(countryCode);
        if (pack == null) {
            return List.of();
        }
        TaxPackContext scoped = new TaxPackContext(organizationId, context.countryCode(),
                context.currency(), context.onDate(), context.taxInclusive(), context.roundingMode(),
                context.roundingLevel(), context.scale());
        return pack.compute(lines, scoped);
    }
}
