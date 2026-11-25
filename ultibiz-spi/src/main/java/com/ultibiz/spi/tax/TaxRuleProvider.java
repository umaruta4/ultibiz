package com.ultibiz.spi.tax;

import java.util.Optional;

/**
 * SPI for country-specific tax rules. The core ships a database-backed
 * implementation; a plugin may contribute its own (e.g. a local tax authority
 * integration) by registering another bean of this type.
 */
public interface TaxRuleProvider {

    /**
     * @return the rule that applies to the query, or empty when the category
     *         is not taxable in that country/period
     */
    Optional<TaxRule> resolve(TaxQuery query);

    /** Identifier of this provider, used in audit trails. */
    String providerId();
}
