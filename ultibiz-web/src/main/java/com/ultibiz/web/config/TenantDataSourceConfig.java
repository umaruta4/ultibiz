package com.ultibiz.web.config;

import com.ultibiz.core.tenant.TenantDataSourceFactory;
import com.ultibiz.core.tenant.TenantRoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The data sources:
 *
 * <ul>
 *   <li>the **control database** — the identity (the organisations, the users,
 *       the roles) and the tenant registry;</li>
 *   <li>the **routing data source** (the primary one) — the connection goes to
 *       the database of the tenant of the request; with the mode "single"
 *       every connection goes to the control database (the behaviour of the
 *       single-tenant installation).</li>
 * </ul>
 *
 * <p>Exactly one data source is marked as primary — two primary candidates
 * make every injection of a {@code DataSource} ambiguous ("more than one
 * 'primary' bean found").
 */
@Configuration
public class TenantDataSourceConfig {

    /** The pool of the control database (not primary — the routing one is). */
    @Bean
    HikariDataSource controlDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /** The routing data source — the application uses this one. */
    @Bean
    @Primary
    DataSource dataSource(
            @Qualifier("controlDataSource") HikariDataSource control,
            @Value("${ultibiz.tenant.mode:single}") String mode,
            @Value("${ultibiz.tenant.admin-host:${ULTIBIZ_DB_HOST:127.0.0.1}}") String host,
            @Value("${ultibiz.tenant.admin-port:${ULTIBIZ_DB_PORT:3311}}") String port,
            @Value("${ultibiz.tenant.application-user:${ULTIBIZ_DB_USER:ultibiz}}") String user,
            @Value("${ultibiz.tenant.application-password:${ULTIBIZ_DB_PASSWORD:}}") String password,
            @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize) {
        TenantDataSourceFactory factory = new TenantDataSourceFactory(host, port, user, password,
                Math.max(2, poolSize / 2));
        return new TenantRoutingDataSource(control, factory, "database".equalsIgnoreCase(mode));
    }

    /** The template of the control database (the registry reads it). */
    @Bean
    @Qualifier("controlJdbcTemplate")
    JdbcTemplate controlJdbcTemplate(@Qualifier("controlDataSource") DataSource control) {
        return new JdbcTemplate(control);
    }
}
