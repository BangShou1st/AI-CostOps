package com.aicostops.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.ledger.application.LedgerCorrectionService;
import com.aicostops.ledger.application.LedgerPostingCommands.CorrectionCommand;
import com.aicostops.ledger.application.LedgerPostingCommands.PostSourceCommand;
import com.aicostops.ledger.application.ProviderChargePostingService;
import com.aicostops.ledger.domain.CorrectionMode;
import com.aicostops.shared.security.AuthenticatedUser;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Small real-MySQL financial invariant matrix for no-budget, overrun and correction. */
@SpringBootTest
@Tag("integration")
class LedgerFinancialInvariantIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private ProviderChargePostingService providerPostings;
    @Autowired
    private LedgerCorrectionService corrections;

    private long sourcePeriodId;
    private long correctionPeriodId;
    private long sourceChargeId;
    private long sourceEntryId;

    @BeforeEach
    void fixture() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN ('LEDGER_POST','LEDGER_CORRECT')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        sourcePeriodId = insertPeriod(JAN_1, FEB_1);
        correctionPeriodId = insertPeriod(FEB_1, MAR_1);
        sourceChargeId = insertCharge("6.00000000");
        var decisionId = insertDecision(sourceChargeId, "6.00000000");
        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, sourceChargeId);
    }

    @Test
    void noBudgetPostingAndOverBudgetActualBothRemainAllowed() {
        var noBudget = providerPostings.post(new AuthenticatedUser(actorUserId, 7), sourceChargeId,
                new PostSourceCommand(java.util.List.of()));
        sourceEntryId = noBudget.entries().getFirst().id();
        assertThat(noBudget.entries().getFirst().budgetId()).isNull();

        var secondCharge = insertCharge("6.00000000");
        var secondDecision = insertDecision(secondCharge, "6.00000000");
        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                secondDecision, secondCharge);
        var budgetId = insertBudget(sourcePeriodId, projectId, "1.00000000");
        var overBudget = providerPostings.post(new AuthenticatedUser(actorUserId, 7), secondCharge,
                new PostSourceCommand(java.util.List.of()));

        assertThat(overBudget.entries().getFirst().budgetId()).isEqualTo(budgetId);
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?", BigDecimal.class,
                budgetId)).isEqualByComparingTo("6.00000000");
        assertThat(jdbc.queryForObject("SELECT total_amount-actual_amount FROM budget WHERE id=?",
                BigDecimal.class, budgetId)).isNegative();
    }

    @Test
    void correctionChangesOnlyTheCorrectionPeriodAndPreservesHistoricalEntry() {
        var posted = providerPostings.post(new AuthenticatedUser(actorUserId, 7), sourceChargeId,
                new PostSourceCommand(java.util.List.of()));
        sourceEntryId = posted.entries().getFirst().id();
        var historical = jdbc.queryForMap("SELECT amount,posting_id,correction_group_id FROM ledger_entry WHERE id=?",
                sourceEntryId);
        var correctionBudgetId = insertBudget(correctionPeriodId, projectId, "100.00000000");

        corrections.correct(new AuthenticatedUser(actorUserId, 7),
                new CorrectionCommand(sourceEntryId, correctionPeriodId, CorrectionMode.REVERSAL_ONLY,
                        "ALLOCATION_ERROR", null, null), "financial-invariant-correction");

        var unchanged = jdbc.queryForMap("SELECT amount,posting_id,correction_group_id FROM ledger_entry WHERE id=?",
                sourceEntryId);
        assertThat(unchanged).isEqualTo(historical);
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?", BigDecimal.class,
                correctionBudgetId)).isEqualByComparingTo("-6.00000000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE reverses_entry_id=?",
                Integer.class, sourceEntryId)).isEqualTo(1);
    }

    private long insertPeriod(String start, String end) {
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?, ?, ?, 'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, start, end);
        return jdbc.queryForObject("SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
    }

    private long insertDecision(long chargeId, String amount) {
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?, 'CHARGE_FACT', ?, NULL, 'MANUAL', NULL, 'CONFIRMED', ?, UTC_TIMESTAMP(6))
                """, orgId, chargeId, actorMemberId);
        var id = jdbc.queryForObject("SELECT MAX(id) FROM allocation_decision WHERE org_id=?",
                Long.class, orgId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 0, ?, 'CNY', ?, NULL, NULL, UTC_TIMESTAMP(6))
                """, orgId, id, amount, projectId);
        return id;
    }

    private long insertBudget(long periodId, long projectId, String total) {
        jdbc.update("""
                INSERT INTO budget(
                    org_id,billing_period_id,scope_type,scope_id,currency,
                    total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?, ?, 'PROJECT', ?, 'CNY', ?, 0, 0, 'ACTIVE', 0, UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, projectId, total);
        return jdbc.queryForObject("""
                SELECT id FROM budget
                WHERE org_id=? AND billing_period_id=? AND scope_type='PROJECT' AND scope_id=?
                """, Long.class, orgId, periodId, projectId);
    }
}
