# M6 Reconciliation & Close Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver AIC-054～AIC-059 as a correct period-end workflow that reconciles confirmed canonical provider charges against immutable Ledger truth, resolves material cases, freezes BillingPeriod financial truth during Close, persists all seven blocker results, and safely closes or reopens periods without rewriting financial history.

**Architecture:** Add a focused `com.aicostops.reconciliation` module for Run/Case/Close orchestration and persistence. Existing modules keep ownership of Import, Charge/Duplicate, Allocation, Expense, BillingPeriod/Budget/Commitment, and Ledger facts; Reconciliation consumes narrow application ports rather than foreign MyBatis mappers. BillingPeriod row locks remain the known-period serialization point, while unknown-period Import admission uses the organization row before any period lock so `CLOSING` makes Close truth monotonic.

**Tech Stack:** Java 21, Spring Boot 4.1, Plain MyBatis, MySQL 8.4/InnoDB, Flyway, JUnit 5, Testcontainers, ArchUnit, React 19, TypeScript, TanStack Query, Ant Design, Vitest/RTL.

**Spec:** `docs/superpowers/specs/2026-08-20-m6-reconciliation-close-design.md`

## Global Constraints

- Baseline is `main@16d4b66fffed24e0e45681967bed7612bb14040b`; work stays on `feat/m6-reconciliation-close` and ends in one squash PR for Issue #89.
- V1～V15 are immutable. M6 starts with `V16__m6_reconciliation_close.sql`.
- BillingPeriod status remains exactly `OPEN | CLOSING | CLOSED`; `BLOCKED` is a CloseRun status, never a period status.
- External financial truth is confirmed-import canonical `charge_fact`; `external_document` is supporting evidence only.
- Internal provider truth is signed immutable LedgerEntry truth in the target BillingPeriod carrying Provider Charge lineage.
- Matching key is `(billingPeriod, providerAccountId, currency)`; no FX, generic DSL, or provider-formula guessing.
- `difference = internalAmount - externalAmount`; money stays `DECIMAL(20,8)` / `BigDecimal` / JSON decimal string.
- Reconciliation tolerance is server-owned, default `0.00000000`; the client cannot choose it.
- Case resolution never mutates Ledger, Budget, canonical Charge money, or creates a Correction.
- Close has exactly seven canonical blocker codes and persists exactly seven terminal Check rows for every finalized attempt.
- `PENDING_CORRECTIONS` is an explicit V1 not-applicable PASS because CorrectionGroup has only committed `POSTED` state.
- Known-period truth-changing writes lock BillingPeriod first and require OPEN. Unknown-period Import admission locks Organization before checking for CLOSING periods. Never introduce Period → Organization lock order.
- Committed Provider/Expense posting replay remains valid in `CLOSING`/`CLOSED`; only creation of new financial truth is fenced.
- Reopen is privileged `CLOSED -> OPEN`, requires reason/audit, increments `close_generation` exactly once, and preserves old Close/Reconciliation/Ledger history.
- M6 permissions are existing seed codes; activate only ORG applicable scope. SYSTEM_ADMIN receives no implicit finance authority.
- Every new HTTP route is explicitly authenticated in `SecurityConfiguration`; default `anyRequest().denyAll()` stays.
- Browser uses existing `frontend/src/lib/money.ts` and `frontend/src/lib/dateTime.ts`; no JavaScript-number accounting and no raw ISO timestamps on M6 pages.
- User-visible M6 frontend copy is Chinese; backend enum/code values remain stable English API contract values.
- Use PowerShell commands in execution notes. Run real MySQL integration tests for financial/concurrency behavior; mocks do not prove row-lock correctness.

---

## Checkpoint 1 — AIC-054～AIC-056 Reconciliation Core

### Task 1: Create the V16 Reconciliation / Close schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V16__m6_reconciliation_close.sql`
- Create: `backend/src/test/java/com/aicostops/M6ReconciliationCloseSchemaIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/testsupport/M2DatabaseCleaner.java`

**Contract:** Create `reconciliation_run`, `reconciliation_case`, `period_close_run`, and `period_close_check`; add only the supporting same-org key/indexes required by the approved design.

- [ ] **Step 1: Write the failing MySQL migration test**

Create `M6ReconciliationCloseSchemaIntegrationTest` using the repository's existing Testcontainers/Flyway integration base. Assert table existence and real constraint rejection for invalid state/code combinations.

```java
@Test
void v16CreatesFourM6TablesAndRejectsUnknownCloseBlocker() {
    assertThat(tableExists("reconciliation_run")).isTrue();
    assertThat(tableExists("reconciliation_case")).isTrue();
    assertThat(tableExists("period_close_run")).isTrue();
    assertThat(tableExists("period_close_check")).isTrue();

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO period_close_check(
          org_id,period_close_run_id,blocker_code,result,item_count,
          summary_json,evaluated_at,created_at)
        VALUES (?,?,?,?,0,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        """, orgId, closeRunId, "NOT_A_BLOCKER", "PASS"))
        .isInstanceOf(DataIntegrityViolationException.class);
}
```

Also prove:

```text
ReconciliationRun status CHECK
ReconciliationCase type/status/resolution consistency CHECKs
PeriodCloseRun status + non-negative generation + positive attempt_no
PeriodCloseCheck seven blocker codes + PASS/FAIL/ERROR
UQ(run, providerAccount, currency)
UQ(org, period, generation, attempt_no)
UQ(closeRun, blockerCode)
same-org period/member/provider/run foreign keys
required query indexes
```

- [ ] **Step 2: Run the schema test and confirm it fails before V16 exists**

```powershell
cd backend
.\mvnw.cmd -Dtest=M6ReconciliationCloseSchemaIntegrationTest test
```

Expected: FAIL because V16 is absent.

- [ ] **Step 3: Implement V16 with exact frozen states and precision**

`reconciliation_run` must include:

```sql
id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
org_id BIGINT NOT NULL,
billing_period_id BIGINT NOT NULL,
status VARCHAR(32) NOT NULL,
algorithm_version VARCHAR(100) NOT NULL,
tolerance_amount DECIMAL(20,8) NOT NULL,
basis_hash CHAR(64) NULL,
summary_json JSON NOT NULL,
created_by_member_id BIGINT NOT NULL,
started_at DATETIME(6) NOT NULL,
finished_at DATETIME(6) NULL,
error_code VARCHAR(100) NULL,
error_summary VARCHAR(500) NULL,
created_at DATETIME(6) NOT NULL,
updated_at DATETIME(6) NOT NULL
```

with status `CREATED | RUNNING | COMPLETED | FAILED`, non-negative tolerance, same-org FKs, and terminal consistency (`COMPLETED` requires `basis_hash` and `finished_at`; `FAILED` requires `finished_at`).

`reconciliation_case` must use exactly:

```text
case_type: MISSING_INTERNAL | MISSING_EXTERNAL | AMOUNT_MISMATCH
status: OPEN | INVESTIGATING | RESOLVED
money: external_amount/internal_amount nullable DECIMAL(20,8), difference_amount DECIMAL(20,8)
row counts: non-negative BIGINT
resolution: reason_code/note/actor/time all present iff RESOLVED
```

`period_close_run` must use exactly `CHECKING | BLOCKED | CLOSED | FAILED`, `close_generation >= 0`, `attempt_no > 0`, and unique `(org_id,billing_period_id,close_generation,attempt_no)`.

`period_close_check` must use exactly these blocker codes:

```text
OPEN_IMPORTS
UNRESOLVED_DUPLICATES
UNALLOCATED_CHARGES
UNPOSTED_APPROVED_EXPENSES
OPEN_MATERIAL_RECONCILIATION
PENDING_CORRECTIONS
LEDGER_INTEGRITY
```

and result `PASS | FAIL | ERROR` with unique `(period_close_run_id,blocker_code)`.

If `reconciliation_case` uses a same-org composite FK to provider account, add `UNIQUE(id,org_id)` to `provider_account`; do not alter its existing natural uniqueness.

