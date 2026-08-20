package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.expense.domain.ExpenseClaimStatus;
import org.junit.jupiter.api.Test;

class ExpenseClaimStatusTest {

    @Test
    void onlyApprovedMayTransitionToPosted() {
        assertThat(ExpenseClaimStatus.APPROVED.canTransitionTo(ExpenseClaimStatus.POSTED)).isTrue();
        assertThat(ExpenseClaimStatus.DRAFT.canTransitionTo(ExpenseClaimStatus.POSTED)).isFalse();
        assertThat(ExpenseClaimStatus.SUBMITTED.canTransitionTo(ExpenseClaimStatus.POSTED)).isFalse();
        assertThat(ExpenseClaimStatus.NEEDS_INFO.canTransitionTo(ExpenseClaimStatus.POSTED)).isFalse();
    }

    @Test
    void postedIsTerminalAndNotOwnerEditable() {
        assertThat(ExpenseClaimStatus.POSTED.canTransitionTo(ExpenseClaimStatus.POSTED)).isFalse();
        assertThat(ExpenseClaimStatus.POSTED.canTransitionTo(ExpenseClaimStatus.APPROVED)).isFalse();
        assertThat(ExpenseClaimStatus.POSTED.editableByOwner()).isFalse();
    }
}
