package com.aicostops.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.allocation.AllocationApiTestSupport;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Workbench API contract at the HTTP boundary: permission-trimmed sections,
 * per-currency aggregation on the M6 close-blocker charge basis, explicit
 * period scoping, and short-TTL cache-aside behavior.
 */
@SpringBootTest
@Tag("integration")
@AutoConfigureMockMvc
class WorkbenchIntegrationTest extends AllocationApiTestSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void dropWorkbenchRoles() {
        // The shared base cleaner only knows its own role names. Child rows
        // (assignments, permissions) must go before the role itself.
        jdbc.update("""
                DELETE FROM role_assignment WHERE role_id IN (
                    SELECT id FROM `role` WHERE code='WORKBENCH_FINANCE')
                """);
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id WHERE r.code='WORKBENCH_FINANCE'
                """);
        jdbc.update("DELETE FROM `role` WHERE code='WORKBENCH_FINANCE'");
    }

    @Test
    void financeReaderSeesEverySectionAggregatedPerCurrency() throws Exception {
        grant("WORKBENCH_FINANCE", List.of(
                "PERIOD_READ", "COST_READ", "BUDGET_READ", "ALLOCATION_READ",
                "DUPLICATE_REVIEW", "EXPENSE_REVIEW", "RECONCILIATION_READ"));
        var periodId = insertOpenJanuaryPeriod();
        insertCharge("10.00000000");
        insertCharge("20.00000000");
        insertBudget(periodId, "100.00000000", "30.00000000", "10.00000000");
        insertExpense(actorMemberId, "5.00000000", "SUBMITTED");
        insertExpense(actorMemberId, "7.00000000", "NEEDS_INFO");
        insertReconciliationRunAndCase();

        mockMvc.perform(get("/api/v1/workbench").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.status").value("OPEN"))
                .andExpect(jsonPath("$.costByProvider[0].providerCode").value("GLM"))
                .andExpect(jsonPath("$.costByProvider[0].currency").value("CNY"))
                .andExpect(jsonPath("$.costByProvider[0].totalAmount").value("30.00000000"))
                .andExpect(jsonPath("$.costByProvider[0].chargeCount").value(2))
                .andExpect(jsonPath("$.unallocatedCharges[0].currency").value("CNY"))
                .andExpect(jsonPath("$.unallocatedCharges[0].amount").value("30.00000000"))
                .andExpect(jsonPath("$.unallocatedCharges[0].chargeCount").value(2))
                .andExpect(jsonPath("$.budgetVariance[0].availableAmount").value("60.00000000"))
                .andExpect(jsonPath("$.budgetVariance[0].overBudget").value(false))
                .andExpect(jsonPath("$.duplicateCandidates.openCount").value(0))
                .andExpect(jsonPath("$.pendingApprovals.submittedCount").value(1))
                .andExpect(jsonPath("$.pendingApprovals.needsInfoCount").value(1))
                .andExpect(jsonPath("$.openReconciliations.activeRunCount").value(1))
                .andExpect(jsonPath("$.openReconciliations.openCaseCount").value(1))
                .andExpect(jsonPath("$.closeStatus.status").value("OPEN"))
                .andExpect(jsonPath("$.closeStatus.closed").value(false));
    }

    @Test
    void amountsNeverSumAcrossCurrencies() throws Exception {
        grant("WORKBENCH_FINANCE", List.of(
                "PERIOD_READ", "COST_READ", "ALLOCATION_READ", "RECONCILIATION_READ",
                "EXPENSE_REVIEW", "DUPLICATE_REVIEW", "BUDGET_READ"));
        insertOpenJanuaryPeriod();
        insertCharge("10.00000000");
        // The shared charge helper hardcodes CNY; insert a USD row directly.
        var nextIndex = jdbc.queryForObject(
                "SELECT COALESCE(MAX(fact_index),-1)+1 FROM charge_fact WHERE raw_record_id=?",
                Integer.class, rawRecordId);
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,?,'OPENAI','USAGE',?,'USD',?,?, 'CLEAN',UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, nextIndex, "4.00000000", JAN_1, FEB_1);

        mockMvc.perform(get("/api/v1/workbench").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costByProvider.length()").value(2))
                .andExpect(
                        jsonPath("$.costByProvider[?(@.currency=='CNY')].totalAmount")
                                .value("10.00000000"))
                .andExpect(
                        jsonPath("$.costByProvider[?(@.currency=='USD')].totalAmount")
                                .value("4.00000000"));
    }

    @Test
    void sectionsWithoutOrgGrantsStayAbsent() throws Exception {
        // ALLOC_WORKER carries only cost/duplicate/allocation reads: the
        // period, budget, expense, reconciliation, and close sections must be
        // absent rather than zero-filled, and no cross-section leak happens.
        insertOpenJanuaryPeriod();
        insertCharge("10.00000000");

        mockMvc.perform(get("/api/v1/workbench").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").doesNotExist())
                .andExpect(jsonPath("$.costByProvider[0].totalAmount").value("10.00000000"))
                .andExpect(jsonPath("$.costByProject").isEmpty())
                .andExpect(jsonPath("$.unallocatedCharges[0].amount").value("10.00000000"))
                .andExpect(jsonPath("$.budgetVariance").isEmpty())
                .andExpect(jsonPath("$.duplicateCandidates.openCount").value(0))
                .andExpect(jsonPath("$.pendingApprovals").doesNotExist())
                .andExpect(jsonPath("$.openReconciliations").doesNotExist())
                .andExpect(jsonPath("$.closeStatus").doesNotExist());
    }

    @Test
    void cacheKeyIsScopedByCallerOrgPermissionFingerprint() throws Exception {
        grant("WORKBENCH_FINANCE", List.of(
                "PERIOD_READ", "COST_READ", "BUDGET_READ", "ALLOCATION_READ",
                "DUPLICATE_REVIEW", "EXPENSE_REVIEW", "RECONCILIATION_READ"));
        var periodId = insertOpenJanuaryPeriod();
        insertCharge("10.00000000");
        insertBudget(periodId, "100.00000000", "10.00000000", "0.00000000");
        insertExpense(actorMemberId, "5.00000000", "SUBMITTED");

        // Warm the cache with the full-permission actor.
        mockMvc.perform(get("/api/v1/workbench").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.status").value("OPEN"))
                .andExpect(jsonPath("$.costByProvider[0].totalAmount").value("10.00000000"))
                .andExpect(jsonPath("$.budgetVariance[0].availableAmount").value("90.00000000"));

        // The finance member has EXPENSE_REVIEW + ALLOCATION_READ only; it must
        // not observe the actor's warm aggregate view within the same org.
        mockMvc.perform(get("/api/v1/workbench")
                        .header("Authorization", "Bearer " + tokens.issue(financeUserId, 7).token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").doesNotExist())
                .andExpect(jsonPath("$.costByProvider").isEmpty())
                .andExpect(jsonPath("$.costByProject").isEmpty())
                .andExpect(jsonPath("$.budgetVariance").isEmpty())
                .andExpect(jsonPath("$.duplicateCandidates").doesNotExist())
                .andExpect(jsonPath("$.openReconciliations").doesNotExist())
                .andExpect(jsonPath("$.pendingApprovals.submittedCount").value(1));

        var keys = redis.keys("workbench:" + orgId + ":*");
        assertThat(keys).filteredOn(key -> key.contains(":perm:")).hasSize(2);
    }

    @Test
    void unknownExplicitPeriodIsRejected() throws Exception {
        grant("WORKBENCH_FINANCE", List.of("PERIOD_READ", "COST_READ"));

        mockMvc.perform(get("/api/v1/workbench").param("billingPeriodId", "987654321")
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void cacheServesStaleViewsUntilTtlOrEviction() throws Exception {
        grant("WORKBENCH_FINANCE", List.of("PERIOD_READ", "COST_READ"));
        insertOpenJanuaryPeriod();
        insertCharge("10.00000000");

        mockMvc.perform(get("/api/v1/workbench").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costByProvider[0].totalAmount").value("10.00000000"));

        // Inside the TTL a second read is served from cache even though the
        // basis changed underneath.
        insertCharge("15.00000000");
        mockMvc.perform(get("/api/v1/workbench").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costByProvider[0].totalAmount").value("10.00000000"));

        // After eviction (TTL expiry simulated by flush) MySQL is re-read.
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        mockMvc.perform(get("/api/v1/workbench").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costByProvider[0].totalAmount").value("25.00000000"));
    }

    @Test
    void redisRestartFallsBackToMysqlAndQueriesRemainCorrectAfterRecovery() throws Exception {
        grant("WORKBENCH_FINANCE", List.of("PERIOD_READ", "COST_READ"));
        insertOpenJanuaryPeriod();
        insertCharge("10.00000000");

        mockMvc.perform(get("/api/v1/workbench").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costByProvider[0].totalAmount").value("10.00000000"));

        AUTH_REDIS.getDockerClient().pauseContainerCmd(AUTH_REDIS.getContainerId()).exec();
        try {
            mockMvc.perform(get("/api/v1/workbench").header("Authorization", bearer()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.costByProvider[0].totalAmount").value("10.00000000"))
                    .andExpect(jsonPath("$.costByProvider[0].currency").value("CNY"));
        } finally {
            AUTH_REDIS.getDockerClient().unpauseContainerCmd(AUTH_REDIS.getContainerId()).exec();
            awaitRedisRecovery();
        }

        mockMvc.perform(get("/api/v1/workbench").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costByProvider[0].totalAmount").value("10.00000000"));
    }

    /**
     * A charge allocated while CLEAN and later marked EXCLUDED_DUPLICATE leaves the provider
     * basis. The project rollup must use that same basis, otherwise a confirmed allocation for
     * the excluded charge would keep it in the project total while provider totals and
     * unallocated charges had already dropped it, and the workbench identity would break.
     */
    @Test
    void excludedDuplicateChargeLeavesTheProjectRollupWithTheProviderBasis() throws Exception {
        grant("WORKBENCH_FINANCE", List.of("PERIOD_READ", "COST_READ", "ALLOCATION_READ"));
        insertOpenJanuaryPeriod();
        var keptChargeId = insertCharge("10.00000000");
        insertCharge("5.00000000");
        var excludedChargeId = insertCharge("20.00000000");
        confirmChargeAllocation(keptChargeId, "10.00000000");
        confirmChargeAllocation(excludedChargeId, "20.00000000");
        jdbc.update("""
                UPDATE charge_fact SET review_status='EXCLUDED_DUPLICATE', duplicate_of_charge_id=?
                WHERE org_id=? AND id=?
                """, keptChargeId, orgId, excludedChargeId);

        var response = mockMvc.perform(get("/api/v1/workbench").header("Authorization", bearer()))
                .andExpect(status().isOk())
                // Provider basis: only the two CLEAN charges (10.00 + 5.00); the excluded charge
                // is excluded cost, not unallocated clean cost.
                .andExpect(jsonPath("$.costByProvider.length()").value(1))
                .andExpect(jsonPath("$.costByProvider[0].totalAmount").value("15.00000000"))
                .andExpect(jsonPath("$.costByProvider[0].chargeCount").value(2))
                // Project rollup shares the basis: only the CLEAN confirmed allocation counts.
                .andExpect(jsonPath("$.costByProject.length()").value(1))
                .andExpect(jsonPath("$.costByProject[0].projectName").value("Target"))
                .andExpect(jsonPath("$.costByProject[0].totalAmount").value("10.00000000"))
                .andExpect(jsonPath("$.unallocatedCharges.length()").value(1))
                .andExpect(jsonPath("$.unallocatedCharges[0].amount").value("5.00000000"))
                .andExpect(jsonPath("$.unallocatedCharges[0].chargeCount").value(1))
                .andReturn();

        // Workbench identity on the shared basis: provider total = project total + unallocated.
        var body = objectMapper.readTree(response.getResponse().getContentAsString());
        var providerTotal = new BigDecimal(
                body.get("costByProvider").get(0).get("totalAmount").asText());
        var projectTotal = new BigDecimal(
                body.get("costByProject").get(0).get("totalAmount").asText());
        var unallocatedTotal = new BigDecimal(
                body.get("unallocatedCharges").get(0).get("amount").asText());
        assertThat(providerTotal).isEqualByComparingTo(projectTotal.add(unallocatedTotal));
        assertThat(projectTotal).isEqualByComparingTo(new BigDecimal("10.00000000"));
        assertThat(unallocatedTotal).isEqualByComparingTo(new BigDecimal("5.00000000"));
    }

    private void confirmChargeAllocation(long chargeId, String amount) {
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?,'CHARGE_FACT',?,NULL,'MANUAL',NULL,'CONFIRMED',NULL,UTC_TIMESTAMP(6))
                """, orgId, chargeId);
        var decisionId = jdbc.queryForObject(
                "SELECT id FROM allocation_decision WHERE org_id=? AND charge_fact_id=?",
                Long.class, orgId, chargeId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?,?,0,?,'CNY',?,NULL,NULL,UTC_TIMESTAMP(6))
                """, orgId, decisionId, amount, projectId);
        jdbc.update("""
                UPDATE charge_fact SET current_allocation_decision_id=?
                WHERE org_id=? AND id=?
                """, decisionId, orgId, chargeId);
    }

    private void awaitRedisRecovery() {
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                org.assertj.core.api.Assertions.assertThat(
                        redis.opsForValue().increment("m8:redis-recovery")).isPositive());
        redis.delete("m8:redis-recovery");
    }

    private void grant(String roleCode, List<String> permissions) {
        createPermissionRole(roleCode, permissions);
        assign(roleCode, "ORG", orgId);
    }

    private long insertOpenJanuaryPeriod() {
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,? ,? ,'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, JAN_1, FEB_1);
        return jdbc.queryForObject(
                "SELECT id FROM billing_period WHERE org_id=? ORDER BY id DESC LIMIT 1",
                Long.class, orgId);
    }

    private void insertBudget(long periodId, String total, String actual, String committed) {
        jdbc.update("""
                INSERT INTO budget(
                    org_id,billing_period_id,scope_type,scope_id,currency,
                    total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?,'ORG',?,'CNY',?,?,?,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, periodId, orgId, total, actual, committed);
    }

    private void insertReconciliationRunAndCase() {
        jdbc.update("""
                INSERT INTO reconciliation_run(
                    org_id,billing_period_id,status,algorithm_version,tolerance_amount,
                    summary_json,created_by_member_id,started_at,created_at,updated_at)
                VALUES (?,?,'RUNNING','workbench-test','0.00000000','{}',?,
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId,
                jdbc.queryForObject(
                        "SELECT id FROM billing_period WHERE org_id=? ORDER BY id DESC LIMIT 1",
                        Long.class, orgId),
                actorMemberId);
        var runId = jdbc.queryForObject(
                "SELECT id FROM reconciliation_run WHERE org_id=? ORDER BY id DESC LIMIT 1",
                Long.class, orgId);
        jdbc.update("""
                INSERT INTO reconciliation_case(
                    org_id,reconciliation_run_id,provider_account_id,currency,case_type,
                    external_amount,difference_amount,external_row_count,internal_row_count,status,
                    created_at,updated_at)
                VALUES (?,?,?,'CNY','MISSING_INTERNAL','3.00000000','3.00000000',1,0,'OPEN',
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, accountId);
    }
}
