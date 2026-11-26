package com.ultibiz.core.infrastructure;

import com.ultibiz.core.dashboard.DashboardException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The Dashboard persistence: metrics, saved views, thresholds and report subscriptions. */
@Repository
public class DashboardRepository {

    private final JdbcTemplate jdbc;

    public DashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static long id(Object value) {
        return ((Number) value).longValue();
    }

    // ------------------------------------------------------------------ records

    public record Metric(long id, String code, String title, String module, String metricKey,
                         String format, String permissionCodes, String drillPath, int sortOrder,
                         boolean active) {
    }

    public record SavedView(long id, long userId, String code, String title, String module,
                            String filters, boolean shared) {
    }

    public record Threshold(long id, String metricKey, String label, String comparison,
                            BigDecimal value, String severity, boolean active) {
    }

    public record Subscription(long id, long organizationId, long userId, String reportCode, String title,
                               String frequency, String format, String email,
                               LocalDateTime nextRunAt, LocalDateTime lastRunAt, String lastStatus,
                               boolean active) {
    }

    public record ReportFile(long id, Long subscriptionId, String reportCode, String period,
                             String format, String fileName, String filePath, long sizeBytes,
                             String deliveryStatus, String deliveryDetail,
                             LocalDateTime generatedAt, String generatedBy) {
    }

