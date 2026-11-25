package com.ultibiz.core.exchange.peppol;

import com.ultibiz.core.exchange.CanonicalDocument;
import com.ultibiz.core.exchange.ExchangeLine;
import com.ultibiz.core.exchange.ValidationIssue;
import java.util.ArrayList;
import java.util.List;

/**
 * The EN 16931 business rules (fatal) plus the Peppol-specific endpoint rules.
 * Rule ids match the official EN 16931 / Peppol catalog so a rejection report
 * carries the same id the Peppol Schematron would produce.
 */
public final class PeppolRules {

    private PeppolRules() {
    }

    public static List<ValidationIssue> validate(CanonicalDocument doc) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (blank(doc.documentNumber())) {
            issues.add(ValidationIssue.fatal("BR-02", "An Invoice shall have an Invoice number"));
        }
        if (doc.issueDate() == null) {
            issues.add(ValidationIssue.fatal("BR-03", "An Invoice shall have an Issue date"));
        }
        if (blank(doc.currency())) {
            issues.add(ValidationIssue.fatal("BR-05",
                    "An Invoice shall have an Invoice currency code"));
        }
        if (doc.seller() == null || blank(doc.seller().name())) {
            issues.add(ValidationIssue.fatal("BR-06", "An Invoice shall contain the Seller name"));
        }
        if (doc.buyer() == null || blank(doc.buyer().name())) {
            issues.add(ValidationIssue.fatal("BR-07", "An Invoice shall contain the Buyer name"));
        }
        if (doc.lines().isEmpty()) {
            issues.add(ValidationIssue.fatal("BR-16",
                    "An Invoice shall have at least one Invoice line"));
        }
        int i = 0;
        for (ExchangeLine line : doc.lines()) {
            i++;
            if (line.quantity() == null || line.quantity().signum() < 0) {
                issues.add(ValidationIssue.fatal("BR-21",
                        "Invoice line " + i + " shall have an invoiced quantity"));
            }
            if (line.unitPrice() == null || line.unitPrice().signum() < 0) {
                issues.add(ValidationIssue.fatal("BR-26",
                        "Invoice line " + i + " shall have a net price"));
            }
            if (line.taxPercent() == null) {
                issues.add(ValidationIssue.fatal("BR-27",
                        "Invoice line " + i + " shall have a VAT rate"));
            }
        }
        // BR-CO-25: Invoice total amount with VAT = total without VAT + VAT amount;
        // the payable amount must not be negative
        if (doc.grossTotal().signum() < 0) {
            issues.add(ValidationIssue.fatal("BR-CO-25",
                    "The amount due for payment must not be negative"));
        }
        // Peppol endpoint rules
        if (doc.buyer() == null || blank(doc.buyer().electronicAddress())) {
            issues.add(ValidationIssue.fatal("PEPPOL-EN16931-R010",
                    "Buyer electronic address MUST be provided"));
        }
        if (doc.seller() == null || blank(doc.seller().electronicAddress())) {
            issues.add(ValidationIssue.fatal("PEPPOL-EN16931-R020",
                    "Seller electronic address MUST be provided"));
        }
        if (doc.dueDate() == null) {
            issues.add(ValidationIssue.warning("BR-09",
                    "An Invoice should have a payment due date"));
        }
        if (doc.buyer() != null && blank(doc.buyer().countryCode())) {
            issues.add(ValidationIssue.warning("BR-55",
                    "The Buyer should have a country code"));
        }
        return issues;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
