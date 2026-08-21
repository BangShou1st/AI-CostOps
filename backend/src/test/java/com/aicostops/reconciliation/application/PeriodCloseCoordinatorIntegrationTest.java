package com.aicostops.reconciliation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.iam.application.AuthorizationContextService;
import com.aicostops.reconciliation.domain.PeriodCloseCheckResult;
import com.aicostops.reconciliation.domain.PeriodCloseRunStatus;
import com.aicostops.shared.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PeriodCloseCoordinatorIntegrationTest extends AllocationApiTestSupport {

    @Autowired ReconciliationRunService reconciliationRuns;
    @Autowired PeriodCloseService close;
    @Autowired AuthorizationContextService authorizationContexts;

    private long periodId;
    private AuthenticatedUser actor;

    @BeforeEach
    void closeSetup() {
        grantM6FinancePermissions("ALLOC_WORKER");
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,? ,? ,'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, JAN_1, FEB_1);
        periodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
        actor = new AuthenticatedUser(actorUserId, 7);
    }

    @Test
    void blockedCloseReturnsPeriodOpenAndPersistsExactlySevenChecks() {
        var result = close.close(actor, periodId);

        assertThat(result.period().status().name()).isEqualTo("OPEN");
        assertThat(result.run().status()).isEqualTo(PeriodCloseRunStatus.BLOCKED);
        assertThat(result.run().attemptNo()).isEqualTo(1);
        assertThat(result.checks()).hasSize(7);
        assertThat(result.checks()).extracting(check -> check.blockerCode().name())
                .containsExactly(
                        "OPEN_IMPORTS", "UNRESOLVED_DUPLICATES", "UNALLOCATED_CHARGES",
                        "UNPOSTED_APPROVED_EXPENSES", "OPEN_MATERIAL_RECONCILIATION",
                        "PENDING_CORRECTIONS", "LEDGER_INTEGRITY");
        assertThat(result.checks()).anySatisfy(check -> {
            if (check.blockerCode().name().equals("OPEN_MATERIAL_RECONCILIATION")) {
                assertThat(check.result()).isEqualTo(PeriodCloseCheckResult.FAIL);
            }
        });
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM period_close_check WHERE period_close_run_id=?",
                Long.class, result.run().id())).isEqualTo(7);
    }

    @Test
    void cleanCloseClosesPeriodAndResponseLossRetryDoesNotCreateSecondRun() {
        reconciliationRuns.run(actor, periodId);
        var first = close.close(actor, periodId);

        assertThat(first.period().status().name()).isEqualTo("CLOSED");
        assertThat(first.run().status()).isEqualTo(PeriodCloseRunStatus.CLOSED);
        assertThat(first.checks()).hasSize(7)
                .allMatch(check -> check.result() == PeriodCloseCheckResult.PASS);
        var runCount = closeRunCount();

        var replay = close.close(actor, periodId);
        assertThat(replay.run().id()).isEqualTo(first.run().id());
        assertThat(closeRunCount()).isEqualTo(runCount);
    }

    @Test
    void interruptedClosingResumesSameCheckingRun() {
        var context = authorizationContexts.fresh(actor);
        var begun = close.beginOrResume(context, periodId);

        assertThat(begun.period().status().name()).isEqualTo("CLOSING");
        assertThat(begun.run().status()).isEqualTo(PeriodCloseRunStatus.CHECKING);
        assertThat(closeRunCount()).isEqualTo(1);

        var resumed = close.close(actor, periodId);
        assertThat(resumed.run().id()).isEqualTo(begun.run().id());
        assertThat(resumed.run().status()).isEqualTo(PeriodCloseRunStatus.BLOCKED);
        assertThat(resumed.period().status().name()).isEqualTo("OPEN");
        assertThat(closeRunCount()).isEqualTo(1);
        assertThat(resumed.checks()).hasSize(7);
    }

    @Test
    void blockedRetryInSameGenerationUsesNextAttemptNumber() {
        var first = close.close(actor, periodId);
        var second = close.close(actor, periodId);

        assertThat(first.run().closeGeneration()).isZero();
        assertThat(second.run().closeGeneration()).isZero();
        assertThat(first.run().attemptNo()).isEqualTo(1);
        assertThat(second.run().attemptNo()).isEqualTo(2);
        assertThat(second.period().status().name()).isEqualTo("OPEN");
    }

    private long closeRunCount() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM period_close_run WHERE org_id=? AND billing_period_id=?",
                Long.class, orgId, periodId);
    }

    private void grantM6FinancePermissions(String roleCode) {
        jdbc.update("""
                INSERT IGNORE INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code=? AND p.code IN (
                  'RECONCILIATION_READ','RECONCILIATION_RUN','RECONCILIATION_RESOLVE',
                  'PERIOD_READ','PERIOD_CLOSE','PERIOD_REOPEN')
                """, roleCode);
    }
}
