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

/** The X12 adapter: 850/810 in and out, 997 acknowledgement. */
@Component
public class X12Adapter implements ExchangeAdapter {

    private static final AtomicLong CONTROL = new AtomicLong(1000);

    @Override
    public String protocol() {
        return "X12";
    }

    @Override
    public CanonicalDocument parse(String payload) throws ExchangeException {
        return X12Documents.toCanonical(X12Interchange.parse(payload));
    }

    @Override
    public List<ValidationIssue> validate(CanonicalDocument document) {
        return StructuralRules.check(document);
    }

    @Override
    public String write(CanonicalDocument document) throws ExchangeException {
        boolean order = CanonicalDocument.ORDER.equals(document.type());
        if (!order && !CanonicalDocument.INVOICE.equals(document.type())) {
            throw new ExchangeException("X12 renders only ORDER (850) or INVOICE (810), not "
                    + document.type());
        }
        long control = CONTROL.incrementAndGet();
        String date = String.valueOf(document.issueDate()).replace("-", "");
        String time = "1200";
        String isa = "ISA*00*          *00*          *ZZ*"
                + String.format("%-15s", "ULTIBIZ") + "*ZZ*"
                + String.format("%-15s", document.buyer() != null
                        ? nullToEmpty(document.buyer().partyCode()) : "PARTNER")
                + "*" + date.substring(2) + "*" + time + "*^*00501*"
                + String.format("%09d", control) + "*0*T*>";
        StringBuilder x = new StringBuilder();
        x.append(isa).append("~\n");
        x.append("GS*").append(order ? "PO" : "IN").append("*ULTIBIZ*")
            .append(document.buyer() != null ? nullToEmpty(document.buyer().partyCode()) : "PARTNER")
            .append('*').append(date).append('*').append(time).append('*')
            .append(control).append("*X*005010~\n");
        x.append("ST*").append(order ? "850" : "810").append("*0001~\n");
        if (order) {
            x.append("BEG*00*SA*").append(document.documentNumber()).append("**")
                .append(date).append("~\n");
        } else {
            x.append("BIG*").append(date).append('*').append(document.documentNumber()).append("~\n");
        }
        int lineNo = 0;
        for (ExchangeLine line : document.lines()) {
            lineNo++;
            x.append(order ? "PO1*" : "IT1*").append(lineNo).append('*')
                .append(line.quantity().stripTrailingZeros().toPlainString()).append("*EA*")
                .append(line.unitPrice().stripTrailingZeros().toPlainString()).append("**BP*")
                .append(line.productCode() == null ? "" : line.productCode()).append("~\n");
        }
        x.append("SE*").append(3 + document.lines().size()).append("*0001~\n");
        x.append("GE*1*").append(control).append("~\n");
        x.append("IEA*1*").append(String.format("%09d", control)).append("~\n");
        return x.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
