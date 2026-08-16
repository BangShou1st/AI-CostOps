package com.aicostops.cost.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.cost.review.application.DuplicateCandidateRepository;
import com.aicostops.cost.review.application.DuplicateReviewReadModels.CandidateDraft;
import com.aicostops.cost.review.domain.CandidateStatus;
import com.aicostops.cost.review.domain.CandidateType;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence contract of {@code duplicate_candidate}: append-only inserts
 * where an existing (org, pair, algorithm) row is a no-op via a plain INSERT
 * plus DuplicateKeyException mapping, DB-enforced pair ordering, org-scoped
 * reads, and org-isolated paging.
 */
@SpringBootTest
@Tag("integration")
class DuplicateCandidatePersistenceIntegrationTest extends MySqlContainerSupport {

    @Autowired
    private DuplicateCandidateRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private long fixtureCounter;

    private long orgA;
    private long orgB;
    private long chargeA1;
    private long chargeA2;
    private long chargeA3;
    private long chargeB1;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        var a = insertConfirmedCharge("persist-a");
        orgA = a.orgId();
        chargeA1 = a.chargeId();
        chargeA2 = insertCharge(a, 1, "20.00000000");
        chargeA3 = insertCharge(a, 2, "30.00000000");
        var b = insertConfirmedCharge("persist-b");
        orgB = b.orgId();
        chargeB1 = b.chargeId();
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void insertsAndReadsBackCandidate() {
        var now = Instant.parse("2026-08-16T00:00:00Z");
        var draft = draft(chargeA1, chargeA2, CandidateType.EXACT, "v1");

        var inserted = repository.insertIgnoringDuplicate(draft, now);
        assertThat(inserted).isEqualTo(1);

        var candidate = repository.findCandidateForUpdate(orgA, candidateId(chargeA1, chargeA2, "v1"))
                .orElseThrow();
        assertThat(candidate.organizationId()).isEqualTo(orgA);
        assertThat(candidate.chargeFactId()).isEqualTo(chargeA1);
        assertThat(candidate.matchedChargeId()).isEqualTo(chargeA2);
        assertThat(candidate.candidateType()).isEqualTo(CandidateType.EXACT);
        assertThat(candidate.algorithmVersion()).isEqualTo("v1");
        assertThat(candidate.fingerprint()).hasSize(64);
        assertThat(candidate.status()).isEqualTo(CandidateStatus.OPEN);
        assertThat(candidate.createdAt()).isEqualTo(now);
        assertThat(candidate.resolvedAt()).isNull();
    }