Confirm/add non-duplicate indexes equivalent to:

```sql
CREATE INDEX idx_ledger_posting_org_period_id
    ON ledger_posting(org_id,billing_period_id,id);
CREATE INDEX idx_expense_claim_org_status_date_id
    ON expense_claim(org_id,status,expense_date,id);
CREATE INDEX idx_import_batch_org_status_period_id
    ON import_batch(org_id,status,period_start,period_end,id);
```

- [ ] **Step 4: Update database cleanup order**

Delete before existing finance parents in this order:

```text
period_close_check
period_close_run
reconciliation_case
reconciliation_run
```

- [ ] **Step 5: Run migration regression**

```powershell
cd backend
.\mvnw.cmd -Dtest=M6ReconciliationCloseSchemaIntegrationTest,V11MigrationIntegrationTest,M3CanonicalCostSchemaIntegrationTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/resources/db/migration/V16__m6_reconciliation_close.sql `
        backend/src/test/java/com/aicostops/M6ReconciliationCloseSchemaIntegrationTest.java `
        backend/src/test/java/com/aicostops/testsupport/M2DatabaseCleaner.java
git commit -m "feat(reconciliation): add M6 close schema"
```

---

### Task 2: Add M6 domain types, persistence mappers, permissions, and architecture boundary

**Files:**
- Create: `backend/src/main/java/com/aicostops/reconciliation/domain/ReconciliationRunStatus.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/domain/ReconciliationCaseStatus.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/domain/ReconciliationCaseType.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/domain/PeriodCloseRunStatus.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/domain/PeriodCloseCheckResult.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/domain/CloseBlockerCode.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/domain/ReconciliationRun.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/domain/ReconciliationCase.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/domain/PeriodCloseRun.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/domain/PeriodCloseCheck.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/ReconciliationMapper.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/PeriodCloseMapper.java`
- Modify: `backend/src/main/java/com/aicostops/iam/domain/M1AdminPermissionPolicy.java`
- Modify: `backend/src/test/java/com/aicostops/iam/domain/M1AdminPermissionPolicyTest.java`
- Modify: `backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationPersistenceIntegrationTest.java`

**Contract:** Domain stays immutable/framework-free. Reconciliation application may consume owning-module application/domain seams but not foreign persistence classes.

- [ ] **Step 1: Write failing permission and architecture tests**

```java
assertThat(M1AdminPermissionPolicy.applicableScopes("RECONCILIATION_READ"))
    .containsExactly(ScopeType.ORG);
assertThat(M1AdminPermissionPolicy.applicableScopes("RECONCILIATION_RUN"))
    .containsExactly(ScopeType.ORG);
assertThat(M1AdminPermissionPolicy.applicableScopes("RECONCILIATION_RESOLVE"))
    .containsExactly(ScopeType.ORG);
assertThat(M1AdminPermissionPolicy.applicableScopes("PERIOD_READ"))
    .containsExactly(ScopeType.ORG);
assertThat(M1AdminPermissionPolicy.applicableScopes("PERIOD_CLOSE"))
    .containsExactly(ScopeType.ORG);
assertThat(M1AdminPermissionPolicy.applicableScopes("PERIOD_REOPEN"))
    .containsExactly(ScopeType.ORG);
```

Add an ArchUnit rule preventing `com.aicostops.reconciliation.application..` from depending on any of:

```text
com.aicostops.ingestion.infrastructure..
com.aicostops.cost.infrastructure..
com.aicostops.cost.review.infrastructure..
com.aicostops.expense.infrastructure..
com.aicostops.budget.infrastructure..
com.aicostops.ledger.infrastructure..
```

- [ ] **Step 2: Run targeted tests and confirm failure**

```powershell
cd backend
.\mvnw.cmd -Dtest=M1AdminPermissionPolicyTest,ModuleDependencyArchitectureTest test
```

- [ ] **Step 3: Add exact enums/records**

```java
public enum CloseBlockerCode {
    OPEN_IMPORTS,
    UNRESOLVED_DUPLICATES,
    UNALLOCATED_CHARGES,
    UNPOSTED_APPROVED_EXPENSES,
    OPEN_MATERIAL_RECONCILIATION,
    PENDING_CORRECTIONS,
    LEDGER_INTEGRITY
}
```

All money fields are `BigDecimal`, all persisted ids are `long`/`Long`, all event timestamps are `Instant`.

- [ ] **Step 4: Implement explicit ReconciliationMapper contract**

The mapper exposes these exact operations (parameter annotations may follow repository style):

```java
int insertRun(long organizationId, long billingPeriodId, String status,
        String algorithmVersion, BigDecimal toleranceAmount, String summaryJson,
        long createdByMemberId, Instant startedAt, Instant createdAt, Instant updatedAt);
long lastInsertId();
ReconciliationRun selectRunByIdAndOrganization(long organizationId, long runId);
ReconciliationRun selectRunByIdForUpdate(long organizationId, long runId);
ReconciliationRun selectLatestRunForPeriod(long organizationId, long billingPeriodId);
List<ReconciliationRun> selectRunsByPeriod(long organizationId, long billingPeriodId,
        int size, int offset);
long countRunsByPeriod(long organizationId, long billingPeriodId);
int markRunCompleted(long organizationId, long runId, String basisHash,
        String summaryJson, Instant finishedAt, Instant updatedAt);
int markRunFailed(long organizationId, long runId, String errorCode,
        String errorSummary, Instant finishedAt, Instant updatedAt);
int insertCase(long organizationId, long runId, long providerAccountId, String currency,
        String caseType, BigDecimal externalAmount, BigDecimal internalAmount,
        BigDecimal differenceAmount, long externalRowCount, long internalRowCount,
        Instant createdAt, Instant updatedAt);
ReconciliationCase selectCaseByIdAndOrganization(long organizationId, long caseId);
ReconciliationCase selectCaseByIdForUpdate(long organizationId, long caseId);
List<ReconciliationCase> selectCasesByRun(long organizationId, long runId, int size, int offset);
long countCasesByRun(long organizationId, long runId);
long countUnresolvedCases(long organizationId, long runId);
int markInvestigating(long organizationId, long caseId, Instant updatedAt);
int returnInvestigatingToOpen(long organizationId, long caseId, Instant updatedAt);
int markResolved(long organizationId, long caseId, String reasonCode,
        String resolutionNote, long resolvedByMemberId, Instant resolvedAt, Instant updatedAt);
```

- [ ] **Step 5: Implement explicit PeriodCloseMapper contract**

```java
int insertRun(long organizationId, long billingPeriodId, long closeGeneration,
        int attemptNo, String status, Long reconciliationRunId,
        long startedByMemberId, Instant startedAt, Instant createdAt, Instant updatedAt);
long lastInsertId();
PeriodCloseRun selectRunByIdAndOrganization(long organizationId, long runId);
PeriodCloseRun selectRunByIdForUpdate(long organizationId, long runId);
PeriodCloseRun selectLatestRunForPeriod(long organizationId, long billingPeriodId);
List<PeriodCloseRun> selectCheckingRunsForGeneration(
        long organizationId, long billingPeriodId, long closeGeneration);
PeriodCloseRun selectLatestSuccessfulRunForGeneration(
        long organizationId, long billingPeriodId, long closeGeneration);
int selectNextAttemptNo(long organizationId, long billingPeriodId, long closeGeneration);
List<PeriodCloseRun> selectRunsByPeriod(long organizationId, long billingPeriodId,
        int size, int offset);
long countRunsByPeriod(long organizationId, long billingPeriodId);
int insertCheck(long organizationId, long closeRunId, String blockerCode,
        String result, long itemCount, String summaryJson,
        Instant evaluatedAt, Instant createdAt);
List<PeriodCloseCheck> selectChecksByRun(long organizationId, long closeRunId);
int markRunBlocked(long organizationId, long runId, Instant finishedAt, Instant updatedAt);
int markRunFailed(long organizationId, long runId, String errorCode,
        String errorSummary, Instant finishedAt, Instant updatedAt);
int markRunClosed(long organizationId, long runId, long reconciliationRunId,
        Instant finishedAt, Instant updatedAt);
```

