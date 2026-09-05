package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.reconciliation.application.CloseBlockerContext;
import com.aicostops.reconciliation.application.blockers.GatewayFinancialWorkBlockerProvider;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M12 close-safety on real MySQL: possible-billable Gateway requests after
 * DISPATCH_INTENT block normal Close; ACTIVE/PENDING_HOLD budget reservations
 * block normal Close; pre-dispatch or period-unscoped requests and
 * RELEASED/FINALIZED reservations do not.
 */
@SpringBootTest
@Tag("integration")
class GatewayFinancialWorkCloseIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GatewayFinancialWorkBlockerProvider provider;

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void dispatchIntentRequestBlocksClose() {
        var fixture = insertFixture();
        var requestId = insertGatewayRequest(fixture, "DISPATCH_INTENT", digest(1), digest(2));

        var result = provider.evaluate(context(fixture));

        assertThat(result.passed()).isFalse();
        assertThat(result.itemCount()).isEqualTo(1);
        assertThat(result.summary()).containsEntry("billingPeriodId", fixture.periodId());
        assertThat(requestId).isPositive();
    }

    @Test
    void transportCompletedAndPostDispatchFailuresBlockClose() {
        var fixture = insertFixture();
        insertGatewayRequest(fixture, "TRANSPORT_COMPLETED", digest(3), digest(4));
        insertGatewayRequest(fixture, "FAILED_AFTER_DISPATCH", digest(5), digest(6));

        var result = provider.evaluate(context(fixture));

        assertThat(result.passed()).isFalse();
        assertThat(result.itemCount()).isEqualTo(2);
    }

    @Test
    void validatedRequestDoesNotBlockClose() {
        var fixture = insertFixture();
        insertGatewayRequest(fixture, "VALIDATED", digest(7), digest(8));

        assertThat(provider.evaluate(context(fixture)).passed()).isTrue();
    }

    @Test
    void activeReservationBlocksCloseWithoutUnresolvedRequest() {
        var fixture = insertFullFixture();
        var requestId = insertGatewayRequest(fixture, "RESERVED", digest(21), digest(22));
        var attemptId = insertRouteAttempt(fixture, requestId, 1, "grd_21111111-1111-4111-8111-111111111111");
        insertReservation(fixture, requestId, attemptId, "ACTIVE");

        var result = provider.evaluate(context(fixture));

        assertThat(result.passed()).isFalse();
        assertThat(result.itemCount()).isEqualTo(1);
    }

    @Test
    void pendingHoldReservationBlocksClose() {
        var fixture = insertFullFixture();
        var requestId = insertGatewayRequest(fixture, "FAILED_AFTER_DISPATCH", digest(23), digest(24));
        // The FAILED_AFTER_DISPATCH request already blocks on its own; move it
        // pre-dispatch so only the PENDING_HOLD proves the reservation rule.
        jdbc.update("UPDATE gateway_request SET state='FAILED_PRE_DISPATCH' WHERE id=?", requestId);
        var attemptId = insertRouteAttempt(fixture, requestId, 1, "grd_24111111-1111-4111-8111-111111111111");
        insertReservation(fixture, requestId, attemptId, "PENDING_HOLD");

        var result = provider.evaluate(context(fixture));

        assertThat(result.passed()).isFalse();
        assertThat(result.itemCount()).isEqualTo(1);
    }

    @Test
    void releasedAndFinalizedReservationsDoNotBlockClose() {
        var fixture = insertFullFixture();
        var firstId = insertGatewayRequest(fixture, "FAILED_PRE_DISPATCH", digest(25), digest(26));
        var firstAttempt = insertRouteAttempt(fixture, firstId, 1, "grd_26111111-1111-4111-8111-111111111111");
        insertReservation(fixture, firstId, firstAttempt, "RELEASED");
        var secondId = insertGatewayRequest(fixture, "FAILED_PRE_DISPATCH", digest(27), digest(28));
        var secondAttempt = insertRouteAttempt(fixture, secondId, 1, "grd_28111111-1111-4111-8111-111111111111");
        insertReservation(fixture, secondId, secondAttempt, "FINALIZED");

        assertThat(provider.evaluate(context(fixture)).passed()).isTrue();
    }

    @Test
    void activeRequestWithOnlySafeReleasedAttemptDoesNotBlockClose() {
        var fixture = insertFullFixture();
        var requestId = insertGatewayRequest(fixture, "UPSTREAM_ACTIVE", digest(29), digest(30));
        var attemptId = insertRouteAttempt(fixture, requestId, 1,
                "grd_30111111-1111-4111-8111-111111111111");
        jdbc.update("UPDATE gateway_route_attempt SET status='SAFE_NO_BILLABLE_EXECUTION', "
                + "safety_reason_code='DNS_PRE_CONNECT' WHERE id=?", attemptId);
        insertReservation(fixture, requestId, attemptId, "RELEASED");

        assertThat(provider.evaluate(context(fixture)).passed()).isTrue();
    }

    @Test
    void failedTransportWithFinalUsageAndSettledFinancialTruthDoesNotBlockClose() {
        var fixture = insertFullFixture();
        var requestId = insertGatewayRequest(fixture, "FAILED_AFTER_DISPATCH", digest(31), digest(32));
        var attemptId = insertRouteAttempt(fixture, requestId, 1,
                "grd_32111111-1111-4111-8111-111111111111");
        var reservationId = insertReservation(fixture, requestId, attemptId, "FINALIZED");
        var usageFactId = insertFinalUsage(fixture, requestId, attemptId);
        insertSettledSettlement(fixture, requestId, attemptId, usageFactId, reservationId);

        var result = provider.evaluate(context(fixture));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void completedTransportWithFinalUsageAndSettledFinancialTruthDoesNotBlockClose() {
        var fixture = insertFullFixture();
        var requestId = insertGatewayRequest(fixture, "TRANSPORT_COMPLETED", digest(45), digest(46));
        var attemptId = insertRouteAttempt(fixture, requestId, 1,
                "grd_46111111-1111-4111-8111-111111111111");
        var reservationId = insertReservation(fixture, requestId, attemptId, "FINALIZED");
        var usageFactId = insertFinalUsage(fixture, requestId, attemptId);
        insertSettledSettlement(fixture, requestId, attemptId, usageFactId, reservationId);

        assertThat(provider.evaluate(context(fixture)).passed()).isTrue();
    }

    @Test
    void safeHistoricalAttemptPlusSettledSuccessorDoesNotBlockClose() {
        var fixture = insertFullFixture();
        var requestId = insertGatewayRequest(fixture, "TRANSPORT_COMPLETED", digest(47), digest(48));
        var safeAttempt = insertRouteAttempt(fixture, requestId, 1,
                "grd_48111111-1111-4111-8111-111111111111");
        jdbc.update("UPDATE gateway_route_attempt SET status='SAFE_NO_BILLABLE_EXECUTION', "
                + "safety_reason_code='DNS_PRE_CONNECT' WHERE id=?", safeAttempt);
        var releasedReservation = insertReservation(fixture, requestId, safeAttempt, "RELEASED");

        var completedAttempt = insertRouteAttempt(fixture, requestId, 2,
                "grd_49111111-1111-4111-8111-111111111111");
        jdbc.update("UPDATE gateway_route_attempt SET status='COMPLETED' WHERE id=?", completedAttempt);
        var finalizedReservation = insertReservation(fixture, requestId, completedAttempt, "FINALIZED");
        var usageFactId = insertFinalUsage(fixture, requestId, completedAttempt);
        insertSettledSettlement(fixture, requestId, completedAttempt, usageFactId, finalizedReservation);

        assertThat(releasedReservation).isPositive();
        assertThat(provider.evaluate(context(fixture)).passed()).isTrue();
    }

    @Test
    void currentFinalUsageWithoutSettlementStillBlocksClose() {
        var fixture = insertFullFixture();
        var requestId = insertGatewayRequest(fixture, "TRANSPORT_COMPLETED", digest(33), digest(34));
        var attemptId = insertRouteAttempt(fixture, requestId, 1,
                "grd_34111111-1111-4111-8111-111111111111");
        insertFinalUsage(fixture, requestId, attemptId);

        var result = provider.evaluate(context(fixture));

        assertThat(result.passed()).isFalse();
        assertThat(result.itemCount()).isEqualTo(1);
    }

    @Test
    void incompleteAndUnknownCurrentUsageBlockClose() {
        var fixture = insertFullFixture();
        var incompleteRequest = insertGatewayRequest(fixture, "TRANSPORT_COMPLETED",
                digest(35), digest(36));
        var incompleteAttempt = insertRouteAttempt(fixture, incompleteRequest, 1,
                "grd_36111111-1111-4111-8111-111111111111");
        insertUsage(fixture, incompleteRequest, incompleteAttempt, "INCOMPLETE");

        var unknownRequest = insertGatewayRequest(fixture, "TRANSPORT_COMPLETED",
                digest(37), digest(38));
        var unknownAttempt = insertRouteAttempt(fixture, unknownRequest, 1,
                "grd_38111111-1111-4111-8111-111111111111");
        insertUsage(fixture, unknownRequest, unknownAttempt, "UNKNOWN");

        var result = provider.evaluate(context(fixture));

        assertThat(result.passed()).isFalse();
        assertThat(result.itemCount()).isEqualTo(2);
    }

    @Test
    void finalUsageWithPendingSettlementBlocksClose() {
        assertFinalSettlementStatusBlocks("PENDING", 39);
    }

    @Test
    void finalUsageWithRetryableFailedSettlementBlocksClose() {
        assertFinalSettlementStatusBlocks("RETRYABLE_FAILED", 41);
    }

    @Test
    void finalUsageWithReconciliationRequiredSettlementBlocksClose() {
        assertFinalSettlementStatusBlocks("RECONCILIATION_REQUIRED", 43);
    }

    @Test
    void requestWithoutBillingPeriodDoesNotBlockClose() {
        var fixture = insertFixture();
        jdbc.update("""
                INSERT INTO gateway_request(
                  org_id,public_request_id,credential_id,principal_type,organization_member_id,
                  service_identity_id,project_id,financial_scope_type,financial_scope_id,
                  logical_model_id,api_surface,idempotency_key_digest,request_fingerprint,
                  request_hmac_version,state,billing_period_id,created_at,validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,0,'PROJECT',0,?,'CHAT_COMPLETIONS',
                  ?,?,1,'VALIDATED',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), "gwr_123e4567-e89b-42d3-a456-426614174000",
                fixture.credentialId(), fixture.serviceIdentityId(), fixture.modelId(),
                digest(9), digest(10));

        assertThat(provider.evaluate(context(fixture)).passed()).isTrue();
    }

    private CloseBlockerContext context(Fixture fixture) {
        return new CloseBlockerContext(fixture.orgId(), fixture.periodId(),
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"));
    }

    private long insertGatewayRequest(Fixture fixture, String state, byte[] idem, byte[] fp) {
        jdbc.update("""
                INSERT INTO gateway_request(
                  org_id,public_request_id,credential_id,principal_type,organization_member_id,
                  service_identity_id,project_id,financial_scope_type,financial_scope_id,
                  logical_model_id,api_surface,idempotency_key_digest,request_fingerprint,
                  request_hmac_version,state,billing_period_id,created_at,validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,0,'PROJECT',0,?,'CHAT_COMPLETIONS',
                  ?,?,1,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), "gwr_" + uuid(System.nanoTime()),
                fixture.credentialId(), fixture.serviceIdentityId(), fixture.modelId(),
                idem, fp, state, fixture.periodId());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertRouteAttempt(Fixture fixture, long requestId, int attemptNo, String decisionId) {
        jdbc.update("""
                INSERT INTO gateway_route_attempt(
                  org_id,request_id,attempt_no,route_decision_id,routing_policy_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,
                  safety_reason_code,provider_request_id,created_at,dispatch_intent_at,completed_at)
                VALUES (?,?,?, ?,NULL,?,?,?,'PLANNED',NULL,NULL,UTC_TIMESTAMP(6),NULL,NULL)
                """, fixture.orgId(), requestId, attemptNo, decisionId,
                fixture.providerAccountId(), fixture.providerModelId(), fixture.pricingVersionId());
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertReservation(Fixture fixture, long requestId, long attemptId, String status) {
        jdbc.update("""
                INSERT INTO budget_reservation(
                  org_id,request_id,route_attempt_id,billing_period_id,budget_id,
                  financial_scope_type,financial_scope_id,currency,
                  reserved_amount,commitment_id,commitment_backed_amount,
                  status,version,expires_at,created_at,updated_at,released_at,finalized_at)
                VALUES (?,?,?,?,?, 'PROJECT',0,'USD',
                  '10.00000000',NULL,0, ?,0,
                  DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 15 MINUTE),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL,NULL)
                """, fixture.orgId(), requestId, attemptId, fixture.periodId(), fixture.budgetId(),
                status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertFinalUsage(Fixture fixture, long requestId, long attemptId) {
        return insertUsage(fixture, requestId, attemptId, "FINAL");
    }

    private long insertUsage(Fixture fixture, long requestId, long attemptId, String status) {
        jdbc.update("""
                INSERT INTO gateway_usage_fact(
                  org_id,request_id,route_attempt_id,sequence,status,usage_effective_at,
                  usage_effective_at_source,pricing_version_id,currency,observed_at,created_at)
                VALUES (?,?,?,1,?,UTC_TIMESTAMP(6),
                  'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?,'USD',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), requestId, attemptId, status, fixture.pricingVersionId());
        var usageFactId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=?,current_usage_fact_id=? WHERE id=?",
                attemptId, usageFactId, requestId);
        return usageFactId;
    }

    private void assertFinalSettlementStatusBlocks(String status, int seed) {
        var fixture = insertFullFixture();
        var requestId = insertGatewayRequest(fixture, "TRANSPORT_COMPLETED",
                digest(seed), digest(seed + 1));
        var attemptId = insertRouteAttempt(fixture, requestId, 1,
                "grd_" + String.format("%02d", seed)
                        + "111111-1111-4111-8111-111111111111");
        var usageFactId = insertFinalUsage(fixture, requestId, attemptId);
        insertSettlement(fixture, requestId, attemptId, usageFactId, status);

        var result = provider.evaluate(context(fixture));

        assertThat(result.passed()).isFalse();
        assertThat(result.itemCount()).isEqualTo(1);
    }

    private void insertSettlement(Fixture fixture, long requestId, long attemptId,
            long usageFactId, String status) {
        jdbc.update("""
                INSERT INTO gateway_settlement(
                  org_id,settlement_key,request_id,route_attempt_id,usage_fact_id,reservation_id,
                  billing_period_id,financial_scope_type,financial_scope_id,provider_account_id,
                  provider_model_id,pricing_version_id,currency,status,attempt_count,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'PROJECT',0,?,?,?,'USD',?,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), "GATEWAY_REQUEST:" + requestId, requestId, attemptId,
                usageFactId, null, fixture.periodId(), fixture.providerAccountId(),
                fixture.providerModelId(), fixture.pricingVersionId(), status);
    }

    private void insertSettledSettlement(Fixture fixture, long requestId, long attemptId,
            long usageFactId, long reservationId) {
        jdbc.update("""
                INSERT INTO gateway_settlement(
                  org_id,settlement_key,request_id,route_attempt_id,usage_fact_id,reservation_id,
                  billing_period_id,financial_scope_type,financial_scope_id,provider_account_id,
                  provider_model_id,pricing_version_id,currency,status,attempt_count,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'PROJECT',0,?,?,?,'USD','PENDING',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), "GATEWAY_REQUEST:" + requestId, requestId, attemptId,
                usageFactId, reservationId, fixture.periodId(), fixture.providerAccountId(),
                fixture.providerModelId(), fixture.pricingVersionId());
        var settlementId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO ledger_posting(
                  org_id,posting_key,source_type,source_id,allocation_decision_id,billing_period_id,
                  status,posting_actor_type,posted_by_member_id,posted_at,created_at)
                VALUES (?,?, 'GATEWAY_SETTLEMENT',?,NULL,?,'POSTED','SYSTEM',NULL,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, fixture.orgId(), "GATEWAY_SETTLEMENT:" + settlementId, settlementId,
                fixture.periodId());
        var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                UPDATE gateway_settlement
                SET calculated_amount_raw='1.00000000',posted_amount='1.00000000',
                    rounding_delta=0,status='SETTLED',ledger_posting_id=?,settled_at=UTC_TIMESTAMP(6),
                    updated_at=UTC_TIMESTAMP(6)
                WHERE id=?
                """, postingId, settlementId);
    }

    private Fixture insertFullFixture() {
        var base = insertFixture();
        jdbc.update("""
                INSERT INTO provider_account(
                  org_id,provider_code,display_name,external_account_ref,status,metadata_json,
                  created_at,updated_at)
                VALUES (?,'MIMO','Close Provider','CLOSE-ACCT','ACTIVE',NULL,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, base.orgId());
        var providerAccountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
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
                """, base.modelId());
        var providerModelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO pricing_version(
                  org_id,provider_account_id,provider_model_id,version,currency,
                  effective_from,effective_to,status,created_at,activated_at,retired_at)
                VALUES (?,?,?,1,'USD','2026-08-01 00:00:00.000000',NULL,'ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
                """, base.orgId(), providerAccountId, providerModelId);
        var pricingVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO budget(
                  org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?, 'PROJECT',0,'USD','100.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, base.orgId(), base.periodId());
        var budgetId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new Fixture(base.orgId(), base.periodId(), base.serviceIdentityId(), base.modelId(),
                base.credentialId(), providerAccountId, providerModelId, pricingVersionId, budgetId);
    }

    private Fixture insertFixture() {
        var suffix = "gw-close-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "M11 " + suffix, suffix);
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
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Close Test Service','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "svc-" + suffix);
        var serviceIdentityId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO model_catalog(
                  model_key,name,status,capabilities_json,default_max_output_tokens,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,'Close Test Model','ACTIVE',JSON_OBJECT(),8192,131072,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "close-model-" + suffix);
        var modelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_credential(
                  org_id,credential_prefix,secret_digest,secret_digest_version,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,budget_enforcement_mode,status,expires_at,
                  predecessor_credential_id,created_at,updated_at,revoked_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,0,'PROJECT',0,'OPTIONAL','ACTIVE',
                  NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
                """, orgId, "0123456789ab", digest(11), serviceIdentityId);
        var credentialId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return new Fixture(orgId, periodId, serviceIdentityId, modelId, credentialId,
                -1L, -1L, -1L, -1L);
    }

    private static String uuid(long seed) {
        var digits = Math.floorMod(seed, 1_000_000_000_000L);
        return "123e4567-e89b-42d3-a456-" + String.format("%012d", digits);
    }

    private static byte[] digest(int seed) {
        var bytes = new byte[32];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) ((seed + i) % 251);
        }
        return bytes;
    }

    @Test
    void validResolutionClearsOnlyItsOwnRequestBlocker() {
        var fixture = insertFullFixture();
        var requestA = insertGatewayRequest(fixture, "TRANSPORT_COMPLETED", digest(31), digest(32));
        var requestB = insertGatewayRequest(fixture, "TRANSPORT_COMPLETED", digest(33), digest(34));
        var attemptA = insertRouteAttempt(fixture, requestA, 1, "grd_a1");
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptA, requestA);
        var attemptB = insertRouteAttempt(fixture, requestB, 1, "grd_b1");
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptB, requestB);
        var runId = insertRun(fixture);
        insertResolution(fixture, runId, requestA, "NO_CHARGE_CONFIRMED", "NONE", null, null);

        var result = provider.evaluate(context(fixture));

        assertThat(result.passed()).isFalse();
        assertThat(result.itemCount()).isEqualTo(1);
    }

    @Test
    void noChargeResolutionWithReleasedReservationPasses() {
        var fixture = insertFullFixture();
        var requestId = insertGatewayRequest(fixture, "FAILED_AFTER_DISPATCH", digest(35), digest(36));
        jdbc.update("UPDATE gateway_request SET state='FAILED_PRE_DISPATCH' WHERE id=?", requestId);
        var attemptId = insertRouteAttempt(fixture, requestId, 1, "grd_c1");
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
        var reservationId = insertReservation(fixture, requestId, attemptId, "RELEASED");
        var runId = insertRun(fixture);
        insertResolution(fixture, runId, requestId, "NO_CHARGE_CONFIRMED", "RELEASED",
                reservationId, null);

        assertThat(provider.evaluate(context(fixture)).passed()).isTrue();
    }

    @Test
    void statementResolutionWithFinalizedReservationPasses() {
        var fixture = insertFullFixture();
        var requestId = insertGatewayRequest(fixture, "FAILED_AFTER_DISPATCH", digest(37), digest(38));
        jdbc.update("UPDATE gateway_request SET state='FAILED_PRE_DISPATCH' WHERE id=?", requestId);
        var attemptId = insertRouteAttempt(fixture, requestId, 1, "grd_d1");
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
        var reservationId = insertReservation(fixture, requestId, attemptId, "FINALIZED");
        var runId = insertRun(fixture);
        jdbc.update("""
                INSERT INTO reconciliation_adjustment(
                  org_id,reconciliation_run_id,reconciliation_case_id,adjustment_key,
                  adjustment_scope,provider_account_id,currency,amount,adjustment_period_id,
                  gateway_request_id,gateway_route_attempt_id,created_by_member_id,reason_code,
                  reason_note,created_at)
                SELECT ?,?,NULL,?,'GATEWAY_REQUEST',?,?, '2.00000000',?,?,?,m.id,
                  'STATEMENT_EVIDENCE','Reviewed',UTC_TIMESTAMP(6)
                FROM provider_account pa
                JOIN organization_member m ON m.org_id=pa.org_id
                WHERE pa.id=?
                """, fixture.orgId(), runId, "adj-close-" + System.nanoTime(),
                fixture.providerAccountId(), "USD", fixture.periodId(), requestId, attemptId,
                fixture.providerAccountId());
        var adjustmentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        insertResolution(fixture, runId, requestId, "STATEMENT_ADJUSTMENT_POSTED", "FINALIZED",
                reservationId, adjustmentId);

        assertThat(provider.evaluate(context(fixture)).passed()).isTrue();
    }

    @Test
    void resolutionWithContradictingEffectiveReservationStillBlocks() {
        var fixture = insertFullFixture();
        var requestId = insertGatewayRequest(fixture, "FAILED_AFTER_DISPATCH", digest(39), digest(40));
        jdbc.update("UPDATE gateway_request SET state='FAILED_PRE_DISPATCH' WHERE id=?", requestId);
        var attemptId = insertRouteAttempt(fixture, requestId, 1, "grd_e1");
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
        var reservationId = insertReservation(fixture, requestId, attemptId, "ACTIVE");
        var runId = insertRun(fixture);
        insertResolution(fixture, runId, requestId, "NO_CHARGE_CONFIRMED", "RELEASED",
                reservationId, null);

        var result = provider.evaluate(context(fixture));

        assertThat(result.passed()).isFalse();
        assertThat(result.itemCount()).isEqualTo(1);
    }

    private long insertRun(Fixture fixture) {
        jdbc.update("""
                INSERT INTO reconciliation_run(org_id,billing_period_id,status,algorithm_version,
                  tolerance_amount,basis_hash,summary_json,created_by_member_id,started_at,
                  finished_at,created_at,updated_at)
                VALUES (?,?,'COMPLETED','M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2','0.00000000',
                  ?,JSON_OBJECT(),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6))
                """.replace("?,JSON_OBJECT()", "?,JSON_OBJECT()"), fixture.orgId(),
                fixture.periodId(), "9".repeat(64), orgMemberId(fixture));
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long orgMemberId(Fixture fixture) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,
                  created_at,updated_at)
                VALUES (?,?, 'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """.replace("?, ?, 'ACTIVE'", "?,?,'ACTIVE'"), "close-m15-" + System.nanoTime()
                + "@example.test", "close-m15");
        var userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, fixture.orgId(), userId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertResolution(Fixture fixture, long runId, long requestId,
            String resolutionType, String reservationOutcome, Long reservationId,
            Long adjustmentId) {
        jdbc.update("""
                INSERT INTO gateway_financial_resolution(
                  org_id,reconciliation_run_id,reconciliation_case_id,request_id,route_attempt_id,
                  usage_fact_id,gateway_settlement_id,statement_charge_fact_id,
                  reconciliation_adjustment_id,reservation_id,resolution_type,reservation_outcome,
                  resolved_by_member_id,reason_code,reason_note,resolved_at,created_at)
                SELECT ?,?,NULL,?,gr.current_route_attempt_id,NULL,NULL,NULL,?,?,
                  ?,?,m.id,'POSITIVE_EVIDENCE','Reviewed',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)
                FROM gateway_request gr
                JOIN organization_member m ON m.org_id=gr.org_id
                WHERE gr.id=?
                """, fixture.orgId(), runId, requestId, adjustmentId, reservationId,
                resolutionType, reservationOutcome, requestId);
    }

    private record Fixture(long orgId, long periodId, long serviceIdentityId, long modelId,
            long credentialId, long providerAccountId, long providerModelId,
            long pricingVersionId, long budgetId) {
    }
}
