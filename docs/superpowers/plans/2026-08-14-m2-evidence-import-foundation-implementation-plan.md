# M2 Evidence & Import Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver AIC-021 through AIC-024 as one backend foundation that stores immutable provider evidence in S3-compatible storage, creates durable import execution history, claims work safely through MySQL leases, and exposes a streaming ProviderAdapter framework without crossing into M3 canonical cost normalization.

**Architecture:** Keep `evidence` and `ingestion` as separate feature modules. `evidence` owns byte identity, storage lifecycle, checksum, object storage, and authorized download; `ingestion` owns ImportBatch/ImportAttempt, worker leases, raw records/issues, adapter registry, and parse orchestration. MySQL is the concurrency and lifecycle source of truth, MinIO/S3 stores file bytes, and all object/network/file parsing work occurs outside long DB transactions.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Plain MyBatis / mybatis-spring-boot-starter 4.1.0, MySQL 8.4, Flyway, Testcontainers 2.0.5, MinIO Java SDK 9.0.1, Spring TaskExecutor, Spring MVC, Spring Security, JUnit 5, AssertJ, ArchUnit.

## Global Constraints

- PowerShell only for local commands.
- Repository path: `E:\AI-CostOps`.
- Planned implementation branch: `feat/m2-evidence-import-foundation`.
- Do not use JPA, Hibernate, MyBatis-Plus, H2, RabbitMQ, Kafka, or Redis as import-job truth.
- `evidence` must not depend on `ingestion`.
- Provider adapters must not depend on ledger, budget, attribution, reporting, or M3 canonical cost types.
- M2 ends at `ImportBatch.PARSED` or `FAILED`; `READY_FOR_REVIEW`, canonical facts, and final Confirm belong to M3.
- Same organization + same SHA-256 reuses one Evidence identity.
- Same Evidence + Provider Account + source type + parser version reuses one ImportBatch and does not implicitly retry.
- Retry/recovery creates a new ImportAttempt; failed Attempts and their raw rows/issues remain available for lineage.
- Object storage I/O and large-file parsing must not hold a long database transaction.
- Claim/recovery/fencing behavior must be proven against real MySQL 8.4 Testcontainers.
- Raw provider payload persisted to MySQL must redact secret-like values; the Evidence object remains the authoritative original byte source.
- Upload hard limit starts at 512 MiB and is configurable.
- Import raw-record persistence batch size starts at 500 and is configurable.
- Worker lease starts at 60s, heartbeat at 20s, and automatic lease recovery budget at 3.
- Current MinIO Compose baseline must be updated from `RELEASE.2025-09-07T16-13-09Z-cpuv1` to `RELEASE.2025-10-15T17-29-55Z`.

---

## Execution preflight

Run only after the design/plan documentation PR has been merged to `main`.

```powershell
Set-Location E:\AI-CostOps
git fetch origin
git switch main
git pull --ff-only origin main
git status --short
git log -3 --oneline --decorate
git switch -c feat/m2-evidence-import-foundation
git push -u origin feat/m2-evidence-import-foundation
```

Expected:

```text
working tree is clean
main == origin/main
new branch feat/m2-evidence-import-foundation tracks origin/feat/m2-evidence-import-foundation
```

If `git status --short` prints anything, stop implementation and resolve the local changes first.

---

## File map

### Database / configuration

- Create `backend/src/main/resources/db/migration/V4__m2_evidence_import_schema.sql`
- Create `backend/src/main/resources/db/migration/V5__m2_finance_reviewer_provider_account_read.sql`
- Modify `backend/pom.xml`
- Modify `backend/src/main/resources/application.yml`
- Modify `backend/src/test/resources/application-test-defaults.yml`
- Modify `compose.yaml`
- Modify `.env.example`

### Evidence module

- Create `backend/src/main/java/com/aicostops/evidence/domain/Evidence.java`
- Create `backend/src/main/java/com/aicostops/evidence/domain/EvidenceStorageStatus.java`
- Create `backend/src/main/java/com/aicostops/evidence/application/ObjectStoragePort.java`
- Create `backend/src/main/java/com/aicostops/evidence/application/EvidenceContentReader.java`
- Create `backend/src/main/java/com/aicostops/evidence/application/EvidenceUploadStager.java`
- Create `backend/src/main/java/com/aicostops/evidence/application/EvidencePersistenceService.java`
- Create `backend/src/main/java/com/aicostops/evidence/application/EvidenceStorageService.java`
- Create `backend/src/main/java/com/aicostops/evidence/application/EvidenceDownloadService.java`
- Create `backend/src/main/java/com/aicostops/evidence/infrastructure/EvidenceMapper.java`
- Create `backend/src/main/java/com/aicostops/evidence/infrastructure/EvidenceStorageProperties.java`
- Create `backend/src/main/java/com/aicostops/evidence/infrastructure/EvidenceStorageConfiguration.java`
- Create `backend/src/main/java/com/aicostops/evidence/infrastructure/MinioObjectStorageAdapter.java`
- Create `backend/src/main/java/com/aicostops/evidence/api/EvidenceController.java`

### Organization read port

- Create `backend/src/main/java/com/aicostops/organization/application/ProviderAccountDirectory.java`
- Create `backend/src/main/java/com/aicostops/organization/domain/ProviderAccountSnapshot.java`
- Create `backend/src/main/java/com/aicostops/organization/infrastructure/MyBatisProviderAccountDirectory.java`

### Ingestion module

