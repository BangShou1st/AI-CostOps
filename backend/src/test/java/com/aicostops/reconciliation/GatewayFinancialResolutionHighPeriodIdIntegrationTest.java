package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.reconciliation.application.GatewayFinancialResolutionService;
import com.aicostops.reconciliation.application.GatewayFinancialResolutionService.GatewayResolutionCommand;
import com.aicostops.shared.security.AuthenticatedUser;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * M15 gateway financial resolution under a BillingPeriod id above the Long
 * autobox cache (127). This context runs with MyBatis
 * {@code local-cache-scope=statement}, so the pre-read lineage and the locked
 * re-read inside one resolution transaction materialize INDEPENDENT boxed
 * instances for the same period id — exactly the shape of a live re-read —
 * and only a numeric identity comparison can keep the legal resolution alive.
 *
 * <p>The shared-context regression
 * {@code GatewayFinancialResolutionIntegrationTest} covers the same-period
 * correction-period assertion; this class covers the re-read path that its
 * session-local cache cannot exercise.
 */
@SpringBootTest(properties = "mybatis.configuration.local-cache-scope=statement")
@Tag("integration")
class GatewayFinancialResolutionHighPeriodIdIntegrationTest extends AllocationApiTestSupport {

    private static final String OCT_START = "2026-10-01 00:00:00.000000";
    private static final String NOV_START = "2026-11-01 00:00:00.000000";

    @Autowired GatewayFinancialResolutionService resolutions;

    private AuthenticatedUser actor;
    private long periodId;
    private long runId;

