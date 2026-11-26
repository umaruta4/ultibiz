package com.ultibiz.web.web;

import com.ultibiz.core.application.DashboardService;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Generates the scheduled reports. The batch runs under a MariaDB named lock,
 * so two replicas never write the same report twice.
 */
@Component
public class ReportScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ReportScheduler.class);
    private static final String LOCK = "ultibiz-report-run";

    private final DashboardService dashboard;
    private final ReportMailer mailer;
    private final DataSource dataSource;

    public ReportScheduler(DashboardService dashboard, ReportMailer mailer, DataSource dataSource) {
        this.dashboard = dashboard;
        this.mailer = mailer;
        this.dataSource = dataSource;
    }

    @Scheduled(fixedDelayString = "${ultibiz.reports.delay-ms:900000}",
            initialDelayString = "${ultibiz.reports.initial-delay-ms:300000}")
    public void runReports() {
        try (Connection connection = dataSource.getConnection()) {
            if (!lock(connection)) {
                return;
            }
            try {
                var files = dashboard.runDueReports();
                if (!files.isEmpty()) {
                    LOG.info("The report scheduler generated {} file(s)", files.size());
                    for (var file : files) {
                        long subscriptionId = file.subscriptionId() == null ? 0L : file.subscriptionId();
                        mailer.send(file, dashboard.subscriptionEmail(subscriptionId),
                                dashboard.subscriptionOrganization(subscriptionId));
                    }
                }
            } finally {
                unlock(connection);
            }
        } catch (Exception ex) {
            LOG.warn("The report scheduler failed: {}", ex.getMessage());
        }
    }

    private boolean lock(Connection connection) {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT GET_LOCK('" + LOCK + "', 0)")) {
            return rs.next() && rs.getInt(1) == 1;
        } catch (Exception ex) {
            return false;
        }
    }

    private void unlock(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT RELEASE_LOCK('" + LOCK + "')");
        } catch (Exception ignored) {
            // the lock goes with the connection
        }
    }
}