- Create `backend/src/main/java/com/aicostops/ingestion/domain/ImportSourceType.java`
- Create `backend/src/main/java/com/aicostops/ingestion/domain/ImportBatchStatus.java`
- Create `backend/src/main/java/com/aicostops/ingestion/domain/ImportAttemptStatus.java`
- Create `backend/src/main/java/com/aicostops/ingestion/domain/ImportAttemptTrigger.java`
- Create `backend/src/main/java/com/aicostops/ingestion/domain/ImportIssueSeverity.java`
- Create `backend/src/main/java/com/aicostops/ingestion/domain/ImportBatch.java`
- Create `backend/src/main/java/com/aicostops/ingestion/domain/ImportAttempt.java`
- Create `backend/src/main/java/com/aicostops/ingestion/domain/RawProviderRecord.java`
- Create `backend/src/main/java/com/aicostops/ingestion/domain/ImportIssue.java`
- Create `backend/src/main/java/com/aicostops/ingestion/application/ProviderSource.java`
- Create `backend/src/main/java/com/aicostops/ingestion/application/ProviderAdapter.java`
- Create `backend/src/main/java/com/aicostops/ingestion/application/ProviderAdapterRegistry.java`
- Create `backend/src/main/java/com/aicostops/ingestion/application/ProviderRecordSink.java`
- Create `backend/src/main/java/com/aicostops/ingestion/application/InspectionResult.java`
- Create `backend/src/main/java/com/aicostops/ingestion/application/ParsedProviderRecord.java`
- Create `backend/src/main/java/com/aicostops/ingestion/application/NormalizedProviderRecord.java`
- Create `backend/src/main/java/com/aicostops/ingestion/application/ImportIssueDraft.java`
- Create `backend/src/main/java/com/aicostops/ingestion/application/ProviderImportService.java`
- Create `backend/src/main/java/com/aicostops/ingestion/application/ImportLeaseService.java`
- Create `backend/src/main/java/com/aicostops/ingestion/application/ImportRawPersistenceService.java`
- Create `backend/src/main/java/com/aicostops/ingestion/application/ImportAttemptExecutor.java`
- Create `backend/src/main/java/com/aicostops/ingestion/application/ImportWorkerCoordinator.java`
- Create `backend/src/main/java/com/aicostops/ingestion/infrastructure/ImportBatchMapper.java`
- Create `backend/src/main/java/com/aicostops/ingestion/infrastructure/ImportAttemptMapper.java`
- Create `backend/src/main/java/com/aicostops/ingestion/infrastructure/RawProviderRecordMapper.java`
- Create `backend/src/main/java/com/aicostops/ingestion/infrastructure/ImportIssueMapper.java`
- Create `backend/src/main/java/com/aicostops/ingestion/infrastructure/ImportWorkerProperties.java`
- Create `backend/src/main/java/com/aicostops/ingestion/infrastructure/ImportWorkerConfiguration.java`
- Create `backend/src/main/java/com/aicostops/ingestion/api/ProviderImportController.java`
- Create `backend/src/main/java/com/aicostops/ingestion/api/ProviderImportResponse.java`

### Existing security / error files

- Modify `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify `backend/src/main/java/com/aicostops/shared/web/ProblemCode.java`
- Modify `backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java`

### Test support and tests

- Create `backend/src/test/java/com/aicostops/testsupport/MinioContainerSupport.java`
- Create `backend/src/test/java/com/aicostops/M2EvidenceImportSchemaIntegrationTest.java`
- Modify `backend/src/test/java/com/aicostops/RolePermissionSeedIntegrationTest.java`
- Create `backend/src/test/java/com/aicostops/evidence/application/EvidenceUploadStagerTest.java`
- Create `backend/src/test/java/com/aicostops/evidence/infrastructure/MinioObjectStorageAdapterIntegrationTest.java`
- Create `backend/src/test/java/com/aicostops/evidence/application/EvidenceStorageServiceIntegrationTest.java`
- Create `backend/src/test/java/com/aicostops/evidence/api/EvidenceDownloadApiIntegrationTest.java`
- Create `backend/src/test/java/com/aicostops/ingestion/application/ProviderAdapterRegistryTest.java`
- Create `backend/src/test/java/com/aicostops/ingestion/api/ProviderImportApiIntegrationTest.java`
- Create `backend/src/test/java/com/aicostops/ingestion/application/ImportLeaseServiceIntegrationTest.java`
- Create `backend/src/test/java/com/aicostops/ingestion/application/ImportAttemptExecutorIntegrationTest.java`

### Documentation / evidence

- Modify `docs/02-development/detailed-design/02-data-model.md`
- Modify `docs/02-development/detailed-design/15-configuration-environments.md`
- Create `docs/02-development/api/03-m2-evidence-import-api.md`
- Create `docs/03-acceptance/implementation/11-m2-evidence-import-foundation-evidence.md`

---

### Task 1: Create the M2 schema and permission delta

**Files:**
- Create: `backend/src/main/resources/db/migration/V4__m2_evidence_import_schema.sql`
- Create: `backend/src/main/resources/db/migration/V5__m2_finance_reviewer_provider_account_read.sql`
- Create: `backend/src/test/java/com/aicostops/M2EvidenceImportSchemaIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/RolePermissionSeedIntegrationTest.java`

**Interfaces:**
- Produces the five durable M2 tables consumed by all later tasks.
- Produces `FINANCE_REVIEWER -> PROVIDER_ACCOUNT_READ` without granting `PROVIDER_ACCOUNT_MANAGE`.

- [ ] **Step 1: Write the failing schema integration test**

The test must assert all five tables, core foreign keys, uniqueness, and queue/recovery indexes.

```java
@SpringBootTest
@Tag("integration")
class M2EvidenceImportSchemaIntegrationTest extends MySqlContainerSupport {
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void migratesEvidenceAndImportFoundation() {
        var tables = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()",
                String.class));
        assertThat(tables).contains(
                "evidence", "import_batch", "import_attempt", "raw_provider_record", "import_issue");
    }

    @Test
    void exposesQueueAndRecoveryIndexes() {
        var indexes = Set.copyOf(jdbcTemplate.queryForList(
                "SELECT DISTINCT index_name FROM information_schema.statistics WHERE table_schema=DATABASE()",
                String.class));
        assertThat(indexes).contains(
                "uq_evidence_org_sha256",
                "uq_import_batch_identity",
                "uq_import_attempt_batch_no",
                "idx_import_attempt_queue",
                "idx_import_attempt_lease",
                "idx_import_attempt_batch_status",
                "uq_raw_provider_record_attempt_index");
    }
}
```

- [ ] **Step 2: Run it and verify RED**

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd -B -Dgroups=integration -Dit.test=M2EvidenceImportSchemaIntegrationTest verify
```

Expected: FAIL because the M2 tables do not exist.

