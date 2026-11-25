package com.ultibiz.core.exchange.edi;

import com.ultibiz.core.exchange.ExchangeException;
import java.util.ArrayList;
import java.util.List;

/**
 * A hand-written EDIFACT interchange parser: optional UNA service string,
 * UNB envelope, UNH..UNT messages and the UNZ trailer. A segment is a list of
 * elements (tag at index 0); an element is a list of components.
 */
public final class EdifactInterchange {

    /** One message (UNH..UNT): the type (ORDERS, INVOIC, ...) and its segments. */
    public record Message(String messageType, List<List<List<String>>> segments) {
    }

    private final String senderId;
    private final String receiverId;
    private final String controlReference;
    private final List<Message> messages;

    private EdifactInterchange(String senderId, String receiverId, String controlReference,
                               List<Message> messages) {
        this.senderId = senderId;
        this.receiverId = receiverId;
        this.controlReference = controlReference;
        this.messages = messages;
    }

    public String senderId() {
        return senderId;
    }

    public String receiverId() {
        return receiverId;
    }

    public String controlReference() {
        return controlReference;
    }

    public List<Message> messages() {
        return messages;
    }

    public static EdifactInterchange parse(String raw) {
        String content = raw.replace("\r", "");
        char component = ':';
        char element = '+';
        char release = '?';
        char terminator = '\'';
        if (content.startsWith("UNA")) {
            if (content.length() < 9) {
                throw new ExchangeException("The UNA service string is truncated");
            }
            component = content.charAt(3);
            element = content.charAt(4);
            release = content.charAt(6);
            terminator = content.charAt(8);
            content = content.substring(9);
        }
        List<String> segmentTexts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean released = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (released) {
                current.append(c);
                released = false;
            } else if (c == release) {
                released = true;
            } else if (c == terminator) {
                segmentTexts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }

        String sender = null, receiver = null, control = null;
        List<Message> messages = new ArrayList<>();
        List<List<List<String>>> currentMessage = null;
        int declaredCount = -1;
        for (String segmentText : segmentTexts) {
            String tag = segmentText.isBlank() ? "" : segmentText.strip();
            if (tag.isEmpty()) {
                continue;
            }
            List<List<String>> elements = splitSegment(tag, element, component);
            String name = elements.get(0).get(0);
            switch (name) {
                case "UNB" -> {
                    sender = elements.get(2).get(0);
                    receiver = elements.get(3).get(0);
                    control = elements.get(5).get(0);
                }
                case "UNH" -> {
                    currentMessage = new ArrayList<>();
                    currentMessage.add(elements);
                    messages.add(new Message(elements.get(2).get(0), currentMessage));
                }
                case "UNT" -> currentMessage = currentMessage;
                case "UNZ" -> declaredCount = Integer.parseInt(elements.get(1).get(0));
                default -> {
                    if (currentMessage != null) {
                        currentMessage.add(elements);
                    }
                }
            }
        }
        if (sender == null) {
            throw new ExchangeException("The UNB envelope is missing");
        }
        if (declaredCount >= 0 && declaredCount != messages.size()) {
            throw new ExchangeException("UNZ declares " + declaredCount + " messages but the "
                    + "interchange carries " + messages.size());
        }
        return new EdifactInterchange(sender, receiver, control, messages);
    }

    /** Splits a segment into elements and components (tag included at [0][0]). */
    private static List<List<String>> splitSegment(String segment, char element, char component) {
        List<List<String>> elements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        List<String> components = new ArrayList<>();
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c == element) {
                components.add(current.toString());
                current.setLength(0);
                elements.add(components);
                components = new ArrayList<>();
            } else if (c == component) {
                components.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        components.add(current.toString());
        elements.add(components);
        return elements;
    }
}
