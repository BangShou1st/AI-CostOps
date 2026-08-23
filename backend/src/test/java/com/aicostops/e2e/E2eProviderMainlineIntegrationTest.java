package com.aicostops.e2e;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.ingestion.application.ImportAttemptExecutor;
import com.aicostops.ingestion.application.ImportLeaseService;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * AIC-063 provider statement mainline over the HTTP boundary: upload a
 * synthetic DeepSeek export, drive the import attempt to READY_FOR_REVIEW,
 * confirm canonical truth, allocate manually to a project, post to the
 * immutable ledger, reconcile the period clean, and close it. Every step
 * after the worker lease is an authenticated API call.
 */
@SpringBootTest
@Tag("integration")
@AutoConfigureMockMvc
class E2eProviderMainlineIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ImportLeaseService leases;
    @Autowired
    private ImportAttemptExecutor executor;

    private static final String AUG_1 = "2026-08-01 00:00:00.000000";
    private static final String SEP_1 = "2026-09-01 00:00:00.000000";

    @Test
    void uploadImportConfirmAllocatePostReconcileCloseReachesClosedPeriod() throws Exception {
        grant("E2E_FINANCE", List.of(
                "IMPORT_READ", "IMPORT_CONFIRM", "COST_READ", "ALLOCATION_READ",
                "ALLOCATION_EDIT", "ALLOCATION_CONFIRM", "LEDGER_POST", "LEDGER_READ",
                "RECONCILIATION_RUN", "RECONCILIATION_READ", "PERIOD_READ", "PERIOD_CLOSE",
                // the import upload path is admitted by EVIDENCE_UPLOAD_PROVIDER
                "EVIDENCE_UPLOAD_PROVIDER"));
        var deepseekAccountId = insertProviderAccount(orgId, "DEEPSEEK");
        var periodId = insertOpenAugustPeriod();

        // 1. Upload the synthetic DeepSeek export (amount + cost CSVs).
        var batchId = uploadDeepseekExport(deepseekAccountId);
        assertThatBatchStatus(batchId, "PENDING");

        // 2. Worker lease executes the parse: attempt SUCCEEDED, batch ready.
        var lease = leases.claimNext("e2e-provider-worker").orElseThrow();
        executor.execute(lease);
        assertThatBatchStatus(batchId, "READY_FOR_REVIEW");

        // 3. Confirm publishes the canonical charge fact.
        mockMvc.perform(post("/api/v1/imports/{importId}/confirm", batchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "e2e-provider-confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        var chargeId = jdbc.queryForObject(
                "SELECT id FROM charge_fact WHERE org_id=? AND review_status='CLEAN'",
                Long.class, orgId);

        // 4. Manual allocation to the fixture project, then confirm it.
        // The decision response embeds lines[].id, so parse the FIRST "id"
        // occurrence (repo-wide convention, cf. decisionIdFrom in
        // AllocationDecisionApiIntegrationTest); a greedy regex would grab the
        // line row's id and 404 on confirm once sequences diverge.
        var decisionResponse = mockMvc.perform(post(
                        "/api/v1/costs/charges/{chargeFactId}/allocation-decisions/manual", chargeId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "e2e-provider-alloc")
                        .contentType("application/json")
                        .content("{\"lines\":[{\"allocatedAmount\":\"1.25000000\","
                                + "\"currency\":\"CNY\",\"projectId\":\"" + projectId + "\"}]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var decisionId = firstStringId(decisionResponse);
        mockMvc.perform(post("/api/v1/allocation-decisions/{decisionId}/confirm", decisionId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "e2e-provider-confirm-alloc"))
                .andExpect(status().isOk());

        // 5. Post the confirmed charge into the immutable ledger.
        mockMvc.perform(post("/api/v1/costs/charges/{chargeFactId}/post", chargeId)
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"commitmentLinks\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].amount").value("1.25000000"))
                .andExpect(jsonPath("$.entries[0].currency").value("CNY"));

        // 6. Reconciliation matches ledger against canonical truth cleanly.
        mockMvc.perform(post("/api/v1/reconciliation-runs")
                        .header("Authorization", bearer())
                        .contentType("application/json")
                        .content("{\"billingPeriodId\":\"" + periodId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.summary.discrepancyCount").value(0));

        // 7. All seven blockers pass; the period closes.
        mockMvc.perform(post("/api/v1/billing-periods/{periodId}/close", periodId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodStatus").value("CLOSED"))
                .andExpect(jsonPath("$.checks.length()").value(7));
        assertThatPeriodStatus(periodId, "CLOSED");
    }

    private void grant(String roleCode, List<String> permissions) {
        createPermissionRole(roleCode, permissions);
        assign(roleCode, "ORG", orgId);
    }

    private long insertOpenAugustPeriod() {
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,? ,? ,'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, AUG_1, SEP_1);
        return jdbc.queryForObject(
                "SELECT id FROM billing_period WHERE org_id=? ORDER BY id DESC LIMIT 1",
                Long.class, orgId);
    }

    private long uploadDeepseekExport(long providerAccountId) throws Exception {
        var entries = new LinkedHashMap<String, String>();
        entries.put("amount-2026-08-01.csv",
                "user_id,start_time_iso,end_time_iso,model,api_key_name,api_key,type,price,amount\n"
                        + "user-1,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,deepseek-chat,default,"
                        + "sk-SECRET-SENTINEL-DO-NOT-PERSIST,api_call,0.000002,125\n");
        entries.put("cost-2026-08-01.csv",
                "user_id,start_time_iso,end_time_iso,model,wallet_type,cost,currency\n"
                        + "user-1,2026-08-01T00:00:00Z,2026-08-01T01:00:00Z,deepseek-chat,main_wallet,1.25,CNY\n");
        var zip = zipOrThrow(entries);

        var result = mockMvc.perform(multipart("/api/v1/provider-imports")
                        .file(new MockMultipartFile("file", "deepseek-2026-08.zip",
                                "application/zip", zip))
                        .param("providerAccountId", Long.toString(providerAccountId))
                        .param("sourceType", "FILE_EXPORT")
                        .header("Authorization", bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchStatus").value("PENDING"))
                .andReturn();
        var body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return Long.parseLong(body.replaceAll(".*\"importBatchId\":\"([0-9]+)\".*", "$1"));
    }

    private void assertThatBatchStatus(long batchId, String expected) {
        assertThatEqual("SELECT status FROM import_batch WHERE id=?", expected, batchId);
    }

    private void assertThatPeriodStatus(long periodId, String expected) {
        assertThatEqual("SELECT status FROM billing_period WHERE id=?", expected, periodId);
    }

    private void assertThatEqual(String query, String expected, long id) {
        var actual = jdbc.queryForObject(query, String.class, id);
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + " but was " + actual
                    + " for [" + query + "] id=" + id);
        }
    }

    /** First {@code "id":"<digits>"} occurrence — the decision id, not a line's. */
    private static long firstStringId(String response) {
        var start = response.indexOf("\"id\":\"") + "\"id\":\"".length();
        var end = response.indexOf('"', start);
        return Long.parseLong(response.substring(start, end));
    }

    private static byte[] zipOrThrow(Map<String, String> entries) {
        try {
            return com.aicostops.ingestion.providers.fixtures.ProviderFixtureFactory.zip(entries);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
