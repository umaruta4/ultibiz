package com.ultibiz.core.application;

import com.ultibiz.core.ai.AiInsight;
import com.ultibiz.core.ai.AiInsightProvider;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The inventory anomalies: stock that went negative and products without a
 * safety stock while the MRP suggests a purchase.
 */
@Component
public class InventoryInsightProvider implements AiInsightProvider {

    private final JdbcTemplate jdbc;

    public InventoryInsightProvider(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "inventory";
    }

    @Override
    public List<AiInsight> insights(SiteScope scope) {
        List<AiInsight> insights = new ArrayList<>();
        jdbc.query("""
                SELECT p.code, p.name, s.quantity AS quantity_on_hand, w.code AS warehouse
                FROM stock_balance s
                JOIN product p ON p.id = s.product_id
                JOIN warehouse w ON w.id = s.warehouse_id
                WHERE s.organization_id = ? AND s.quantity < 0
                """, rs -> {
            insights.add(new AiInsight("INVENTORY_NEGATIVE_STOCK", "CRITICAL",
                    "Negative stock on " + rs.getString("code"),
                    "Product " + rs.getString("name") + " in " + rs.getString("warehouse")
                            + " is at " + rs.getString("quantity_on_hand") + " units",
                    0.95, "Check the movements of the period and reverse the wrong issue",
                    "product", rs.getString("code")));
        }, scope.organizationId());

        jdbc.query("""
                SELECT pr.code, pr.name, pr.safety_stock, COALESCE(SUM(s.quantity), 0) AS on_hand
                FROM product pr
                LEFT JOIN stock_balance s ON s.product_id = pr.id AND s.organization_id = pr.organization_id
                WHERE pr.organization_id = ? AND pr.safety_stock > 0
                GROUP BY pr.id, pr.code, pr.name, pr.safety_stock
                HAVING on_hand < pr.safety_stock
                """, rs -> {
            insights.add(new AiInsight("INVENTORY_BELOW_SAFETY_STOCK", "WARNING",
                    "Below the safety stock: " + rs.getString("code"),
                    "Product " + rs.getString("name") + " has " + rs.getString("on_hand")
                            + " units on hand, the safety stock is " + rs.getString("safety_stock"),
                    0.80, "Run the MRP suggestions and create the purchase order",
                    "product", rs.getString("code")));
        }, scope.organizationId());
        return insights;
    }
}
