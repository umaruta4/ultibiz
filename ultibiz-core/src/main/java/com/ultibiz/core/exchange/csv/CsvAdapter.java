package com.ultibiz.core.exchange.csv;

import com.ultibiz.core.exchange.CanonicalDocument;
import com.ultibiz.core.exchange.ExchangeAdapter;
import com.ultibiz.core.exchange.ExchangeException;
import com.ultibiz.core.exchange.ExchangeLine;
import com.ultibiz.core.exchange.ExchangeParty;
import com.ultibiz.core.exchange.ValidationIssue;
import com.ultibiz.core.exchange.rest.StructuralRules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The CSV flat-file adapter. One row per line; the document header fields are
 * repeated on every row:
 *
 * <pre>type;documentNumber;issueDate;currency;sellerCode;sellerName;buyerCode;buyerName;productCode;description;quantity;unitPrice;taxCode;taxPercent</pre>
 */
@Component
public class CsvAdapter implements ExchangeAdapter {

    @Override
    public String protocol() {
        return "CSV";
    }

    @Override
    public CanonicalDocument parse(String payload) throws ExchangeException {
        String[] rows = payload.strip().split("\\R");
        if (rows.length == 0) {
            throw new ExchangeException("The CSV payload is empty");
        }
        List<ExchangeLine> lines = new ArrayList<>();
        String type = null, number = null, currency = null;
        LocalDate issueDate = null;
        ExchangeParty seller = null, buyer = null;
        for (int i = 0; i < rows.length; i++) {
            String[] c = rows[i].split(";", -1);
            if (c.length < 14) {
                throw new ExchangeException("CSV row " + (i + 1) + " has " + c.length
                        + " columns, 14 expected");
            }
            if (i == 0) {
                type = c[0].trim();
                number = c[1].trim();
                issueDate = LocalDate.parse(c[2].trim());
                currency = c[3].trim();
                seller = new ExchangeParty(c[4].trim(), c[5].trim(), null, null, null, null, null, null);
                buyer = new ExchangeParty(c[6].trim(), c[7].trim(), null, null, null, null, null, null);
            }
            lines.add(new ExchangeLine(c[8].trim(), c[9].trim(),
                    new BigDecimal(c[10].trim()), new BigDecimal(c[11].trim()),
                    c[12].trim(), new BigDecimal(c[13].trim())));
        }
        return new CanonicalDocument(type, number, issueDate, null, currency, seller, buyer,
                lines, null, List.of());
    }

    @Override
    public List<ValidationIssue> validate(CanonicalDocument document) {
        return StructuralRules.check(document);
    }

    @Override
    public String write(CanonicalDocument document) throws ExchangeException {
        StringBuilder out = new StringBuilder();
        for (ExchangeLine line : document.lines()) {
            out.append(document.type()).append(';')
                .append(document.documentNumber()).append(';')
                .append(document.issueDate()).append(';')
                .append(document.currency()).append(';')
                .append(document.seller() != null && document.seller().partyCode() != null
                        ? document.seller().partyCode() : "").append(';')
                .append(document.seller() != null ? document.seller().name() : "").append(';')
                .append(document.buyer() != null && document.buyer().partyCode() != null
                        ? document.buyer().partyCode() : "").append(';')
                .append(document.buyer() != null ? document.buyer().name() : "").append(';')
                .append(line.productCode()).append(';')
                .append(line.description()).append(';')
                .append(line.quantity()).append(';')
                .append(line.unitPrice()).append(';')
                .append(line.taxCode()).append(';')
                .append(line.taxPercent()).append('\n');
        }
        return out.toString();
    }
}