- [ ] **Step 6: Activate only ORG scope for the six existing seed permissions**

Do not alter V3 seed contents.

- [ ] **Step 7: Write and run persistence integration tests**

Prove generated ids, org privacy, ordering, unique keys, row-lock transitions, and terminal consistency against real MySQL.

```powershell
cd backend
.\mvnw.cmd -Dtest=ReconciliationPersistenceIntegrationTest,M1AdminPermissionPolicyTest,ModuleDependencyArchitectureTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/aicostops/reconciliation `
        backend/src/main/java/com/aicostops/iam/domain/M1AdminPermissionPolicy.java `
        backend/src/test/java/com/aicostops/iam/domain/M1AdminPermissionPolicyTest.java `
        backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java `
        backend/src/test/java/com/aicostops/reconciliation/ReconciliationPersistenceIntegrationTest.java
git commit -m "feat(reconciliation): establish M6 domain boundaries"
```

---

### Task 3: Implement external/internal truth, tolerance, matching, and basis hash

**Files:**
- Create: `backend/src/main/java/com/aicostops/cost/application/ReconciliationExternalTruthPort.java`
- Create: `backend/src/main/java/com/aicostops/cost/infrastructure/ReconciliationExternalTruthAdapter.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/ReconciliationInternalTruthPort.java`
- Create: `backend/src/main/java/com/aicostops/ledger/infrastructure/ReconciliationInternalTruthAdapter.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationMoney.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationTolerancePolicy.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationMatchEngine.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationTruthHasher.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationReadModels.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationMatchEngineTest.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationTruthIntegrationTest.java`

**Contracts:**

```java
public interface ReconciliationExternalTruthPort {
    List<ExternalAggregate> aggregateConfirmedCharges(
        long organizationId, Instant periodStart, Instant periodEnd);

    record ExternalAggregate(
        long providerAccountId, String currency, long rowCount, BigDecimal amount) {}
}
```

```java
public interface ReconciliationInternalTruthPort {
    List<InternalAggregate> aggregateProviderLedger(
        long organizationId, long billingPeriodId);

    record InternalAggregate(
        long providerAccountId, String currency, long rowCount, BigDecimal amount) {}
}
```

`ReconciliationReadModels.MatchRow` contains providerAccountId, currency, both presence flags/counts/amounts, `difference`, and nullable `ReconciliationCaseType`.

- [ ] **Step 1: Write matching-engine tests first**

Cover exact match, within-tolerance match, missing side, mismatch, zero-net-but-present, deterministic ordering, and sign:

```java
@Test
void differenceIsInternalMinusExternal() {
    var result = engine.match(
        List.of(external(7, "USD", 1, "10.00000000")),
        List.of(internal(7, "USD", 1, "12.00000000")),
        new BigDecimal("0.00000000"));

    assertThat(result.getFirst().difference()).isEqualByComparingTo("2.00000000");
    assertThat(result.getFirst().caseType())
        .isEqualTo(ReconciliationCaseType.AMOUNT_MISMATCH);
}
```

- [ ] **Step 2: Run and confirm failure**

```powershell
cd backend
.\mvnw.cmd -Dtest=ReconciliationMatchEngineTest test
```

- [ ] **Step 3: Implement external truth SQL**

```sql
SELECT ib.provider_account_id,
       cf.currency,
       COUNT(*) AS row_count,
       SUM(cf.amount) AS amount
FROM charge_fact cf
JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
JOIN import_batch ib ON ib.id=ia.import_batch_id
WHERE cf.org_id=#{organizationId}
  AND ib.org_id=cf.org_id
  AND ib.status='CONFIRMED'
  AND ib.confirmed_attempt_id=ia.id
  AND cf.review_status IN ('CLEAN','SUSPECTED_DUPLICATE')
  AND cf.period_start >= #{periodStart}
  AND cf.period_start < #{periodEnd}
GROUP BY ib.provider_account_id,cf.currency
ORDER BY ib.provider_account_id,cf.currency
```

Do not use `external_document` as authority.

- [ ] **Step 4: Implement internal truth SQL including correction lineage**

```sql
SELECT ib.provider_account_id,
       le.currency,
       COUNT(*) AS row_count,
       SUM(le.amount) AS amount
FROM ledger_entry le
JOIN ledger_posting lp ON lp.id=le.posting_id AND lp.org_id=le.org_id
JOIN charge_fact cf ON cf.id=le.source_charge_fact_id AND cf.org_id=le.org_id
JOIN raw_provider_record rpr ON rpr.id=cf.raw_record_id
JOIN import_attempt ia ON ia.id=rpr.import_attempt_id
JOIN import_batch ib ON ib.id=ia.import_batch_id AND ib.org_id=le.org_id
WHERE le.org_id=#{organizationId}
  AND lp.billing_period_id=#{billingPeriodId}
  AND le.source_charge_fact_id IS NOT NULL
GROUP BY ib.provider_account_id,le.currency
ORDER BY ib.provider_account_id,le.currency
```

Do not filter parent posting to `PROVIDER_CHARGE`; correction entries with preserved source Charge lineage must contribute.

- [ ] **Step 5: Implement exact money/tolerance policy**

`ReconciliationMoney.requireScale8Exact(BigDecimal)` rejects values that cannot be represented exactly at scale 8; it never silently rounds. `ReconciliationTolerancePolicy` reads:

```text
aicostops.reconciliation.tolerance
```

with default `0.00000000`, validates non-negative exact scale-8 money, and exposes `BigDecimal amount()`.

- [ ] **Step 6: Implement deterministic SHA-256 basis hash**

Canonical UTF-8 input starts with algorithm version and then one sorted line per `(providerAccount,currency)`. A line contains:

```text
providerAccountId|currency|externalPresent|externalRowCount|externalAmount|internalPresent|internalRowCount|internalAmount
```

Amounts are exact scale-8 strings; null side amount uses a fixed marker `-`; booleans use `1`/`0`; line separator is `\n`.

- [ ] **Step 7: Write real-MySQL truth integration tests**

Prove confirmed-attempt lineage only, excluded/suspected behavior, half-open boundaries, expense exclusion, provider correction inclusion, and zero-net presence.

```powershell
cd backend
.\mvnw.cmd -Dtest=ReconciliationMatchEngineTest,ReconciliationTruthIntegrationTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/aicostops/cost/application/ReconciliationExternalTruthPort.java `
        backend/src/main/java/com/aicostops/cost/infrastructure/ReconciliationExternalTruthAdapter.java `
        backend/src/main/java/com/aicostops/ledger/application/ReconciliationInternalTruthPort.java `
        backend/src/main/java/com/aicostops/ledger/infrastructure/ReconciliationInternalTruthAdapter.java `
        backend/src/main/java/com/aicostops/reconciliation/application `
        backend/src/test/java/com/aicostops/reconciliation/ReconciliationMatchEngineTest.java `
        backend/src/test/java/com/aicostops/reconciliation/ReconciliationTruthIntegrationTest.java
git commit -m "feat(reconciliation): match canonical truth to ledger"
```

---

### Task 4: Implement Reconciliation Run orchestration, query API, OpenAPI, and audit

**Files:**
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationAuditPort.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationRunService.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationQueryService.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/AuditReconciliationAdapter.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/api/ReconciliationController.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/api/ReconciliationRequests.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/api/ReconciliationResponses.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify: `docs/02-development/api/openapi.yaml`
- Create: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationRunIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/M6OpenApiContractTest.java`

**Contracts:**

```java
public ReconciliationReadModels.RunDetail run(AuthenticatedUser user, long billingPeriodId);
public ReconciliationReadModels.RunPage listRuns(
    AuthenticatedUser user, long billingPeriodId, int page, int size);
