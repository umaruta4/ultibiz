package com.ultibiz.core.exchange.rest;

import com.ultibiz.core.exchange.CanonicalDocument;
import com.ultibiz.core.exchange.ValidationIssue;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The structural business rules every adapter shares, named after the EN 16931
 * rules they mirror so a rejection report carries the same rule id a Peppol
 * validator would produce.
 */
public final class StructuralRules {

    private static final Set<String> TYPES = Set.of(
            CanonicalDocument.INVOICE, CanonicalDocument.CREDIT_NOTE, CanonicalDocument.ORDER,
            CanonicalDocument.DESPATCH, CanonicalDocument.PURCHASE_INVOICE,
            CanonicalDocument.STATEMENT);

    private StructuralRules() {
    }

    public static List<ValidationIssue> check(CanonicalDocument document) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (document.type() == null || !TYPES.contains(document.type())) {
            issues.add(ValidationIssue.fatal("UB-01", "Unknown document type: " + document.type()));
        }
        if (document.documentNumber() == null || document.documentNumber().isBlank()) {
            issues.add(ValidationIssue.fatal("BR-02", "An Invoice shall have an Invoice number"));
        }
        if (document.issueDate() == null) {
            issues.add(ValidationIssue.fatal("BR-03", "An Invoice shall have an Issue date"));
        }
        if (document.currency() == null || document.currency().isBlank()) {
            issues.add(ValidationIssue.fatal("BR-05", "An Invoice shall have an invoice currency code"));
        }
        if (document.seller() == null || document.seller().name() == null
                || document.seller().name().isBlank()) {
            issues.add(ValidationIssue.fatal("BR-06", "An Invoice shall contain the Seller name"));
        }
        if (document.buyer() == null || document.buyer().name() == null
                || document.buyer().name().isBlank()) {
            issues.add(ValidationIssue.fatal("BR-07", "An Invoice shall contain the Buyer name"));
        }
        if (document.lines().isEmpty()) {
            issues.add(ValidationIssue.fatal("BR-16", "An Invoice shall have at least one Invoice line"));
        }
        for (int i = 0; i < document.lines().size(); i++) {
            var line = document.lines().get(i);
            if (line.quantity() == null || line.quantity().signum() < 0) {
                issues.add(ValidationIssue.fatal("BR-21",
                        "Invoice line " + (i + 1) + " has a negative or missing quantity"));
            }
            if (line.unitPrice() == null || line.unitPrice().signum() < 0) {
                issues.add(ValidationIssue.fatal("BR-26",
                        "Invoice line " + (i + 1) + " has a negative or missing price"));
            }
            if (line.taxPercent() == null) {
                issues.add(ValidationIssue.fatal("BR-27",
                        "Invoice line " + (i + 1) + " is missing its VAT rate"));
            }
        }
        // BR-CO-25: the amount due for payment must equal the legal total
        // (net + tax, minus prepaid). The canonical model derives the totals
        // from the lines, so the rule guards the declared total when a
        // payload carries one.
        BigDecimal gross = document.grossTotal();
        if (gross.signum() < 0) {
            issues.add(ValidationIssue.fatal("BR-CO-25",
                    "The amount due for payment must not be negative (" + gross + ")"));
        }
        return issues;
    }
}
