package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.reconciliation.application.GatewayFinancialResolutionService;
import com.aicostops.reconciliation.application.GatewayFinancialResolutionService.GatewayResolutionCommand;
import com.aicostops.reconciliation.application.GatewayResolutionFailureInjector;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Injected failure after any gateway financial resolution mutation boundary
 * rolls the whole transaction back: no partial adjustment, Ledger posting,
 * Budget Actual, Commitment usage, Reservation transition, Audit, resolution
 * or resolution-evidence state may survive, and no idempotency reservation is
 * left behind as committed/provisional garbage.
 */
@SpringBootTest
@Tag("integration")
class GatewayFinancialResolutionRollbackIntegrationTest extends AllocationApiTestSupport {

    private static final String AUG_START = "2026-08-01 00:00:00.000000";
    private static final String SEP_START = "2026-09-01 00:00:00.000000";
    private static final String NO_CHARGE_PROOF = "PROVIDER_PORTAL_CONFIRMED_NO_CHARGE";

    @Autowired GatewayFinancialResolutionService resolutions;
    @MockitoBean GatewayResolutionFailureInjector failureInjector;

    private AuthenticatedUser actor;
    private long periodId;
    private long runId;

    @BeforeEach
    void rollbackSetup() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN (
                  'RECONCILIATION_RESOLVE','LEDGER_CORRECT')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        actor = new AuthenticatedUser(actorUserId, 7);

        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
                VALUES (?,?,?,?,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, AUG_START, SEP_START, "OPEN");
        periodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
        jdbc.update("""
                INSERT INTO reconciliation_run(org_id,billing_period_id,status,algorithm_version,
                  tolerance_amount,basis_hash,summary_json,created_by_member_id,started_at,
                  finished_at,created_at,updated_at)
                VALUES (?,?,'COMPLETED','M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2','0.00000000',
                  ?,JSON_OBJECT(),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6))
                """, orgId, periodId, "b".repeat(64), actorMemberId);
        runId = jdbc.queryForObject(
                "SELECT id FROM reconciliation_run WHERE org_id=? AND billing_period_id=? "
                        + "ORDER BY id DESC LIMIT 1",
                Long.class, orgId, periodId);
    }

    @Test
    void failureAfterAdjustmentInsertRollsEverythingBack() {
        var fixture = insertUnknownUsageFixture(false, false);
        failAt("ADJUSTMENT_INSERTED");

        assertThatThrownBy(() -> resolveStatement(fixture, "rb-adj"))
                .isInstanceOf(RuntimeException.class);
        assertZeroResidue();
    }

    @Test
    void failureAfterLedgerEntryInsertRollsEverythingBack() {
        var fixture = insertUnknownUsageFixture(false, false);
        failAt("LEDGER_ENTRY_INSERTED");

        assertThatThrownBy(() -> resolveStatement(fixture, "rb-ledger"))
                .isInstanceOf(RuntimeException.class);
        assertZeroResidue();
    }

    @Test
    void failureAfterBudgetActualRollsEverythingBack() {
        var fixture = insertUnknownUsageFixture(true, false);
        failAt("BUDGET_ACTUAL_MUTATED");

        assertThatThrownBy(() -> resolveStatement(fixture, "rb-budget"))
                .isInstanceOf(RuntimeException.class);
        assertZeroResidue();
        assertThat(jdbc.queryForObject(
                "SELECT actual_amount FROM budget WHERE scope_type='PROJECT' AND scope_id=?",
                java.math.BigDecimal.class, projectId)).isEqualByComparingTo("0.00000000");
    }

    @Test
    void failureAfterCommitmentConsumeRollsEverythingBack() {
        var fixture = insertUnknownUsageFixture(true, true);
        failAt("COMMITMENT_CONSUMED");

        assertThatThrownBy(() -> resolveStatement(fixture, "rb-commitment"))
                .isInstanceOf(RuntimeException.class);
        assertZeroResidue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM budget_reservation WHERE id=?", String.class,
                fixture.reservationId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT remaining_amount FROM budget_commitment WHERE id=?",
                java.math.BigDecimal.class, fixture.commitmentId()))
                .isEqualByComparingTo("5.00000000");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_commitment_usage WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void failureAfterReservationFinalizedRollsEverythingBack() {
        var fixture = insertUnknownUsageFixture(true, false);
        failAt("RESERVATION_TRANSITIONED");

        assertThatThrownBy(() -> resolveStatement(fixture, "rb-reservation"))
                .isInstanceOf(RuntimeException.class);
        assertZeroResidue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM budget_reservation WHERE id=?", String.class,
                fixture.reservationId())).isEqualTo("ACTIVE");
    }

    @Test
    void failureAfterAuditRollsEverythingBack() {
        var fixture = insertUnknownUsageFixture(false, false);
        failAt("AUDIT_WRITTEN");

        assertThatThrownBy(() -> resolveStatement(fixture, "rb-audit"))
                .isInstanceOf(RuntimeException.class);
        assertZeroResidue();
    }

    @Test
    void failureAfterResolutionInsertRollsEverythingBack() {
        var fixture = insertUnknownUsageFixture(false, false);
        failAt("RESOLUTION_INSERTED");

        assertThatThrownBy(() -> resolveStatement(fixture, "rb-resolution"))
                .isInstanceOf(RuntimeException.class);
        assertZeroResidue();
    }

    @Test
    void failureAfterResolutionEvidenceRollsEverythingBack() {
        var fixture = insertUnknownUsageFixture(false, false);
        failAt("RESOLUTION_EVIDENCE_INSERTED");

        assertThatThrownBy(() -> resolveStatement(fixture, "rb-evidence"))
                .isInstanceOf(RuntimeException.class);
        assertZeroResidue();
    }

    @Test
    void failureAfterChargeOwnershipDispositionRollsEverythingBack() {
        var fixture = insertUnknownUsageFixture(false, false);
        failAt("CHARGE_DISPOSITION_INSERTED");

        assertThatThrownBy(() -> resolveStatement(fixture, "rb-disposition"))
                .isInstanceOf(RuntimeException.class);
        assertZeroResidue();
        // The financial ownership claim on the statement charge must be gone.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void failureDuringNoChargeReleaseRollsEverythingBack() {
        var fixture = insertUnknownUsageFixture(true, false);
        failAt("RESERVATION_TRANSITIONED");

        assertThatThrownBy(() -> resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "NO_CHARGE_CONFIRMED", null, "rollback-portal-proof-1", null,
                        NO_CHARGE_PROOF, "Provider confirmed no charge"),
                "rb-nocharge"))
                .isInstanceOf(RuntimeException.class);
        assertZeroResidue();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM budget_reservation WHERE id=?", String.class,
                fixture.reservationId())).isEqualTo("ACTIVE");
    }

    // ------------------------------------------------------------------
    // fixtures and helpers
    // ------------------------------------------------------------------

    private record RollbackFixture(long requestId, Long reservationId, Long commitmentId,
            long providerAccountId) {
    }

    private void failAt(String checkpoint) {
        doAnswer((Answer<Void>) invocation -> {
            throw new IllegalStateException("injected failure at " + checkpoint);
        }).when(failureInjector).after(checkpoint);
    }

    private void resolveStatement(RollbackFixture fixture, String key) {
        var chargeId = insertConfirmedCharge(fixture.providerAccountId());
        resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Reviewed statement line"),
                key);
    }

    private void assertZeroResidue() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_adjustment WHERE org_id=?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=? AND "
                        + "source_type IN ('RECONCILIATION_ADJUSTMENT','GATEWAY_SETTLEMENT')",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entry WHERE org_id=?", Long.class, orgId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_financial_resolution WHERE org_id=?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_evidence WHERE org_id=? "
                        + "AND match_kind <> 'GATEWAY_UNRESOLVED'",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=?",
                Long.class, orgId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_idempotency WHERE org_id=? AND operation=?",
                Long.class, orgId, "GATEWAY_FINANCIAL_RESOLUTION")).isZero();
    }

    /** UNKNOWN usage request; withBudget adds budget+reservation; withCommitment binds one. */
    private RollbackFixture insertUnknownUsageFixture(boolean withBudget,
            boolean withCommitment) {
        var gateway = insertGatewayChain("rb-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 20));
        var requestId = gateway.requestId();
        var attemptId = gateway.attemptId();
        jdbc.update("""
                INSERT INTO gateway_usage_fact(org_id,request_id,route_attempt_id,sequence,
                  status,usage_effective_at,usage_effective_at_source,pricing_version_id,
                  currency,observed_at,created_at)
                VALUES (?,?,?,1,'UNKNOWN',UTC_TIMESTAMP(6),
                  'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?,'USD',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, requestId, attemptId, gateway.pricingVersionId());
        var usageFactId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_usage_fact_id=? WHERE id=?",
                usageFactId, requestId);
        jdbc.update("""
                INSERT INTO reconciliation_evidence(org_id,reconciliation_run_id,evidence_key,
                  provider_account_id,currency,match_kind,gateway_request_id,
                  gateway_route_attempt_id,created_at)
                VALUES (?,?,CONCAT('GATEWAY_UNRESOLVED:REQUEST:',?),?,?,'GATEWAY_UNRESOLVED',
                  ?,?,UTC_TIMESTAMP(6))
                """, orgId, runId, requestId, gateway.providerAccountId(), "USD", requestId,
                attemptId);
        Long reservationId = null;
        Long commitmentId = null;
        if (withBudget) {
            jdbc.update("""
                    INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                      total_amount,actual_amount,committed_amount,status,version,created_at,
                      updated_at)
                    VALUES (?,?,'PROJECT',?,'USD','20.00000000',0,0,'ACTIVE',0,
                      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, orgId, periodId, projectId);
            var budgetId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            if (withCommitment) {
                jdbc.update("""
                        INSERT INTO budget_commitment(org_id,budget_id,status,requested_amount,
                          approved_amount,remaining_amount,version,created_at,updated_at)
                        VALUES (?,?,'ACTIVE','5.00000000','5.00000000','5.00000000',0,
                          UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                        """, orgId, budgetId);
                commitmentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                jdbc.update("""
                        INSERT INTO budget_reservation(org_id,request_id,route_attempt_id,
                          billing_period_id,budget_id,financial_scope_type,financial_scope_id,
                          currency,reserved_amount,commitment_id,commitment_backed_amount,
                          status,version,expires_at,created_at,updated_at)
                        VALUES (?,?,?,?,?,'PROJECT',?,'USD','5.00000000',?,?,'ACTIVE',0,
                          UTC_TIMESTAMP(6) + INTERVAL 7 DAY,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                        """, orgId, requestId, attemptId, periodId, budgetId, projectId,
                        commitmentId, new java.math.BigDecimal("2.00000000"));
            } else {
                jdbc.update("""
                        INSERT INTO budget_reservation(org_id,request_id,route_attempt_id,
                          billing_period_id,budget_id,financial_scope_type,financial_scope_id,
                          currency,reserved_amount,commitment_id,commitment_backed_amount,
                          status,version,expires_at,created_at,updated_at)
                        VALUES (?,?,?,?,?,'PROJECT',?,'USD','5.00000000',NULL,0,'ACTIVE',0,
                          UTC_TIMESTAMP(6) + INTERVAL 7 DAY,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                        """, orgId, requestId, attemptId, periodId, budgetId, projectId);
            }
            reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        }
        return new RollbackFixture(requestId, reservationId, commitmentId,
                gateway.providerAccountId());
    }

    private record GatewayChain(long requestId, long attemptId, long providerAccountId,
            long pricingVersionId) {
    }

    private GatewayChain insertGatewayChain(String suffix) {
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "rb-svc-" + suffix, suffix);
        var serviceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,status,capabilities_json,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),1024,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "rb-model-" + suffix, suffix);
        var modelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        var providerCode = "MIMO-" + suffix.substring(0, Math.min(10, suffix.length()));
        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                  capabilities_json,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, suffix, "MIMO", "https://provider.invalid");
        jdbc.update("""
                INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,
                  routing_eligible,capabilities_json,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',TRUE,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, modelId, "rb-wire-" + suffix);
        var providerModelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,
                  external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, suffix, suffix);
        var providerAccountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,
                  currency,effective_from,status,created_at,activated_at)
                VALUES (?,?,?,1,'USD','2026-01-01 00:00:00','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerAccountId, providerModelId);
        var pricingVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,
                  secret_digest_version,principal_type,organization_member_id,service_identity_id,
                  project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,
                  status,created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,?,'PROJECT',?,'OPTIONAL','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix.substring(0, Math.min(12, suffix.length())), digest(81),
                serviceId, projectId, projectId);
        var credentialId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_request(org_id,public_request_id,credential_id,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,logical_model_id,api_surface,idempotency_key_digest,
                  request_fingerprint,request_hmac_version,state,billing_period_id,created_at,
                  validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,?,'PROJECT',?,?,'CHAT_COMPLETIONS',?,?,1,
                  'TRANSPORT_COMPLETED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, fixedRequestId(), credentialId, serviceId, projectId, projectId,
                modelId, digest(82), digest(83), periodId);
        var requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,created_at)
                VALUES (?,?,1,?,?,?,?, 'BILLABLE_POSSIBLE',UTC_TIMESTAMP(6))
                """, orgId, requestId, fixedRequestId(), providerAccountId, providerModelId,
                pricingVersionId);
        var attemptId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
        return new GatewayChain(requestId, attemptId, providerAccountId, pricingVersionId);
    }

    private long insertConfirmedCharge(long providerAccountId) {
        var rawRecordId = insertConfirmedRawRecord(orgId, actorMemberId, providerAccountId,
                UUID.randomUUID().toString().replace("-", ""));
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,0,'GLM','USAGE','2.00000000','USD',?,
                  DATE_ADD(?, INTERVAL 1 DAY),'CLEAN',UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, AUG_START, AUG_START);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM charge_fact WHERE org_id=? AND raw_record_id=?",
                Long.class, orgId, rawRecordId);
    }

    private static String fixedRequestId() {
        return (UUID.randomUUID().toString().replace("-", "")
                + "0000000000000000000000000000").substring(0, 40);
    }

    private static byte[] digest(int seed) {
        var result = new byte[32];
        for (var i = 0; i < result.length; i++) {
            result[i] = (byte) (seed + i);
        }
        return result;
    }
}
