package com.ultibiz.core.tenant;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The connection goes to the database of the tenant of the current request.
 * With the mode "single" every query goes to the control database (the
 * behaviour of the single-tenant installation).
 *
 * <p>The registry itself is read from the control database — the routing never
 * needs to know the tenant before it can ask for the tenant.
 */
public class TenantRoutingDataSource implements DataSource {

    private static final Logger LOG = LoggerFactory.getLogger(TenantRoutingDataSource.class);

    private final DataSource control;
    private final TenantDataSourceFactory factory;
    private final boolean perTenantDatabase;
    private final Map<String, DataSource> tenants = new ConcurrentHashMap<>();

    public TenantRoutingDataSource(DataSource control, TenantDataSourceFactory factory,
                                    boolean perTenantDatabase) {
        this.control = control;
        this.factory = factory;
        this.perTenantDatabase = perTenantDatabase;
    }

    /** The pool of the current request. */
    protected DataSource resolve() {
        String database = TenantContext.current();
        if (database == null || !perTenantDatabase) {
            return control;
        }
        try {
            return tenants.computeIfAbsent(database, factory::create);
        } catch (Exception ex) {
            LOG.warn("The database {} of the tenant is not reachable: {}", database, ex.getMessage());
            return control;
        }
    }

    @Override
    public Connection getConnection() throws SQLException {
        return resolve().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return resolve().getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return control.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        control.setLogWriter(out);
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        control.setLoginTimeout(seconds);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return control.getLoginTimeout();
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return control.getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return control.unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return control.isWrapperFor(iface);
    }
}
