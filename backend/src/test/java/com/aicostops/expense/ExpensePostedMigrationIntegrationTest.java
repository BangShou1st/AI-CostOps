package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;

/** Proves V14 accepts POSTED while preserving the status allow-list. */
@SpringBootTest
@Tag("integration")
class ExpensePostedMigrationIntegrationTest extends ExpenseTestSupport {

    @Test
    void acceptsPostedAndRejectsUnknownStatuses() {
        var expenseId = insertExpenseDraftFor(orgId, employeeMemberId,
                "10.00000000", "CNY", "POSTED");

        assertThat(expenseStatus(expenseId)).isEqualTo("POSTED");
        assertThatThrownBy(() -> insertExpenseDraftFor(orgId, employeeMemberId,
                "10.00000000", "CNY", "VOIDED"))
                .isInstanceOf(DataAccessException.class);
    }
}
