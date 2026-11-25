package com.ultibiz.core.exchange.edi;

import com.ultibiz.core.exchange.CanonicalDocument;
import com.ultibiz.core.exchange.ExchangeException;
import com.ultibiz.core.exchange.ExchangeLine;
import com.ultibiz.core.exchange.ExchangeParty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Maps X12 transaction sets (850 purchase order, 810 invoice) to the canonical model. */
public final class X12Documents {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private X12Documents() {
    }

    /** Maps the first transaction of an interchange to the canonical model. */
    public static CanonicalDocument toCanonical(X12Interchange interchange) {
        if (interchange.transactions().isEmpty()) {
            throw new ExchangeException("The interchange carries no transaction set");
        }
        X12Interchange.Transaction tx = interchange.transactions().get(0);
        String number = null;
        LocalDate date = null;
        List<ExchangeLine> lines = new ArrayList<>();
        String type = switch (tx.transactionSetCode()) {
            case "850" -> CanonicalDocument.ORDER;
            case "810" -> CanonicalDocument.INVOICE;
            default -> throw new ExchangeException(
                    "Unsupported X12 transaction set: " + tx.transactionSetCode());
        };
        for (List<String> segment : tx.segments()) {
            String tag = segment.get(0);
            if ("BEG".equals(tag)) {
                number = segment.get(3);
                date = LocalDate.parse(segment.get(5), DATE);
            } else if ("BIG".equals(tag)) {
                date = LocalDate.parse(segment.get(1), DATE);
                number = segment.get(2);
            } else if ("PO1".equals(tag) || "IT1".equals(tag)) {
                String productCode = segment.size() > 7 ? segment.get(7) : null;
                lines.add(new ExchangeLine(productCode, productCode,
                        new BigDecimal(segment.get(2)), new BigDecimal(segment.get(4)),
                        "EXEMPT", BigDecimal.ZERO));
            }
        }
        if (number == null) {
            throw new ExchangeException("No BEG/BIG header segment found");
        }
        ExchangeParty seller = new ExchangeParty(interchange.senderId(), interchange.senderId(),
                null, null, null, null, null, null);
        ExchangeParty buyer = new ExchangeParty(interchange.receiverId(), interchange.receiverId(),
                null, null, null, null, null, null);
        return new CanonicalDocument(type, number, date, null, "IDR", seller, buyer, lines,
                null, List.of());
    }

    /**
     * Generates the 997 functional acknowledgement for a received interchange.
     * Deterministic: every envelope field comes from the parameters.
     */
    public static String generate997(String ourId, String theirId, String date, String time,
                                     String controlNumber, String groupControl,
                                     String functionalGroup, int transactionCount,
                                     boolean accepted) {
        String isa = "ISA*00*          *00*          *ZZ*" + pad(ourId, 15)
                + "*ZZ*" + pad(theirId, 15) + "*" + date.substring(2) + "*" + time
                + "*^*00501*" + padLeft(controlNumber, 9) + "*0*T*>";
        StringBuilder x = new StringBuilder();
        x.append(isa).append("~\n");
        x.append("GS*FA*").append(ourId).append('*').append(theirId).append('*')
            .append(date).append('*').append(time).append('*').append(groupControl)
            .append("*X*005010~\n");
        x.append("ST*997*0001~\n");
        x.append("AK1*").append(functionalGroup).append('*').append(groupControl).append("~\n");
        x.append("AK9*").append(accepted ? 'A' : 'R').append('*').append(transactionCount)
            .append('*').append(transactionCount).append('*')
            .append(accepted ? transactionCount : 0).append("~\n");
        x.append("SE*4*0001~\n");
        x.append("GE*1*").append(groupControl).append("~\n");
        x.append("IEA*1*").append(padLeft(controlNumber, 9)).append("~\n");
        return x.toString();
    }

    private static String pad(String value, int width) {
        return String.format("%-" + width + "s", value == null ? "" : value).substring(0, width);
    }

    private static String padLeft(String value, int width) {
        String v = value == null ? "" : value;
        return v.length() >= width ? v : "0".repeat(width - v.length()) + v;
    }
}