public ReconciliationReadModels.RunDetail getRun(AuthenticatedUser user, long runId);
```

Add `RunPage(List<RunSummary> content,long totalElements,int page,int size)` to `ReconciliationReadModels` and serialize to the repository's existing pagination response shape.

`POST /api/v1/reconciliation-runs` accepts only:

```json
{"billingPeriodId":"123"}
```

- [ ] **Step 1: Write failing API/integration tests**

Prove permission, cross-org 404, OPEN-only start, no caller tolerance/totals, new history per explicit run, completed snapshot fields, case creation, within-tolerance summary-only behavior, FAILED run behavior, JSON-string IDs, and scale-8 money strings.

- [ ] **Step 2: Run and confirm failure**

```powershell
cd backend
.\mvnw.cmd -Dtest=ReconciliationRunIntegrationTest,M6OpenApiContractTest test
```

- [ ] **Step 3: Implement synchronous three-phase Run lifecycle**

Phase 1 transaction:

```text
fresh authorization context
require RECONCILIATION_RUN @ ORG
org-scoped BillingPeriod lookup
require OPEN
insert RUNNING with algorithm + tolerance snapshot
commit
```

Snapshot transaction uses `REPEATABLE_READ` for both truth-port reads and computes immutable MatchRows + basis hash.

Finalize transaction:

```text
Run FOR UPDATE
require RUNNING
insert only discrepancy Cases
mark COMPLETED with basis_hash/summary/finished_at
append audit
commit
```

On evaluation exception, mark that Run FAILED in a fresh transaction when possible. Never expose a partial Case set as COMPLETED.

- [ ] **Step 4: Implement audit through existing `AuditService.append`**

Use event `RECONCILIATION_RUN_COMPLETED` or `RECONCILIATION_RUN_FAILED`, subject `RECONCILIATION_RUN`, and bounded metadata containing period id, algorithm, and counts only.

- [ ] **Step 5: Add explicit security matchers and OpenAPI**

Authenticate Run/Case M6 routes explicitly; business permission stays in services.

- [ ] **Step 6: Run Run/OpenAPI/architecture tests**

```powershell
cd backend
.\mvnw.cmd -Dtest=ReconciliationRunIntegrationTest,M6OpenApiContractTest,ModuleDependencyArchitectureTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/aicostops/reconciliation `
        backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java `
        backend/src/test/java/com/aicostops/reconciliation `
        docs/02-development/api/openapi.yaml
git commit -m "feat(reconciliation): add run workflow and API"
```

---

### Task 5: Implement Reconciliation Case lifecycle without accounting mutation

**Files:**
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationCaseService.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationQueryService.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationAuditPort.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/AuditReconciliationAdapter.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/api/ReconciliationController.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/api/ReconciliationRequests.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/api/ReconciliationResponses.java`
- Extend: `docs/02-development/api/openapi.yaml`
- Create: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationCaseLifecycleIntegrationTest.java`

**Contracts:**

```java
public ReconciliationReadModels.CaseDetail investigate(AuthenticatedUser user, long caseId);
public ReconciliationReadModels.CaseDetail returnOpen(AuthenticatedUser user, long caseId);
public ReconciliationReadModels.CaseDetail resolve(
    AuthenticatedUser user, long caseId, ResolveCaseCommand command);
public ReconciliationReadModels.CaseDetail getCase(AuthenticatedUser user, long caseId);
public ReconciliationReadModels.CasePage listCases(
    AuthenticatedUser user, long runId, String status, int page, int size);
public record ResolveCaseCommand(String reasonCode, String resolutionNote) {}
```

- [ ] **Step 1: Write failing lifecycle/invariant tests**

```text
OPEN -> INVESTIGATING
INVESTIGATING -> OPEN
INVESTIGATING -> RESOLVED
OPEN cannot resolve directly
RESOLVED terminal
blank reason/note rejected
concurrent stale transition -> one success, one 409
cross-org -> 404 after applicable permission check
no permission -> 403 before disclosure
resolve leaves Ledger/Budget/canonical facts unchanged
```

- [ ] **Step 2: Run and confirm failure**

```powershell
cd backend
.\mvnw.cmd -Dtest=ReconciliationCaseLifecycleIntegrationTest test
```

- [ ] **Step 3: Implement row-locked transitions**

Each mutation uses fresh auth, `RECONCILIATION_RESOLVE @ ORG`, org-scoped Case `FOR UPDATE`, exact from-state validation, one CAS, audit, and committed readback.

Resolve writes in the same transaction:

```text
status=RESOLVED
reason_code
resolution_note
resolved_by_member_id
resolved_at
updated_at
```

It performs no accounting write.

- [ ] **Step 4: Add routes/OpenAPI**

```text
GET  /api/v1/reconciliation-cases
GET  /api/v1/reconciliation-cases/{caseId}
POST /api/v1/reconciliation-cases/{caseId}/investigate
POST /api/v1/reconciliation-cases/{caseId}/return-open
POST /api/v1/reconciliation-cases/{caseId}/resolve
```

- [ ] **Step 5: Run Case + financial invariant regression**

```powershell
cd backend
.\mvnw.cmd -Dtest=ReconciliationCaseLifecycleIntegrationTest,LedgerFinancialInvariantIntegrationTest,M6OpenApiContractTest test
```

Expected: PASS.

- [ ] **Step 6: Commit and stop for Sol Checkpoint 1 review**

```powershell
git add backend/src/main/java/com/aicostops/reconciliation `
        backend/src/test/java/com/aicostops/reconciliation `
        docs/02-development/api/openapi.yaml
git commit -m "feat(reconciliation): add case resolution lifecycle"
```

Checkpoint evidence:

```powershell
cd backend
.\mvnw.cmd "-Dtest=com.aicostops.reconciliation.*" test
.\mvnw.cmd -Dtest=ModuleDependencyArchitectureTest test
```

Sol reviews schema, truth semantics, tolerance/hash, case non-mutation, permission/privacy, and architecture boundaries before Close Core.

---

## Checkpoint 2 — AIC-057～AIC-058 Close Core

### Task 6: Add owner-module Close blocker data ports and integrity snapshots

**Files:**
- Create: `backend/src/main/java/com/aicostops/ingestion/application/ImportCloseBlockerPort.java`
- Create: `backend/src/main/java/com/aicostops/ingestion/infrastructure/ImportCloseBlockerAdapter.java`
- Create: `backend/src/main/java/com/aicostops/cost/review/application/DuplicateCloseBlockerPort.java`
- Create: `backend/src/main/java/com/aicostops/cost/review/infrastructure/DuplicateCloseBlockerAdapter.java`
- Create: `backend/src/main/java/com/aicostops/cost/application/AllocationCloseBlockerPort.java`
- Create: `backend/src/main/java/com/aicostops/cost/infrastructure/AllocationCloseBlockerAdapter.java`
- Create: `backend/src/main/java/com/aicostops/expense/application/ExpenseCloseBlockerPort.java`
- Create: `backend/src/main/java/com/aicostops/expense/infrastructure/ExpenseCloseBlockerAdapter.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/LedgerIntegrityPort.java`
- Create: `backend/src/main/java/com/aicostops/ledger/infrastructure/LedgerIntegrityAdapter.java`
- Create: `backend/src/main/java/com/aicostops/budget/application/BudgetIntegrityPort.java`
- Create: `backend/src/main/java/com/aicostops/budget/infrastructure/BudgetIntegrityAdapter.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/CloseBlockerDataPortsIntegrationTest.java`

**Contracts:** each module returns count + bounded sample ids or typed aggregate snapshots, never its mapper.

```java
public interface ImportCloseBlockerPort {
    BlockerItems openImports(long organizationId, Instant periodStart, Instant periodEnd,
        int sampleLimit);
    record BlockerItems(long count, List<Long> sampleIds) {}
}
```

```java
public interface DuplicateCloseBlockerPort {
    BlockerItems unresolvedDuplicates(long organizationId, Instant periodStart,
        Instant periodEnd, int sampleLimit);
    record BlockerItems(long count, List<Long> sampleCandidateIds) {}
}
```

