package com.aicostops.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Expense evidence contract: owner upload with expectedVersion CAS, real
 * MinIO-backed storage, expense-scoped download (owner / finance, never the
 * org-wide EVIDENCE_DOWNLOAD), and rejections for non-owners, locked statuses,
 * and stale versions.
 */
@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=duplicate-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class ExpenseEvidenceIntegrationTest extends ExpenseTestSupport {

    private static final byte[] RECEIPT = "fake-receipt-bytes".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;

    @Test
    void ownerAttachesEvidenceAndIncrementsVersion() throws Exception {
        var expenseId = insertExpenseDraft();
        mockMvc.perform(multipart("/api/v1/expenses/{expenseId}/evidence", expenseId)
                        .file(new MockMultipartFile("file", "receipt.pdf",
                                "application/pdf", RECEIPT))
                        .param("expectedVersion", "0")
                        .header("Authorization", employeeBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.evidenceId").isString());

        var evidenceId = jdbc.queryForObject(
                "SELECT evidence_id FROM expense_claim WHERE id=?", Long.class, expenseId);
        assertThat(evidenceId).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT storage_status FROM evidence WHERE id=?", String.class, evidenceId))
                .isEqualTo("AVAILABLE");
        assertThat(expenseVersion(expenseId)).isEqualTo(1);
        assertThat(auditCount("EXPENSE_EVIDENCE_ATTACHED")).isEqualTo(1);
    }

    @Test
    void attachRequiresOwnershipAndEditableStatusAndFreshVersion() throws Exception {
        // non-owner -> privacy 404
        var foreignUserId = insertUser("evidence-foreign-" + System.nanoTime() + "@example.com");
        var foreignClaimant = insertMember(orgId, foreignUserId);
        var foreignExpenseId = insertExpenseDraftFor(orgId, foreignClaimant,
                "50.00000000", "CNY", "DRAFT");
        mockMvc.perform(multipart("/api/v1/expenses/{expenseId}/evidence", foreignExpenseId)
                        .file(new MockMultipartFile("file", "receipt.pdf",
                                "application/pdf", RECEIPT))
                        .param("expectedVersion", "0")
                        .header("Authorization", employeeBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // SUBMITTED expense cannot swap evidence
        var submittedId = insertExpenseDraft();
        setExpenseStatus(submittedId, "SUBMITTED");
        mockMvc.perform(multipart("/api/v1/expenses/{expenseId}/evidence", submittedId)
                        .file(new MockMultipartFile("file", "receipt.pdf",
                                "application/pdf", RECEIPT))
                        .param("expectedVersion", "0")
                        .header("Authorization", employeeBearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));

        // stale expectedVersion
        var staleId = insertExpenseDraft();
        assertThat(staleId).isNotNull();
        jdbc.update("UPDATE expense_claim SET version=2 WHERE id=?", staleId);
        mockMvc.perform(multipart("/api/v1/expenses/{expenseId}/evidence", staleId)
                        .file(new MockMultipartFile("file", "receipt.pdf",
                                "application/pdf", RECEIPT))
                        .param("expectedVersion", "0")
                        .header("Authorization", employeeBearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void ownerDownloadsOwnEvidenceAndForeignIs404() throws Exception {
        var expenseId = insertExpenseDraft();
        attachEvidence(expenseId);

        mockMvc.perform(get("/api/v1/expenses/{expenseId}/evidence/download", expenseId)
                        .header("Authorization", employeeBearer()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(RECEIPT));

        var foreignUserId = insertUser("download-foreign-" + System.nanoTime() + "@example.com");
        var foreignClaimant = insertMember(orgId, foreignUserId);
        var foreignExpenseId = insertExpenseDraftFor(orgId, foreignClaimant,
                "50.00000000", "CNY", "DRAFT");
        mockMvc.perform(get("/api/v1/expenses/{expenseId}/evidence/download", foreignExpenseId)
                        .header("Authorization", employeeBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void financeReviewsEvidenceOfAnyOrgExpense() throws Exception {
        var expenseId = insertExpenseDraft();
        attachEvidence(expenseId);

        mockMvc.perform(get("/api/v1/expenses/{expenseId}/evidence/download", expenseId)
                        .header("Authorization", financeBearer()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(RECEIPT));
    }

    @Test
    void downloadWithoutEvidenceIs404() throws Exception {
        var expenseId = insertExpenseDraft();
        mockMvc.perform(get("/api/v1/expenses/{expenseId}/evidence/download", expenseId)
                        .header("Authorization", employeeBearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private void attachEvidence(long expenseId) throws Exception {
        mockMvc.perform(multipart("/api/v1/expenses/{expenseId}/evidence", expenseId)
                        .file(new MockMultipartFile("file", "receipt.pdf",
                                "application/pdf", RECEIPT))
                        .param("expectedVersion", "0")
                        .header("Authorization", employeeBearer()))
                .andExpect(status().isOk());
    }
}