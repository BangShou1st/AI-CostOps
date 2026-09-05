package com.aicostops.reconciliation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.allocation.AllocationApiTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * M15 hybrid reconciliation API contract: decimal-string identifiers, exact
 * scale-8 money strings, Idempotency-Key required on financial POSTs and
 * permission boundaries over the new evidence/disposition/adjustment/resolution
 * endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
class M15ReconciliationApiIntegrationTest extends AllocationApiTestSupport {

    private static final String AUG_START = "2026-08-01 00:00:00.000000";
    private static final String SEP_START = "2026-09-01 00:00:00.000000";

    @Autowired MockMvc mvc;
    @Autowired com.aicostops.cost.application.ReconciliationExternalTruthPort externalTruth;
    @Autowired com.aicostops.ledger.application.ReconciliationInternalTruthPort internalTruth;
    @Autowired com.aicostops.reconciliation.application.ReconciliationMatchEngine matchEngine;
    @Autowired com.aicostops.reconciliation.application.ReconciliationTruthHasher hasher;
    @Autowired com.aicostops.reconciliation.application.ReconciliationTolerancePolicy tolerancePolicy;

    private long periodId;
    private long runId;
    private long caseId;
    private long chargeId;
    private long requestId;

    @BeforeEach
    void apiSetup() throws Exception {
        jdbc.update("""
                INSERT IGNORE INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN (
                  'LEDGER_POST','LEDGER_CORRECT','RECONCILIATION_READ','RECONCILIATION_RUN',
                  'RECONCILIATION_RESOLVE')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
                VALUES (?,?,?,?,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, AUG_START, SEP_START, "OPEN");
        periodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,999,'GLM','USAGE','10.00000000','USD',?,'2026-08-02 00:00:00',
                  'CLEAN',UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, AUG_START);
        chargeId = jdbc.queryForObject("SELECT MAX(id) FROM charge_fact WHERE org_id=?",
                Long.class, orgId);

        jdbc.update("""
                INSERT INTO reconciliation_run(org_id,billing_period_id,status,algorithm_version,
                  tolerance_amount,basis_hash,summary_json,created_by_member_id,started_at,
                  finished_at,created_at,updated_at)
                VALUES (?,?,'COMPLETED','M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2','0.00000000',
                  ?,JSON_OBJECT(),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6))
                """, orgId, periodId, currentBasisHash(), actorMemberId);
        runId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO reconciliation_case(org_id,reconciliation_run_id,provider_account_id,
                  currency,case_type,external_amount,internal_amount,difference_amount,
                  external_row_count,internal_row_count,status,created_at,updated_at)
                VALUES (?,?,?,'USD','AMOUNT_MISMATCH','10.00000000','8.00000000','-2.00000000',
                  1,1,'INVESTIGATING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, accountId);
        caseId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        var gateway = insertGatewayRequest();
        requestId = gateway;
    }

    @Test
    void runEvidenceEndpointReturnsBoundedDecimalStringEvidence() throws Exception {
        jdbc.update("""
                INSERT INTO reconciliation_evidence(
                  org_id,reconciliation_run_id,reconciliation_case_id,evidence_key,
                  provider_account_id,currency,match_kind,difference_kind,external_amount,
                  internal_amount,difference_amount,created_at)
                VALUES (?,?,?,?,?,'USD','AGGREGATE_SCOPE','UNCLASSIFIED',
                  '10.00000000','8.00000000','-2.00000000',UTC_TIMESTAMP(6))
                """, orgId, runId, caseId,
                "AGGREGATE:" + accountId + ":USD", accountId);

        mvc.perform(get("/api/v1/reconciliation-runs/%d/evidence".formatted(runId))
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.items[0].reconciliationRunId")
                        .value(Long.toString(runId)))
                .andExpect(jsonPath("$.items[0].reconciliationCaseId")
                        .value(Long.toString(caseId)))
                .andExpect(jsonPath("$.items[0].matchKind").value("AGGREGATE_SCOPE"))
                .andExpect(jsonPath("$.items[0].differenceKind").value("UNCLASSIFIED"))
                .andExpect(jsonPath("$.items[0].externalAmount").value("10.00000000"))
                .andExpect(jsonPath("$.items[0].differenceAmount").value("-2.00000000"));

        mvc.perform(get("/api/v1/reconciliation-cases/%d/evidence".formatted(caseId))
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void chargeDispositionEndpointRequiresIdempotencyKeyAndReplays() throws Exception {
        var body = """
                {"chargeFactId":"%d","disposition":"DIRECT_PROVIDER_CHARGE",
                 "reasonCode":"MANUAL_REVIEW","reasonNote":"Reviewed direct cost"}
                """.formatted(chargeId);

        mvc.perform(post("/api/v1/reconciliation-cases/%d/charge-dispositions".formatted(caseId))
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        var first = mvc.perform(
                        post("/api/v1/reconciliation-cases/%d/charge-dispositions".formatted(caseId))
                                .header("Authorization", bearer())
                                .header("Idempotency-Key", "disp-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.decisionSource").value("MANUAL"))
                .andExpect(jsonPath("$.chargeFactId").value(Long.toString(chargeId)))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(post("/api/v1/reconciliation-cases/%d/charge-dispositions".formatted(caseId))
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "disp-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString());

        assertThatDispositionCountIs(1);
        assertThatDispositionCountIs(1);
    }

    @Test
    void caseAdjustmentEndpointPostsExactAdjustmentWithDecimalStrings() throws Exception {
        var body = """
                {"amount":"2.00000000","adjustmentPeriodId":"%d",
                 "lines":[{"lineIndex":"0","scopeType":"PROJECT","scopeId":"%d",
                           "amount":"2.00000000"}],
                 "reasonCode":"AGGREGATE_RESOLVED","reasonNote":"Statement reviewed"}
                """.formatted(periodId, projectId);

        mvc.perform(post("/api/v1/reconciliation-cases/%d/adjustments".formatted(caseId))
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "adj-api-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.caseId").value(Long.toString(caseId)))
                .andExpect(jsonPath("$.adjustmentScope").value("CASE_FULL"))
                .andExpect(jsonPath("$.amount").value("2.00000000"))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void gatewayResolutionEndpointRequiresFinancialPermission() throws Exception {
        // Strip the LEDGER_CORRECT permission: financial resolution must fail.
        jdbc.update("""
                DELETE FROM role_permission
                WHERE permission_id IN (SELECT id FROM permission WHERE code='LEDGER_CORRECT')
                  AND role_id IN (SELECT id FROM `role` WHERE code='ALLOC_WORKER')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        mvc.perform(post("/api/v1/reconciliation-runs/%d/gateway-resolutions".formatted(runId))
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "gwres-api-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%d","resolutionType":"NO_CHARGE_CONFIRMED",
                                 "reasonCode":"POSITIVE_NO_CHARGE","reasonNote":"Reviewed"}
                                """.formatted(requestId)))
                .andExpect(status().isForbidden());
    }

    private String currentBasisHash() {
        var external = externalTruth.aggregateConfirmedCharges(orgId,
                java.time.Instant.parse("2026-08-01T00:00:00Z"),
                java.time.Instant.parse("2026-09-01T00:00:00Z"));
        var internal = internalTruth.aggregateProviderLedger(orgId, periodId);
        return hasher.hash(matchEngine.match(external, internal,
                tolerancePolicy.amount()).rows());
    }

    private long insertGatewayRequest() {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "api-svc-" + suffix, suffix);
        var serviceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,status,capabilities_json,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),1024,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "api-model-" + suffix, suffix);
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
                """, providerCode, modelId, "api-wire-" + suffix);
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
                """, orgId, suffix.substring(0, 12), digest(81), serviceId, projectId, projectId);
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
        var gwRequestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,created_at)
                VALUES (?,?,1,?,?,?,?, 'BILLABLE_POSSIBLE',UTC_TIMESTAMP(6))
                """, orgId, gwRequestId, fixedRequestId(), providerAccountId, providerModelId,
                pricingVersionId);
        var attemptId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, gwRequestId);
        return gwRequestId;
    }

    private void assertThatDispositionCountIs(int expected) {
        var count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=?",
                Long.class, orgId);
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(expected);
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
