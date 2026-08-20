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

**Interfaces:**
- Produces durable tables `reconciliation_run`, `reconciliation_case`, `period_close_run`, `period_close_check` and the indexes/keys consumed by all later tasks.
- Adds `UNIQUE(id, org_id)` to `provider_account` only if needed as the same-org FK target; does not alter existing provider-account business identity.

- [ ] **Step 1: Write the failing MySQL migration test**

Create `M6ReconciliationCloseSchemaIntegrationTest` using the repository's existing Testcontainers/Flyway integration base. Assert all of the following against real MySQL metadata and constraint behavior:

```java
@Test
void v16CreatesFourM6TablesAndCanonicalConstraints() {
    assertThat(tableExists("reconciliation_run")).isTrue();
    assertThat(tableExists("reconciliation_case")).isTrue();
    assertThat(tableExists("period_close_run")).isTrue();
    assertThat(tableExists("period_close_check")).isTrue();

    assertThatThrownBy(() -> jdbc.update("""
        INSERT INTO period_close_check(
          org_id,period_close_run_id,blocker_code,result,item_count,summary_json,evaluated_at,created_at)
        VALUES (?,?,?,?,0,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
        """, orgId, closeRunId, "NOT_A_BLOCKER", "PASS"))
        .isInstanceOf(DataIntegrityViolationException.class);
}
```

Also cover:

```text
ReconciliationRun status CHECK
ReconciliationCase case_type/status/resolution consistency CHECKs
PeriodCloseRun status + non-negative generation + positive attempt_no CHECKs
PeriodCloseCheck canonical blocker/result CHECKs
UQ(run, providerAccount, currency)
UQ(org, period, generation, attempt_no)
UQ(closeRun, blockerCode)
same-org period/member/provider/run FKs
required indexes
```

- [ ] **Step 2: Run the schema test and confirm it fails before V16 exists**

PowerShell:

```powershell
cd backend
.\mvnw.cmd -Dtest=M6ReconciliationCloseSchemaIntegrationTest test
```

Expected: FAIL because V16 tables/constraints are absent.

- [ ] **Step 3: Implement V16 with exact frozen states and money precision**

The migration must encode, at minimum:

```sql
CREATE TABLE reconciliation_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
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
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_reconciliation_run_id_org UNIQUE (id, org_id),
    CONSTRAINT chk_reconciliation_run_status
      CHECK (status IN ('CREATED','RUNNING','COMPLETED','FAILED')),
    CONSTRAINT chk_reconciliation_run_tolerance CHECK (tolerance_amount >= 0),
    CONSTRAINT chk_reconciliation_run_terminal CHECK (
      (status IN ('CREATED','RUNNING') AND finished_at IS NULL)
      OR (status='COMPLETED' AND finished_at IS NOT NULL AND basis_hash IS NOT NULL)
      OR (status='FAILED' AND finished_at IS NOT NULL)
    )
);
```

Use analogous explicit CHECK/FK/index definitions for the other three tables. `reconciliation_case.case_type` is exactly:

```text
MISSING_INTERNAL | MISSING_EXTERNAL | AMOUNT_MISMATCH
```

`period_close_check.blocker_code` is exactly the seven codes in the spec; `result` is `PASS | FAIL | ERROR`.

Add supporting indexes after confirming they do not duplicate existing coverage:

```sql
CREATE INDEX idx_ledger_posting_org_period_id
    ON ledger_posting(org_id, billing_period_id, id);
CREATE INDEX idx_expense_claim_org_status_date_id
    ON expense_claim(org_id, status, expense_date, id);
CREATE INDEX idx_import_batch_org_status_period_id
    ON import_batch(org_id, status, period_start, period_end, id);
```

- [ ] **Step 4: Update database cleanup order for the four new child/parent tables**

Delete in FK-safe order before existing finance tables:

```text
period_close_check
period_close_run
reconciliation_case
reconciliation_run
```

- [ ] **Step 5: Run schema and existing migration regressions**

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

### Task 2: Add M6 domain types, persistence mappers, permission activation, and architecture boundary

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

**Interfaces:**
- Produces typed M6 records/enums and mapper operations for Run/Case/CloseRun/Check.
- Activates six already-seeded M6 permissions at ORG only.
- Freezes architecture rule: `reconciliation.application` may consume owning-module application/domain seams, never foreign `..infrastructure..`.

- [ ] **Step 1: Write failing permission and architecture tests**

Add assertions equivalent to:

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

Add an ArchUnit rule:

```java
classes().that().resideInAPackage("com.aicostops.reconciliation.application..")
    .should().onlyDependOnClassesThat().resideOutsideOfPackages(
        "com.aicostops.ingestion.infrastructure..",
        "com.aicostops.cost.infrastructure..",
        "com.aicostops.cost.review.infrastructure..",
        "com.aicostops.expense.infrastructure..",
        "com.aicostops.budget.infrastructure..",
        "com.aicostops.ledger.infrastructure..");
```

- [ ] **Step 2: Run targeted tests and confirm failure**

```powershell
cd backend
.\mvnw.cmd -Dtest=M1AdminPermissionPolicyTest,ModuleDependencyArchitectureTest test
```

Expected: permission assertions fail because M6 codes are not mapped yet.

- [ ] **Step 3: Add exact domain enums and immutable records**

For example:

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

Keep domain package framework-free.

- [ ] **Step 4: Add focused mapper methods**

`ReconciliationMapper` must support:

```java
void insertRun(...);
ReconciliationRun selectRunByIdAndOrganization(long organizationId, long runId);
ReconciliationRun selectLatestRunForPeriod(long organizationId, long billingPeriodId);
ReconciliationRun selectRunByIdForUpdate(long organizationId, long runId);
void markRunCompleted(...);
void markRunFailed(...);
void insertCase(...);
ReconciliationCase selectCaseByIdAndOrganization(...);
ReconciliationCase selectCaseByIdForUpdate(...);
List<ReconciliationCase> selectCasesByRun(...);
long countUnresolvedCases(long organizationId, long runId);
int transitionCase(...);
```

