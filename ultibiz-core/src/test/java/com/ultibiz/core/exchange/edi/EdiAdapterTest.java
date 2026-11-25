package com.ultibiz.core.exchange.edi;

import com.ultibiz.core.exchange.CanonicalDocument;
import com.ultibiz.core.exchange.ExchangeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Golden-file tests for the X12 and EDIFACT adapters. */
class EdiAdapterTest {

    private final X12Adapter x12 = new X12Adapter();
    private final EdifactAdapter edifact = new EdifactAdapter();

    private static String golden(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/exchange/" + name));
    }

    private static String normalize(String text) {
        return text.replace("\r", "").strip();
    }

    @Test
    void parsesTheX12PurchaseOrder() throws Exception {
        X12Interchange interchange = X12Interchange.parse(golden("x12-850.edi"));
        assertEquals("ULTIBIZ", interchange.senderId());
        assertEquals("PARTNER", interchange.receiverId());
        assertEquals("000000001", interchange.controlNumber());
        assertEquals(1, interchange.transactions().size());
        assertEquals("850", interchange.transactions().get(0).transactionSetCode());

        CanonicalDocument doc = x12.parse(golden("x12-850.edi"));
        assertEquals(CanonicalDocument.ORDER, doc.type());
        assertEquals("PO-1001", doc.documentNumber());
        assertEquals(LocalDate.of(2026, 11, 25), doc.issueDate());
        assertEquals(2, doc.lines().size());
        assertEquals("BRG-0001", doc.lines().get(0).productCode());
        assertEquals(0, doc.lines().get(0).quantity().compareTo(new java.math.BigDecimal("10")));
        assertEquals(0, doc.lines().get(1).unitPrice().compareTo(new java.math.BigDecimal("12000")));
    }

    @Test
    void generatesThe997Acknowledgement() throws Exception {
        String ack = X12Documents.generate997("ULTIBIZ", "PARTNER", "20261125", "1201",
                "000000002", "2", "PO", 1, true);
        assertEquals(normalize(golden("x12-997.edi")), normalize(ack));
    }

    @Test
    void parsesTheEdifactOrder() throws Exception {
        CanonicalDocument doc = edifact.parse(golden("edifact-orders.edi"));
        assertEquals(CanonicalDocument.ORDER, doc.type());
        assertEquals("PO-2001", doc.documentNumber());
        assertEquals(LocalDate.of(2026, 11, 25), doc.issueDate());
        assertEquals(2, doc.lines().size());
        assertEquals("BRG-0001", doc.lines().get(0).productCode());
        assertEquals(0, doc.lines().get(0).quantity().compareTo(new java.math.BigDecimal("10")));
    }

    @Test
    void generatesTheContrlAcknowledgement() throws Exception {
        String ack = EdifactDocuments.generateContrl("ULTIBIZ", "PARTNER", "20261125", "1201",
                "2", "1", true);
        assertEquals(normalize(golden("edifact-contrl.edi")), normalize(ack));
    }

    @Test
    void roundTripsAnX12Document() throws Exception {
        CanonicalDocument parsed = x12.parse(golden("x12-850.edi"));
        CanonicalDocument back = x12.parse(x12.write(parsed));
        assertEquals(parsed.documentNumber(), back.documentNumber());
        assertEquals(parsed.lines().size(), back.lines().size());
        assertEquals(0, parsed.grossTotal().compareTo(back.grossTotal()));
    }

    @Test
    void roundTripsAnEdifactDocument() throws Exception {
        CanonicalDocument parsed = edifact.parse(golden("edifact-orders.edi"));
        CanonicalDocument back = edifact.parse(edifact.write(parsed));
        assertEquals(parsed.documentNumber(), back.documentNumber());
        assertEquals(parsed.lines().size(), back.lines().size());
        assertEquals(0, parsed.grossTotal().compareTo(back.grossTotal()));
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(ExchangeException.class, () -> X12Interchange.parse("not an interchange"));
        assertThrows(ExchangeException.class, () -> EdifactInterchange.parse("no UNB here"));
    }

    @Test
    void rejectsAnInterchangeWithAMismatchedControlNumber() throws Exception {
        String broken = golden("x12-850.edi").replace("IEA*1*000000001", "IEA*1*000000009");
        assertThrows(ExchangeException.class, () -> X12Interchange.parse(broken));
    }
}
