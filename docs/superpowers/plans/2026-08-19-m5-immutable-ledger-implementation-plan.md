# M5 Immutable Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver AIC-047～AIC-053 as one integrated M5 branch that turns confirmed Provider charges and approved/allocated Expenses into immutable Ledger truth, updates Budget actuals, optionally consumes explicitly linked Commitments, exposes scoped lineage/correction APIs, and ships the Ledger React workflow.

**Architecture:** `com.aicostops.ledger` owns posting history, correction history, ledger queries and orchestration. Existing modules keep ownership of Charge, Allocation, Expense, BillingPeriod/Budget/Commitment and expose narrow application seams for Ledger; Ledger application code must not import another module's infrastructure mapper. All financial mutations run in MySQL transactions with BillingPeriod → Budgets(id ASC) → Commitments(id ASC) → Source → AllocationDecision → AllocationLines ordering, immutable Ledger rows, durable natural-key/idempotency constraints, and secret-free transactional audit.

**Tech Stack:** Java 21, Spring Boot 4.1.0, MyBatis Spring Boot 4.1.0, MySQL/Flyway, Testcontainers 2.0.5, ArchUnit 1.4.2, React 19.2.8, TypeScript 6.0.3, React Router 7.18.2, TanStack Query 5.101.4, Ant Design 6.6.0, Vitest 4.1.10.

**Spec:** `docs/superpowers/specs/2026-08-19-m5-immutable-ledger-design.md`

## Global Constraints

- Delivery Issue: `#87 — M5 Immutable Ledger — AIC-047 ~ AIC-053`.
- Delivery branch: `feat/m5-immutable-ledger`; one final M5 PR only after integrated implementation/local evidence.
- Baseline: `main@a835cd4b213fd85709e67ae957ba9b28da505137`; do not edit or rewrite M0～M4 history.
- Migrations are forward-only after V12; never modify V1～V12.
- Money: MySQL `DECIMAL(20,8)`, Java `BigDecimal`, HTTP decimal string, frontend string; no `float`, `double`, or JS `Number` for financial truth.
- HTTP IDs stay decimal strings even though backend persistence uses `BIGINT`.
- Normal Provider/Expense Posting requires an OPEN BillingPeriod resolved from the business source; CLOSING/CLOSED rejects with `PERIOD_NOT_OPEN`.
- Ledger history is immutable: no application UPDATE/DELETE of committed postings/entries/correction groups.
- Provider stable key: `CHARGE:{chargeFactId}:ALLOCATION:{allocationDecisionId}`.
- Expense stable key: `EXPENSE:{expenseClaimId}`.
- Correction uses caller `Idempotency-Key`, `api_idempotency`, and a server-created CorrectionGroup/Posting; historical Ledger/closed-period Budget rows are never mutated.
- LedgerEntry is 1:1 with AllocationLine for normal posting; preserve line order via `entry_index = line_index`.
- Budget matching is deterministic: exact scope in posting period/currency → ORG fallback → no Budget. Existing exact and ORG budgets are never both updated for one entry.
- Missing Budget or over-budget result never blocks a real cost posting.
- Commitment linkage is optional and explicit, at most one linked Commitment per AllocationLine; no automatic commitment selection. A linked line must have a positive amount because the existing consume primitive accepts only positive `entryAmount`; CREDIT/negative entries post normally but cannot consume a Commitment.
- Expense Posting atomically transitions `APPROVED -> POSTED`; M5 does not add VOIDED.
- Finance review queue must retain APPROVED claims until POSTED so posting-ready claims remain discoverable.
- `LEDGER_READ = ORG|PROJECT|TEAM|COST_CENTER`, `LEDGER_POST = ORG`, `LEDGER_CORRECT = ORG`; SYSTEM_ADMIN does not gain Finance permissions.
- Provider posting requires `LEDGER_POST`; Expense posting requires both `EXPENSE_POST` and `LEDGER_POST`.
- Audit failure rolls back the complete financial transaction.
- Redis is not a financial correctness source or idempotency anchor.
- M6 Reconciliation/Close, standalone Commitment consume HTTP/UI, FX correction, broad Auth refactor and unrelated M4 polish remain out of scope.
- `.zcode/` and `start-dev.bat` are local-only tooling and must not be staged or changed.

---

## Execution bootstrap (run once before Task 1)

**Files:** none.

**Interfaces:** Consumes the remote M5 Design commit and produces a clean local execution branch.

- [ ] **Step 1: Verify local repository state from PowerShell**

```powershell
Set-Location E:\AI-CostOps
git status --short --branch
git branch --show-current
git rev-parse HEAD
```

Expected before switching: local `main` may show only `?? .zcode/` and `?? start-dev.bat`; do not add/delete them.

- [ ] **Step 2: Fetch and switch to the prepared M5 branch**

```powershell
git fetch origin
if (git show-ref --verify --quiet refs/heads/feat/m5-immutable-ledger) {
    git switch feat/m5-immutable-ledger
    git pull --ff-only origin feat/m5-immutable-ledger
} else {
    git switch --track -c feat/m5-immutable-ledger origin/feat/m5-immutable-ledger
}
git status --short --branch
git log -3 --oneline
```

Expected: branch is `feat/m5-immutable-ledger`; the latest commits include the M5 design/plan docs; only the two intentional local untracked tools may remain.