`PeriodCloseMapper` must support:

```java
void insertRun(...);
PeriodCloseRun selectLatestRunForPeriod(...);
PeriodCloseRun selectCheckingRunForGeneration(...);
PeriodCloseRun selectLatestSuccessfulRunForGeneration(...);
PeriodCloseRun selectRunByIdForUpdate(...);
int nextAttemptNo(...);
void insertCheck(...);
List<PeriodCloseCheck> selectChecksByRun(...);
void markBlocked(...);
void markFailed(...);
void markClosed(...);
```

All reads are org-scoped; lock methods use `FOR UPDATE` only where state transitions require current reads.

- [ ] **Step 5: Activate only ORG scope for the six M6 permissions**

Add six entries to `M1AdminPermissionPolicy`; do not change V3 role seed.

- [ ] **Step 6: Write/run persistence integration tests**

Test generated IDs, org-scoped privacy, run/case ordering, `FOR UPDATE` transition behavior, and unique keys against real MySQL.

```powershell
cd backend
.\mvnw.cmd -Dtest=ReconciliationPersistenceIntegrationTest,M1AdminPermissionPolicyTest,ModuleDependencyArchitectureTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/aicostops/reconciliation `
        backend/src/main/java/com/aicostops/iam/domain/M1AdminPermissionPolicy.java `
        backend/src/test/java/com/aicostops/iam/domain/M1AdminPermissionPolicyTest.java `
        backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java `
        backend/src/test/java/com/aicostops/reconciliation/ReconciliationPersistenceIntegrationTest.java
git commit -m "feat(reconciliation): establish M6 domain boundaries"
```

---

### Task 3: Implement external/internal financial truth ports, tolerance policy, matching, and deterministic basis hash

**Files:**
- Create: `backend/src/main/java/com/aicostops/cost/application/ReconciliationExternalTruthPort.java`
- Create: `backend/src/main/java/com/aicostops/cost/infrastructure/ReconciliationExternalTruthAdapter.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/ReconciliationInternalTruthPort.java`
- Create: `backend/src/main/java/com/aicostops/ledger/infrastructure/ReconciliationInternalTruthAdapter.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationTolerancePolicy.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationMatchEngine.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationTruthHasher.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationMatchEngineTest.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationTruthIntegrationTest.java`

**Interfaces:**

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

`ReconciliationMatchEngine` produces a sorted immutable list of rows with explicit presence booleans, counts, amounts, difference, and optional discrepancy type.

- [ ] **Step 1: Write matching-engine unit tests first**

Cover exact match, tolerance match, missing side, amount mismatch, zero-net-but-present, deterministic sort, and difference sign:

```java
@Test
void differenceIsInternalMinusExternal() {
    var result = engine.match(
        List.of(external(7, "USD", 1, "10.00000000")),
        List.of(internal(7, "USD", 1, "12.00000000")),
        new BigDecimal("0.00000000"));

    assertThat(result.getFirst().difference())
        .isEqualByComparingTo("2.00000000");
    assertThat(result.getFirst().caseType())
        .isEqualTo(ReconciliationCaseType.AMOUNT_MISMATCH);
}
```

- [ ] **Step 2: Run and confirm unit tests fail**

```powershell
cd backend
.\mvnw.cmd -Dtest=ReconciliationMatchEngineTest test
```

Expected: FAIL because engine/ports do not exist.

- [ ] **Step 3: Implement external truth query with confirmed lineage only**

The MyBatis SQL must derive provider account from ImportBatch lineage and use half-open Charge effective time:

```sql
SELECT ib.provider_account_id,
       cf.currency,
       COUNT(*) AS row_count,
       SUM(cf.amount) AS amount
FROM charge_fact cf
JOIN raw_provider_record rpr ON rpr.id = cf.raw_record_id
JOIN import_attempt ia ON ia.id = rpr.import_attempt_id
JOIN import_batch ib ON ib.id = ia.import_batch_id
WHERE cf.org_id = #{organizationId}
  AND ib.org_id = cf.org_id
  AND ib.status = 'CONFIRMED'
  AND ib.confirmed_attempt_id = ia.id
  AND cf.review_status IN ('CLEAN','SUSPECTED_DUPLICATE')
  AND cf.period_start >= #{periodStart}
  AND cf.period_start < #{periodEnd}
GROUP BY ib.provider_account_id, cf.currency
ORDER BY ib.provider_account_id, cf.currency
```

Do not join `external_document` for authority.

- [ ] **Step 4: Implement internal truth query including provider-charge correction lineage**

Use the target period on parent posting and source Charge lineage on Entry:

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
GROUP BY ib.provider_account_id, le.currency
ORDER BY ib.provider_account_id, le.currency
```

Do not filter `lp.source_type='PROVIDER_CHARGE'`, because valid Correction entries preserve `source_charge_fact_id` and must contribute to internal net truth.

- [ ] **Step 5: Implement server-owned tolerance and canonical basis hash**

Use an injected property with exact default:

```java
@Component
public final class ReconciliationTolerancePolicy {
    private final BigDecimal amount;

    public ReconciliationTolerancePolicy(
            @Value("${aicostops.reconciliation.tolerance:0.00000000}") String configured) {
        this.amount = BudgetDecimal.requireMoney(new BigDecimal(configured));
        if (amount.signum() < 0) throw new IllegalArgumentException("tolerance must be non-negative");
    }

