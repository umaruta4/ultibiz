package com.ultibiz.core.infrastructure;

import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class OrganizationRepository {

    private final DSLContext dsl;

    public OrganizationRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long count() {
        Number n = (Number) dsl.fetchValue("SELECT COUNT(*) FROM organization WHERE deleted_at IS NULL");
        return n.longValue();
    }
}
