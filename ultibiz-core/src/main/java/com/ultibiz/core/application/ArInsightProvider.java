package com.ultibiz.core.application;

import com.ultibiz.core.ai.AiInsight;
import com.ultibiz.core.ai.AiInsightProvider;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** The receivables anomalies: the invoices that are overdue. */
@Component
public class ArInsightProvider implements AiInsightProvider {

    private final JdbcTemplate jdbc;

    public ArInsightProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "accounting";
    }

    @Override
    public List<AiInsight> insights(SiteScope scope) {
        List<AiInsight> insights = new ArrayList<>();
        LocalDate today = LocalDate.now();
        jdbc.query("""
                SELECT i.invoice_number, p.name AS party, i.due_date, i.total, i.paid_amount,
                       i.currency
                FROM ar_invoice i JOIN party p ON p.id = i.party_id
                WHERE i.organization_id = ? AND i.status IN ('OPEN', 'PARTIAL') AND i.due_date < ?
                ORDER BY i.due_date
                """, rs -> {
            long days = java.time.temporal.ChronoUnit.DAYS.between(
                    rs.getDate("due_date").toLocalDate(), today);
            insights.add(new AiInsight("AR_OVERDUE_INVOICE", days > 30 ? "CRITICAL" : "WARNING",
                    "Overdue invoice " + rs.getString("invoice_number"),
                    "Customer " + rs.getString("party") + " owes " + rs.getString("total") + " "
                            + rs.getString("currency") + ", due on " + rs.getString("due_date")
                            + " (" + days + " days late)",
                    0.90, "Send a reminder or start the collection follow-up",
                    "ar_invoice", rs.getString("invoice_number")));
        }, scope.organizationId(), today);
        return insights;
    }
}