- [ ] **Step 3: Baseline regression before feature code**

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd verify
Set-Location E:\AI-CostOps\frontend
npm test -- --run
npm run lint
npm run build
```

Expected: all baseline backend unit/integration tests and frontend tests/lint/build pass. If baseline fails, stop feature implementation and report the exact failing command/output before changing M5 code.

---

### Task 1: AIC-047 — Immutable Ledger / Correction schema foundation

**Files:**
- Create: `backend/src/main/resources/db/migration/V13__m5_immutable_ledger_schema.sql`
- Create: `backend/src/main/java/com/aicostops/ledger/domain/LedgerSourceType.java`
- Create: `backend/src/main/java/com/aicostops/ledger/domain/LedgerEntryType.java`
- Create: `backend/src/main/java/com/aicostops/ledger/domain/LedgerPosting.java`
- Create: `backend/src/main/java/com/aicostops/ledger/domain/LedgerEntry.java`
- Create: `backend/src/main/java/com/aicostops/ledger/domain/CorrectionMode.java`
- Create: `backend/src/main/java/com/aicostops/ledger/domain/CorrectionGroup.java`
- Create: `backend/src/main/java/com/aicostops/ledger/infrastructure/LedgerPostingMapper.java`
- Create: `backend/src/test/java/com/aicostops/ledger/M5LedgerSchemaIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java`

**Interfaces:**
- Produces: immutable persistence types and mapper primitives used by Tasks 4～9.
- `LedgerPostingMapper.insertPosting(...) -> int`, `lastInsertId() -> long`, `selectPostingByKey(orgId, postingKey)`, `selectPostingByIdAndOrganization(...)`, `insertEntry(...) -> int`, `selectEntryByIdAndOrganization(...)`, `insertCorrectionGroup(...) -> int`.

- [ ] **Step 1: Write the migration integration test first**

Test exact schema invariants against real MySQL/Testcontainers:

```java
@Test
void v13CreatesImmutableLedgerAndCompletesCommitmentLineage() {
    assertTable("ledger_posting");
    assertTable("ledger_entry");
    assertTable("correction_group");
    assertUnique("ledger_posting", "org_id", "posting_key");
    assertUnique("ledger_entry", "posting_id", "entry_index");
    assertUnique("correction_group", "org_id", "target_entry_id");
    assertForeignKey("budget_commitment_usage", "ledger_entry_id", "ledger_entry", "id");
}
```

Also test: same-org FKs; exactly-one target CHECK; normal source consistency; signed DECIMAL(20,8); `allocation_line(id, org_id)` composite unique added before Ledger FK; correction group target entry/posting integrity; existing V1～V12 checksums/history are unchanged.

- [ ] **Step 2: Run only the new schema test and prove RED**

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd -Dit.test=M5LedgerSchemaIntegrationTest verify
```

Expected: FAIL because V13/tables do not exist.

- [ ] **Step 3: Implement V13 with staged circular-FK creation**

Required DDL order:

```text
ALTER allocation_line ADD UNIQUE(id, org_id)
CREATE ledger_posting
CREATE ledger_entry (correction_group_id column present; FK added later)
CREATE correction_group (FK target_entry_id -> ledger_entry, target_posting_id -> ledger_posting)
ALTER ledger_entry ADD correction_group same-org FK
ALTER budget_commitment_usage ADD same-org ledger_entry FK
```

Freeze at least:

```text
ledger_posting: UQ(org_id,posting_key), source_type PROVIDER_CHARGE|EXPENSE_CLAIM|CORRECTION, status POSTED
ledger_entry: UQ(posting_id,entry_index), one target PROJECT|COST_CENTER|TEAM, signed amount, optional budget/source/allocation/correction/reversal lineage
correction_group: UQ(org_id,correction_key), UQ(org_id,target_entry_id), status POSTED
```

- [ ] **Step 4: Add framework-free Ledger domain records/enums and mapper INSERT/SELECT primitives**

Use `BigDecimal` for amounts and `Instant` for ledger instants. Do not add repository UPDATE/DELETE methods for committed Ledger tables.

- [ ] **Step 5: Run schema + architecture tests GREEN**

```powershell
.\mvnw.cmd -Dit.test=M5LedgerSchemaIntegrationTest verify
.\mvnw.cmd -Dtest=ModuleDependencyArchitectureTest test
```

Expected: PASS.

- [ ] **Step 6: Commit the schema stage**

```powershell
git add backend/src/main/resources/db/migration/V13__m5_immutable_ledger_schema.sql backend/src/main/java/com/aicostops/ledger backend/src/test/java/com/aicostops/ledger/M5LedgerSchemaIntegrationTest.java backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java
git commit -m "feat(m5): establish immutable ledger schema"
```

---

### Task 2: Activate Ledger authorization and authenticated endpoint shell

