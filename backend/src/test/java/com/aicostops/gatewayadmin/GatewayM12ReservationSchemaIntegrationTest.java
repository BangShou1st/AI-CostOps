package com.aicostops.gatewayadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V19 contract for the M12 MySQL-authoritative budget reservation
 * (AIC-087 / AIC-092 section 12). Real MySQL 8.4: the empty database migrates
 * through the current V21 schema, the reservation table carries per-route-attempt
 * uniqueness plus one-effective-hold-per-request uniqueness, and the M13-B
 * settlement table is present as a forward additive migration.
 */
@SpringBootTest
@Tag("integration")
class GatewayM12ReservationSchemaIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void emptyDatabaseMigratesThroughV21AndV1TablesRemainUsable() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                Integer.class)).isEqualTo(22);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '20' AND success = 1",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '21' AND success = 1",
                Integer.class)).isEqualTo(1);

        assertThat(queryTables()).contains("budget_reservation", "gateway_settlement");
        assertThat(queryTables())
                .contains("routing_policy", "routing_policy_candidate");
    }

    @Test
    void routeAttemptUniquenessIsEnforced() {
        var fixture = insertFixture("route-uq");

        insertReservation(fixture, fixture.attemptId(), "ACTIVE", "10.00000000");

        assertThatThrownBy(() -> insertReservation(fixture, fixture.attemptId(), "ACTIVE", "5.00000000"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_budget_reservation_route");
    }

    @Test
    void onlyOneEffectiveHoldPerRequest() {
        var fixture = insertFixture("effective");
        var secondAttemptId = insertRouteAttempt(fixture, 2, "grd_e2e2e2e2-e2e2-4e2e-8e2e-e2e2e2e2e2e2");

        insertReservation(fixture, fixture.attemptId(), "ACTIVE", "10.00000000");

        // A second ACTIVE hold for the same request (even on another attempt)
        // must fail on the effective-slot uniqueness, not silently double-hold.
        assertThatThrownBy(() -> insertReservation(fixture, secondAttemptId, "ACTIVE", "5.00000000"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_budget_reservation_effective");

        // PENDING_HOLD is equally effective: it also collides.
        assertThatThrownBy(() -> insertReservation(fixture, secondAttemptId, "PENDING_HOLD", "5.00000000"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_budget_reservation_effective");
    }

    @Test
    void releasedAndFinalizedDoNotBlockANewEffectiveHold() {
        var fixture = insertFixture("terminal");
        var secondAttemptId = insertRouteAttempt(fixture, 2, "grd_f3f3f3f3-f3f3-4f3f-8f3f-f3f3f3f3f3f3");

        insertReservation(fixture, fixture.attemptId(), "RELEASED", "10.00000000");

        // Terminal holds free the effective slot for a later attempt.
        insertReservation(fixture, secondAttemptId, "ACTIVE", "5.00000000");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM budget_reservation
                WHERE org_id=? AND status='ACTIVE'
                """, Integer.class, fixture.orgId())).isOne();
    }

    @Test
    void reservedAmountMustBePositive() {
        var fixture = insertFixture("amount");

        // Zero and negative reservations are both rejected. A negative row
        // violates two CHECKs at once (reserved_amount and the backed bound),
        // and MySQL reports only one of them, so the test asserts rejection
        // plus the existence of the specific CHECK rather than the message.
        assertThatThrownBy(() -> insertReservation(fixture, fixture.attemptId(), "ACTIVE", "0.00000000"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_budget_reservation");

        var secondAttemptId = insertRouteAttempt(fixture, 2, "grd_a4a4a4a4-a4a4-44a4-84a4-a4a4a4a4a4a4");
        assertThatThrownBy(() -> insertReservation(fixture, secondAttemptId, "ACTIVE", "-1.00000000"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_budget_reservation");

        assertThat(checkConstraintClause("chk_budget_reservation_reserved_amount"))
                .contains("reserved_amount")
                .contains(">");
    }

    @Test
    void commitmentBackedAmountIsBoundedByReservedAmount() {
        var fixture = insertFixture("commitment");
        var secondAttemptId = insertRouteAttempt(fixture, 2, "grd_b5b5b5b5-b5b5-4b5b-8b5b-b5b5b5b5b5b5");

        // Backed amount above the reservation is a double-counting bug: reject.
        assertThatThrownBy(() -> insertReservationWithBacked(
                fixture, fixture.attemptId(), "ACTIVE", "10.00000000", "10.00000001"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_budget_reservation_commitment_backed");

        assertThatThrownBy(() -> insertReservationWithBacked(
                fixture, secondAttemptId, "ACTIVE", "10.00000000", "-0.00000001"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_budget_reservation_commitment_backed");
    }

    @Test
    void statusCheckRejectsUnknownStatus() {
        var fixture = insertFixture("status");

        assertThatThrownBy(() -> insertReservation(fixture, fixture.attemptId(), "NOT_A_STATUS", "1.00000000"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_budget_reservation_status");
    }

    @Test
    void currencyAndScopeChecksRejectInvalidValues() {
        var fixture = insertFixture("currency");

        assertThatThrownBy(() -> insertReservationWithCurrency(
                fixture, fixture.attemptId(), "ACTIVE", "1.00000000", "US"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_budget_reservation_currency");

        var secondAttemptId = insertRouteAttempt(fixture, 2, "grd_c6c6c6c6-c6c6-46c6-86c6-c6c6c6c6c6c6");
        assertThatThrownBy(() -> insertReservationWithScope(
                fixture, secondAttemptId, "ACTIVE", "1.00000000", "ORG"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_budget_reservation_scope_type");
    }

    @Test
    void sameOrgForeignKeyViolationFails() {
        var fixture = insertFixture("fk-org");
        var otherOrgId = insertOtherOrganization("fk-org-other");

        assertThatThrownBy(() -> jdbc.update(insertReservationSql(),
                otherOrgId, fixture.requestId(), fixture.attemptId(), fixture.periodId(),
                fixture.budgetId(), "PROJECT", 0L, "USD", "1.00000000",
                "ACTIVE", 0L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_budget_reservation_request_org");
    }

    @Test
    void exactColumnTypesMatchAic092() {
        assertThat(columnType("budget_reservation", "reserved_amount")).isEqualTo("decimal(20,8)");
        assertThat(columnType("budget_reservation", "commitment_backed_amount")).isEqualTo("decimal(20,8)");
        assertThat(columnType("budget_reservation", "currency")).isEqualTo("char(3)");
        assertThat(columnType("budget_reservation", "status")).isEqualTo("varchar(32)");
        assertThat(columnType("budget_reservation", "version")).isEqualTo("bigint");
        assertThat(columnType("budget_reservation", "expires_at")).isEqualTo("datetime(6)");
        assertThat(columnType("budget_reservation", "effective_slot")).isEqualTo("tinyint");
        assertThat(columnType("budget_reservation", "route_attempt_id")).isEqualTo("bigint");
    }

    private List<String> queryTables() {
        return jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema=DATABASE()
                """, String.class);
    }

    private String columnType(String table, String column) {
        return jdbc.queryForObject("""
                SELECT COLUMN_TYPE FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name=? AND column_name=?
                """, String.class, table, column);
    }

    private String checkConstraintClause(String constraintName) {
        return jdbc.queryForObject("""
                SELECT CHECK_CLAUSE FROM information_schema.check_constraints
                WHERE constraint_schema=DATABASE() AND constraint_name=?
                """, String.class, constraintName);
    }

    private void insertReservation(Fixture fixture, long attemptId, String status, String amount) {
        insertReservationWithScope(fixture, attemptId, status, amount, "PROJECT");
    }

    private void insertReservationWithScope(
            Fixture fixture, long attemptId, String status, String amount, String scopeType) {
        insertReservationWithCurrency(fixture, attemptId, status, amount, "USD", scopeType);
    }

    private void insertReservationWithCurrency(
            Fixture fixture, long attemptId, String status, String amount, String currency) {
        insertReservationWithCurrency(fixture, attemptId, status, amount, currency, "PROJECT");
    }

    private void insertReservationWithCurrency(
            Fixture fixture, long attemptId, String status, String amount,
            String currency, String scopeType) {
        jdbc.update(insertReservationSql(),
                fixture.orgId(), fixture.requestId(), attemptId, fixture.periodId(),
                fixture.budgetId(), scopeType, 0L, currency, amount, status, 0L);
    }

    private void insertReservationWithBacked(
            Fixture fixture, long attemptId, String status, String amount, String backed) {
        jdbc.update("""
                INSERT INTO budget_reservation(
                  org_id,request_id,route_attempt_id,billing_period_id,budget_id,
                  financial_scope_type,financial_scope_id,currency,
                  reserved_amount,commitment_id,commitment_backed_amount,
                  status,version,expires_at,created_at,updated_at,released_at,finalized_at)
                VALUES (?,?,?,?,?,?,? ,? ,?,NULL,?, ?,?,
                  DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 15 MINUTE),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
                """,
                fixture.orgId(), fixture.requestId(), attemptId, fixture.periodId(),
                fixture.budgetId(), "PROJECT", 0L, "USD", amount, backed, status, 0L);
    }

    private static String insertReservationSql() {
        return """
                INSERT INTO budget_reservation(
                  org_id,request_id,route_attempt_id,billing_period_id,budget_id,
                  financial_scope_type,financial_scope_id,currency,
                  reserved_amount,commitment_id,commitment_backed_amount,
                  status,version,expires_at,created_at,updated_at,released_at,finalized_at)
                VALUES (?,?,?,?,?,?,? ,? ,?,NULL,0, ?,?,
                  DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 15 MINUTE),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
                """;
    }

    private long insertRouteAttempt(Fixture fixture, int attemptNo, String routeDecisionId) {
        jdbc.update("""
                INSERT INTO gateway_route_attempt(
                  org_id,request_id,attempt_no,route_decision_id,routing_policy_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,
                  safety_reason_code,provider_request_id,created_at,dispatch_intent_at,completed_at)
                VALUES (?,?,?,?,NULL,?,?,?,'PLANNED',NULL,NULL,UTC_TIMESTAMP(6),NULL,NULL)
                """, fixture.orgId(), fixture.requestId(), attemptNo, routeDecisionId,
                fixture.providerAccountId(), fixture.providerModelId(), fixture.pricingVersionId());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private Fixture insertFixture(String prefix) {
        var suffix = prefix + "-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "M12 " + suffix, suffix);
        var orgId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO billing_period(
                  org_id,period_start,period_end,status,close_generation,closing_started_at,
                  closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?,'2026-08-01 00:00:00.000000','2026-09-01 00:00:00.000000',
                  'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        var periodId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO budget(
                  org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?, 'PROJECT',0,'USD','100.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId);
        var budgetId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO provider_account(
                  org_id,provider_code,display_name,external_account_ref,status,metadata_json,
                  created_at,updated_at)
                VALUES (?,'MIMO',?,'MIMO-ACCT','ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix);
        var providerAccountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO model_catalog(
                  model_key,name,status,capabilities_json,default_max_output_tokens,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,'M12 Logical Model','ACTIVE',JSON_OBJECT(),8192,131072,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "default-chat-" + suffix);
        var modelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO provider_catalog(
                  provider_code,name,adapter_code,base_url,status,capabilities_json,
                  created_at,updated_at)
                VALUES ('MIMO','MiMo','MIMO','https://api.xiaomimimo.com/v1','ACTIVE',JSON_OBJECT(),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        jdbc.update("""
                INSERT INTO provider_model(
                  provider_code,model_id,provider_model_name,status,routing_eligible,
                  capabilities_json,created_at,updated_at)
                VALUES ('MIMO',?,'mimo-v2.5-pro','ACTIVE',TRUE,JSON_OBJECT(),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, modelId);
        var providerModelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'M12 Service','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "svc-" + suffix);
        var serviceIdentityId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        var credentialPrefix = uniquePrefix(prefix);
        jdbc.update("""
                INSERT INTO gateway_credential(
                  org_id,credential_prefix,secret_digest,secret_digest_version,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,budget_enforcement_mode,status,expires_at,
                  predecessor_credential_id,created_at,updated_at,revoked_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,0,'PROJECT',0,'REQUIRED','ACTIVE',
                  NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
                """, orgId, credentialPrefix, digest(7), serviceIdentityId);
        var credentialId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO gateway_credential_model(credential_id,org_id,model_id,status,created_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, credentialId, orgId, modelId);

        jdbc.update("""
                INSERT INTO provider_credential(
                  org_id,provider_account_id,credential_type,ciphertext,nonce,
                  encryption_key_version,safe_label,status,predecessor_credential_id,
                  created_at,rotated_at,revoked_at)
                VALUES (?,?,'API_KEY',?,'123456789012',1,'M12 label','ACTIVE',NULL,
                  UTC_TIMESTAMP(6),NULL,NULL)
                """, orgId, providerAccountId, digest(13));

        jdbc.update("""
                INSERT INTO pricing_version(
                  org_id,provider_account_id,provider_model_id,version,currency,
                  effective_from,effective_to,status,created_at,activated_at,retired_at)
                VALUES (?,?,?,1,'USD','2026-08-01 00:00:00.000000',NULL,'ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
                """, orgId, providerAccountId, providerModelId);
        var pricingVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price)
                VALUES (?,?,'INPUT_TOKEN',1000000,'30.00000000')
                """, orgId, pricingVersionId);
        jdbc.update("""
                INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price)
                VALUES (?,?,'OUTPUT_TOKEN',1000000,'60.00000000')
                """, orgId, pricingVersionId);

        var publicId = "gwr_" + java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO gateway_request(
                  org_id,public_request_id,credential_id,principal_type,organization_member_id,
                  service_identity_id,project_id,financial_scope_type,financial_scope_id,
                  logical_model_id,api_surface,idempotency_key_digest,request_fingerprint,
                  request_hmac_version,state,billing_period_id,current_route_attempt_id,
                  current_usage_fact_id,created_at,validated_at,dispatch_intent_at,terminal_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,0,'PROJECT',0,?,'CHAT_COMPLETIONS',
                  ?,?,1,'VALIDATED',?,
                  NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL,UTC_TIMESTAMP(6))
                """, orgId, publicId, credentialId, serviceIdentityId, modelId,
                digest(61), digest(62), null);
        var requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        var fixture = new Fixture(orgId, periodId, budgetId, requestId, -1L,
                providerAccountId, providerModelId, pricingVersionId);
        var attemptId = insertRouteAttempt(fixture, 1, "grd_" + java.util.UUID.randomUUID());
        return new Fixture(orgId, periodId, budgetId, requestId, attemptId,
                providerAccountId, providerModelId, pricingVersionId);
    }

    private long insertOtherOrganization(String prefix) {
        var suffix = prefix + "-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "M12 " + suffix, suffix);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static String uniquePrefix(String seed) {
        var alphabet = "0123456789abcdefghjkmnpqrstvwxyz";
        var hash = seed.hashCode() ^ System.nanoTime();
        var builder = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            builder.append(alphabet.charAt(Math.floorMod(hash, alphabet.length())));
            hash = hash * 31 + 7;
        }
        return builder.toString().toLowerCase();
    }

    private static byte[] digest(int seed) {
        var bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((seed + i) % 251);
        }
        return bytes;
    }

    private record Fixture(
            long orgId,
            long periodId,
            long budgetId,
            long requestId,
            long attemptId,
            long providerAccountId,
            long providerModelId,
            long pricingVersionId) {
    }
}
