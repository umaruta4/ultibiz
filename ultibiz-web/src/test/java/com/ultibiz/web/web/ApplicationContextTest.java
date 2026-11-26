package com.ultibiz.web.web;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.ultibiz.core.tenant.TenantService;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The whole application context against the test database — the wiring of the
 * data sources, the migrations, the security and the schedulers. A web slice
 * ({@code @WebMvcTest}) does not load the domain layer, so a broken data-source
 * configuration only shows up here (or, worse, at the deployment).
 *
 * <p>Runs when the release check passes the test database
 * ({@code -Dultibiz.it.jdbcUrl=...}), skipped otherwise.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "ultibiz.it.jdbcUrl", matches = ".+")
class ApplicationContextTest {

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getProperty("ultibiz.it.jdbcUrl"));
        registry.add("spring.datasource.username",
                () -> System.getProperty("ultibiz.it.user", "ultibiz"));
        registry.add("spring.datasource.password",
                () -> System.getProperty("ultibiz.it.password", ""));
        registry.add("ultibiz.tenant.control-database",
                () -> System.getProperty("ultibiz.it.jdbcUrl").replaceAll(".*/([^/?]+).*", "$1"));
        registry.add("ultibiz.tenant.admin-password",
                () -> System.getProperty("ultibiz.it.adminPassword", ""));
        registry.add("ultibiz.tenant.mode", () -> "single");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void theWholeContextStarts() {
        assertNotNull(context.getBean(DataSource.class), "the routing data source");
        assertNotNull(context.getBean(TenantService.class), "the tenant service");
        // the single sign-on is off without the profile "sso"
        org.junit.jupiter.api.Assertions.assertEquals(0, context.getBeanNamesForType(
                org.springframework.security.oauth2.client.registration.ClientRegistrationRepository.class)
                .length, "no OAuth2 client without the profile sso");
    }

    @Test
    void theControlTemplateAndTheRoutingDataSourceAreDistinct() {
        DataSource routing = context.getBean(DataSource.class);
        Object control = context.getBean("controlDataSource");
        assertNotNull(routing);
        assertNotNull(control);
        org.junit.jupiter.api.Assertions.assertNotSame(routing, control,
                "the control pool and the routing data source must not be the same bean");
    }
}
