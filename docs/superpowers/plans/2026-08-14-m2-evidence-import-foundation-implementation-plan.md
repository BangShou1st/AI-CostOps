# M2 Evidence & Import Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver AIC-021 through AIC-024 as one backend foundation that stores immutable provider evidence in S3-compatible storage, creates durable import execution history, claims work safely through MySQL leases, and exposes a streaming ProviderAdapter framework without crossing into M3 canonical cost normalization.

**Architecture:** Keep `evidence` and `ingestion` as separate feature modules. `evidence` owns byte identity, storage lifecycle, checksum, object storage, and authorized download; `ingestion` owns ImportBatch/ImportAttempt, worker leases, raw records/issues, adapter registry, and parse orchestration. MySQL is the concurrency/lifecycle source of truth, MinIO/S3 stores file bytes, and object/network/file parsing work occurs outside long DB transactions.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Plain MyBatis / mybatis-spring-boot-starter 4.1.0, MySQL 8.4, Flyway, Testcontainers 2.0.5, MinIO Java SDK 9.0.1, Spring TaskExecutor, Spring MVC, Spring Security, JUnit 5, AssertJ, ArchUnit.

**Primary Spec:** `docs/superpowers/specs/2026-08-14-m2-evidence-import-foundation-design.md`

## Global Constraints

- PowerShell only for local commands; repository path is `E:\AI-CostOps`.
- Planned implementation branch: `feat/m2-evidence-import-foundation`.
- No JPA, Hibernate, MyBatis-Plus, H2, RabbitMQ, Kafka, or Redis job truth.
- `evidence` must not depend on `ingestion`; `ingestion` may depend on `evidence` and read-only `organization` ports.
- Provider adapters must not depend on ledger, budget, attribution, reporting, or M3 canonical cost types.
- M2 ends at `ImportBatch.PARSED` or `FAILED`; `READY_FOR_REVIEW`, canonical facts, and final Confirm belong to M3.
- Same organization + same SHA-256 reuses one Evidence identity; there is no cross-tenant object dedup.
- Same Evidence + Provider Account + source type + parser version reuses one ImportBatch and does not implicitly retry.
- Retry/recovery creates a new ImportAttempt; old Attempts and their raw rows/issues remain durable lineage.
- Provider raw-evidence upload/download require their permission **and ORG scope**.
- Object storage I/O and large-file parsing must not hold long DB transactions.
- Claim/recovery/fencing must be proven against real MySQL 8.4 Testcontainers.
- Raw provider payload stored in MySQL is redacted before persistence; Evidence object bytes remain authoritative original evidence.
- Upload hard limit starts at 512 MiB; raw-record persistence batch starts at 500; worker lease 60s; heartbeat 20s; automatic lease recovery budget 3. All are configurable.
- MinIO Java SDK is fixed at 9.0.1 for this PR.
- Local MinIO image moves to `minio/minio:RELEASE.2025-10-15T17-29-55Z`.
- MinIO bucket initialization is lazy on first storage operation. No network call from bean construction, `@PostConstruct`, configuration binding, or unrelated application startup.
- Group 1 contains no production DeepSeek/MiMo/Kimi/GLM/OpenAI adapter. Synthetic adapters are test-only; Group 2 supplies production adapters.
- Test environment defaults `aicostops.ingestion.worker-enabled=false`. Worker/coordinator tests explicitly opt in.
- Every new M2 integration test using shared MySQL state performs FK-safe cleanup both before and after each test.

---

## Execution preflight

Run only after the documentation PR containing this plan/spec has been merged to `main`.

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
working tree clean
main == origin/main
feature branch tracks origin/feat/m2-evidence-import-foundation
```

If `git status --short` prints anything, do not start implementation until the local changes are resolved.

---

## Test isolation rule

Create `backend/src/test/java/com/aicostops/testsupport/M2DatabaseCleaner.java` and use it from M2 integration tests in `@BeforeEach` and `@AfterEach`.

FK-safe delete order:

```text
import_issue
raw_provider_record
import_attempt
import_batch
evidence
api_idempotency
audit_event
invitation
role_assignment
project_member
team_member
provider_account
organization_member
project
team
cost_center
user_credential
app_user
organization
```

Do not delete the seeded `role`, `permission`, or `role_permission` catalog in the cleaner.

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

### Existing security / shared files
- Modify `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify `backend/src/main/java/com/aicostops/shared/web/ProblemCode.java`
- Modify `backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java`