    public BigDecimal amount() { return amount; }
}
```

If reusing `BudgetDecimal` would create an undesirable reconciliation→budget-domain dependency, implement the same exact DECIMAL(20,8) representability check locally in Reconciliation and cover it by tests; do not silently round.

Hash canonical lines such as:

```text
M6_PERIOD_PROVIDER_CURRENCY_V1\n
7|USD|1|3|10.00000000|1|3|10.00000000\n
```

with SHA-256 UTF-8 in providerAccount/currency order.

- [ ] **Step 6: Write and run real-MySQL truth integration tests**

Fixtures must prove:

```text
only confirmed_attempt_id lineage contributes externally
EXCLUDED_* does not contribute
SUSPECTED_DUPLICATE still contributes externally
period_start half-open boundaries are correct
expense ledger rows do not contribute internally
provider correction reversal/adjustment does contribute internally
zero-net aggregate remains present through rowCount
```

```powershell
cd backend
.\mvnw.cmd -Dtest=ReconciliationMatchEngineTest,ReconciliationTruthIntegrationTest test
```

Expected: PASS.

- [ ] **Step 7: Commit**

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
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationReadModels.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/AuditReconciliationAdapter.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/api/ReconciliationController.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/api/ReconciliationRequests.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/api/ReconciliationResponses.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify: `docs/02-development/api/openapi.yaml`
- Create: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationRunIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/M6OpenApiContractTest.java`

**Interfaces:**

```java
public ReconciliationRunDetail run(AuthenticatedUser user, long billingPeriodId)
public PageResponse<ReconciliationRunSummary> list(...)
public ReconciliationRunDetail get(AuthenticatedUser user, long runId)
```

`POST /api/v1/reconciliation-runs` body is only `{ "billingPeriodId": "..." }`.

- [ ] **Step 1: Write failing API/integration tests**

Prove:

```text
RECONCILIATION_RUN ORG required
foreign period -> 404
CLOSING/CLOSED period -> 409
caller cannot submit tolerance/totals
second explicit run creates new history
completed run snapshots algorithm/tolerance/basis hash
one discrepancy -> one persisted Case
within-tolerance match -> summary only, no Case
failed evaluation -> FAILED Run, no completed partial Case set
IDs are JSON strings and money strings are scale-8
```

- [ ] **Step 2: Run targeted tests and confirm failure**

```powershell
cd backend
.\mvnw.cmd -Dtest=ReconciliationRunIntegrationTest,M6OpenApiContractTest test
```

- [ ] **Step 3: Implement a two-phase synchronous Run lifecycle with consistent snapshot**

Phase 1 transaction:

```text
fresh/current authorization as required
org-scoped period lookup/OPEN check
insert Run RUNNING with current algorithm + tolerance
commit
```

Snapshot transaction uses `REPEATABLE_READ` for both external/internal aggregate reads and builds immutable in-memory match rows + basis hash.

Finalize transaction:

```text
lock Run FOR UPDATE
require RUNNING
insert discrepancy Cases
mark COMPLETED with basis_hash + summary_json + finished_at
append secret-free audit
commit
```

On an evaluation exception, finalize the same Run as FAILED in a fresh transaction when possible. Never mark partial cases completed.

- [ ] **Step 4: Add audit adapter through existing `AuditService.append(...)`**

Use bounded metadata, for example:

```java
audit.append("RECONCILIATION_RUN_COMPLETED", organizationId, actorUserId,
    "RECONCILIATION_RUN", runId,
    Map.of("billingPeriodId", billingPeriodId,
           "caseCount", caseCount,
           "algorithmVersion", ALGORITHM_VERSION));
```

Never store raw payload/evidence content.

- [ ] **Step 5: Add explicit SecurityConfiguration matchers and OpenAPI contract**

Add authenticated routes for Run/Case resources now; business permission remains in services.

- [ ] **Step 6: Run Run/OpenAPI/architecture regression**

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
- Extend: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationAuditPort.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/api/ReconciliationController.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/api/ReconciliationRequests.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/api/ReconciliationResponses.java`
- Extend: `docs/02-development/api/openapi.yaml`
- Create: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationCaseLifecycleIntegrationTest.java`

**Interfaces:**

```java
public ReconciliationCaseDetail investigate(AuthenticatedUser user, long caseId)
public ReconciliationCaseDetail returnOpen(AuthenticatedUser user, long caseId)
public ReconciliationCaseDetail resolve(
    AuthenticatedUser user, long caseId, ResolveCaseCommand command)

public record ResolveCaseCommand(String reasonCode, String resolutionNote) {}
```

- [ ] **Step 1: Write lifecycle and invariant tests before implementation**

Test:

```text
OPEN -> INVESTIGATING
INVESTIGATING -> OPEN
INVESTIGATING -> RESOLVED
OPEN cannot resolve directly
RESOLVED is terminal
blank reason/note rejected
stale concurrent transition -> one success, one 409
cross-org detail/transition -> 404
missing applicable permission -> 403 before disclosure
LedgerEntry/LedgerPosting/Budget counters unchanged by resolve
```

- [ ] **Step 2: Run and confirm failure**

```powershell
cd backend
.\mvnw.cmd -Dtest=ReconciliationCaseLifecycleIntegrationTest test
```

- [ ] **Step 3: Implement row-locked state transitions**

Use one transaction per command:

```text
fresh auth -> require RECONCILIATION_RESOLVE @ ORG
case org-scoped FOR UPDATE
validate exact from-state
CAS update status + resolution fields
append audit
read committed detail
commit
```

For resolve, persist all resolution fields atomically:

```text
reason_code
resolution_note
resolved_by_member_id
resolved_at
status=RESOLVED
```

- [ ] **Step 4: Add API routes and OpenAPI examples**

Routes:

```text
POST /api/v1/reconciliation-cases/{caseId}/investigate
POST /api/v1/reconciliation-cases/{caseId}/return-open
POST /api/v1/reconciliation-cases/{caseId}/resolve
```

- [ ] **Step 5: Run Case + Ledger invariant regression**

```powershell
cd backend
.\mvnw.cmd -Dtest=ReconciliationCaseLifecycleIntegrationTest,LedgerFinancialInvariantIntegrationTest,M6OpenApiContractTest test
```

Expected: PASS with no financial mutation from Case resolution.

- [ ] **Step 6: Commit and stop for Sol Checkpoint 1 review**

```powershell
git add backend/src/main/java/com/aicostops/reconciliation `
        backend/src/test/java/com/aicostops/reconciliation `
        docs/02-development/api/openapi.yaml
git commit -m "feat(reconciliation): add case resolution lifecycle"
```

