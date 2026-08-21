package com.aicostops.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

public abstract class MySqlContainerSupport {

    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("aicostops_test")
            .withUsername("aicostops")
            .withPassword("aicostops-test-only");

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