- [ ] **Step 3: Add `V4__m2_evidence_import_schema.sql`**

Use explicit InnoDB tables, `utf8mb4_0900_ai_ci`, UTC `DATETIME(6)`, and `VARCHAR + CHECK` states. The physical indexes must be:

```sql
CONSTRAINT uq_evidence_org_sha256 UNIQUE (org_id, sha256);
CONSTRAINT uq_import_batch_identity UNIQUE (evidence_id, provider_account_id, source_type, parser_version);
CONSTRAINT uq_import_attempt_batch_no UNIQUE (import_batch_id, attempt_no);
KEY idx_import_attempt_queue (status, available_at, id);
KEY idx_import_attempt_lease (status, lease_until, id);
KEY idx_import_attempt_batch_status (import_batch_id, status, id);
CONSTRAINT uq_raw_provider_record_attempt_index UNIQUE (import_attempt_id, record_index);
```

Use these states exactly:

```text
Evidence.storage_status = STAGING | AVAILABLE | FAILED
ImportBatch.status = PENDING | PROCESSING | PARSED | FAILED | CANCELED
ImportAttempt.status = QUEUED | RUNNING | SUCCEEDED | FAILED | CANCELED
ImportAttempt.trigger_type = INITIAL | LEASE_RECOVERY | MANUAL_RETRY
RawProviderRecord.normalize_status = NORMALIZED | WARN | ERROR
ImportIssue.severity = WARN | ERROR
```

`import_batch.provider_account_id` is `NOT NULL` for this M2 provider-import workflow. `parser_version` is immutable by application contract. `predecessor_attempt_id` is nullable and references `import_attempt(id)`.

- [ ] **Step 4: Add `V5__m2_finance_reviewer_provider_account_read.sql`**

```sql
INSERT INTO role_permission(role_id, permission_id)
SELECT r.id, p.id
FROM `role` r
JOIN permission p
WHERE r.code='FINANCE_REVIEWER'
  AND p.code='PROVIDER_ACCOUNT_READ'
  AND NOT EXISTS (
      SELECT 1 FROM role_permission rp
      WHERE rp.role_id=r.id AND rp.permission_id=p.id
  );
```

Do not grant `PROVIDER_ACCOUNT_MANAGE`.

- [ ] **Step 5: Extend role/permission seed integration coverage**

Assert `FINANCE_REVIEWER` has `PROVIDER_ACCOUNT_READ` and still lacks `PROVIDER_ACCOUNT_MANAGE`.

- [ ] **Step 6: Run schema + seed integration tests**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=M2EvidenceImportSchemaIntegrationTest,RolePermissionSeedIntegrationTest verify
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/resources/db/migration backend/src/test/java/com/aicostops/M2EvidenceImportSchemaIntegrationTest.java backend/src/test/java/com/aicostops/RolePermissionSeedIntegrationTest.java
git commit -m "feat(import): add evidence and ingestion schema"
```

---

### Task 2: Add evidence persistence primitives

**Files:**
- Create: `backend/src/main/java/com/aicostops/evidence/domain/Evidence.java`
- Create: `backend/src/main/java/com/aicostops/evidence/domain/EvidenceStorageStatus.java`
- Create: `backend/src/main/java/com/aicostops/evidence/infrastructure/EvidenceMapper.java`
- Create: `backend/src/main/java/com/aicostops/evidence/application/EvidencePersistenceService.java`
- Test: `backend/src/test/java/com/aicostops/evidence/application/EvidenceStorageServiceIntegrationTest.java`

**Interfaces:**
- Produces `EvidencePersistenceService.reserve(...)`, `findAvailable(...)`, `markAvailable(...)`, and `markFailedUnlessAvailable(...)`.
- Later object-storage code must not call MyBatis directly.

- [ ] **Step 1: Write a failing integration test for same-SHA identity**

Test that two reservations with the same `(org_id, sha256)` return the same Evidence id and that `AVAILABLE` can never be downgraded to `FAILED`.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=EvidenceStorageServiceIntegrationTest verify
```

Expected: FAIL because the evidence persistence service does not exist.

- [ ] **Step 3: Implement focused domain records**

`Evidence` contains the persisted columns only; do not add import state to it.

```java
public record Evidence(
        long id,
        long organizationId,
        String sha256,
        String objectKey,
        String originalFilename,
        String mediaType,
        long sizeBytes,
        long uploadedByMemberId,
        EvidenceStorageStatus storageStatus,
        String storageErrorCode,
        Instant createdAt,
        Instant updatedAt) {}
```

- [ ] **Step 4: Implement `EvidenceMapper` with organization-scoped SQL**

Required methods:

```java
Evidence findByOrganizationAndSha(long organizationId, String sha256);
Evidence findByIdAndOrganization(long evidenceId, long organizationId);
int insertStaging(...);
int markAvailable(long evidenceId, long organizationId, Instant now);
int markFailedUnlessAvailable(long evidenceId, long organizationId, String errorCode, Instant now);
long lastInsertId();
```

`markFailedUnlessAvailable` must contain `AND storage_status <> 'AVAILABLE'` so a late failing concurrent uploader cannot downgrade a successfully stored object.

- [ ] **Step 5: Implement transactional reservation/finalization**

`EvidencePersistenceService` owns short `@Transactional` methods. Duplicate-key races are converted into lookup/reuse; they are not surfaced as 500 errors.

- [ ] **Step 6: Run GREEN**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=EvidenceStorageServiceIntegrationTest verify
```

Expected: PASS for duplicate reservation and no-downgrade behavior.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/aicostops/evidence backend/src/test/java/com/aicostops/evidence/application/EvidenceStorageServiceIntegrationTest.java
git commit -m "feat(evidence): add evidence persistence identity"
```

---