**Checkpoint 1 evidence before proceeding:**

```powershell
cd backend
.\mvnw.cmd -Dtest='com.aicostops.reconciliation.*' test
.\mvnw.cmd -Dtest=ModuleDependencyArchitectureTest test
```

Sol reviews schema, truth semantics, hash/tolerance, case non-mutation, permission/privacy, and architecture boundaries before AIC-057/058 begins.

---

## Checkpoint 2 — AIC-057～AIC-058 Close Core

### Task 6: Add owner-module Close blocker data ports and real-MySQL invariant snapshots

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

**Interfaces:**

Each owner module returns bounded facts/counts; it does not return its mapper to Reconciliation. Example:

```java
public interface ExpenseCloseBlockerPort {
    BlockerItems approvedUnposted(
        long organizationId, Instant periodStart, Instant periodEnd, int sampleLimit);

    record BlockerItems(long count, List<Long> sampleIds) {}
}
```

Import port additionally treats unknown/partial period bounds conservatively.

Ledger/Budget integrity ports return typed mismatch counts/sample ids and budget aggregate snapshots needed to compare counters.

- [ ] **Step 1: Write one failing integration test matrix covering all source-port semantics**

Include fixtures for:

```text
unknown-period PENDING Import -> counted
FAILED relevant Import -> counted
CANCELED/CONFIRMED Import -> not counted
complete non-overlapping Import -> not counted
OPEN duplicate endpoint in period -> counted
terminal duplicate -> not counted
confirmed+CLEAN Charge without confirmed allocation -> counted
SUSPECTED/EXCLUDED Charge -> not double-counted as unallocated
APPROVED Expense uses expense_date UTC effective time -> counted
POSTED Expense -> not counted
Ledger posting with no entry -> integrity mismatch
normal entry/allocation mismatch -> integrity mismatch
correction reversal amount mismatch -> integrity mismatch
budget.actual drift -> mismatch
budget.committed drift -> mismatch
```

- [ ] **Step 2: Run and confirm failure**

```powershell
cd backend
.\mvnw.cmd -Dtest=CloseBlockerDataPortsIntegrationTest test
```

- [ ] **Step 3: Implement narrow SQL adapters with bounded samples**

Every port returns full `count` plus at most a fixed sample (for example 20 IDs). Do not serialize provider raw payloads into diagnostics.

For Expense use the same UTC financial-date interpretation as posting. Because `expense_date` is a SQL DATE, the query can compare against UTC-derived `LocalDate` bounds only when the BillingPeriod boundaries are exact UTC dates; otherwise perform the exact `expense_date at 00:00Z` half-open comparison in SQL/application without browser timezone assumptions.

- [ ] **Step 4: Implement Ledger/Budget integrity snapshot calculations without repair**

Budget actual check is exact:

```text
budget.actual_amount == SUM(ledger_entry.amount WHERE ledger_entry.budget_id = budget.id)
```

Committed check derives the expected outstanding counter from commitment states that currently contribute to `budget.committed_amount`; use the existing commitment status semantics, not a new status list invented in Reconciliation.

- [ ] **Step 5: Run blocker data tests plus existing M5 financial invariants**

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

### Task 7: Implement the seven blocker providers, registry, and informational Close readiness

**Files:**
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/CloseBlockerProvider.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/CloseBlockerResult.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/CloseBlockerRegistry.java`
- Create seven providers under: `backend/src/main/java/com/aicostops/reconciliation/application/blockers/`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/PeriodCloseQueryService.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/api/PeriodCloseController.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/api/PeriodCloseResponses.java`
- Extend: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Extend: `docs/02-development/api/openapi.yaml`
- Create: `backend/src/test/java/com/aicostops/reconciliation/CloseBlockerProviderIntegrationTest.java`

**Interfaces:**

```java
public interface CloseBlockerProvider {
    CloseBlockerCode code();
    CloseBlockerResult evaluate(CloseBlockerContext context);
}

public record CloseBlockerResult(
    CloseBlockerCode code,
    boolean passed,
    long itemCount,
    Map<String, Object> summary) {}
```

Registry validates at startup that each canonical enum code has exactly one provider.

- [ ] **Step 1: Write failing tests asserting the registry has exactly seven unique providers**

```java
assertThat(registry.providers())
    .extracting(CloseBlockerProvider::code)
    .containsExactlyInAnyOrder(CloseBlockerCode.values());
```

Add PASS/FAIL tests for every provider.

- [ ] **Step 2: Implement five source blockers + explicit pending-correction PASS**

`PENDING_CORRECTIONS` returns:

```java
return CloseBlockerResult.pass(
    CloseBlockerCode.PENDING_CORRECTIONS,
    0,
    Map.of("notApplicable", true,
           "reason", "M5 corrections are persisted only as committed POSTED groups"));
```

- [ ] **Step 3: Implement `OPEN_MATERIAL_RECONCILIATION` freshness in exact order**

It must require:

```text
latest period Run exists and COMPLETED
algorithm version current
tolerance snapshot == current policy
recomputed current basis hash == run basis_hash
unresolved Case count == 0
```

Do not fall back to an older Run if the latest is failed/running/stale.

- [ ] **Step 4: Implement `LEDGER_INTEGRITY` by combining owner-port snapshots**

Return FAIL when any frozen integrity invariant mismatches. Keep a bounded summary by category and sample ids.

- [ ] **Step 5: Add informational `close-readiness` endpoint**

```text
GET /api/v1/billing-periods/{periodId}/close-readiness
Permission: PERIOD_READ @ ORG
```

It evaluates the seven providers but does not set CLOSING and does not persist authoritative `period_close_check` rows. Response must label it as preview/readiness state.

- [ ] **Step 6: Run blocker/readiness tests**

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

### Task 8: Add the BillingPeriod financial write fence and retrofit known-period finance mutations

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

**Interfaces:**

