package com.aicostops.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.ledger.application.LedgerAuditPort;
import com.aicostops.ledger.application.LedgerCorrectionService;
import com.aicostops.ledger.application.LedgerPostingCommands.CorrectionCommand;
import com.aicostops.ledger.application.LedgerPostingCommands.PostSourceCommand;
import com.aicostops.ledger.application.ProviderChargePostingService;
import com.aicostops.ledger.domain.CorrectionMode;
import com.aicostops.shared.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Audit failure must roll back every correction-side financial mutation. */
@SpringBootTest
@Tag("integration")
class LedgerCorrectionRollbackIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private ProviderChargePostingService providerPostings;
    @Autowired
    private LedgerCorrectionService corrections;
    @MockitoBean
    private LedgerAuditPort audit;

    private long targetEntryId;
    private long correctionPeriodId;
    private long correctionBudgetId;

    @BeforeEach
    void fixture() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN ('LEDGER_POST','LEDGER_CORRECT')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        var sourcePeriodId = insertPeriod(JAN_1, FEB_1);
        correctionPeriodId = insertPeriod(FEB_1, MAR_1);
        correctionBudgetId = insertBudget(correctionPeriodId, "PROJECT", projectId);
        var chargeId = insertCharge("6.00000000");
        var decisionId = insertDecision(chargeId);
        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, chargeId);
        var posted = providerPostings.post(new AuthenticatedUser(actorUserId, 7), chargeId,
                new PostSourceCommand(java.util.List.of()));
        targetEntryId = posted.entries().getFirst().id();
        assertThat(posted.posting().billingPeriodId()).isEqualTo(sourcePeriodId);
    }

    @Test
    void auditFailureRollsBackCorrectionPostingAndBudgetActual() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit).correctionPosted(
                anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyInt(), anyString());
        var command = new CorrectionCommand(targetEntryId, correctionPeriodId,
                CorrectionMode.REVERSAL_ONLY, "ALLOCATION_ERROR", null, null);

        assertThatThrownBy(() -> corrections.correct(new AuthenticatedUser(actorUserId, 7), command,
                "rollback-correction")).isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        verify(audit).correctionPosted(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyString(),
                anyInt(), anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM correction_group WHERE org_id=?",
                Integer.class, orgId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, orgId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                java.math.BigDecimal.class, correctionBudgetId)).isEqualByComparingTo("0.00000000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency WHERE org_id=?",
                Integer.class, orgId)).isZero();
    }

    private long insertPeriod(String start, String end) {
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?, ?, ?, 'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, start, end);
        return jdbc.queryForObject("SELECT MAX(id) FROM billing_period WHERE org_id=?",
                Long.class, orgId);
    }

    private long insertDecision(long chargeId) {
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

    private long insertBudget(long periodId, String scopeType, long scopeId) {
        jdbc.update("""
                INSERT INTO budget(
                    org_id,billing_period_id,scope_type,scope_id,currency,
                    total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?, ?, ?, ?, 'CNY', 100, 0, 0, 'ACTIVE', 0, UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, scopeType, scopeId);
        return jdbc.queryForObject("""
                SELECT id FROM budget
                WHERE org_id=? AND billing_period_id=? AND scope_type=? AND scope_id=?
                """, Long.class, orgId, periodId, scopeType, scopeId);
    }
}
