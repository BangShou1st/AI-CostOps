package com.aicostops.gateway.testsupport;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Gateway integration-test support. The Gateway has no Flyway dependency (the
 * Backend is the sole production migration owner), so tests apply the exact
 * repository migration scripts V1..V18 directly from the Backend module over
 * the shared MySQL 8.4 container.
 */
public abstract class GatewayMySqlContainerSupport {

    private static final int STARTUP_TIMEOUT_SECONDS = 600;
    private static final List<String> MIGRATIONS = List.of(
            "V1__foundation_baseline.sql",
            "V2__m1_identity_organization_schema.sql",
            "V3__seed_v1_roles_permissions.sql",
            "V4__m2_evidence_import_schema.sql",
            "V5__m2_finance_reviewer_provider_account_read.sql",
            "V6__m2_raw_provider_record_usage_window_check.sql",
            "V7__m2_import_workflow_review_indexes.sql",
            "V8__m3_canonical_cost_foundation.sql",
            "V9__m3_duplicate_attribution_foundation.sql",
            "V10__m4_expense_approval.sql",
            "V11__m4_budget_period_schema.sql",
            "V12__m4_budget_commitment_approval.sql",
            "V13__m5_immutable_ledger_schema.sql",
            "V14__m5_expense_posted_state.sql",
            "V15__m5_ledger_target_integrity.sql",
            "V16__m6_reconciliation_close.sql",
            "V17__m8_budget_lookup_index.sql",
            "V18__m11_gateway_edge_foundation.sql");

    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("aicostops_test")
            .withUsername("aicostops")
            .withPassword("aicostops-test-only")
            .withStartupTimeoutSeconds(STARTUP_TIMEOUT_SECONDS)
            .withConnectTimeoutSeconds(60);

    static {
        MYSQL.start();
        applyMigrations();
    }

    @DynamicPropertySource
    static void registerMySqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    private static void applyMigrations() {
        var migrationRoot = Path.of("..", "backend", "src", "main", "resources", "db", "migration");
        var url = MYSQL.getJdbcUrl() + "?serverTimezone=UTC";
        try (var connection = DriverManager.getConnection(url, MYSQL.getUsername(), MYSQL.getPassword())) {
            for (var migration : MIGRATIONS) {
                var resource = new org.springframework.core.io.FileSystemResource(
                        migrationRoot.resolve(migration).toFile());
                ScriptUtils.executeSqlScript(connection, resource);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to apply repository migrations for Gateway tests", ex);
        }
    }
}