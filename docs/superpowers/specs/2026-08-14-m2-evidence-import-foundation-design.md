# M2 Evidence & Import Foundation Design

> Date: 2026-08-14  
> Scope: AIC-021, AIC-022, AIC-023, AIC-024  
> Delivery group: M2 Group 1 — Evidence & Import Foundation  
> Planned implementation branch: `feat/m2-evidence-import-foundation`

## 1. Purpose

M2 Group 1 establishes the durable foundation for provider evidence storage and recoverable provider-data ingestion.

The group must make it possible to:

```text
Finance user
→ upload provider evidence
→ persist/reuse immutable Evidence identity
→ persist/reuse ImportBatch identity
→ enqueue ImportAttempt for a newly created Batch
→ claim work through MySQL
→ inspect provider schema
→ parse and provider-normalize records
→ retain RawProviderRecord + ImportIssue lineage
→ finish at PARSED or FAILED
```

This group does **not** create canonical cost facts, perform duplicate cost review, allocate costs, post ledger entries, or finalize Import Confirm.

The approved architecture remains:

```text
Spring Boot modular monolith
MySQL = source of truth
Redis = runtime acceleration only
MinIO/S3 = evidence object storage
Plain MyBatis = explicit persistence SQL
Spring TaskExecutor = V1 import execution
```

## 2. Existing Boundaries Preserved

The existing module split is retained.

### `evidence`

Owns:

```text
Evidence metadata
Checksum / byte identity
ObjectStoragePort
MinIO adapter
Storage lifecycle
Evidence download authorization hook
```

It does not understand provider rows, ImportBatch, ImportAttempt, or canonical cost.

### `ingestion`

Owns:

```text
ImportBatch
ImportAttempt
RawProviderRecord
ImportIssue
DB-backed worker
ProviderAdapter registry
Schema inspection
Parse orchestration
Provider-side intermediate normalization
```

It may depend on `evidence` and read-only organization/provider-account ports. Provider adapters must not depend on ledger, budget, attribution, or reporting.

### M2/M3 hard boundary

M2 ends at:

```text
Evidence
→ ImportBatch
→ ImportAttempt
→ RawProviderRecord / ImportIssue
→ Batch PARSED
```

M3 owns:

```text
RawProviderRecord
→ canonical cost facts
→ READY_FOR_REVIEW
→ Confirm
```

Therefore M2 must not create:

```text
external_document
consumption_fact
pricing_fact
charge_fact
attribution_hint
```

and must not treat `PARSED` as equivalent to canonical review readiness.

## 3. Main Data Flow

```text
POST provider import
        │
        ▼
stream request to temporary file
+ enforce upload limit
+ compute SHA-256
        │
        ▼
reserve/reuse Evidence row
        │
        ▼
store/recover object in MinIO/S3
        │
        ▼
Evidence AVAILABLE
        │
        ▼
resolve expected ProviderAdapter + parserVersion
        │
        ▼
create/reuse ImportBatch
        │
        ├── existing Batch → return existing state; no implicit re-execution
        │
        └── new Batch
              │
              ▼
        create Initial ImportAttempt QUEUED
              │
              ▼
        short MySQL claim transaction
              │
              ▼
        ImportAttempt RUNNING + lease
              │
              ▼
        ProviderAdapter.inspect()
              │
              ▼
        schema fingerprint / provider detection
              │
              ▼
        ProviderAdapter.parse() as stream
              │
              ▼
        provider-side normalize()
              │
              ▼
        bounded, fenced persistence transactions
        RawProviderRecord + ImportIssue
              │
              ├── ERROR exists → Attempt FAILED / Batch FAILED
              │
              └── no ERROR     → Attempt SUCCEEDED / Batch PARSED
```

## 4. Evidence Identity and Storage

### 4.1 Evidence is an immutable byte identity

The natural identity is:

```text
UQ(org_id, sha256)
```

Within one organization, uploading the same bytes reuses the same Evidence row.

