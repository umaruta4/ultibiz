package com.ultibiz.core.tenant;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

/** Creates the connection pool of a tenant database (on the same MariaDB server). */
public class TenantDataSourceFactory {

    private final String host;
    private final String port;
    private final String user;
    private final String password;
    private final int poolSize;

    public TenantDataSourceFactory(String host, String port, String user, String password,
                                    int poolSize) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.poolSize = poolSize;
    }

    /** The pool of one database. */
    public DataSource create(String databaseName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mariadb://" + host + ":" + port + "/" + databaseName
                + "?useUnicode=true&characterEncoding=UTF-8");
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(poolSize);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        return new HikariDataSource(config);
    }

    /** The URL of a database (for the migration and the administration). */
    public String jdbcUrl(String databaseName) {
        return "jdbc:mariadb://" + host + ":" + port + "/" + databaseName;
    }
}
