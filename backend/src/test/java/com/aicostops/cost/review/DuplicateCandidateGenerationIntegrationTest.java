package com.aicostops.cost.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.cost.review.application.DuplicateCandidateRepository;
import com.aicostops.cost.review.application.DuplicateReviewCommandService;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.DuplicateScanSummary;
import com.aicostops.cost.review.infrastructure.MyBatisDuplicateCandidateRepository;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Candidate generation rules of the org-level scan: evidence grouping, EXACT vs
 * half-open OVERLAP, accepted-attempt lineage eligibility, cross-org isolation,
 * and terminal-pair / aggregate reconciliation semantics on rescan.
 */
@SpringBootTest
@Tag("integration")
class DuplicateCandidateGenerationIntegrationTest extends AuthenticationContainersSupport {

    private static final String JAN_1 = "2026-01-01 00:00:00.000000";
    private static final String JAN_2 = "2026-01-02 00:00:00.000000";
    private static final String FEB_1 = "2026-02-01 00:00:00.000000";
    private static final String MAR_1 = "2026-03-01 00:00:00.000000";

    /** Test-only deadlock injection: off unless a test turns it on. */
    private static final AtomicBoolean INJECT_DEADLOCK = new AtomicBoolean(false);
    private static final AtomicInteger INSERT_CALLS = new AtomicInteger();

    @TestConfiguration
    static class DeadlockInjectionConfiguration {
        @Bean
        @Primary
        DuplicateCandidateRepository deadlockInjectingRepository(MyBatisDuplicateCandidateRepository real) {
            return (DuplicateCandidateRepository) Proxy.newProxyInstance(
                    DuplicateCandidateGenerationIntegrationTest.class.getClassLoader(),
                    new Class<?>[] {DuplicateCandidateRepository.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("insertIgnoringDuplicate")
                                && INJECT_DEADLOCK.get()
                                && INSERT_CALLS.incrementAndGet() == 2) {
                            // Simulates MySQL choosing this transaction as the
                            // deadlock loser after one insert already succeeded.
                            throw new DeadlockLoserDataAccessException("test-injected deadlock", null);
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException invocation) {
                            throw invocation.getCause();
                        }
                    });
        }
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private DuplicateReviewCommandService commands;

    private long fixtureCounter;

    private long orgA;
    private long actorUserId;
    private long actorMemberId;
    private long accountId;

    @BeforeEach
    void setUp() {
        INJECT_DEADLOCK.set(false);
        INSERT_CALLS.set(0);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        var suffix = ++fixtureCounter + "-" + System.nanoTime();
        orgA = insertOrganization("Scan Org", "scan-" + suffix);
        actorUserId = insertUser("scanner-" + suffix + "@example.com", 7);
        actorMemberId = insertMember(orgA, actorUserId);
        createPermissionRole("DUP_REVIEWER", List.of("DUPLICATE_REVIEW"));
        assign("DUP_REVIEWER", "ORG", orgA);
        accountId = insertProviderAccount(orgA, "GLM");
    }