Across organizations, no physical deduplication is attempted. This preserves tenant isolation even when two organizations upload identical bytes.

### 4.2 Evidence schema

The implementation should preserve the existing baseline fields and add explicit storage lifecycle state:

```text
evidence
-------
id BIGINT PK
org_id BIGINT NOT NULL
sha256 CHAR(64) NOT NULL
object_key VARCHAR(...) NOT NULL
original_filename VARCHAR(...) NOT NULL
media_type VARCHAR(...) NULL
size_bytes BIGINT NOT NULL
uploaded_by_member_id BIGINT NOT NULL
storage_status VARCHAR(32) NOT NULL
storage_error_code VARCHAR(...) NULL
created_at DATETIME(6) NOT NULL
updated_at DATETIME(6) NOT NULL

UQ(org_id, sha256)
FK org_id -> organization
FK uploaded_by_member_id -> organization_member
```

Storage state:

```text
STAGING
AVAILABLE
FAILED
```

`sha256`, `object_key`, and `size_bytes` define the stored byte identity and are not mutated after the object is accepted.

### 4.3 Upload strategy

Do not hold a database transaction open while receiving or uploading the file.

Approved sequence:

```text
HTTP stream
→ bounded-buffer temporary file
→ compute SHA-256 and size
→ short DB reservation/reuse transaction
→ commit
→ object-storage operation without DB transaction
→ short DB finalize transaction
→ delete local temporary file in success/failure cleanup
```

A complete file must never be loaded into JVM heap with `readAllBytes()` or an equivalent whole-file API.

Temporary files must be deleted in deterministic cleanup paths. Process-level stale temp cleanup may be added only if implementation evidence shows that normal `finally` cleanup is insufficient.

### 4.4 Upload size limit

The storage service exposes a configurable hard upload limit.

Initial V1 default:

```text
512 MiB
```

This is a safety limit, not a benchmark claim. M8 import benchmarking may justify changing it later.

### 4.5 Deterministic object key

Use a tenant-scoped deterministic key derived from organization and checksum, for example:

```text
org/{orgId}/evidence/sha256/{sha-prefix}/{sha256}
```

Do not place original filename, user email, provider account reference, API key, or another sensitive identifier in the object key.

### 4.6 DB/object-storage failure handling

Object storage and MySQL are not treated as one distributed transaction.

If an Evidence row is `STAGING` and the deterministic object already exists with matching expected size and explicit SHA-256 object metadata, a later request may repair the row to `AVAILABLE`.

Do not use S3/MinIO ETag as the authoritative SHA-256 check because multipart/object-storage ETag semantics are not the Evidence checksum contract.

If the object does not exist, the upload may be retried to the same deterministic key.

If storage fails, the Evidence row becomes `FAILED` with a bounded non-secret error code/summary. The original provider file contents or credentials must not be copied into logs or database error fields.

Concurrent duplicate uploads converge on the same `(org_id, sha256)` Evidence identity. Duplicate-key races are an expected idempotency path, not an exceptional data-loss condition. Concurrent writes to the same deterministic object key are safe only because they are required to represent the same verified SHA-256 bytes.

## 5. Provider Account Context and ImportBatch Identity

### 5.1 Provider imports require a Provider Account

For M2 provider imports, `provider_account_id` is required and must refer to an ACTIVE Provider Account in the current organization.

The Provider Account supplies the expected provider identity. Its provider code is snapshotted into the batch as `expected_provider_code` so later provider-account metadata changes cannot rewrite historical import context.

### 5.2 Adapter/parser resolution precedes Batch creation

The expected provider code selects an explicitly registered ProviderAdapter. The adapter supplies the `parserVersion` used in Batch identity.

If no adapter is registered for the expected provider code, provider import creation fails explicitly rather than creating a Batch with an unknown parser contract.

Group 1 may exercise this path with synthetic test adapters; production provider adapters arrive in Group 2.

### 5.3 ImportBatch identity

A batch means one stable interpretation context for one Evidence object.

The batch is reused only when all of the following match:

