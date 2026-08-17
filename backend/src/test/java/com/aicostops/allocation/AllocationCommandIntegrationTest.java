package com.aicostops.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.application.AllocationCommands.AllocationLineCommand;
import com.aicostops.allocation.application.AllocationDecisionCommandService;
import com.aicostops.allocation.application.AllocationReadModels.AllocationChargeRow;
import com.aicostops.allocation.infrastructure.AllocationChargeFactMapper;
import com.aicostops.audit.application.AuditService;
import com.aicostops.attribution.domain.AllocationDecisionSource;
import com.aicostops.cost.domain.ReviewStatus;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Confirm atomicity and concurrency: exactly one CONFIRMED decision per charge,
 * no line mutation after confirm, and full rollback on audit or pointer-write
 * failure.
 */
@SpringBootTest
@Tag("integration")
class AllocationCommandIntegrationTest extends AllocationApiTestSupport {

    private static final AtomicBoolean FAIL_AUDIT = new AtomicBoolean(false);
    private static final AtomicBoolean FAIL_POINTER = new AtomicBoolean(false);

    @TestConfiguration
    static class SwitchableConfiguration {
        @Bean
        @Primary
        com.aicostops.allocation.application.AllocationAuditPort switchableAuditPort(
                AuditService auditService) {
            var real = new com.aicostops.allocation.infrastructure.AuditAllocationAdapter(auditService);
            return (organizationId, actorUserId, decisionId, chargeFactId, decisionSource,
                    allocationRuleId, lineCount, currency) -> {
                if (FAIL_AUDIT.get()) {
                    throw new IllegalStateException("test audit failure");
                }
                real.decisionConfirmed(organizationId, actorUserId, decisionId, chargeFactId,
                        decisionSource, allocationRuleId, lineCount, currency);
            };
        }

        @Bean
        @Primary
        AllocationChargeFactMapper switchableChargeMapper(AllocationChargeFactMapper real) {
            return new SwitchableChargeFactMapper(real);
        }
    }

    @Autowired
    private AllocationDecisionCommandService commands;

    private long chargeId;

    @BeforeEach
    void setUpSwitches() {
        FAIL_AUDIT.set(false);
        FAIL_POINTER.set(false);
        chargeId = insertCharge("10.00000000");
    }

    @AfterEach
    void tearDownSwitches() {
        FAIL_AUDIT.set(false);
        FAIL_POINTER.set(false);
    }

    @Test
    void twoDraftsConfirmSameChargeExactlyOneWins() throws Exception {
        var manual = commands.createManualDraft(user(), chargeId,
                new com.aicostops.allocation.application.AllocationCommands.ManualDraftCommand(
                        List.of(new AllocationLineCommand(
                                new BigDecimal("4.00000000"), "CNY", projectId, null, null),
                                new AllocationLineCommand(
                                        new BigDecimal("6.00000000"), "CNY", null, costCenterId, null))),
                "race-manual");
        var ruleId = insertRule(orgId, actorMemberId, projectId, accountId,
                "PROVIDER_PROJECT", "race-rule");
        var ruleDraft = insertRuleDraft(orgId, chargeId, ruleId, projectId, "10.00000000", "CNY");

        var outcomes = runConcurrently(
                () -> commands.confirm(user(), manual.decision().id(), "race-confirm-manual"),
                () -> commands.confirm(user(), ruleDraft, "race-confirm-rule"));

        assertThat(outcomes.successCount()).isEqualTo(1);
        assertThat(outcomes.conflictCount()).isEqualTo(1);
        var confirmed = jdbc.queryForList("""
                SELECT id FROM allocation_decision
                WHERE org_id=? AND charge_fact_id=? AND status='CONFIRMED'
                """, orgId, chargeId);
        assertThat(confirmed).hasSize(1);
        var winner = ((Number) confirmed.get(0).get("id")).longValue();
        assertThat(currentDecisionPointer(chargeId)).isEqualTo(winner);
        // The loser stays a DRAFT; it must never overwrite the pointer.
        var loser = winner == manual.decision().id() ? ruleDraft : manual.decision().id();
        assertThat(decisionStatus(loser)).isEqualTo("DRAFT");
        assertThat(auditCount("ALLOCATION_DECISION_CONFIRMED")).isEqualTo(1);
    }

    @Test
    void confirmVersusEditNeverMutatesLinesAfterConfirm() throws Exception {
        var draft = commands.createManualDraft(user(), chargeId,
                new com.aicostops.allocation.application.AllocationCommands.ManualDraftCommand(
                        List.of(new AllocationLineCommand(
                                new BigDecimal("4.00000000"), "CNY", projectId, null, null),
                                new AllocationLineCommand(
                                        new BigDecimal("6.00000000"), "CNY", null, costCenterId, null))),
                "race-edit-manual");
        var decisionId = draft.decision().id();

        var outcomes = runConcurrently(
                () -> commands.confirm(user(), decisionId, "race-edit-confirm"),
                () -> commands.replaceLines(user(), decisionId, List.of(new AllocationLineCommand(
                        new BigDecimal("10.00000000"), "CNY", projectId, null, null))));

        // The confirm always wins the race (edit before confirm is fine too);
        // no unexpected error may escape.
        assertThat(outcomes.successCount() + outcomes.conflictCount()).isEqualTo(2);
        assertThat(decisionStatus(decisionId)).isEqualTo("CONFIRMED");
        // Lines are a complete set that the confirm validated: either the
        // original two lines or the replaced single line, never a partial mix.
        var count = lineCount(decisionId);
        assertThat(count).isIn(1, 2);
        assertThat(lineSum(decisionId)).isEqualTo("10.00000000");

        // A further edit after the race must be rejected: no post-confirm mutation.
        assertThatThrownBy(() -> commands.replaceLines(user(), decisionId,
                List.of(new AllocationLineCommand(
                        new BigDecimal("5.00000000"), "CNY", projectId, null, null))))
                .satisfies(thrown -> assertDomain((DomainException) thrown, 409, "DECISION_NOT_DRAFT"));
        assertThat(lineSum(decisionId)).isEqualTo("10.00000000");
    }