```java
public interface AllocationCloseBlockerPort {
    BlockerItems unallocatedCleanCharges(long organizationId, Instant periodStart,
        Instant periodEnd, int sampleLimit);
    record BlockerItems(long count, List<Long> sampleChargeIds) {}
}
```

```java
public interface ExpenseCloseBlockerPort {
    BlockerItems approvedUnposted(long organizationId, Instant periodStart,
        Instant periodEnd, int sampleLimit);
    record BlockerItems(long count, List<Long> sampleExpenseIds) {}
}
```

`LedgerIntegrityPort` exposes period-scoped posting/allocation/correction checks plus `Map<Long,BigDecimal> actualByBudget`; `BudgetIntegrityPort` exposes current `actual_amount`, `committed_amount`, and expected outstanding commitment sum per period Budget.

- [ ] **Step 1: Write failing data-port integration matrix**

Prove unknown-period/FAILED imports block, CANCELED/CONFIRMED do not; OPEN duplicate blocks; terminal duplicate does not; CLEAN confirmed unallocated Charge blocks; suspected/excluded are not double-counted; APPROVED Expense uses UTC date semantics; Ledger/Allocation/Correction/Budget counter drift is detectable.

- [ ] **Step 2: Run and confirm failure**

```powershell
cd backend
.\mvnw.cmd -Dtest=CloseBlockerDataPortsIntegrationTest test
```

- [ ] **Step 3: Implement bounded owner queries**

Every count query returns the full count; sample query returns at most 20 ids ordered deterministically. No raw provider payload enters a blocker summary.

Import semantics:

```text
safe terminal = CONFIRMED or CANCELED
complete overlapping period bounds = relevant
missing/partial bounds on nonterminal Batch = conservatively relevant
complete non-overlap = irrelevant
```

Expense semantics reuse `expense_date at 00:00:00 UTC`, never browser timezone.

- [ ] **Step 4: Implement exact Ledger/Budget integrity snapshots**

At minimum detect:

```text
posting with zero entries
normal Provider/Expense entries not matching confirmed allocation line identity/index/money/currency/target
posting source_id vs entry source pointer mismatch
invalid correction reversal/group/target linkage or replacement cardinality
double reversed historical target
budget.actual_amount != signed Ledger sum by budget
budget.committed_amount != outstanding remaining amount of currently contributing commitment states
```

Do not repair rows.

- [ ] **Step 5: Run data-port + M5 invariants**

```powershell
cd backend
.\mvnw.cmd -Dtest=CloseBlockerDataPortsIntegrationTest,LedgerFinancialInvariantIntegrationTest test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/aicostops/ingestion `
        backend/src/main/java/com/aicostops/cost `
        backend/src/main/java/com/aicostops/expense `
        backend/src/main/java/com/aicostops/ledger `
        backend/src/main/java/com/aicostops/budget `
        backend/src/test/java/com/aicostops/reconciliation/CloseBlockerDataPortsIntegrationTest.java
git commit -m "feat(close): expose deterministic blocker facts"
```

---

### Task 7: Implement all seven blocker providers, registry, and readiness preview

**Files:**
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/CloseBlockerContext.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/CloseBlockerProvider.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/CloseBlockerResult.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/CloseBlockerRegistry.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/blockers/OpenImportsBlockerProvider.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/blockers/UnresolvedDuplicatesBlockerProvider.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/blockers/UnallocatedChargesBlockerProvider.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/blockers/UnpostedApprovedExpensesBlockerProvider.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/blockers/OpenMaterialReconciliationBlockerProvider.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/blockers/PendingCorrectionsBlockerProvider.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/blockers/LedgerIntegrityBlockerProvider.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/PeriodCloseQueryService.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/api/PeriodCloseController.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/api/PeriodCloseResponses.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify: `docs/02-development/api/openapi.yaml`
- Create: `backend/src/test/java/com/aicostops/reconciliation/CloseBlockerProviderIntegrationTest.java`

**Contracts:**

```java
public record CloseBlockerContext(
    long organizationId, long billingPeriodId, Instant periodStart, Instant periodEnd) {}

public interface CloseBlockerProvider {
    CloseBlockerCode code();
    CloseBlockerResult evaluate(CloseBlockerContext context);
}

public record CloseBlockerResult(
    CloseBlockerCode code, boolean passed, long itemCount,
    Map<String,Object> summary) {}
```

Registry sorts by `CloseBlockerCode.ordinal()` and fails startup if a code is missing or duplicated.

- [ ] **Step 1: Write failing registry/provider tests**

```java
assertThat(registry.providers())
    .extracting(CloseBlockerProvider::code)
    .containsExactly(CloseBlockerCode.values());
```

- [ ] **Step 2: Implement four owner-source blockers and pending-correction not-applicable PASS**

`PENDING_CORRECTIONS` returns count 0, `passed=true`, and summary `{notApplicable:true, model:"POSTED_ONLY"}`.

- [ ] **Step 3: Implement OPEN_MATERIAL_RECONCILIATION freshness**

PASS only if latest period Run is COMPLETED, algorithm current, tolerance snapshot equals current server policy, recomputed current basis hash equals stored hash, and unresolved Case count is zero. Never fall back from a newer failed/running/stale Run to an older completed Run.

- [ ] **Step 4: Implement LEDGER_INTEGRITY provider**

Combine owner snapshots from Task 6. Any frozen mismatch yields FAIL with category counts and at most 20 sample ids per category.

- [ ] **Step 5: Add readiness preview**

```text
GET /api/v1/billing-periods/{periodId}/close-readiness
Permission: PERIOD_READ @ ORG
```

Preview evaluates seven providers but never changes period state or persists authoritative CloseChecks.

- [ ] **Step 6: Run blocker/OpenAPI tests**

```powershell
cd backend
.\mvnw.cmd -Dtest=CloseBlockerProviderIntegrationTest,M6OpenApiContractTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/aicostops/reconciliation `
        backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java `
        backend/src/test/java/com/aicostops/reconciliation/CloseBlockerProviderIntegrationTest.java `
        docs/02-development/api/openapi.yaml
git commit -m "feat(close): evaluate canonical close blockers"
```

---

### Task 8: Add BillingPeriod financial write fence and retrofit known-period mutations

**Files:**
- Create: `backend/src/main/java/com/aicostops/budget/application/BillingPeriodFinancialWriteFence.java`
- Create: `backend/src/main/java/com/aicostops/budget/application/BillingPeriodFinancialWriteFenceService.java`
- Modify: `backend/src/main/java/com/aicostops/budget/infrastructure/BillingPeriodMapper.java`
- Modify: `backend/src/main/java/com/aicostops/budget/application/LedgerBudgetService.java`
- Modify: `backend/src/main/java/com/aicostops/budget/application/BudgetCommandService.java`
- Modify: `backend/src/main/java/com/aicostops/budget/application/BudgetCommitmentCommandService.java`
- Modify: `backend/src/main/java/com/aicostops/expense/application/ExpenseReviewCommandService.java`
- Modify: `backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java`
- Create: `backend/src/test/java/com/aicostops/budget/BillingPeriodFinancialWriteFenceIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/KnownPeriodCloseRaceIntegrationTest.java`

**Contract:**

```java
public interface BillingPeriodFinancialWriteFence {
    BillingPeriod lockOpenAt(long organizationId, Instant effectiveAt);
    BillingPeriod lockOpenById(long organizationId, long billingPeriodId);
    void lockOrganizationAndRequireNoClosingPeriod(long organizationId);
    boolean hasClosingPeriod(long organizationId);
}
```

`lockOrganizationAndRequireNoClosingPeriod` executes `organization FOR UPDATE` and then checks current CLOSING periods in the same transaction.

- [ ] **Step 1: Write failing fence tests**

Prove unique OPEN period returns; CLOSING/CLOSED returns existing period-not-open error; missing/ambiguous covering period fails closed; organization admission serializes against a concurrent CLOSING transition.

- [ ] **Step 2: Implement fence and delegate LedgerBudgetService to it**

