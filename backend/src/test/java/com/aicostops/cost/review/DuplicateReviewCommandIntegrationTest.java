package com.aicostops.cost.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.audit.application.AuditService;
import com.aicostops.cost.domain.ReviewStatus;
import com.aicostops.cost.review.application.DuplicateReviewCommandService;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.CandidateSummary;
import com.aicostops.cost.review.domain.CandidateStatus;
import com.aicostops.cost.review.infrastructure.AuditDuplicateReviewAdapter;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Keep / Exclude command semantics: candidate state machine, charge aggregate
 * reconciliation, duplicate-chain guards, exact-key idempotency, audit
 * atomicity, and lock-order concurrency on shared endpoints.
 */
@SpringBootTest
@Tag("integration")
class DuplicateReviewCommandIntegrationTest extends AuthenticationContainersSupport {

    private static final String JAN_1 = "2026-01-01 00:00:00.000000";
    private static final String FEB_1 = "2026-02-01 00:00:00.000000";

    /** Test-only switch so one class can also cover the audit-rollback path. */
    private static final AtomicBoolean FAIL_AUDIT = new AtomicBoolean(false);

    @TestConfiguration
    static class SwitchableAuditConfiguration {
        @Bean
        @Primary
        com.aicostops.cost.review.application.DuplicateReviewAuditPort switchableAuditPort(
                AuditService auditService) {
            var real = new AuditDuplicateReviewAdapter(auditService);
            return new com.aicostops.cost.review.application.DuplicateReviewAuditPort() {
                @Override
                public void candidateKeptClean(long organizationId, long actorUserId,
                        CandidateSummary before, CandidateSummary after) {
                    if (FAIL_AUDIT.get()) {
                        throw new IllegalStateException("test audit failure");
                    }
                    real.candidateKeptClean(organizationId, actorUserId, before, after);
                }

                @Override
                public void candidateExcluded(long organizationId, long actorUserId, long candidateId,
                        long excludedChargeFactId, long keeperChargeFactId,
                        com.aicostops.cost.domain.ReviewStatus previousReviewStatus,
                        int supersededCandidateCount) {
                    if (FAIL_AUDIT.get()) {
                        throw new IllegalStateException("test audit failure");
                    }
                    real.candidateExcluded(organizationId, actorUserId, candidateId,
                            excludedChargeFactId, keeperChargeFactId, previousReviewStatus,
                            supersededCandidateCount);
                }
            };
        }
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private DuplicateReviewCommandService commands;

    private long fixtureCounter;

    private long orgId;
    private long foreignOrgId;
    private long actorUserId;
    private long actorMemberId;
    private long accountId;
    private long rawRecordId;

    private long charge1;
    private long charge2;
    private long charge3;

    @BeforeEach
    void setUp() {
        FAIL_AUDIT.set(false);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        var suffix = ++fixtureCounter + "-" + System.nanoTime();
        orgId = insertOrganization("Command Org", "cmd-" + suffix);
        foreignOrgId = insertOrganization("Command Foreign", "cmd-foreign-" + suffix);
        actorUserId = insertUser("reviewer-" + suffix + "@example.com");
        actorMemberId = insertMember(orgId, actorUserId);
        createPermissionRole("DUP_WORKER", List.of("DUPLICATE_REVIEW"));
        assign("DUP_WORKER", "ORG", orgId);
        accountId = insertProviderAccount(orgId, "GLM");
        rawRecordId = insertConfirmedRawRecord(orgId, actorMemberId, accountId, suffix);

        charge1 = insertCharge("10.00000000");
        charge2 = insertCharge("10.00000000");
        charge3 = insertCharge("10.00000000");
        commands.scan(user());
    }

