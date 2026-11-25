package com.ultibiz.core.exchange.edi;

import com.ultibiz.core.exchange.CanonicalDocument;
import com.ultibiz.core.exchange.ExchangeAdapter;
import com.ultibiz.core.exchange.ExchangeException;
import com.ultibiz.core.exchange.ExchangeLine;
import com.ultibiz.core.exchange.ValidationIssue;
import com.ultibiz.core.exchange.rest.StructuralRules;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** The EDIFACT adapter: ORDERS/INVOIC in and out, CONTRL acknowledgement. */
@Component
public class EdifactAdapter implements ExchangeAdapter {

    private static final AtomicLong CONTROL = new AtomicLong(1000);

    @Override
    public String protocol() {
        return "EDIFACT";
    }

    @Override
    public CanonicalDocument parse(String payload) throws ExchangeException {
        return EdifactDocuments.toCanonical(EdifactInterchange.parse(payload));
    }

    @Override
    public List<ValidationIssue> validate(CanonicalDocument document) {
        return StructuralRules.check(document);
    }

    @Override
    public String write(CanonicalDocument document) throws ExchangeException {
        boolean order = CanonicalDocument.ORDER.equals(document.type());
        if (!order && !CanonicalDocument.INVOICE.equals(document.type())) {
            throw new ExchangeException("EDIFACT renders only ORDER (ORDERS) or INVOICE (INVOIC), not "
                    + document.type());
        }
        long control = CONTROL.incrementAndGet();
        String date = String.valueOf(document.issueDate()).replace("-", "");
        String partner = document.buyer() != null && document.buyer().partyCode() != null
                ? document.buyer().partyCode() : "PARTNER";
        StringBuilder x = new StringBuilder();
        x.append("UNB+UNOC:3+ULTIBIZ+").append(partner).append('+')
            .append(date.substring(2)).append(":1200+").append(control).append("'\n");
        x.append("UNH+1+").append(order ? "ORDERS" : "INVOIC").append(":D:96A:UN'\n");
        x.append("BGM+").append(order ? "220" : "380").append('+')
            .append(document.documentNumber()).append("+9'\n");
        x.append("DTM+137:").append(date).append(":102'\n");
        int lineNo = 0;
        for (ExchangeLine line : document.lines()) {
            lineNo++;
            x.append("LIN+").append(lineNo).append("++")
                .append(line.productCode() == null ? "" : line.productCode()).append(":BP'\n");
            x.append("QTY+21:").append(line.quantity().stripTrailingZeros().toPlainString()).append("'\n");
            x.append("PRI+AAA:").append(line.unitPrice().stripTrailingZeros().toPlainString()).append("'\n");
        }
        x.append("UNT+").append(4 + document.lines().size() * 3).append("+1'\n");
        x.append("UNZ+1+").append(control).append("'\n");
        return x.toString();
    }
}