### Test support / tests
- Create `backend/src/test/java/com/aicostops/testsupport/M2DatabaseCleaner.java`
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
- Create `backend/src/test/java/com/aicostops/ingestion/application/ImportWorkerCoordinatorIntegrationTest.java`

### Documentation / evidence
- Modify `docs/02-development/detailed-design/02-data-model.md`
- Modify `docs/02-development/detailed-design/15-configuration-environments.md`
- Create `docs/02-development/api/03-m2-evidence-import-api.md`
- Create `docs/03-acceptance/implementation/11-m2-evidence-import-foundation-evidence.md`

---

### Task 1: Create M2 schema, permission delta, and deterministic test cleanup

**Files:**
- Create `V4__m2_evidence_import_schema.sql`
- Create `V5__m2_finance_reviewer_provider_account_read.sql`
- Create `M2EvidenceImportSchemaIntegrationTest.java`
- Create `M2DatabaseCleaner.java`
- Modify `RolePermissionSeedIntegrationTest.java`

**Produces:** five durable M2 tables and the Finance Reviewer read-only Provider Account permission.

- [ ] **Step 1: Write failing schema tests**

Assert tables, key FKs, uniqueness, and these physical indexes:

```text
uq_evidence_org_sha256
uq_import_batch_identity
uq_import_attempt_batch_no
idx_import_attempt_queue
idx_import_attempt_lease
idx_import_attempt_batch_status
uq_raw_provider_record_attempt_index
```

- [ ] **Step 2: Verify RED**

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd -B -Dgroups=integration -Dit.test=M2EvidenceImportSchemaIntegrationTest verify
```

Expected: FAIL because M2 tables do not exist.

- [ ] **Step 3: Implement V4 with these table shapes**

```text
evidence:
  id, org_id, sha256, object_key, original_filename, media_type,
  size_bytes, uploaded_by_member_id, storage_status, storage_error_code,
  created_at, updated_at
  UQ(org_id,sha256)

import_batch:
  id, org_id, evidence_id, provider_account_id,
  expected_provider_code, source_type, parser_version, status,
  period_start, period_end, created_by_member_id, created_at, updated_at
  UQ(evidence_id,provider_account_id,source_type,parser_version)

import_attempt:
  id, import_batch_id, attempt_no, status, trigger_type,
  predecessor_attempt_id, available_at, lease_owner, lease_until, lease_version,
  parser_version, detected_provider_code, schema_fingerprint,
  started_at, finished_at, error_code, error_summary,
  records_seen, records_valid, warning_count, error_count, created_at
  UQ(import_batch_id,attempt_no)
  IDX(status,available_at,id)
  IDX(status,lease_until,id)
  IDX(import_batch_id,status,id)

raw_provider_record:
  id, import_attempt_id, record_index, record_locator, provider_record_key,
  raw_payload JSON, normalized_payload JSON,
  usage_start, usage_end, normalize_status, created_at
  UQ(import_attempt_id,record_index)

import_issue:
  id, import_attempt_id, raw_provider_record_id,
  severity, issue_code, record_locator, field_name,
  message, raw_value_masked, created_at
```

Use `BIGINT AUTO_INCREMENT`, `DATETIME(6)`, InnoDB, `utf8mb4_0900_ai_ci`, explicit FKs, and CHECKs for the frozen states:

```text
Evidence: STAGING|AVAILABLE|FAILED
Batch: PENDING|PROCESSING|PARSED|FAILED|CANCELED
Attempt: QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELED
Trigger: INITIAL|LEASE_RECOVERY|MANUAL_RETRY
Raw normalize: NORMALIZED|WARN|ERROR
Issue severity: WARN|ERROR
```

- [ ] **Step 4: Implement V5 permission delta**

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

Assert Reviewer still lacks `PROVIDER_ACCOUNT_MANAGE`.

- [ ] **Step 5: Implement `M2DatabaseCleaner`**

Use the exact FK-safe delete order in the Test isolation rule. Every new M2 integration test calls `clean(jdbcTemplate)` in both `@BeforeEach` and `@AfterEach`.

- [ ] **Step 6: Verify GREEN**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=M2EvidenceImportSchemaIntegrationTest,RolePermissionSeedIntegrationTest verify
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/resources/db/migration backend/src/test/java/com/aicostops/M2EvidenceImportSchemaIntegrationTest.java backend/src/test/java/com/aicostops/RolePermissionSeedIntegrationTest.java backend/src/test/java/com/aicostops/testsupport/M2DatabaseCleaner.java
git commit -m "feat(import): add evidence and ingestion schema"
```

