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

/** Maps EDIFACT messages (ORDERS, INVOIC) to the canonical model and builds CONTRL acks. */
public final class EdifactDocuments {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private EdifactDocuments() {
    }

    /** Maps the first message of an interchange to the canonical model. */
    public static CanonicalDocument toCanonical(EdifactInterchange interchange) {
        if (interchange.messages().isEmpty()) {
            throw new ExchangeException("The interchange carries no message");
        }
        EdifactInterchange.Message message = interchange.messages().get(0);
        String type = switch (message.messageType()) {
            case "ORDERS" -> CanonicalDocument.ORDER;
            case "INVOIC" -> CanonicalDocument.INVOICE;
            default -> throw new ExchangeException(
                    "Unsupported EDIFACT message: " + message.messageType());
        };
        String number = null;
        LocalDate date = null;
        List<ExchangeLine> lines = new ArrayList<>();
        String pendingProduct = null;
        BigDecimal pendingQty = null;
        for (List<List<String>> segment : message.segments()) {
            String tag = segment.get(0).get(0);
            switch (tag) {
                case "BGM" -> number = segment.get(2).get(0);
                case "DTM" -> {
                    if ("137".equals(segment.get(1).get(0)) && date == null) {
                        date = LocalDate.parse(segment.get(1).get(1), DATE);
                    }
                }
                case "LIN" -> {
                    pendingProduct = segment.size() > 3 ? segment.get(3).get(0) : null;
                    pendingQty = null;
                }
                case "QTY" -> {
                    if (pendingProduct != null) {
                        pendingQty = new BigDecimal(segment.get(1).get(1));
                    }
                }
                case "PRI" -> {
                    if (pendingProduct != null && "AAA".equals(segment.get(1).get(0))) {
                        lines.add(new ExchangeLine(pendingProduct, pendingProduct,
                                pendingQty == null ? BigDecimal.ONE : pendingQty,
                                new BigDecimal(segment.get(1).get(1)),
                                "EXEMPT", BigDecimal.ZERO));
                        pendingProduct = null;
                    }
                }
                default -> {
                    // NAD, UNS, UNT and friends are not mapped into the BDM
                }
            }
        }
        if (number == null) {
            throw new ExchangeException("No BGM segment found");
        }
        ExchangeParty seller = new ExchangeParty(interchange.senderId(), interchange.senderId(),
                null, null, null, null, null, null);
        ExchangeParty buyer = new ExchangeParty(interchange.receiverId(), interchange.receiverId(),
                null, null, null, null, null, null);
        return new CanonicalDocument(type, number, date, null, "IDR", seller, buyer, lines,
                null, List.of());
    }

    /**
     * Generates the CONTRL acknowledgement for a received interchange.
     * Deterministic: every envelope field comes from the parameters.
     */
    public static String generateContrl(String ourId, String theirId, String date, String time,
                                        String controlReference, String receivedControl,
                                        boolean accepted) {
        StringBuilder x = new StringBuilder();
        x.append("UNB+UNOC:3+").append(ourId).append('+').append(theirId).append('+')
            .append(date.substring(2)).append(':').append(time).append('+')
            .append(controlReference).append("'\n");
        x.append("UNH+1+CONTRL:D:96A:UN'\n");
        x.append("UCI+").append(receivedControl).append('+').append(theirId).append('+')
            .append(ourId).append('+').append(accepted ? '7' : '4').append("'\n");
        x.append("UNT+3+1'\n");
        x.append("UNZ+1+").append(controlReference).append("'\n");
        return x.toString();
    }
}
