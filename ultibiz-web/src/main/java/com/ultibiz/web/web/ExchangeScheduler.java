package com.ultibiz.web.web;

import com.ultibiz.core.application.ExchangeService;
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
 * Retries the failed exchange messages on a schedule. The batch runs under a
 * MariaDB named lock, so two replicas never deliver the same message twice.
 */
@Component
public class ExchangeScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(ExchangeScheduler.class);
    private static final String LOCK = "ultibiz-exchange-retry";

    private final ExchangeService exchange;
    private final SiteScopeProvider scopeProvider;
    private final DataSource dataSource;

    public ExchangeScheduler(ExchangeService exchange, SiteScopeProvider scopeProvider,
                             DataSource dataSource) {
        this.exchange = exchange;
        this.scopeProvider = scopeProvider;
        this.dataSource = dataSource;
    }

    @Scheduled(fixedDelayString = "${ultibiz.exchange.retry-delay-ms:60000}",
            initialDelayString = "${ultibiz.exchange.retry-initial-delay-ms:30000}")
    public void retryFailedMessages() {
        try (Connection connection = dataSource.getConnection()) {
            if (!lock(connection)) {
                return; // another replica is doing the work
            }
            try {
                SiteScope scope = scopeProvider.current();
                int retried = exchange.retryDue(scope, "scheduler");
                if (retried > 0) {
                    LOG.info("Retried {} exchange message(s)", retried);
                }
            } finally {
                unlock(connection);
            }
        } catch (Exception ex) {
            LOG.warn("The exchange retry batch failed: {}", ex.getMessage());
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
            // the lock is released with the connection anyway
        }
    }
}