```java
public interface BillingPeriodFinancialWriteFence {
    BillingPeriod lockOpenAt(long organizationId, Instant effectiveAt);
    BillingPeriod lockOpenById(long organizationId, long billingPeriodId);
    void lockOrganizationAndRequireNoClosingPeriod(long organizationId);
    boolean hasClosingPeriod(long organizationId);
}
```

`lockOrganizationAndRequireNoClosingPeriod` is used only for unknown-period/org-level admission paths; known-period finance writes use period-first methods.

- [ ] **Step 1: Write failing period-fence tests**

Prove:

```text
unique covering OPEN period locks and returns
CLOSING/CLOSED -> PERIOD_NOT_OPEN
missing/ambiguous period -> deterministic conflict
organization admission waits/serializes and rejects when any period is CLOSING
```

- [ ] **Step 2: Implement fence service and make `LedgerBudgetService` delegate to it**

Do not duplicate two independent OPEN-lock implementations. Preserve existing posting behavior and error contracts.

- [ ] **Step 3: Retrofit Budget create/update to period-first locking**

Create:

```text
transaction -> lockOpenById(command.billingPeriodId) -> validate/insert Budget
```

Update:

```text
pre-read org-scoped Budget identity
transaction -> lockOpenById(preRead.billingPeriodId)
            -> Budget FOR UPDATE
            -> revalidate same id/period/version
            -> update total
```

This fixes the real Budget→Period reverse-order Close race.

- [ ] **Step 4: Retrofit Commitment request without changing approve/release semantics**

For a new request after idempotency replay is ruled out:

```text
pre-read budget period
transaction reserve/replay
-> lockOpenById(budget.billingPeriodId)
-> re-read/validate Budget
-> insert REQUESTED commitment + approval lineage
```

Approve/Release already use Period→Budget→Commitment; refactor to the shared fence only if behavior remains byte/transaction equivalent.

- [ ] **Step 5: Retrofit Expense approve only**

Pre-read the org-scoped claim to derive:

```java
Instant effectiveAt = claim.expenseDate().atStartOfDay(ZoneOffset.UTC).toInstant();
```

Inside the existing idempotent transaction:

```text
reserve/replay first
replay -> return old response without OPEN gate
new approve -> lockOpenAt(effectiveAt)
            -> lock Expense
            -> verify expenseDate still equals pre-read date
            -> lock ApprovalCase
            -> APPROVED transition
```

`requestInfo` and `reject` do not introduce an APPROVED-unposted blocker and need no new period gate.

- [ ] **Step 6: Add real MySQL races for writer-first and Close/CLOSING-first behavior**

At this stage simulate the CLOSING state with the real period row lock/update; Close coordinator comes later.

Test Budget update, Commitment request, Expense approve, and verify existing Provider/Expense posting, Correction, Commitment approve/release remain period-first and pass their regression suites.

- [ ] **Step 7: Run known-period regression**

```powershell
cd backend
.\mvnw.cmd -Dtest=BillingPeriodFinancialWriteFenceIntegrationTest,KnownPeriodCloseRaceIntegrationTest,ProviderPostingConcurrencyIntegrationTest,LedgerCorrectionIntegrationTest test
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/aicostops/budget `
        backend/src/main/java/com/aicostops/expense/application/ExpenseReviewCommandService.java `
        backend/src/test/java/com/aicostops/budget/BillingPeriodFinancialWriteFenceIntegrationTest.java `
        backend/src/test/java/com/aicostops/reconciliation/KnownPeriodCloseRaceIntegrationTest.java `
        backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java
git commit -m "fix(close): fence known-period finance writes"
```

---

### Task 9: Fence unknown-period Import and Duplicate truth mutations without breaking replay/cleanup

**Files:**
- Modify: `backend/src/main/java/com/aicostops/ingestion/application/ProviderImportService.java`
- Modify: `backend/src/main/java/com/aicostops/ingestion/application/ImportWorkflowCommandService.java`
- Modify: `backend/src/main/java/com/aicostops/cost/review/application/DuplicateReviewCommandService.java`
- Modify: `backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/UnknownPeriodCloseRaceIntegrationTest.java`

**Interfaces:**
- Consumes `BillingPeriodFinancialWriteFence.lockOrganizationAndRequireNoClosingPeriod` only in workflow services that can introduce/change Close truth.
- Existing semantic replay paths remain allowed because they create no new truth.

- [ ] **Step 1: Write failing race/replay tests before edits**

Cover:

```text
new Provider Import batch vs CLOSING
existing identical ImportBatch reuse while CLOSING -> still returns existing batch
Import retry new successor vs CLOSING -> rejected
Import retry same successful Idempotency-Key replay while CLOSING -> returns stored success
Import confirm new transition vs CLOSING -> rejected
semantic re-confirm of already CONFIRMED same attempt -> succeeds without new truth
Import cancel during CLOSING -> remains allowed because it only reduces blocker
Duplicate scan chunk cannot create OPEN candidate after CLOSING wins admission
Duplicate keep remains allowed during CLOSING because it only reduces blocker
Duplicate exclude is fenced because it changes included external truth
```

- [ ] **Step 2: Retrofit ProviderImport creation with post-upload admission recheck**

Do not hold a DB lock while streaming Evidence. Keep storage outside the short DB transaction.

Inside `createOrReuseBatch`:

```text
find existing identity
existing -> reuse immediately
absent -> lock Organization admission
       -> recheck identity (concurrent winner convergence)
       -> require no CLOSING period
       -> insert Batch + Initial Attempt
```

If Evidence was stored but Close wins before Batch admission, leave the immutable Evidence reusable; do not invent a cross-system rollback.

- [ ] **Step 3: Retrofit retry/confirm after idempotency replay decision**

Pattern:

```text
reserve idempotency
same-key replay -> return stored response
new mutation -> lockOrganizationAndRequireNoClosingPeriod(org)
             -> continue existing Attempt/Batch locking/state machine
```

Keep Cancel ungated.

- [ ] **Step 4: Retrofit Duplicate scan/exclude conservatively at organization admission boundary**

