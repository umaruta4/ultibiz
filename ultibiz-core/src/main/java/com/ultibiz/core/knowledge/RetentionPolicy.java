package com.ultibiz.core.knowledge;

import java.time.LocalDate;

/** The retention rules: how old a document may get before the policy acts. */
public final class RetentionPolicy {

    private RetentionPolicy() {
    }

    /** What the policy does with a document. */
    public enum Action {
        ARCHIVE,
        DELETE
    }

    /** Is the document older than the retention period? */
    public static boolean expired(LocalDate lastChange, int retainMonths, LocalDate today) {
        if (retainMonths <= 0) {
            return true;      // a policy without a period expires everything
        }
        if (lastChange == null || today == null) {
            return false;
        }
        // the period months part alone is not enough (1 year 10 months = 10 months)
        return !lastChange.plusMonths(retainMonths).isAfter(today);
    }

    /** The action of a policy. */
    public static Action action(String action) {
        try {
            return Action.valueOf(action == null ? "ARCHIVE" : action);
        } catch (IllegalArgumentException ex) {
            throw new KnowledgeException("Unknown retention action: " + action);
        }
    }
}