### Task 3: Add S3-compatible MinIO configuration and adapter

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application-test-defaults.yml`
- Modify: `compose.yaml`
- Modify: `.env.example`
- Create: `backend/src/main/java/com/aicostops/evidence/application/ObjectStoragePort.java`
- Create: `backend/src/main/java/com/aicostops/evidence/application/EvidenceContentReader.java`
- Create: `backend/src/main/java/com/aicostops/evidence/infrastructure/EvidenceStorageProperties.java`
- Create: `backend/src/main/java/com/aicostops/evidence/infrastructure/EvidenceStorageConfiguration.java`
- Create: `backend/src/main/java/com/aicostops/evidence/infrastructure/MinioObjectStorageAdapter.java`
- Create: `backend/src/test/java/com/aicostops/testsupport/MinioContainerSupport.java`
- Create: `backend/src/test/java/com/aicostops/evidence/infrastructure/MinioObjectStorageAdapterIntegrationTest.java`

**Interfaces:**
- `ObjectStoragePort.put(objectKey, path, size, sha256)`
- `ObjectStoragePort.stat(objectKey)` returns size + explicit SHA-256 user metadata.
- `ObjectStoragePort.open(objectKey)` returns a closeable stream.
- `ObjectStoragePort` contains no MinIO classes in its public signature.

- [ ] **Step 1: Add the MinIO Java dependency**

```xml
<properties>
  ...
  <minio.version>9.0.1</minio.version>
</properties>

<dependency>
  <groupId>io.minio</groupId>
  <artifactId>minio</artifactId>
  <version>${minio.version}</version>
