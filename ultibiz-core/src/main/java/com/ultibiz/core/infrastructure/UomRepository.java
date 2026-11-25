package com.ultibiz.core.infrastructure;

import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/** Units of measure (read-only reference data). */
@Repository
public class UomRepository {

    private final DSLContext dsl;

    public UomRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public List<String> codes() {
        return dsl.fetch("SELECT code FROM uom WHERE is_active = 1 ORDER BY code")
                .map(r -> RowValues.asString(r.get("code")));
    }
}