**Files:**
- Modify: `backend/src/main/java/com/aicostops/iam/domain/M1AdminPermissionPolicy.java`
- Modify: `backend/src/test/java/com/aicostops/iam/domain/M1AdminPermissionPolicyTest.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Create: `backend/src/main/java/com/aicostops/ledger/api/LedgerController.java`
- Create: `backend/src/main/java/com/aicostops/ledger/api/LedgerRequests.java`
- Create: `backend/src/main/java/com/aicostops/ledger/api/LedgerResponses.java`
- Create: `backend/src/test/java/com/aicostops/ledger/LedgerSecurityIntegrationTest.java`

**Interfaces:**
- Produces current applicable Ledger permission scopes and authenticated route registration.
- Controller routes to freeze now: `POST /costs/charges/{id}/post`, `POST /expenses/{id}/post`, `GET /ledger/postings`, `GET /ledger/postings/{id}`, `GET /ledger/entries`, `GET /ledger/entries/{id}`, `POST /ledger/corrections`.

- [ ] **Step 1: Add failing permission-policy assertions**

```java
assertEquals(Set.of(ORG, PROJECT, TEAM, COST_CENTER), applicableScopes("LEDGER_READ"));
assertEquals(Set.of(ORG), applicableScopes("LEDGER_POST"));
assertEquals(Set.of(ORG), applicableScopes("LEDGER_CORRECT"));
```

Also retain the existing SYSTEM_ADMIN seed boundary; do not alter V3 seed.

- [ ] **Step 2: Run policy test RED, implement mappings, rerun GREEN**

```powershell
.\mvnw.cmd -Dtest=M1AdminPermissionPolicyTest test
```

Expected first run: Ledger assertions fail; second run after implementation: PASS.

- [ ] **Step 3: Register M5 endpoints as `.authenticated()` in SecurityConfiguration**

Keep business authorization in services; security config only blocks anonymous requests and preserves `.anyRequest().denyAll()`.

- [ ] **Step 4: Add security integration cases**

Cover anonymous 401, authenticated-without-applicable-permission 403 once service stubs exist, and no accidental public route.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/aicostops/iam backend/src/test/java/com/aicostops/iam backend/src/main/java/com/aicostops/ledger/api backend/src/test/java/com/aicostops/ledger/LedgerSecurityIntegrationTest.java
git commit -m "feat(m5): activate ledger authorization contract"
```

---

### Task 3: Add narrow posting seams in Cost, Allocation, Expense and Budget

**Files:**
- Create: `backend/src/main/java/com/aicostops/cost/application/ChargePostingPort.java`
- Create: `backend/src/main/java/com/aicostops/cost/infrastructure/ChargePostingAdapter.java`
- Create: `backend/src/main/java/com/aicostops/allocation/application/AllocationPostingPort.java`
- Create: `backend/src/main/java/com/aicostops/allocation/infrastructure/AllocationPostingAdapter.java`
- Create: `backend/src/main/java/com/aicostops/expense/application/ExpensePostingPort.java`
- Create: `backend/src/main/java/com/aicostops/expense/application/ExpensePostingService.java`
- Create: `backend/src/main/java/com/aicostops/budget/application/LedgerBudgetPort.java`
- Create: `backend/src/main/java/com/aicostops/budget/application/LedgerBudgetService.java`
- Modify: `backend/src/main/java/com/aicostops/budget/infrastructure/BudgetMapper.java`
- Modify: `backend/src/main/java/com/aicostops/budget/infrastructure/BudgetCommitmentMapper.java`
- Create: `backend/src/test/java/com/aicostops/ledger/PostingPortIntegrationTest.java`

**Interfaces:**

Define exact contracts before Ledger orchestration:

```java
public interface ChargePostingPort {
    ChargePostingSource load(long organizationId, long chargeFactId);
    ChargePostingSource lockAndRequirePostable(long organizationId, long chargeFactId, long expectedDecisionId);
}
```

`ChargePostingSource` includes `id, amount, currency, periodStart, currentAllocationDecisionId, reviewStatus, confirmedImport`.

```java
public interface AllocationPostingPort {
    ConfirmedAllocation load(long organizationId, long decisionId);
    ConfirmedAllocation lockConfirmed(long organizationId, long decisionId, AllocationSubjectType subjectType, long subjectId);
}
```

`ConfirmedAllocation` includes the CONFIRMED decision and ordered immutable `AllocationLine` list.

```java
public interface ExpensePostingPort {
    ExpensePostingSource load(long organizationId, long expenseId);
    ExpensePostingSource lockAndRequireApproved(long organizationId, long expenseId, long expectedDecisionId);
    void markPosted(long organizationId, long expenseId, long expectedVersion, Instant now);
}
```

`ExpensePostingSource` includes `id, amount, currency, expenseDate, currentAllocationDecisionId, version, status`.

```java
public interface LedgerBudgetPort {
    BillingPeriod lockOpenPeriodAt(long organizationId, Instant effectiveAt);
    List<BudgetSelection> resolveSelections(long organizationId, long billingPeriodId, List<EntryScopeAmount> entries);
    List<Budget> lockBudgets(long organizationId, Collection<Long> budgetIds);
    List<BudgetCommitment> lockCommitments(long organizationId, Collection<Long> commitmentIds);
    void incrementActual(long organizationId, long budgetId, BigDecimal signedAmount, Instant now);
}
```

`lockBudgets`/`lockCommitments` must sort distinct ids ascending internally. `resolveSelections` applies exact-target then ORG fallback and returns null budget when absent.

- [ ] **Step 1: Write integration tests proving seams revalidate under row locks**

Cover Charge confirmed-import + CLEAN + pointer equality; Allocation CONFIRMED + subject equality + ordered lines; Expense APPROVED + pointer equality; Budget period lock/revalidate; deterministic exact/ORG/no-budget selection; positive-only commitment-link eligibility.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd -Dit.test=PostingPortIntegrationTest verify
```

Expected: compile/test failure because seams do not exist.

- [ ] **Step 3: Implement the narrow seams using each owner module's existing mapper/repository**

Do not expose another module's Mapper through an interface. Do not reuse `AllocationSubjectPort.assertConfirmEligible` for Posting because that method models pre-confirm state.

Add to `BudgetMapper` an atomic signed update with no availability guard:

```sql
UPDATE budget
SET actual_amount = actual_amount + #{amount},
    version = version + 1,
    updated_at = #{now}