There must be one canonical period OPEN lock implementation. Preserve M5 posting/correction error behavior and committed replay fast paths.

- [ ] **Step 3: Retrofit Budget create/update**

Create transaction lock order:

```text
BillingPeriod OPEN lock -> scope validation/current reads -> Budget INSERT
```

Update uses org-scoped Budget pre-read only to derive immutable `billingPeriodId`, then:

```text
BillingPeriod OPEN lock -> Budget FOR UPDATE -> id/period/version revalidation -> total update
```

- [ ] **Step 4: Retrofit Commitment request only where currently unfenced**

After idempotency replay is ruled out, lock the Budget's BillingPeriod OPEN before creating `REQUESTED` commitment lineage. Keep approve/release Period→Budget→Commitment semantics unchanged.

- [ ] **Step 5: Retrofit Expense approve**

Pre-read org-scoped Expense only to derive:

```java
Instant effectiveAt = claim.expenseDate().atStartOfDay(ZoneOffset.UTC).toInstant();
```

Inside transaction:

```text
idempotency reserve/replay
replay -> return old response, no new OPEN gate
new approve -> period OPEN lock -> Expense FOR UPDATE
            -> revalidate unchanged expenseDate/version/state
            -> ApprovalCase FOR UPDATE -> approve
```

Request-info/reject remain allowed because they do not introduce APPROVED-unposted truth.

- [ ] **Step 6: Add real-MySQL race tests**

Cover writer-first and CLOSING-first for Budget update, Commitment request, Expense approve, plus regression of Provider/Expense posting, Correction, Commitment approve/release.

```powershell
cd backend
.\mvnw.cmd -Dtest=BillingPeriodFinancialWriteFenceIntegrationTest,KnownPeriodCloseRaceIntegrationTest,ProviderPostingConcurrencyIntegrationTest,LedgerCorrectionIntegrationTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/aicostops/budget `
        backend/src/main/java/com/aicostops/expense/application/ExpenseReviewCommandService.java `
        backend/src/test/java/com/aicostops/budget/BillingPeriodFinancialWriteFenceIntegrationTest.java `
        backend/src/test/java/com/aicostops/reconciliation/KnownPeriodCloseRaceIntegrationTest.java `
        backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java
git commit -m "fix(close): fence known-period finance writes"
```

---

### Task 9: Fence unknown-period Import and Duplicate truth changes without breaking replay/cleanup

**Files:**
- Modify: `backend/src/main/java/com/aicostops/ingestion/application/ProviderImportService.java`
- Modify: `backend/src/main/java/com/aicostops/ingestion/application/ImportWorkflowCommandService.java`
- Modify: `backend/src/main/java/com/aicostops/cost/review/application/DuplicateReviewCommandService.java`
- Modify: `backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/UnknownPeriodCloseRaceIntegrationTest.java`

**Contract:** workflow classes consume `BillingPeriodFinancialWriteFence.lockOrganizationAndRequireNoClosingPeriod`; semantic replay/cleanup that creates no new truth remains allowed.

- [ ] **Step 1: Write failing race/replay tests**

Prove:

```text
new Provider Import Batch rejected if Close/CLOSING wins
existing identical Batch reuse succeeds during CLOSING
Import retry new successor rejected during CLOSING
same-key retry replay succeeds during CLOSING
Import confirm new transition rejected during CLOSING
semantic re-confirm already CONFIRMED same attempt succeeds
Import cancel remains allowed during CLOSING
Duplicate scan cannot persist a new OPEN candidate after Close wins admission
Duplicate keep remains allowed during CLOSING
Duplicate exclude is fenced because it changes included external truth
```

- [ ] **Step 2: Retrofit ProviderImport after Evidence storage without a long DB lock**

Inside `createOrReuseBatch`:

```text
read existing identity
existing -> reuse
absent -> Organization admission lock
       -> re-read identity for concurrent winner
       -> winner exists: reuse
       -> otherwise require no CLOSING period
       -> insert Batch + Initial Attempt
```

If immutable Evidence storage finished but Close then rejects new Batch admission, keep Evidence reusable; do not fake a cross-system rollback.

- [ ] **Step 3: Retrofit retry/confirm after idempotency replay decision**

```text
reserve
replay -> return stored success
new state change -> organization admission gate -> existing workflow locks/state machine
```

Cancel stays ungated.

- [ ] **Step 4: Retrofit Duplicate scan/exclude**

Every scan persistence chunk obtains short org admission before inserting OPEN candidates. `keep` stays allowed. `exclude` obtains org admission because it changes current included external truth.

- [ ] **Step 5: Narrowly adjust architecture rules**

Permit only these workflow application classes/packages to use `budget.application.BillingPeriodFinancialWriteFence`; canonical cost domain/normalization and ingestion infrastructure remain independent from Budget.

- [ ] **Step 6: Run race and regression tests**

```powershell
cd backend
.\mvnw.cmd -Dtest=UnknownPeriodCloseRaceIntegrationTest,DuplicateReviewCommandIntegrationTest,ImportCanonicalizationIntegrationTest,ModuleDependencyArchitectureTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/aicostops/ingestion/application `
        backend/src/main/java/com/aicostops/cost/review/application/DuplicateReviewCommandService.java `
        backend/src/test/java/com/aicostops/reconciliation/UnknownPeriodCloseRaceIntegrationTest.java `
        backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java
git commit -m "fix(close): fence import and duplicate truth changes"
```

---

### Task 10: Implement resumable Close coordinator and exactly-seven persisted checks

**Files:**
- Create: `backend/src/main/java/com/aicostops/budget/application/BillingPeriodClosePort.java`
- Create: `backend/src/main/java/com/aicostops/budget/application/BillingPeriodCloseService.java`
- Modify: `backend/src/main/java/com/aicostops/budget/infrastructure/BillingPeriodMapper.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/PeriodCloseService.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationAuditPort.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/AuditReconciliationAdapter.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/api/PeriodCloseController.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/api/PeriodCloseResponses.java`
- Extend: `docs/02-development/api/openapi.yaml`
- Create: `backend/src/test/java/com/aicostops/reconciliation/PeriodCloseCoordinatorIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/PeriodCloseConcurrencyIntegrationTest.java`

**Contract:**

```java
public interface BillingPeriodClosePort {
    void lockOrganizationAdmission(long organizationId);
    BillingPeriod lockPeriod(long organizationId, long periodId);
    BillingPeriod markClosing(long organizationId, long periodId,
        long expectedVersion, Instant now);
    BillingPeriod returnOpen(long organizationId, long periodId,
        long expectedVersion, Instant now);
    BillingPeriod markClosed(long organizationId, long periodId,
        long expectedVersion, Instant now);
    BillingPeriod reopen(long organizationId, long periodId,
        long expectedVersion, Instant now);
}
```

`PeriodCloseService.close(AuthenticatedUser,long)` owns begin/resume, seven-provider evaluation, and finalization.

- [ ] **Step 1: Write state-machine/crash tests first**

```text
OPEN -> CLOSING -> CLOSED
one FAIL -> CloseRun BLOCKED + period OPEN
one ERROR -> CloseRun FAILED + period OPEN
finalized attempt has exactly seven Check rows
blocked retry same generation increments attempt_no
simulated stop after begin leaves CLOSING+CHECKING; next close resumes same run
CLOSED response-loss retry returns current successful result without extra run/audit
CLOSING with zero/multiple CHECKING runs -> deterministic conflict
```

Expose package-private begin/finalize seams for tests; no debug HTTP endpoint.

- [ ] **Step 2: Implement Budget-owned CAS transitions**

Use expected status + version even under row lock. For example:

```sql
UPDATE billing_period
SET status='CLOSING', closing_started_at=#{now},
    version=version+1, updated_at=#{now}
WHERE id=#{periodId} AND org_id=#{organizationId}
  AND status='OPEN' AND version=#{expectedVersion}
