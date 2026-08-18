package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import com.aicostops.budget.application.BudgetCommitmentCommandService;
import com.aicostops.budget.application.BudgetCommitmentCommands.RequestCommitmentCommand;
import com.aicostops.budget.application.CommitmentAuditPort;
import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The audit write is part of the request transaction: when the audit port
 * fails, the whole command must roll back — no commitment, no approval case,
 * no SUBMIT action, no audit row, and no leftover provisional idempotency
 * row. The audit port is the only mocked seam; everything else runs against
 * the real MySQL Testcontainer.
 */
@SpringBootTest
@Tag("integration")
class CommitmentRequestAuditRollbackIntegrationTest extends CommitmentTestSupport {

    @Autowired
    private BudgetCommitmentCommandService commands;

    @MockitoBean
    private CommitmentAuditPort auditPort;

    @Test
    void requestRollsBackEverythingWhenTheAuditWriteFails() {
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        doThrow(new IllegalStateException("simulated audit outage"))
                .when(auditPort).requested(anyLong(), anyLong(), anyLong(), anyLong(), any());

        assertThatThrownBy(() -> commands.request(requesterUser(),
                new RequestCommitmentCommand(budgetId, new BigDecimal("5.00000000"), "CNY"),
                "req-audit-failure"))
                .isInstanceOf(IllegalStateException.class);

        // No half state: no commitment, no case, no action, no audit, and the
        // idempotency reservation rolled back with the transaction.
        var commitments = jdbc.queryForObject("""
                SELECT COUNT(*) FROM budget_commitment WHERE org_id=? AND budget_id=?
                """, Integer.class, orgId, budgetId);
        assertThat(commitments).isZero();
        var cases = jdbc.queryForObject("""
                SELECT COUNT(*) FROM approval_case WHERE org_id=?
                """, Integer.class, orgId);
        assertThat(cases).isZero();
        assertThat(auditCount("COMMITMENT_REQUESTED")).isZero();
        var provisional = jdbc.queryForObject("""
                SELECT COUNT(*) FROM api_idempotency
                WHERE org_id=? AND operation='COMMITMENT_REQUEST'
                """, Integer.class, orgId);
        assertThat(provisional).isZero();
        // The budget counter is untouched too.
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
    }
}
