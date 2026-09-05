package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.reconciliation.application.ReconciliationRunService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * M15 hybrid evidence generation and OPEN/CLOSED/CLOSING run admission.
 * GLM is the only provider whose import profile certifies
 * provider_record_key as PROVIDER_REQUEST_ID (see the test property).
 */
@SpringBootTest
@Tag("integration")
@TestPropertySource(properties = {
        "aicostops.reconciliation.correlation-certified-providers=GLM"})
class HybridReconciliationEvidenceIntegrationTest extends AllocationApiTestSupport {

    private static final String AUG_START = "2026-08-01 00:00:00.000000";
    private static final String SEP_START = "2026-09-01 00:00:00.000000";

    @Autowired JdbcTemplate jdbc;
    @Autowired ReconciliationRunService runs;
    @Autowired AuthorizationContextService authorizationContexts;

    private long periodId;
    private AuthenticatedUser actor;

    @BeforeEach
    void reconcileSetup() {
        grantM6FinancePermissions();
        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
                VALUES (?,? ,? ,'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, AUG_START, SEP_START);
        periodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
        actor = new AuthenticatedUser(actorUserId, 7);
    }

    @Test
    void certifiedExactCorrelationProducesExactEvidenceWithoutChargeDisposition() {
        var chargeId = insertPeriodCharge("10.00000000", "USD", "CLEAN",
                "2026-08-10 00:00:00", "prov-req-exact-1", "GLM");
        var gateway = insertGatewayRequest("COMPLETED", "prov-req-exact-1", "FINAL", "USD");

        runs.run(actor, periodId);

        var evidence = jdbc.queryForMap("""
                SELECT match_kind,charge_fact_id,gateway_request_id,gateway_route_attempt_id,
                       provider_request_id
                FROM reconciliation_evidence
                WHERE org_id=? AND match_kind='EXACT_PROVIDER_REQUEST'
                """, orgId);
        assertThat(evidence.get("charge_fact_id")).isEqualTo(chargeId);
        assertThat(evidence.get("gateway_request_id")).isEqualTo(gateway[0]);
        assertThat(evidence.get("gateway_route_attempt_id")).isEqualTo(gateway[1]);
        assertThat(evidence.get("provider_request_id")).isEqualTo("prov-req-exact-1");

        // Exact evidence is explanatory; it never creates a Charge disposition.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void safeAndPlannedAttemptsNeverMatchExactly() {
        insertPeriodCharge("10.00000000", "USD", "CLEAN", "2026-08-10 00:00:00",
                "prov-req-safe-1", "GLM");
        insertGatewayRequest("SAFE_NO_BILLABLE_EXECUTION", "prov-req-safe-1", "FINAL", "USD");
        insertPeriodCharge("20.00000000", "USD", "CLEAN", "2026-08-11 00:00:00",
                "prov-req-planned-1", "GLM");
        insertGatewayRequest("PLANNED", "prov-req-planned-1", "FINAL", "USD");

        runs.run(actor, periodId);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reconciliation_evidence
                WHERE org_id=? AND match_kind='EXACT_PROVIDER_REQUEST'
                """, Long.class, orgId)).isZero();
    }

    @Test
    void ambiguousDuplicateProviderRequestIdNeverAutoBinds() {
        insertPeriodCharge("10.00000000", "USD", "CLEAN", "2026-08-10 00:00:00",
                "prov-req-dup-1", "GLM");
        insertPeriodCharge("12.00000000", "USD", "CLEAN", "2026-08-11 00:00:00",
                "prov-req-dup-1", "GLM");
        insertGatewayRequest("COMPLETED", "prov-req-dup-1", "FINAL", "USD");

        runs.run(actor, periodId);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reconciliation_evidence
                WHERE org_id=? AND match_kind='EXACT_PROVIDER_REQUEST'
                """, Long.class, orgId)).isZero();
    }

    @Test
    void uncertifiedProviderProfileNeverMatchesExactly() {
        // The import provider code is OTHER, which is not certified by the
        // profile registry even though the raw key equals the Provider id.
        insertPeriodCharge("10.00000000", "USD", "CLEAN", "2026-08-10 00:00:00",
                "prov-req-other-1", "OTHER");
        insertGatewayRequest("COMPLETED", "prov-req-other-1", "FINAL", "USD");

        runs.run(actor, periodId);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reconciliation_evidence
                WHERE org_id=? AND match_kind='EXACT_PROVIDER_REQUEST'
                """, Long.class, orgId)).isZero();
    }

    @Test
    void amountAndTimeProximityAloneNeverMatchesExactly() {
        insertPeriodCharge("10.00000000", "USD", "CLEAN", "2026-08-10 00:00:00", null, "GLM");
        insertGatewayRequest("COMPLETED", "prov-req-unrelated", "FINAL", "USD");

        runs.run(actor, periodId);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reconciliation_evidence
                WHERE org_id=? AND match_kind='EXACT_PROVIDER_REQUEST'
                """, Long.class, orgId)).isZero();
    }

    @Test
    void aggregateCaseCarriesBoundedScopeEvidence() {
        insertPeriodCharge("10.00000000", "USD", "CLEAN", "2026-08-10 00:00:00", null, "GLM");

        runs.run(actor, periodId);

        var caseRow = jdbc.queryForMap("""
                SELECT id,case_type,external_amount,internal_amount,difference_amount
                FROM reconciliation_case WHERE org_id=?
                """, orgId);
        assertThat(caseRow.get("case_type")).isEqualTo("MISSING_INTERNAL");
        var evidence = jdbc.queryForMap("""
                SELECT match_kind,difference_kind,reconciliation_case_id,external_amount,
                       internal_amount,difference_amount
                FROM reconciliation_evidence WHERE org_id=?
                """, orgId);
        assertThat(evidence.get("match_kind")).isEqualTo("AGGREGATE_SCOPE");
        assertThat(evidence.get("reconciliation_case_id")).isEqualTo(caseRow.get("id"));
        // Fail-closed classification: no evidence proves a specific root cause.
        assertThat(evidence.get("difference_kind")).isEqualTo("UNCLASSIFIED");
        assertThat(((java.math.BigDecimal) evidence.get("external_amount")))
                .isEqualByComparingTo("10.00000000");
    }

    @Test
    void runLevelUnresolvedGatewayEvidenceNeedsNoFabricatedCase() {
        var account = insertGatewayAccount("unresolved");
        // Possible-billable attempt with no usage fact and no statement charge:
        // external 0, internal 0, but unresolved Gateway financial work.
        insertGatewayRequestOn(account, "BILLABLE_POSSIBLE", "prov-req-unresolved-1", null, "USD");

        runs.run(actor, periodId);

        var evidence = jdbc.queryForMap("""
                SELECT match_kind,reconciliation_case_id,gateway_request_id
                FROM reconciliation_evidence WHERE org_id=? AND match_kind='GATEWAY_UNRESOLVED'
                """, orgId);
        assertThat(evidence.get("reconciliation_case_id")).isNull();
        assertThat(evidence.get("gateway_request_id")).isNotNull();
        // No zero-amount case may be fabricated for the unresolved scope.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_case WHERE org_id=?", Long.class, orgId))
                .isZero();
    }

    @Test
    void closedPeriodReconcilesWithoutFinancialMutation() {
        insertPeriodCharge("10.00000000", "USD", "CLEAN", "2026-08-10 00:00:00", null, "GLM");
        jdbc.update("UPDATE billing_period SET status='CLOSED' WHERE id=?", periodId);

        var run = runs.run(actor, periodId);

        assertThat(run.status().name()).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM billing_period WHERE id=?", String.class, periodId))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_posting WHERE org_id=?", Long.class, orgId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_evidence WHERE org_id=?", Long.class, orgId))
                .isPositive();
    }

    @Test
    void closingPeriodRejectsReconciliationRun() {
        jdbc.update("UPDATE billing_period SET status='CLOSING' WHERE id=?", periodId);

        assertThatThrownBy(() -> runs.run(actor, periodId))
                .isInstanceOf(DomainException.class);
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private void grantM6FinancePermissions() {
        jdbc.update("""
                INSERT IGNORE INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN (
                  'RECONCILIATION_READ','RECONCILIATION_RUN','RECONCILIATION_RESOLVE',
                  'PERIOD_READ')
                """);
    }

    private long insertPeriodCharge(String amount, String currency, String reviewStatus,
            String periodStart, String providerRecordKey, String providerCode) {
        jdbc.update("UPDATE raw_provider_record SET provider_record_key=? WHERE id=?",
                providerRecordKey, rawRecordId);
        var nextIndex = jdbc.queryForObject(
                "SELECT COALESCE(MAX(fact_index),-1)+1 FROM charge_fact WHERE raw_record_id=?",
                Integer.class, rawRecordId);
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,?,?,'USAGE',?,?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, nextIndex, providerCode, amount, currency, periodStart,
                periodStart, reviewStatus);
        return jdbc.queryForObject("SELECT MAX(id) FROM charge_fact WHERE org_id=?",
                Long.class, orgId);
    }

    /** Returns {requestId, attemptId}. */
    private long[] insertGatewayRequest(String attemptStatus, String providerRequestId,
            String usageStatus, String currency) {
        var account = insertGatewayAccount("m15ev");
        return insertGatewayRequestOn(account, attemptStatus, providerRequestId, usageStatus,
                currency);
    }

    /** Returns {requestId, attemptId}; usageStatus NULL means no usage fact. */
    private long[] insertGatewayRequestOn(long accountId, String attemptStatus,
            String providerRequestId, String usageStatus, String currency) {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "m15ev-svc-" + suffix, suffix);
        var serviceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,status,capabilities_json,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),1024,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "m15ev-model-" + suffix, suffix);
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
                """, providerCode, modelId, "m15ev-wire-" + suffix);
        var providerModelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,
                  currency,effective_from,status,created_at,activated_at)
                VALUES (?,?,?,1,?,'2026-01-01 00:00:00','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, accountId, providerModelId, currency);
        var pricingVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,
                  secret_digest_version,principal_type,organization_member_id,service_identity_id,
                  project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,
                  status,created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,?,'PROJECT',?,'OPTIONAL','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix.substring(0, 12), digest(31), serviceId,
                projectId, projectId);
        var credentialId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_request(org_id,public_request_id,credential_id,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,logical_model_id,api_surface,idempotency_key_digest,
                  request_fingerprint,request_hmac_version,state,billing_period_id,created_at,
                  validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,?,'PROJECT',?,?,'CHAT_COMPLETIONS',?,?,1,
                  'TRANSPORT_COMPLETED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, fixedRequestId(), credentialId, serviceId,
                projectId, projectId, modelId, digest(32), digest(33), periodId);
        var requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,
                  provider_request_id,created_at)
                VALUES (?,?,1,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, requestId, fixedRequestId(), accountId, providerModelId,
                pricingVersionId, attemptStatus, providerRequestId);
        var attemptId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
        if (usageStatus != null) {
            jdbc.update("""
                    INSERT INTO gateway_usage_fact(org_id,request_id,route_attempt_id,sequence,
                      status,usage_effective_at,usage_effective_at_source,pricing_version_id,
                      currency,observed_at,created_at)
                    VALUES (?,?,?,1,?,UTC_TIMESTAMP(6),
                      'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, orgId, requestId, attemptId, usageStatus, pricingVersionId, currency);
            var usageFactId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("UPDATE gateway_request SET current_usage_fact_id=? WHERE id=?",
                    usageFactId, requestId);
        }
        return new long[] {requestId, attemptId};
    }

    private long insertGatewayAccount(String tag) {
        var suffix = tag + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,
                  external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "MIMO-" + suffix, suffix, suffix);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
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
