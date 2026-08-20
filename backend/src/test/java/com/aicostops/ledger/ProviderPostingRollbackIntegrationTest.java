package com.aicostops.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.ledger.application.LedgerAuditPort;
import com.aicostops.ledger.application.LedgerPostingCommands.CommitmentLink;
import com.aicostops.ledger.application.LedgerPostingCommands.PostSourceCommand;
import com.aicostops.ledger.application.ProviderChargePostingService;
import com.aicostops.shared.security.AuthenticatedUser;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Proves the provider posting transaction rolls back all financial writes. */
@SpringBootTest
@Tag("integration")
class ProviderPostingRollbackIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private ProviderChargePostingService postings;

    @MockitoBean
    private LedgerAuditPort audit;

    private long periodId;
    private long chargeId;
    private long decisionId;
    private long lineId;
    private long budgetId;
    private long commitmentId;

    @BeforeEach
    void fixture() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code='LEDGER_POST'
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,
                    closing_started_at,closed_at,reopened_at,version,created_at,updated_at)
                VALUES (?, '2026-01-01 00:00:00.000000','2026-02-01 00:00:00.000000',
                    'OPEN',0,NULL,NULL,NULL,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        periodId = jdbc.queryForObject("SELECT MAX(id) FROM billing_period WHERE org_id=?",
                Long.class, orgId);
        chargeId = insertCharge("10.00000000");
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?, 'CHARGE_FACT', ?, NULL, 'MANUAL', NULL, 'CONFIRMED', ?, UTC_TIMESTAMP(6))
                """, orgId, chargeId, actorMemberId);
        decisionId = jdbc.queryForObject("SELECT MAX(id) FROM allocation_decision WHERE org_id=?",
                Long.class, orgId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?, ?, 0, '10.00000000','CNY', ?, NULL, NULL, UTC_TIMESTAMP(6))
                """, orgId, decisionId, projectId);
        lineId = jdbc.queryForObject("SELECT id FROM allocation_line WHERE decision_id=?",
                Long.class, decisionId);
        jdbc.update("UPDATE charge_fact SET current_allocation_decision_id=? WHERE id=?",
                decisionId, chargeId);
        budgetId = insertBudget();
        commitmentId = insertCommitment();
    }

    @Test
    void auditFailureRollsBackPostingEntriesActualAndCommitmentUsage() {
        doThrow(new IllegalStateException("simulated ledger audit outage"))
                .when(audit).chargePosted(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                        anyInt(), anyString());

        assertThatThrownBy(() -> postings.post(new AuthenticatedUser(actorUserId, 7), chargeId,
                new PostSourceCommand(java.util.List.of(new CommitmentLink(lineId, commitmentId)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated ledger audit outage");

        assertThat(count("ledger_posting")).isZero();
        assertThat(count("ledger_entry")).isZero();
        assertThat(amount("budget", "actual_amount", budgetId)).isEqualByComparingTo("0.00000000");
        assertThat(amount("budget", "committed_amount", budgetId)).isEqualByComparingTo("5.00000000");
        assertThat(jdbc.queryForObject("SELECT version FROM budget WHERE id=?", Long.class, budgetId))
                .isZero();
        assertThat(amount("budget_commitment", "remaining_amount", commitmentId))
                .isEqualByComparingTo("5.00000000");
        assertThat(countWhere("budget_commitment_usage", "budget_commitment_id", commitmentId)).isZero();
        assertThat(count("audit_event")).isZero();
    }

    private long insertBudget() {
        jdbc.update("""
                INSERT INTO budget(
                    org_id,billing_period_id,scope_type,scope_id,currency,
                    total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?, ?, 'PROJECT', ?, 'CNY', '100.00000000', 0, '5.00000000',
                    'ACTIVE', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, orgId, periodId, projectId);
        return jdbc.queryForObject("SELECT MAX(id) FROM budget WHERE org_id=?", Long.class, orgId);
    }

    private long insertCommitment() {
        jdbc.update("""
                INSERT INTO budget_commitment(
                    org_id,budget_id,status,requested_amount,approved_amount,remaining_amount,
                    version,created_at,updated_at)
                VALUES (?, ?, 'ACTIVE', '5.00000000','5.00000000','5.00000000',1,
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, budgetId);
        return jdbc.queryForObject("SELECT MAX(id) FROM budget_commitment WHERE org_id=?",
                Long.class, orgId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE org_id=?",
                Integer.class, orgId);
    }

    private int countWhere(String table, String column, long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE org_id=? AND "
                + column + "=?", Integer.class, orgId, id);
    }

    private BigDecimal amount(String table, String column, long id) {
        return jdbc.queryForObject("SELECT " + column + " FROM " + table + " WHERE id=?",
                BigDecimal.class, id);
    }
}