Each scan persistence batch acquires the short org admission gate before creating new OPEN candidates. If Close starts between batches, already committed candidates are visible to Close; later candidate creation stops.

`keep` can remain legal during CLOSING. `exclude`, which changes current included external truth, requires no CLOSING period before its existing candidate/charge locks.

- [ ] **Step 5: Narrowly update ArchUnit allowed dependencies**

Allow only the workflow application packages/classes that consume `budget.application.BillingPeriodFinancialWriteFence`; do not permit `cost.domain`, canonical normalization, or generic ingestion infrastructure to depend on Budget.

- [ ] **Step 6: Run race plus existing Import/Duplicate concurrency tests**

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

### Task 10: Implement resumable BillingPeriod Close coordinator and persisted seven-check finalization

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

**Interfaces:**

```java
public interface BillingPeriodClosePort {
    void lockOrganizationAdmission(long organizationId);
    BillingPeriod lockPeriod(long organizationId, long periodId);
    void markClosing(long organizationId, long periodId, long expectedVersion, Instant now);
    void returnOpen(long organizationId, long periodId, long expectedVersion, Instant now);
    void markClosed(long organizationId, long periodId, long expectedVersion, Instant now);
    void reopen(long organizationId, long periodId, long expectedVersion, Instant now);
}
```

`PeriodCloseService.close(user, periodId)` owns begin/resume → evaluate → finalize orchestration.

- [ ] **Step 1: Write the Close state-machine tests first**

Test:

```text
OPEN -> CLOSING -> CLOSED
one FAIL -> CloseRun BLOCKED + period OPEN
one ERROR -> CloseRun FAILED + period OPEN
finalized attempt has exactly 7 Check rows
blocked retry same generation increments attempt_no
hard-crash simulation after begin leaves CLOSING+CHECKING; next close resumes same run
CLOSED retry returns current successful result with no second run/audit
ambiguous CLOSING without exactly one CHECKING run -> 409/integrity conflict
```

Provide a package-private/test seam that executes `beginOrResume` separately from `evaluateAndFinalize`; do not add a production-only debug endpoint.

- [ ] **Step 2: Implement Budget-owned period CAS mutations**

Under row lock, updates still require expected status/version in SQL. Example:

```sql
UPDATE billing_period
SET status='CLOSING', closing_started_at=#{now},
    version=version+1, updated_at=#{now}
WHERE id=#{periodId} AND org_id=#{organizationId}
  AND status='OPEN' AND version=#{expectedVersion}
```

Analogous exact transitions:

```text
CLOSING -> OPEN: closing_started_at=NULL, version+1
CLOSING -> CLOSED: closed_at=now, version+1
CLOSED -> OPEN (Task 11): generation+1, reopened_at=now, version+1
```

- [ ] **Step 3: Implement begin/resume transaction with global lock order**

```text
fresh auth + PERIOD_CLOSE ORG
Organization row FOR UPDATE
BillingPeriod FOR UPDATE
CLOSED -> return existing successful run
CLOSING -> find exactly one CHECKING run current generation and resume
OPEN -> insert CHECKING run with next attempt_no; mark CLOSING; audit start; commit
```

- [ ] **Step 4: Evaluate all seven providers independently**

Catch provider exceptions into `ERROR` results so final diagnostics still contain all seven codes. Do not persist partial authoritative Check rows before finalization.

- [ ] **Step 5: Finalize in one transaction**

```text
lock period + close run
revalidate CLOSING/CHECKING/generation
insert exactly 7 unique checks
if any ERROR -> run FAILED + period OPEN
else if any FAIL -> run BLOCKED + period OPEN
else -> run CLOSED + period CLOSED
append exactly one terminal audit
commit
```

Before terminal transition assert programmatically:

```java
if (results.size() != CloseBlockerCode.values().length
        || results.stream().map(CloseBlockerResult::code).distinct().count() != 7) {
    throw new IllegalStateException("A finalized Close must contain exactly seven blocker results");
}
```

- [ ] **Step 6: Add Close route and API contract**

```text
POST /api/v1/billing-periods/{periodId}/close
Permission: PERIOD_CLOSE @ ORG
```

Response includes period status, generation, CloseRun status/attempt, and seven checks. BLOCKED is not serialized as BillingPeriod status.

- [ ] **Step 7: Prove real row-lock races**

Use two executors/latches with real MySQL to prove both orders for at least Provider posting and one newly retrofitted writer:

```text
writer wins period/admission lock -> Close waits and sees committed result
Close wins -> writer sees CLOSING and cannot create new truth
```

Also prove committed posting replay in CLOSED still returns prior posting.

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

### Task 11: Implement privileged Reopen, generation semantics, close-history reads, and authorization matrix

**Files:**
- Extend: `backend/src/main/java/com/aicostops/reconciliation/application/PeriodCloseService.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/application/PeriodCloseQueryService.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/api/PeriodCloseController.java`
- Create/extend: `backend/src/main/java/com/aicostops/reconciliation/api/PeriodCloseRequests.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/api/PeriodCloseResponses.java`
- Extend: `docs/02-development/api/openapi.yaml`
- Create: `backend/src/test/java/com/aicostops/reconciliation/PeriodReopenIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/M6AuthorizationIntegrationTest.java`

**Interfaces:**

```java
public record ReopenPeriodCommand(String reasonCode, String reasonNote) {}
public PeriodCloseView reopen(AuthenticatedUser user, long periodId, ReopenPeriodCommand command)
```

Read APIs:

```text
GET /api/v1/billing-periods/{periodId}/close-runs
GET /api/v1/billing-periods/{periodId}/close-runs/{runId}
POST /api/v1/billing-periods/{periodId}/reopen
```

- [ ] **Step 1: Write failing Reopen/generation tests**

Prove:

```text
CLOSED -> OPEN only
reasonCode required
reasonNote nonblank required
latest successful CloseRun must match current generation
close_generation increments exactly once
reopened_at set, closing_started_at NULL
closed_at retained until next successful close
old close runs/checks/reconciliation/ledger rows unchanged
reopen when already OPEN -> 409 and no generation increment
next close after reopen uses new generation + attempt_no=1
```

