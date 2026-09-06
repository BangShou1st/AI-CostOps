package com.aicostops.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.ledger.application.LedgerCorrectionService;
import com.aicostops.ledger.application.LedgerPostingCommands.CorrectionCommand;
import com.aicostops.ledger.application.LedgerPostingCommands.CorrectionCommand.Replacement;
import com.aicostops.ledger.application.LedgerPostingCommands.PostSourceCommand;
import com.aicostops.ledger.application.ProviderChargePostingService;
import com.aicostops.ledger.domain.CorrectionMode;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Immutable correction behavior against real MySQL row locks and foreign keys. */
@SpringBootTest
@Tag("integration")
class LedgerCorrectionIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private ProviderChargePostingService providerPostings;
    @Autowired
    private LedgerCorrectionService corrections;

    private final AuthenticatedUser actor = new AuthenticatedUser(0, 7);
    private long chargeId;
    private long targetEntryId;
    private long correctionPeriodId;
    private long sourcePostingId;

    @BeforeEach
    void fixture() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN ('LEDGER_POST','LEDGER_CORRECT')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        var sourcePeriodId = insertPeriod(JAN_1, FEB_1, "OPEN");
        correctionPeriodId = insertPeriod(FEB_1, MAR_1, "OPEN");
        chargeId = insertCharge("6.00000000");
        var decisionId = insertConfirmedDecision(chargeId);
        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, chargeId);
        var posted = providerPostings.post(new AuthenticatedUser(actorUserId, 7), chargeId,
                new PostSourceCommand(java.util.List.of()));
        sourcePostingId = posted.posting().id();
        targetEntryId = posted.entries().getFirst().id();
        assertThat(posted.posting().billingPeriodId()).isEqualTo(sourcePeriodId);
    }

    @Test
    void reversalOnlyKeepsHistoricalRowsAndReplaysByIdempotencyKey() {
        var command = new CorrectionCommand(targetEntryId, correctionPeriodId,
                CorrectionMode.REVERSAL_ONLY, "ALLOCATION_ERROR", "Move out of old period", null);
        var result = corrections.correct(new AuthenticatedUser(actorUserId, 7), command, "corr-1");

        assertThat(result.correctionGroup().correctionKey()).startsWith("CORRECTION_COMMAND:");
        assertThat(result.posting().posting().postingKey())
                .isEqualTo("CORRECTION:" + result.correctionGroup().id());
        assertThat(result.posting().posting().billingPeriodId()).isEqualTo(correctionPeriodId);
        assertThat(result.posting().entries()).hasSize(1);
        var reversal = result.posting().entries().getFirst();
        assertThat(reversal.entryType().name()).isEqualTo("REVERSAL");
        assertThat(reversal.amount()).isEqualByComparingTo("-6.00000000");
        assertThat(reversal.reversesEntryId()).isEqualTo(targetEntryId);
        assertThat(reversal.sourceChargeFactId()).isEqualTo(chargeId);
        assertThat(reversal.projectId()).isEqualTo(projectId);
        assertThat(reversal.allocationLineId()).isEqualTo(jdbc.queryForObject(
                "SELECT allocation_line_id FROM ledger_entry WHERE id=?", Long.class, targetEntryId));

        var historical = jdbc.queryForMap("SELECT * FROM ledger_entry WHERE id=?", targetEntryId);
        assertThat(historical.get("posting_id")).isEqualTo(sourcePostingId);
        assertThat(historical.get("correction_group_id")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, orgId)).isEqualTo(2);
        assertThat(auditCount("LEDGER_CORRECTION_POSTED")).isEqualTo(1);

        var replay = corrections.correct(new AuthenticatedUser(actorUserId, 7), command, "corr-1");
        assertThat(replay.correctionGroup().id()).isEqualTo(result.correctionGroup().id());
        assertThat(replay.posting().posting().id()).isEqualTo(result.posting().posting().id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE org_id=?",
                Integer.class, orgId)).isEqualTo(2);
        assertThat(auditCount("LEDGER_CORRECTION_POSTED")).isEqualTo(1);

        var changed = new CorrectionCommand(targetEntryId, correctionPeriodId,
                CorrectionMode.REVERSAL_ONLY, "OTHER_REASON", null, null);
        assertThatThrownBy(() -> corrections.correct(new AuthenticatedUser(actorUserId, 7), changed,
                "corr-1")).isInstanceOf(DomainException.class)
                .hasMessageContaining("different request");
    }

    @Test
    void replaceWritesSignedCorrectionPeriodActualsWithoutCommitmentUsage() {
        var budgetId = insertBudget(correctionPeriodId, "PROJECT", projectId, "100.00000000");
        var orgBudgetId = insertBudget(correctionPeriodId, "ORG", orgId, "100.00000000");
        var command = new CorrectionCommand(targetEntryId, correctionPeriodId,
                CorrectionMode.REPLACE, "ALLOCATION_ERROR", null,
                new Replacement(new BigDecimal("10.00000000"), "CNY", null, null, teamId));

        var result = corrections.correct(new AuthenticatedUser(actorUserId, 7), command, "corr-2");

        assertThat(result.posting().entries()).hasSize(2);
        assertThat(result.posting().entries().get(0).amount()).isEqualByComparingTo("-6.00000000");
        assertThat(result.posting().entries().get(1).amount()).isEqualByComparingTo("10.00000000");
        assertThat(result.posting().entries().get(0).budgetId()).isEqualTo(budgetId);
        assertThat(result.posting().entries().get(1).budgetId()).isEqualTo(orgBudgetId);
        assertThat(result.posting().entries().get(1).allocationLineId()).isNull();
        assertThat(result.posting().entries().get(1).sourceChargeFactId()).isEqualTo(chargeId);
        assertThat(result.posting().entries().get(1).sourceExpenseClaimId()).isNull();
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?", BigDecimal.class,
                budgetId)).isEqualByComparingTo("-6.00000000");
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?", BigDecimal.class,
                orgBudgetId)).isEqualByComparingTo("10.00000000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM budget_commitment_usage WHERE org_id=?",
                Integer.class, orgId)).isZero();
    }

    @Test
    void replaceRejectsCurrencyChangeBeforeWritingCorrectionRows() {
        var command = new CorrectionCommand(targetEntryId, correctionPeriodId,
                CorrectionMode.REPLACE, "CURRENCY_ERROR", null,
                new Replacement(new BigDecimal("10.00000000"), "USD", null, null, teamId));

        assertThatThrownBy(() -> corrections.correct(new AuthenticatedUser(actorUserId, 7), command,
                "corr-currency")).isInstanceOf(DomainException.class)
                .hasMessageContaining("Replacement currency must match");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM correction_group WHERE org_id=?",
                Integer.class, orgId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, orgId)).isEqualTo(1);
    }

    @Test
    void replaceRejectsMissingForeignArchivedAndDisabledTargetsWithoutRows() {
        var foreignProject = insertTarget("project", foreignOrgId, "corr-foreign-target");
        var archivedProject = insertTarget("project", orgId, "corr-archived-target");
        var disabledProject = insertTarget("project", orgId, "corr-disabled-target");
        jdbc.update("UPDATE project SET status='ARCHIVED' WHERE id=?", archivedProject);
        jdbc.update("UPDATE project SET status='DISABLED' WHERE id=?", disabledProject);

        assertInvalidReplacement(999_999_991L, "corr-missing-target");
        assertInvalidReplacement(foreignProject, "corr-foreign-target");
        assertInvalidReplacement(archivedProject, "corr-archived-target");
        assertInvalidReplacement(disabledProject, "corr-disabled-target");
        assertNoCorrectionRows();
    }

    @Test
    void closedPeriodAndDoubleReversalRejectWithoutMutatingHistory() {
        jdbc.update("UPDATE billing_period SET status='CLOSED' WHERE id=?", correctionPeriodId);
        var command = new CorrectionCommand(targetEntryId, correctionPeriodId,
                CorrectionMode.REVERSAL_ONLY, "ALLOCATION_ERROR", null, null);
        assertThatThrownBy(() -> corrections.correct(new AuthenticatedUser(actorUserId, 7), command,
                "corr-closed")).isInstanceOf(DomainException.class)
                .hasMessageContaining("CLOSED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM correction_group WHERE org_id=?",
                Integer.class, orgId)).isZero();

        jdbc.update("UPDATE billing_period SET status='OPEN' WHERE id=?", correctionPeriodId);
        corrections.correct(new AuthenticatedUser(actorUserId, 7), command, "corr-first");
        assertThatThrownBy(() -> corrections.correct(new AuthenticatedUser(actorUserId, 7), command,
                "corr-second")).isInstanceOf(DomainException.class)
                .hasMessageContaining("already been reversed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM correction_group WHERE org_id=?",
                Integer.class, orgId)).isEqualTo(1);
    }

    @Test
    void gatewaySettlementCorrectionPreservesDirectSourceLineage() {
        var settlementEntryId = insertGatewaySettlementLedgerEntry("10.00000000");

        var command = new CorrectionCommand(settlementEntryId, correctionPeriodId,
                CorrectionMode.REVERSAL_ONLY, "STATEMENT_MISMATCH", "Provider statement differs",
                null);
        var result = corrections.correct(new AuthenticatedUser(actorUserId, 7), command,
                "corr-gw-" + System.nanoTime());

        var reversal = result.posting().entries().getFirst();
        var expectedSettlementId = jdbc.queryForObject(
                "SELECT source_gateway_settlement_id FROM ledger_entry WHERE id=?",
                Long.class, settlementEntryId);
        assertThat(reversal.sourceGatewaySettlementId()).isEqualTo(expectedSettlementId);
        assertThat(countDirectSources(reversal.id())).isEqualTo(1);
        // The historical target is unchanged.
        var historical = jdbc.queryForMap("SELECT * FROM ledger_entry WHERE id=?",
                settlementEntryId);
        assertThat(historical.get("source_gateway_settlement_id"))
                .isEqualTo(expectedSettlementId);
    }

    @Test
    void gatewaySettlementReplacementPreservesDirectSourceLineage() {
        var settlementEntryId = insertGatewaySettlementLedgerEntry("10.00000000");

        var command = new CorrectionCommand(settlementEntryId, correctionPeriodId,
                CorrectionMode.REPLACE, "STATEMENT_MISMATCH", "Corrected to statement amount",
                new Replacement(new BigDecimal("12.00000000"), "CNY", projectId, null, null));
        var result = corrections.correct(new AuthenticatedUser(actorUserId, 7), command,
                "corr-gw-r-" + System.nanoTime());

        var expectedSettlementId = jdbc.queryForObject(
                "SELECT source_gateway_settlement_id FROM ledger_entry WHERE id=?",
                Long.class, settlementEntryId);
        for (var entry : result.posting().entries()) {
            assertThat(entry.sourceGatewaySettlementId()).isEqualTo(expectedSettlementId);
            assertThat(countDirectSources(entry.id())).isEqualTo(1);
        }
    }

    @Test
    void reconciliationAdjustmentCorrectionPreservesDirectSourceLineage() {
        var adjustmentEntryId = insertAdjustmentLedgerEntry("3.00000000");

        var command = new CorrectionCommand(adjustmentEntryId, correctionPeriodId,
                CorrectionMode.REVERSAL_ONLY, "ADJUSTMENT_ERROR", "Wrong adjustment basis",
                null);
        var result = corrections.correct(new AuthenticatedUser(actorUserId, 7), command,
                "corr-adj-" + System.nanoTime());

        var reversal = result.posting().entries().getFirst();
        var expectedAdjustmentId = jdbc.queryForObject(
                "SELECT source_reconciliation_adjustment_id FROM ledger_entry WHERE id=?",
                Long.class, adjustmentEntryId);
        assertThat(reversal.sourceReconciliationAdjustmentId()).isEqualTo(expectedAdjustmentId);
        assertThat(countDirectSources(reversal.id())).isEqualTo(1);
    }

    private long countDirectSources(long entryId) {
        return jdbc.queryForObject("""
                SELECT (CASE WHEN source_charge_fact_id IS NOT NULL THEN 1 ELSE 0 END)
                     + (CASE WHEN source_expense_claim_id IS NOT NULL THEN 1 ELSE 0 END)
                     + (CASE WHEN source_gateway_settlement_id IS NOT NULL THEN 1 ELSE 0 END)
                     + (CASE WHEN source_reconciliation_adjustment_id IS NOT NULL THEN 1 ELSE 0 END)
                FROM ledger_entry WHERE id=?
                """, Integer.class, entryId);
    }

    /** Gateway settlement lineage with its immutable Ledger entry; returns entry id. */
    private long insertGatewaySettlementLedgerEntry(String amount) {
        var suffix = "corr-gw-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO service_identity(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "svc-" + suffix, suffix);
        var serviceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO model_catalog(model_key,name,status,capabilities_json,
                  max_output_tokens,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),1024,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "model-" + suffix, suffix);
        var modelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        var providerCode = "MIMO-" + suffix;
        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                  capabilities_json,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, suffix, "MIMO", "https://provider.invalid");
        jdbc.update("""
                INSERT INTO provider_model(provider_code,model_id,provider_model_name,status,
                  routing_eligible,capabilities_json,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',TRUE,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, providerCode, modelId, "wire-" + suffix);
        var providerModelId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,
                  external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,?, 'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, suffix, suffix);
        var gwAccountId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,version,
                  currency,effective_from,status,created_at,activated_at)
                VALUES (?,?,?,1,'CNY','2026-01-01 00:00:00','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, gwAccountId, providerModelId);
        var pricingVersionId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_credential(org_id,credential_prefix,secret_digest,
                  secret_digest_version,principal_type,organization_member_id,service_identity_id,
                  project_id,financial_scope_type,financial_scope_id,budget_enforcement_mode,
                  status,created_at,updated_at)
                VALUES (?,?,?,1,'SERVICE',NULL,?,?,'PROJECT',?,'OPTIONAL','ACTIVE',
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix.substring(suffix.length() - 12), digest(51), serviceId,
                projectId, projectId);
        var credentialId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_request(org_id,public_request_id,credential_id,principal_type,
                  organization_member_id,service_identity_id,project_id,financial_scope_type,
                  financial_scope_id,logical_model_id,api_surface,idempotency_key_digest,
                  request_fingerprint,request_hmac_version,state,billing_period_id,created_at,
                  validated_at,updated_at)
                VALUES (?,?,?,'SERVICE',NULL,?,?,'PROJECT',?,?,'CHAT_COMPLETIONS',?,?,1,
                  'TRANSPORT_COMPLETED',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, fixedRequestId(), credentialId, serviceId, projectId, projectId,
                modelId, digest(52), digest(53));
        var requestId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO gateway_route_attempt(org_id,request_id,attempt_no,route_decision_id,
                  provider_account_id,provider_model_id,pricing_version_id,status,created_at)
                VALUES (?,?,1,?,?,?,?, 'COMPLETED',UTC_TIMESTAMP(6))
                """, orgId, requestId, fixedRequestId(), gwAccountId, providerModelId,
                pricingVersionId);
        var attemptId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_route_attempt_id=? WHERE id=?",
                attemptId, requestId);
        jdbc.update("""
                INSERT INTO gateway_usage_fact(org_id,request_id,route_attempt_id,sequence,status,
                  usage_effective_at,usage_effective_at_source,pricing_version_id,currency,
                  observed_at,created_at)
                VALUES (?,?,?,1,'FINAL',UTC_TIMESTAMP(6),
                  'GATEWAY_DISPATCH_INTENT_TIMESTAMP',?,'CNY',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, requestId, attemptId, pricingVersionId);
        var usageFactId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE gateway_request SET current_usage_fact_id=? WHERE id=?",
                usageFactId, requestId);
        jdbc.update("""
                INSERT INTO gateway_settlement(
                  org_id,settlement_key,request_id,route_attempt_id,usage_fact_id,reservation_id,
                  billing_period_id,financial_scope_type,financial_scope_id,provider_account_id,
                  provider_model_id,pricing_version_id,currency,status,attempt_count,
                  created_at,updated_at)
                VALUES (?,?,?,?,?,NULL,?,'PROJECT',?,?,?,?,?,'RECONCILIATION_REQUIRED',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "GATEWAY_REQUEST:" + suffix, requestId, attemptId, usageFactId,
                correctionPeriodId, projectId, gwAccountId, providerModelId, pricingVersionId,
                "CNY");
        var settlementId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO ledger_posting(
                    org_id,posting_key,source_type,source_id,allocation_decision_id,billing_period_id,
                    status,posting_actor_type,posted_by_member_id,posted_at,created_at)
                VALUES (?,?,'GATEWAY_SETTLEMENT',?,NULL,?,'POSTED','SYSTEM',NULL,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "GATEWAY_SETTLEMENT:" + settlementId, settlementId,
                correctionPeriodId);
        var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO ledger_entry(
                    org_id,posting_id,entry_index,entry_type,amount,currency,project_id,
                    source_gateway_settlement_id,created_at)
                VALUES (?,?,0,'COST',?,'CNY',?,?,UTC_TIMESTAMP(6))
                """, orgId, postingId, amount, projectId, settlementId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** Reconciliation adjustment lineage with its Ledger entry; returns entry id. */
    private long insertAdjustmentLedgerEntry(String amount) {
        jdbc.update("""
                INSERT INTO reconciliation_run(org_id,billing_period_id,status,algorithm_version,
                  tolerance_amount,basis_hash,summary_json,created_by_member_id,started_at,
                  finished_at,created_at,updated_at)
                VALUES (?,?,'COMPLETED','M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2','0.00000000',
                  ?,JSON_OBJECT(),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6))
                """, orgId, correctionPeriodId, "d".repeat(64), actorMemberId);
        var runId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO reconciliation_case(org_id,reconciliation_run_id,provider_account_id,
                  currency,case_type,external_amount,internal_amount,difference_amount,
                  external_row_count,internal_row_count,status,created_at,updated_at)
                VALUES (?,?,?,'CNY','AMOUNT_MISMATCH','12.00000000','10.00000000','-2.00000000',
                  1,1,'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, accountId);
        var caseId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO reconciliation_adjustment(
                  org_id,reconciliation_run_id,reconciliation_case_id,adjustment_key,
                  adjustment_scope,provider_account_id,currency,amount,adjustment_period_id,
                  created_by_member_id,reason_code,reason_note,created_at)
                VALUES (?,?,?,?,'CASE_FULL',?,'CNY',?,?,?, 'test','note',UTC_TIMESTAMP(6))
                """, orgId, runId, caseId, "adj-" + System.nanoTime(), accountId, amount,
                correctionPeriodId, actorMemberId);
        var adjustmentId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO ledger_posting(
                    org_id,posting_key,source_type,source_id,allocation_decision_id,billing_period_id,
                    status,posted_by_member_id,posted_at,created_at)
                VALUES (?,?,'RECONCILIATION_ADJUSTMENT',?,NULL,?,'POSTED',?,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "RECONCILIATION_ADJUSTMENT:" + adjustmentId, adjustmentId,
                correctionPeriodId, actorMemberId);
        var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO ledger_entry(
                    org_id,posting_id,entry_index,entry_type,amount,currency,project_id,
                    source_reconciliation_adjustment_id,created_at)
                VALUES (?,?,0,'ADJUSTMENT',?,'CNY',?,?,UTC_TIMESTAMP(6))
                """, orgId, postingId, amount, projectId, adjustmentId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private static String fixedRequestId() {
        return (java.util.UUID.randomUUID().toString().replace("-", "")
                + "0000000000000000000000000000").substring(0, 40);
    }

    private static byte[] digest(int seed) {
        var result = new byte[32];
        for (var i = 0; i < result.length; i++) {
            result[i] = (byte) (seed + i);
        }
        return result;
    }

    private long insertPeriod(String start, String end, String status) {
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?, ?, ?, ?, 0, NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, start, end, status);
        return jdbc.queryForObject("SELECT MAX(id) FROM billing_period WHERE org_id=?",
                Long.class, orgId);
    }

    private long insertConfirmedDecision(long chargeId) {
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?, 'CHARGE_FACT', ?, NULL, 'MANUAL', NULL, 'CONFIRMED', ?, UTC_TIMESTAMP(6))
                """, orgId, chargeId, actorMemberId);
        var decisionId = jdbc.queryForObject("SELECT MAX(id) FROM allocation_decision WHERE org_id=?",
                Long.class, orgId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 0, '6.00000000','CNY', ?, NULL, NULL, UTC_TIMESTAMP(6))
                """, orgId, decisionId, projectId);
        return decisionId;
    }

    private long insertBudget(long periodId, String scopeType, long scopeId, String total) {
        jdbc.update("""
                INSERT INTO budget(
                    org_id,billing_period_id,scope_type,scope_id,currency,
                    total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?, ?, ?, ?, 'CNY', ?, 0, 0, 'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orgId, periodId, scopeType, scopeId, total);
        return jdbc.queryForObject("""
                SELECT id FROM budget
                WHERE org_id=? AND billing_period_id=? AND scope_type=? AND scope_id=? AND currency='CNY'
                """, Long.class, orgId, periodId, scopeType, scopeId);
    }

    private void assertInvalidReplacement(long targetId, String idempotencyKey) {
        var command = new CorrectionCommand(targetEntryId, correctionPeriodId,
                CorrectionMode.REPLACE, "ALLOCATION_ERROR", null,
                new Replacement(new BigDecimal("10.00000000"), "CNY", targetId, null, null));
        assertThatThrownBy(() -> corrections.correct(new AuthenticatedUser(actorUserId, 7), command,
                idempotencyKey)).isInstanceOf(DomainException.class)
                .hasMessageContaining("ACTIVE");
    }

    private void assertNoCorrectionRows() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM correction_group WHERE org_id=?",
                Integer.class, orgId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, orgId)).isEqualTo(1);
    }
}