```text
evidence_id
provider_account_id
source_type
parser_version
```

Therefore the intended uniqueness is:

```text
UQ(evidence_id, provider_account_id, source_type, parser_version)
```

This refines the earlier baseline that did not include `provider_account_id`.

Rationale: the same bytes may have been associated with the wrong Provider Account previously. Re-associating the evidence with a different Provider Account must be representable without duplicating Evidence bytes.

A parser version is immutable for a Batch. If provider parsing behavior changes in a way that requires a new `parserVersion`, processing the same Evidence under that new parser creates/reuses a **different ImportBatch**. Existing Batch/Attempt history is never rewritten to a new parser version.

### 5.4 Reusing an existing Batch is not a retry

If an upload resolves to an existing Batch with the same identity, creation returns that existing Batch and its latest Attempt state. It does **not** create a new Attempt merely because the user uploaded the same file again.

Execution retry is represented explicitly by a new ImportAttempt. Manual retry is implemented in AIC-030.

This keeps:

```text
same upload request / same interpretation context = idempotent lookup
retry execution                            = explicit new Attempt
```

### 5.5 ImportBatch schema direction

```text
import_batch
------------
id BIGINT PK
org_id BIGINT NOT NULL
evidence_id BIGINT NOT NULL
provider_account_id BIGINT NOT NULL
expected_provider_code VARCHAR(100) NOT NULL
source_type VARCHAR(64) NOT NULL
parser_version VARCHAR(...) NOT NULL
status VARCHAR(32) NOT NULL
period_start DATETIME(6) NULL
period_end DATETIME(6) NULL
created_by_member_id BIGINT NOT NULL
created_at DATETIME(6) NOT NULL
updated_at DATETIME(6) NOT NULL

UQ(evidence_id, provider_account_id, source_type, parser_version)
FK org_id -> organization
FK evidence_id -> evidence
FK provider_account_id -> provider_account
FK created_by_member_id -> organization_member
```

Cross-organization consistency between the batch, Evidence, Provider Account, and member is enforced by application-level organization-scoped queries in addition to foreign keys.

### 5.6 Batch state

M2 batch state:

```text
PENDING
PROCESSING
PARSED
FAILED
CANCELED
```

Meaning:

- `PENDING`: one queued execution exists or recovery has queued a new execution.
- `PROCESSING`: a valid worker lease owns the current execution.
- `PARSED`: provider parsing and intermediate normalization completed without ERROR issues.
- `FAILED`: the latest execution ended in a non-recovering failure or automatic recovery budget was exhausted.
- `CANCELED`: the workflow was explicitly canceled before M3 confirmation.

`PARSED` is intentionally not named `READY_FOR_REVIEW`.

## 6. ImportAttempt as Immutable Execution History

### 6.1 Core rule

Retry and recovery never rewrite a failed Attempt into success.

Each real execution gets a new Attempt:

```text
Batch #10
├─ Attempt #1 FAILED
└─ Attempt #2 SUCCEEDED
```

### 6.2 At most one active Attempt per Batch

A Batch may never have two simultaneously active executions.

At any point there is at most one Attempt in:

```text
QUEUED
or
RUNNING
```

MySQL has no convenient partial unique constraint for these status values, so Attempt creation/recovery/manual retry must serialize on the `import_batch` row with a short row-locking transaction. The transaction verifies there is no existing QUEUED/RUNNING Attempt before assigning the next `attempt_no` and inserting the new Attempt.

This rule is covered by integration tests, including concurrent creation/recovery paths.

### 6.3 Attempt schema direction