    @Test
    void samePairAndAlgorithmInsertIsIgnored() {
        var draft = draft(chargeA1, chargeA2, CandidateType.EXACT, "v1");
        assertThat(repository.insertIgnoringDuplicate(draft, Instant.now())).isEqualTo(1);

        // The second insert of the same (org, pair, algorithm) is a no-op, even
        // when other descriptive columns differ: pair identity wins.
        var differing = new CandidateDraft(orgA, chargeA1, chargeA2, CandidateType.OVERLAP,
                "another fingerprint", "v1", "another reason");
        assertThat(repository.insertIgnoringDuplicate(differing, Instant.now())).isEqualTo(0);

        var rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM duplicate_candidate WHERE org_id=? AND charge_fact_id=? AND matched_charge_id=?",
                Integer.class, orgA, chargeA1, chargeA2);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void samePairWithDifferentAlgorithmMayCoexist() {
        assertThat(repository.insertIgnoringDuplicate(draft(chargeA1, chargeA2, CandidateType.EXACT, "v1"),
                Instant.now())).isEqualTo(1);
        assertThat(repository.insertIgnoringDuplicate(draft(chargeA1, chargeA2, CandidateType.OVERLAP, "v2"),
                Instant.now())).isEqualTo(1);

        var rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM duplicate_candidate WHERE org_id=? AND charge_fact_id=? AND matched_charge_id=?",
                Integer.class, orgA, chargeA1, chargeA2);
        assertThat(rows).isEqualTo(2);
    }

    @Test
    void rejectsCandidateWhenChargeBelongsToAnotherOrganization() {
        // candidate claims org B but its charge endpoint lives in org A.
        var crossOrg = new CandidateDraft(orgB, chargeA1, chargeA2, CandidateType.EXACT,
                "f".repeat(64), "v1", "reason");

        assertThatThrownBy(() -> repository.insertIgnoringDuplicate(crossOrg, Instant.now()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_duplicate_candidate_charge_org");
    }

    @Test
    void enforcesOrderedPairAtDatabaseLevel() {
        var reversed = new CandidateDraft(orgA, chargeA2, chargeA1, CandidateType.EXACT,
                "f".repeat(64), "v1", "reason");

        // MySQL reports CHECK violations with SQLState HY000, which mybatis-spring
        // surfaces as UncategorizedSQLException rather than a typed integrity error.
        assertThatThrownBy(() -> repository.insertIgnoringDuplicate(reversed, Instant.now()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("chk_duplicate_candidate_order");
    }

    @Test
    void readsStayOrganizationScoped() {
        repository.insertIgnoringDuplicate(draft(chargeA1, chargeA2, CandidateType.EXACT, "v1"),
                Instant.now());
        var foreignId = candidateId(chargeA1, chargeA2, "v1");

        assertThat(repository.findCandidateForUpdate(orgB, foreignId)).isEmpty();

        var foreignPage = repository.page(orgB, 0, 10, null, null);
        assertThat(foreignPage.totalElements()).isZero();
        assertThat(foreignPage.items()).isEmpty();

        var ownPage = repository.page(orgA, 0, 10, null, null);
        assertThat(ownPage.totalElements()).isEqualTo(1);
        var summary = ownPage.items().getFirst();
        assertThat(summary.candidate().id()).isEqualTo(foreignId);
        assertThat(summary.candidate().status()).isEqualTo(CandidateStatus.OPEN);
        assertThat(summary.chargeFact().id()).isEqualTo(chargeA1);
        assertThat(summary.matchedChargeFact().id()).isEqualTo(chargeA2);
        assertThat(summary.chargeFact().amount()).isEqualByComparingTo("10.00000000");
        assertThat(summary.matchedChargeFact().amount()).isEqualByComparingTo("20.00000000");
    }

    @Test
    void pageFiltersByStatusAndCandidateType() {
        repository.insertIgnoringDuplicate(draft(chargeA1, chargeA2, CandidateType.EXACT, "v1"),
                Instant.now());
        repository.insertIgnoringDuplicate(draft(chargeA2, chargeA3, CandidateType.OVERLAP, "v1"),
                Instant.now());

        assertThat(repository.page(orgA, 0, 10, CandidateStatus.OPEN, null).totalElements()).isEqualTo(2);
        assertThat(repository.page(orgA, 0, 10, CandidateStatus.OPEN, CandidateType.EXACT)
                .totalElements()).isEqualTo(1);
        assertThat(repository.page(orgA, 0, 10, CandidateStatus.KEPT_CLEAN, null).totalElements()).isZero();
        assertThat(repository.page(orgA, 0, 1, null, null).items()).hasSize(1);
        assertThat(repository.page(orgA, 0, 1, null, null).totalPages()).isEqualTo(2);
    }

    private CandidateDraft draft(long lowId, long highId, CandidateType type, String algorithmVersion) {
        return new CandidateDraft(orgA, lowId, highId, type,
                (type.name() + algorithmVersion + "fingerprint").repeat(8).substring(0, 64),
                algorithmVersion, "deterministic evidence");
    }

    private long candidateId(long lowId, long highId, String algorithmVersion) {
        return jdbc.queryForObject("""
                SELECT id FROM duplicate_candidate
                WHERE org_id=? AND charge_fact_id=? AND matched_charge_id=? AND algorithm_version=?
                """, Long.class, orgA, lowId, highId, algorithmVersion);
    }

    // -- fixtures ----------------------------------------------------------------

    private record OrgFixture(long orgId, long memberId, long rawRecordId, long chargeId) {
    }

    private OrgFixture insertConfirmedCharge(String label) {
        var suffix = label + "-" + ++fixtureCounter + "-" + System.nanoTime();
        var slug = ("dp-" + suffix).substring(0, Math.min(63, "dp-".length() + suffix.length()));
        var orgId = insertOrganization("Dup Persistence " + label, slug);
        var userId = insertUser(suffix + "@example.com");
        var memberId = insertMember(orgId, userId);
        var rawRecordId = insertRawRecordUnderConfirmedBatch(orgId, memberId, suffix);
        var fixture = new OrgFixture(orgId, memberId, rawRecordId, 0);
        var chargeId = insertCharge(fixture, 0, "10.00000000");
        return new OrgFixture(orgId, memberId, rawRecordId, chargeId);
    }

    private long insertCharge(OrgFixture fixture, int factIndex, String amount) {
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,created_at)
                VALUES (?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, fixture.orgId(), fixture.rawRecordId(), factIndex, "GLM", "USAGE", amount, "CNY");
        return jdbc.queryForObject(
                "SELECT id FROM charge_fact WHERE raw_record_id=? AND fact_index=?",
                Long.class, fixture.rawRecordId(), factIndex);
    }

    private long insertRawRecordUnderConfirmedBatch(long orgId, long memberId, String suffix) {
        var sha256 = (suffix.replace("-", "") + "0123456789abcdef").repeat(4).substring(0, 64);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, sha256, "org/" + orgId + "/evidence/" + sha256, "usage.csv", "text/csv", 1L, memberId);
        var evidenceId = jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, orgId, sha256);
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,'Dup Persistence Account',NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "GLM");
        var accountId = jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code='GLM'", Long.class, orgId);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'CONFIRMED',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, evidenceId, accountId, "GLM", "FILE_EXPORT", "test-parser-v1", memberId);
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
                VALUES (?,0,?,NULL,JSON_OBJECT(),NULL,'2026-01-01 00:00:00','2026-01-02 00:00:00',
                    'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId, "dp:" + suffix);
        return jdbc.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attemptId);
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
                VALUES (?,?,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, "Dup Persistence User");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }

    private long insertMember(long orgId, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId, userId);
    }
}
