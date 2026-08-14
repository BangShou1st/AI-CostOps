package com.aicostops.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.evidence.domain.Evidence;
import com.aicostops.evidence.domain.EvidenceStorageStatus;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class EvidenceStorageServiceIntegrationTest extends MySqlContainerSupport {

    private static final String SHA_256 = "a".repeat(64);
    private static final String OBJECT_KEY = "org/1/evidence/sha256/aa/" + SHA_256;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EvidencePersistenceService persistence;

    private long organizationId;
    private long foreignOrganizationId;
    private long uploaderMemberId;
    private long foreignUploaderMemberId;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        organizationId = insertOrganization("Evidence Persistence", "evidence-persistence");
        foreignOrganizationId = insertOrganization("Foreign", "evidence-persistence-foreign");
        var uploaderUserId = insertUser("evidence-uploader@example.com");
        var foreignUserId = insertUser("evidence-uploader-foreign@example.com");
        uploaderMemberId = insertMember(organizationId, uploaderUserId);
        foreignUploaderMemberId = insertMember(foreignOrganizationId, foreignUserId);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @Test
    void sameOrganizationAndSha256ReusesTheSameEvidenceIdentity() {
        var first = persistence.reserveOrReuse(
                organizationId, SHA_256, OBJECT_KEY, "invoice.csv", "text/csv", 128L, uploaderMemberId, Instant.now());
        var second = persistence.reserveOrReuse(
                organizationId, SHA_256, OBJECT_KEY, "invoice.csv", "text/csv", 128L, uploaderMemberId, Instant.now());

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(evidenceCount()).isEqualTo(1);
    }

    @Test
    void sameSha256InDifferentOrganizationsKeepsTenantIsolation() {
        var local = persistence.reserveOrReuse(
                organizationId, SHA_256, OBJECT_KEY, "invoice.csv", "text/csv", 128L, uploaderMemberId, Instant.now());
        var foreign = persistence.reserveOrReuse(
                foreignOrganizationId, SHA_256, OBJECT_KEY, "invoice.csv", "text/csv", 128L, foreignUploaderMemberId, Instant.now());

        assertThat(foreign.id()).isNotEqualTo(local.id());
        assertThat(foreign.organizationId()).isEqualTo(foreignOrganizationId);
        assertThat(evidenceCount()).isEqualTo(2);
    }

    @Test
    void availableEvidenceCanNeverBeDowngradedByALateStorageFailure() {
        var evidence = persistence.reserveOrReuse(
                organizationId, SHA_256, OBJECT_KEY, "invoice.csv", "text/csv", 128L, uploaderMemberId, Instant.now());

        persistence.markAvailable(evidence.id(), organizationId, Instant.now());
        persistence.markFailedUnlessAvailable(evidence.id(), organizationId, "STORAGE_UPLOAD_FAILED", Instant.now());

        assertThat(findEvidence(evidence.id()).storageStatus()).isEqualTo(EvidenceStorageStatus.AVAILABLE);
        assertThat(findEvidence(evidence.id()).storageErrorCode()).isNull();
    }

    @Test
    void failedEvidenceRemainsFailedUntilASuccessfulRepair() {
        var evidence = persistence.reserveOrReuse(
                organizationId, SHA_256, OBJECT_KEY, "invoice.csv", "text/csv", 128L, uploaderMemberId, Instant.now());

        persistence.markFailedUnlessAvailable(evidence.id(), organizationId, "STORAGE_UPLOAD_FAILED", Instant.now());
        assertThat(findEvidence(evidence.id()).storageStatus()).isEqualTo(EvidenceStorageStatus.FAILED);

        persistence.markAvailable(evidence.id(), organizationId, Instant.now());
        var repaired = findEvidence(evidence.id());
        assertThat(repaired.storageStatus()).isEqualTo(EvidenceStorageStatus.AVAILABLE);
        assertThat(repaired.storageErrorCode()).isNull();
    }

    @Test
    void evidenceLookupIsScopedToOrganization() {
        var local = persistence.reserveOrReuse(
                organizationId, SHA_256, OBJECT_KEY, "invoice.csv", "text/csv", 128L, uploaderMemberId, Instant.now());

        assertThat(persistence.findByIdAndOrganization(local.id(), organizationId)).isPresent();
        assertThat(persistence.findByIdAndOrganization(local.id(), foreignOrganizationId)).isEmpty();
    }

    private Evidence findEvidence(long id) {
        return persistence.findByIdAndOrganization(id, organizationId).orElseThrow();
    }

    private int evidenceCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM evidence", Integer.class);
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
                VALUES (?,'Evidence Uploader','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email);
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
