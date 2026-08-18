package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import com.aicostops.budget.application.BudgetAuditPort;
import com.aicostops.budget.application.BudgetCommandService;
import com.aicostops.budget.application.BudgetCommands.CreateBudgetCommand;
import com.aicostops.iam.domain.ScopeType;
import com.aicostops.shared.security.AuthenticatedUser;
import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Budget creation must be one MySQL transaction: the INSERT, the
 * generated-id readback, and the BUDGET_CREATED audit commit or roll back
 * together. When the audit write fails inside the create transaction, the
 * just-inserted budget row must not survive. This proves the transaction
 * boundary with the real MySQL Testcontainer and a test-only failing audit
 * port — not by mocking the mapper.
 */
@SpringBootTest
@Tag("integration")
class BudgetCreateTransactionIntegrationTest extends BudgetTestSupport {

    @Autowired
    private BudgetCommandService commands;

    /** Replaces the real audit adapter with a failing double for this class. */
    @MockitoBean
    private BudgetAuditPort audit;

    @Test
    void auditFailureInsideCreateRollsBackTheInsertedBudget() {
        doThrow(new IllegalStateException("test audit failure"))
                .when(audit).created(anyLong(), anyLong(), anyLong(), anyString(),
                        anyString(), anyLong(), any(BigDecimal.class));

        var command = new CreateBudgetCommand(periodId, ScopeType.PROJECT, projectId, "CNY",
                new BigDecimal("1000.00000000"));

        assertThatThrownBy(() -> commands.create(
                new AuthenticatedUser(managerUserId, 7), command))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("test audit failure");

        assertBudgetCountZero("PROJECT", projectId, "CNY");
    }

    @Test
    void successfulCreateIsStillVisibleAndAudited() {
        org.mockito.Mockito.reset(audit);
        var command = new CreateBudgetCommand(periodId, ScopeType.PROJECT, projectId, "CNY",
                new BigDecimal("1000.00000000"));

        var created = commands.create(new AuthenticatedUser(managerUserId, 7), command);

        assertThat(created.totalAmount()).isEqualByComparingTo("1000.00000000");
        assertThat(created.scopeType()).isEqualTo(ScopeType.PROJECT);
        assertThat(created.scopeId()).isEqualTo(projectId);
        var count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM budget
                WHERE org_id=? AND billing_period_id=? AND scope_type=? AND scope_id=? AND currency=?
                """, Integer.class, orgId, periodId, "PROJECT", projectId, "CNY");
        assertThat(count).isEqualTo(1);
        // The mock audit port records nothing, but the service still invokes it.
        org.mockito.Mockito.verify(audit).created(anyLong(), anyLong(), anyLong(), anyString(),
                anyString(), anyLong(), any(BigDecimal.class));
    }

    private void assertBudgetCountZero(String scopeType, long scopeId, String currency) {
        var count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM budget
                WHERE org_id=? AND billing_period_id=? AND scope_type=? AND scope_id=? AND currency=?
                """, Integer.class, orgId, periodId, scopeType, scopeId, currency);
        assertThat(count).as("budget with the natural identity must be rolled back").isZero();
    }
}