```

CLOSING→OPEN clears `closing_started_at`; CLOSING→CLOSED sets `closed_at`; each increments version exactly once.

- [ ] **Step 3: Implement begin/resume transaction with global lock order**

```text
fresh auth + PERIOD_CLOSE @ ORG
Organization FOR UPDATE
BillingPeriod FOR UPDATE
CLOSED -> return current generation successful CloseRun
CLOSING -> require exactly one current-generation CHECKING run and resume it
OPEN -> next attempt_no -> insert CHECKING -> mark CLOSING -> audit start -> commit
```

- [ ] **Step 4: Evaluate all seven providers independently**

Convert provider exceptions to `ERROR` evaluation results so diagnostics still contain all seven codes. Do not persist authoritative partial checks before finalization.

- [ ] **Step 5: Finalize atomically**

```text
BillingPeriod FOR UPDATE
CloseRun FOR UPDATE
require CLOSING/CHECKING/current generation
validate exactly seven unique in-memory results
insert seven Check rows
any ERROR -> run FAILED + period OPEN
else any FAIL -> run BLOCKED + period OPEN
else -> run CLOSED + period CLOSED
append one terminal audit
commit
```

Validation:

```java
if (results.size() != CloseBlockerCode.values().length
        || results.stream().map(CloseBlockerResult::code).distinct().count()
            != CloseBlockerCode.values().length) {
    throw new IllegalStateException("A finalized Close must contain exactly seven blocker results");
}
```

- [ ] **Step 6: Add Close API**

```text
POST /api/v1/billing-periods/{periodId}/close
Permission: PERIOD_CLOSE @ ORG
```

Response includes BillingPeriod status/generation plus CloseRun attempt/status/checks. `BLOCKED` never appears as period status.

- [ ] **Step 7: Prove real row-lock races**

Use two executors/latches with real MySQL for Provider posting and at least one newly retrofitted writer. Prove writer-first makes Close observe committed truth; Close-first makes new write fail after seeing CLOSING. Also prove committed posting replay still succeeds after CLOSED.

- [ ] **Step 8: Run Close core tests**

```powershell
cd backend
.\mvnw.cmd -Dtest=PeriodCloseCoordinatorIntegrationTest,PeriodCloseConcurrencyIntegrationTest,ProviderPostingConcurrencyIntegrationTest,M6OpenApiContractTest test
```

Expected: PASS.

- [ ] **Step 9: Commit**

```powershell
git add backend/src/main/java/com/aicostops/budget `
        backend/src/main/java/com/aicostops/reconciliation `
        backend/src/test/java/com/aicostops/reconciliation `
        docs/02-development/api/openapi.yaml
git commit -m "feat(close): coordinate resumable billing period close"
```

---

### Task 11: Implement Reopen, generation semantics, history reads, and authorization matrix

**Files:**
- Extend: `backend/src/main/java/com/aicostops/reconciliation/application/PeriodCloseService.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/application/PeriodCloseQueryService.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/api/PeriodCloseController.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/api/PeriodCloseRequests.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/api/PeriodCloseResponses.java`
- Extend: `docs/02-development/api/openapi.yaml`
- Create: `backend/src/test/java/com/aicostops/reconciliation/PeriodReopenIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/M6AuthorizationIntegrationTest.java`

**Contracts:**

```java
public record ReopenPeriodCommand(String reasonCode, String reasonNote) {}
public PeriodCloseView reopen(
    AuthenticatedUser user, long periodId, ReopenPeriodCommand command);
```

```text
GET  /api/v1/billing-periods/{periodId}/close-runs
GET  /api/v1/billing-periods/{periodId}/close-runs/{runId}
POST /api/v1/billing-periods/{periodId}/reopen
```

- [ ] **Step 1: Write Reopen/generation tests first**

Prove CLOSED-only transition; mandatory reason code/nonblank note; current-generation successful CloseRun required; generation increments once; reopened_at set; closing_started_at cleared; closed_at retained; old close/reconciliation/ledger history unchanged; repeat while OPEN returns 409; next generation starts Close attempt 1.

- [ ] **Step 2: Implement Reopen under period row lock**

```text
fresh auth -> PERIOD_REOPEN @ ORG
BillingPeriod FOR UPDATE
require CLOSED
require latest successful CloseRun for current generation
validate reason
CLOSED -> OPEN CAS with close_generation+1
append PERIOD_REOPENED audit with old/new generation + reasonCode
commit
```

Persist the user explanation in the approved command/audit representation without copying secrets or unbounded payloads into metadata.

- [ ] **Step 3: Prove finance authorization matrix**

```text
FINANCE_REVIEWER: reconciliation read/run/resolve + PERIOD_READ; no Close/Reopen
FINANCE_ADMIN: Close/Reopen allowed
SYSTEM_ADMIN-only: no implicit finance capability
```

- [ ] **Step 4: Run Reopen/security/OpenAPI tests**

```powershell
cd backend
.\mvnw.cmd -Dtest=PeriodReopenIntegrationTest,M6AuthorizationIntegrationTest,M6OpenApiContractTest test
```

Expected: PASS.

- [ ] **Step 5: Commit and stop for Sol Checkpoint 2**

```powershell
git add backend/src/main/java/com/aicostops/reconciliation `
        backend/src/test/java/com/aicostops/reconciliation `
        docs/02-development/api/openapi.yaml
git commit -m "feat(close): add privileged period reopen"
```

Checkpoint evidence:

```powershell
cd backend
.\mvnw.cmd test
```

Sol reviews write-fence races, exactly-seven finalization, crash/resume, replay exceptions, generation/history, ledger/budget integrity, authorization/privacy, and API contract before frontend work.

---

## Checkpoint 3 — AIC-059 Frontend / UAT

### Task 12: Build typed React Reconciliation and Period Close workflows

**Files:**
- Create: `frontend/src/features/reconciliation/api/reconciliationApi.ts`
- Create: `frontend/src/features/reconciliation/api/reconciliationApi.test.ts`
- Create: `frontend/src/features/reconciliation/presentation.ts`
- Create: `frontend/src/features/reconciliation/ReconciliationRunsPage.tsx`
- Create: `frontend/src/features/reconciliation/ReconciliationRunsPage.test.tsx`
- Create: `frontend/src/features/reconciliation/ReconciliationRunDetailPage.tsx`
- Create: `frontend/src/features/reconciliation/ReconciliationRunDetailPage.test.tsx`
- Create: `frontend/src/features/reconciliation/ReconciliationCaseDetailPage.tsx`
- Create: `frontend/src/features/reconciliation/ReconciliationCaseDetailPage.test.tsx`
- Create: `frontend/src/features/period-close/api/periodCloseApi.ts`
- Create: `frontend/src/features/period-close/api/periodCloseApi.test.ts`
- Create: `frontend/src/features/period-close/presentation.ts`
- Create: `frontend/src/features/period-close/PeriodClosePage.tsx`
- Create: `frontend/src/features/period-close/PeriodClosePage.test.tsx`
- Modify: `frontend/src/features/imports/api/importTypes.ts`
- Modify: `frontend/src/app/router/AppRouter.tsx`
- Modify: `frontend/src/app/layout/appNavigation.tsx`
- Modify: `frontend/src/app/layout/appNavigation.test.tsx`
- Modify: `frontend/src/app/layout/AuthenticatedLayout.tsx`
- Modify: `frontend/src/app/layout/AuthenticatedLayout.test.tsx`
- Modify: `frontend/src/styles.css` only if existing layout utilities cannot express the M6 screens.

**Contracts:** frontend ids/money are strings.

```ts
export type ReconciliationRunStatus = 'CREATED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type ReconciliationCaseStatus = 'OPEN' | 'INVESTIGATING' | 'RESOLVED'
export type ReconciliationCaseType = 'MISSING_INTERNAL' | 'MISSING_EXTERNAL' | 'AMOUNT_MISMATCH'
export type BillingPeriodStatus = 'OPEN' | 'CLOSING' | 'CLOSED'
export type PeriodCloseRunStatus = 'CHECKING' | 'BLOCKED' | 'CLOSED' | 'FAILED'
export type CloseCheckResult = 'PASS' | 'FAIL' | 'ERROR'
```

