package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.reconciliation.application.CloseBlockerContext;
import com.aicostops.reconciliation.application.CloseBlockerProvider;
import com.aicostops.reconciliation.application.CloseBlockerRegistry;
import com.aicostops.reconciliation.domain.CloseBlockerCode;
import com.aicostops.reconciliation.domain.PeriodCloseCheckResult;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CloseBlockerProviderIntegrationTest extends AllocationApiTestSupport {

    @Autowired CloseBlockerRegistry registry;

    private long periodId;
    private CloseBlockerContext context;

    @BeforeEach
    void closeFixture() {
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,? ,? ,'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, JAN_1, FEB_1);
        periodId = jdbc.queryForObject(
                "SELECT id FROM billing_period WHERE org_id=? ORDER BY id DESC LIMIT 1",
                Long.class, orgId);
        context = new CloseBlockerContext(orgId, periodId,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    void registryContainsExactlySevenCanonicalProviders() {
        assertThat(registry.providers()).extracting(CloseBlockerProvider::code)
                .containsExactly(CloseBlockerCode.values());
    }

    @Test
    void sourceBlockersDetectPeriodEndWorkAndPendingCorrectionsIsExplicitPass() {
        insertUnknownPeriodPendingImport();
        var first = insertCharge("5.00000000");
        var second = insertCharge("6.00000000");
        insertOpenDuplicate(first, second);
        insertApprovedJanuaryExpense();
        insertPostingWithoutEntry();

        var results = registry.providers().stream()
                .map(provider -> provider.evaluate(context))
                .collect(Collectors.toMap(result -> result.code(), Function.identity()));

        assertThat(results).hasSize(7);
        assertThat(results.get(CloseBlockerCode.OPEN_IMPORTS).result())
                .isEqualTo(PeriodCloseCheckResult.FAIL);
        assertThat(results.get(CloseBlockerCode.UNRESOLVED_DUPLICATES).result())
                .isEqualTo(PeriodCloseCheckResult.FAIL);
        assertThat(results.get(CloseBlockerCode.UNALLOCATED_CHARGES).result())
                .isEqualTo(PeriodCloseCheckResult.FAIL);
        assertThat(results.get(CloseBlockerCode.UNPOSTED_APPROVED_EXPENSES).result())
                .isEqualTo(PeriodCloseCheckResult.FAIL);
        assertThat(results.get(CloseBlockerCode.OPEN_MATERIAL_RECONCILIATION).result())
                .isEqualTo(PeriodCloseCheckResult.FAIL);
        assertThat(results.get(CloseBlockerCode.PENDING_CORRECTIONS).result())
                .isEqualTo(PeriodCloseCheckResult.PASS);
        assertThat(results.get(CloseBlockerCode.PENDING_CORRECTIONS).summary())
                .containsEntry("notApplicable", true);
        assertThat(results.get(CloseBlockerCode.LEDGER_INTEGRITY).result())
                .isEqualTo(PeriodCloseCheckResult.FAIL);
    }

    private void insertUnknownPeriodPendingImport() {
        var suffix = "close-pending-" + System.nanoTime();
        var sha = Integer.toHexString(suffix.hashCode()).replace("-", "a");
        sha = (sha + "0".repeat(64)).substring(0, 64);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,size_bytes,uploaded_by_member_id,
                    storage_status,created_at,updated_at)
                VALUES (?,?,?,'pending.csv',1,?,'AVAILABLE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, sha, "m6/" + suffix, actorMemberId);
        var evidenceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,'GLM','FILE_EXPORT','test-v1','PENDING',NULL,NULL,?,
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, evidenceId, accountId, actorMemberId);
    }

    private void insertOpenDuplicate(long first, long second) {
        var low = Math.min(first, second);
        var high = Math.max(first, second);
        jdbc.update("""
                INSERT INTO duplicate_candidate(
                    org_id,charge_fact_id,matched_charge_id,candidate_type,fingerprint,
                    algorithm_version,match_reason,status,created_at,resolved_at)
                VALUES (?,?,?,'EXACT',?,'test-v1','same charge','OPEN',UTC_TIMESTAMP(6),NULL)
                """, orgId, low, high, "d".repeat(64));
    }

    private void insertApprovedJanuaryExpense() {
        jdbc.update("""
                INSERT INTO expense_claim(
                    org_id,claimant_member_id,expense_date,amount,currency,status,version,
                    created_at,updated_at)
                VALUES (?,?,'2026-01-15','3.00000000','CNY','APPROVED',0,
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, actorMemberId);
    }

    private void insertPostingWithoutEntry() {
        jdbc.update("""
                INSERT INTO ledger_posting(
                    org_id,posting_key,source_type,source_id,allocation_decision_id,
                    billing_period_id,status,posted_by_member_id,posted_at,created_at)
                VALUES (?,?,'PROVIDER_CHARGE',999999,NULL,?,'POSTED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "M6-INTEGRITY-" + System.nanoTime(), periodId, actorMemberId);
    }
}
