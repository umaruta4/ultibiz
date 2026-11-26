package com.ultibiz.core.application;

import com.ultibiz.core.dashboard.DashboardException;
import com.ultibiz.core.dashboard.ReportSchedulePolicy;
import com.ultibiz.core.dashboard.SavedViewPolicy;
import com.ultibiz.core.dashboard.ThresholdPolicy;
import com.ultibiz.core.domain.AuditEvent;
import com.ultibiz.core.infrastructure.AiRepository;
import com.ultibiz.core.infrastructure.DashboardRepository;
import com.ultibiz.core.settings.SystemSettingsService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Dashboard plugin: the KPI tiles a role may see, the thresholds that
 * raise an alert, the saved views, the scheduled reports (the file is written
 * and — when a webhook is configured — delivered) and the insight board that
 * consumes the AI insight providers.
 */
@Service
public class DashboardService {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardService.class);

    private final DashboardRepository dashboards;
    private final InsightService insights;
    private final AuditService audit;
    private final Path reportDirectory;
    private final String deliveryWebhook;

    /** The settings of the deployment: the directory and the webhook are read at runtime. */
    @Autowired(required = false)
    private SystemSettingsService settings;

    public DashboardService(DashboardRepository dashboards, InsightService insights,
                            AuditService audit,
                            @Value("${ultibiz.dashboard.report-dir:}") String reportDir,
                            @Value("${ultibiz.dashboard.delivery-webhook:}") String webhook) {
        this.dashboards = dashboards;
        this.insights = insights;
        this.audit = audit;
        this.deliveryWebhook = webhook == null ? "" : webhook.trim();
        Path directory;
        if (reportDir == null || reportDir.isBlank()) {
            directory = Paths.get(System.getProperty("java.io.tmpdir"), "ultibiz-reports");
        } else {
            directory = Paths.get(reportDir);
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException ex) {
            LOG.warn("The report directory {} is not writable ({}), falling back to the temp dir",
                    directory, ex.getMessage());
            directory = Paths.get(System.getProperty("java.io.tmpdir"), "ultibiz-reports");
            try {
                Files.createDirectories(directory);
            } catch (IOException ignored) {
                // the report then cannot be written — the failure is reported per run
            }
        }
        this.reportDirectory = directory;
    }

    // ------------------------------------------------------------------ tiles

    /** A tile with its value. */
    public record Tile(DashboardRepository.Metric metric, BigDecimal value) {
    }

    /** The tiles the given permissions may see. */
    public List<Tile> tiles(SiteScope scope, Set<String> permissions) {
        List<Tile> tiles = new ArrayList<>();
        for (DashboardRepository.Metric metric : dashboards.metrics(scope.organizationId())) {
            if (!visible(metric, permissions)) {
                continue;
            }
            tiles.add(new Tile(metric, metricValue(scope, metric.metricKey())));
        }
        return tiles;
    }

    /** A tile is visible when the metric asks for no permission, or the user holds one. */
    static boolean visible(DashboardRepository.Metric metric, Set<String> permissions) {
        String codes = metric.permissionCodes();
        if (codes == null || codes.isBlank()) {
            return true;
        }
        for (String code : codes.split(",")) {
            if (!code.isBlank() && permissions.contains(code.trim())) {
                return true;
            }
        }
        return false;
    }

    /** The value of one metric, computed from the ERP data. */
    public BigDecimal metricValue(SiteScope scope, String key) {
        BigDecimal value = switch (key) {
            case "REVENUE_MONTH" -> sum(scope, """
                    SELECT COALESCE(SUM(total), 0) FROM ar_invoice
                    WHERE organization_id = ? AND status <> 'VOID'
                      AND YEAR(invoice_date) = YEAR(CURDATE()) AND MONTH(invoice_date) = MONTH(CURDATE())
                    """);
            case "AR_OVERDUE_AMOUNT" -> sum(scope, """
                    SELECT COALESCE(SUM(total - paid_amount), 0) FROM ar_invoice
                    WHERE organization_id = ? AND status IN ('OPEN', 'PARTIAL')
                      AND due_date < CURDATE()
                    """);
            case "CASH_BALANCE" -> sum(scope, """
                    SELECT COALESCE(SUM(l.credit - l.debit), 0) FROM journal_line l
                    JOIN account a ON a.id = l.account_id
                    JOIN journal_entry j ON j.id = l.journal_id
                    WHERE j.organization_id = ? AND a.code IN ('1100', '1110') AND j.status = 'POSTED'
                    """);
            case "OPEN_SALES_ORDERS" -> sum(scope, """
                    SELECT COUNT(*) FROM sales_order
                    WHERE organization_id = ? AND status IN ('DRAFT', 'CONFIRMED')
                    """);
            case "OPEN_PURCHASE_ORDERS" -> sum(scope, """
                    SELECT COUNT(*) FROM purchase_order
                    WHERE organization_id = ? AND status IN ('DRAFT', 'CONFIRMED')
                    """);
            case "STOCK_VALUE" -> sum(scope, """
                    SELECT COALESCE(SUM(total_value), 0) FROM stock_balance WHERE organization_id = ?
                    """);
            case "HEADCOUNT" -> sum(scope, """
                    SELECT COUNT(*) FROM hr_employee
                    WHERE organization_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                    """);
            case "OPEN_LEAVES" -> sum(scope, """
                    SELECT COUNT(*) FROM hr_leave_request
                    WHERE organization_id = ? AND status = 'SUBMITTED'
                    """);
            case "CRM_PIPELINE" -> sum(scope, """
                    SELECT COALESCE(SUM(amount), 0) FROM crm_opportunity
                    WHERE organization_id = ? AND status = 'OPEN'
                    """);
            case "PROJECT_BUDGET" -> sum(scope, """
                    SELECT COALESCE(SUM(budget_amount), 0) FROM prj_project
                    WHERE organization_id = ? AND status = 'ACTIVE'
                    """);
            default -> BigDecimal.ZERO;
        };
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal sum(SiteScope scope, String sql) {
        BigDecimal value = dashboards.jdbcQuery(sql, scope.organizationId());
        return value == null ? BigDecimal.ZERO : value;
    }

    // ------------------------------------------------------------------ alerts

    /** An alert raised by a threshold. */
    public record Alert(String metricKey, String label, String severity, BigDecimal value,
                        BigDecimal threshold, String comparison) {
    }

    /** The alerts of the thresholds that are breached. */
    public List<Alert> alerts(SiteScope scope) {
        List<Alert> alerts = new ArrayList<>();
        for (DashboardRepository.Threshold threshold : dashboards.thresholds(scope.organizationId())) {
            BigDecimal value = metricValue(scope, threshold.metricKey());
            if (ThresholdPolicy.breached(new ThresholdPolicy.Threshold(threshold.metricKey(),
                    threshold.label(), threshold.comparison(), threshold.value(),
                    threshold.severity()), value)) {
                alerts.add(new Alert(threshold.metricKey(), threshold.label(), threshold.severity(),
                        value, threshold.value(), threshold.comparison()));
            }
        }
        alerts.sort((a, b) -> Integer.compare(ThresholdPolicy.severityRank(b.severity()),
                ThresholdPolicy.severityRank(a.severity())));
        return alerts;
    }

    // ------------------------------------------------------------------ insight board

    /** The insight board: the alerts of the AI insight providers. */
    public List<AiRepository.Alert> insightBoard(SiteScope scope) {
        insights.run(scope);
        return insights.alerts(scope, "OPEN", 50);
    }

    // ------------------------------------------------------------------ saved views

    @Transactional
    public long saveView(SiteScope scope, String actor, long userId, String code, String title,
                         String module, String filters, boolean shared) {
        SavedViewPolicy.check(code, title, module);
        long id = dashboards.createSavedView(scope.organizationId(), userId, code, title, module,
                filters == null || filters.isBlank() ? "{}" : filters, shared);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "DASH_VIEW_SAVE",
                "dash_saved_view", String.valueOf(id), "{\"code\":\"" + code + "\"}", null));
        return id;
    }

    @Transactional
    public void deleteView(SiteScope scope, String actor, long userId, long viewId) {
        dashboards.deleteSavedView(scope.organizationId(), userId, viewId);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "DASH_VIEW_DELETE",
                "dash_saved_view", String.valueOf(viewId), null, null));
    }

    public List<DashboardRepository.SavedView> savedViews(SiteScope scope, long userId) {
        return dashboards.savedViews(scope.organizationId(), userId);
    }

    // ------------------------------------------------------------------ reports

    @Transactional
    public long subscribe(SiteScope scope, String actor, long userId, String reportCode,
                          String title, String frequency, String format, String email) {
        LocalDateTime next = ReportSchedulePolicy.nextRun(frequency, LocalDateTime.now());
        long id = dashboards.createSubscription(scope.organizationId(), userId, reportCode,
                title, frequency, format, email, next);
        audit.record(AuditEvent.of(scope.organizationId(), actor, "DASH_REPORT_SUBSCRIBE",
                "dash_report_subscription", String.valueOf(id),
                "{\"report\":\"" + reportCode + "\"}", null));
        return id;
    }

    public List<DashboardRepository.Subscription> subscriptions(SiteScope scope, long userId) {
        return dashboards.subscriptions(scope.organizationId(), userId);
    }

    /** The e-mail of a subscription (the delivery address of the report). */
    public String subscriptionEmail(long subscriptionId) {
        return dashboards.subscriptionEmail(subscriptionId);
    }

    /** The organisation of a subscription. */
    public long subscriptionOrganization(long subscriptionId) {
        return dashboards.subscriptionOrganization(subscriptionId);
    }

    public List<DashboardRepository.ReportFile> reportFiles(SiteScope scope, long userId) {
        return dashboards.reportFiles(scope.organizationId(), userId);
    }

    /** The catalogue of the reports that can be scheduled. */
    public List<String[]> reportCatalogue() {
        return List.of(
                new String[]{"AR_OVERDUE", "Overdue receivables"},
                new String[]{"OPEN_SALES_ORDERS", "Open sales orders"},
                new String[]{"STOCK_VALUE", "Stock value"},
                new String[]{"HEADCOUNT", "Employees"});
    }

    /**
     * Runs the subscriptions that are due: the report is written as CSV (or
     * HTML) and — when a delivery webhook is configured — posted to it.
     */
    @Transactional
    public List<DashboardRepository.ReportFile> runDueReports() {
        List<DashboardRepository.ReportFile> generated = new ArrayList<>();
        for (DashboardRepository.Subscription subscription : dashboards.dueSubscriptions(
                LocalDateTime.now())) {
            generated.add(generate(subscription));
        }
        return generated;
    }

    /** Generates one report file for a subscription. */
    @Transactional
    public DashboardRepository.ReportFile generate(DashboardRepository.Subscription subscription) {
        String period = LocalDate.now().toString();
        List<Map<String, Object>> rows = dashboards.reportRows(subscription.organizationId(),
                subscription.reportCode());
        String format = subscription.format() == null ? "CSV" : subscription.format();
        String body = "CSV".equals(format)
                ? toCsv(rows) : toXhtml(subscription.title(), period, rows);
        String extension = switch (format) {
            case "CSV" -> "csv";
            case "PDF" -> "pdf";
            default -> "html";
        };
        String fileName = subscription.reportCode() + "-" + period + "." + extension;
        Path path = reportDirectory().resolve(fileName);
        try {
            if ("PDF".equals(format)) {
                Files.write(path, toPdf(subscription.title(), period, rows));
            } else {
                Files.writeString(path, body, StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new DashboardException("The report could not be written: " + ex.getMessage());
        }
        String delivery = "FILE_READY";
        String detail = "The report is ready for download";
        if (!effectiveWebhook().isBlank()) {
            delivery = deliver(subscription, path, fileName, detail, effectiveWebhook());
        }
        long id = dashboards.addReportFile(subscription.organizationId(), subscription.id(),
                subscription.reportCode(), period, subscription.format(), fileName,
                path.toString(), body.length(), delivery, detail, "scheduler");
        dashboards.markRun(subscription.id(),
                ReportSchedulePolicy.nextRun(subscription.frequency(), LocalDateTime.now()),
                delivery);
        return dashboards.reportFiles(subscription.organizationId(), subscription.userId()).stream()
                .filter(file -> file.id() == id).findFirst().orElseThrow();
    }

    /** The directory of the reports: the setting of the deployment wins. */
    private Path reportDirectory() {
        if (settings == null) {
            return reportDirectory;
        }
        String configured = settings.value("report.dir");
        if (configured == null || configured.isBlank()) {
            return reportDirectory;
        }
        return Paths.get(configured.trim());
    }

    /** The webhook of the delivery: the setting of the deployment wins. */
    private String effectiveWebhook() {
        if (settings == null) {
            return deliveryWebhook;
        }
        String configured = settings.value("dashboard.delivery.webhook");
        if (configured == null || configured.isBlank()) {
            return deliveryWebhook;
        }
        return configured.trim();
    }

    /** Posts the report to the configured delivery webhook. */
    private String deliver(DashboardRepository.Subscription subscription, Path path,
                           String fileName, String detail, String webhook) {
        try {
            String payload = "{\"report\":\"" + subscription.reportCode() + "\",\"file\":\""
                    + fileName + "\",\"email\":\"" + (subscription.email() == null ? ""
                    : subscription.email()) + "\"}";
            java.net.http.HttpRequest request = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(webhook))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            java.net.http.HttpResponse<String> response = java.net.http.HttpClient
                    .newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return "DELIVERED";
            }
            return "FAILED";
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("The report delivery failed: {}", ex.getMessage());
            return "FAILED";
        }
    }

    /** The rows as a PDF (the XHTML report, rendered headless). */
    public static byte[] toPdf(String title, String period, List<Map<String, Object>> rows) {
        String xhtml = toXhtml(title, period, rows);
        try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            org.xhtmlrenderer.pdf.ITextRenderer renderer = new org.xhtmlrenderer.pdf.ITextRenderer();
            renderer.setDocumentFromString(xhtml);
            renderer.layout();
            renderer.createPDF(out);
            return out.toByteArray();
        } catch (Exception ex) {
            throw new DashboardException("The PDF could not be rendered: " + ex.getMessage());
        }
    }

    /** The report as XHTML (well-formed, every value escaped). */
    public static String toXhtml(String title, String period, List<Map<String, Object>> rows) {
        StringBuilder html = new StringBuilder();
        html.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<!DOCTYPE html>\n")
                .append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>")
                .append(escape(title)).append("</title><style>")
                .append("body{font-family:sans-serif;margin:24px}")
                .append("table{border-collapse:collapse;width:100%}th,td{border:1px solid #ccc;")
                .append("padding:4px 8px;font-size:12px}th{background:#f3f4f6}")
                .append("</style></head><body>")
                .append("<h1>").append(escape(title)).append("</h1><p>Period: ")
                .append(escape(period)).append("</p>");
        if (!rows.isEmpty()) {
            List<String> columns = new ArrayList<>(rows.get(0).keySet());
            html.append("<table><thead><tr>");
            columns.forEach(column -> html.append("<th>").append(escape(column)).append("</th>"));
            html.append("</tr></thead><tbody>");
            for (Map<String, Object> row : rows) {
                html.append("<tr>");
                columns.forEach(column -> html.append("<td>")
                        .append(escape(row.get(column) == null ? "" : row.get(column)))
                        .append("</td>"));
                html.append("</tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    /** Escapes a value for the HTML/XHTML report. */
    public static String escape(Object value) {
        return value.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** The rows as CSV. */
    static String toCsv(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        StringBuilder csv = new StringBuilder();
        List<String> columns = new ArrayList<>(rows.get(0).keySet());
        csv.append(String.join(";", columns)).append('\n');
        for (Map<String, Object> row : rows) {
            List<String> values = new ArrayList<>();
            for (String column : columns) {
                Object value = row.get(column);
                String text = value == null ? "" : value.toString();
                values.add(text.contains(";") ? "\"" + text + "\"" : text);
            }
            csv.append(String.join(";", values)).append('\n');
        }
        return csv.toString();
    }

    /** The rows as a print-ready HTML page (the browser can save it as PDF). */
    public static String toHtml(String title, String period, List<Map<String, Object>> rows) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
                .append(escape(title)).append("</title><style>body{font-family:sans-serif;margin:24px}")
                .append("table{border-collapse:collapse;width:100%}th,td{border:1px solid #ccc;")
                .append("padding:4px 8px;font-size:12px}th{background:#f3f4f6}</style></head><body>")
                .append("<h1>").append(escape(title)).append("</h1><p>Period: ")
                .append(escape(period)).append("</p>");
        if (!rows.isEmpty()) {
            List<String> columns = new ArrayList<>(rows.get(0).keySet());
            html.append("<table><thead><tr>");
            columns.forEach(column -> html.append("<th>").append(escape(column)).append("</th>"));
            html.append("</tr></thead><tbody>");
            for (Map<String, Object> row : rows) {
                html.append("<tr>");
                columns.forEach(column -> html.append("<td>")
                        .append(escape(row.get(column) == null ? "" : row.get(column)))
                        .append("</td>"));
                html.append("</tr>");
            }
            html.append("</tbody></table>");
        }
        html.append("</body></html>");
        return html.toString();
    }
}
