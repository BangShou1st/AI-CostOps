package com.aicostops.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.evidence.domain.Evidence;
import com.aicostops.evidence.domain.EvidenceStorageStatus;
import com.aicostops.evidence.infrastructure.EvidenceStorageProperties;
import com.aicostops.shared.web.DomainException;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MinioContainerSupport;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest
@Tag("integration")
class EvidenceStorageServiceIntegrationTest extends MinioContainerSupport {

    private static final String SHA_256 = "a".repeat(64);
    private static final String OBJECT_KEY = "org/1/evidence/sha256/aa/" + SHA_256;
    private static final byte[] CONTENT = "same provider bytes".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EvidencePersistenceService persistence;
    @Autowired
    private ObjectStoragePort realStorage;
    @Autowired
    private EvidenceStorageProperties properties;
    @Autowired
    private Clock clock;

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

    // ------------------------------------------------------------------
    // Task 2: Evidence persistence identity
    // ------------------------------------------------------------------

    @Test
    void sameOrganizationAndSha256ReusesTheSameEvidenceIdentity() {
        var first = persistence.reserveOrReuse(
                organizationId, SHA_256, OBJECT_KEY, "invoice.csv", "text/csv", 128L, uploaderMemberId, Instant.now());
        var second = persistence.reserveOrReuse(
                organizationId, SHA_256, OBJECT_KEY, "invoice.csv", "text/csv", 128L, uploaderMemberId, Instant.now());

        assertThat(second.evidence().id()).isEqualTo(first.evidence().id());
        assertThat(evidenceCount()).isEqualTo(1);
    }

    @Test
    void sameSha256InDifferentOrganizationsKeepsTenantIsolation() {
        var local = persistence.reserveOrReuse(
                organizationId, SHA_256, OBJECT_KEY, "invoice.csv", "text/csv", 128L, uploaderMemberId, Instant.now());
        var foreign = persistence.reserveOrReuse(
                foreignOrganizationId, SHA_256, OBJECT_KEY, "invoice.csv", "text/csv", 128L, foreignUploaderMemberId, Instant.now());

        assertThat(foreign.evidence().id()).isNotEqualTo(local.evidence().id());
        assertThat(foreign.evidence().organizationId()).isEqualTo(foreignOrganizationId);
        assertThat(evidenceCount()).isEqualTo(2);
    }

    @Test
    void availableEvidenceCanNeverBeDowngradedByALateStorageFailure() {
        var evidence = persistence.reserveOrReuse(
                organizationId, SHA_256, OBJECT_KEY, "invoice.csv", "text/csv", 128L, uploaderMemberId, Instant.now());

        persistence.markAvailable(evidence.evidence().id(), organizationId, Instant.now());
        persistence.markFailedUnlessAvailable(evidence.evidence().id(), organizationId, "STORAGE_UPLOAD_FAILED", Instant.now());

        assertThat(findEvidence(evidence.evidence().id()).storageStatus()).isEqualTo(EvidenceStorageStatus.AVAILABLE);
        assertThat(findEvidence(evidence.evidence().id()).storageErrorCode()).isNull();
    }

    @Test
    void failedEvidenceRemainsFailedUntilASuccessfulRepair() {
        var evidence = persistence.reserveOrReuse(
                organizationId, SHA_256, OBJECT_KEY, "invoice.csv", "text/csv", 128L, uploaderMemberId, Instant.now());

        persistence.markFailedUnlessAvailable(evidence.evidence().id(), organizationId, "STORAGE_UPLOAD_FAILED", Instant.now());
        assertThat(findEvidence(evidence.evidence().id()).storageStatus()).isEqualTo(EvidenceStorageStatus.FAILED);

        persistence.markAvailable(evidence.evidence().id(), organizationId, Instant.now());
        var repaired = findEvidence(evidence.evidence().id());
        assertThat(repaired.storageStatus()).isEqualTo(EvidenceStorageStatus.AVAILABLE);
        assertThat(repaired.storageErrorCode()).isNull();
    }

    @Test
    void evidenceLookupIsScopedToOrganization() {
        var local = persistence.reserveOrReuse(
                organizationId, SHA_256, OBJECT_KEY, "invoice.csv", "text/csv", 128L, uploaderMemberId, Instant.now());

        assertThat(persistence.findByIdAndOrganization(local.evidence().id(), organizationId)).isPresent();
        assertThat(persistence.findByIdAndOrganization(local.evidence().id(), foreignOrganizationId)).isEmpty();
    }

