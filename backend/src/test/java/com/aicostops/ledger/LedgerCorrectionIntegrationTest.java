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
