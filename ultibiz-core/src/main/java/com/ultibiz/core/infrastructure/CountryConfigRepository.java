package com.ultibiz.core.infrastructure;

import com.ultibiz.core.domain.OrganizationCountry;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** Per-organization country configuration (currency, locale, fiscal calendar). */
@Repository
public class CountryConfigRepository {

    private final DSLContext dsl;

    public CountryConfigRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<OrganizationCountry> find(long organizationId, String countryCode) {
        Record r = dsl.fetchOne("""
                SELECT organization_id, country_code, is_enabled, currency, locale, timezone,
                       fiscal_year_start_month, fiscal_year_start_day
                FROM organization_country WHERE organization_id = ? AND country_code = ?
                """, organizationId, countryCode);
        return r == null ? Optional.empty() : Optional.of(map(r));
    }

    public List<OrganizationCountry> list(long organizationId) {
        return dsl.fetch("""
                SELECT organization_id, country_code, is_enabled, currency, locale, timezone,
                       fiscal_year_start_month, fiscal_year_start_day
                FROM organization_country WHERE organization_id = ? ORDER BY country_code
                """, organizationId).map(CountryConfigRepository::map);
    }

    private static OrganizationCountry map(Record r) {
        return new OrganizationCountry(
                RowValues.asLong(r.get("organization_id")),
                RowValues.asString(r.get("country_code")),
                RowValues.asBoolean(r.get("is_enabled")),
                RowValues.asString(r.get("currency")),
                RowValues.asString(r.get("locale")),
                RowValues.asString(r.get("timezone")),
                RowValues.asInt(r.get("fiscal_year_start_month")),
                RowValues.asInt(r.get("fiscal_year_start_day")));
    }
}