    @AfterEach
    void tearDown() {
        INJECT_DEADLOCK.set(false);
        INSERT_CALLS.set(0);
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void exactPairCreatesOneExactOpenCandidateAndMarksBothSuspected() {
        var raw = insertConfirmedRawRecord(orgA, actorMemberId, suffix("exact"));
        insertCharge(raw, "10.00000000", JAN_1, FEB_1);
        insertCharge(raw, "10.00000000", JAN_1, FEB_1);

        var summary = commands.scan(user());

        assertThat(summary.chargesScanned()).isEqualTo(2);
        assertThat(summary.candidatesCreated()).isEqualTo(1);
        assertThat(summary.candidatePairsEvaluated()).isEqualTo(1);
        assertThat(candidateCount("candidate_type='EXACT' AND status='OPEN'")).isEqualTo(1);
        assertThat(reviewStatusOfFirstCharge()).isEqualTo("SUSPECTED_DUPLICATE");
        assertThat(reviewStatusOfSecondCharge()).isEqualTo("SUSPECTED_DUPLICATE");
    }

    @Test
    void overlappingHalfOpenWindowsCreateOverlapCandidate() {
        var raw = insertConfirmedRawRecord(orgA, actorMemberId, suffix("overlap"));
        insertCharge(raw, "10.00000000", JAN_1, MAR_1);
        insertCharge(raw, "99.00000000", FEB_1, MAR_1);

        var summary = commands.scan(user());

        assertThat(summary.candidatesCreated()).isEqualTo(1);
        assertThat(candidateCount("candidate_type='OVERLAP' AND status='OPEN'")).isEqualTo(1);
        assertThat(candidateCount("candidate_type='EXACT'")).isZero();
    }

    @Test
    void adjacentWindowsCreateNoCandidate() {
        var raw = insertConfirmedRawRecord(orgA, actorMemberId, suffix("adjacent"));
        insertCharge(raw, "10.00000000", JAN_1, FEB_1);
        insertCharge(raw, "10.00000000", FEB_1, MAR_1);

        var summary = commands.scan(user());

        assertThat(summary.candidatesCreated()).isZero();
        assertThat(candidateCount(null)).isZero();
        assertThat(reviewStatusOfFirstCharge()).isEqualTo("CLEAN");
        assertThat(reviewStatusOfSecondCharge()).isEqualTo("CLEAN");
    }

    @Test
    void nullWindowsCreateNoCandidate() {
        var raw = insertConfirmedRawRecord(orgA, actorMemberId, suffix("null-window"));
        insertCharge(raw, "10.00000000", null, null);
        insertCharge(raw, "10.00000000", null, null);

        var summary = commands.scan(user());

        assertThat(summary.candidatesCreated()).isZero();
        assertThat(candidateCount(null)).isZero();
    }

    @Test
    void negativeAndZeroEqualAmountsFormExactCandidates() {
        var rawNegative = insertConfirmedRawRecord(orgA, actorMemberId, suffix("negative"));
        insertCharge(rawNegative, "-5.00000000", JAN_1, FEB_1, "CNY");
        insertCharge(rawNegative, "-5.00000000", JAN_1, FEB_1, "CNY");
        var rawZero = insertConfirmedRawRecord(orgA, actorMemberId, suffix("zero"));
        insertCharge(rawZero, "0.00000000", JAN_1, FEB_1, "USD");
        insertCharge(rawZero, "0.00000000", JAN_1, FEB_1, "USD");

        var summary = commands.scan(user());

        assertThat(summary.candidatesCreated()).isEqualTo(2);
        assertThat(candidateCount("candidate_type='EXACT' AND status='OPEN'")).isEqualTo(2);
    }

    @Test
    void chargesOfNonConfirmedBatchesAndAttemptsNeverParticipate() {
        // FAILED batch with a FAILED attempt: excluded by batch status.
        var failedRaw = insertRawRecordUnderBatch(orgA, actorMemberId, suffix("failed-batch"),
                "FAILED", "FAILED", 1);
        insertCharge(failedRaw, "10.00000000", JAN_1, FEB_1);

        // One CONFIRMED batch with a retry lineage: attempt 1 (historical
        // predecessor) and attempt 2 (the confirmed successor). Equal-evidence
        // charges exist under both attempts, but only the confirmed attempt's
        // charge may participate, so no candidate can be created.
        var lineage = createTwoAttemptConfirmedBatch(suffix("retry-lineage"));
        var predecessorRaw = insertRawRecordUnderAttempt(lineage.predecessorAttemptId(),
                "pre:" + suffix("pre-rec"));
        insertCharge(predecessorRaw, "10.00000000", JAN_1, FEB_1);
        var successorRaw = insertRawRecordUnderAttempt(lineage.confirmedAttemptId(),
                "succ:" + suffix("succ-rec"));
        insertCharge(successorRaw, "10.00000000", JAN_1, FEB_1);

        var summary = commands.scan(user());

        assertThat(summary.chargesScanned()).isEqualTo(1);
        assertThat(summary.candidatesCreated()).isZero();
        assertThat(candidateCount(null)).isZero();
    }

    @Test
    void crossOrgScansStayIsolated() {
        var orgB = insertOrganization("Scan Foreign", "scan-foreign-" + System.nanoTime());
        var foreignUser = insertUser("foreign-scanner@example.com", 0);
        var foreignMember = insertMember(orgB, foreignUser);
        assignForeign("DUP_REVIEWER", "ORG", orgB, foreignMember);

        var rawA = insertConfirmedRawRecord(orgA, actorMemberId, suffix("cross-a"));
        insertCharge(rawA, "10.00000000", JAN_1, FEB_1);
        insertCharge(rawA, "10.00000000", JAN_1, FEB_1);
        var rawB = insertConfirmedRawRecord(orgB, foreignMember, suffix("cross-b"));
        insertCharge(rawB, "10.00000000", JAN_1, FEB_1);
        insertCharge(rawB, "10.00000000", JAN_1, FEB_1);

        var summary = commands.scan(user());

        assertThat(summary.chargesScanned()).isEqualTo(2);
        assertThat(candidateCount(null)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM duplicate_candidate dc
                        WHERE dc.org_id=?
                        """, Integer.class, orgB)).isZero();
    }

    @Test
    void rescanOfOpenPairCreatesNoDuplicateRow() {
        var raw = insertConfirmedRawRecord(orgA, actorMemberId, suffix("rescan"));
        insertCharge(raw, "10.00000000", JAN_1, FEB_1);
        insertCharge(raw, "10.00000000", JAN_1, FEB_1);

        commands.scan(user());
        var second = commands.scan(user());

        assertThat(second.candidatesCreated()).isZero();
        assertThat(second.candidatesAlreadyPresent()).isEqualTo(1);
        assertThat(candidateCount(null)).isEqualTo(1);
    }

    @Test
    void terminalKeptPairRescanLeavesChargeClean() {
        var raw = insertConfirmedRawRecord(orgA, actorMemberId, suffix("terminal"));
        var low = insertCharge(raw, "10.00000000", JAN_1, FEB_1);
        var high = insertCharge(raw, "10.00000000", JAN_1, FEB_1);
        commands.scan(user());
        // A reviewer already kept the pair clean: terminal row, charges restored CLEAN.
        jdbc.update("""
                UPDATE duplicate_candidate
                SET status='KEPT_CLEAN', resolved_at=UTC_TIMESTAMP(6)
                WHERE org_id=? AND charge_fact_id=? AND matched_charge_id=?
                """, orgA, low, high);
        jdbc.update("UPDATE charge_fact SET review_status='CLEAN' WHERE org_id=? AND id IN (?,?)",
                orgA, low, high);

        var summary = commands.scan(user());

        assertThat(summary.candidatesCreated()).isZero();
        assertThat(summary.candidatesAlreadyPresent()).isEqualTo(1);
        assertThat(reviewStatus(low)).isEqualTo("CLEAN");
        assertThat(reviewStatus(high)).isEqualTo("CLEAN");
        assertThat(candidateCount("status='OPEN'")).isZero();
    }

    @Test
    void scanMarksSuspectedOnlyWhenOpenCandidateExistsInDatabase() {
        var raw = insertConfirmedRawRecord(orgA, actorMemberId, suffix("db-open"));
        var low = insertCharge(raw, "10.00000000", JAN_1, FEB_1);
        var high = insertCharge(raw, "10.00000000", JAN_1, FEB_1);
        // Pre-existing OPEN candidate in the DB whose evidence no longer matches:
        // the in-memory generation will not re-create this pair (amount changed),
        // yet the DB OPEN candidate must still drive SUSPECTED for its endpoints.
        jdbc.update("UPDATE charge_fact SET amount='99.00000000' WHERE id=?", high);
        insertCandidateDirectly(orgA, low, high);

        var summary = commands.scan(user());

        assertThat(summary.candidatesCreated()).isZero();
        assertThat(reviewStatus(low)).isEqualTo("SUSPECTED_DUPLICATE");
        assertThat(reviewStatus(high)).isEqualTo("SUSPECTED_DUPLICATE");
    }

    @Test
    void scanSummaryReportsCountsAndTimestamp() {
        var raw = insertConfirmedRawRecord(orgA, actorMemberId, suffix("summary"));
        insertCharge(raw, "10.00000000", JAN_1, FEB_1);
        insertCharge(raw, "10.00000000", JAN_1, FEB_1);
        insertCharge(raw, "30.00000000", JAN_1, FEB_1);

        var summary = commands.scan(user());

        assertThat(summary.chargesScanned()).isEqualTo(3);
        // one same-dims group of three equal charges: all three pairs match
        assertThat(summary.candidatePairsEvaluated()).isEqualTo(3);
        assertThat(summary.candidatesCreated()).isEqualTo(3);
        assertThat(summary.scannedAt()).isNotNull();
    }

    @Test
    void scanCountsOnlyCommittedBatchesAfterDeadlockRetry() {
        var raw = insertConfirmedRawRecord(orgA, actorMemberId, suffix("deadlock"));
        insertCharge(raw, "10.00000000", JAN_1, FEB_1);
        insertCharge(raw, "10.00000000", JAN_1, FEB_1);
        insertCharge(raw, "10.00000000", JAN_1, FEB_1);

        INJECT_DEADLOCK.set(true);
        INSERT_CALLS.set(0);
        DuplicateScanSummary summary;
        try {
            // One batch of three pair drafts; the second insert loses a deadlock
            // after the first insert already succeeded, rolling the whole batch
            // back. The retry re-runs the entire batch from scratch: only the
            // committed retry's three inserts may enter the summary — the rolled
            // back attempt's partial count must never leak into it.
            summary = commands.scan(user());
        } finally {
            INJECT_DEADLOCK.set(false);
        }

        assertThat(summary.candidatesCreated()).isEqualTo(3);
        assertThat(summary.candidatesAlreadyPresent()).isZero();
        assertThat(candidateCount(null)).isEqualTo(3);
    }

    // -- helpers -----------------------------------------------------------------

    // -- helpers -----------------------------------------------------------------

    private AuthenticatedUser user() {
        return new AuthenticatedUser(actorUserId, 7);
    }

    private String suffix(String label) {
        return label + "-" + ++fixtureCounter + "-" + System.nanoTime();
    }

    private int candidateCount(String where) {
        var sql = "SELECT COUNT(*) FROM duplicate_candidate WHERE org_id=" + orgA
                + (where == null ? "" : " AND " + where);
        return jdbc.queryForObject(sql, Integer.class);
    }

    private String reviewStatus(long chargeId) {
        return jdbc.queryForObject("SELECT review_status FROM charge_fact WHERE id=?",
                String.class, chargeId);
    }

    private String reviewStatusOfFirstCharge() {
        return jdbc.queryForObject(
                "SELECT review_status FROM charge_fact WHERE org_id=? ORDER BY id LIMIT 1",
                String.class, orgA);
    }

    private String reviewStatusOfSecondCharge() {
        return jdbc.queryForObject(
                "SELECT review_status FROM charge_fact WHERE org_id=? ORDER BY id LIMIT 1 OFFSET 1",
                String.class, orgA);
    }

    private void insertCandidateDirectly(long orgId, long low, long high) {
        jdbc.update("""
                INSERT INTO duplicate_candidate(
                    org_id,charge_fact_id,matched_charge_id,candidate_type,fingerprint,algorithm_version,
                    match_reason,status,created_at)
                VALUES (?,?,?,'EXACT',SHA2('direct',256),'v1','direct fixture','OPEN',UTC_TIMESTAMP(6))
                """, orgId, low, high);
    }

    private long insertCharge(long rawRecordId, String amount, String periodStart, String periodEnd) {
        return insertCharge(rawRecordId, amount, periodStart, periodEnd, "CNY");
    }

    private long insertCharge(long rawRecordId, String amount, String periodStart, String periodEnd,
            String currency) {
        var nextIndex = jdbc.queryForObject(
                "SELECT COALESCE(MAX(fact_index),-1)+1 FROM charge_fact WHERE raw_record_id=?",
                Integer.class, rawRecordId);
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,created_at)
                VALUES (?,?,?,'GLM','USAGE',?,?,?,?,UTC_TIMESTAMP(6))
                """, orgA, rawRecordId, nextIndex, amount, currency, periodStart, periodEnd);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM charge_fact WHERE org_id=? AND raw_record_id=?",
                Long.class, orgA, rawRecordId);
    }