---

### Task 2: Add Evidence persistence identity

**Files:** Evidence domain record/status, `EvidenceMapper`, `EvidencePersistenceService`, `EvidenceStorageServiceIntegrationTest`.

**Produces:** short transactional reservation/finalization APIs; later storage code never calls MyBatis directly.

- [ ] **Step 1: Write failing integration tests** for same `(org_id,sha256)` reuse, different-org separation, and “AVAILABLE cannot be downgraded by a late failure”. Use M2 before/after cleanup.

- [ ] **Step 2: Verify RED**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=EvidenceStorageServiceIntegrationTest verify
```

- [ ] **Step 3: Implement the record**

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

- [ ] **Step 4: Implement mapper methods**

```java
Evidence findByOrganizationAndSha(long organizationId, String sha256);
Evidence findByIdAndOrganization(long evidenceId, long organizationId);
int insertStaging(...);
int markAvailable(long evidenceId, long organizationId, Instant now);
int markFailedUnlessAvailable(long evidenceId, long organizationId, String errorCode, Instant now);
long lastInsertId();
```

`markFailedUnlessAvailable` includes `AND storage_status <> 'AVAILABLE'`.

- [ ] **Step 5: Implement `EvidencePersistenceService`** with short `@Transactional` methods. Convert duplicate-key races into lookup/reuse rather than 500.

- [ ] **Step 6: Verify GREEN and commit**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=EvidenceStorageServiceIntegrationTest verify
git add backend/src/main/java/com/aicostops/evidence backend/src/test/java/com/aicostops/evidence/application/EvidenceStorageServiceIntegrationTest.java
git commit -m "feat(evidence): add evidence persistence identity"
```

---

### Task 3: Add MinIO/S3 adapter with lazy initialization

**Files:** `pom.xml`, application/test config, Compose, `.env.example`, `ObjectStoragePort`, `EvidenceContentReader`, storage properties/configuration, MinIO adapter, `MinioContainerSupport`, MinIO integration test.

**Produces:** S3-compatible storage behind a MinIO-free application port.

- [ ] **Step 1: Add MinIO Java 9.0.1**

```xml
<minio.version>9.0.1</minio.version>
...
<dependency>
  <groupId>io.minio</groupId>
  <artifactId>minio</artifactId>
  <version>${minio.version}</version>
</dependency>
```

- [ ] **Step 2: Add storage config**

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

Keep framework multipart limits slightly above the domain limit:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: ${AICOSTOPS_MULTIPART_MAX_FILE_SIZE:520MB}
      max-request-size: ${AICOSTOPS_MULTIPART_MAX_REQUEST_SIZE:525MB}
```

- [ ] **Step 3: In `application-test-defaults.yml`, also set**

```yaml
aicostops:
  ingestion:
    worker-enabled: false
```

This prevents async races in all ordinary integration tests.

- [ ] **Step 4: Update Compose MinIO image** to `minio/minio:RELEASE.2025-10-15T17-29-55Z`; keep existing endpoint/credential/bucket env wiring.

- [ ] **Step 5: Define port without MinIO types**

```java
void put(String objectKey, Path file, long sizeBytes, String sha256);
Optional<StoredObjectMetadata> stat(String objectKey);
InputStream open(String objectKey);
```

`StoredObjectMetadata` carries exact size and explicit SHA-256 user metadata. ETag is never the Evidence checksum contract.

- [ ] **Step 6: Create `MinioContainerSupport extends MySqlContainerSupport`**

It starts a `GenericContainer` for MinIO and registers dynamic storage endpoint/credentials/bucket properties. Tests needing MySQL+MinIO extend this one class; tests needing MySQL only keep using `MySqlContainerSupport`.

- [ ] **Step 7: Write RED/GREEN real MinIO test**

Test put/stat/open exact bytes and metadata.

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=MinioObjectStorageAdapterIntegrationTest verify
```

