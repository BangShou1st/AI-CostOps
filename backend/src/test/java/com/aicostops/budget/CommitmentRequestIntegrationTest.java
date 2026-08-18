package com.aicostops.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.budget.application.BudgetCommitmentCommandService;
import com.aicostops.budget.application.BudgetCommitmentCommands.RequestCommitmentCommand;
import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.math.BigDecimal;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

/**
 * AIC-044 request foundation: a REQUESTED budget commitment plus its PENDING
 * approval case and SUBMIT action are created in one MySQL transaction; the
 * request never touches budget.committed_amount; money is exact DECIMAL(20,8);
 * the budget must be a same-org ACTIVE budget whose currency matches; and the
 * idempotency rules (replay, same key + different hash = 409) follow the
 * project convention on the shared api_idempotency table. Audit-failure
 * rollback lives in CommitmentRequestAuditRollbackIntegrationTest.
 */
@SpringBootTest
@Tag("integration")
class CommitmentRequestIntegrationTest extends CommitmentTestSupport {

    @Autowired
    private BudgetCommitmentCommandService commands;

    @Test
    void requestCreatesRequestedCommitmentPendingCaseAndSubmitActionAtomically() {
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var committedBefore = budgetCommitted(budgetId);

        var detail = commands.request(requesterUser(),
                new RequestCommitmentCommand(budgetId, new BigDecimal("250.50000000"), "CNY"),
                "req-atomic-1");

        assertThat(detail.id()).isPositive();
        assertThat(detail.budgetId()).isEqualTo(budgetId);
        assertThat(detail.status().name()).isEqualTo("REQUESTED");
        assertThat(detail.requestedAmount()).isEqualByComparingTo("250.50000000");
        assertThat(detail.approvedAmount()).isNull();
        assertThat(detail.remainingAmount()).isNull();
        assertThat(detail.approvalCaseId()).isNotNull();
        assertThat(detail.approvalStatus().name()).isEqualTo("PENDING");
        assertThat(detail.history()).hasSize(1);
        assertThat(detail.history().get(0).actionType().name()).isEqualTo("SUBMIT");
        assertThat(detail.history().get(0).actorMemberId()).isEqualTo(requesterMemberId);

        // REQUESTED never reserves budget capacity.
        assertThat(budgetCommitted(budgetId)).isEqualTo(committedBefore);
        assertThat(budgetVersion(budgetId)).isZero();
        // Single SUBMIT action on a single PENDING case; one audit event.
        assertThat(approvalActionCount(detail.id())).isEqualTo(1);
        assertThat(auditCount("COMMITMENT_REQUESTED")).isEqualTo(1);
    }

    @Test
    void requestRejectsNonPositiveRequestedAmount() {
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");

        assertThatThrownBy(() -> commands.request(requesterUser(),
                new RequestCommitmentCommand(budgetId, new BigDecimal("0.00000000"), "CNY"),
                "req-zero"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
                });

        assertThatThrownBy(() -> commands.request(requesterUser(),
                new RequestCommitmentCommand(budgetId, new BigDecimal("-1.00000000"), "CNY"),
                "req-negative"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
                });

        assertThat(commitmentCountForBudget(budgetId)).isZero();
    }

    @Test
    void requestRejectsExcessPrecision() {
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");

        assertThatThrownBy(() -> commands.request(requesterUser(),
                new RequestCommitmentCommand(budgetId, new BigDecimal("1.000000001"), "CNY"),
                "req-precision"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
                });
    }

    @Test
    void requestRejectsWrongOrgBudgetWithPrivacy404() {
        var foreignPeriod = insertBillingPeriod(foreignOrgId, "2026-08-01 00:00:00.000000",
                "2026-09-01 00:00:00.000000", "OPEN");
        var foreignBudget = insertBudgetRow(foreignOrgId, foreignPeriod, "ORG", foreignOrgId,
                "CNY", "1000.00000000", "0.00000000", "0.00000000");

        assertThatThrownBy(() -> commands.request(requesterUser(),
                new RequestCommitmentCommand(foreignBudget, new BigDecimal("1.00000000"), "CNY"),
                "req-foreign-budget"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo(ProblemCode.RESOURCE_NOT_FOUND);
                });
    }