```text
import_attempt
--------------
id BIGINT PK
import_batch_id BIGINT NOT NULL
attempt_no INT NOT NULL
status VARCHAR(32) NOT NULL
trigger_type VARCHAR(32) NOT NULL
predecessor_attempt_id BIGINT NULL
available_at DATETIME(6) NOT NULL
lease_owner VARCHAR(...) NULL
lease_until DATETIME(6) NULL
lease_version BIGINT NOT NULL DEFAULT 0
parser_version VARCHAR(...) NOT NULL
detected_provider_code VARCHAR(100) NULL
schema_fingerprint CHAR(64) NULL
started_at DATETIME(6) NULL
finished_at DATETIME(6) NULL
error_code VARCHAR(...) NULL
error_summary VARCHAR(...) NULL
records_seen BIGINT NOT NULL DEFAULT 0
records_valid BIGINT NOT NULL DEFAULT 0
warning_count BIGINT NOT NULL DEFAULT 0
error_count BIGINT NOT NULL DEFAULT 0
created_at DATETIME(6) NOT NULL

UQ(import_batch_id, attempt_no)
IDX(status, available_at, lease_until)
FK import_batch_id -> import_batch
FK predecessor_attempt_id -> import_attempt
```

`parser_version` is copied onto each Attempt even though it is part of batch identity, because execution lineage must remain self-describing.

### 6.4 Attempt states

```text
QUEUED
RUNNING
SUCCEEDED
FAILED
CANCELED
```

Trigger types:

```text
INITIAL
LEASE_RECOVERY
MANUAL_RETRY
```

`MANUAL_RETRY` is stored by the schema now, but the HTTP retry workflow is implemented in AIC-030.

## 7. Worker Claim, Lease, and Recovery

### 7.1 Claim is a short transaction

The claim SQL must be explicit and reviewed in MyBatis infrastructure.

Conceptually:

```sql
BEGIN;

SELECT ...
FROM import_attempt
WHERE status = 'QUEUED'
  AND available_at <= UTC_TIMESTAMP(6)
ORDER BY available_at, id
FOR UPDATE SKIP LOCKED
LIMIT 1;

UPDATE import_attempt
SET status = 'RUNNING',
    lease_owner = :worker,
    lease_until = :leaseUntil,
    lease_version = lease_version + 1,
    started_at = COALESCE(started_at, UTC_TIMESTAMP(6))
WHERE id = :id;

UPDATE import_batch
SET status = 'PROCESSING', updated_at = UTC_TIMESTAMP(6)
WHERE id = :batchId;

COMMIT;
```

File download, schema inspection, parsing, and record persistence happen after the claim transaction has committed.

### 7.2 Database time is the lease clock

Lease eligibility and expiration use MySQL UTC time (`UTC_TIMESTAMP(6)` or a tested equivalent) rather than comparing independent application-node clocks.

Application `Clock` remains appropriate for ordinary business timestamps, but distributed lease correctness is based on the database clock.

### 7.3 Lease fencing

`lease_owner` alone is insufficient because an old worker can wake up after another worker has recovered the job.

Each claim increments `lease_version`.

A worker may finalize or perform lease-sensitive updates only when the expected tuple still matches:

```text
attempt_id
status = RUNNING
lease_owner
lease_version
lease not expired
```

If the conditional write affects zero rows, the worker has lost ownership and must stop persisting/finalizing that execution.

### 7.4 Raw-record persistence is fenced too

Lease fencing applies not only to final status writes but also to every bounded RawProviderRecord/ImportIssue persistence transaction.

Each bounded persistence transaction must first lock/validate the owning Attempt using the expected owner/version and non-expired lease, then insert the records/issues and update counters in that same short transaction.

Recovery also locks the Attempt row before expiring it. Therefore an old worker cannot insert new raw rows after a recovery worker has invalidated its lease.

### 7.5 Lease and heartbeat defaults

Initial configurable defaults:

```text
lease duration: 60 seconds
heartbeat interval: 20 seconds
```

These are conservative operational defaults, not performance conclusions.

Heartbeat renewal must itself be a short transaction.

### 7.6 Crash recovery creates a new Attempt

A stale `RUNNING` Attempt is never reclaimed in place.

Recovery itself uses a short locking transaction and conceptually performs:

