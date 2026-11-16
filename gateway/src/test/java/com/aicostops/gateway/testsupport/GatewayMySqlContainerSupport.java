package com.aicostops.gateway.testsupport;

import java.sql.DriverManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;

/**
 * Gateway integration-test support. The Gateway has no Flyway dependency (the
 * Backend is the sole production migration owner), so tests apply every
 * repository migration script directly from the Backend module over the shared
 * MySQL 8.4 container. The migration set is discovered at runtime so tests
 * cannot silently drift behind the production schema. A shared Redis container
 * provides the runtime rate-limit/coordination dependency so an enabled
 * mandatory limiter has a real broker.
 */
public abstract class GatewayMySqlContainerSupport {

    private static final int STARTUP_TIMEOUT_SECONDS = 600;

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("aicostops_test")
            .withUsername("aicostops")
            .withPassword("aicostops-test-only")
            .withStartupTimeoutSeconds(STARTUP_TIMEOUT_SECONDS)
            .withConnectTimeoutSeconds(60);

    protected static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.1-alpine")
            .withExposedPorts(6379);

    static {
        MYSQL.start();
        applyMigrations();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerMySqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static void applyMigrations() {
        var url = MYSQL.getJdbcUrl() + "?serverTimezone=UTC";
        try (var connection = DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword())) {
            for (var migration : GatewayMigrationSupport.migrationPaths()) {
                var resource = new org.springframework.core.io.FileSystemResource(
                        migration.toFile());
                ScriptUtils.executeSqlScript(connection, resource);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to apply repository migrations for Gateway tests", ex);
        }
    }
}