    @BeforeEach
    void highPeriodSetup() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN (
                  'RECONCILIATION_RESOLVE','LEDGER_CORRECT')
                """);
        actor = new AuthenticatedUser(actorUserId, 7);
    }

    @Test
    void highBillingPeriodIdSurvivesIndependentLineageMaterialization() {
        // Deterministic id above the Long autobox cache: numerically-equal
        // boxed ids arrive as distinct instances.
        jdbc.update("ALTER TABLE billing_period AUTO_INCREMENT = 5000");
        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
                VALUES (?,?,?,?,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, OCT_START, NOV_START, "OPEN");
        periodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
        runId = insertCompletedRun(periodId);
        assertThat(periodId).isGreaterThan(127L);

        var fixture = insertGatewayFixture("BILLABLE_POSSIBLE", "UNKNOWN", false, false);
        var chargeId = insertConfirmedStatementCharge(fixture.providerAccountId(),
                "2.00000000", "2026-10-05 00:00:00");
        insertUnresolvedEvidence(fixture);

        var result = resolutions.resolveGatewayFinancialWork(actor,
                new GatewayResolutionCommand(runId, null, fixture.requestId(),
                        "STATEMENT_ADJUSTMENT_POSTED", chargeId, null, null,
                        "REVIEWED_STATEMENT_LINE", "Provider statement line reviewed"),
                "gwres-highid-lineage");

        assertThat(jdbc.queryForObject(
                "SELECT adjustment_period_id FROM reconciliation_adjustment WHERE id=?",
                Long.class, result.adjustmentId())).isEqualTo(periodId);
    }

    private long insertCompletedRun(long billingPeriodId) {
        jdbc.update("""
                INSERT INTO reconciliation_run(org_id,billing_period_id,status,
                  algorithm_version,tolerance_amount,basis_hash,summary_json,
                  created_by_member_id,started_at,finished_at,created_at,updated_at)
                VALUES (?,?,'COMPLETED','M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2',
                  '0.00000000',?,JSON_OBJECT(),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, billingPeriodId, "e".repeat(64), actorMemberId);
        return jdbc.queryForObject(
                "SELECT id FROM reconciliation_run WHERE org_id=? AND billing_period_id=? "
                        + "ORDER BY id DESC LIMIT 1",
                Long.class, orgId, billingPeriodId);
    }

    private void insertUnresolvedEvidence(Fixture fixture) {
        jdbc.update("""
                INSERT INTO reconciliation_evidence(org_id,reconciliation_run_id,
                  reconciliation_case_id,evidence_key,
                  provider_account_id,currency,match_kind,gateway_request_id,
                  gateway_route_attempt_id,gateway_usage_fact_id,gateway_settlement_id,
                  created_at)
                VALUES (?,?,?,CONCAT('GATEWAY_UNRESOLVED:REQUEST:',?),?,?,
                  'GATEWAY_UNRESOLVED',?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, runId, null, fixture.requestId(), fixture.providerAccountId(),
                "USD", fixture.requestId(), fixture.attemptId(), fixture.usageFactId(),
                fixture.settlementId());
    }

    private record Fixture(long requestId, long attemptId, Long usageFactId, Long settlementId,
            Long reservationId, long providerAccountId, long providerModelId,
            long pricingVersionId) {
    }

    /** usageStatus null = no usage fact; withSettlement requires FINAL usage. */
    private Fixture insertGatewayFixture(String attemptStatus, String usageStatus,
            boolean withSettlement, boolean withReservation) {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "gwr-svc-" + suffix, suffix);
        var serviceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,status,capabilities_json,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),1024,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "gwr-model-" + suffix, suffix);
        var modelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        var providerCode = "MIMO-" + suffix.substring(0, 10);
        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                  capabilities_json,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, suffix, "MIMO", "https://provider.invalid");
        jdbc.update("""
                INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,
                  routing_eligible,capabilities_json,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',TRUE,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, modelId, "gwr-wire-" + suffix);
        var providerModelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,
                  external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, suffix, suffix);
        var providerAccountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,
                  version,currency,effective_from,status,created_at,activated_at)
                VALUES (?,?,?,1,'USD','2026-01-01 00:00:00','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerAccountId, providerModelId);
        var pricingVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,
                  secret_digest_version,principal_type,organization_member_id,
                  service_identity_id,project_id,financial_scope_type,financial_scope_id,
                  budget_enforcement_mode,status,created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,?,'PROJECT',?,'OPTIONAL','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix.substring(0, 12), digest(71), serviceId, projectId,
                projectId);
        var credentialId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_request(org_id,public_request_id,credential_id,
                  principal_type,organization_member_id,service_identity_id,project_id,
                  financial_scope_type,financial_scope_id,logical_model_id,api_surface,
                  idempotency_key_digest,request_fingerprint,request_hmac_version,state,
                  billing_period_id,created_at,validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,?,'PROJECT',?,?,'CHAT_COMPLETIONS',?,?,1,
                  'TRANSPORT_COMPLETED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6))
                """, orgId, fixedRequestId(), credentialId, serviceId, projectId, projectId,
                modelId, digest(72), digest(73), periodId);
        var requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,
                  route_decision_id,provider_account_id,provider_model_id,pricing_version_id,
                  status,created_at)
                VALUES (?,?,1,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, requestId, fixedRequestId(), providerAccountId, providerModelId,
                pricingVersionId, attemptStatus);
        var attemptId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);

        Long usageFactId = null;
        if (usageStatus != null) {
            jdbc.update("""
                    INSERT INTO gateway_usage_fact(org_id,request_id,route_attempt_id,sequence,
                      status,usage_effective_at,usage_effective_at_source,pricing_version_id,
                      currency,observed_at,created_at)
                    VALUES (?,?,?,1,?,UTC_TIMESTAMP(6),
                      'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?,'USD',UTC_TIMESTAMP(6),
                      UTC_TIMESTAMP(6))
                    """, orgId, requestId, attemptId, usageStatus, pricingVersionId);
            usageFactId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("UPDATE gateway_request SET current_usage_fact_id=? WHERE id=?",
                    usageFactId, requestId);
        }

        Long settlementId = null;
        if (withSettlement) {
            jdbc.update("""
                    INSERT INTO gateway_settlement(
                      org_id,settlement_key,request_id,route_attempt_id,usage_fact_id,
                      reservation_id,billing_period_id,financial_scope_type,
                      financial_scope_id,provider_account_id,provider_model_id,
                      pricing_version_id,currency,status,attempt_count,created_at,updated_at)
                    VALUES (?,?,?,?,?,NULL,?,'PROJECT',?,?,?,?,?,'PENDING',0,
                      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, orgId, "GATEWAY_REQUEST:" + suffix, requestId, attemptId,
                    usageFactId, periodId, projectId, providerAccountId, providerModelId,
                    pricingVersionId, "USD");
            settlementId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        }

        Long reservationId = null;
        if (withReservation) {
            jdbc.update("""
                    INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                      total_amount,actual_amount,committed_amount,status,version,created_at,
                      updated_at)
                    VALUES (?,?,'PROJECT',?,'USD','20.00000000',0,0,'ACTIVE',0,
                      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, orgId, periodId, projectId);
            var budgetId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("""
                    INSERT INTO budget_reservation(org_id,request_id,route_attempt_id,
                      billing_period_id,budget_id,financial_scope_type,financial_scope_id,
                      currency,reserved_amount,commitment_id,commitment_backed_amount,status,
                      version,expires_at,created_at,updated_at)
                    VALUES (?,?,?,?,?,'PROJECT',?,'USD','5.00000000',NULL,0,'ACTIVE',0,
                      UTC_TIMESTAMP(6) + INTERVAL 7 DAY,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, orgId, requestId, attemptId, periodId, budgetId, projectId);
            reservationId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        }
        return new Fixture(requestId, attemptId, usageFactId, settlementId, reservationId,
                providerAccountId, providerModelId, pricingVersionId);
    }

    /** Confirmed USD GLM statement charge for the given provider account. */
    private long insertConfirmedStatementCharge(long providerAccountId, String amount,
            String periodStart) {
        var rawRecordId = insertConfirmedRawRecord(orgId, actorMemberId, providerAccountId,
                uniqueSuffix());
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,
                    currency,period_start,period_end,review_status,created_at)
                VALUES (?,?,0,'GLM','USAGE',?,?,?,DATE_ADD(?, INTERVAL 1 DAY),?,
                  UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, amount, "USD", periodStart, periodStart, "CLEAN");
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM charge_fact WHERE org_id=? AND raw_record_id=?",
                Long.class, orgId, rawRecordId);
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "");
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