WHERE id=#{budgetId} AND org_id=#{organizationId} AND status='ACTIVE'
```

- [ ] **Step 4: Run GREEN and architecture test**

```powershell
.\mvnw.cmd -Dit.test=PostingPortIntegrationTest verify
.\mvnw.cmd -Dtest=ModuleDependencyArchitectureTest test
```

Expected: PASS and Ledger application remains independent of foreign infrastructure packages.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/aicostops/cost backend/src/main/java/com/aicostops/allocation backend/src/main/java/com/aicostops/expense backend/src/main/java/com/aicostops/budget backend/src/test/java/com/aicostops/ledger/PostingPortIntegrationTest.java
git commit -m "feat(m5): add ledger posting module seams"
```

---

### Task 4: AIC-048 — Provider Charge Posting transaction

**Files:**
- Create: `backend/src/main/java/com/aicostops/ledger/application/LedgerPostingCommands.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/ProviderChargePostingService.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/LedgerAuditPort.java`
- Create: `backend/src/main/java/com/aicostops/ledger/infrastructure/AuditLedgerAdapter.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerController.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerRequests.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerResponses.java`
- Create: `backend/src/test/java/com/aicostops/ledger/ProviderChargePostingIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/ledger/ProviderChargePostingApiIntegrationTest.java`

**Interfaces:**

```java
record CommitmentLink(long allocationLineId, long commitmentId) {}
record PostSourceCommand(List<CommitmentLink> commitmentLinks) {}
LedgerPostingDetail post(AuthenticatedUser user, long chargeFactId, PostSourceCommand command)
```

No caller `Idempotency-Key` and no caller `allocationDecisionId` for normal Provider Posting.

- [ ] **Step 1: Write happy-path failing integration test**

Fixture must create: CONFIRMED import lineage → CLEAN Charge → CONFIRMED AllocationDecision with at least two lines → OPEN period → exact Budget for one line + ORG fallback for another → optional positive linked Commitment.

Assert:

```text
one LedgerPosting with stable key
one LedgerEntry per AllocationLine, same ordered amounts/currency/target
exact budget updated only for exact line
ORG fallback updated only for fallback line
commitment usage references the inserted LedgerEntry
full entry amount increments actual, min(entry,remaining) decrements committed
one secret-free LEDGER_CHARGE_POSTED audit
```

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd -Dit.test=ProviderChargePostingIntegrationTest verify
```

Expected: FAIL because posting service/API are incomplete.

- [ ] **Step 3: Implement transaction and fixed lock ordering**

Required sequence inside one transaction:

```text
pre-read charge/current decision/lines and candidate ids
lock source-effective BillingPeriod and require OPEN
lock distinct selected Budgets id ASC
lock distinct linked Commitments id ASC
lock Charge
lock AllocationDecision
lock AllocationLines line_index ASC
revalidate import confirmed + CLEAN + current pointer + CONFIRMED + sum/currency
validate each commitment link belongs to its selected budget and line amount > 0
check existing posting_key and replay if present
insert posting
insert entries in line order
increment actual once per entry with matching budget
call CommitmentConsumeService.consume(...) only for explicit links
append transactional audit
commit
```

Use bounded deadlock retry ×3 around the complete transaction, matching existing project pattern.

- [ ] **Step 4: Add API contract tests**

Cover 200 response/replay, decimal-string amounts, string IDs, 403 no `LEDGER_POST`, privacy-preserving 404 cross-org, 409 `PERIOD_NOT_OPEN`, 409 allocation/source conflict, 400 malformed/duplicate commitment links.

- [ ] **Step 5: Run provider posting suite GREEN**

```powershell
.\mvnw.cmd -Dit.test=ProviderChargePostingIntegrationTest,ProviderChargePostingApiIntegrationTest verify
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/aicostops/ledger backend/src/test/java/com/aicostops/ledger/ProviderChargePostingIntegrationTest.java backend/src/test/java/com/aicostops/ledger/ProviderChargePostingApiIntegrationTest.java
git commit -m "feat(m5): implement provider charge posting"
```

---

### Task 5: Prove Provider Posting concurrency, rollback, over-budget and no-budget behavior

**Files:**
- Create: `backend/src/test/java/com/aicostops/ledger/ProviderPostingConcurrencyIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/ledger/ProviderPostingRollbackIntegrationTest.java`
- Modify only if tests expose defects: `ProviderChargePostingService.java`, Ledger/owner seams.

**Interfaces:** Consumes Task 4 transaction and proves financial invariants rather than adding new API surface.

- [ ] **Step 1: Add concurrent duplicate posting test**

Launch two real transactions against the same Charge and assert after both complete:

```text
COUNT(ledger_posting for stable key) = 1
COUNT(entries) = allocation line count
budget actual applied exactly once
commitment usage applied exactly once
one posting audit event
both callers converge to the same posting id
```

- [ ] **Step 2: Add rollback tests**

Inject/fake `LedgerAuditPort` failure after entry/counter work and assert no posting/entry/actual/commitment usage survives. Add CLOSING/CLOSED period rollback cases.

- [ ] **Step 3: Add governance-not-admission tests**

Exact cases:

```text
no matching budget => posting succeeds, entry.budgetId null
over-budget after actual => posting succeeds and Budget read model overBudget=true
negative credit => signed actual decreases; no commitment link allowed
entry amount > commitment remaining => remaining consumed; excess remains uncommitted actual
```

- [ ] **Step 4: Run focused tests**

```powershell
.\mvnw.cmd -Dit.test=ProviderPostingConcurrencyIntegrationTest,ProviderPostingRollbackIntegrationTest verify
```

Expected: PASS consistently on repeated execution.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/test/java/com/aicostops/ledger backend/src/main/java/com/aicostops/ledger backend/src/main/java/com/aicostops/budget backend/src/main/java/com/aicostops/cost backend/src/main/java/com/aicostops/allocation
git commit -m "test(m5): prove provider posting invariants"
```

