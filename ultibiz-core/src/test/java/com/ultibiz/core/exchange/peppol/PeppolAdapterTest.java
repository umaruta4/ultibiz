package com.ultibiz.core.exchange.peppol;

import com.ultibiz.core.exchange.CanonicalDocument;
import com.ultibiz.core.exchange.ExchangeException;
import com.ultibiz.core.exchange.ExchangeLine;
import com.ultibiz.core.exchange.ExchangeParty;
import com.ultibiz.core.exchange.ValidationIssue;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Golden round-trip and rule-id tests for the Peppol UBL adapter. */
class PeppolAdapterTest {

    private final PeppolAdapter adapter = new PeppolAdapter();

    static CanonicalDocument fixture() {
        ExchangeParty seller = new ExchangeParty(null, "Acme", "0192:987654321", "ID001",
                "Jl. Merdeka 1", "Jakarta", "10110", "ID");
        ExchangeParty buyer = new ExchangeParty(null, "Customer", "0192:123456789", null,
                null, null, null, "ID");
        List<ExchangeLine> lines = List.of(
                new ExchangeLine("BRG-0001", "Bearing", new BigDecimal("10"),
                        new BigDecimal("45000"), "S", new BigDecimal("11")),
                new ExchangeLine("BRG-0002", "Grease", new BigDecimal("5"),
                        new BigDecimal("12000"), "S", new BigDecimal("11")));
        return new CanonicalDocument(CanonicalDocument.INVOICE, "INV-2026-001",
                LocalDate.of(2026, 11, 25), LocalDate.of(2026, 12, 25), "IDR",
                seller, buyer, lines, "NET 30", List.of());
    }

    @Test
    void writesPeppolBisInvoiceXml() {
        String xml = adapter.write(fixture());
        assertTrue(xml.contains("urn:cen.eu:en16931:2017#compliant#urn:fdc:peppol.eu:2017:poacc:billing:3.0"),
                "CustomizationID");
        assertTrue(xml.contains("<cbc:EndpointID schemeID=\"0192\">987654321</cbc:EndpointID>"),
                "seller endpoint");
        assertTrue(xml.contains("<cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode>"));
        assertTrue(xml.contains("<cbc:Percent>11</cbc:Percent>"));
        assertTrue(xml.contains("PayableAmount"));
    }

    @Test
    void roundTripsThroughTheCanonicalModel() {
        CanonicalDocument fixture = fixture();
        CanonicalDocument back = adapter.parse(adapter.write(fixture));
        assertEquals(fixture.documentNumber(), back.documentNumber());
        assertEquals(fixture.issueDate(), back.issueDate());
        assertEquals(fixture.dueDate(), back.dueDate());
        assertEquals(fixture.currency(), back.currency());
        assertEquals("0192:987654321", back.seller().electronicAddress());
        assertEquals(fixture.lines().size(), back.lines().size());
        assertEquals(0, fixture.netTotal().compareTo(back.netTotal()));
        assertEquals(0, fixture.grossTotal().compareTo(back.grossTotal()));
    }

    @Test
    void parsesTheGoldenFile() throws Exception {
        String golden = Files.readString(
                Path.of("src/test/resources/exchange/peppol-invoice.xml"));
        CanonicalDocument fromGolden = adapter.parse(golden);
        CanonicalDocument fromWriter = adapter.parse(adapter.write(fixture()));
        assertEquals(fromWriter.documentNumber(), fromGolden.documentNumber());
        assertEquals(fromWriter.issueDate(), fromGolden.issueDate());
        assertEquals(fromWriter.currency(), fromGolden.currency());
        assertEquals(fromWriter.seller().electronicAddress(), fromGolden.seller().electronicAddress());
        assertEquals(fromWriter.lines().size(), fromGolden.lines().size());
        assertEquals(0, fromWriter.grossTotal().compareTo(fromGolden.grossTotal()));
    }

    @Test
    void rejectsAnInvoiceWithoutLinesWithTheExactRuleId() {
        CanonicalDocument broken = new CanonicalDocument(CanonicalDocument.INVOICE, "INV-X",
                LocalDate.of(2026, 11, 25), null, "IDR",
                new ExchangeParty(null, "Acme", "0192:987654321", null, null, null, null, "ID"),
                new ExchangeParty(null, "Customer", "0192:123456789", null, null, null, null, "ID"),
                List.of(), null, List.of());
        List<String> fatalIds = adapter.validate(broken).stream()
                .filter(i -> ValidationIssue.FATAL.equals(i.severity()))
                .map(ValidationIssue::ruleId)
                .toList();
        assertTrue(fatalIds.contains("BR-16"), "missing lines must report BR-16, got " + fatalIds);
    }

    @Test
    void rejectsANegativePayableWithBrCo25() {
        // a negative quantity drives the totals negative — BR-21 fires on the
        // line and BR-CO-25 on the negative amount due
        CanonicalDocument broken = new CanonicalDocument(CanonicalDocument.INVOICE, "INV-Y",
                LocalDate.of(2026, 11, 25), null, "IDR",
                new ExchangeParty(null, "Acme", "0192:987654321", null, null, null, null, "ID"),
                new ExchangeParty(null, "Customer", "0192:123456789", null, null, null, null, "ID"),
                List.of(new ExchangeLine("X", "Refund line", new BigDecimal("-1"),
                        new BigDecimal("100"), "S", new BigDecimal("11"))),
                null, List.of());
        List<String> fatalIds = adapter.validate(broken).stream()
                .filter(i -> ValidationIssue.FATAL.equals(i.severity()))
                .map(ValidationIssue::ruleId)
                .toList();
        assertTrue(fatalIds.contains("BR-CO-25"), "negative payable must report BR-CO-25, got " + fatalIds);
    }

    @Test
    void rejectsMalformedXml() {
        assertThrows(ExchangeException.class, () -> adapter.parse("<Invoice><broken"));
    }

    @Test
    void rendersACreditNote() {
        CanonicalDocument creditNote = new CanonicalDocument(CanonicalDocument.CREDIT_NOTE,
                "CN-1", LocalDate.of(2026, 11, 25), null, "IDR",
                fixture().seller(), fixture().buyer(), fixture().lines(), null, List.of());
        String xml = adapter.write(creditNote);
        assertTrue(xml.contains("<CreditNote"));
        assertTrue(xml.contains("<cbc:CreditNoteTypeCode>381</cbc:CreditNoteTypeCode>"));
    }
}
