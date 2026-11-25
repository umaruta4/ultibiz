package com.ultibiz.core.exchange;

/**
 * One validation finding. The rule id is the external rule identifier where one
 * exists (Peppol EN 16931 rules like BR-CO-25), or an internal code otherwise.
 */
public record ValidationIssue(String ruleId, String severity, String message) {

    public static final String FATAL = "FATAL";
    public static final String WARNING = "WARNING";

    public static ValidationIssue fatal(String ruleId, String message) {
        return new ValidationIssue(ruleId, FATAL, message);
    }

    public static ValidationIssue warning(String ruleId, String message) {
        return new ValidationIssue(ruleId, WARNING, message);
    }
}
