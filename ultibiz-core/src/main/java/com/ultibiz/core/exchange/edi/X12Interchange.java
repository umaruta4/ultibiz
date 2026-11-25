package com.ultibiz.core.exchange.edi;

import com.ultibiz.core.exchange.ExchangeException;
import java.util.ArrayList;
import java.util.List;

/**
 * A hand-written X12 interchange parser: the fixed-width ISA envelope, the
 * GS..GE groups and the ST..SE transaction sets. Segments are exposed as tag +
 * elements (the tag is not part of the element list).
 */
public final class X12Interchange {

    /** One transaction set (ST..SE): the code (850, 810, ...) and its segments. */
    public record Transaction(String transactionSetCode, List<List<String>> segments) {
    }

    private final String senderId;
    private final String receiverId;
    private final String controlNumber;
    private final List<Transaction> transactions;

    private X12Interchange(String senderId, String receiverId, String controlNumber,
                           List<Transaction> transactions) {
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.controlNumber = controlNumber;
        this.transactions = transactions;
    }

    public String senderId() {
        return senderId;
    }

    public String receiverId() {
        return receiverId;
    }

    public String controlNumber() {
        return controlNumber;
    }

    public List<Transaction> transactions() {
        return transactions;
    }

    public static X12Interchange parse(String raw) {
        String content = raw.replace("\r", "");
        if (content.length() < 106 || !content.startsWith("ISA")) {
            throw new ExchangeException("Not an X12 interchange: the ISA envelope is missing");
        }
        char elementSeparator = content.charAt(3);
        char segmentTerminator = content.charAt(105);

        // the ISA segment itself
        String isa = content.substring(0, 105);
        String[] isaFields = split(isa, elementSeparator);
        if (isaFields.length < 17) {
            throw new ExchangeException("The ISA envelope has " + isaFields.length
                    + " elements, 17 expected");
        }
        String sender = isaFields[6].trim();
        String receiver = isaFields[8].trim();   // ISA08 (ISA07 is the qualifier)
        String control = isaFields[13];

        List<Transaction> transactions = new ArrayList<>();
        String rest = content.substring(106);
        String[] segments = split(rest, segmentTerminator);
        String groupControl = null;
        String setControl = null;
        List<List<String>> current = null;
        String currentCode = null;
        boolean ieaSeen = false;

        for (String segment : segments) {
            if (segment.isBlank()) {
                continue;
            }
            String[] elements = split(segment.strip(), elementSeparator);
            String tag = elements[0];
            switch (tag) {
                case "GS" -> groupControl = elements[6];
                case "ST" -> {
                    setControl = elements[2];
                    current = new ArrayList<>();
                    current.add(listOf(elements));
                    current = current;
                    transactions.add(new Transaction(elements[1], current));
                }
                case "SE" -> {
                    if (setControl != null && !elements[2].equals(setControl)) {
                        throw new ExchangeException("SE02 " + elements[2]
                                + " does not match ST02 " + setControl);
                    }
                    setControl = null;
                }
                case "GE" -> {
                    if (groupControl != null && !elements[2].equals(groupControl)) {
                        throw new ExchangeException("GE02 " + elements[2]
                                + " does not match GS06 " + groupControl);
                    }
                    groupControl = null;
                }
                case "IEA" -> {
                    ieaSeen = true;
                    if (!elements[2].equals(control)) {
                        throw new ExchangeException("IEA02 " + elements[2]
                                + " does not match ISA13 " + control);
                    }
                }
                default -> {
                    if (current != null) {
                        current.add(listOf(elements));
                    }
                }
            }
        }
        if (!ieaSeen) {
            throw new ExchangeException("The IEA trailer is missing");
        }
        return new X12Interchange(sender, receiver, control, transactions);
    }

    /** A stored segment keeps its tag as element 0. */
    private static List<String> listOf(String[] elements) {
        List<String> out = new ArrayList<>();
        for (String element : elements) {
            out.add(element);
        }
        return out;
    }

    static String[] split(String text, char separator) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == separator) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out.toArray(new String[0]);
    }
}
