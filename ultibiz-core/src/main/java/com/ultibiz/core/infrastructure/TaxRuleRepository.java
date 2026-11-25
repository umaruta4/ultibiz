package com.ultibiz.core.infrastructure;

import com.ultibiz.spi.tax.TaxRule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/**
 * Effective-dated tax rules. The lookup returns the rule with the highest
 * priority that is valid on the requested date; a later rate change never
 * rewrites documents that were computed earlier.
 */
@Repository
public class TaxRuleRepository {

    private final DSLContext dsl;

    public TaxRuleRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<TaxRule> resolve(long organizationId, String countryCode,
                                     String categoryCode, LocalDate onDate) {
        String sql = """
                SELECT r.id, c.code AS category_code, r.country_code, r.name, r.rate,
                       r.effective_from, r.effective_to, r.priority
                FROM tax_rule r
                JOIN tax_category c ON c.id = r.tax_category_id
                WHERE r.organization_id = ?
                  AND r.country_code = ?
                  AND c.code = ?
                  AND r.is_active = 1
                  AND c.is_active = 1
                  AND r.effective_from <= ?
                  AND (r.effective_to IS NULL OR r.effective_to >= ?)
                ORDER BY r.priority ASC, r.effective_from DESC, r.id DESC
                LIMIT 1
                """;
        Record rec = dsl.fetchOne(sql, organizationId, countryCode, categoryCode, onDate, onDate);
        return rec == null ? Optional.empty() : Optional.of(map(rec));
    }

    /** The code of a tax category (used to pick the tax pack rate). */
    public java.util.Optional<String> categoryCode(long categoryId) {
        Object code = dsl.fetchValue("SELECT code FROM tax_category WHERE id = ?", categoryId);
        return code == null ? java.util.Optional.empty() : java.util.Optional.of(String.valueOf(code));
    }

    public List<TaxRule> listRules(long organizationId, String countryCode) {
        return dsl.fetch("""
                SELECT r.id, c.code AS category_code, r.country_code, r.name, r.rate,
                       r.effective_from, r.effective_to, r.priority
                FROM tax_rule r
                JOIN tax_category c ON c.id = r.tax_category_id
                WHERE r.organization_id = ? AND r.country_code = ?
                ORDER BY c.code, r.effective_from DESC
                """, organizationId, countryCode).map(TaxRuleRepository::map);
    }

    public long snapshot(long organizationId, TaxRule rule, String categoryCode, String countryCode) {
        dsl.execute("""
                INSERT INTO tax_rule_snapshot (organization_id, tax_rule_id, category_code, country_code,
                    name, rate, effective_from)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                organizationId, rule.id(), categoryCode, countryCode, rule.name(), rule.rate(),
                rule.effectiveFrom());
        Number n = (Number) dsl.fetchValue("SELECT LAST_INSERT_ID()");
        return n == null ? 0L : n.longValue();
    }

    private static TaxRule map(Record r) {
        Object from = r.get("effective_from");
        Object to = r.get("effective_to");
        return new TaxRule(
                RowValues.asNullableLong(r.get("id")),
                RowValues.asString(r.get("category_code")),
                RowValues.asString(r.get("country_code")),
                RowValues.asString(r.get("name")),
                r.get("rate") == null ? BigDecimal.ZERO
                        : new BigDecimal(String.valueOf(r.get("rate"))),
                from instanceof java.sql.Date d ? d.toLocalDate()
                        : (from == null ? null : LocalDate.parse(String.valueOf(from))),
                to instanceof java.sql.Date d ? d.toLocalDate()
                        : (to == null ? null : LocalDate.parse(String.valueOf(to))),
                RowValues.asInt(r.get("priority")));
    }
}
