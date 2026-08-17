package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.expense.application.ExpenseClaimCommandService;
import com.aicostops.expense.application.ExpenseCommands.CreateExpenseCommand;
import com.aicostops.expense.application.ExpenseCommands.EditExpenseCommand;
import com.aicostops.expense.application.ExpenseQueryService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Expense own lifecycle: idempotent DRAFT creation, exact 8-decimal money,
 * optimistic-version edits, stale-version and non-editable-status conflicts,
 * and owner-scoped reads.
 */
@SpringBootTest
@Tag("integration")
class ExpenseOwnLifecycleIntegrationTest extends ExpenseTestSupport {

    @Autowired
    private ExpenseClaimCommandService commands;
    @Autowired
    private ExpenseQueryService queries;

    @Test
    void createMakesDraftBoundToClaimantWithVersionZero() {
        var created = commands.create(employeeUser(), createCommand(), "create-1");
        assertThat(created.status().name()).isEqualTo("DRAFT");
        assertThat(created.claimantMemberId()).isEqualTo(employeeMemberId);
        assertThat(created.version()).isZero();
        assertThat(created.amount()).isEqualByComparingTo("100.00000000");
        assertThat(created.currency()).isEqualTo("CNY");
        assertThat(created.postingReady()).isFalse();
        assertThat(auditCount("EXPENSE_CREATED")).isEqualTo(1);
    }

    @Test
    void createReplaysOnSameKeyAndConflictsOnDifferentBody() {
        var first = commands.create(employeeUser(), createCommand(), "replay-key");
        var replayed = commands.create(employeeUser(), createCommand(), "replay-key");
        assertThat(replayed).isEqualTo(first);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM expense_claim WHERE claimant_member_id=?",
                Integer.class, employeeMemberId)).isEqualTo(1);

        assertThatThrownBy(() -> commands.create(employeeUser(),
                new CreateExpenseCommand(LocalDate.parse("2026-08-02"),
                        new BigDecimal("200.00000000"), "CNY"), "replay-key"))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(409);
                    assertThat(domain.code().name()).isEqualTo("STATE_CONFLICT");
                });
    }

    @Test
    void editReplacesBodyAndIncrementsVersion() {
        var expenseId = insertExpenseDraft();
        var updated = commands.edit(employeeUser(), expenseId,
                new EditExpenseCommand(LocalDate.parse("2026-08-03"),
                        new BigDecimal("120.00000000"), "USD", 0));
        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.amount()).isEqualByComparingTo("120.00000000");
        assertThat(updated.currency()).isEqualTo("USD");
        assertThat(expenseVersion(expenseId)).isEqualTo(1);
        assertThat(auditCount("EXPENSE_EDITED")).isEqualTo(1);
    }

    @Test
    void editWithStaleVersionConflicts() {
        var expenseId = insertExpenseDraft();
        commands.edit(employeeUser(), expenseId,
                new EditExpenseCommand(LocalDate.parse("2026-08-03"),
                        new BigDecimal("120.00000000"), "CNY", 0));
        assertThatThrownBy(() -> commands.edit(employeeUser(), expenseId,
                new EditExpenseCommand(LocalDate.parse("2026-08-04"),
                        new BigDecimal("130.00000000"), "CNY", 0)))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(409);
                    assertThat(domain.code().name()).isEqualTo("STATE_CONFLICT");
                });
        assertThat(expenseVersion(expenseId)).isEqualTo(1);
    }

    @Test
    void editNonEditableStatusConflicts() {
        var expenseId = insertExpenseDraft();
        setExpenseStatus(expenseId, "SUBMITTED");
        assertThatThrownBy(() -> commands.edit(employeeUser(), expenseId,
                new EditExpenseCommand(LocalDate.parse("2026-08-03"),
                        new BigDecimal("120.00000000"), "CNY", 0)))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(409);
                    assertThat(domain.code().name()).isEqualTo("STATE_CONFLICT");
                });
    }

    @Test
    void editForeignExpenseIsPrivacy404() {
        var foreignUserId = insertUser("foreign-" + System.nanoTime() + "@example.com");
        var foreignClaimant = insertMember(orgId, foreignUserId);
        var otherExpenseId = insertExpenseDraftFor(orgId, foreignClaimant,
                "50.00000000", "CNY", "DRAFT");
        assertThatThrownBy(() -> commands.edit(employeeUser(), otherExpenseId,
                new EditExpenseCommand(LocalDate.parse("2026-08-03"),
                        new BigDecimal("55.00000000"), "CNY", 0)))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(404);
                    assertThat(domain.code().name()).isEqualTo("RESOURCE_NOT_FOUND");
                });
    }

    @Test
    void getOwnedReturnsDetailAndForeignIsPrivacy404() {
        var expenseId = insertExpenseDraft();
        var detail = queries.getOwned(employeeUser(), expenseId);
        assertThat(detail.id()).isEqualTo(expenseId);

        var foreignUserId = insertUser("foreign-read-" + System.nanoTime() + "@example.com");
        var foreignClaimant = insertMember(orgId, foreignUserId);
        var otherExpenseId = insertExpenseDraftFor(orgId, foreignClaimant,
                "50.00000000", "CNY", "DRAFT");
        assertThatThrownBy(() -> queries.getOwned(employeeUser(), otherExpenseId))
                .satisfies(thrown -> {
                    var domain = (DomainException) thrown;
                    assertThat(domain.status().value()).isEqualTo(404);
                    assertThat(domain.code().name()).isEqualTo("RESOURCE_NOT_FOUND");
                });
    }

    @Test
    void listMineOnlyReturnsOwnExpenses() {
        commands.create(employeeUser(), createCommand(), "list-1");
        var foreignUserId = insertUser("foreign-list-" + System.nanoTime() + "@example.com");
        var foreignClaimant = insertMember(orgId, foreignUserId);
        insertExpenseDraftFor(orgId, foreignClaimant, "50.00000000", "CNY", "DRAFT");

        var mine = queries.listMine(employeeUser(), 0, 50);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).claimantMemberId()).isEqualTo(employeeMemberId);
        assertThat(queries.countMine(employeeUser())).isEqualTo(1);
    }

    private AuthenticatedUser employeeUser() {
        return new AuthenticatedUser(employeeUserId, 7);
    }

    private CreateExpenseCommand createCommand() {
        return new CreateExpenseCommand(LocalDate.parse("2026-08-01"),
                new BigDecimal("100.00000000"), "CNY");
    }
}