package com.aicostops.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

public abstract class MySqlContainerSupport {

    /*
     * The default 120s startup window is not enough when host-side storage
     * latency degrades (observed: mysqld --initialize phases taking minutes
     * per fsync-heavy step on WSL2). The container does come up eventually,
     * so give it a generous window instead of failing the whole suite.
     */
    private static final int STARTUP_TIMEOUT_SECONDS = 600;

    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("aicostops_test")
            .withUsername("aicostops")
            .withPassword("aicostops-test-only")
            .withStartupTimeoutSeconds(STARTUP_TIMEOUT_SECONDS)
            .withConnectTimeoutSeconds(60);

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void registerMySqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
    }
}