    // ------------------------------------------------------------------
    // Task 4: streaming upload, dedup and storage recovery
    // ------------------------------------------------------------------

    @Test
    void sameBytesInSameOrganizationReuseEvidenceAndSkipObjectRewrite() {
        var recording = new RecordingObjectStoragePort(realStorage);
        var service = new EvidenceStorageService(persistence, recording, properties, clock);

        var first = service.store(organizationId, uploaderMemberId, "invoice.csv", "text/csv",
                new ByteArrayInputStream(CONTENT));
        var second = service.store(organizationId, uploaderMemberId, "invoice.csv", "text/csv",
                new ByteArrayInputStream(CONTENT));

        assertThat(second.evidence().id()).isEqualTo(first.evidence().id());
        assertThat(second.evidence().storageStatus()).isEqualTo(EvidenceStorageStatus.AVAILABLE);
        assertThat(second.duplicate()).isTrue();
        assertThat(first.duplicate()).isFalse();
        assertThat(recording.putKeys()).containsExactly(EvidenceStorageService.objectKey(organizationId, sha256Of(CONTENT)));
        assertThat(evidenceCount()).isEqualTo(1);
    }

    @Test
    void sameBytesInDifferentOrganizationsUseSeparateObjectNamespaces() {
        var recording = new RecordingObjectStoragePort(realStorage);
        var service = new EvidenceStorageService(persistence, recording, properties, clock);

        var local = service.store(organizationId, uploaderMemberId, "invoice.csv", "text/csv",
                new ByteArrayInputStream(CONTENT));
        var foreign = service.store(foreignOrganizationId, foreignUploaderMemberId, "invoice.csv", "text/csv",
                new ByteArrayInputStream(CONTENT));

        assertThat(foreign.evidence().id()).isNotEqualTo(local.evidence().id());
        assertThat(recording.putKeys()).containsExactlyInAnyOrder(
                EvidenceStorageService.objectKey(organizationId, sha256Of(CONTENT)),
                EvidenceStorageService.objectKey(foreignOrganizationId, sha256Of(CONTENT)));
    }

    @Test
    void objectStoragePutNeverRunsInsideADatabaseTransaction() {
        var recording = new RecordingObjectStoragePort(realStorage);
        var service = new EvidenceStorageService(persistence, recording, properties, clock);

        service.store(organizationId, uploaderMemberId, "invoice.csv", "text/csv",
                new ByteArrayInputStream(CONTENT));

        assertThat(recording.putTransactionStates()).containsOnly(false);
    }

    @Test
    void stagingEvidenceWithExistingMatchingObjectRepairsToAvailableWithoutRewriting() throws Exception {
        var sha256 = sha256Of(CONTENT);
        var key = EvidenceStorageService.objectKey(organizationId, sha256);
        var stagedObject = Files.createTempFile("repair-", ".bin");
        Files.write(stagedObject, CONTENT);
        try {
            realStorage.put(key, stagedObject, CONTENT.length, sha256);
        } finally {
            Files.deleteIfExists(stagedObject);
        }
        var reserved = persistence.reserveOrReuse(
                organizationId, sha256, key, "invoice.csv", "text/csv", CONTENT.length, uploaderMemberId, Instant.now());
        assertThat(reserved.evidence().storageStatus()).isEqualTo(EvidenceStorageStatus.STAGING);

        var recording = new RecordingObjectStoragePort(realStorage);
        var service = new EvidenceStorageService(persistence, recording, properties, clock);
        var repaired = service.store(organizationId, uploaderMemberId, "invoice.csv", "text/csv",
                new ByteArrayInputStream(CONTENT));

        assertThat(repaired.evidence().storageStatus()).isEqualTo(EvidenceStorageStatus.AVAILABLE);
        assertThat(repaired.duplicate()).isTrue();
        assertThat(recording.putKeys()).isEmpty();
    }