---

### Task 6: AIC-049 — Expense POSTED state and Expense Posting transaction

**Files:**
- Create: `backend/src/main/resources/db/migration/V14__m5_expense_posted_state.sql`
- Modify: `backend/src/main/java/com/aicostops/expense/domain/ExpenseClaimStatus.java`
- Modify: `backend/src/main/java/com/aicostops/expense/infrastructure/ExpenseClaimMapper.java`
- Modify: `backend/src/main/java/com/aicostops/expense/application/ExpensePostingService.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/ExpensePostingService.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerController.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerRequests.java`
- Create: `backend/src/test/java/com/aicostops/ledger/ExpensePostingIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/ledger/ExpensePostingApiIntegrationTest.java`
- Modify: existing Expense lifecycle/read-model tests that enumerate statuses.

**Interfaces:**
- `Ledger ExpensePostingService.post(AuthenticatedUser user, long expenseId, PostSourceCommand command)`.
- Owner-module `ExpensePostingPort.markPosted(...)` performs exactly `APPROVED -> POSTED` with version increment under the already-held claim lock.

- [ ] **Step 1: Write V14/state-machine RED tests**

Assert DB accepts POSTED after V14, enum allows only `APPROVED -> POSTED`, POSTED is terminal, and existing lifecycle transitions remain unchanged.

- [ ] **Step 2: Run RED, implement migration + enum evolution, rerun GREEN**

```powershell
.\mvnw.cmd -Dtest=ExpenseClaimStatusTest test
.\mvnw.cmd -Dit.test=ExpenseLifecycleIntegrationTest verify
```

If no focused enum test exists, create `backend/src/test/java/com/aicostops/expense/ExpenseClaimStatusTest.java` before the first command.

- [ ] **Step 3: Fix Finance review queue semantics before posting UI depends on it**

Change `ExpenseClaimMapper.selectReviewQueue/countReviewQueue` so `APPROVED` includes all APPROVED claims, not only those with `current_allocation_decision_id IS NULL`; POSTED is excluded. Add a test proving an APPROVED + CONFIRMED allocation remains discoverable until the post succeeds, then disappears after POSTED.

- [ ] **Step 4: Write Expense Posting RED test**

Fixture: APPROVED Expense + evidence + current CONFIRMED allocation + OPEN period. Assert stable key `EXPENSE:{id}`, 1:1 entries, Budget/Commitment semantics identical to Provider, and claim becomes POSTED in same transaction.

- [ ] **Step 5: Implement Expense Posting**

Use `expenseDate.atStartOfDay(ZoneOffset.UTC).toInstant()` for source period resolution. Require both `EXPENSE_POST@ORG` and `LEDGER_POST@ORG`. Lock order mirrors Provider and calls `markPosted` only after Ledger/counter/audit work succeeds inside the same transaction.

- [ ] **Step 6: Add replay/rollback/security cases**

Cover concurrent duplicate post, audit failure leaves APPROVED, CLOSING/CLOSED leaves APPROVED, missing/non-confirmed allocation rejects, cross-org 404, permission 403, positive-only Commitment link.

- [ ] **Step 7: Run Expense suite GREEN**

```powershell
.\mvnw.cmd -Dit.test=ExpensePostingIntegrationTest,ExpensePostingApiIntegrationTest,ExpenseLifecycleIntegrationTest verify
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/resources/db/migration/V14__m5_expense_posted_state.sql backend/src/main/java/com/aicostops/expense backend/src/main/java/com/aicostops/ledger backend/src/test/java/com/aicostops/expense backend/src/test/java/com/aicostops/ledger
git commit -m "feat(m5): implement expense posting"
```

---

### Task 7: AIC-050 — Scoped Ledger Query and full lineage API

**Files:**
- Create: `backend/src/main/java/com/aicostops/ledger/application/LedgerReadModels.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/LedgerQueryService.java`
- Create: `backend/src/main/java/com/aicostops/ledger/infrastructure/LedgerQueryMapper.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerController.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerResponses.java`
- Create: `backend/src/test/java/com/aicostops/ledger/LedgerQueryIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/ledger/LedgerLineageApiIntegrationTest.java`

**Interfaces:**

```text
GET /api/v1/ledger/postings
GET /api/v1/ledger/postings/{postingId}
GET /api/v1/ledger/entries
GET /api/v1/ledger/entries/{entryId}
```

Filters: `billingPeriodId`, `sourceType`, `projectId`, `costCenterId`, `teamId`, `page`, `size`, `sort=postedAt,asc|desc` only.

- [ ] **Step 1: Write scoped visibility RED tests**

Mirror BudgetQueryService semantics: missing applicable LEDGER_READ => 403; ORG grant sees org ledger; typed grants see only matching target entries; out-of-scope/cross-org detail => 404. A mixed-scope posting detail exposes only visible entries and any totals are computed from visible entries only.

- [ ] **Step 2: Add mapper read projections**

Ledger-owned `LedgerQueryMapper` may JOIN read-only source tables to build lineage; application code must not call foreign infrastructure mappers. Keep list queries paged and whitelist sort/filter parameters.