- [ ] **Step 2: Implement Reopen under BillingPeriod lock**

```text
fresh auth -> PERIOD_REOPEN ORG
BillingPeriod FOR UPDATE
require CLOSED
require latest successful CloseRun current generation
validate reason fields
Budget close port CLOSED -> OPEN CAS with generation+1
append PERIOD_REOPENED audit containing old/new generation + reasonCode
commit
```

Do not put arbitrary long reason note into audit metadata if it exceeds the repository's bounded audit policy; persist user explanation in the command audit representation only as allowed by the existing secret-free convention.

- [ ] **Step 3: Add FINANCE_REVIEWER / FINANCE_ADMIN / SYSTEM_ADMIN authorization matrix**

Integration fixtures must prove:

```text
FINANCE_REVIEWER: reconciliation read/run/resolve + period read; no close/reopen
FINANCE_ADMIN: close/reopen allowed
SYSTEM_ADMIN: no implicit finance access
```

- [ ] **Step 4: Run Reopen/security/OpenAPI tests**

```powershell
cd backend
.\mvnw.cmd -Dtest=PeriodReopenIntegrationTest,M6AuthorizationIntegrationTest,M6OpenApiContractTest test
```

Expected: PASS.

- [ ] **Step 5: Commit and stop for Sol Checkpoint 2 review**

```powershell
git add backend/src/main/java/com/aicostops/reconciliation `
        backend/src/test/java/com/aicostops/reconciliation `
        docs/02-development/api/openapi.yaml
git commit -m "feat(close): add privileged period reopen"
```

**Checkpoint 2 evidence before frontend:**

```powershell
cd backend
.\mvnw.cmd test
```

Sol reviews all write-fence races, exactly-seven finalization, crash/resume, generation semantics, replay exceptions, ledger/budget integrity, and finance authorization before AIC-059 begins.

---

## Checkpoint 3 — AIC-059 Frontend / UAT

### Task 12: Build typed M6 React APIs, navigation, Reconciliation workflow, and Period Close workflow

**Files:**
- Create: `frontend/src/features/reconciliation/api/reconciliationApi.ts`
- Create: `frontend/src/features/reconciliation/presentation.ts`
- Create: `frontend/src/features/reconciliation/ReconciliationRunsPage.tsx`
- Create: `frontend/src/features/reconciliation/ReconciliationRunDetailPage.tsx`
- Create: `frontend/src/features/reconciliation/ReconciliationCaseDetailPage.tsx`
- Create: `frontend/src/features/period-close/api/periodCloseApi.ts`
- Create: `frontend/src/features/period-close/presentation.ts`
- Create: `frontend/src/features/period-close/PeriodClosePage.tsx`
- Create corresponding `*.test.tsx`/API tests for the new pages.
- Modify: `frontend/src/features/imports/api/importTypes.ts`
- Modify: `frontend/src/app/router/AppRouter.tsx`
- Modify: `frontend/src/app/layout/appNavigation.tsx`
- Modify: `frontend/src/app/layout/appNavigation.test.tsx`
- Modify: `frontend/src/app/layout/AuthenticatedLayout.tsx`
- Modify: `frontend/src/app/layout/AuthenticatedLayout.test.tsx`
- Modify: `frontend/src/styles.css` only for M6 layout styles that cannot be expressed by existing component classes.

**Interfaces:**

Frontend IDs/money remain strings. Example API types:

```ts
export type ReconciliationRunStatus = 'CREATED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type ReconciliationCaseStatus = 'OPEN' | 'INVESTIGATING' | 'RESOLVED'
export type ReconciliationCaseType = 'MISSING_INTERNAL' | 'MISSING_EXTERNAL' | 'AMOUNT_MISMATCH'
export type PeriodStatus = 'OPEN' | 'CLOSING' | 'CLOSED'
export type PeriodCloseRunStatus = 'CHECKING' | 'BLOCKED' | 'CLOSED' | 'FAILED'
export type CloseCheckResult = 'PASS' | 'FAIL' | 'ERROR'
```

- [ ] **Step 1: Write frontend type/API tests first**

Assert:

```text
all ids remain strings
money/difference/tolerance remain strings
close check enum includes PASS/FAIL/ERROR
ImportBatchStatus now includes READY_FOR_REVIEW and CONFIRMED
API requests never send caller tolerance/totals/basis hash
```

Update Import union exactly:

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

- [ ] **Step 2: Add permission-aware routes and navigation with icons**

Add business navigation:

```text
/reconciliation -> 对账        requires RECONCILIATION_READ
/period-close   -> 账期结算     requires PERIOD_READ
```

Add actual `NAV_ICONS` entries so desktop/mobile/collapsed layouts never render blank icon slots.

Router must protect nested mutation views using backend permissions for button visibility, while backend remains authoritative.

- [ ] **Step 3: Build Reconciliation list/detail/case flows**

Use TanStack Query keys scoped by period/run/case. UI displays backend values only:

```text
Run status / algorithm / tolerance / counts
provider + currency
external / internal / difference
Case type/status
investigate / return-open / resolve actions
```

Resolve modal requires Chinese reason/note copy and sends only reasonCode/resolutionNote.

Use `formatMoney(...)` or the existing money helper on decimal strings; never `Number(amount)` for accounting.

- [ ] **Step 4: Build Period Close page**

Show:

```text
BillingPeriod OPEN/CLOSING/CLOSED
close generation
readiness preview
exactly seven blocker cards/rows
latest CloseRun status + attempt
history by generation/attempt
Close action for PERIOD_CLOSE
Reopen action for PERIOD_REOPEN and CLOSED only
```

Render `BLOCKED` as `本次结算被阻断`, not a BillingPeriod status. Render `FAILED` separately as technical failure.

- [ ] **Step 5: Reuse shared date/time behavior**

Use:

```ts
formatBusinessDate(...)
formatBusinessDateRange(...)
formatEventDateTime(...)
```