- [ ] **Step 8: Implement lazy bucket initialization**

On first actual storage operation only:

```text
if not initialized -> bucketExists
missing + autoCreate=true -> makeBucket
missing + autoCreate=false -> dependency failure
success -> memoize initialized
failure -> do not memoize; a later operation may retry
```

No MinIO calls during bean construction/startup.

- [ ] **Step 9: Regression proof that M1 boots without MinIO**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=M1SchemaIntegrationTest verify
```

Expected: PASS without attempting `localhost:9000`.

- [ ] **Step 10: Validate/commit**

```powershell
Set-Location E:\AI-CostOps
docker compose config --quiet
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd -B -Dgroups=integration -Dit.test=MinioObjectStorageAdapterIntegrationTest verify
git add pom.xml src/main/resources/application.yml src/test/resources/application-test-defaults.yml ..\compose.yaml ..\.env.example src/main/java/com/aicostops/evidence src/test/java/com/aicostops/testsupport/MinioContainerSupport.java src/test/java/com/aicostops/evidence/infrastructure/MinioObjectStorageAdapterIntegrationTest.java
git commit -m "feat(storage): add S3-compatible evidence storage"
```

---

### Task 4: Implement streaming Evidence upload, dedup, and storage recovery

**Files:** `EvidenceUploadStager`, `EvidenceStorageService`, `ProblemCode`, stager unit test, Evidence storage integration test.

- [ ] **Step 1: RED unit tests** for known SHA, temp cleanup, `limit+1` rejection, and no whole-file API.

```powershell
.\mvnw.cmd -B -Dtest=EvidenceUploadStagerTest test
```

- [ ] **Step 2: Implement bounded staging** using a fixed ~64 KiB buffer, `MessageDigest("SHA-256")`, explicit byte counter, and deterministic cleanup. Add `EVIDENCE_TOO_LARGE`, HTTP 413. Never use `readAllBytes()` or `MultipartFile.getBytes()`.

- [ ] **Step 3: RED integration tests** for:

```text
same org/same bytes -> same Evidence id
same bytes/different org -> different Evidence id/object namespace
storage put sees TransactionSynchronizationManager.isActualTransactionActive()==false
STAGING + matching existing object -> AVAILABLE repair
mismatched size/SHA metadata at deterministic key -> conflict, never overwrite
late failure cannot downgrade AVAILABLE
```

- [ ] **Step 4: Implement deterministic key**

```text
org/{orgId}/evidence/sha256/{sha[0..1]}/{sha256}
```

Flow:

```text
stage bytes
-> short DB reserve/reuse
-> AVAILABLE? return
-> stat object
-> matching object? short DB mark AVAILABLE
-> otherwise put outside DB tx
-> short DB mark AVAILABLE
-> storage failure: mark FAILED unless already AVAILABLE
-> always remove temp file
```

- [ ] **Step 5: Verify/commit**

```powershell
.\mvnw.cmd -B -Dtest=EvidenceUploadStagerTest test
.\mvnw.cmd -B -Dgroups=integration -Dit.test=EvidenceStorageServiceIntegrationTest verify
git add src/main/java/com/aicostops/evidence src/main/java/com/aicostops/shared/web/ProblemCode.java src/test/java/com/aicostops/evidence
git commit -m "feat(evidence): add resilient streaming upload"
```

---

### Task 5: Add authorized raw Evidence download

**Files:** `EvidenceDownloadService`, `EvidenceController`, `SecurityConfiguration`, `EvidenceDownloadApiIntegrationTest`.

**Route:** `GET /api/v1/evidence/{id}/download`.

**Authorization:** `EVIDENCE_DOWNLOAD` + ORG scope. Cross-org/nonexistent = 404. Existing Evidence not `AVAILABLE` = `409 STATE_CONFLICT` and no object stream is opened.

- [ ] **Step 1: RED API tests**

```text
Finance Reviewer + permission + ORG -> 200 exact bytes
same permission but only non-ORG scope -> 403
missing permission -> 403
cross-org -> 404
STAGING/FAILED -> 409 STATE_CONFLICT
```

- [ ] **Step 2: Implement service** using `AuthorizationContextService` + `M1AuthorizationService.requireOrg(context,"EVIDENCE_DOWNLOAD")`, then org-scoped Evidence lookup, state check, and object open.

- [ ] **Step 3: Implement streaming controller** with `StreamingResponseBody`; close stream deterministically and sanitize Content-Disposition filename. Never expose object key.

- [ ] **Step 4: Add only the exact authenticated matcher** and retain final `.anyRequest().denyAll()`.

- [ ] **Step 5: Verify/commit**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=EvidenceDownloadApiIntegrationTest verify
git add src/main/java/com/aicostops/evidence src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java src/test/java/com/aicostops/evidence/api/EvidenceDownloadApiIntegrationTest.java
git commit -m "feat(evidence): add authorized evidence download"
```

