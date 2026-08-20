package com.aicostops.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.ledger.application.LedgerPostingCommands.CommitmentLink;
import com.aicostops.ledger.application.LedgerPostingCommands.PostSourceCommand;
import com.aicostops.ledger.application.ExpensePostingService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** Expense posting invariants against the migrated MySQL schema. */
@SpringBootTest
@Tag("integration")
class ExpensePostingIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private ExpensePostingService postings;

    private long periodId;
    private long expenseId;
    private long decisionId;
    private long firstLineId;
    private long exactBudgetId;
    private long orgBudgetId;
    private long commitmentId;

    @BeforeEach
    void fixture() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN ('EXPENSE_POST','LEDGER_POST')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        periodId = insertPeriod();
        expenseId = insertApprovedExpense("10.00000000");
        decisionId = insertConfirmedDecision();
        jdbc.update("UPDATE expense_claim SET current_allocation_decision_id=? WHERE id=?",
                decisionId, expenseId);
        exactBudgetId = insertBudget("PROJECT", projectId, "6.00000000");
        orgBudgetId = insertBudget("ORG", orgId, "100.00000000");
        jdbc.update("UPDATE budget SET committed_amount='3.00000000' WHERE id=?", exactBudgetId);
        commitmentId = insertCommitment(exactBudgetId, "3.00000000");
    }

    @Test
    void postsExpenseEntriesMarksPostedAndReplaysStableKey() {
        var actor = new AuthenticatedUser(actorUserId, 7);
        var first = postings.post(actor, expenseId,
                new PostSourceCommand(java.util.List.of(new CommitmentLink(firstLineId, commitmentId))));

        assertThat(first.posting().postingKey()).isEqualTo("EXPENSE:" + expenseId);
        assertThat(first.posting().sourceType().name()).isEqualTo("EXPENSE_CLAIM");
        assertThat(first.entries()).hasSize(2);
        assertThat(first.entries().get(0).sourceExpenseClaimId()).isEqualTo(expenseId);
        assertThat(expenseStatus(expenseId)).isEqualTo("POSTED");
        assertThat(expenseVersion(expenseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, orgId)).isEqualTo(1);
        assertThat(auditCount("LEDGER_EXPENSE_POSTED")).isEqualTo(1);

        var replay = postings.post(actor, expenseId, new PostSourceCommand(java.util.List.of()));
        assertThat(replay.posting().id()).isEqualTo(first.posting().id());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE org_id=?",
                Integer.class, orgId)).isEqualTo(2);
        assertThat(auditCount("LEDGER_EXPENSE_POSTED")).isEqualTo(1);
    }

    @Test
    void committedPostingReplaysAfterPeriodClosesWithoutMutation() {
        var actor = new AuthenticatedUser(actorUserId, 7);
        var first = postings.post(actor, expenseId,
                new PostSourceCommand(java.util.List.of(new CommitmentLink(firstLineId, commitmentId))));
        var postingCount = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, orgId);
        var entryCount = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE org_id=?",
                Integer.class, orgId);
        var version = expenseVersion(expenseId);
        var exactActual = jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, exactBudgetId);
        var orgActual = jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, orgBudgetId);
        var remaining = jdbc.queryForObject("SELECT remaining_amount FROM budget_commitment WHERE id=?",
                BigDecimal.class, commitmentId);
        var usageCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_commitment_usage WHERE budget_commitment_id=?",
                Integer.class, commitmentId);
        var auditCount = auditCount("LEDGER_EXPENSE_POSTED");

        jdbc.update("UPDATE billing_period SET status='CLOSED' WHERE id=?", periodId);

        var replay = postings.post(actor, expenseId, new PostSourceCommand(java.util.List.of()));

        assertThat(replay.posting().id()).isEqualTo(first.posting().id());
        assertThat(expenseStatus(expenseId)).isEqualTo("POSTED");
        assertThat(expenseVersion(expenseId)).isEqualTo(version);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, orgId)).isEqualTo(postingCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE org_id=?",
                Integer.class, orgId)).isEqualTo(entryCount);
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, exactBudgetId)).isEqualByComparingTo(exactActual);
        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, orgBudgetId)).isEqualByComparingTo(orgActual);
        assertThat(jdbc.queryForObject("SELECT remaining_amount FROM budget_commitment WHERE id=?",
                BigDecimal.class, commitmentId)).isEqualByComparingTo(remaining);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_commitment_usage WHERE budget_commitment_id=?",
                Integer.class, commitmentId)).isEqualTo(usageCount);
        assertThat(auditCount("LEDGER_EXPENSE_POSTED")).isEqualTo(auditCount);
    }

    @Test
    void noBudgetDoesNotBlockExpensePosting() {
        jdbc.update("DELETE FROM budget_commitment WHERE id=?", commitmentId);
        jdbc.update("DELETE FROM budget WHERE id IN (?,?)", exactBudgetId, orgBudgetId);

        var detail = postings.post(new AuthenticatedUser(actorUserId, 7), expenseId,
                new PostSourceCommand(java.util.List.of()));

        assertThat(detail.entries()).allMatch(entry -> entry.budgetId() == null);
        assertThat(expenseStatus(expenseId)).isEqualTo("POSTED");
    }

    @Test
    void overBudgetActualStillPosts() {
        jdbc.update("UPDATE budget SET actual_amount='6.00000000' WHERE id=?", exactBudgetId);

        postings.post(new AuthenticatedUser(actorUserId, 7), expenseId,
                new PostSourceCommand(java.util.List.of()));

        assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, exactBudgetId)).isEqualByComparingTo("12.00000000");
        var available = jdbc.queryForObject("""
                SELECT total_amount-actual_amount-committed_amount FROM budget WHERE id=?
                """, BigDecimal.class, exactBudgetId);
        assertThat(available.signum()).isNegative();
    }

    @Test
    void negativeCreditCannotConsumeAnExplicitCommitment() {
        var secondLineId = jdbc.queryForObject(
                "SELECT id FROM allocation_line WHERE decision_id=? AND line_index=1",
                Long.class, decisionId);
        jdbc.update("UPDATE allocation_line SET allocated_amount='-2.00000000' WHERE id=?",
                firstLineId);
        jdbc.update("UPDATE allocation_line SET allocated_amount='12.00000000' WHERE id=?",
                secondLineId);

        assertThatThrownBy(() -> postings.post(new AuthenticatedUser(actorUserId, 7), expenseId,
                new PostSourceCommand(java.util.List.of(new CommitmentLink(firstLineId, commitmentId)))))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("Credits");
        assertThat(expenseStatus(expenseId)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
                Integer.class, orgId)).isZero();
        assertThat(jdbc.queryForObject("SELECT remaining_amount FROM budget_commitment WHERE id=?",
                BigDecimal.class, commitmentId)).isEqualByComparingTo("3.00000000");
    }

    private long insertPeriod() {
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?, '2026-08-01 00:00:00.000000','2026-09-01 00:00:00.000000',
                    'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        return jdbc.queryForObject("SELECT MAX(id) FROM billing_period WHERE org_id=?",
                Long.class, orgId);
    }

    private long insertConfirmedDecision() {
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?, 'EXPENSE_CLAIM', NULL, ?, 'MANUAL', NULL, 'CONFIRMED', ?, UTC_TIMESTAMP(6))
                """, orgId, expenseId, actorMemberId);
        var id = jdbc.queryForObject(
                "SELECT MAX(id) FROM allocation_decision WHERE org_id=? AND expense_claim_id=?",
                Long.class, orgId, expenseId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 0, '6.00000000','CNY', ?, NULL, NULL, UTC_TIMESTAMP(6))
                """, orgId, id, projectId);
        firstLineId = jdbc.queryForObject(
                "SELECT id FROM allocation_line WHERE decision_id=? AND line_index=0",
                Long.class, id);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 1, '4.00000000','CNY', NULL, NULL, ?, UTC_TIMESTAMP(6))
                """, orgId, id, teamId);
        return id;
    }

    private long insertBudget(String scopeType, long scopeId, String total) {
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

    private long insertCommitment(long budgetId, String remaining) {
        jdbc.update("""
                INSERT INTO budget_commitment(
                    org_id,budget_id,status,requested_amount,approved_amount,remaining_amount,
                    version,created_at,updated_at)
                VALUES (?, ?, 'ACTIVE', ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orgId, budgetId, remaining, remaining, remaining);
        return jdbc.queryForObject("SELECT MAX(id) FROM budget_commitment WHERE org_id=?",
                Long.class, orgId);
    }

    private String expenseStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM expense_claim WHERE id=?", String.class, id);
    }

    private long expenseVersion(long id) {
        return jdbc.queryForObject("SELECT version FROM expense_claim WHERE id=?", Long.class, id);
    }
}
