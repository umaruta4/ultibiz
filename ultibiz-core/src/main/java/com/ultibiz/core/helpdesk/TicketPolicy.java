package com.ultibiz.core.helpdesk;

/**
 * The ticket rules: the status moves forward (with a reopen), a reply needs a
 * body, and the resolution needs a public reply so the customer knows.
 */
public final class TicketPolicy {

    private TicketPolicy() {
    }

    /** The status flow of a ticket. */
    public static void checkTransition(String from, String to) {
        boolean allowed = switch (from + ">" + to) {
            case "NEW>TRIAGED", "TRIAGED>ASSIGNED", "ASSIGNED>IN_PROGRESS",
                    "ASSIGNED>RESOLVED", "ASSIGNED>WAITING",
                    "IN_PROGRESS>WAITING", "WAITING>IN_PROGRESS", "IN_PROGRESS>RESOLVED",
                    "WAITING>RESOLVED", "RESOLVED>CLOSED", "RESOLVED>IN_PROGRESS" -> true;
            default -> false;
        };
        if (!allowed) {
            throw new HelpdeskException("A ticket cannot move from " + from + " to " + to);
        }
    }

    /** A reply needs a body. */
    public static void checkReply(String body) {
        if (body == null || body.isBlank()) {
            throw new HelpdeskException("A reply needs a body");
        }
    }

    /** A ticket needs a subject and a description. */
    public static void checkIntake(String subject, String description) {
        if (subject == null || subject.isBlank()) {
            throw new HelpdeskException("A ticket needs a subject");
        }
        if (description == null || description.isBlank()) {
            throw new HelpdeskException("A ticket needs a description");
        }
    }

    /** The priority of a new ticket from the words of the requester. */
    public static String detectPriority(String subject, String description) {
        String text = ((subject == null ? "" : subject) + " " + (description == null ? ""
                : description)).toLowerCase();
        if (text.contains("server down") || text.contains("outage") || text.contains("data loss")
                || text.contains("security breach")) {
            return "URGENT";
        }
        if (text.contains("urgent") || text.contains("critical") || text.contains("immediately")) {
            return "HIGH";
        }
        if (text.contains("question") || text.contains("how to") || text.contains("request")) {
            return "LOW";
        }
        return "NORMAL";
    }
}