---

### Task 6: Add Provider Account directory and explicit ProviderAdapter registry

**Files:** organization read port/snapshot/adapter; ProviderSource/Adapter/Registry/Sink and inspection/record/issue value types; registry unit test.

**Adapter interface:**

```java
public interface ProviderAdapter {
    String providerCode();
    String parserVersion();
    InspectionResult inspect(ProviderSource source);
    void parse(ProviderSource source, InspectionResult inspection, ProviderRecordSink sink);
    NormalizedProviderRecord normalize(ParsedProviderRecord record);
}
```

- [ ] **Step 1: RED registry tests** for canonical lookup, unknown provider, and duplicate registration. Canonical code is `trim().toUpperCase(Locale.ROOT)`.

- [ ] **Step 2: Implement registry** as immutable explicit map. Duplicate canonical code fails construction; unknown code returns stable 400 validation behavior. Empty production list is allowed in Group 1. Never register synthetic adapters in `src/main`.

- [ ] **Step 3: Define inspection contract**

```text
detectedProviderCode
schemaFingerprint
compatible
issues[]
```

Fingerprint is SHA-256 of a canonical schema descriptor, not file bytes/row values.

- [ ] **Step 4: Define ProviderSource** so every `openStream()` opens a fresh object stream; inspect and parse never share an exhausted InputStream.

- [ ] **Step 5: Implement `ProviderAccountDirectory.findActive(orgId,id)`** with SQL scoped by id+org and status `ACTIVE`; return only snapshot fields ingestion needs.

- [ ] **Step 6: Verify/commit**

```powershell
.\mvnw.cmd -B -Dtest=ProviderAdapterRegistryTest test
git add src/main/java/com/aicostops/organization src/main/java/com/aicostops/ingestion/application src/test/java/com/aicostops/ingestion/application/ProviderAdapterRegistryTest.java
git commit -m "feat(import): add provider adapter registry"
```

---

### Task 7: Add Import persistence and idempotent provider-import creation

**Files:** ingestion enums/records, `ImportBatchMapper`, `ImportAttemptMapper`, Provider import service/controller/response, security matcher, API integration test.

**Source type:**

```java
FILE_EXPORT,
USAGE_API_JSON,
COSTS_API_JSON
```

**Route:** `POST /api/v1/provider-imports` multipart `file`, `providerAccountId`, `sourceType`.

**Authorization:** `EVIDENCE_UPLOAD_PROVIDER` + ORG scope.

- [ ] **Step 1: RED API tests with a test-only `TEST_PROVIDER` adapter**

```text
new context -> Evidence + Batch + Attempt #1 QUEUED
same bytes/account/source/parser -> same Evidence/Batch/latest Attempt; no new Attempt
same bytes + different Provider Account -> same Evidence, different Batch
permission but non-ORG scope -> 403
inactive/cross-org Provider Account -> 404
unsupported provider -> 400 and no ImportBatch
missing upload permission -> 403
```

Because test defaults disable worker, these tests can deterministically assert `QUEUED`.

- [ ] **Step 2: Implement Batch identity transaction**

```text
lock/find-or-insert Batch identity
new Batch -> insert Attempt #1, INITIAL, QUEUED
existing Batch -> return latest Attempt, no retry
```

Any future Attempt creation path locks parent `import_batch` and verifies there is no `QUEUED`/`RUNNING` Attempt before assigning `attempt_no`.

- [ ] **Step 3: Implement request order**