    @Test
    void requestRequiresPermissionBeforeAnyLookup() {
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");

        assertThatThrownBy(() -> commands.request(noPermissionUser(),
                new RequestCommitmentCommand(budgetId, new BigDecimal("1.00000000"), "CNY"),
                "req-no-permission"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.code()).isEqualTo(ProblemCode.FORBIDDEN);
                });
    }

    @Test
    void requestRejectsBudgetOutsideTheGrantedScope() {
        // The scoped actor only holds PROJECT-scoped grants; an ORG-scoped
        // budget of the same organization stays invisible (privacy 404).
        var orgBudget = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");

        assertThatThrownBy(() -> commands.request(scopedUser(),
                new RequestCommitmentCommand(orgBudget, new BigDecimal("1.00000000"), "CNY"),
                "req-scope-mismatch"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.code()).isEqualTo(ProblemCode.RESOURCE_NOT_FOUND);
                });

        // ... while a project-scoped budget of the granted project is visible.
        var projectBudget = insertBudgetRow(orgId, periodId, "PROJECT", projectId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var detail = commands.request(scopedUser(),
                new RequestCommitmentCommand(projectBudget, new BigDecimal("1.00000000"), "CNY"),
                "req-scope-match");
        assertThat(detail.status().name()).isEqualTo("REQUESTED");
    }

    @Test
    void requestRejectsCurrencyMismatch() {
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");

        assertThatThrownBy(() -> commands.request(requesterUser(),
                new RequestCommitmentCommand(budgetId, new BigDecimal("1.00000000"), "USD"),
                "req-currency"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
                });
    }

    @Test
    void requestReplaysIdempotentlyWithoutSideEffects() {
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");
        var command = new RequestCommitmentCommand(budgetId, new BigDecimal("25.00000000"), "CNY");

        var first = commands.request(requesterUser(), command, "req-replay-1");
        var replayed = commands.request(requesterUser(), command, "req-replay-1");

        assertThat(replayed.id()).isEqualTo(first.id());
        assertThat(replayed.status().name()).isEqualTo("REQUESTED");
        assertThat(commitmentCountForBudget(budgetId)).isEqualTo(1);
        assertThat(approvalActionCount(first.id())).isEqualTo(1);
        assertThat(auditCount("COMMITMENT_REQUESTED")).isEqualTo(1);
        assertThat(budgetCommitted(budgetId)).isEqualTo("0.00000000");
    }

    @Test
    void requestWithSameKeyButDifferentBodyIs409() {
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");

        commands.request(requesterUser(),
                new RequestCommitmentCommand(budgetId, new BigDecimal("10.00000000"), "CNY"),
                "req-conflict-1");

        assertThatThrownBy(() -> commands.request(requesterUser(),
                new RequestCommitmentCommand(budgetId, new BigDecimal("11.00000000"), "CNY"),
                "req-conflict-1"))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(ProblemCode.STATE_CONFLICT);
                });
    }

    @Test
    void requestRejectsMissingIdempotencyKey() {
        var budgetId = insertBudgetRow(orgId, periodId, "ORG", orgId, "CNY",
                "1000.00000000", "0.00000000", "0.00000000");

        assertThatThrownBy(() -> commands.request(requesterUser(),
                new RequestCommitmentCommand(budgetId, new BigDecimal("1.00000000"), "CNY"),
                "  "))
                .isInstanceOf(DomainException.class)
                .satisfies(problem -> {
                    var exception = (DomainException) problem;
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo(ProblemCode.VALIDATION_FAILED);
                });
    }

    private int commitmentCountForBudget(long budgetId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_commitment WHERE org_id=? AND budget_id=?",
                Integer.class, orgId, budgetId);
    }
}
