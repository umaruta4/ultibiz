package com.ultibiz.core.exchange.rest;

import com.ultibiz.core.exchange.CanonicalDocument;
import com.ultibiz.core.exchange.CanonicalJson;
import com.ultibiz.core.exchange.ExchangeAdapter;
import com.ultibiz.core.exchange.ExchangeException;
import com.ultibiz.core.exchange.ValidationIssue;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The REST/JSON adapter: the payload is the canonical JSON document itself.
 * Validation checks the structural business rules every protocol shares.
 */
@Component
public class RestJsonAdapter implements ExchangeAdapter {

    @Override
    public String protocol() {
        return "REST_JSON";
    }

    @Override
    public CanonicalDocument parse(String payload) throws ExchangeException {
        return CanonicalJson.fromJson(payload);
    }

    @Override
    public List<ValidationIssue> validate(CanonicalDocument document) {
        List<ValidationIssue> issues = new ArrayList<>(StructuralRules.check(document));
        return issues;
    }

    @Override
    public String write(CanonicalDocument document) throws ExchangeException {
        return CanonicalJson.toJson(document);
    }
}