```text
auth + require ORG/EVIDENCE_UPLOAD_PROVIDER
-> ACTIVE provider account
-> registry resolve adapter/parserVersion
-> store/reuse Evidence
-> create/reuse Batch
-> response
```

Resolve unsupported provider before expensive file storage when possible. Group 1 production therefore returns 400 for real providers until Group 2 registers their adapters.

- [ ] **Step 4: Verify/commit**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=ProviderImportApiIntegrationTest verify
git add src/main/java/com/aicostops/ingestion src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java src/test/java/com/aicostops/ingestion/api/ProviderImportApiIntegrationTest.java
git commit -m "feat(import): add idempotent provider import creation"
```

---

### Task 8: Implement MySQL claim, heartbeat, and lease fencing

**Files:** `ImportLeaseService`, `ImportAttemptMapper`, worker properties, lease integration test.

**Produces:** `claimNext(workerId)`, `heartbeat(...)`, fenced ownership verification.

- [ ] **Step 1: RED dual-worker tests** prove one queued job cannot be claimed twice and two queued jobs can be claimed by two workers without blocking each other.

- [ ] **Step 2: Implement claim in one short transaction**

```sql
SELECT ...
FROM import_attempt ia
JOIN import_batch ib ON ib.id=ia.import_batch_id
WHERE ia.status='QUEUED'
  AND ia.available_at <= UTC_TIMESTAMP(6)
ORDER BY ia.available_at,ia.id
FOR UPDATE SKIP LOCKED
LIMIT 1;
```

Then set Attempt `RUNNING`, owner, `lease_until=TIMESTAMPADD(MICROSECOND,:leaseMicros,UTC_TIMESTAMP(6))`, increment `lease_version`, set `started_at`, and parent Batch `PROCESSING` before commit.

- [ ] **Step 3: Implement heartbeat conditional update** requiring id, `RUNNING`, owner, exact lease version, and unexpired DB-time lease. Zero rows = lease lost.

- [ ] **Step 4: RED/GREEN stale-owner tests** prove stale owner/version cannot heartbeat or finalize.

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=ImportLeaseServiceIntegrationTest verify
```

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/aicostops/ingestion src/test/java/com/aicostops/ingestion/application/ImportLeaseServiceIntegrationTest.java
git commit -m "feat(import): add fenced MySQL import leases"
```

---

### Task 9: Implement lease-expiry crash recovery

**Files:** extend lease service, attempt/batch mappers, lease integration test.

- [ ] **Step 1: RED recovery tests**

```text
expired RUNNING -> old FAILED(WORKER_LEASE_EXPIRED) + successor QUEUED
successor attempt_no=N+1, trigger=LEASE_RECOVERY, predecessor=old id
Batch -> PENDING
old raw rows remain
3 recovery Attempts exhausted -> no successor; Batch FAILED
nonexpired RUNNING untouched
concurrent recovery workers -> exactly one successor
```

- [ ] **Step 2: Implement recovery query**

```sql
WHERE status='RUNNING'
  AND lease_until < UTC_TIMESTAMP(6)
ORDER BY lease_until,id
FOR UPDATE SKIP LOCKED
LIMIT 1
```

Lock parent Batch before successor insertion, verify no active Attempt, and count existing `LEASE_RECOVERY` attempts against max 3.

- [ ] **Step 3: Verify/commit**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=ImportLeaseServiceIntegrationTest verify
git add src/main/java/com/aicostops/ingestion src/test/java/com/aicostops/ingestion/application/ImportLeaseServiceIntegrationTest.java
git commit -m "feat(import): add import lease crash recovery"
```

---

### Task 10: Implement fenced bounded RawProviderRecord / ImportIssue persistence

**Files:** raw/issue mappers and records, `ImportRawPersistenceService`, executor integration test.

- [ ] **Step 1: RED tests**

```text
500 records -> one atomic persistence batch
501 -> second bounded transaction
stale lease -> zero inserts
partial rows survive later Attempt failure
secret-like raw values are redacted before DB
WARN/ERROR counters exact
```

- [ ] **Step 2: Implement recursive redaction** before JSON serialization. Normalize key names and redact fragments including `password`, `token`, `secret`, `apikey`/`api_key`, `authorization`. Do not log the rejected value.

- [ ] **Step 3: Implement fenced transaction**