- [ ] **Step 1: Write API/type tests first**

Assert ids remain strings, money/tolerance/difference remain strings, caller never sends tolerance/totals/hash, and Import status union is exactly:

```ts
export type ImportBatchStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'PARSED'
  | 'READY_FOR_REVIEW'
  | 'CONFIRMED'
  | 'FAILED'
  | 'CANCELED'
```

- [ ] **Step 2: Add navigation/routes/icons**

```text
/reconciliation -> 对账 -> RECONCILIATION_READ
/period-close -> 账期结算 -> PERIOD_READ
```

Add actual Ant Design icons to `NAV_ICONS` for both paths so desktop/mobile/collapsed sidebar never has a blank icon slot.

- [ ] **Step 3: Build Reconciliation pages**

List/history by period, explicit Run action, Run detail summary, Case filters/detail/actions. Render backend-provided external/internal/difference values only. Resolve sends only `reasonCode` and `resolutionNote`.

Use `frontend/src/lib/money.ts`; never use `Number(amount)` for authoritative arithmetic.

- [ ] **Step 4: Build Period Close page**

Show period status, generation, readiness preview, seven blockers, latest Close attempt, history, Close control, and CLOSED-only Reopen control. Render `BLOCKED` as `本次结算被阻断`, not as BillingPeriod status; render `FAILED` separately as technical failure.

- [ ] **Step 5: Reuse shared time/date helpers**

Use `formatBusinessDate`, `formatBusinessDateRange`, and `formatEventDateTime` from `frontend/src/lib/dateTime.ts`; do not create another Intl formatter in M6. Business DATE fields remain date-only.

- [ ] **Step 6: Add TanStack Query invalidation**

After Run/Case/Close/Reopen mutation, invalidate affected Run/Case/BillingPeriod/readiness/CloseRun keys so stale finance state cannot remain onscreen.

- [ ] **Step 7: Run targeted frontend tests**

```powershell
cd frontend
npm test -- --run src/features/reconciliation src/features/period-close src/app/layout src/app/router src/features/imports
```

Expected: PASS.

- [ ] **Step 8: Run frontend gates**

```powershell
cd frontend
npm run lint
npm test -- --run
npm run build
```

Expected: PASS.

- [ ] **Step 9: Commit**

```powershell
git add frontend/src
git commit -m "feat(frontend): add reconciliation and period close workflow"
```

---

### Task 13: Full regression, browser UAT, acceptance evidence, and final PR readiness

**Files:**
- Create: `docs/03-acceptance/implementation/16-m6-reconciliation-close-evidence.md`
- Modify if implementation proves pre-M6 drift: `docs/02-development/detailed-design/02-data-model.md`
- Modify if implementation proves pre-M6 drift: `docs/02-development/detailed-design/03-state-machines.md`
- Modify if implementation proves pre-M6 drift: `docs/02-development/detailed-design/04-transactions-idempotency-concurrency.md`
- Modify if implementation proves pre-M6 drift: `docs/02-development/detailed-design/06-permission-matrix.md`

- [ ] **Step 1: Run complete backend suite**

```powershell
cd backend
.\mvnw.cmd test
```

Expected: all unit/integration/architecture/migration/OpenAPI tests PASS.

- [ ] **Step 2: Run complete frontend gates**

```powershell
cd ..\frontend
npm ci
npm run lint
npm test -- --run
npm run build
```

Expected: PASS.

- [ ] **Step 3: Run Compose smoke**

```powershell
cd ..
docker compose build
docker compose up -d
docker compose ps
```

Expected: infrastructure/backend/frontend match existing healthy/running contracts.

- [ ] **Step 4: Execute desktop/tablet/mobile browser UAT**

Verify:

```text
1. Exact-match reconciliation completes without Case.
2. Material mismatch creates Case; investigate/resolve records reason/note.
3. Resolving Case alone leaves Ledger totals unchanged.
4. Readiness shows all seven blocker codes.
5. OPEN_IMPORTS / UNRESOLVED_DUPLICATES / UNALLOCATED_CHARGES /
   UNPOSTED_APPROVED_EXPENSES expose useful owning-workflow context.
6. Clean Close transitions OPEN -> CLOSED with seven PASS checks.
7. Blocked Close returns period to OPEN and history records BLOCKED.
8. Reopen increments generation and preserves old history.
9. FINANCE_REVIEWER cannot Close/Reopen; FINANCE_ADMIN can.
10. SYSTEM_ADMIN-only account has no implicit finance action.
11. M6 visible copy is Chinese; both new nav items have icons on all layouts.
12. Event timestamps use the shared formatter; business dates do not shift.
```

- [ ] **Step 5: Record exact evidence**

Acceptance doc records branch/head SHA, V16, backend test output summary, frontend gate summaries, Compose status, UAT matrix, and any resolved defects. Do not claim PASS without actual command/UAT evidence.

- [ ] **Step 6: Sync canonical detailed-design docs only where actual M6 implementation supersedes pre-M6 text**

Do not rewrite unrelated history.

- [ ] **Step 7: Run final diff hygiene**

```powershell
git status --short
git diff --check
git diff main...HEAD --stat
git diff --name-only main...HEAD -- backend/src/main/resources/db/migration/V1__foundation_baseline.sql `
  backend/src/main/resources/db/migration/V2__m1_identity_organization_schema.sql `
  backend/src/main/resources/db/migration/V3__seed_v1_roles_permissions.sql `
  backend/src/main/resources/db/migration/V4__m2_evidence_import_schema.sql `
  backend/src/main/resources/db/migration/V5__m2_import_worker_support.sql `
  backend/src/main/resources/db/migration/V6__m2_provider_pipeline.sql `
  backend/src/main/resources/db/migration/V7__m2_import_workflow_review_indexes.sql `
  backend/src/main/resources/db/migration/V8__m3_canonical_cost_foundation.sql `
  backend/src/main/resources/db/migration/V9__m3_duplicate_attribution_foundation.sql `
  backend/src/main/resources/db/migration/V10__m4_expense_approval.sql `
  backend/src/main/resources/db/migration/V11__m4_budget_period_schema.sql `
  backend/src/main/resources/db/migration/V12__m4_budget_commitment_approval.sql `
  backend/src/main/resources/db/migration/V13__m5_immutable_ledger_schema.sql `
  backend/src/main/resources/db/migration/V14__m5_expense_posted_state.sql `
  backend/src/main/resources/db/migration/V15__m5_ledger_target_integrity.sql
```

Expected: no V1～V15 file listed, no secrets/generated artifacts/unrelated refactors.

- [ ] **Step 8: Commit evidence/doc sync if changed**

```powershell
git add docs
git commit -m "docs(m6): record reconciliation close acceptance"
```

- [ ] **Step 9: Stop for Sol Checkpoint 3/final review before opening the single PR**

Sol reviews spec coverage, truth correctness, row-lock races, Case non-mutation, exactly-seven checks, crash/resume/replay, Reopen history, authorization/privacy/OpenAPI, frontend money/time/localization, and full regression/UAT evidence. Only a clean review proceeds to the one Issue #89 PR and squash merge.

---

## Plan Self-Review Map

```text
V16 four-table schema                         -> Task 1
M6 permission/architecture boundary           -> Task 2
confirmed Charge external truth               -> Task 3
immutable provider Ledger internal truth      -> Task 3
server tolerance + deterministic basis hash   -> Task 3
Run history/API                               -> Task 4
Case lifecycle + no accounting mutation       -> Task 5
blocker owner data sources                    -> Task 6
seven blocker providers/readiness             -> Task 7
known-period write fence                      -> Task 8
unknown-period admission/replay               -> Task 9
CLOSING/resume/exact-seven finalization       -> Task 10
Reopen generation/history                     -> Task 11
React workflow/localization/time/money        -> Task 12
full regression/UAT/evidence                  -> Task 13
```

No task may replace an explicit rule above with a generic abstraction, Redis correctness lock, browser calculation, or deferred follow-up issue.
