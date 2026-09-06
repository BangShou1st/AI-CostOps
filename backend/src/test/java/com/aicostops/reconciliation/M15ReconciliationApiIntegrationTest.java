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
    void actionableEvidenceFilterExcludesTerminalResolvedRequests() throws Exception {
        var unresolvedLineage = currentLineageOf(requestId);
        var unresolvedId = insertBoundUnresolvedEvidence(unresolvedLineage);
        // A second request whose historical GATEWAY_UNRESOLVED evidence already
        // has a committed terminal gateway_financial_resolution.
        var resolvedRequestId = insertGatewayRequest();
        var resolvedLineage = currentLineageOf(resolvedRequestId);
        var resolvedEvidenceId = insertBoundUnresolvedEvidence(resolvedLineage);
        var attemptId = jdbc.queryForObject(
                "SELECT id FROM gateway_route_attempt WHERE org_id=? AND request_id=?",
                Long.class, orgId, resolvedRequestId);
        jdbc.update("""
                INSERT INTO gateway_financial_resolution(
                  org_id,reconciliation_run_id,request_id,route_attempt_id,
                  resolution_type,reservation_outcome,resolved_by_member_id,reason_code,
                  reason_note,resolved_at,created_at)
                VALUES (?,?,?,?,'NO_CHARGE_CONFIRMED','NONE',?,'PROVIDER_PORTAL_CONFIRMED_NO_CHARGE',
                  'Reviewed positive proof',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, resolvedRequestId, attemptId, actorMemberId);
        var resolutionId = jdbc.queryForObject(
                "SELECT id FROM gateway_financial_resolution WHERE org_id=? AND request_id=?",
                Long.class, orgId, resolvedRequestId);

        // The actionable queue contains only the request without a terminal
        // resolution, and the total matches the same predicate.
        mvc.perform(get("/api/v1/reconciliation-runs/%d/evidence".formatted(runId))
                        .header("Authorization", bearer())
                        .param("matchKind", "GATEWAY_UNRESOLVED")
                        .param("actionableOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(unresolvedId)))
                .andExpect(jsonPath("$.items[0].currentGatewayResolutionId")
                        .value(org.hamcrest.Matchers.nullValue()));

        // History is preserved: the unfiltered list still serves both rows and
        // projects the committed resolution id on the resolved one.
        var response = mvc.perform(get("/api/v1/reconciliation-runs/%d/evidence"
                        .formatted(runId))
                        .header("Authorization", bearer())
                        .param("matchKind", "GATEWAY_UNRESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(response)
                .contains(Long.toString(resolvedEvidenceId))
                .contains(Long.toString(resolutionId));

        // The bounded filter is only meaningful for GATEWAY_UNRESOLVED.
        mvc.perform(get("/api/v1/reconciliation-runs/%d/evidence".formatted(runId))
                        .header("Authorization", bearer())
                        .param("actionableOnly", "true"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void evidenceResponseProjectsCurrentChargeDisposition() throws Exception {
        jdbc.update("""
                INSERT INTO provider_charge_disposition(
                  org_id,charge_fact_id,disposition,decision_source,decided_by_member_id,
                  reason_code,resolution_note,created_at)
                VALUES (?,?, 'DIRECT_PROVIDER_CHARGE','MANUAL',?,'MANUAL_DIRECT',
                  'Reviewed direct provider cost',UTC_TIMESTAMP(6))
                """, orgId, chargeId, actorMemberId);
        var chargeEvidenceId = insertCaseEvidenceWithCharge("RESOLUTION_ACTION", chargeId);

        mvc.perform(get("/api/v1/reconciliation-cases/%d/evidence".formatted(caseId))
                        .header("Authorization", bearer())
                        .param("matchKind", "RESOLUTION_ACTION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(chargeEvidenceId)))
                .andExpect(jsonPath("$.items[0].currentChargeDisposition")
                        .value("DIRECT_PROVIDER_CHARGE"));
    }

    @Test
    void resolutionContextReadableWithoutBudgetRead() throws Exception {
        // The reconciliation operator owns RECONCILIATION_READ/RESOLVE and
        // LEDGER_CORRECT but must never require BUDGET_READ to complete a
        // Gateway correction workflow: the bounded context endpoint serves
        // only period identity, never budget-sensitive fields.
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                JOIN permission p ON p.id=rp.permission_id
                WHERE r.code='ALLOC_WORKER' AND p.code='BUDGET_READ'
                """);

        mvc.perform(get("/api/v1/billing-periods")
                        .header("Authorization", bearer()))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/v1/reconciliation-runs/%d/financial-resolution-context"
                        .formatted(runId))
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalBillingPeriodId")
                        .value(Long.toString(periodId)))
                .andExpect(jsonPath("$.originalBillingPeriodStatus").value("OPEN"))
                .andExpect(jsonPath("$.eligibleCorrectionPeriods").isArray());
    }

    @Test
    void actionableFilterExcludesFinalUsageOwnedByM13() throws Exception {
        var lineage = currentLineageOf(insertGatewayRequest());
        insertUsage(lineage, "FINAL");
        var evidenceId = insertBoundUnresolvedEvidence(lineage);

        assertActionableTotal(0);
        assertUnfilteredTotal("GATEWAY_UNRESOLVED", 1);
        org.assertj.core.api.Assertions.assertThat(evidenceId).isPositive();
    }

    @Test
    void actionableFilterExcludesPendingSettlement() throws Exception {
        var lineage = currentLineageOf(insertGatewayRequest());
        var usageId = insertUsage(lineage, "FINAL");
        insertSettlement(lineage, usageId, "PENDING");
        insertBoundUnresolvedEvidence(lineage);

        assertActionableTotal(0);
        assertUnfilteredTotal("GATEWAY_UNRESOLVED", 1);
    }

    @Test
    void actionableFilterExcludesRetryableFailedSettlement() throws Exception {
        var lineage = currentLineageOf(insertGatewayRequest());
        var usageId = insertUsage(lineage, "FINAL");
        insertSettlement(lineage, usageId, "RETRYABLE_FAILED");
        insertBoundUnresolvedEvidence(lineage);

        assertActionableTotal(0);
        assertUnfilteredTotal("GATEWAY_UNRESOLVED", 1);
    }

    @Test
    void actionableFilterExcludesSettledRequest() throws Exception {
        var lineage = currentLineageOf(insertGatewayRequest());
        var usageId = insertUsage(lineage, "FINAL");
        var settlementId = insertSettlement(lineage, usageId, "PENDING");
        // A SETTLED Settlement is immutable financial truth with posted
        // amounts and a Ledger posting: transition the row the governed way.
        jdbc.update("""
                INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,
                  allocation_decision_id,billing_period_id,status,posting_actor_type,
                  posted_by_member_id,posted_at,created_at)
                VALUES (?,?,'GATEWAY_SETTLEMENT',?,NULL,?,'POSTED','SYSTEM',NULL,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "GATEWAY_SETTLEMENT:" + settlementId, settlementId, periodId);
        var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                UPDATE gateway_settlement SET status='SETTLED',calculated_amount_raw=1.8,
                  posted_amount=1.8,rounding_delta=0,ledger_posting_id=?,settled_at=UTC_TIMESTAMP(6)
                WHERE id=?
                """, postingId, settlementId);
        insertBoundUnresolvedEvidence(lineage);

        assertActionableTotal(0);
        assertUnfilteredTotal("GATEWAY_UNRESOLVED", 1);
    }

    @Test
    void actionableFilterExcludesStaleRouteAttempt() throws Exception {
        var lineage = currentLineageOf(insertGatewayRequest());
        var evidenceId = insertBoundUnresolvedEvidence(lineage);
        // A failover moves the request to a newer attempt: the evidence bound
        // to the older attempt is history, not currently actionable work.
        var freshAttempt = insertRouteAttempt(lineage.requestId(), "BILLABLE_POSSIBLE");
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                freshAttempt, lineage.requestId());

        assertActionableTotal(0);
        assertUnfilteredTotal("GATEWAY_UNRESOLVED", 1);
        org.assertj.core.api.Assertions.assertThat(evidenceId).isPositive();
    }

    @Test
    void actionableFilterKeepsMissingUsage() throws Exception {
        var lineage = currentLineageOf(insertGatewayRequest());
        var evidenceId = insertBoundUnresolvedEvidence(lineage);

        assertActionableTotal(1);
        mvc.perform(get("/api/v1/reconciliation-runs/%d/evidence".formatted(runId))
                        .header("Authorization", bearer())
                        .param("matchKind", "GATEWAY_UNRESOLVED")
                        .param("actionableOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(evidenceId)))
                .andExpect(jsonPath("$.items[0].currentGatewayState").value("ACTIONABLE"));
    }

    @Test
    void actionableFilterKeepsUnknownUsage() throws Exception {
        var lineage = currentLineageOf(insertGatewayRequest());
        insertUsage(lineage, "UNKNOWN");
        insertBoundUnresolvedEvidence(lineage);

        assertActionableTotal(1);
    }

    @Test
    void actionableFilterKeepsIncompleteUsage() throws Exception {
        var lineage = currentLineageOf(insertGatewayRequest());
        insertUsage(lineage, "INCOMPLETE");
        insertBoundUnresolvedEvidence(lineage);

        assertActionableTotal(1);
    }

    @Test
    void actionableFilterKeepsReconciliationRequiredSettlement() throws Exception {
        var lineage = currentLineageOf(insertGatewayRequest());
        var usageId = insertUsage(lineage, "FINAL");
        insertSettlement(lineage, usageId, "RECONCILIATION_REQUIRED");
        insertBoundUnresolvedEvidence(lineage);

        assertActionableTotal(1);
    }

    @Test
    void runEvidenceEndpointSupportsBoundedMatchKindFilterAndTruePagination() throws Exception {
        // Three aggregate rows first, then two unresolved Gateway rows: with a
        // small generic page the unresolved work sits beyond page 0.
        for (int i = 0; i < 3; i++) {
            insertCaseEvidence("AGGREGATE_SCOPE", null);
        }
        long unresolvedA = insertCaseEvidence("GATEWAY_UNRESOLVED", requestId);
        long unresolvedB = insertCaseEvidence("GATEWAY_UNRESOLVED", requestId);

        // Generic page 0 (size 2) contains only the first two aggregate rows.
        mvc.perform(get("/api/v1/reconciliation-runs/%d/evidence".formatted(runId))
                        .header("Authorization", bearer())
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].matchKind").value("AGGREGATE_SCOPE"))
                .andExpect(jsonPath("$.items[1].matchKind").value("AGGREGATE_SCOPE"));

        // The bounded server-side filter returns the unresolved rows no matter
        // where they sit in the unfiltered order.
        mvc.perform(get("/api/v1/reconciliation-runs/%d/evidence".formatted(runId))
                        .header("Authorization", bearer())
                        .param("matchKind", "GATEWAY_UNRESOLVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(unresolvedA)))
                .andExpect(jsonPath("$.items[1].id").value(Long.toString(unresolvedB)));

        // The filtered list is paginated server-side as well.
        mvc.perform(get("/api/v1/reconciliation-runs/%d/evidence".formatted(runId))
                        .header("Authorization", bearer())
                        .param("matchKind", "GATEWAY_UNRESOLVED")
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(unresolvedB)));

        // Arbitrary filter values outside the bounded vocabulary are rejected.
        mvc.perform(get("/api/v1/reconciliation-runs/%d/evidence".formatted(runId))
                        .header("Authorization", bearer())
                        .param("matchKind", "MATCH_KIND'; DROP TABLE x"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void caseEvidenceEndpointServesTrueServerPages() throws Exception {
        for (int i = 0; i < 5; i++) {
            insertCaseEvidence("AGGREGATE_SCOPE", null);
        }

        var page0 = mvc.perform(get("/api/v1/reconciliation-cases/%d/evidence".formatted(caseId))
                        .header("Authorization", bearer())
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        var page1 = mvc.perform(get("/api/v1/reconciliation-cases/%d/evidence".formatted(caseId))
                        .header("Authorization", bearer())
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        // Page 2 is genuinely fetched from the server, not a re-slice of the
        // first page.
        org.assertj.core.api.Assertions.assertThat(page0).isNotEqualTo(page1);
    }

    @Test
    void runEvidenceEndpointSupportsRequestScopedExactLookup() throws Exception {
        var genericA = insertCaseEvidence("AGGREGATE_SCOPE", null);
        insertCaseEvidence("AGGREGATE_SCOPE", null);
        var exactId = insertCaseEvidence("EXACT_PROVIDER_REQUEST", requestId);

        mvc.perform(get("/api/v1/reconciliation-runs/%d/evidence".formatted(runId))
                        .header("Authorization", bearer())
                        .param("matchKind", "EXACT_PROVIDER_REQUEST")
                        .param("gatewayRequestId", Long.toString(requestId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(exactId)))
                .andExpect(jsonPath("$.items[0].gatewayRequestId")
                        .value(Long.toString(requestId)));

        // No exact evidence for an unrelated request id.
        mvc.perform(get("/api/v1/reconciliation-runs/%d/evidence".formatted(runId))
                        .header("Authorization", bearer())
                        .param("matchKind", "EXACT_PROVIDER_REQUEST")
                        .param("gatewayRequestId", "999999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        org.assertj.core.api.Assertions.assertThat(genericA).isPositive();
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

    private long insertCaseEvidence(String matchKind, Long gatewayRequestId) {
        return insertCaseEvidenceWithCharge(matchKind, null, gatewayRequestId);
    }

    private long insertCaseEvidenceWithCharge(String matchKind, Long chargeFactId) {
        return insertCaseEvidenceWithCharge(matchKind, chargeFactId, null);
    }

    private long insertCaseEvidenceWithCharge(String matchKind, Long chargeFactId,
            Long gatewayRequestId) {
        var evidenceKey = matchKind + ":" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reconciliation_evidence(
                  org_id,reconciliation_run_id,reconciliation_case_id,evidence_key,
                  provider_account_id,currency,match_kind,charge_fact_id,gateway_request_id,
                  created_at)
                VALUES (?,?,?,?,?,'USD',?,?,?,UTC_TIMESTAMP(6))
                """, orgId, runId, caseId, evidenceKey, accountId, matchKind, chargeFactId,
                gatewayRequestId);
        return jdbc.queryForObject(
                "SELECT id FROM reconciliation_evidence WHERE org_id=? AND evidence_key=?",
                Long.class, orgId, evidenceKey);
    }

    private String currentBasisHash() {
        var external = externalTruth.aggregateConfirmedCharges(orgId,
                java.time.Instant.parse("2026-08-01T00:00:00Z"),
                java.time.Instant.parse("2026-09-01T00:00:00Z"));
        var internal = internalTruth.aggregateProviderLedger(orgId, periodId);
        return hasher.hash(matchEngine.match(external, internal,
                tolerancePolicy.amount()).rows());
    }

    private record GatewayLineage(long requestId, long attemptId, long providerAccountId,
            long providerModelId, long pricingVersionId) {
    }

    private GatewayLineage currentLineageOf(long requestId) {
        var row = jdbc.queryForMap("""
                SELECT ra.id AS attempt_id, ra.provider_account_id AS provider_account_id,
                       ra.provider_model_id AS provider_model_id,
                       ra.pricing_version_id AS pricing_version_id
                FROM gateway_route_attempt ra
                WHERE ra.org_id=? AND ra.id=(
                  SELECT current_route_attempt_id FROM gateway_request WHERE id=?)
                """, orgId, requestId);
        return new GatewayLineage(requestId,
                ((Number) row.get("attempt_id")).longValue(),
                ((Number) row.get("provider_account_id")).longValue(),
                ((Number) row.get("provider_model_id")).longValue(),
                ((Number) row.get("pricing_version_id")).longValue());
    }

    private long insertBoundUnresolvedEvidence(GatewayLineage lineage) {
        var evidenceKey = "GATEWAY_UNRESOLVED:" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reconciliation_evidence(
                  org_id,reconciliation_run_id,reconciliation_case_id,evidence_key,
                  provider_account_id,currency,match_kind,gateway_request_id,
                  gateway_route_attempt_id,created_at)
                VALUES (?,?,?,?,?,'USD','GATEWAY_UNRESOLVED',?,?,UTC_TIMESTAMP(6))
                """, orgId, runId, caseId, evidenceKey, lineage.providerAccountId(),
                lineage.requestId(), lineage.attemptId());
        return jdbc.queryForObject(
                "SELECT id FROM reconciliation_evidence WHERE org_id=? AND evidence_key=?",
                Long.class, orgId, evidenceKey);
    }

    private long insertUsage(GatewayLineage lineage, String usageStatus) {
        jdbc.update("""
                INSERT INTO gateway_usage_fact(org_id,request_id,route_attempt_id,sequence,
                  status,usage_effective_at,usage_effective_at_source,pricing_version_id,
                  currency,observed_at,created_at)
                VALUES (?,?,?,1,?,UTC_TIMESTAMP(6),
                  'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?,'USD',UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6))
                """, orgId, lineage.requestId(), lineage.attemptId(), usageStatus,
                lineage.pricingVersionId());
        var usageId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_usage_fact_id=? WHERE id=?",
                usageId, lineage.requestId());
        return usageId;
    }

    private long insertSettlement(GatewayLineage lineage, Long usageId, String status) {
        jdbc.update("""
                INSERT INTO gateway_settlement(
                  org_id,settlement_key,request_id,route_attempt_id,usage_fact_id,reservation_id,
                  billing_period_id,financial_scope_type,financial_scope_id,provider_account_id,
                  provider_model_id,pricing_version_id,currency,status,attempt_count,
                  created_at,updated_at)
                VALUES (?,?,?,?,?,NULL,?,'PROJECT',?,?,?,?,'USD',?,0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "M15 settlement " + UUID.randomUUID(), lineage.requestId(),
                lineage.attemptId(), usageId, periodId, projectId, lineage.providerAccountId(),
                lineage.providerModelId(), lineage.pricingVersionId(), status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertRouteAttempt(long requestId, String status) {
        var lineage = currentLineageOf(requestId);
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,created_at)
                VALUES (?,?,2,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, orgId, requestId, fixedRequestId(), lineage.providerAccountId(),
                lineage.providerModelId(), lineage.pricingVersionId(), status);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void assertActionableTotal(int expected) throws Exception {
        mvc.perform(get("/api/v1/reconciliation-runs/%d/evidence".formatted(runId))
                        .header("Authorization", bearer())
                        .param("matchKind", "GATEWAY_UNRESOLVED")
                        .param("actionableOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(expected))
                .andExpect(jsonPath("$.items.length()").value(expected));
    }

    private void assertUnfilteredTotal(String matchKind, int expected) throws Exception {
        mvc.perform(get("/api/v1/reconciliation-runs/%d/evidence".formatted(runId))
                        .header("Authorization", bearer())
                        .param("matchKind", matchKind))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(expected));
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