    /** One scalar value of a metric query. */
    public BigDecimal jdbcQuery(String sql, long organizationId) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, organizationId);
        return value;
    }

    // ------------------------------------------------------------------ metrics

    public List<Metric> metrics(long organizationId) {
        return jdbc.query("""
                SELECT * FROM dash_metric WHERE organization_id = ? AND is_active = TRUE
                ORDER BY sort_order, code
                """, (rs, n) -> new Metric(id(rs.getObject("id")), rs.getString("code"),
                        rs.getString("title"), rs.getString("module"), rs.getString("metric_key"),
                        rs.getString("format"), rs.getString("permission_codes"),
                        rs.getString("drill_path"), rs.getInt("sort_order"),
                        rs.getBoolean("is_active")), organizationId);
    }

    public List<Threshold> thresholds(long organizationId) {
        return jdbc.query("""
                SELECT * FROM dash_threshold WHERE organization_id = ? AND is_active = TRUE
                ORDER BY metric_key
                """, (rs, n) -> new Threshold(id(rs.getObject("id")), rs.getString("metric_key"),
                        rs.getString("label"), rs.getString("comparison"),
                        rs.getBigDecimal("value"), rs.getString("severity"),
                        rs.getBoolean("is_active")), organizationId);
    }

    // ------------------------------------------------------------------ saved views

    public long createSavedView(long organizationId, long userId, String code, String title,
                                String module, String filters, boolean shared) {
        jdbc.update("""
                INSERT INTO dash_saved_view (organization_id, user_id, code, title, module,
                    filters, is_shared)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, organizationId, userId, code, title, module, filters, shared);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<SavedView> savedViews(long organizationId, long userId) {
        return jdbc.query("""
                SELECT * FROM dash_saved_view
                WHERE organization_id = ? AND (user_id = ? OR is_shared = TRUE)
                ORDER BY module, title
                """, (rs, n) -> new SavedView(id(rs.getObject("id")), id(rs.getObject("user_id")),
                        rs.getString("code"), rs.getString("title"), rs.getString("module"),
                        rs.getString("filters"), rs.getBoolean("is_shared")),
                organizationId, userId);
    }

    public void deleteSavedView(long organizationId, long userId, long viewId) {
        jdbc.update("""
                DELETE FROM dash_saved_view WHERE organization_id = ? AND user_id = ? AND id = ?
                """, organizationId, userId, viewId);
    }

    // ------------------------------------------------------------------ subscriptions

    public long createSubscription(long organizationId, long userId, String reportCode,
                                   String title, String frequency, String format, String email,
                                   LocalDateTime nextRunAt) {
        jdbc.update("""
                INSERT INTO dash_report_subscription (organization_id, user_id, report_code, title,
                    frequency, format, email, next_run_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, organizationId, userId, reportCode, title, frequency, format, email,
                Timestamp.valueOf(nextRunAt));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public Optional<Subscription> subscription(long organizationId, long userId,
                                               String reportCode) {
        List<Subscription> rows = jdbc.query("""
                SELECT * FROM dash_report_subscription
                WHERE organization_id = ? AND user_id = ? AND report_code = ?
                """, (rs, n) -> mapSubscription(rs), organizationId, userId, reportCode);
        return rows.stream().findFirst();
    }

    public List<Subscription> subscriptions(long organizationId, long userId) {
        return jdbc.query("""
                SELECT * FROM dash_report_subscription
                WHERE organization_id = ? AND user_id = ? ORDER BY title
                """, (rs, n) -> mapSubscription(rs), organizationId, userId);
    }

    /** The subscriptions whose next run is due. */
    public List<Subscription> dueSubscriptions(LocalDateTime now) {
        return jdbc.query("""
                SELECT * FROM dash_report_subscription
                WHERE is_active = TRUE AND next_run_at <= ?
                """, (rs, n) -> mapSubscription(rs), Timestamp.valueOf(now));
    }

    private Subscription mapSubscription(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp next = rs.getTimestamp("next_run_at");
        Timestamp last = rs.getTimestamp("last_run_at");
        return new Subscription(id(rs.getObject("id")), id(rs.getObject("organization_id")),
                id(rs.getObject("user_id")),
                rs.getString("report_code"), rs.getString("title"), rs.getString("frequency"),
                rs.getString("format"), rs.getString("email"),
                next == null ? null : next.toLocalDateTime(),
                last == null ? null : last.toLocalDateTime(),
                rs.getString("last_status"), rs.getBoolean("is_active"));
    }

    public void markRun(long subscriptionId, LocalDateTime nextRun, String status) {
        jdbc.update("""
                UPDATE dash_report_subscription SET last_run_at = CURRENT_TIMESTAMP(6),
                    next_run_at = ?, last_status = ? WHERE id = ?
                """, Timestamp.valueOf(nextRun), status, subscriptionId);
    }

    // ------------------------------------------------------------------ report files

    public long addReportFile(long organizationId, Long subscriptionId, String reportCode,
                              String period, String format, String fileName, String filePath,
                              long sizeBytes, String deliveryStatus, String deliveryDetail,
                              String generatedBy) {
        jdbc.update("""
                INSERT INTO dash_report_file (organization_id, subscription_id, report_code,
                    period, format, file_name, file_path, size_bytes, delivery_status,
                    delivery_detail, generated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, organizationId, subscriptionId, reportCode, period, format, fileName,
                filePath, sizeBytes, deliveryStatus, deliveryDetail, generatedBy);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public List<ReportFile> reportFiles(long organizationId, long userId) {
        return jdbc.query("""
                SELECT f.* FROM dash_report_file f
                LEFT JOIN dash_report_subscription s ON s.id = f.subscription_id
                WHERE f.organization_id = ? AND (s.user_id = ? OR s.id IS NULL)
                ORDER BY f.generated_at DESC
                """, (rs, n) -> {
            Timestamp generated = rs.getTimestamp("generated_at");
            return new ReportFile(id(rs.getObject("id")),
                    rs.getObject("subscription_id") == null ? null : id(rs.getObject("subscription_id")),
                    rs.getString("report_code"), rs.getString("period"), rs.getString("format"),
                    rs.getString("file_name"), rs.getString("file_path"),
                    rs.getLong("size_bytes"), rs.getString("delivery_status"),
                    rs.getString("delivery_detail"),
                    generated == null ? null : generated.toLocalDateTime(),
                    rs.getString("generated_by"));
        }, organizationId, userId);
    }

    /** The raw rows of a report (the CSV/HTML body). */
    public List<java.util.Map<String, Object>> reportRows(long organizationId, String reportCode) {
        return switch (reportCode) {
            case "AR_OVERDUE" -> jdbc.queryForList("""
                    SELECT i.invoice_number, p.name AS customer, i.due_date, i.total, i.currency
                    FROM ar_invoice i JOIN party p ON p.id = i.party_id
                    WHERE i.organization_id = ? AND i.status IN ('OPEN', 'PARTIAL')
                      AND i.due_date < CURDATE()
                    ORDER BY i.due_date
                    """, organizationId);
            case "OPEN_SALES_ORDERS" -> jdbc.queryForList("""
                    SELECT o.order_number, p.name AS customer, o.status, o.order_date, o.total
                    FROM sales_order o JOIN party p ON p.id = o.party_id
                    WHERE o.organization_id = ? AND o.status IN ('DRAFT', 'CONFIRMED')
                    ORDER BY o.order_date
                    """, organizationId);
            case "STOCK_VALUE" -> jdbc.queryForList("""
                    SELECT p.code AS product, w.code AS warehouse, s.quantity, s.total_value
                    FROM stock_balance s JOIN product p ON p.id = s.product_id
                    JOIN warehouse w ON w.id = s.warehouse_id
                    WHERE s.organization_id = ? AND s.quantity <> 0
                    ORDER BY p.code
                    """, organizationId);
            case "HEADCOUNT" -> jdbc.queryForList("""
                    SELECT e.employee_no, CONCAT(e.first_name, ' ', e.last_name) AS name,
                           e.hire_date, e.status
                    FROM hr_employee e
                    WHERE e.organization_id = ? AND e.deleted_at IS NULL
                    ORDER BY e.employee_no
                    """, organizationId);
            default -> throw new DashboardException("Unknown report: " + reportCode);
        };
    }
}
