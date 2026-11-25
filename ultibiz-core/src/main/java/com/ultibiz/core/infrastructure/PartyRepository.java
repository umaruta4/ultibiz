package com.ultibiz.core.infrastructure;

import com.ultibiz.core.application.PartyDraft;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.core.application.SiteScopedSql;
import com.ultibiz.core.domain.Party;
import com.ultibiz.core.domain.PartyAddress;
import com.ultibiz.core.domain.PartyContact;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Repository;

/** Party master (customers, suppliers) plus their addresses and contacts. */
@Repository
public class PartyRepository {

    private static final String COLUMNS = """
            id, organization_id, site_id, code, name, party_type, tax_id, country_code,
            email, phone, credit_limit, payment_term_days, currency, is_active, row_version
            """;

    private final DSLContext dsl;

    public PartyRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<Party> list(SiteScope scope, String search, String partyType,
                            boolean activeOnly, int limit, int offset) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(scope, search, partyType, activeOnly, params);
        String sql = "SELECT " + COLUMNS + " FROM party WHERE " + where + " ORDER BY code LIMIT ? OFFSET ?";
        params.add(limit);
        params.add(offset);
        return dsl.fetch(sql, params.toArray()).map(PartyRepository::map);
    }

    public long count(SiteScope scope, String search, String partyType, boolean activeOnly) {
        List<Object> params = new ArrayList<>();
        String where = buildWhere(scope, search, partyType, activeOnly, params);
        Number n = (Number) dsl.fetchValue("SELECT COUNT(*) FROM party WHERE " + where, params.toArray());
        return n == null ? 0L : n.longValue();
    }

    public Optional<Party> findById(SiteScope scope, long id) {
        List<Object> params = new ArrayList<>();
        String where = SiteScopedSql.masterDataClause("organization_id", "site_id", scope, params);
        params.add(id);
        Record r = dsl.fetchOne("SELECT " + COLUMNS + " FROM party WHERE " + where
                + " AND id = ? AND deleted_at IS NULL", params.toArray());
        return r == null ? Optional.empty() : Optional.of(map(r));
    }

    public Optional<Party> findByCode(SiteScope scope, String code) {
        List<Object> params = new ArrayList<>();
        String where = SiteScopedSql.masterDataClause("organization_id", "site_id", scope, params);
        params.add(code);
        Record r = dsl.fetchOne("SELECT " + COLUMNS + " FROM party WHERE " + where
                + " AND code = ? AND deleted_at IS NULL", params.toArray());
        return r == null ? Optional.empty() : Optional.of(map(r));
    }

    public long insert(SiteScope scope, PartyDraft draft) {
        dsl.execute("""
                INSERT INTO party (organization_id, site_id, code, name, party_type, tax_id, country_code,
                    email, phone, credit_limit, payment_term_days, currency, is_active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                scope.organizationId(), draft.siteId(), draft.code(), draft.name(), draft.partyType(),
                draft.taxId(), draft.countryCode(), draft.email(), draft.phone(), draft.creditLimit(),
                draft.paymentTermDays(), draft.currency(), RowValues.boolFlag(draft.active()));
        Number n = (Number) dsl.fetchValue("SELECT LAST_INSERT_ID()");
        return n == null ? 0L : n.longValue();
    }

    public boolean update(SiteScope scope, long id, PartyDraft draft, long expectedVersion) {
        List<Object> params = new ArrayList<>();
        String where = SiteScopedSql.masterDataClause("organization_id", "site_id", scope, params);
        params.add(draft.siteId());
        params.add(draft.name());
        params.add(draft.partyType());
        params.add(draft.taxId());
        params.add(draft.countryCode());
        params.add(draft.email());
        params.add(draft.phone());
        params.add(draft.creditLimit());
        params.add(draft.paymentTermDays());
        params.add(draft.currency());
        params.add(RowValues.boolFlag(draft.active()));
        params.add(id);
        params.add(expectedVersion);
        return dsl.execute("""
                UPDATE party SET site_id = ?, name = ?, party_type = ?, tax_id = ?, country_code = ?,
                    email = ?, phone = ?, credit_limit = ?, payment_term_days = ?, currency = ?,
                    is_active = ?, row_version = row_version + 1
                WHERE """ + where + " AND id = ? AND deleted_at IS NULL AND row_version = ?",
                params.toArray()) == 1;
    }

    public boolean softDelete(SiteScope scope, long id) {
        List<Object> params = new ArrayList<>();
        String where = SiteScopedSql.masterDataClause("organization_id", "site_id", scope, params);
        params.add(id);
        return dsl.execute("UPDATE party SET deleted_at = CURRENT_TIMESTAMP(6), row_version = row_version + 1 "
                + "WHERE " + where + " AND id = ? AND deleted_at IS NULL", params.toArray()) == 1;
    }

    public List<PartyAddress> addresses(long partyId) {
        return dsl.fetch("""
                SELECT id, party_id, address_type, address_line, city, postal_code, country_code, is_primary
                FROM party_address WHERE party_id = ? ORDER BY is_primary DESC, id
                """, partyId).map(r -> new PartyAddress(
                RowValues.asLong(r.get("id")),
                RowValues.asLong(r.get("party_id")),
                RowValues.asString(r.get("address_type")),
                RowValues.asString(r.get("address_line")),
                RowValues.asString(r.get("city")),
                RowValues.asString(r.get("postal_code")),
                RowValues.asString(r.get("country_code")),
                RowValues.asBoolean(r.get("is_primary"))));
    }

    public List<PartyContact> contacts(long partyId) {
        return dsl.fetch("""
                SELECT id, party_id, contact_name, contact_role, email, phone, is_primary
                FROM party_contact WHERE party_id = ? ORDER BY is_primary DESC, id
                """, partyId).map(r -> new PartyContact(
                RowValues.asLong(r.get("id")),
                RowValues.asLong(r.get("party_id")),
                RowValues.asString(r.get("contact_name")),
                RowValues.asString(r.get("contact_role")),
                RowValues.asString(r.get("email")),
                RowValues.asString(r.get("phone")),
                RowValues.asBoolean(r.get("is_primary"))));
    }

    private String buildWhere(SiteScope scope, String search, String partyType,
                              boolean activeOnly, List<Object> params) {
        StringBuilder sb = new StringBuilder("deleted_at IS NULL AND ");
        sb.append(SiteScopedSql.masterDataClause("organization_id", "site_id", scope, params));
        if (search != null && !search.isBlank()) {
            sb.append(" AND (code LIKE ? OR name LIKE ? OR tax_id LIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (partyType != null && !partyType.isBlank()) {
            sb.append(" AND party_type = ?");
            params.add(partyType);
        }
        if (activeOnly) {
            sb.append(" AND is_active = 1");
        }
        return sb.toString();
    }

    private static Party map(Record r) {
        return new Party(
                RowValues.asLong(r.get("id")),
                RowValues.asLong(r.get("organization_id")),
                RowValues.asNullableLong(r.get("site_id")),
                RowValues.asString(r.get("code")),
                RowValues.asString(r.get("name")),
                RowValues.asString(r.get("party_type")),
                RowValues.asString(r.get("tax_id")),
                RowValues.asString(r.get("country_code")),
                RowValues.asString(r.get("email")),
                RowValues.asString(r.get("phone")),
                r.get("credit_limit") == null ? null
                        : new java.math.BigDecimal(String.valueOf(r.get("credit_limit"))),
                RowValues.asInt(r.get("payment_term_days")),
                RowValues.asString(r.get("currency")),
                RowValues.asBoolean(r.get("is_active")),
                RowValues.asLong(r.get("row_version")));
    }
}
