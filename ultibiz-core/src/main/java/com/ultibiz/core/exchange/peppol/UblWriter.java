package com.ultibiz.core.exchange.peppol;

import com.ultibiz.core.exchange.CanonicalDocument;
import com.ultibiz.core.exchange.ExchangeException;
import com.ultibiz.core.exchange.ExchangeLine;
import com.ultibiz.core.exchange.ExchangeParty;
import com.ultibiz.core.exchange.ExchangeTax;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Renders a canonical invoice or credit note as UBL 2.1 per Peppol BIS
 * Billing 3.0 (EN 16931). Hand-rolled: no XML library, every value escaped.
 */
public final class UblWriter {

    public static final String CUSTOMIZATION_ID =
            "urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0";
    public static final String PROFILE_ID = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";

    private UblWriter() {
    }

    public static String write(CanonicalDocument doc) {
        boolean creditNote = CanonicalDocument.CREDIT_NOTE.equals(doc.type());
        if (!CanonicalDocument.INVOICE.equals(doc.type()) && !creditNote) {
            throw new ExchangeException("Peppol renders only INVOICE or CREDIT_NOTE, not "
                    + doc.type());
        }
        String root = creditNote ? "CreditNote" : "Invoice";
        StringBuilder x = new StringBuilder(4096);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append('<').append(root)
            .append(" xmlns=\"urn:oasis:names:specification:ubl:schema:xsd:").append(root).append("-2\"")
            .append(" xmlns:cac=\"urn:oasis:names:specification:ubl:schema:xsd:CommonAggregateComponents-2\"")
            .append(" xmlns:cbc=\"urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2\">\n");
        cbc(x, "UBLVersionID", "2.1");
        cbc(x, "CustomizationID", CUSTOMIZATION_ID);
        cbc(x, "ProfileID", PROFILE_ID);
        cbc(x, "ID", doc.documentNumber());
        cbc(x, "IssueDate", String.valueOf(doc.issueDate()));
        if (doc.dueDate() != null) {
            cbc(x, "DueDate", doc.dueDate().toString());
        }
        cbc(x, creditNote ? "CreditNoteTypeCode" : "InvoiceTypeCode", creditNote ? "381" : "380");
        cbc(x, "DocumentCurrencyCode", doc.currency());
        party(x, "AccountingSupplierParty", doc.seller());
        party(x, "AccountingCustomerParty", doc.buyer());

        // tax total, one subtotal per (code, percent)
        x.append("<cac:TaxTotal>");
        amount(x, "TaxAmount", doc.taxTotal(), doc.currency());
        for (ExchangeTax tax : doc.taxBreakdown()) {
            x.append("<cac:TaxSubtotal>");
            amount(x, "TaxableAmount", tax.taxableAmount(), doc.currency());
            amount(x, "TaxAmount", tax.taxAmount(), doc.currency());
            x.append("<cac:TaxCategory>");
            cbc(x, "ID", tax.taxCode() == null || tax.taxCode().isBlank() ? "S" : "S");
            cbc(x, "Percent", tax.percent().stripTrailingZeros().toPlainString());
            x.append("<cac:TaxScheme>");
            cbc(x, "ID", "VAT");
            x.append("</cac:TaxScheme></cac:TaxCategory></cac:TaxSubtotal>");
        }
        x.append("</cac:TaxTotal>");

        x.append("<cac:LegalMonetaryTotals>");
        amount(x, "LineExtensionAmount", doc.netTotal(), doc.currency());
        amount(x, "TaxExclusiveAmount", doc.netTotal(), doc.currency());
        amount(x, "TaxInclusiveAmount", doc.grossTotal(), doc.currency());
        amount(x, "PayableAmount", doc.grossTotal(), doc.currency());
        x.append("</cac:LegalMonetaryTotals>");

        String lineTag = creditNote ? "CreditedQuantity" : "InvoicedQuantity";
        String lineRoot = creditNote ? "CreditNoteLine" : "InvoiceLine";
        int lineNo = 0;
        for (ExchangeLine line : doc.lines()) {
            lineNo++;
            x.append("<cac:").append(lineRoot).append('>');
            cbc(x, "ID", String.valueOf(lineNo));
            x.append("<cbc:").append(lineTag).append(" unitCode=\"EA\">")
                .append(line.quantity().stripTrailingZeros().toPlainString())
                .append("</cbc:").append(lineTag).append('>');
            amount(x, "LineExtensionAmount", line.netAmount(), doc.currency());
            x.append("<cac:Item>");
            cbc(x, "Name", line.description() == null ? line.productCode() : line.description());
            if (line.productCode() != null && !line.productCode().isBlank()) {
                x.append("<cac:SellersItemIdentification>");
                cbc(x, "ID", line.productCode());
                x.append("</cac:SellersItemIdentification>");
            }
            x.append("<cac:ClassifiedTaxCategory>");
            cbc(x, "ID", "S");
            cbc(x, "Percent", line.taxPercent().stripTrailingZeros().toPlainString());
            x.append("</cac:ClassifiedTaxCategory>");
            x.append("</cac:Item>");
            x.append("<cac:Price>");
            amount(x, "PriceAmount", line.unitPrice(), doc.currency());
            x.append("</cac:Price>");
            x.append("</cac:").append(lineRoot).append(">\n");
        }
        x.append("</").append(root).append('>');
        return x.toString();
    }

    private static void party(StringBuilder x, String tag, ExchangeParty party) {
        if (party == null) {
            return;
        }
        x.append("<cac:").append(tag).append("><cac:Party>");
        if (party.electronicAddress() != null && party.electronicAddress().contains(":")) {
            String scheme = party.electronicAddress().substring(0, party.electronicAddress().indexOf(':'));
            String value = party.electronicAddress().substring(party.electronicAddress().indexOf(':') + 1);
            x.append("<cbc:EndpointID schemeID=\"").append(esc(scheme)).append("\">")
                .append(esc(value)).append("</cbc:EndpointID>");
        }
        x.append("<cac:PartyName>");
        cbc(x, "Name", party.name());
        x.append("</cac:PartyName>");
        x.append("<cac:PostalAddress>");
        if (party.street() != null) {
            cbc(x, "StreetName", party.street());
        }
        if (party.city() != null) {
            cbc(x, "CityName", party.city());
        }
        if (party.postalCode() != null) {
            cbc(x, "PostalZone", party.postalCode());
        }
        x.append("<cac:Country>");
        cbc(x, "IdentificationCode", party.countryCode() == null ? "ID" : party.countryCode());
        x.append("</cac:Country></cac:PostalAddress>");
        if (party.vatNumber() != null) {
            x.append("<cac:PartyTaxScheme>");
            cbc(x, "CompanyID", party.vatNumber());
            x.append("<cac:TaxScheme>");
            cbc(x, "ID", "VAT");
            x.append("</cac:TaxScheme></cac:PartyTaxScheme>");
        }
        x.append("<cac:PartyLegalEntity>");
        cbc(x, "RegistrationName", party.name());
        x.append("</cac:PartyLegalEntity>");
        x.append("</cac:Party></cac:").append(tag).append(">");
    }

    private static void cbc(StringBuilder x, String tag, String value) {
        x.append("<cbc:").append(tag).append('>').append(esc(value)).append("</cbc:")
            .append(tag).append('>');
    }

    private static void amount(StringBuilder x, String tag, BigDecimal value, String currency) {
        x.append("<cbc:").append(tag).append(" currencyID=\"").append(esc(currency)).append("\">")
            .append(value.setScale(2, RoundingMode.HALF_UP).toPlainString())
            .append("</cbc:").append(tag).append('>');
    }

    static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