    @AfterEach
    void tearDown() {
        FAIL_AUDIT.set(false);
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    // -- keep ------------------------------------------------------------------

    @Test
    void keepSingleOpenCandidateMarksKeptCleanAndRestoresBothChargesClean() {
        // isolate one pair: drop every candidate not touching the (1,2) pair
        jdbc.update("DELETE FROM duplicate_candidate WHERE (charge_fact_id,matched_charge_id) NOT IN ((?,?))",
                charge1, charge2);

        var summary = commands.keep(user(), candidateOf(charge1, charge2), "keep-1");

        assertThat(summary.candidate().status()).isEqualTo(CandidateStatus.KEPT_CLEAN);
        assertThat(summary.chargeFact().reviewStatus()).isEqualTo(ReviewStatus.CLEAN);
        assertThat(summary.matchedChargeFact().reviewStatus()).isEqualTo(ReviewStatus.CLEAN);
        assertThat(candidateStatus(charge1, charge2)).isEqualTo("KEPT_CLEAN");
        assertThat(reviewStatus(charge1)).isEqualTo("CLEAN");
        assertThat(reviewStatus(charge2)).isEqualTo("CLEAN");
        assertThat(auditCount("DUPLICATE_CANDIDATE_KEPT_CLEAN")).isEqualTo(1);
    }

    @Test
    void keepLeavesEndpointSuspectedWhileAnotherOpenCandidateRemains() {
        commands.keep(user(), candidateOf(charge1, charge2), "keep-2");

        assertThat(candidateStatus(charge1, charge2)).isEqualTo("KEPT_CLEAN");
        // charge1 still has OPEN (1,3); charge2 still has OPEN (2,3).
        assertThat(reviewStatus(charge1)).isEqualTo("SUSPECTED_DUPLICATE");
        assertThat(reviewStatus(charge2)).isEqualTo("SUSPECTED_DUPLICATE");
        assertThat(reviewStatus(charge3)).isEqualTo("SUSPECTED_DUPLICATE");
    }

    @Test
    void keepWithNewKeyAfterTerminalReturnsConflict() {
        commands.keep(user(), candidateOf(charge1, charge2), "keep-3a");

        assertThatThrownBy(() -> commands.keep(user(), candidateOf(charge1, charge2), "keep-3b"))
                .satisfies(thrown -> assertDomain(thrown, 409, "STATE_CONFLICT"));
    }

    @Test
    void sameKeyReplayReturnsCachedSemanticResponseWithoutSecondMutation() {
        jdbc.update("DELETE FROM duplicate_candidate WHERE (charge_fact_id,matched_charge_id) NOT IN ((?,?))",
                charge1, charge2);

        var first = commands.keep(user(), candidateOf(charge1, charge2), "keep-replay");
        var replay = commands.keep(user(), candidateOf(charge1, charge2), "keep-replay");

        assertThat(replay.candidate().id()).isEqualTo(first.candidate().id());
        assertThat(replay.candidate().status()).isEqualTo(first.candidate().status());
        assertThat(replay.chargeFact().id()).isEqualTo(first.chargeFact().id());
        assertThat(replay.chargeFact().reviewStatus()).isEqualTo(first.chargeFact().reviewStatus());
        assertThat(replay.matchedChargeFact().id()).isEqualTo(first.matchedChargeFact().id());
        assertThat(replay.matchedChargeFact().reviewStatus())
                .isEqualTo(first.matchedChargeFact().reviewStatus());
        assertThat(replay.chargeFact().amount()).isEqualByComparingTo(first.chargeFact().amount());
        assertThat(replay.matchedChargeFact().amount())
                .isEqualByComparingTo(first.matchedChargeFact().amount());
        assertThat(auditCount("DUPLICATE_CANDIDATE_KEPT_CLEAN")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isEqualTo(1);
    }

    // -- exclude ---------------------------------------------------------------

    @Test
    void excludeMarksChosenSideExcludedDuplicateOfKeeper() {
        jdbc.update("DELETE FROM duplicate_candidate WHERE (charge_fact_id,matched_charge_id) NOT IN ((?,?))",
                charge1, charge2);

        var summary = commands.exclude(user(), candidateOf(charge1, charge2), "exc-1", charge2);

        assertThat(summary.candidate().status()).isEqualTo(CandidateStatus.CONFIRMED_DUPLICATE);
        assertThat(summary.matchedChargeFact().reviewStatus()).isEqualTo(ReviewStatus.EXCLUDED_DUPLICATE);
        assertThat(reviewStatus(charge2)).isEqualTo("EXCLUDED_DUPLICATE");
        assertThat(duplicateOf(charge2)).isEqualTo(charge1);
        assertThat(reviewStatus(charge1)).isEqualTo("CLEAN");
        assertThat(duplicateOf(charge1)).isNull();
        assertThat(auditCount("DUPLICATE_CANDIDATE_EXCLUDED")).isEqualTo(1);
    }

    @Test
    void excludeSupersedesOtherOpenCandidatesTouchingExcludedSideAndCleansCounterparts() {
        // keep only (1,2) and (2,3): superseding (2,3) must leave charge3 with no
        // remaining OPEN candidate, so the aggregate can restore it to CLEAN.
        jdbc.update("DELETE FROM duplicate_candidate WHERE charge_fact_id=? AND matched_charge_id=?",
                charge1, charge3);
        var summary = commands.exclude(user(), candidateOf(charge1, charge2), "exc-2", charge2);

        assertThat(summary.candidate().status()).isEqualTo(CandidateStatus.CONFIRMED_DUPLICATE);
        // (2,3) touched the excluded charge and is superseded; its counterpart is clean.
        assertThat(candidateStatus(charge2, charge3)).isEqualTo("SUPERSEDED");
        assertThat(reviewStatus(charge3)).isEqualTo("CLEAN");
        // charge1 has no remaining OPEN candidate either: aggregate restores CLEAN.
        assertThat(reviewStatus(charge1)).isEqualTo("CLEAN");
    }

    @Test
    void excludeRejectsSideThatAlreadyCarriesADuplicatePointer() {
        jdbc.update("UPDATE charge_fact SET duplicate_of_charge_id=? WHERE id=?", charge1, charge2);

        assertThatThrownBy(() -> commands.exclude(user(), candidateOf(charge1, charge2), "exc-3", charge2))
                .satisfies(thrown -> assertDomain(thrown, 409, "STATE_CONFLICT"));
    }

    @Test
    void excludeRejectsChargeWithInboundDuplicateDependents() {
        // charge2 already excluded as a duplicate of charge1 (charge1 has an inbound dependent).
        commands.exclude(user(), candidateOf(charge1, charge2), "exc-4a", charge2);

        // Trying to exclude charge1 (the keeper with dependents) would create a chain.
        assertThatThrownBy(() -> commands.exclude(user(), candidateOf(charge1, charge3), "exc-4b", charge1))
                .satisfies(thrown -> assertDomain(thrown, 409, "STATE_CONFLICT"));
        assertThat(duplicateOf(charge1)).isNull();
    }

    @Test
    void keeperMayAlreadyHaveInboundDependentsAndRemainRoot() {
        commands.exclude(user(), candidateOf(charge1, charge2), "exc-5a", charge2);

        // charge1 is already the keeper of charge2; it may keep charge3 too.
        var summary = commands.exclude(user(), candidateOf(charge1, charge3), "exc-5b", charge3);

        assertThat(summary.candidate().status()).isEqualTo(CandidateStatus.CONFIRMED_DUPLICATE);
        assertThat(duplicateOf(charge3)).isEqualTo(charge1);
        assertThat(duplicateOf(charge1)).isNull();
        assertThat(reviewStatus(charge1)).isEqualTo("CLEAN");
    }

    @Test
    void crossOrgCandidateIsNotFound() {
        var foreignRaw = insertConfirmedRawRecord(foreignOrgId, actorMemberId,
                insertProviderAccount(foreignOrgId, "GLM"), "foreign-" + System.nanoTime());
        var foreignChargeA = insertCharge(foreignOrgId, foreignRaw, "7.00000000");
        var foreignChargeB = insertCharge(foreignOrgId, foreignRaw, "7.00000000");
        insertCandidateDirectly(foreignOrgId, foreignChargeA, foreignChargeB);
        var foreignCandidateId = candidateIdOf(foreignOrgId, foreignChargeA, foreignChargeB);

        assertThatThrownBy(() -> commands.keep(user(), foreignCandidateId, "keep-foreign"))
                .satisfies(thrown -> assertDomain(thrown, 404, "RESOURCE_NOT_FOUND"));
        assertThatThrownBy(() -> commands.exclude(user(), foreignCandidateId, "exc-foreign",
                foreignChargeB))
                .satisfies(thrown -> assertDomain(thrown, 404, "RESOURCE_NOT_FOUND"));
    }

    @Test
    void excludeWithIdOutsideThePairIsRejected() {
        assertThatThrownBy(() -> commands.exclude(user(), candidateOf(charge1, charge2), "exc-bad",
                charge3))
                .satisfies(thrown -> assertDomain(thrown, 400, "VALIDATION_FAILED"));
        assertThatThrownBy(() -> commands.exclude(user(), candidateOf(charge1, charge2), "exc-bad2",
                999999L))
                .satisfies(thrown -> assertDomain(thrown, 400, "VALIDATION_FAILED"));
    }

    // -- idempotency -----------------------------------------------------------

    @Test
    void rawKeysDifferingOnlyByWhitespaceAreDistinctCallerKeys() {
        jdbc.update("DELETE FROM duplicate_candidate WHERE (charge_fact_id,matched_charge_id) NOT IN ((?,?))",
                charge1, charge2);
        commands.keep(user(), candidateOf(charge1, charge2), "abc");

        // " abc" fingerprints differently from "abc": a NEW key against a terminal pair.
        assertThatThrownBy(() -> commands.keep(user(), candidateOf(charge1, charge2), " abc"))
                .satisfies(thrown -> assertDomain(thrown, 409, "STATE_CONFLICT"));
        assertThat(auditCount("DUPLICATE_CANDIDATE_KEPT_CLEAN")).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentExcludeBodyReturnsConflict() {
        commands.exclude(user(), candidateOf(charge1, charge2), "same-key", charge2);

        assertThatThrownBy(() -> commands.exclude(user(), candidateOf(charge1, charge2), "same-key",
                charge1))
                .satisfies(thrown -> assertDomain(thrown, 409, "STATE_CONFLICT"));
    }

    @Test
    void auditFailureRollsBackCandidateChargeAndIdempotencyTogether() {
        FAIL_AUDIT.set(true);
        try {
            assertThatThrownBy(() -> commands.exclude(user(), candidateOf(charge1, charge2),
                    "exc-audit-fail", charge2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("test audit failure");
        } finally {
            FAIL_AUDIT.set(false);
        }

        assertThat(candidateStatus(charge1, charge2)).isEqualTo("OPEN");
        assertThat(reviewStatus(charge2)).isEqualTo("SUSPECTED_DUPLICATE");
        assertThat(duplicateOf(charge2)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isZero();
    }

    // -- concurrency -----------------------------------------------------------

    @Test
    void keepVersusExcludeOnSameCandidateCommitsExactlyOneOutcome() throws Exception {
        var candidateId = candidateOf(charge1, charge2);
        var outcomes = runConcurrently(
                () -> commands.keep(user(), candidateId, "race-keep"),
                () -> commands.exclude(user(), candidateId, "race-exclude", charge2));

        assertThat(outcomes.successCount()).isEqualTo(1);
        assertThat(outcomes.conflictCount()).isEqualTo(1);
        var terminal = candidateStatus(charge1, charge2);
        assertThat(terminal).isIn("KEPT_CLEAN", "CONFIRMED_DUPLICATE");
        // No orphan SUSPECTED when nothing OPEN remains on an endpoint.
        if ("KEPT_CLEAN".equals(terminal)) {
            // (2,3) may be superseded or still open; (1,3) keeps charge1 suspected unless kept too.
            assertThat(reviewStatus(charge2)).isIn("CLEAN", "SUSPECTED_DUPLICATE");
        } else {
            assertThat(reviewStatus(charge2)).isEqualTo("EXCLUDED_DUPLICATE");
            assertThat(duplicateOf(charge2)).isEqualTo(charge1);
        }
    }

    @Test
    void twoExcludesSharingAnEndpointNeverProduceAChainOrLostUpdate() throws Exception {
        var candidate12 = candidateOf(charge1, charge2);
        var candidate13 = candidateOf(charge1, charge3);
        var outcomes = runConcurrently(
                () -> commands.exclude(user(), candidate12, "race-a", charge2),
                () -> commands.exclude(user(), candidate13, "race-b", charge3));

        assertThat(outcomes.successCount() + outcomes.conflictCount()).isEqualTo(2);
        // No chain: nobody points at an excluded charge, and the shared keeper stays a root.
        assertThat(duplicateOf(charge1)).isNull();
        assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM charge_fact cf
                        JOIN charge_fact dep ON dep.duplicate_of_charge_id = cf.id
                        WHERE cf.org_id=? AND cf.duplicate_of_charge_id IS NOT NULL
                        """, Integer.class, orgId)).isZero();
        // Charges 2 and 3 either both resolved under keeper 1 or untouched.
        assertThat(reviewStatus(charge2)).isIn("EXCLUDED_DUPLICATE", "SUSPECTED_DUPLICATE", "CLEAN");
        assertThat(reviewStatus(charge3)).isIn("EXCLUDED_DUPLICATE", "SUSPECTED_DUPLICATE", "CLEAN");
        // No endpoint stays SUSPECTED without an OPEN candidate.
        assertNoOrphanSuspected();
    }

    @Test
    void concurrentKeepAndExcludeOnTouchingCandidatesKeepInvariantsStable() throws Exception {
        var candidate12 = candidateOf(charge1, charge2);
        var candidate13 = candidateOf(charge1, charge3);
        var outcomes = runConcurrently(
                () -> commands.keep(user(), candidate12, "mix-keep"),
                () -> commands.exclude(user(), candidate13, "mix-exclude", charge3));

        // Bounded deadlock retry absorbs lock inversion; outcomes are 200 or 409 only.
        assertThat(outcomes.successCount() + outcomes.conflictCount()).isEqualTo(2);
        assertNoOrphanSuspected();
        assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM charge_fact cf
                        JOIN charge_fact dep ON dep.duplicate_of_charge_id = cf.id
                        WHERE cf.org_id=? AND cf.duplicate_of_charge_id IS NOT NULL
                        """, Integer.class, orgId)).isZero();
    }

    @Test
    void scanVersusExcludeOnSharedEndpointStaysConsistent() throws Exception {
        // Keep only (1,2): the concurrent scan tries to re-create (1,3) and
        // (2,3) while the exclude resolves (1,2) by excluding the shared
        // endpoint charge1 (keeper charge2, third charge charge3).
        jdbc.update("""
                DELETE FROM duplicate_candidate
                WHERE (charge_fact_id=? AND matched_charge_id=?) OR (charge_fact_id=? AND matched_charge_id=?)
                """, charge1, charge3, charge2, charge3);
        var candidate12 = candidateOf(charge1, charge2);

        var outcomes = runConcurrently(
                () -> commands.scan(user()),
                () -> commands.exclude(user(), candidate12, "race-scan-exclude", charge1));

        // Both sides finish as committed success or domain 409 after the
        // bounded deadlock retry; no unexpected error may escape.
        assertThat(outcomes.successCount() + outcomes.conflictCount()).isEqualTo(2);

        // The excluded endpoint is terminal and points at its keeper.
        assertThat(reviewStatus(charge1)).isEqualTo("EXCLUDED_DUPLICATE");
        assertThat(duplicateOf(charge1)).isEqualTo(charge2);
        // No OPEN candidate may survive on the excluded endpoint.
        assertThat(openCountTouching(charge1)).isZero();
        // The third charge must never stay SUSPECTED without an OPEN candidate.
        if (openCountTouching(charge3) == 0) {
            assertThat(reviewStatus(charge3)).isEqualTo("CLEAN");
        }
        assertNoOrphanSuspected();
        assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM charge_fact cf
                        JOIN charge_fact dep ON dep.duplicate_of_charge_id = cf.id
                        WHERE cf.org_id=? AND cf.duplicate_of_charge_id IS NOT NULL
                        """, Integer.class, orgId)).isZero();
    }

    // -- helpers ----------------------------------------------------------------

    private record RaceOutcomes(int successCount, int conflictCount) {
    }

    private RaceOutcomes runConcurrently(ThrowingRunnable first, ThrowingRunnable second) throws Exception {
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
                            && "STATE_CONFLICT".equals(domain.code().name())) {
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

    private static java.util.concurrent.Callable<Void> task(ThrowingRunnable runnable, CountDownLatch start) {
        return () -> {
            start.await();
            runnable.run();
            return null;
        };
    }

    private int openCountTouching(long chargeId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM duplicate_candidate
                WHERE org_id=? AND status='OPEN'
                  AND (charge_fact_id=? OR matched_charge_id=?)
                """, Integer.class, orgId, chargeId, chargeId);
    }

    private void assertNoOrphanSuspected() {
        var orphans = jdbc.queryForList("""
                SELECT cf.id FROM charge_fact cf
                WHERE cf.org_id=? AND cf.review_status='SUSPECTED_DUPLICATE'
                  AND NOT EXISTS (
                      SELECT 1 FROM duplicate_candidate dc
                      WHERE dc.org_id=cf.org_id AND dc.status='OPEN'
                        AND (dc.charge_fact_id=cf.id OR dc.matched_charge_id=cf.id))
                """, Long.class, orgId);
        assertThat(orphans).isEmpty();
    }

    private static void assertDomain(Throwable throwable, int status, String code) {
        assertThat(throwable).isInstanceOf(DomainException.class);
        var exception = (DomainException) throwable;
        assertThat(exception.status().value()).isEqualTo(status);
        assertThat(exception.code().name()).isEqualTo(code);
    }

    private AuthenticatedUser user() {
        return new AuthenticatedUser(actorUserId, 7);
    }

    private long candidateOf(long low, long high) {
        return candidateIdOf(orgId, low, high);
    }

    private long candidateIdOf(long org, long low, long high) {
        return jdbc.queryForObject("""
                SELECT id FROM duplicate_candidate
                WHERE org_id=? AND charge_fact_id=? AND matched_charge_id=?
                """, Long.class, org, low, high);
    }

    private String candidateStatus(long low, long high) {
        return jdbc.queryForObject("""
                SELECT status FROM duplicate_candidate
                WHERE org_id=? AND charge_fact_id=? AND matched_charge_id=?
                """, String.class, orgId, low, high);
    }

    private String reviewStatus(long chargeId) {
        return jdbc.queryForObject("SELECT review_status FROM charge_fact WHERE id=?",
                String.class, chargeId);
    }

    private Long duplicateOf(long chargeId) {
        return jdbc.queryForObject("SELECT duplicate_of_charge_id FROM charge_fact WHERE id=?",
                Long.class, chargeId);
    }

    private int auditCount(String eventType) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE event_type=?",
                Integer.class, eventType);
    }

    private void insertCandidateDirectly(long org, long low, long high) {
        jdbc.update("""
                INSERT INTO duplicate_candidate(
                    org_id,charge_fact_id,matched_charge_id,candidate_type,fingerprint,algorithm_version,
                    match_reason,status,created_at)
                VALUES (?,?,?,'EXACT',SHA2('cmd',256),'v1','direct fixture','OPEN',UTC_TIMESTAMP(6))
                """, org, low, high);
    }

    private long insertCharge(String amount) {
        return insertCharge(orgId, rawRecordId, amount);
    }

    private long insertCharge(long org, long raw, String amount) {
        var nextIndex = jdbc.queryForObject(
                "SELECT COALESCE(MAX(fact_index),-1)+1 FROM charge_fact WHERE raw_record_id=?",
                Integer.class, raw);
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,created_at)
                VALUES (?,?,?,'GLM','USAGE',?,'CNY',?,?,UTC_TIMESTAMP(6))
                """, org, raw, nextIndex, amount, JAN_1, FEB_1);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM charge_fact WHERE org_id=? AND raw_record_id=?",
                Long.class, org, raw);
    }

    private long insertConfirmedRawRecord(long org, long memberId, long account, String suffix) {
        var sha256 = (suffix.replace("-", "") + "0123456789abcdef").repeat(4).substring(0, 64);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, sha256, "org/" + org + "/evidence/" + sha256, "usage.csv", "text/csv", 1L,
                memberId);
        var evidenceId = jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, org, sha256);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'CONFIRMED',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, evidenceId, account, "GLM", "FILE_EXPORT", "test-parser-v1", memberId);
        var batchId = jdbc.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?",
                Long.class, evidenceId);
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'SUCCEEDED','INITIAL',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        var attemptId = jdbc.queryForObject(
                "SELECT id FROM import_attempt WHERE import_batch_id=?", Long.class, batchId);
        jdbc.update("UPDATE import_batch SET confirmed_attempt_id=? WHERE id=?", attemptId, batchId);
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,?,NULL,JSON_OBJECT(),NULL,?,?,'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId, "cmd:" + suffix, JAN_1, FEB_1);
        return jdbc.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attemptId);
    }

    private void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code='DUP_WORKER'
                """);
        jdbc.update("DELETE FROM `role` WHERE code='DUP_WORKER'");
    }

    private void createPermissionRole(String roleCode, List<String> permissions) {
        jdbc.update("INSERT INTO `role`(code,name) VALUES (?,?)", roleCode, roleCode);
        for (var permission : permissions) {
            jdbc.update("""
                    INSERT INTO role_permission(role_id,permission_id)
                    SELECT r.id,p.id FROM `role` r JOIN permission p
                    WHERE r.code=? AND p.code=?
                    """, roleCode, permission);
        }
    }

    private void assign(String roleCode, String scopeType, long scopeId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, actorMemberId, scopeType, scopeId, roleCode);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private long insertProviderAccount(long org, String providerCode) {
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,'Command Account',NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, providerCode);
        return jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code=?",
                Long.class, org, providerCode);
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, "Command Reviewer");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?",
                Long.class, email);
    }

    private long insertMember(long org, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, org, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?",
                Long.class, org, userId);
    }
}