```text
expired RUNNING Attempt
→ lock/verify expiry
→ mark old Attempt FAILED
   error_code = WORKER_LEASE_EXPIRED
→ create next Attempt QUEUED
   trigger_type = LEASE_RECOVERY
   predecessor_attempt_id = expired attempt
→ Batch PENDING
```

Automatic lease recovery is bounded to a configurable maximum, initially 3 `LEASE_RECOVERY` Attempts for the same batch. Once exhausted, the Batch remains `FAILED` for later manual retry.

This prevents infinite retry loops for permanent bugs.

### 7.7 Retry classification

Automatic recovery is appropriate for execution loss such as worker crash or transient infrastructure interruption.

Schema/data failures do not auto-retry indefinitely:

```text
unknown schema
missing required field
malformed provider export
provider/data ERROR
```

These end the Attempt and Batch as `FAILED` so a user can review the issue or retry after input/parser changes.

Manual retry in AIC-030 reruns the same Batch/parser contract and therefore creates a new Attempt. A parser-version change is not a manual retry of the old Batch; it creates/reuses the Batch identity for the new parser version.

## 8. RawProviderRecord Persistence

### 8.1 Raw record role

`raw_provider_record` is an ingestion intermediate, not a canonical financial fact.

Direction:

```text
raw_provider_record
-------------------
id BIGINT PK
import_attempt_id BIGINT NOT NULL
record_index BIGINT NOT NULL
record_locator VARCHAR(...) NOT NULL
provider_record_key VARCHAR(...) NULL
raw_payload JSON NOT NULL
normalized_payload JSON NULL
usage_start DATETIME(6) NULL
usage_end DATETIME(6) NULL
normalize_status VARCHAR(32) NOT NULL
created_at DATETIME(6) NOT NULL

UQ(import_attempt_id, record_index)
FK import_attempt_id -> import_attempt
```

Examples of `record_locator`:

```text
cost.csv:row=12
Model usage detail!row=4
$.data[10]
```

### 8.2 Bounded persistence

Adapters stream records. Ingestion persists records in bounded batches rather than holding a complete provider export in memory.

Initial configurable persistence batch size:

```text
500 records
```

Each persistence batch uses its own short, lease-fenced DB transaction.

If a later batch fails, already-persisted rows remain attached to the failed Attempt for review. A retry gets a new Attempt and therefore cannot accidentally overwrite or mix rows from the previous execution.

### 8.3 Secret handling

The Evidence object is the authoritative raw byte source and is protected by strict download authorization.

`raw_payload` must preserve useful provider structure while redacting known secret-like fields before database persistence. Full API keys, credentials, tokens, or other forbidden secrets must not be copied into review APIs, issue rows, audit metadata, or logs.

## 9. ImportIssue

Direction:

```text
import_issue
------------
id BIGINT PK
import_attempt_id BIGINT NOT NULL
raw_provider_record_id BIGINT NULL
severity VARCHAR(16) NOT NULL
issue_code VARCHAR(...) NOT NULL
record_locator VARCHAR(...) NULL
field_name VARCHAR(...) NULL
message VARCHAR(...) NOT NULL
raw_value_masked VARCHAR(...) NULL
created_at DATETIME(6) NOT NULL

FK import_attempt_id -> import_attempt
FK raw_provider_record_id -> raw_provider_record
```

Severity:

```text
WARN
ERROR
```

Semantics:

- WARN: processing can continue and the Attempt may still succeed.
- ERROR: the Attempt cannot finish as successful provider parsing input.

An Attempt with only WARN issues may end `SUCCEEDED` and the Batch `PARSED`.

An Attempt with one or more ERROR issues ends `FAILED` and the Batch `FAILED`.

Raw records and issues from failed attempts are retained.

## 10. ProviderAdapter Contract

### 10.1 Alternatives rejected

Rejected: adapter returns all records as a `List`.

Reason: large exports can exhaust memory.

Rejected: adapter writes directly to MySQL.

Reason: provider-specific code would own persistence/transaction behavior and destroy the ingestion boundary.

Approved: streaming adapter + ingestion-owned sink/orchestrator.

