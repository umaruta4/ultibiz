package com.ultibiz.web.web;

import com.ultibiz.core.application.HelpdeskService;
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
 * Watches the SLA of the open tickets and escalates the ones that passed
 * their deadline. The batch runs under a MariaDB named lock, so two replicas
 * never escalate the same ticket twice.
 */
@Component
public class SlaScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(SlaScheduler.class);
    private static final String LOCK = "ultibiz-sla-run";

    private final HelpdeskService helpdesk;
    private final SiteScopeProvider scopeProvider;
    private final DataSource dataSource;

    public SlaScheduler(HelpdeskService helpdesk, SiteScopeProvider scopeProvider,
                        DataSource dataSource) {
        this.helpdesk = helpdesk;
        this.scopeProvider = scopeProvider;
        this.dataSource = dataSource;
    }

    @Scheduled(fixedDelayString = "${ultibiz.sla.delay-ms:600000}",
            initialDelayString = "${ultibiz.sla.initial-delay-ms:180000}")
    public void watchSla() {
        try (Connection connection = dataSource.getConnection()) {
            if (!lock(connection)) {
                return;
            }
            try {
                int escalated = helpdesk.runSlaMonitor(scopeProvider.current(), "scheduler");
                if (escalated > 0) {
                    LOG.info("The SLA monitor escalated {} ticket(s)", escalated);
                }
            } finally {
                unlock(connection);
            }
        } catch (Exception ex) {
            LOG.warn("The SLA monitor failed: {}", ex.getMessage());
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
