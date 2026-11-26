package com.ultibiz.core.dashboard;

import com.ultibiz.core.application.DashboardService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The report rendering: CSV, HTML, XHTML and the PDF. */
class ReportRenderingTest {

    @Test
    void theHtmlEscapesTheValues() {
        List<Map<String, Object>> rows = List.of(Map.of("name", "Research & <Development>"));
        String html = DashboardService.toHtml("Headcount & payroll", "2026-11-26", rows);
        assertTrue(html.contains("Research &amp; &lt;Development&gt;"),
                "the cell value is escaped");
        assertTrue(html.contains("Headcount &amp; payroll"), "the title is escaped");
    }

    @Test
    void theXhtmlIsWellFormed() {
        List<Map<String, Object>> rows = List.of(Map.of("name", "Ananda & Co"));
        String xhtml = DashboardService.toXhtml("Report", "2026-11-26", rows);
        assertTrue(xhtml.startsWith("<?xml"), "an XHTML document");
        assertTrue(xhtml.contains("xmlns"), "the XHTML namespace");
        assertTrue(xhtml.contains("Ananda &amp; Co"), "the value is escaped");
    }

    @Test
    void thePdfIsARealDocument() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("employee", "Admin UltiBiz");
        row.put("salary", "10000000.00");
        byte[] pdf = DashboardService.toPdf("Payroll", "2026-11-26", List.of(row));
        // a PDF starts with %PDF-
        assertTrue(pdf.length > 500, "the PDF is not empty (" + pdf.length + " bytes)");
        assertEquals("%PDF-", new String(pdf, 0, 5));
    }
}