### 10.2 Contract responsibilities

Conceptually:

```text
ProviderAdapter
├─ providerCode()
├─ parserVersion()
├─ inspect(source) -> InspectionResult
├─ parse(source, inspection, sink)
└─ normalize(parsedRecord) -> provider-side normalized record
```

The exact Java type names may be refined in the implementation plan, but these responsibilities are frozen.

### 10.3 Repeatable source

`inspect()` and `parse()` may need independent reads, so the contract must not pass one already-consumed InputStream through the whole pipeline.

Use a repeatable abstraction such as:

```text
ProviderSource.openStream()
```

which obtains a fresh stream from Evidence storage when needed.

### 10.4 Schema fingerprint

Evidence SHA-256 and schema fingerprint solve different problems.

```text
Evidence SHA-256
= fingerprint of full file bytes

Schema fingerprint
= SHA-256 of a canonicalized schema descriptor
```

Files with different row counts/content may therefore have different Evidence checksums but the same schema fingerprint.

The canonical schema descriptor must be deterministic: stable ordering, stable normalization of field names/types, and no row-value data that would make the fingerprint content-dependent.

`schema_fingerprint` and `detected_provider_code` are stored on the Attempt that performed inspection.

### 10.5 Registry

Adapter registration is explicit and deterministic.

Conceptually:

```text
DEEPSEEK -> DeepSeekAdapter
MIMO     -> MiMoAdapter
KIMI     -> KimiAdapter
GLM      -> GlmAdapter
OPENAI   -> OpenAiAdapter
```

Group 1 establishes the registry/framework; the actual provider adapters arrive in Group 2.

Duplicate adapter registration for one provider code is a startup/configuration error.

Unsupported provider codes fail explicitly. Unknown schema is reported as a reviewable error rather than guessed.

## 11. Meaning of M2 `normalize()`

M2 normalization is provider-side intermediate normalization only.

Allowed examples:

```text
trim provider strings
parse provider timestamp strings
structure token component fields
normalize provider-local scalar representation
redact sensitive fields
```

Forbidden in M2:

```text
create ChargeFact
create ConsumptionFact
create PricingFact
infer price from amount / quantity
invent provider formulas
invent FOCUS mappings
perform allocation
```

Cross-provider financial normalization remains M3.

## 12. Authorization and Provider Account Permission Adjustment

### 12.1 Reuse M1 authorization

Do not introduce a new authorization subsystem.

Evidence/import application services use the current model:

```text
AuthorizationContext
+ explicit application-service authorization
+ organization/scope-aware SQL
```

Existing HTTP semantics remain:

```text
missing permission -> 403
nonexistent / cross-org / outside scope -> privacy-preserving 404
invalid state -> 409
```

### 12.2 Required permissions

Relevant catalog entries already exist:

```text
EVIDENCE_UPLOAD_PROVIDER
EVIDENCE_READ
EVIDENCE_DOWNLOAD
IMPORT_READ
IMPORT_RETRY
IMPORT_CONFIRM
IMPORT_CANCEL
```

`EVIDENCE_DOWNLOAD` remains stricter than normalized cost read because raw provider evidence may contain sensitive identifiers.

### 12.3 FINANCE_REVIEWER permission gap

Current role seed gives FINANCE_REVIEWER provider-evidence/import permissions but not `PROVIDER_ACCOUNT_READ`.

M2 Group 1 adds `PROVIDER_ACCOUNT_READ` to FINANCE_REVIEWER through a forward-only migration.

It does **not** add `PROVIDER_ACCOUNT_MANAGE`.

Result:

```text
Finance Reviewer -> may select/read Provider Account for import
Finance Admin    -> may configure/manage Provider Account
```

## 13. Group 1 API Surface

Group 1 implements only the minimum API needed to create provider imports and securely retrieve Evidence.

### Provider import creation

Conceptual endpoint:

```text
POST /api/v1/provider-imports
Content-Type: multipart/form-data

file
providerAccountId
sourceType
```