</dependency>
```

- [ ] **Step 2: Add configuration properties**

Under `aicostops.storage` configure:

```yaml
aicostops:
  storage:
    endpoint: ${AICOSTOPS_STORAGE_ENDPOINT:http://localhost:9000}
    access-key: ${AICOSTOPS_STORAGE_ACCESS_KEY:aicostops}
    secret-key: ${AICOSTOPS_STORAGE_SECRET_KEY:change-me-local-only}
    bucket: ${AICOSTOPS_STORAGE_BUCKET:aicostops-evidence}
    upload-limit: ${AICOSTOPS_STORAGE_UPLOAD_LIMIT:512MiB}
    auto-create-bucket: ${AICOSTOPS_STORAGE_AUTO_CREATE_BUCKET:true}
```

Keep multipart framework limits slightly above the domain limit so application code can return the domain-specific oversize error rather than Tomcat rejecting first:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: ${AICOSTOPS_MULTIPART_MAX_FILE_SIZE:520MB}
      max-request-size: ${AICOSTOPS_MULTIPART_MAX_REQUEST_SIZE:525MB}
```

- [ ] **Step 3: Update local MinIO image**

In `compose.yaml` use:

```yaml
image: minio/minio:RELEASE.2025-10-15T17-29-55Z
```

Retain the existing S3 endpoint, bucket, access-key, and secret-key environment variables.

- [ ] **Step 4: Write a real MinIO integration test**

`MinioContainerSupport` uses Testcontainers `GenericContainer` with the same MinIO image and dynamically supplies endpoint/credentials/bucket properties.

Test:

```text
put object with sha256 metadata
stat object returns exact size and sha256
open object streams exact bytes
```

- [ ] **Step 5: Implement the adapter**

The adapter must use explicit user metadata `sha256=<lowercase-hex>`. Do not treat ETag as Evidence SHA-256. When `autoCreateBucket=true`, check/create the configured bucket before use; when false and the bucket is missing, fail fast with a storage dependency exception.

- [ ] **Step 6: Run GREEN**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=MinioObjectStorageAdapterIntegrationTest verify
```

Expected: PASS against real MinIO.

- [ ] **Step 7: Validate Compose**

```powershell
Set-Location E:\AI-CostOps
docker compose config --quiet
```

Expected: exit code 0 and no output.

- [ ] **Step 8: Commit**

```powershell
git add backend/pom.xml backend/src/main/resources/application.yml backend/src/test/resources/application-test-defaults.yml compose.yaml .env.example backend/src/main/java/com/aicostops/evidence backend/src/test/java/com/aicostops/testsupport/MinioContainerSupport.java backend/src/test/java/com/aicostops/evidence/infrastructure/MinioObjectStorageAdapterIntegrationTest.java
git commit -m "feat(storage): add S3-compatible evidence storage"
```

---

### Task 4: Implement streaming upload, checksum, dedup, and storage recovery

**Files:**
- Create: `backend/src/main/java/com/aicostops/evidence/application/EvidenceUploadStager.java`
- Create: `backend/src/main/java/com/aicostops/evidence/application/EvidenceStorageService.java`
- Modify: `backend/src/main/java/com/aicostops/shared/web/ProblemCode.java`
- Create: `backend/src/test/java/com/aicostops/evidence/application/EvidenceUploadStagerTest.java`
- Extend: `backend/src/test/java/com/aicostops/evidence/application/EvidenceStorageServiceIntegrationTest.java`

**Interfaces:**
- `EvidenceUploadStager.stage(InputStream, originalFilename, mediaType)` returns temp path, SHA-256, and size and is `AutoCloseable`/cleanup-safe.
- `EvidenceStorageService.store(...)` returns an `Evidence` plus `reused` flag.

- [ ] **Step 1: Write unit tests for streaming staging**

Cover:

```text
checksum matches known bytes
temp file is deleted after close
limit is enforced at limit + 1 byte
stream is processed with bounded buffer; no whole-file API is used
```

- [ ] **Step 2: Run RED**

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd -B -Dtest=EvidenceUploadStagerTest test
```

Expected: FAIL because the stager does not exist.

- [ ] **Step 3: Implement bounded streaming**

Use a fixed buffer such as 64 KiB, `MessageDigest.getInstance("SHA-256")`, and an explicit byte counter. Abort and delete the temp file when bytes exceed the configured 512 MiB limit. Do not call `readAllBytes()` or `MultipartFile.getBytes()`.

Add `EVIDENCE_TOO_LARGE` to `ProblemCode` and map oversize uploads to HTTP 413.

- [ ] **Step 4: Write integration tests for storage lifecycle**

Cover:

```text
same org + same bytes -> same Evidence id
same bytes across different orgs -> different Evidence ids/object namespaces
MinIO/object storage put runs with TransactionSynchronizationManager.isActualTransactionActive() == false
STAGING + matching existing object repairs to AVAILABLE
mismatched existing object metadata -> identity conflict; never overwrite
late storage failure cannot downgrade AVAILABLE
```

Use a recording/fault-injecting `ObjectStoragePort` test bean for transaction-boundary and failure-window tests; keep real MinIO behavior in Task 3 tests.

- [ ] **Step 5: Implement object-key and storage lifecycle**

Object key format:

```text
org/{orgId}/evidence/sha256/{first-two-hex}/{sha256}
```

Sequence:

```text
stage request bytes
-> reserve/reuse STAGING Evidence in short transaction
-> if AVAILABLE return reused result
-> stat deterministic object
-> if matching object exists mark AVAILABLE
-> else upload outside DB transaction
-> short transaction mark AVAILABLE
-> on storage error mark FAILED unless already AVAILABLE
-> always delete temp file
```

- [ ] **Step 6: Run tests**

```powershell
.\mvnw.cmd -B -Dtest=EvidenceUploadStagerTest test
.\mvnw.cmd -B -Dgroups=integration -Dit.test=EvidenceStorageServiceIntegrationTest verify
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/aicostops/evidence backend/src/main/java/com/aicostops/shared/web/ProblemCode.java backend/src/test/java/com/aicostops/evidence
git commit -m "feat(evidence): add resilient streaming upload"
```

---

### Task 5: Add authorized Evidence download

**Files:**
- Create: `backend/src/main/java/com/aicostops/evidence/application/EvidenceDownloadService.java`
- Create: `backend/src/main/java/com/aicostops/evidence/api/EvidenceController.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Create: `backend/src/test/java/com/aicostops/evidence/api/EvidenceDownloadApiIntegrationTest.java`

**Interfaces:**
- `GET /api/v1/evidence/{id}/download`
- Requires `EVIDENCE_DOWNLOAD` through existing AuthorizationContext/Application Service checks.
- Cross-org and nonexistent evidence both return privacy-preserving 404.

- [ ] **Step 1: Write failing API tests**

Cover:

```text
Finance Reviewer with EVIDENCE_DOWNLOAD -> 200 and exact bytes
missing permission -> 403
cross-org Evidence id -> 404
Evidence storage_status != AVAILABLE -> 409 or dependency-safe failure; do not stream partial content
```

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=EvidenceDownloadApiIntegrationTest verify
```

- [ ] **Step 3: Implement service and controller**

Use organization-scoped Evidence lookup first, then open the object. Stream with `StreamingResponseBody`; close the object stream deterministically. Build `Content-Disposition` from a sanitized filename, never from object key.

- [ ] **Step 4: Add authenticated matcher**

Add only the exact GET route to `SecurityConfiguration`; keep final `.anyRequest().denyAll()`.

- [ ] **Step 5: Run GREEN**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=EvidenceDownloadApiIntegrationTest verify
```

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/aicostops/evidence backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java backend/src/test/java/com/aicostops/evidence/api/EvidenceDownloadApiIntegrationTest.java
git commit -m "feat(evidence): add authorized evidence download"
```

---

### Task 6: Add Provider Account read port and ProviderAdapter registry

**Files:**
- Create: `backend/src/main/java/com/aicostops/organization/application/ProviderAccountDirectory.java`
- Create: `backend/src/main/java/com/aicostops/organization/domain/ProviderAccountSnapshot.java`
- Create: `backend/src/main/java/com/aicostops/organization/infrastructure/MyBatisProviderAccountDirectory.java`
- Create: `backend/src/main/java/com/aicostops/ingestion/application/ProviderSource.java`
- Create: `backend/src/main/java/com/aicostops/ingestion/application/ProviderAdapter.java`
- Create: `backend/src/main/java/com/aicostops/ingestion/application/ProviderAdapterRegistry.java`
- Create: `backend/src/main/java/com/aicostops/ingestion/application/ProviderRecordSink.java`
- Create: `backend/src/main/java/com/aicostops/ingestion/application/InspectionResult.java`
- Create: `backend/src/main/java/com/aicostops/ingestion/application/ParsedProviderRecord.java`
- Create: `backend/src/main/java/com/aicostops/ingestion/application/NormalizedProviderRecord.java`
- Create: `backend/src/main/java/com/aicostops/ingestion/application/ImportIssueDraft.java`
- Create: `backend/src/test/java/com/aicostops/ingestion/application/ProviderAdapterRegistryTest.java`

**Interfaces:**

```java
public interface ProviderAdapter {
    String providerCode();
    String parserVersion();
    InspectionResult inspect(ProviderSource source);
    void parse(ProviderSource source, InspectionResult inspection, ProviderRecordSink sink);
    NormalizedProviderRecord normalize(ParsedProviderRecord record);
}
```

`ProviderSource.openStream()` must return a fresh closeable stream each time so inspect and parse do not share an exhausted stream.

- [ ] **Step 1: Write registry tests**

Cover canonical provider-code lookup, missing adapter, and duplicate canonical registration.

Canonicalization rule:

```java
providerCode.trim().toUpperCase(Locale.ROOT)
```

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd -B -Dtest=ProviderAdapterRegistryTest test
```

- [ ] **Step 3: Implement explicit registry**

Build an immutable map from injected adapters. Duplicate canonical codes throw at application startup/registry construction. Unknown providers return a stable validation failure; never guess from class names.

- [ ] **Step 4: Implement schema fingerprint contract**

`InspectionResult` carries:

```text
detectedProviderCode
schemaFingerprint
compatible
issues[]
```

Schema fingerprint is SHA-256 over a canonical schema descriptor, not over file bytes and not over row values.

- [ ] **Step 5: Implement read-only Provider Account directory**

`findActive(organizationId, providerAccountId)` must scope SQL by both id and org and require status `ACTIVE`. It returns only fields needed by ingestion: id, org id, canonicalizable provider code, display name, status.

- [ ] **Step 6: Run GREEN**

```powershell
.\mvnw.cmd -B -Dtest=ProviderAdapterRegistryTest test
```

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/aicostops/organization backend/src/main/java/com/aicostops/ingestion/application backend/src/test/java/com/aicostops/ingestion/application/ProviderAdapterRegistryTest.java
git commit -m "feat(import): add provider adapter registry"
```

---

### Task 7: Add Import persistence and idempotent provider-import creation

**Files:**
- Create: ingestion domain enums/records listed in file map
- Create: `ImportBatchMapper.java`
- Create: `ImportAttemptMapper.java`
- Create: `ProviderImportService.java`
- Create: `ProviderImportController.java`
- Create: `ProviderImportResponse.java`
- Modify: `SecurityConfiguration.java`
- Create: `ProviderImportApiIntegrationTest.java`

**Interfaces:**
- `POST /api/v1/provider-imports` multipart fields: `file`, `providerAccountId`, `sourceType`.
- Requires `EVIDENCE_UPLOAD_PROVIDER`.
- Resolves Provider Account and Adapter before persisting a new Batch.
- Returns existing Batch without creating a new Attempt when identity already exists.

- [ ] **Step 1: Define M2 source type enum**

```java
public enum ImportSourceType {
    FILE_EXPORT,
    USAGE_API_JSON,
    COSTS_API_JSON
}
```

- [ ] **Step 2: Write failing integration tests with a synthetic test adapter**

Test configuration registers a `TEST_PROVIDER` adapter only in tests.

Cover:

```text
new file/context -> Evidence + Batch + Attempt #1 QUEUED
same bytes + same provider account + same source type + same parser version -> same Evidence/Batch/Attempt
same bytes + different Provider Account -> same Evidence but different Batch
inactive/cross-org Provider Account -> privacy-preserving 404
unsupported provider -> 400 and no ImportBatch
missing EVIDENCE_UPLOAD_PROVIDER -> 403
```

- [ ] **Step 3: Run RED**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=ProviderImportApiIntegrationTest verify
```

- [ ] **Step 4: Implement Batch insert/reuse transaction**

Within one short transaction:

```text
lock/find-or-insert ImportBatch identity
if new: insert Attempt #1 status QUEUED, trigger INITIAL, attempt_no=1
if existing: return latest Attempt; do not create a new Attempt
```

Any code path that creates a new Attempt must lock the parent `import_batch` row first and verify no `QUEUED` or `RUNNING` Attempt exists.

- [ ] **Step 5: Implement request flow**

Order:

```text
auth context
-> require EVIDENCE_UPLOAD_PROVIDER
-> find ACTIVE provider account in current org
-> resolve adapter/parserVersion
-> store/reuse Evidence
-> create/reuse Batch
-> return response
```

Resolve unsupported provider before expensive file storage when possible.

- [ ] **Step 6: Add security matcher and GREEN test**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=ProviderImportApiIntegrationTest verify
```

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/aicostops/ingestion backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java backend/src/test/java/com/aicostops/ingestion/api/ProviderImportApiIntegrationTest.java
git commit -m "feat(import): add idempotent provider import creation"
```

---

### Task 8: Implement MySQL claim, lease, heartbeat, and fencing

**Files:**
- Create: `backend/src/main/java/com/aicostops/ingestion/application/ImportLeaseService.java`
- Extend: `backend/src/main/java/com/aicostops/ingestion/infrastructure/ImportAttemptMapper.java`
- Create: `backend/src/main/java/com/aicostops/ingestion/infrastructure/ImportWorkerProperties.java`
- Create: `backend/src/test/java/com/aicostops/ingestion/application/ImportLeaseServiceIntegrationTest.java`

**Interfaces:**
- `claimNext(workerId)` -> optional claimed Attempt carrying lease owner/version.
- `heartbeat(attemptId, workerId, leaseVersion)` -> boolean ownership retained.
- `assertLeaseForUpdate(...)` is used before every bounded raw persistence transaction/finalization.

- [ ] **Step 1: Write dual-worker claim test**

Insert two queued Attempts, start two concurrent claim transactions, and prove each worker receives a different Attempt. Add a single-job variant proving only one worker receives it.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=ImportLeaseServiceIntegrationTest verify
```

- [ ] **Step 3: Implement explicit claim SQL**

Mapper select:

```sql
SELECT ...
FROM import_attempt ia
JOIN import_batch ib ON ib.id=ia.import_batch_id
WHERE ia.status='QUEUED'
  AND ia.available_at <= UTC_TIMESTAMP(6)
ORDER BY ia.available_at, ia.id
FOR UPDATE SKIP LOCKED
LIMIT 1
```

In the same transaction, set:

```sql
status='RUNNING',
lease_owner=:workerId,
lease_until=TIMESTAMPADD(MICROSECOND,:leaseMicros,UTC_TIMESTAMP(6)),
lease_version=lease_version+1,
started_at=COALESCE(started_at,UTC_TIMESTAMP(6))
```

and set parent Batch to `PROCESSING`.

- [ ] **Step 4: Implement heartbeat fencing**

Heartbeat update must require:

```sql
id=:attemptId
AND status='RUNNING'
AND lease_owner=:workerId
AND lease_version=:leaseVersion
AND lease_until > UTC_TIMESTAMP(6)
```

Zero affected rows means lease lost.

- [ ] **Step 5: Test stale-owner fencing**

Prove a stale owner/version cannot heartbeat or finalize after ownership changes/expiry.

- [ ] **Step 6: Run GREEN**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=ImportLeaseServiceIntegrationTest verify
```

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/aicostops/ingestion backend/src/test/java/com/aicostops/ingestion/application/ImportLeaseServiceIntegrationTest.java
git commit -m "feat(import): add fenced MySQL import leases"
```

---

### Task 9: Implement lease-expiry crash recovery

**Files:**
- Extend: `ImportLeaseService.java`
- Extend: `ImportAttemptMapper.java`
- Extend: `ImportBatchMapper.java`
- Extend: `ImportLeaseServiceIntegrationTest.java`

**Interfaces:**
- `recoverOneExpired()` atomically fails an expired Attempt and either creates one new `LEASE_RECOVERY` Attempt or leaves Batch `FAILED` when budget is exhausted.

- [ ] **Step 1: Write failing recovery tests**

Cover:

```text
expired RUNNING -> old FAILED + new QUEUED Attempt #N+1
new Attempt predecessor_attempt_id points to expired Attempt
new Attempt trigger_type=LEASE_RECOVERY
Batch -> PENDING
old raw rows remain attached to old Attempt
3 recovery Attempts exhausted -> no new Attempt, Batch FAILED
non-expired RUNNING -> untouched
concurrent recovery workers -> only one successor Attempt
```

- [ ] **Step 2: Implement recovery transaction**

Select expired Attempts with:

```sql
WHERE status='RUNNING'
  AND lease_until < UTC_TIMESTAMP(6)
ORDER BY lease_until,id
FOR UPDATE SKIP LOCKED
LIMIT 1
```

Lock the parent Batch before assigning the next Attempt number. Count existing `LEASE_RECOVERY` attempts for the Batch and compare against configured max 3.

- [ ] **Step 3: Run GREEN**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=ImportLeaseServiceIntegrationTest verify
```

- [ ] **Step 4: Commit**

```powershell
git add backend/src/main/java/com/aicostops/ingestion backend/src/test/java/com/aicostops/ingestion/application/ImportLeaseServiceIntegrationTest.java
git commit -m "feat(import): add import lease crash recovery"
```

---

### Task 10: Implement fenced bounded raw-record and issue persistence

**Files:**
- Create: `RawProviderRecordMapper.java`
- Create: `ImportIssueMapper.java`
- Create: `ImportRawPersistenceService.java`
- Create: raw/issue domain records listed in file map
- Create/extend: `ImportAttemptExecutorIntegrationTest.java`

**Interfaces:**
- `persistBatch(claim, records, issues)` validates the current lease under row lock, then inserts at most configured 500 records and matching issues in one short transaction.
- Raw payload is redacted before insertion.

- [ ] **Step 1: Write failing tests**

Cover:

```text
500-record batch inserts and increments counters atomically
501st record is handled in next transaction, not one giant transaction
stale lease cannot insert rows
partial rows from failed Attempt remain after failure
full API-key/token-like raw values are not persisted
WARN and ERROR issue counts are accurate
```

- [ ] **Step 2: Implement secret redaction**

Reuse the existing M1 secret-key philosophy: recursively normalize keys and redact keys containing fragments such as `password`, `token`, `secret`, `apikey`, `api_key`, `authorization` before serializing `raw_payload`/`normalized_payload` or issue raw values.

Do not log rejected secret values.

- [ ] **Step 3: Implement fenced transaction**

Every persistence batch begins by locking/verifying the Attempt row against `(attemptId, leaseOwner, leaseVersion, lease_until > DB_NOW)`. Inserts + counter update occur in that same transaction.

- [ ] **Step 4: Run GREEN**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=ImportAttemptExecutorIntegrationTest verify
```

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/aicostops/ingestion backend/src/test/java/com/aicostops/ingestion/application/ImportAttemptExecutorIntegrationTest.java
git commit -m "feat(import): add fenced raw record persistence"
```

---

### Task 11: Implement adapter execution and worker TaskExecutor lifecycle

**Files:**
- Create: `ImportAttemptExecutor.java`
- Create: `ImportWorkerCoordinator.java`
- Create: `ImportWorkerConfiguration.java`
- Extend: `ImportWorkerProperties.java`
- Extend: `ImportAttemptExecutorIntegrationTest.java`

**Interfaces:**
- Worker concurrency defaults to 2.
- Poller submits only when a local concurrency permit is available.
- A scheduled heartbeat renews the current lease every 20s while parsing.
- Worker finalization is fenced.

- [ ] **Step 1: Add worker config**

```yaml
aicostops:
  ingestion:
    worker-enabled: ${AICOSTOPS_IMPORT_WORKER_ENABLED:true}
    worker-concurrency: ${AICOSTOPS_IMPORT_WORKER_CONCURRENCY:2}
    poll-interval: ${AICOSTOPS_IMPORT_POLL_INTERVAL:1s}
    lease-duration: ${AICOSTOPS_IMPORT_LEASE_DURATION:60s}
    heartbeat-interval: ${AICOSTOPS_IMPORT_HEARTBEAT_INTERVAL:20s}
    max-lease-recoveries: ${AICOSTOPS_IMPORT_MAX_LEASE_RECOVERIES:3}
    persistence-batch-size: ${AICOSTOPS_IMPORT_PERSISTENCE_BATCH_SIZE:500}
```

- [ ] **Step 2: Configure bounded Spring executors**

Use `ThreadPoolTaskExecutor` with core/max equal to configured concurrency and no unbounded queue. A local `Semaphore` must be acquired before DB claim so a successfully claimed Attempt is never left RUNNING merely because executor submission was already saturated.

Use a separate Spring `TaskScheduler` for heartbeat; do not block a worker thread just to sleep between renewals.

- [ ] **Step 3: Implement execution flow**

```text
claim
-> open Evidence-backed ProviderSource
-> inspect
-> persist inspection WARN/ERROR
-> if incompatible/ERROR: fail Attempt/Batch
-> parse streaming records
-> adapter.normalize(record)
-> redact payload
-> flush bounded batches through fenced persistence
-> final flush
-> if error_count > 0: Attempt FAILED / Batch FAILED
-> else Attempt SUCCEEDED / Batch PARSED
```

`ProviderSource.openStream()` delegates to Evidence/ObjectStorage and opens a fresh stream for each call.

- [ ] **Step 4: Implement heartbeat loss behavior**

If heartbeat returns false, mark an in-memory guard as lease-lost. Parsing/sink operations must stop; finalization must not write success. The stale worker may not create its own recovery Attempt; the DB recovery path owns that transition.

- [ ] **Step 5: Add tests for WARN/ERROR and unknown schema**

Use synthetic adapters:

```text
WARN-only -> Attempt SUCCEEDED, Batch PARSED
ERROR/unknown schema -> Attempt FAILED, Batch FAILED
parserVersion + schemaFingerprint persisted on Attempt
partial raw records retained after later parse error
```

- [ ] **Step 6: Run GREEN**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=ImportAttemptExecutorIntegrationTest verify
```

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/aicostops/ingestion backend/src/main/resources/application.yml backend/src/test/resources/application-test-defaults.yml backend/src/test/java/com/aicostops/ingestion/application/ImportAttemptExecutorIntegrationTest.java
git commit -m "feat(import): add recoverable import worker execution"
```

---

### Task 12: Enforce module boundaries with ArchUnit

**Files:**
- Modify: `backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java`

**Interfaces:**
- Prevent future coupling that violates the approved M2/M3 boundary.

- [ ] **Step 1: Write failing architecture rules**

Add rules equivalent to:

```text
com.aicostops.evidence.. must not depend on com.aicostops.ingestion..
com.aicostops.ingestion.. must not depend on com.aicostops.ledger..
com.aicostops.ingestion.. must not depend on com.aicostops.budget..
com.aicostops.ingestion.. must not depend on com.aicostops.attribution..
com.aicostops.ingestion.. must not depend on com.aicostops.reporting..
```

Do not prohibit `ingestion -> evidence` or `ingestion -> organization`.

- [ ] **Step 2: Run architecture tests**

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd -B -Dgroups=architecture test
```

Expected: PASS once package dependencies follow the design.

- [ ] **Step 3: Commit**

```powershell
git add backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java
git commit -m "test(architecture): enforce evidence ingestion boundaries"
```

---

### Task 13: Run the Group 1 acceptance suite and update documentation

**Files:**
- Modify: `docs/02-development/detailed-design/02-data-model.md`
- Modify: `docs/02-development/detailed-design/15-configuration-environments.md`
- Create: `docs/02-development/api/03-m2-evidence-import-api.md`
- Create: `docs/03-acceptance/implementation/11-m2-evidence-import-foundation-evidence.md`

**Interfaces:**
- Documents only behavior actually implemented and test evidence actually observed.

- [ ] **Step 1: Update the data model doc**

Reflect actual V4 schema, including:

```text
UQ(org_id,sha256)
UQ(evidence_id,provider_account_id,source_type,parser_version)
Batch PENDING/PROCESSING/PARSED/FAILED/CANCELED
Attempt QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELED
lease_owner/lease_until/lease_version
separate queue and lease indexes
M2 PARSED != M3 READY_FOR_REVIEW
```

- [ ] **Step 2: Document Group 1 APIs**

`03-m2-evidence-import-api.md` records only implemented Group 1 routes:

```text
POST /api/v1/provider-imports
GET  /api/v1/evidence/{id}/download
```

Explicitly state that Retry/Cancel/List/Detail are AIC-030 and final Confirm is M3.

- [ ] **Step 3: Run backend unit suite**

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd -B -DexcludedGroups=architecture,integration test
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Run backend integration suite**

```powershell
.\mvnw.cmd -B -Dgroups=integration verify
```

Expected: BUILD SUCCESS, including M1 regression tests and new M2 MySQL/MinIO tests.

- [ ] **Step 5: Run backend architecture suite**

```powershell
.\mvnw.cmd -B -Dgroups=architecture test
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Validate Docker/Compose**

```powershell
Set-Location E:\AI-CostOps
docker compose config --quiet
docker build --tag ai-costops-backend:m2-foundation backend
```

Expected: both commands exit 0.

- [ ] **Step 7: Record acceptance evidence**

The evidence document records exact test counts/results from the commands above. Do not invent performance claims. Include explicit evidence for:

```text
same-SHA dedup
MinIO failure/recovery
unauthorized download
no long DB transaction during object upload
dual-worker claim
lease expiry/crash recovery
lease fencing
unknown schema
parser version + schema fingerprint lineage
WARN vs ERROR behavior
```

- [ ] **Step 8: Inspect diff and secrets**

```powershell
git status --short
git diff --check
git diff --stat main...HEAD
git grep -n -I -E "sk-[A-Za-z0-9_-]{16,}|BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|password\s*[:=]\s*[^$<{]" -- . ':!docs/superpowers' ':!.env.example'
```

Expected:

```text
git diff --check -> no output
secret scan -> no real secret match
```

Review any secret-scan match manually; do not silence the command merely to make it green.

- [ ] **Step 9: Commit documentation/evidence**

```powershell
git add docs .env.example
git commit -m "docs(m2): record evidence import foundation contracts"
```

- [ ] **Step 10: Final branch status**

```powershell
git status --short
git log --oneline --decorate main..HEAD
```

Expected: clean working tree and a readable series of focused commits.

---

## PR preparation after all tasks pass

Push:

```powershell
Set-Location E:\AI-CostOps
git push
```

Formal PR:

```text
feat(m2): establish evidence and import foundation

Closes #29  AIC-021
Closes #30  AIC-022
Closes #31  AIC-023
Closes #32  AIC-024
```

The PR must not be merged until all required checks are green:

```text
backend-unit
backend-integration
backend-architecture
frontend-lint
frontend-test
frontend-build
docker-build
```

No required check may be disabled or bypassed to merge.

## Implementation review gates

Before declaring Group 1 ready for PR, verify all of these explicitly:

```text
[ ] no canonical cost tables/types created
[ ] no M3 READY_FOR_REVIEW/Confirm implementation
[ ] no production DeepSeek/MiMo/Kimi/GLM/OpenAI adapter yet
[ ] Evidence dedup is org-scoped, not cross-tenant
[ ] duplicate upload does not implicitly create retry Attempt
[ ] object upload occurs outside DB transaction
[ ] object-key identity conflict never overwrites mismatched bytes
[ ] one Batch has at most one QUEUED/RUNNING Attempt
[ ] claim uses real MySQL FOR UPDATE SKIP LOCKED
[ ] lease expiry uses DB UTC time
[ ] stale lease cannot persist raw rows or finalize success
[ ] crash recovery creates new Attempt and retains old Attempt history
[ ] recovery loop is bounded
[ ] raw payload redaction occurs before DB persistence
[ ] failed Attempt raw rows/issues remain reviewable
[ ] Adapter registry is explicit and duplicate-safe
[ ] schema fingerprint is schema-derived, not file SHA
[ ] Group 1 API/security routes preserve final denyAll
[ ] Finance Reviewer gained Provider Account read only, not manage
[ ] all seven required GitHub checks are green before merge
```
