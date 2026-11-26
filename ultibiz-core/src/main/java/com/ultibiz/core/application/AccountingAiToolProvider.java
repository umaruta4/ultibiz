package com.ultibiz.core.application;

import com.ultibiz.core.ai.AiToolProvider;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The accounting tools of the assistant: a read tool that lists the invoices
 * and a write tool that drafts a journal. The write tool never posts — it
 * prepares the proposal that a human confirms.
 */
@Component
public class AccountingAiToolProvider implements AiToolProvider {

    private final JdbcTemplate jdbc;

    public AccountingAiToolProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "accounting";
    }

    @Override
    public List<AiTool> tools() {
        return List.of(
                new AiTool("find_invoices", "List the customer invoices and their balances", false,
                        List.of("invoice", "faktur", "piutang", "ar", "receivable")),
                new AiTool("find_bills", "List the supplier bills and their balances", false,
                        List.of("bill", "tagihan", "hutang", "ap", "payable")),
                new AiTool("draft_journal", "Draft a journal entry for a human to confirm", true,
                        List.of("journal", "jurnal", "posting", "catat", "catatAN")));
    }

    /** The result of a read tool. */
    public record ToolResult(String summary, Map<String, Object> data) {
    }

    /** Runs a read tool of this module. */
    public ToolResult read(SiteScope scope, String toolName, Map<String, String> arguments) {
        int limit = Integer.parseInt(arguments.getOrDefault("limit", "5"));
        if ("find_invoices".equals(toolName)) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT i.invoice_number, p.name AS party, i.invoice_date, i.due_date,
                           i.currency, i.total, i.paid_amount, i.status
                    FROM ar_invoice i JOIN party p ON p.id = i.party_id
                    WHERE i.organization_id = ? ORDER BY i.invoice_date DESC LIMIT ?
                    """, scope.organizationId(), limit);
            StringBuilder summary = new StringBuilder();
            for (Map<String, Object> row : rows) {
                summary.append(row.get("invoice_number")).append(" | ").append(row.get("party"))
                        .append(" | total ").append(row.get("total")).append(" ")
                        .append(row.get("currency")).append(" | paid ").append(row.get("paid_amount"))
                        .append(" | ").append(row.get("status")).append('\n');
            }
            if (summary.length() == 0) {
                summary.append("There are no customer invoices.");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("invoices", rows);
            return new ToolResult(summary.toString().trim(), data);
        }
        if ("find_bills".equals(toolName)) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT b.bill_number, p.name AS party, b.bill_date, b.due_date,
                           b.currency, b.total, b.paid_amount, b.status
                    FROM ap_bill b JOIN party p ON p.id = b.party_id
                    WHERE b.organization_id = ? ORDER BY b.bill_date DESC LIMIT ?
                    """, scope.organizationId(), limit);
            StringBuilder summary = new StringBuilder();
            for (Map<String, Object> row : rows) {
                summary.append(row.get("bill_number")).append(" | ").append(row.get("party"))
                        .append(" | total ").append(row.get("total")).append(" ")
                        .append(row.get("currency")).append(" | paid ").append(row.get("paid_amount"))
                        .append(" | ").append(row.get("status")).append('\n');
            }
            if (summary.length() == 0) {
                summary.append("There are no supplier bills.");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("bills", rows);
            return new ToolResult(summary.toString().trim(), data);
        }
        throw new IllegalArgumentException("Unknown read tool: " + toolName);
    }
}
