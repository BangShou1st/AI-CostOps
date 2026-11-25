package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Proves Flyway V34 adds {@code budget.reserved_amount}, backfills exactly
 * {@code SUM(ACTIVE + PENDING_HOLD)} while ignoring {@code RELEASED} and
 * {@code FINALIZED} reservations, and enforces {@code chk_budget_reserved_nonneg}.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V34BudgetReservedAmountMigrationIntegrationTest {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("aicostops_v34_test")
            .withUsername("aicostops")
            .withPassword("aicostops-test-only")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withConnectTimeoutSeconds(60);

    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private long mixedBudgetId;
    private long terminalOnlyBudgetId;
    private long emptyBudgetId;

    @BeforeAll
    void migrateSeedAndApplyV34() {
        MYSQL.start();
        var url = MYSQL.getJdbcUrl() + "?serverTimezone=UTC";
        dataSource = new SingleConnectionDataSource(url, MYSQL.getUsername(), MYSQL.getPassword(), true);
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("33")
                .load()
                .migrate();

        seedMixedReservationStatuses();

        Flyway.configure()
                .dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("34")
                .load()
                .migrate();
    }

    @AfterAll
    void stopMysql() {
        if (dataSource != null) {
            dataSource.destroy();
        }
        MYSQL.stop();
    }

    @Test
    void backfillSumsActiveAndPendingHoldOnly() {
        assertThat(jdbc.queryForObject(
                "SELECT reserved_amount FROM budget WHERE id=?",
                BigDecimal.class, mixedBudgetId)).isEqualByComparingTo("20.00000000");
        assertThat(jdbc.queryForObject(
                "SELECT reserved_amount FROM budget WHERE id=?",
                BigDecimal.class, terminalOnlyBudgetId)).isEqualByComparingTo("0.00000000");
        assertThat(jdbc.queryForObject(
                "SELECT reserved_amount FROM budget WHERE id=?",
                BigDecimal.class, emptyBudgetId)).isEqualByComparingTo("0.00000000");
    }

    @Test
    void checkConstraintRejectsNegativeReservedAmount() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE budget SET reserved_amount='-0.00000001' WHERE id=?", emptyBudgetId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_budget_reserved_nonneg");
    }

    private void seedMixedReservationStatuses() {
        var suffix = "v34-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "V34 " + suffix, suffix);
        var orgId = lastId();

        jdbc.update("""
                INSERT INTO billing_period(
                  org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,'2026-08-01 00:00:00.000000','2026-09-01 00:00:00.000000',
                  'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        var periodId = lastId();

        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'P1','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "p1-" + suffix);
        var project1 = lastId();
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'P2','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "p2-" + suffix);
        var project2 = lastId();
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'P3','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "p3-" + suffix);
        var project3 = lastId();

        mixedBudgetId = insertBudget(orgId, periodId, project1);
        terminalOnlyBudgetId = insertBudget(orgId, periodId, project2);
        emptyBudgetId = insertBudget(orgId, periodId, project3);

        jdbc.update("""
                INSERT INTO provider_catalog(
                  provider_code,name,adapter_code,base_url,status,capabilities_json,created_at,updated_at)
                VALUES ('V34_PROV','V34','CUSTOM_OPENAI_COMPATIBLE','https://provider.invalid','ACTIVE',
                  JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        jdbc.update("""
                INSERT INTO model_catalog(
                  model_key,name,status,capabilities_json,default_max_output_tokens,max_output_tokens,
                  created_at,updated_at)
                VALUES (?,'M','ACTIVE',JSON_OBJECT(),8192,131072,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "m-" + suffix);
        var modelId = lastId();
        jdbc.update("""
                INSERT INTO provider_model(
                  provider_code,model_id,provider_model_name,status,routing_eligible,
                  capabilities_json,created_at,updated_at)
                VALUES ('V34_PROV',?,'wire','ACTIVE',TRUE,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, modelId);
        var providerModelId = lastId();
        jdbc.update("""
                INSERT INTO provider_account(
                  org_id,provider_code,display_name,external_account_ref,status,metadata_json,
                  created_at,updated_at)
                VALUES (?,'V34_PROV','Acct','ext','ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        var providerAccountId = lastId();
        jdbc.update("""
                INSERT INTO pricing_version(
                  org_id,provider_account_id,provider_model_id,version,currency,
                  effective_from,status,created_at,activated_at)
                VALUES (?,?,?,1,'USD','2026-08-01 00:00:00.000000','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerAccountId, providerModelId);
        var pricingVersionId = lastId();
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Svc','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "svc-" + suffix);
        var serviceId = lastId();
        jdbc.update("""
                INSERT INTO gateway_credential(
                  org_id,credential_prefix,secret_digest,secret_digest_version,principal_type,
                  service_identity_id,project_id,financial_scope_type,financial_scope_id,
                  budget_enforcement_mode,status,created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',?,?,'PROJECT',?,'REQUIRED','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "v34cred00001", digest(1), serviceId, project1, project1);
        var credentialId = lastId();

        // Mixed budget: ACTIVE (12.50) + PENDING_HOLD (7.50) + RELEASED (99.00) + FINALIZED (50.00)
        insertReservationForStatus(orgId, periodId, credentialId, serviceId, project1, modelId,
                providerAccountId, providerModelId, pricingVersionId, mixedBudgetId, "ACTIVE", "12.50000000", 10);
        insertReservationForStatus(orgId, periodId, credentialId, serviceId, project1, modelId,
                providerAccountId, providerModelId, pricingVersionId, mixedBudgetId, "PENDING_HOLD", "7.50000000", 20);
        insertReservationForStatus(orgId, periodId, credentialId, serviceId, project1, modelId,
                providerAccountId, providerModelId, pricingVersionId, mixedBudgetId, "RELEASED", "99.00000000", 30);
        insertReservationForStatus(orgId, periodId, credentialId, serviceId, project1, modelId,
                providerAccountId, providerModelId, pricingVersionId, mixedBudgetId, "FINALIZED", "50.00000000", 40);

        // Terminal-only budget: RELEASED (10.00) + FINALIZED (5.00) -> expected 0.00
        insertReservationForStatus(orgId, periodId, credentialId, serviceId, project2, modelId,
                providerAccountId, providerModelId, pricingVersionId, terminalOnlyBudgetId, "RELEASED", "10.00000000", 50);
        insertReservationForStatus(orgId, periodId, credentialId, serviceId, project2, modelId,
                providerAccountId, providerModelId, pricingVersionId, terminalOnlyBudgetId, "FINALIZED", "5.00000000", 60);
    }

    private long insertBudget(long orgId, long periodId, long projectId) {
        jdbc.update("""
                INSERT INTO budget(
                  org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?,'PROJECT',?,'USD','500.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, projectId);
        return lastId();
    }

    private void insertReservationForStatus(
            long orgId, long periodId, long credentialId, long serviceId, long projectId,
            long modelId, long providerAccountId, long providerModelId, long pricingVersionId,
            long budgetId, String status, String amount, int seed) {
        jdbc.update("""
                INSERT INTO gateway_request(
                  org_id,public_request_id,credential_id,principal_type,service_identity_id,
                  project_id,financial_scope_type,financial_scope_id,logical_model_id,api_surface,
                  idempotency_key_digest,request_fingerprint,request_hmac_version,state,
                  billing_period_id,created_at,validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',?,?,'PROJECT',?,?,'CHAT_COMPLETIONS',?,?,1,'RESERVED',
                  ?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "gwr_" + UUID.randomUUID(), credentialId, serviceId, projectId,
                projectId, modelId, digest(seed), digest(seed + 1), periodId);
        var requestId = lastId();
        jdbc.update("""
                INSERT INTO gateway_route_attempt(
                  org_id,request_id,attempt_no,route_decision_id,provider_account_id,
                  provider_model_id,pricing_version_id,status,created_at)
                VALUES (?,?,1,?,?,?,?,'PLANNED',UTC_TIMESTAMP(6))
                """, orgId, requestId, "grd_" + UUID.randomUUID(), providerAccountId,
                providerModelId, pricingVersionId);
        var attemptId = lastId();
        jdbc.update("""
                INSERT INTO budget_reservation(
                  org_id,request_id,route_attempt_id,billing_period_id,budget_id,
                  financial_scope_type,financial_scope_id,currency,reserved_amount,
                  commitment_id,commitment_backed_amount,status,version,expires_at,
                  created_at,updated_at,released_at,finalized_at)
                VALUES (?,?,?,?,?,'PROJECT',?,'USD',?,NULL,0,?,0,
                  DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 15 MINUTE),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),
                  CASE WHEN ?='RELEASED' THEN UTC_TIMESTAMP(6) ELSE NULL END,
                  CASE WHEN ?='FINALIZED' THEN UTC_TIMESTAMP(6) ELSE NULL END)
                """, orgId, requestId, attemptId, periodId, budgetId, projectId,
                amount, status, status, status);
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static byte[] digest(int seed) {
        var bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((seed + i) % 251);
        }
        return bytes;
    }
}