    /** One raw record under a CONFIRMED batch whose confirmed attempt is its own. */
    private long insertConfirmedRawRecord(long orgId, long memberId, String suffix) {
        return insertRawRecordUnderBatch(orgId, memberId, suffix, "CONFIRMED", "SUCCEEDED", 1);
    }

    private record RetryLineage(long batchId, long predecessorAttemptId, long confirmedAttemptId) {
    }

    /** One CONFIRMED batch whose confirmed attempt is the retry successor (no 2). */
    private RetryLineage createTwoAttemptConfirmedBatch(String suffix) {
        var sha256 = (suffix.replace("-", "") + "0123456789abcdef").repeat(4).substring(0, 64);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgA, sha256, "org/" + orgA + "/evidence/" + sha256, "usage.csv",
                "text/csv", 1L, actorMemberId);
        var evidenceId = jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, orgA, sha256);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'CONFIRMED',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgA, evidenceId, accountId, "GLM", "FILE_EXPORT", "test-parser-v1", actorMemberId);
        var batchId = jdbc.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?",
                Long.class, evidenceId);
        var predecessorAttemptId = insertAttempt(batchId, 1, "SUCCEEDED", null);
        var confirmedAttemptId = insertAttempt(batchId, 2, "SUCCEEDED", predecessorAttemptId);
        jdbc.update("UPDATE import_batch SET confirmed_attempt_id=? WHERE id=?",
                confirmedAttemptId, batchId);
        return new RetryLineage(batchId, predecessorAttemptId, confirmedAttemptId);
    }

    private long insertRawRecordUnderAttempt(long attemptId, String locator) {
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,?,NULL,JSON_OBJECT(),NULL,?,?,'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId, locator, JAN_1, FEB_1);
        return jdbc.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attemptId);
    }

    private long insertAttempt(long batchId, int attemptNo, String status, Long predecessor) {
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,?,?,'INITIAL',?,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId, attemptNo, status, predecessor);
        return jdbc.queryForObject(
                "SELECT id FROM import_attempt WHERE import_batch_id=? AND attempt_no=?",
                Long.class, batchId, attemptNo);
    }

    private long insertRawRecordUnderBatch(long orgId, long memberId, String suffix,
            String batchStatus, String attemptStatus, int attemptNo) {
        var sha256 = (suffix.replace("-", "") + "0123456789abcdef").repeat(4).substring(0, 64);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, sha256, "org/" + orgId + "/evidence/" + sha256, "usage.csv",
                "text/csv", 1L, memberId);
        var evidenceId = jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, orgId, sha256);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, evidenceId, accountId, "GLM", "FILE_EXPORT", "test-parser-v1",
                batchStatus, null, null, memberId);
        var batchId = jdbc.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?",
                Long.class, evidenceId);
        var attemptId = insertAttempt(batchId, attemptNo, attemptStatus, null);
        jdbc.update("UPDATE import_batch SET confirmed_attempt_id=? WHERE id=? AND status='CONFIRMED'",
                attemptId, batchId);
        return insertRawRecordUnderAttempt(attemptId, "gen:" + suffix);
    }

    private Long previousAttemptId(long batchId, int attemptNo) {
        return jdbc.queryForObject(
                "SELECT id FROM import_attempt WHERE import_batch_id=? AND attempt_no=?",
                Long.class, batchId, attemptNo);
    }

    private void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code IN ('DUP_REVIEWER')
                """);
        jdbc.update("DELETE FROM `role` WHERE code IN ('DUP_REVIEWER')");
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

    private void assignForeign(String roleCode, String scopeType, long scopeId, long foreignMemberId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, foreignMemberId, scopeType, scopeId, roleCode);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private long insertProviderAccount(long orgId, String providerCode) {
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,'Scan Account',NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode);
        return jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code=?", Long.class,
                orgId, providerCode);
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email, int securityVersion) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, "Scan User", securityVersion);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?",
                Long.class, email);
    }

    private long insertMember(long orgId, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?",
                Long.class, orgId, userId);
    }
}