- [ ] **Step 3: Build Provider and Expense lineage read models**

Provider:

```text
Entry -> Posting -> AllocationLine/Decision -> ChargeFact -> RawProviderRecord -> ImportAttempt -> ImportBatch -> Evidence
```

Expense:

```text
Entry -> Posting -> AllocationLine/Decision -> ExpenseClaim -> Evidence(if any)
```

Do not fabricate provider lineage for Expense.

- [ ] **Step 4: Run query/lineage GREEN**

```powershell
.\mvnw.cmd -Dit.test=LedgerQueryIntegrationTest,LedgerLineageApiIntegrationTest verify
```

Expected: PASS; JSON IDs strings, money scale-8 strings.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/aicostops/ledger backend/src/test/java/com/aicostops/ledger/LedgerQueryIntegrationTest.java backend/src/test/java/com/aicostops/ledger/LedgerLineageApiIntegrationTest.java
git commit -m "feat(m5): add ledger query and lineage"
```

---

### Task 8: AIC-051 — Immutable Correction Posting

**Files:**
- Create: `backend/src/main/java/com/aicostops/ledger/application/LedgerCorrectionService.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/application/LedgerPostingCommands.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerController.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerRequests.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerResponses.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/infrastructure/LedgerPostingMapper.java`
- Create: `backend/src/test/java/com/aicostops/ledger/LedgerCorrectionIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/ledger/LedgerCorrectionApiIntegrationTest.java`

**Interfaces:**

```java
CorrectionResult correct(AuthenticatedUser user, CorrectionCommand command, String idempotencyKey)
```

`CorrectionCommand` exactly carries targetEntryId, correctionPeriodId, mode `REVERSAL_ONLY|REPLACE`, reasonCode, optional reasonText, optional replacement amount/currency/exactly-one target.

- [ ] **Step 1: Write REVERSAL_ONLY RED test**

Assert target historical entry/posting never changes; OPEN correction period gets one CORRECTION posting + one REVERSAL with `amount=-target.amount`, copied source lineage/dimensions and `reverses_entry_id=target`.

- [ ] **Step 2: Write REPLACE RED test**

Assert same reversal plus one signed replacement entry in correction period; replacement currency must equal target currency; exact/ORG/no-budget selection applies to the new correction entries; correction never consumes Commitments.

- [ ] **Step 3: Implement idempotent correction transaction**

Sequence:

```text
validate Idempotency-Key and request hash
reserve api_idempotency
read target identity
lock chosen correction BillingPeriod; require OPEN
resolve + lock correction Budgets id ASC
lock target Entry + parent Posting
reject if target already has committed CorrectionGroup (UQ also enforces)
insert CorrectionGroup
insert CORRECTION posting with key CORRECTION:{groupId}
insert reversal; optional replacement
apply signed actual deltas only to correction-period budgets
audit LEDGER_CORRECTION_POSTED
finalize idempotency response
commit
```

- [ ] **Step 4: Add conflict/security/rollback tests**

Cover same key/same hash replay, same key/different hash 409, double reversal 409, CLOSED correction period reject, FX replacement reject 400, missing LEDGER_CORRECT 403, cross-org target 404, audit failure rolls back group/posting/entries/budget/idempotency finalization.

- [ ] **Step 5: Run correction GREEN**

```powershell
.\mvnw.cmd -Dit.test=LedgerCorrectionIntegrationTest,LedgerCorrectionApiIntegrationTest verify
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/aicostops/ledger backend/src/test/java/com/aicostops/ledger/LedgerCorrectionIntegrationTest.java backend/src/test/java/com/aicostops/ledger/LedgerCorrectionApiIntegrationTest.java
git commit -m "feat(m5): implement correction posting"
```

---

### Task 9: AIC-052 — Executable Ledger invariants and architecture gate

**Files:**
- Modify: `backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java`
- Create: `backend/src/test/java/com/aicostops/ledger/LedgerImmutabilityArchitectureTest.java`
- Create: `backend/src/test/java/com/aicostops/ledger/LedgerFinancialInvariantIntegrationTest.java`

**Interfaces:** No new production API; converts design rules into regression gates.

- [ ] **Step 1: Add ArchUnit rules**

Enforce:

```text
ingestion.. must not depend on ledger..
ledger.application.. must not depend on ..infrastructure packages outside ledger
ledger.domain.. framework-free
no production Ledger mapper/service method named/annotated as destructive update/delete for ledger_posting/ledger_entry/correction_group
```

- [ ] **Step 2: Add financial invariant integration matrix**

One suite must re-prove:

```text
duplicate Provider and Expense posting converges
period CLOSED/CLOSING rejects normal posting
no Budget posts
Budget overrun posts
commitment consume + actual + usage atomic
Expense POSTED + Ledger atomic
correction preserves historical rows
one reversal per target entry
cross-org lineage invisible
```

- [ ] **Step 3: Run architecture + invariant suites**

```powershell
.\mvnw.cmd -Dtest=ModuleDependencyArchitectureTest,LedgerImmutabilityArchitectureTest test
.\mvnw.cmd -Dit.test=LedgerFinancialInvariantIntegrationTest verify
```

Expected: PASS.

- [ ] **Step 4: Commit**

```powershell
git add backend/src/test/java/com/aicostops/architecture backend/src/test/java/com/aicostops/ledger
git commit -m "test(m5): enforce ledger invariants"
```

---

### Task 10: AIC-053 — Ledger frontend API, navigation, list/detail/lineage pages

**Files:**
- Create: `frontend/src/features/ledger/api/ledgerApi.ts`
- Create: `frontend/src/features/ledger/api/ledgerKeys.ts`
- Create: `frontend/src/features/ledger/presentation.ts`
- Create: `frontend/src/features/ledger/LedgerListPage.tsx`
- Create: `frontend/src/features/ledger/LedgerPostingDetailPage.tsx`
- Create: `frontend/src/features/ledger/LedgerEntryDetailPage.tsx`
- Create: `frontend/src/features/ledger/LedgerPages.test.tsx`
- Modify: `frontend/src/app/router/AppRouter.tsx`
- Modify: `frontend/src/app/layout/appNavigation.tsx`
- Modify: `frontend/src/app/layout/appNavigation.test.tsx`
- Modify: `frontend/src/app/layout/AuthenticatedLayout.tsx`
- Modify: `frontend/src/app/layout/AuthenticatedLayout.test.tsx`

**Interfaces:**
- Frontend routes: `/ledger`, `/ledger/postings/:id`, `/ledger/entries/:id` under `PermissionRoute permission="LEDGER_READ"`.
- `ledgerApi` mirrors backend string ID/decimal-string models and never converts money to Number.

- [ ] **Step 1: Write frontend RED tests for permission route/nav**

Assert `LEDGER_READ` shows `账本` nav with an icon in desktop/mobile layout; no permission hides it; direct route is permission-gated.

```powershell
Set-Location E:\AI-CostOps\frontend
npm test -- --run src/app/layout/appNavigation.test.tsx src/app/layout/AuthenticatedLayout.test.tsx
```

Expected first run: FAIL.

- [ ] **Step 2: Implement API types and route/navigation shell**

Use existing `apiClient`, `PageResponse`, shared ProblemDetail and shared date/time formatters. Add `/ledger` icon to `NAV_ICONS` in all layout modes via the shared map.

- [ ] **Step 3: Write and implement Ledger list/detail tests**

List: filters, source type, posted time, period, per-currency amounts without cross-currency aggregation, entry count, correction marker.

Posting detail: visible header + entries; mixed-scope response displays only server-returned visible entries.

Entry detail: render Provider/Expense/Correction lineage with Simplified Chinese labels while retaining stable codes/ids where useful.

- [ ] **Step 4: Run ledger frontend tests GREEN**

```powershell
npm test -- --run src/features/ledger/LedgerPages.test.tsx src/app/layout/appNavigation.test.tsx src/app/layout/AuthenticatedLayout.test.tsx
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add frontend/src/features/ledger frontend/src/app/router/AppRouter.tsx frontend/src/app/layout
git commit -m "feat(m5): add ledger workflow ui"
```

---

### Task 11: Provider/Expense Posting UI, explicit Commitment links, and Correction UX

**Files:**
- Create: `frontend/src/features/ledger/PostingAction.tsx`
- Create: `frontend/src/features/ledger/PostingAction.test.tsx`
- Create: `frontend/src/features/ledger/CorrectionAction.tsx`
- Create: `frontend/src/features/ledger/CorrectionAction.test.tsx`
- Modify: `frontend/src/features/costs/CostDetailPage.tsx`
- Modify: `frontend/src/features/expenses/ExpenseReviewDetailPage.tsx`
- Modify: `frontend/src/features/expenses/api/expenseApi.ts` (`POSTED` response status)
- Modify: relevant existing Cost/Expense page tests.

**Interfaces:**
- Post buttons call `ledgerApi.postCharge` / `ledgerApi.postExpense` and invalidate source, allocation, budget, commitment and ledger query keys.
- Correction action calls `ledgerApi.correct(..., crypto.randomUUID())` with explicit confirmation.

- [ ] **Step 1: Write Provider Posting action RED test**

Show action only when user has `LEDGER_POST`, source is confirmed-import/CLEAN and confirmed allocation exists. A backend 409 is presented and relevant queries are refreshed; financial mutation is never silently retried.

- [ ] **Step 2: Implement optional Commitment picker without inventing authority**

For each positive AllocationLine, use existing Budget reads to present the same exact-target → ORG fallback candidate and fetch `GET /commitments?budgetId=...` for optional selection. Do not offer a picker for negative/zero lines or no visible Budget. Backend remains authoritative and revalidates every link.

- [ ] **Step 3: Write Expense Posting action RED test**

Show only when `expense.status==='APPROVED'`, `postingReady===true`, and user has both `EXPENSE_POST` + `LEDGER_POST`. Success refreshes review queue/detail and renders POSTED; add `POSTED` to frontend `ExpenseClaimStatus`/Chinese labels.

- [ ] **Step 4: Implement Correction action tests and UI**

Only `LEDGER_CORRECT` users see it. Support `REVERSAL_ONLY` and `REPLACE`; show immutable original entry, chosen OPEN correction period and signed effect before submit; replacement requires same currency and exactly one target.

- [ ] **Step 5: Run focused frontend suite**

```powershell
npm test -- --run src/features/ledger/PostingAction.test.tsx src/features/ledger/CorrectionAction.test.tsx src/features/expenses/ExpensePages.test.tsx
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add frontend/src/features/ledger frontend/src/features/costs frontend/src/features/expenses
git commit -m "feat(m5): connect posting and correction ux"
```

---

### Task 12: OpenAPI and canonical design-document synchronization

**Files:**
- Modify: `docs/02-development/api/openapi.yaml`
- Modify: `docs/02-development/detailed-design/01-module-boundaries.md`
- Modify: `docs/02-development/detailed-design/02-data-model.md`
- Modify: `docs/02-development/detailed-design/03-state-machines.md`
- Modify: `docs/02-development/detailed-design/04-transactions-idempotency-concurrency.md`
- Modify: `docs/02-development/detailed-design/06-permission-matrix.md`
- Modify: `docs/02-development/detailed-design/09-frontend-information-architecture.md`
- Modify: `docs/02-development/implementation/02-issue-backlog.md`
- Add/modify OpenAPI contract tests under `backend/src/test/java/com/aicostops/ledger/`.

**Interfaces:** Documentation must match delivered code; no speculative M6 behavior.

- [ ] **Step 1: Update OpenAPI from implemented DTOs/endpoints**

Document Provider/Expense post, ledger list/detail, correction, filters, Idempotency-Key only on correction, string IDs, decimal-string money, ProblemDetail responses and permissions.

- [ ] **Step 2: Update canonical docs to the M5 reality**

Explicitly record:

```text
BillingPeriod remains in budget for M5
Expense APPROVED -> POSTED implemented
Finance queue retains APPROVED until POSTED
exact-scope -> ORG fallback budget selection
positive-only explicit commitment link
Ledger permissions/scopes
immutable correction behavior
M6 close/reconciliation still deferred
```

- [ ] **Step 3: Add/execute contract checks**

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd -Dtest=*OpenApiContractTest test
```