Each bounded transaction first locks/verifies Attempt ownership `(attemptId,owner,leaseVersion,lease_until>DB_NOW)`, then inserts rows/issues and updates counters in the same transaction.

- [ ] **Step 4: Verify/commit**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=ImportAttemptExecutorIntegrationTest verify
git add src/main/java/com/aicostops/ingestion src/test/java/com/aicostops/ingestion/application/ImportAttemptExecutorIntegrationTest.java
git commit -m "feat(import): add fenced raw record persistence"
```

---

### Task 11: Implement adapter execution and Spring TaskExecutor worker lifecycle

**Files:** `ImportAttemptExecutor`, `ImportWorkerCoordinator`, worker config/properties, executor integration test, coordinator integration test, application/test config.

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

Keep test-default `worker-enabled:false` from Task 3.

- [ ] **Step 2: Configure `@EnableScheduling` + bounded `ThreadPoolTaskExecutor`**

Core/max = configured concurrency, no unbounded queue. Coordinator bean is conditional on `worker-enabled=true`. Acquire a local `Semaphore` **before** DB claim so executor saturation cannot strand a claimed Attempt. Use a separate Spring `TaskScheduler` for heartbeat.

- [ ] **Step 3: Implement coordinator `pollOnce()`**

Deterministic production/test entry point:

```text
recover at most one expired Attempt
if local permit unavailable -> return
claim next queued Attempt
none -> release permit
claimed -> submit executor task
executor finally -> release permit
```

The scheduled method delegates to `pollOnce()`. Tests call `pollOnce()` directly; they do not use arbitrary `Thread.sleep` to wait for a scheduler tick.

- [ ] **Step 4: Implement Attempt execution**

```text
Evidence-backed ProviderSource
-> inspect
-> persist inspection WARN/ERROR
-> incompatible/ERROR -> fenced fail Attempt + Batch
-> parse streaming records
-> adapter.normalize(record)
-> redact
-> flush <=500 records through fenced persistence
-> final flush
-> error_count>0 -> FAILED/FAILED
-> no ERROR -> SUCCEEDED/PARSED
```

Persist `parserVersion`, detected provider code, and schema fingerprint on the Attempt.

- [ ] **Step 5: Heartbeat guard**

Schedule renewal every 20s. Renewal failure marks local lease-lost; sink/executor stops further processing and never finalizes success. The stale worker does not create a recovery Attempt itself.

- [ ] **Step 6: Executor integration tests with synthetic adapters**

```text
WARN-only -> Attempt SUCCEEDED, Batch PARSED
unknown/incompatible schema -> FAILED/FAILED with ERROR issue
late parse error -> partial raw rows retained
schemaFingerprint/parserVersion persisted
```

- [ ] **Step 7: Coordinator integration test**

Override `aicostops.ingestion.worker-enabled=true`, register synthetic adapter, insert one queued Attempt, call `pollOnce()` directly, and use a `CountDownLatch` from the synthetic adapter to prove TaskExecutor dispatch. Assert final state after latch completion; no timing sleeps.

- [ ] **Step 8: Verify/commit**

```powershell
.\mvnw.cmd -B -Dgroups=integration -Dit.test=ImportAttemptExecutorIntegrationTest,ImportWorkerCoordinatorIntegrationTest verify
git add src/main/java/com/aicostops/ingestion src/main/resources/application.yml src/test/resources/application-test-defaults.yml src/test/java/com/aicostops/ingestion
git commit -m "feat(import): add recoverable import worker execution"
```

---

### Task 12: Enforce M2 module boundaries with ArchUnit

**File:** `ModuleDependencyArchitectureTest.java`.

- [ ] **Step 1: Add rules**

```text
evidence.. must not depend on ingestion..
ingestion.. must not depend on ledger..
ingestion.. must not depend on budget..
ingestion.. must not depend on attribution..
ingestion.. must not depend on reporting..
```

Do not prohibit `ingestion -> evidence` or `ingestion -> organization`.

- [ ] **Step 2: Verify/commit**

```powershell
.\mvnw.cmd -B -Dgroups=architecture test
git add src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java
git commit -m "test(architecture): enforce evidence ingestion boundaries"
```

---

### Task 13: Full acceptance, documentation, and evidence

**Files:** data model/config docs, new M2 API doc, new acceptance evidence doc.

- [ ] **Step 1: Update data-model doc** to actual V4/V5 implementation: provider account included in Batch identity, split queue/lease indexes, Batch `PARSED`, Attempt lease/fencing fields, M2/M3 boundary.

- [ ] **Step 2: Create `03-m2-evidence-import-api.md`** documenting only:

```text
POST /api/v1/provider-imports          EVIDENCE_UPLOAD_PROVIDER / ORG
GET  /api/v1/evidence/{id}/download    EVIDENCE_DOWNLOAD / ORG
```

State that List/Detail/Retry/Cancel are AIC-030, Confirm is M3, and Group 1 has no production provider adapters.

- [ ] **Step 3: Backend unit suite**

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd -B -DexcludedGroups=architecture,integration test
```