The request organization always comes from the authenticated authorization context, never from a client-provided org ID.

The use case:

```text
store/reuse Evidence
→ validate Provider Account
→ resolve registered adapter/parserVersion
→ create/reuse ImportBatch
→ if Batch is new, create Initial QUEUED ImportAttempt
→ if Batch already exists, return it without implicit retry
```

Response identifies the durable resources and whether idempotent reuse occurred, for example:

```text
evidenceId
importBatchId
latestAttemptId
batchStatus
duplicateEvidence
duplicateBatch
```

Exact DTO naming is left to the implementation plan.

### Evidence download

Conceptual endpoint:

```text
GET /api/v1/evidence/{id}/download
```

Requires `EVIDENCE_DOWNLOAD` and an organization-scoped lookup before opening the object stream.

### Deferred to AIC-030

Group 1 does not implement the full import review/query workflow:

```text
Import list/detail
Attempt list/detail
Issue browsing
RawRecord browsing
Manual retry
Cancel HTTP action
```

Those belong to M2 Group 3.

## 14. Cancel Semantics Reserved by the Schema

AIC-030 implements the HTTP workflow, but Group 1 state design reserves the semantics.

V1 does not attempt forceful Java-thread interruption during provider parsing.

Allowed cancellation targets are expected to include non-running states such as `QUEUED`, `FAILED`, or `PARSED` before M3 confirmation.

A `RUNNING` Attempt is not falsely reported as canceled; a cancellation request against an actively running execution returns state conflict unless a future cooperative-cancellation design is explicitly added.

## 15. Transaction Boundaries

The following operations are short, independent transactions:

```text
Evidence reservation/finalization
ImportBatch + initial Attempt creation
Attempt creation/recovery serialized on Batch row
worker claim
lease heartbeat
lease-expiry recovery
bounded lease-fenced RawProviderRecord/ImportIssue persistence
attempt success/failure finalization
batch status finalization
```

The following must happen outside an active DB transaction:

```text
receiving large upload body
uploading/downloading Evidence object
provider schema inspection
provider file parsing
CPU-heavy normalization
```

Application use cases own `@Transactional` boundaries. Domain/application code does not depend on MyBatis APIs.

## 16. Observability and Error Hygiene

Useful M2 metrics should align with the existing architecture direction, including:

```text
import_jobs_total
import_failed_total
import_duration
```

Additional implementation metrics may include claim/recovery counts if they remain low-cardinality.

Logs, ProblemDetail, ImportIssue, audit metadata, and metrics must not contain:

```text
raw API keys
passwords/tokens
full provider evidence contents
unbounded raw rows
```

Dependency failures use the existing temporary-dependency error semantics where appropriate. Domain-specific import failure codes may be represented in Attempt/Issue records without proliferating global HTTP ProblemCode values unnecessarily.

## 17. Test Strategy

### 17.1 Schema / MySQL integration

Use the real MySQL Testcontainer for:

```text
Flyway clean-database migration
FK/unique constraints
Evidence duplicate race
ImportBatch uniqueness
Attempt uniqueness
at-most-one active Attempt per Batch
queue index presence/usage as appropriate
FOR UPDATE SKIP LOCKED behavior
dual-worker claim
lease expiration
lease fencing
crash recovery
```

Do not use mocks to prove MySQL concurrency behavior.

### 17.2 Evidence/object storage

Cover:

```text
same SHA reuses Evidence
concurrent same-SHA upload converges
upload size limit
temporary-file cleanup
MinIO unavailable
STAGING object recovery
object exists / DB finalize interrupted
ETag is not treated as SHA-256 truth
unauthorized download
cross-org download
object-storage operation occurs outside long DB transaction
```

A MinIO-backed integration test environment should be added for storage behavior rather than testing only a fake ObjectStoragePort.

### 17.3 Worker

Cover:

