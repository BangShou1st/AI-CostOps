package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.expense.application.ExpenseQueryService;
import com.aicostops.expense.application.ExpenseReviewQueryService;
import com.aicostops.expense.infrastructure.ExpenseClaimMapper;
import com.aicostops.expense.infrastructure.ExpenseApprovalMapper;
import com.aicostops.shared.security.AuthenticatedUser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Tag("integration")
class ExpenseSummaryQueryIntegrationTest extends ExpenseTestSupport {
    @Autowired private ExpenseQueryService queries;
    @Autowired private ExpenseReviewQueryService reviews;
    @Autowired private MockMvc mvc;
    @MockitoSpyBean private ExpenseClaimMapper mapper;
    @MockitoSpyBean private ExpenseApprovalMapper approvals;

    @Test
    void listsPreserveScopeAndDoNotLoadDetailsOrApprovalHistory() {
        var mine = insertExpenseDraft();
        var approved = insertExpenseDraftFor(orgId, employeeMemberId, "50.00000000", "CNY", "APPROVED");
        insertExpenseDraftFor(foreignOrgId,
                insertMember(foreignOrgId, insertUser("summary-foreign-" + System.nanoTime() + "@example.test")),
                "75.00000000", "CNY", "APPROVED");
        clearInvocations(mapper, approvals);
        var summaries = queries.listMine(new AuthenticatedUser(employeeUserId, 7), 0, 50);
        assertThat(summaries).extracting(summary -> summary.id()).containsExactly(approved, mine);
        assertThat(summaries).allSatisfy(summary -> {
            assertThat(summary.organizationId()).isEqualTo(orgId);
            assertThat(summary.postingReady()).isFalse();
            assertThat(summary.approvalStatus()).isNull();
            assertThat(summary.amount().scale()).isEqualTo(8);
        });
        assertThat(reviews.listQueue(new AuthenticatedUser(financeUserId, 7), "APPROVED", 0, 50))
                .extracting(summary -> summary.id()).containsExactly(approved);
        verify(approvals, never()).selectApprovalCaseByExpense(anyLong(), anyLong());
        verify(approvals, never()).selectApprovalActionsByCase(anyLong(), anyLong());
        verify(mapper, never()).selectDecisionStatus(anyLong(), anyLong());
    }

    @Test
    void summariesReadApprovalAndPostingReadinessFromCurrentLinks() {
        var expense = insertExpenseDraftFor(orgId, employeeMemberId, "50.00000000", "CNY", "APPROVED");
        jdbc.update("""
                INSERT INTO approval_case(org_id,expense_claim_id,status,created_at,updated_at)
                VALUES (?,?,'APPROVED',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, expense);
        var approval = jdbc.queryForObject("SELECT id FROM approval_case WHERE org_id=? AND expense_claim_id=?",
                Long.class, orgId, expense);
        jdbc.update("""
                INSERT INTO allocation_decision(org_id,subject_type,expense_claim_id,decision_source,status,created_at)
                VALUES (?,'EXPENSE_CLAIM',?,'MANUAL','CONFIRMED',UTC_TIMESTAMP(6))
                """, orgId, expense);
        var decision = jdbc.queryForObject("SELECT id FROM allocation_decision WHERE org_id=? AND expense_claim_id=?",
                Long.class, orgId, expense);
        jdbc.update("UPDATE expense_claim SET approval_case_id=?,current_allocation_decision_id=? WHERE id=?",
                approval, decision, expense);

        var mine = queries.listMine(new AuthenticatedUser(employeeUserId, 7), 0, 50).getFirst();
        var review = reviews.listQueue(new AuthenticatedUser(financeUserId, 7), "APPROVED", 0, 50).getFirst();
        assertThat(mine.approvalStatus().name()).isEqualTo("APPROVED");
        assertThat(mine.postingReady()).isTrue();
        assertThat(review).isEqualTo(mine);

        jdbc.update("UPDATE allocation_decision SET status='SUPERSEDED' WHERE id=?", decision);
        assertThat(queries.listMine(new AuthenticatedUser(employeeUserId, 7), 0, 50).getFirst().postingReady())
                .isFalse();
    }

    @Test
    void queueFiltersCountsAndPaginationAgreeWithinTheOrganization() {
        var submitted = insertExpenseDraftFor(orgId, employeeMemberId, "10.00000000", "CNY", "SUBMITTED");
        var needsInfo = insertExpenseDraftFor(orgId, employeeMemberId, "20.00000000", "CNY", "NEEDS_INFO");
        var approved = insertExpenseDraftFor(orgId, employeeMemberId, "30.00000000", "CNY", "APPROVED");
        insertExpenseDraftFor(orgId, employeeMemberId, "40.00000000", "CNY", "POSTED");
        insertExpenseDraft();
        insertExpenseDraftFor(foreignOrgId,
                insertMember(foreignOrgId, insertUser("queue-foreign-" + System.nanoTime() + "@example.test")),
                "50.00000000", "CNY", "SUBMITTED");
        var reviewer = new AuthenticatedUser(financeUserId, 7);
        var expected = java.util.Map.of("SUBMITTED", submitted, "NEEDS_INFO", needsInfo, "APPROVED", approved);
        expected.forEach((filter, id) -> {
            assertThat(reviews.listQueue(reviewer, filter, 0, 50)).extracting(summary -> summary.id())
                    .containsExactly(id);
            assertThat(reviews.countQueue(reviewer, filter)).isEqualTo(1);
        });
        var all = reviews.listQueue(reviewer, "ALL", 0, 50);
        assertThat(all).extracting(summary -> summary.id()).containsExactly(submitted, needsInfo, approved);
        assertThat(reviews.countQueue(reviewer, "ALL")).isEqualTo(all.size());
        for (var page = 0; page < all.size(); page++) {
            assertThat(reviews.listQueue(reviewer, "ALL", page, 1)).containsExactly(all.get(page));
        }
        assertThat(reviews.listQueue(reviewer, "ALL", 3, 1)).isEmpty();
        assertThat(reviews.listQueue(reviewer, "submitted", 0, 50)).extracting(summary -> summary.id())
                .containsExactly(submitted);
        assertThat(queries.countMine(new AuthenticatedUser(employeeUserId, 7))).isEqualTo(5);
        assertThat(queries.listMine(new AuthenticatedUser(employeeUserId, 7), 1, 2)).hasSize(2);
    }

    @Test
    void bothListsRejectInvalidPaginationWithTheSameErrorContract() throws Exception {
        for (var query : new String[] { "page=-1&size=50", "page=0&size=0",
                "page=0&size=201", "page=50000000&size=50" }) {
            mvc.perform(get("/api/v1/expenses?" + query).header("Authorization", employeeBearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.traceId").isNotEmpty());
            mvc.perform(get("/api/v1/expense-reviews?" + query).header("Authorization", financeBearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }
}