Expected: BUILD SUCCESS.

- [ ] **Step 4: Full backend integration suite**

```powershell
.\mvnw.cmd -B -Dgroups=integration verify
```

Expected: BUILD SUCCESS, including all existing M1 regressions plus new M2 MySQL/MinIO tests.

- [ ] **Step 5: Architecture suite**

```powershell
.\mvnw.cmd -B -Dgroups=architecture test
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Docker/Compose validation**

```powershell
Set-Location E:\AI-CostOps
docker compose config --quiet
docker build --tag ai-costops-backend:m2-foundation backend
```

Expected: exit 0.

- [ ] **Step 7: Record real acceptance evidence** in `11-m2-evidence-import-foundation-evidence.md` using actual test counts/output. Include evidence for same-SHA dedup, MinIO failure/recovery, unrelated context boot without MinIO, auth download, no long DB tx during object I/O, dual worker claim, lease expiry/recovery/fencing, unknown schema, parser/fingerprint lineage, WARN/ERROR behavior, and test isolation.

- [ ] **Step 8: Diff/secret hygiene**

```powershell
git status --short
git diff --check
git diff --stat main...HEAD
git grep -n -I -E "sk-[A-Za-z0-9_-]{16,}|BEGIN (RSA|OPENSSH|EC) PRIVATE KEY" -- . ':!docs/superpowers'
```

Expected: `git diff --check` empty; no real-secret hit.

- [ ] **Step 9: Commit docs/evidence**

```powershell
git add docs
git commit -m "docs(m2): record evidence import foundation contracts"
```

- [ ] **Step 10: Final branch status**

```powershell
git status --short
git log --oneline --decorate main..HEAD
```

Expected: clean tree and focused commits.

---

## Formal PR after all tasks pass

Push:

```powershell
Set-Location E:\AI-CostOps
git push
```

PR title/body closing targets:

```text
feat(m2): establish evidence and import foundation

Closes #29
Closes #30
Closes #31
Closes #32
```

Required checks before merge:

```text
backend-unit
backend-integration
backend-architecture
frontend-lint
frontend-test
frontend-build
docker-build
```

Never disable/bypass checks to merge. Merge remains squash-only and requires explicit user authorization.

## Final review gates

```text
[ ] no canonical cost tables/types
[ ] no READY_FOR_REVIEW/Confirm implementation
[ ] no production provider adapter in Group 1
[ ] org-scoped Evidence dedup only
[ ] duplicate upload never implicitly retries
[ ] MinIO/object I/O outside DB transaction
[ ] MinIO initialization is lazy; M1 contexts do not require MinIO
[ ] deterministic-key mismatch never overwrites bytes
[ ] upload/download require permission + ORG scope
[ ] test worker disabled by default
[ ] M2 tests clean shared DB before and after
[ ] at most one QUEUED/RUNNING Attempt per Batch
[ ] claim uses real MySQL FOR UPDATE SKIP LOCKED
[ ] lease expiry uses DB UTC time
[ ] stale lease cannot persist/finalize
[ ] crash recovery creates new Attempt and is bounded
[ ] failed Attempt raw rows/issues retained
[ ] raw payload redacted before persistence
[ ] Adapter registry explicit and duplicate-safe
[ ] schema fingerprint is schema-derived, not file SHA
[ ] final security matcher still denyAll
[ ] Finance Reviewer gains Provider Account read only, not manage
[ ] all seven required checks green before merge
```