Expected: PASS; M5 paths and schemas are present and existing API contracts are not regressed.

- [ ] **Step 4: Commit**

```powershell
git add docs backend/src/test/java/com/aicostops/ledger
git commit -m "docs(m5): synchronize immutable ledger contracts"
```

---

### Task 13: Integrated regression, local evidence, push and handoff to Sol

**Files:** no intentional production changes; fix only defects found by the verification commands and commit fixes separately with scoped messages.

**Interfaces:** Produces the evidence Sol needs for independent remote review. This task does not open or merge the PR.

- [ ] **Step 1: Full backend verification**

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd verify
```

Expected: BUILD SUCCESS; unit, architecture and all `*IntegrationTest` suites pass against Testcontainers/MySQL as configured.

- [ ] **Step 2: Full frontend verification**

```powershell
Set-Location E:\AI-CostOps\frontend
npm test -- --run
npm run lint
npm run build
```

Expected: all tests pass, ESLint exits 0, TypeScript/Vite build exits 0.

- [ ] **Step 3: Verify migration immutability and local diff hygiene**

```powershell
Set-Location E:\AI-CostOps
git diff origin/main -- backend/src/main/resources/db/migration/V1__foundation_baseline.sql backend/src/main/resources/db/migration/V2__m1_identity_organization_schema.sql backend/src/main/resources/db/migration/V3__seed_v1_roles_permissions.sql backend/src/main/resources/db/migration/V4__m2_evidence_import_schema.sql backend/src/main/resources/db/migration/V5__m2_finance_reviewer_provider_account_read.sql backend/src/main/resources/db/migration/V6__m2_raw_provider_record_usage_window_check.sql backend/src/main/resources/db/migration/V7__m2_import_attempt_lease.sql backend/src/main/resources/db/migration/V8__m3_canonical_cost_foundation.sql backend/src/main/resources/db/migration/V9__m3_duplicate_attribution_foundation.sql backend/src/main/resources/db/migration/V10__m4_expense_approval.sql backend/src/main/resources/db/migration/V11__m4_budget_period_schema.sql backend/src/main/resources/db/migration/V12__m4_budget_commitment_approval.sql
git status --short --branch
git log --oneline origin/main..HEAD
```

Expected: first command produces no diff for V1～V12; `.zcode/` and `start-dev.bat` remain untracked/local only; log shows semantic M5 stages.

- [ ] **Step 4: Push the complete branch**

```powershell
git push origin feat/m5-immutable-ledger
```

Expected: remote branch advances successfully.

- [ ] **Step 5: Send Sol the execution evidence**

Return exactly these items:

```text
1. git rev-parse HEAD
2. git status --short --branch
3. git log --oneline origin/main..HEAD
4. backend .\mvnw.cmd verify final BUILD SUCCESS tail
5. frontend npm test -- --run summary
6. frontend npm run lint result
7. frontend npm run build result
8. any tests that required fixes and the final fix commit(s)
9. confirmation that V1～V12 diff is empty
10. known non-blocking limitations, if any, tied to #87 scope
```

Do not open the final PR from the implementation agent unless Sol/user explicitly asks. Sol will independently inspect the pushed branch, run remote diff/architecture/transaction review, request fixes if needed, then open/manage the single final M5 PR and CI/UAT gate. Merge remains prohibited until explicit user authorization.

---

## Plan self-review checklist

Before declaring implementation complete, the executor must verify every item below is backed by a task/test above:

```text
AIC-047 schema + same-org/FK/immutability
AIC-048 Provider posting + stable-key concurrency + Budget + Commitment + audit
AIC-049 Expense posting + POSTED + queue discoverability + rollback
AIC-050 scoped query + Provider/Expense/Correction lineage
AIC-051 reversal/replacement correction + idempotency + historical immutability
AIC-052 architecture/financial invariants
AIC-053 React Ledger + posting/correction actions
OpenAPI/canonical docs
full backend/frontend regression
one-branch/one-final-PR delivery
```

No M6 Reconciliation/Close implementation belongs in this plan.