    @Test
    void auditFailureRollsBackDecisionPointerAndIdempotency() {
        var draft = commands.createManualDraft(user(), chargeId,
                new com.aicostops.allocation.application.AllocationCommands.ManualDraftCommand(
                        List.of(new AllocationLineCommand(
                                new BigDecimal("10.00000000"), "CNY", projectId, null, null))),
                "audit-fail-draft");
        FAIL_AUDIT.set(true);
        try {
            assertThatThrownBy(() -> commands.confirm(user(), draft.decision().id(), "audit-fail"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("test audit failure");
        } finally {
            FAIL_AUDIT.set(false);
        }

        assertThat(decisionStatus(draft.decision().id())).isEqualTo("DRAFT");
        assertThat(currentDecisionPointer(chargeId)).isNull();
        assertThat(auditCount("ALLOCATION_DECISION_CONFIRMED")).isZero();
        assertThat(idempotencyCount("ALLOCATION_CONFIRM")).isZero();
    }

    @Test
    void pointerFailureRollsBackDecisionStatusAuditAndIdempotency() {
        var draft = commands.createManualDraft(user(), chargeId,
                new com.aicostops.allocation.application.AllocationCommands.ManualDraftCommand(
                        List.of(new AllocationLineCommand(
                                new BigDecimal("10.00000000"), "CNY", projectId, null, null))),
                "pointer-fail-draft");
        FAIL_POINTER.set(true);
        try {
            assertThatThrownBy(() -> commands.confirm(user(), draft.decision().id(), "pointer-fail"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("test pointer failure");
        } finally {
            FAIL_POINTER.set(false);
        }

        assertThat(decisionStatus(draft.decision().id())).isEqualTo("DRAFT");
        assertThat(currentDecisionPointer(chargeId)).isNull();
        assertThat(auditCount("ALLOCATION_DECISION_CONFIRMED")).isZero();
        assertThat(idempotencyCount("ALLOCATION_CONFIRM")).isZero();
    }

    // -- helpers ----------------------------------------------------------------

    private AuthenticatedUser user() {
        return new AuthenticatedUser(actorUserId, 7);
    }

    private record RaceOutcomes(int successCount, int conflictCount) {
    }

    private int idempotencyCount(String operation) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_idempotency WHERE operation=?",
                Integer.class, operation);
    }

    private RaceOutcomes runConcurrently(ThrowingRunnable first, ThrowingRunnable second)
            throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            var futures = List.of(pool.submit(task(first, start)), pool.submit(task(second, start)));
            start.countDown();
            int success = 0;
            int conflict = 0;
            for (Future<Void> future : futures) {
                try {
                    future.get(30, TimeUnit.SECONDS);
                    success++;
                } catch (java.util.concurrent.ExecutionException execution) {
                    if (execution.getCause() instanceof DomainException domain
                            && domain.status().value() == 409) {
                        conflict++;
                    } else {
                        throw execution;
                    }
                }
            }
            return new RaceOutcomes(success, conflict);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static java.util.concurrent.Callable<Void> task(ThrowingRunnable runnable,
            CountDownLatch start) {
        return () -> {
            start.await();
            runnable.run();
            return null;
        };
    }

    private static void assertDomain(DomainException thrown, int status, String code) {
        assertThat(thrown.status().value()).isEqualTo(status);
        assertThat(thrown.code().name()).isEqualTo(code);
    }

    /** Test-only decorator that can fail the current-decision pointer write. */
    static class SwitchableChargeFactMapper implements AllocationChargeFactMapper {

        private final AllocationChargeFactMapper delegate;

        SwitchableChargeFactMapper(AllocationChargeFactMapper delegate) {
            this.delegate = delegate;
        }

        @Override
        public AllocationChargeRow selectCharge(long organizationId, long chargeFactId) {
            return delegate.selectCharge(organizationId, chargeFactId);
        }

        @Override
        public AllocationChargeRow selectChargeForUpdate(long organizationId, long chargeFactId) {
            return delegate.selectChargeForUpdate(organizationId, chargeFactId);
        }

        @Override
        public com.aicostops.allocation.application.AllocationReadModels.ChargeLineage selectLineage(
                long organizationId, long chargeFactId) {
            return delegate.selectLineage(organizationId, chargeFactId);
        }

        @Override
        public HintRow selectHint(long organizationId, long rawRecordId, int factIndex) {
            return delegate.selectHint(organizationId, rawRecordId, factIndex);
        }

        @Override
        public int updateCurrentDecisionPointer(long organizationId, long chargeFactId,
                long decisionId) {
            if (FAIL_POINTER.get()) {
                throw new IllegalStateException("test pointer failure");
            }
            return delegate.updateCurrentDecisionPointer(organizationId, chargeFactId, decisionId);
        }
    }
}