    @Test
    void failedEvidenceRepairReportsReusedIdentity() {
        var first = new EvidenceStorageService(persistence, realStorage, properties, clock)
                .store(organizationId, uploaderMemberId, "invoice.csv", "text/csv",
                        new ByteArrayInputStream(CONTENT));
        persistence.markFailedUnlessAvailable(first.evidence().id(), organizationId,
                "STORAGE_UPLOAD_FAILED", Instant.now());

        var recording = new RecordingObjectStoragePort(realStorage);
        var service = new EvidenceStorageService(persistence, recording, properties, clock);
        var repaired = service.store(organizationId, uploaderMemberId, "invoice.csv", "text/csv",
                new ByteArrayInputStream(CONTENT));

        assertThat(repaired.evidence().id()).isEqualTo(first.evidence().id());
        assertThat(repaired.evidence().storageStatus()).isEqualTo(EvidenceStorageStatus.AVAILABLE);
        assertThat(repaired.duplicate()).isTrue();
        assertThat(recording.putKeys()).isEmpty();
    }

    @Test
    void mismatchedObjectMetadataAtDeterministicKeyConflictsAndNeverOverwritesBytes() throws Exception {
        var correctSha = sha256Of(CONTENT);
        var key = EvidenceStorageService.objectKey(organizationId, correctSha);
        var tamperedBytes = "tampered content".getBytes(StandardCharsets.UTF_8);
        var tamperedSha = sha256Of(tamperedBytes);
        var tamperedObject = Files.createTempFile("tampered-", ".bin");
        Files.write(tamperedObject, tamperedBytes);
        try {
            realStorage.put(key, tamperedObject, tamperedBytes.length, tamperedSha);
        } finally {
            Files.deleteIfExists(tamperedObject);
        }

        var recording = new RecordingObjectStoragePort(realStorage);
        var service = new EvidenceStorageService(persistence, recording, properties, clock);
        assertThatThrownBy(() -> service.store(organizationId, uploaderMemberId, "invoice.csv", "text/csv",
                new ByteArrayInputStream(CONTENT)))
                .isInstanceOf(DomainException.class)
                .satisfies(exception -> {
                    var domainException = (DomainException) exception;
                    assertThat(domainException.status().value()).isEqualTo(409);
                    assertThat(domainException.code().name()).isEqualTo("STATE_CONFLICT");
                });

        var stat = realStorage.stat(key).orElseThrow();
        assertThat(stat.sha256()).isEqualTo(tamperedSha);
        assertThat(stat.sizeBytes()).isEqualTo(tamperedBytes.length);
        assertThat(recording.putKeys()).isEmpty();
    }

    @Test
    void storageFailureMarksEvidenceFailedButNeverDowngradesAvailable() {
        var failingStorage = new FailingObjectStoragePort();
        var service = new EvidenceStorageService(persistence, failingStorage, properties, clock);

        assertThatThrownBy(() -> service.store(organizationId, uploaderMemberId, "invoice.csv", "text/csv",
                new ByteArrayInputStream(CONTENT)))
                .isInstanceOf(RuntimeException.class);

        var evidence = persistence.findByOrganizationAndSha(organizationId, sha256Of(CONTENT)).orElseThrow();
        assertThat(evidence.storageStatus()).isEqualTo(EvidenceStorageStatus.FAILED);
        assertThat(evidence.storageErrorCode()).isEqualTo("STORAGE_UPLOAD_FAILED");
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

    private static String sha256Of(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class RecordingObjectStoragePort implements ObjectStoragePort {

        private final ObjectStoragePort delegate;
        private final List<String> putKeys = new ArrayList<>();
        private final List<Boolean> putTransactionStates = new ArrayList<>();

        private RecordingObjectStoragePort(ObjectStoragePort delegate) {
            this.delegate = delegate;
        }

        @Override
        public void put(String objectKey, Path file, long sizeBytes, String sha256) {
            putKeys.add(objectKey);
            putTransactionStates.add(TransactionSynchronizationManager.isActualTransactionActive());
            delegate.put(objectKey, file, sizeBytes, sha256);
        }

        @Override
        public Optional<StoredObjectMetadata> stat(String objectKey) {
            return delegate.stat(objectKey);
        }

        @Override
        public InputStream open(String objectKey) {
            return delegate.open(objectKey);
        }

        private List<String> putKeys() {
            return putKeys;
        }

        private List<Boolean> putTransactionStates() {
            return putTransactionStates;
        }
    }

    private static final class FailingObjectStoragePort implements ObjectStoragePort {

        @Override
        public void put(String objectKey, Path file, long sizeBytes, String sha256) {
            throw new IllegalStateException("simulated object storage outage");
        }

        @Override
        public Optional<StoredObjectMetadata> stat(String objectKey) {
            return Optional.empty();
        }

        @Override
        public InputStream open(String objectKey) {
            throw new IllegalStateException("simulated object storage outage");
        }
    }
}