from `frontend/src/lib/dateTime.ts`. Do not duplicate `Intl.DateTimeFormat` inside M6 pages. Expense business dates stay date-only.

- [ ] **Step 6: Add query invalidation after every mutation**

After Run/Case/Close/Reopen success invalidate the affected:

```text
reconciliation-runs
reconciliation-run detail
reconciliation-cases
billing-periods
close-readiness
close-runs
```

so stale status does not survive navigation.

- [ ] **Step 7: Run targeted frontend tests**

```powershell
cd frontend
npm test -- --run src/features/reconciliation src/features/period-close src/app/layout src/app/router src/features/imports
```

Expected: PASS.

- [ ] **Step 8: Run frontend quality gates**

```powershell
cd frontend
npm run lint
npm test -- --run
npm run build
```

Expected: all PASS.

- [ ] **Step 9: Commit**

```powershell
git add frontend/src
git commit -m "feat(frontend): add reconciliation and period close workflow"
```

---

### Task 13: Run full M6 regression, browser UAT, acceptance evidence, and final PR preparation

**Files:**
- Create: `docs/03-acceptance/implementation/16-m6-reconciliation-close-evidence.md`
- Modify only if test evidence proves drift: `docs/02-development/detailed-design/02-data-model.md`
- Modify only if test evidence proves drift: `docs/02-development/detailed-design/03-state-machines.md`
- Modify only if test evidence proves drift: `docs/02-development/detailed-design/04-transactions-idempotency-concurrency.md`
- Modify only if test evidence proves drift: `docs/02-development/detailed-design/06-permission-matrix.md`

**Interfaces:**
- Produces reviewable evidence for Issue #89 and the one final squash PR; no new business behavior is introduced here.

- [ ] **Step 1: Run complete backend tests from a clean branch state**

```powershell
cd backend
.\mvnw.cmd test
```

Expected: all unit, integration, architecture, migration and OpenAPI tests PASS.

- [ ] **Step 2: Run complete frontend quality gates**

```powershell
cd ..\frontend
npm ci
npm run lint
npm test -- --run
npm run build
```

Expected: all PASS.

- [ ] **Step 3: Run Compose smoke**

From repository root with the established development env:

```powershell
cd ..
docker compose build
docker compose up -d
docker compose ps
```

Expected: MySQL/Redis/MinIO/backend/frontend are healthy/running according to existing compose health contracts.

- [ ] **Step 4: Execute browser UAT on desktop/tablet/mobile layouts**

Use real finance-role accounts and verify these business paths:

```text
1. Run reconciliation on an OPEN period with an exact match.
2. Create a material mismatch, inspect Case, investigate, resolve with reason/note.
3. Confirm resolving Case alone does not change Ledger totals.
4. Preview Close readiness and see all seven blocker codes.
5. Demonstrate OPEN_IMPORTS / UNRESOLVED_DUPLICATES / UNALLOCATED_CHARGES /
   UNPOSTED_APPROVED_EXPENSES each links the operator to the owning workflow.
6. Close clean period: OPEN -> CLOSED, all seven checks PASS.
7. Blocked Close returns period to OPEN and history shows BLOCKED attempt.
8. Reopen CLOSED period with reason; generation increments; old close history remains.
9. Verify FINANCE_REVIEWER cannot Close/Reopen; FINANCE_ADMIN can.
10. Verify SYSTEM_ADMIN-only account has no finance actions.
11. Verify all M6 visible copy is Chinese, sidebar icons exist in all layouts,
    event times use shared formatter, business dates do not shift.
```

- [ ] **Step 5: Record exact evidence, not assertions without output**

Acceptance doc includes:

```text
branch + head SHA
schema migration version
backend command + PASS summary
frontend lint/test/build summaries
Compose service state
browser UAT matrix and screenshots/reference notes as appropriate
known non-goals / no deferred blocker
```

Do not claim full pass until the commands in Steps 1–4 have actually been run on the implementation head.

- [ ] **Step 6: Sync detailed-design docs only where implementation now differs from pre-M6 documents**

Update the canonical data model/state machine/concurrency/permission docs to the actual implemented contract. Do not rewrite unrelated historical sections.

- [ ] **Step 7: Run final diff hygiene**

```powershell
git status --short
git diff --check
git diff main...HEAD --stat
```

Expected:

```text
no secrets
no generated build artifacts
no accidental V1-V15 edits
no unrelated refactors
```

- [ ] **Step 8: Commit acceptance/doc sync if changed**

```powershell
git add docs
git commit -m "docs(m6): record reconciliation close acceptance"
```

- [ ] **Step 9: Stop for Sol Checkpoint 3/final review before opening PR**

Sol must review:

```text
spec coverage
financial truth correctness
all row-lock/race proofs
Case non-mutation
exactly-seven Close checks
crash/resume and replay semantics
Reopen history preservation
authorization/privacy/OpenAPI
frontend no-JS-money + localization/time formatting
full regression/UAT evidence
```

Only after this review is clean should the existing project workflow open the single final PR for Issue #89 and use squash merge.

---

## Plan Self-Review Checklist

Before implementation starts, the executor/reviewer must be able to map every frozen design invariant to a task above:

```text
V16 four-table schema                         -> Task 1
M6 permission/architecture boundary           -> Task 2
confirmed Charge external truth               -> Task 3
immutable provider Ledger internal truth      -> Task 3
server tolerance + deterministic basis hash   -> Task 3
Run history/API                               -> Task 4
Case lifecycle + no Ledger mutation           -> Task 5
seven blocker fact sources                    -> Task 6
seven blocker providers/readiness             -> Task 7
known-period write fence                      -> Task 8
unknown-period admission/replay               -> Task 9
CLOSING/resume/exact-seven finalization       -> Task 10
Reopen generation/history                     -> Task 11
React workflow/localization/time/money        -> Task 12
full regression/UAT/evidence                  -> Task 13
```

No task may replace an explicit rule above with a generic abstraction, a Redis lock, browser calculation, or a deferred follow-up issue.
