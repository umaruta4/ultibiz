package com.ultibiz.web.web;

import com.ultibiz.core.application.InsightService;
import com.ultibiz.core.application.SiteScope;
import com.ultibiz.web.security.SiteScopeProvider;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the anomaly rules on a schedule. The batch runs under a MariaDB named
 * lock, so two replicas never run the same rules twice.
 */
@Component
public class InsightScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(InsightScheduler.class);
    private static final String LOCK = "ultibiz-insight-run";

    private final InsightService insights;
    private final SiteScopeProvider scopeProvider;
    private final DataSource dataSource;

    public InsightScheduler(InsightService insights, SiteScopeProvider scopeProvider,
                            DataSource dataSource) {
        this.insights = insights;
        this.scopeProvider = scopeProvider;
        this.dataSource = dataSource;
    }

    @Scheduled(fixedDelayString = "${ultibiz.insights.delay-ms:600000}",
            initialDelayString = "${ultibiz.insights.initial-delay-ms:120000}")
    public void runRules() {
        try (Connection connection = dataSource.getConnection()) {
            if (!lock(connection)) {
                return;
            }
            try {
                SiteScope scope = scopeProvider.current();
                int found = insights.run(scope);
                if (found > 0) {
                    LOG.info("The insight rules produced {} finding(s)", found);
                }
            } finally {
                unlock(connection);
            }
        } catch (Exception ex) {
            LOG.warn("The insight rules failed: {}", ex.getMessage());
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