```text
one queued Attempt claimed once
second worker skips locked row
lease uses DB time
heartbeat renews owned lease
stale lease_version cannot persist rows
stale lease_version cannot finalize
expired lease marks old Attempt FAILED
recovery creates new Attempt
recovery cannot create a second active Attempt
recovery limit prevents infinite loop
schema/data failure does not auto-loop
partial failed-attempt raw records remain
```

### 17.4 Adapter framework

Before Group 2 provider adapters exist, cover the framework with small synthetic test adapters/sources:

```text
explicit adapter selection
duplicate registry rejection
unsupported provider
parserVersion participates in Batch identity
new parserVersion creates different Batch identity
unknown schema
stable schema fingerprint
WARN-only import succeeds
ERROR import fails
bounded streaming sink behavior
parser version retained in lineage
```

## 18. Alternatives Considered

### Evidence/storage ordering

1. **Object first, DB second** — rejected because DB failure leaves untracked objects as the normal failure mode.
2. **Hold DB transaction across object upload** — rejected because large provider evidence would create long DB transactions.
3. **DB reservation → object storage → DB finalize** — approved because it makes intermediate state explicit and recoverable.

### Crash recovery

1. **Reclaim the same Attempt** — rejected because one Attempt would represent multiple real executions and obscure lineage.
2. **Fail old Attempt and create a recovery Attempt** — approved because execution history remains truthful.

### Adapter output

1. **Return full List** — rejected for memory scalability.
2. **Adapter writes database rows** — rejected for module/transaction coupling.
3. **Streaming adapter + ingestion-owned sink** — approved.

### Duplicate upload

1. **Reject same SHA** — rejected because upload should be naturally idempotent.
2. **Always create new Evidence** — rejected because byte identity and storage would be duplicated.
3. **Reuse Evidence and reuse matching Batch context without implicit retry** — approved.

## 19. Explicit Non-goals for Group 1

Do not implement:

```text
DeepSeek production adapter
MiMo production adapter
Kimi production adapter
GLM production adapter
OpenAI production adapter

Canonical Cost schema/facts
Canonical Cost normalization
Duplicate/overlap cost workflow
Allocation
Ledger
Budget
Reconciliation

Import Confirm
full Import review/retry/cancel API
Evidence/Import React workflow
RabbitMQ/Kafka
Redis queue truth
cross-organization evidence dedup
forceful running-parser cancellation
```

## 20. Definition of Done

AIC-021 through AIC-024 are complete when:

- Flyway creates the Evidence/Import schema from a clean MySQL database.
- Evidence identity, storage status, ImportBatch identity, Attempt history, RawRecord, and Issue constraints are covered by integration tests.
- S3-compatible Evidence storage works through `ObjectStoragePort` with a MinIO adapter and streaming behavior.
- Duplicate evidence and interrupted-storage failure paths are recoverable.
- Provider import creation produces/reuses the correct Evidence/Batch; only a newly created Batch gets an initial Attempt.
- Re-uploading an existing Batch identity does not implicitly retry execution.
- Parser-version changes create/reuse a different Batch identity rather than rewriting old history.
- A Batch never has more than one QUEUED/RUNNING Attempt.
- DB-backed workers can claim concurrently through tested MySQL locking semantics without duplicate ownership.
- Lease fencing protects both raw-record writes and finalization, and crash recovery preserves execution history.
- ProviderAdapter registry, schema inspection, fingerprinting, streaming parse orchestration, and ERROR/WARN semantics are testable before provider-specific adapters arrive.
- M2 produces no canonical cost facts and stops at `PARSED`.
- Existing M1 authorization/privacy semantics remain intact.
- FINANCE_REVIEWER can read Provider Accounts for imports but cannot manage them.
- Required repository CI checks remain green.

## 21. Delivery

These four Issues ship together in one vertical PR:

```text
AIC-021 -> GitHub #29
AIC-022 -> GitHub #30
AIC-023 -> GitHub #31
AIC-024 -> GitHub #32
```

Planned implementation branch after this spec is approved:

```text
feat/m2-evidence-import-foundation
```

No implementation begins until this written spec has passed user review and an implementation plan has been produced.
