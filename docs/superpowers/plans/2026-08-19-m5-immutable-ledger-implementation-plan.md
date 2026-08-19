# M5 Immutable Ledger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver AIC-047～AIC-053 as one integrated M5 branch that turns confirmed Provider charges and approved/allocated Expenses into immutable Ledger truth, updates Budget actuals, optionally consumes explicitly linked Commitments, exposes scoped lineage/correction APIs, and ships the Ledger React workflow.

**Architecture:** `com.aicostops.ledger` owns posting history, correction history, Ledger queries and financial orchestration. Existing modules keep ownership of Charge, Allocation, Expense, BillingPeriod/Budget/Commitment and expose narrow application seams to Ledger; `ledger.application` must not import another module's infrastructure mapper. All financial mutations use MySQL transactions with BillingPeriod → Budgets(id ASC) → Commitments(id ASC) → Source → AllocationDecision → AllocationLines ordering, immutable Ledger rows, durable uniqueness/idempotency and secret-free transactional audit.

**Tech Stack:** Java 21, Spring Boot 4.1.0, MyBatis Spring Boot 4.1.0, MySQL/Flyway, Testcontainers 2.0.5, ArchUnit 1.4.2, React 19.2.8, TypeScript 6.0.3, React Router 7.18.2, TanStack Query 5.101.4, Ant Design 6.6.0, Vitest 4.1.10.

**Spec:** `docs/superpowers/specs/2026-08-19-m5-immutable-ledger-design.md`

## Global Constraints

- Delivery Issue: `#87 — M5 Immutable Ledger — AIC-047 ~ AIC-053`.
- Delivery branch: `feat/m5-immutable-ledger`; one final M5 PR only after integrated implementation/local evidence.
- Baseline: `main@a835cd4b213fd85709e67ae957ba9b28da505137`; do not rewrite M0～M4 history.
- Migrations are forward-only after V12; never modify V1～V12.
- Money: MySQL `DECIMAL(20,8)`, Java `BigDecimal`, HTTP decimal string, frontend string; no `float`, `double`, or JS `Number` for financial truth.
- HTTP IDs stay decimal strings even though backend persistence uses `BIGINT`.
- Normal Provider/Expense Posting requires an OPEN BillingPeriod resolved from the business source; CLOSING/CLOSED rejects with `PERIOD_NOT_OPEN`.
- Ledger history is immutable: no application UPDATE/DELETE of committed postings/entries/correction groups.
- Provider stable key: `CHARGE:{chargeFactId}:ALLOCATION:{allocationDecisionId}`.
- Expense stable key: `EXPENSE:{expenseClaimId}`.
- Correction requires caller `Idempotency-Key`, uses `api_idempotency`, and creates new CorrectionGroup/Posting/Entries; historical Ledger and closed-period Budget rows are never mutated.
- Normal LedgerEntry is 1:1 with AllocationLine and preserves `entry_index = line_index`.
- Budget matching: exact scope in posting period/currency → ORG fallback → no Budget. One entry never updates exact and ORG budgets simultaneously.
- Missing Budget or an over-budget result never blocks a real cost posting.
- Commitment linkage is optional/explicit, at most one linked Commitment per AllocationLine; no automatic selection. Linked lines must have positive amounts because `CommitmentConsumeService` rejects non-positive `entryAmount`; CREDIT/negative entries still post but cannot consume a Commitment.
- Expense Posting atomically transitions `APPROVED -> POSTED`; M5 does not add VOIDED.
- Finance review queue retains APPROVED claims until POSTED so posting-ready claims remain discoverable.
- `LEDGER_READ = ORG|PROJECT|TEAM|COST_CENTER`, `LEDGER_POST = ORG`, `LEDGER_CORRECT = ORG`; SYSTEM_ADMIN does not gain Finance permissions.
- Provider posting requires `LEDGER_POST`; Expense posting requires both `EXPENSE_POST` and `LEDGER_POST`.
- Audit failure rolls back the complete financial transaction.
- Redis is not a financial correctness source or idempotency anchor.
- M6 Reconciliation/Close, standalone Commitment consume HTTP/UI, FX correction, broad Auth refactor and unrelated M4 polish remain out of scope.
- `.zcode/` and `start-dev.bat` are local-only tooling and must not be staged or changed.

---

## Execution bootstrap

**Files:** none.

**Interfaces:** Produces a clean local branch containing the approved M5 Design + Plan before feature implementation.

- [ ] **Step 1: Verify local state**

```powershell
Set-Location E:\AI-CostOps
git status --short --branch
git branch --show-current
git rev-parse HEAD
```

Expected: local `main` may show only `?? .zcode/` and `?? start-dev.bat`; never add/delete them.

- [ ] **Step 2: Fetch/switch the prepared M5 branch**

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

Expected: current branch `feat/m5-immutable-ledger`; latest history contains the M5 design/plan docs; only intentional local untracked tools may remain.

- [ ] **Step 3: Prove baseline green before M5 code**

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd verify
Set-Location E:\AI-CostOps\frontend
npm test -- --run
npm run lint
npm run build
```

Expected: all commands exit 0. A baseline failure is reported with exact output before M5 implementation starts.

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

**Interfaces:** Produces immutable Ledger persistence primitives used by Tasks 4～9. `LedgerPostingMapper` exposes INSERT/SELECT only for committed Ledger tables: `insertPosting`, `lastInsertId`, `selectPostingByKey`, `selectPostingByIdAndOrganization`, `insertEntry`, `selectEntryByIdAndOrganization`, `insertCorrectionGroup`.

- [ ] **Step 1: Write the real-MySQL migration test first**

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

Also assert same-org FKs, exactly-one target CHECK, normal source consistency, signed `DECIMAL(20,8)`, composite `allocation_line(id,org_id)` target, correction target entry/posting integrity, and unchanged V1～V12 history.

- [ ] **Step 2: Run RED**

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd -Dit.test=M5LedgerSchemaIntegrationTest verify
```

Expected: FAIL because V13/tables do not exist.

- [ ] **Step 3: Implement staged V13 DDL**

```text
ALTER allocation_line ADD UNIQUE(id, org_id)
CREATE ledger_posting
CREATE ledger_entry with correction_group_id column but delayed FK
CREATE correction_group referencing target ledger_entry + ledger_posting
ALTER ledger_entry ADD correction_group same-org FK
ALTER budget_commitment_usage ADD same-org ledger_entry FK
```

Freeze `ledger_posting.source_type = PROVIDER_CHARGE|EXPENSE_CLAIM|CORRECTION`, `status=POSTED`; `ledger_entry` has signed amount, exactly one target, optional budget/source/allocation/correction/reversal lineage; `correction_group` has `UQ(org_id,correction_key)` and `UQ(org_id,target_entry_id)`.

- [ ] **Step 4: Add framework-free domain records/enums + mapper INSERT/SELECT**

Use `BigDecimal`/`Instant`; do not create production UPDATE/DELETE methods for committed Ledger tables.

- [ ] **Step 5: Run GREEN**

```powershell
.\mvnw.cmd -Dit.test=M5LedgerSchemaIntegrationTest verify
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/resources/db/migration/V13__m5_immutable_ledger_schema.sql backend/src/main/java/com/aicostops/ledger backend/src/test/java/com/aicostops/ledger/M5LedgerSchemaIntegrationTest.java
git commit -m "feat(m5): establish immutable ledger schema"
```

---

### Task 2: Activate Ledger permission scopes and security matchers

**Files:**
- Modify: `backend/src/main/java/com/aicostops/iam/domain/M1AdminPermissionPolicy.java`
- Modify: `backend/src/test/java/com/aicostops/iam/domain/M1AdminPermissionPolicyTest.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify: `backend/src/test/java/com/aicostops/shared/security/SecurityConfigurationTest.java`

**Interfaces:** Produces applicable permission scopes and authenticated route matchers; business authorization stays in Task 4/6/7/8 services.

- [ ] **Step 1: Add RED permission assertions**

```java
assertEquals(Set.of(ORG, PROJECT, TEAM, COST_CENTER), applicableScopes("LEDGER_READ"));
assertEquals(Set.of(ORG), applicableScopes("LEDGER_POST"));
assertEquals(Set.of(ORG), applicableScopes("LEDGER_CORRECT"));
```

- [ ] **Step 2: Run RED then implement mappings**

```powershell
.\mvnw.cmd -Dtest=M1AdminPermissionPolicyTest test
```

Expected first run: new Ledger assertions fail; after mappings: PASS. Do not modify V3 role/permission seed; SYSTEM_ADMIN boundary stays unchanged.

- [ ] **Step 3: Add authenticated matchers for all frozen M5 paths**

```text
POST /api/v1/costs/charges/{id}/post
POST /api/v1/expenses/{id}/post
GET  /api/v1/ledger/postings
GET  /api/v1/ledger/postings/{id}
GET  /api/v1/ledger/entries
GET  /api/v1/ledger/entries/{id}
POST /api/v1/ledger/corrections
```

Keep `.anyRequest().denyAll()`.

- [ ] **Step 4: Extend SecurityConfigurationTest for matcher coverage**

Verify anonymous requests to each M5 path are rejected by authentication rather than falling through as public routes; authenticated business permission behavior is tested when endpoints exist in later tasks.

- [ ] **Step 5: Run GREEN and commit**

```powershell
.\mvnw.cmd -Dtest=M1AdminPermissionPolicyTest,SecurityConfigurationTest test
git add backend/src/main/java/com/aicostops/iam backend/src/test/java/com/aicostops/iam backend/src/test/java/com/aicostops/shared/security/SecurityConfigurationTest.java
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

```java
public interface ChargePostingPort {
    ChargePostingSource load(long organizationId, long chargeFactId);
    ChargePostingSource lockAndRequirePostable(long organizationId, long chargeFactId, long expectedDecisionId);
}
```

`ChargePostingSource`: `id, amount, currency, periodStart, currentAllocationDecisionId, reviewStatus, confirmedImport`.

```java
public interface AllocationPostingPort {
    ConfirmedAllocation load(long organizationId, long decisionId);
    ConfirmedAllocation lockConfirmed(long organizationId, long decisionId, AllocationSubjectType subjectType, long subjectId);
}
```

`ConfirmedAllocation`: CONFIRMED decision + ordered immutable `AllocationLine` list.

```java
public interface ExpensePostingPort {
    ExpensePostingSource load(long organizationId, long expenseId);
    ExpensePostingSource lockAndRequireApproved(long organizationId, long expenseId, long expectedDecisionId);
    void markPosted(long organizationId, long expenseId, long expectedVersion, Instant now);
}
```

`ExpensePostingSource`: `id, amount, currency, expenseDate, currentAllocationDecisionId, version, status`.

```java
public interface LedgerBudgetPort {
    BillingPeriod lockOpenPeriodAt(long organizationId, Instant effectiveAt);
    List<BudgetSelection> resolveSelections(long organizationId, long billingPeriodId, List<EntryScopeAmount> entries);
    List<Budget> lockBudgets(long organizationId, Collection<Long> budgetIds);
    List<BudgetCommitment> lockCommitments(long organizationId, Collection<Long> commitmentIds);
    void incrementActual(long organizationId, long budgetId, BigDecimal signedAmount, Instant now);
}
```

`lockBudgets`/`lockCommitments` sort distinct ids ascending internally. `resolveSelections` applies exact target then ORG fallback and returns a null Budget when absent.

- [ ] **Step 1: Write RED integration tests for all seams**

Cover Charge confirmed-import+CLEAN+pointer equality; Allocation CONFIRMED+subject equality+ordered lines; Expense APPROVED+pointer equality; BillingPeriod lock/revalidate; exact/ORG/no-budget selection; linked Commitment belongs to selected Budget and is consumable.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd -Dit.test=PostingPortIntegrationTest verify
```

Expected: compile/test failure because seams do not exist.

- [ ] **Step 3: Implement seams using each owner module's own persistence**

Do not expose foreign Mappers. Do not reuse `AllocationSubjectPort.assertConfirmEligible` for Posting; it models pre-confirm state.

Add this signed actual mutation to `BudgetMapper`:

```sql
UPDATE budget
SET actual_amount=actual_amount+#{amount},
    version=version+1,
    updated_at=#{now}
WHERE id=#{budgetId} AND org_id=#{organizationId} AND status='ACTIVE'
```

- [ ] **Step 4: Run GREEN**

```powershell
.\mvnw.cmd -Dit.test=PostingPortIntegrationTest verify
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/aicostops/cost backend/src/main/java/com/aicostops/allocation backend/src/main/java/com/aicostops/expense backend/src/main/java/com/aicostops/budget backend/src/test/java/com/aicostops/ledger/PostingPortIntegrationTest.java
git commit -m "feat(m5): add ledger posting module seams"
```

---

### Task 4: AIC-048 — Provider Charge Posting transaction + HTTP API

**Files:**
- Create: `backend/src/main/java/com/aicostops/ledger/application/LedgerPostingCommands.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/LedgerReadModels.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/ProviderChargePostingService.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/LedgerAuditPort.java`
- Create: `backend/src/main/java/com/aicostops/ledger/infrastructure/AuditLedgerAdapter.java`
- Create: `backend/src/main/java/com/aicostops/ledger/api/LedgerController.java`
- Create: `backend/src/main/java/com/aicostops/ledger/api/LedgerRequests.java`
- Create: `backend/src/main/java/com/aicostops/ledger/api/LedgerResponses.java`
- Create: `backend/src/test/java/com/aicostops/ledger/ProviderChargePostingIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/ledger/ProviderChargePostingApiIntegrationTest.java`

**Interfaces:**

```java
record CommitmentLink(long allocationLineId, long commitmentId) {}
record PostSourceCommand(List<CommitmentLink> commitmentLinks) {}
LedgerPostingDetail post(AuthenticatedUser user, long chargeFactId, PostSourceCommand command)
```

HTTP: `POST /api/v1/costs/charges/{chargeFactId}/post`, no caller `allocationDecisionId`, no Idempotency-Key.

- [ ] **Step 1: Write happy-path RED integration test**

Fixture: CONFIRMED import → CLEAN Charge → CONFIRMED AllocationDecision with two lines → OPEN period → exact Budget for one line + ORG fallback for another → optional positive linked Commitment.

Assert one stable-key Posting, one Entry per line, exact amounts/currency/targets, exact budget only on exact line, ORG only on fallback line, usage references inserted Entry, actual increments full Entry, committed decrements `min(entry,remaining)`, one secret-free `LEDGER_CHARGE_POSTED` audit.

- [ ] **Step 2: Run RED**

```powershell
.\mvnw.cmd -Dit.test=ProviderChargePostingIntegrationTest verify
```

- [ ] **Step 3: Implement transaction with fixed lock order**

```text
pre-read Charge/current Decision/lines/candidate ids
lock source-effective BillingPeriod; require OPEN
lock distinct selected Budgets id ASC
lock distinct linked Commitments id ASC
lock Charge
lock AllocationDecision
lock AllocationLines line_index ASC
revalidate import confirmed + CLEAN + current pointer + CONFIRMED + sum/currency
validate links: unique per line, line belongs decision, amount>0, Commitment belongs selected Budget
check/return existing posting_key
insert Posting
insert Entries in line order
increment Budget actual exactly once per budgeted Entry
call CommitmentConsumeService.consume only for explicit links
append Ledger audit
commit
```

Wrap the whole transaction in bounded deadlock retry ×3, retrying only retryable MySQL deadlock/serialization losers.

- [ ] **Step 4: Add API RED/GREEN cases**

Cover 200 response/replay, string IDs, scale-8 amount strings, 401 anonymous, 403 no LEDGER_POST, cross-org 404, period 409, allocation/source 409, malformed/duplicate links 400.

- [ ] **Step 5: Run GREEN and commit**

```powershell
.\mvnw.cmd -Dit.test=ProviderChargePostingIntegrationTest,ProviderChargePostingApiIntegrationTest verify
git add backend/src/main/java/com/aicostops/ledger backend/src/test/java/com/aicostops/ledger/ProviderChargePostingIntegrationTest.java backend/src/test/java/com/aicostops/ledger/ProviderChargePostingApiIntegrationTest.java
git commit -m "feat(m5): implement provider charge posting"
```

---

### Task 5: Prove Provider Posting concurrency, rollback and governance invariants

**Files:**
- Create: `backend/src/test/java/com/aicostops/ledger/ProviderPostingConcurrencyIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/ledger/ProviderPostingRollbackIntegrationTest.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/application/ProviderChargePostingService.java`

**Interfaces:** No new endpoint; strengthens Task 4 behavior.

- [ ] **Step 1: Add concurrent duplicate test**

Two real transactions post the same Charge. Final assertions:

```text
one ledger_posting stable key
one entry set
actual applied once
commitment usage applied once
one posting audit
both callers return same posting id
```

- [ ] **Step 2: Add rollback test**

Inject a failing `LedgerAuditPort` after writes/counters and assert no Posting/Entry/actual/usage survives. Add CLOSING/CLOSED rollback cases.

- [ ] **Step 3: Add governance-not-admission cases**

```text
no Budget => succeeds, entry.budgetId null
over-budget => succeeds, Budget read model overBudget=true
negative CREDIT => signed actual decreases, Commitment link rejected
entry > remaining Commitment => remaining consumed, excess is uncommitted actual
```

- [ ] **Step 4: Run GREEN twice**

```powershell
.\mvnw.cmd -Dit.test=ProviderPostingConcurrencyIntegrationTest,ProviderPostingRollbackIntegrationTest verify
.\mvnw.cmd -Dit.test=ProviderPostingConcurrencyIntegrationTest,ProviderPostingRollbackIntegrationTest verify
```

Expected: both runs PASS.

- [ ] **Step 5: Commit**

```powershell
git add backend/src/test/java/com/aicostops/ledger/ProviderPostingConcurrencyIntegrationTest.java backend/src/test/java/com/aicostops/ledger/ProviderPostingRollbackIntegrationTest.java backend/src/main/java/com/aicostops/ledger/application/ProviderChargePostingService.java
git commit -m "test(m5): prove provider posting invariants"
```

---

### Task 6: AIC-049 — Expense POSTED state, discoverable posting queue and Expense Posting

**Files:**
- Create: `backend/src/main/resources/db/migration/V14__m5_expense_posted_state.sql`
- Create: `backend/src/test/java/com/aicostops/expense/ExpenseClaimStatusTest.java`
- Create: `backend/src/test/java/com/aicostops/expense/ExpensePostedMigrationIntegrationTest.java`
- Modify: `backend/src/main/java/com/aicostops/expense/domain/ExpenseClaimStatus.java`
- Modify: `backend/src/main/java/com/aicostops/expense/infrastructure/ExpenseClaimMapper.java`
- Modify: `backend/src/main/java/com/aicostops/expense/application/ExpensePostingService.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/ExpensePostingService.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerController.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerRequests.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerResponses.java`
- Create: `backend/src/test/java/com/aicostops/ledger/ExpensePostingIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/ledger/ExpensePostingApiIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/expense/ExpenseLifecycleIntegrationTest.java`

**Interfaces:** Ledger `ExpensePostingService.post(AuthenticatedUser,long,PostSourceCommand)`. Owner `ExpensePostingPort.markPosted(...)` is exactly APPROVED→POSTED with version increment under the already-held claim lock.

- [ ] **Step 1: Write RED state/migration tests**

Assert V14 permits POSTED, enum permits only APPROVED→POSTED, POSTED terminal, earlier transitions unchanged.

```powershell
.\mvnw.cmd -Dtest=ExpenseClaimStatusTest test
.\mvnw.cmd -Dit.test=ExpensePostedMigrationIntegrationTest verify
```

Expected first run: FAIL.

- [ ] **Step 2: Implement V14 + enum and rerun GREEN**

Alter the existing expense status CHECK forward-only; never edit V10.

- [ ] **Step 3: Change review queue semantics with test**

`selectReviewQueue/countReviewQueue`: APPROVED filter and ALL include every APPROVED claim until POSTED, regardless of `current_allocation_decision_id`. Add integration assertion: APPROVED+CONFIRMED allocation remains in queue; after successful POSTED it disappears.

- [ ] **Step 4: Write Expense Posting RED test**

Fixture APPROVED claim + current CONFIRMED allocation + OPEN covering period. Assert `EXPENSE:{id}`, 1:1 Entries, Budget/Commitment behavior matches Provider, claim becomes POSTED atomically.

- [ ] **Step 5: Implement Expense Posting**

Use `expenseDate.atStartOfDay(ZoneOffset.UTC).toInstant()`. Require both `EXPENSE_POST@ORG` and `LEDGER_POST@ORG`. Use the same lock order as Provider and call `markPosted` within the same transaction after Ledger/counter/audit operations have succeeded.

- [ ] **Step 6: Add replay/rollback/security cases**

Concurrent duplicate, audit failure leaves APPROVED, CLOSING/CLOSED leaves APPROVED, missing/non-confirmed allocation 409, cross-org 404, missing either permission 403, negative line cannot link Commitment.

- [ ] **Step 7: Run GREEN and commit**

```powershell
.\mvnw.cmd -Dtest=ExpenseClaimStatusTest test
.\mvnw.cmd -Dit.test=ExpensePostedMigrationIntegrationTest,ExpensePostingIntegrationTest,ExpensePostingApiIntegrationTest,ExpenseLifecycleIntegrationTest verify
git add backend/src/main/resources/db/migration/V14__m5_expense_posted_state.sql backend/src/main/java/com/aicostops/expense backend/src/main/java/com/aicostops/ledger backend/src/test/java/com/aicostops/expense backend/src/test/java/com/aicostops/ledger/ExpensePostingIntegrationTest.java backend/src/test/java/com/aicostops/ledger/ExpensePostingApiIntegrationTest.java
git commit -m "feat(m5): implement expense posting"
```

---

### Task 7: AIC-050 — Scoped Ledger Query and full lineage API

**Files:**
- Modify: `backend/src/main/java/com/aicostops/ledger/application/LedgerReadModels.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/LedgerQueryService.java`
- Create: `backend/src/main/java/com/aicostops/ledger/infrastructure/LedgerQueryMapper.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerController.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/api/LedgerResponses.java`
- Create: `backend/src/test/java/com/aicostops/ledger/LedgerQueryIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/ledger/LedgerLineageApiIntegrationTest.java`

**Interfaces:** GET `/api/v1/ledger/postings`, `/ledger/postings/{postingId}`, `/ledger/entries`, `/ledger/entries/{entryId}`. Filters: `billingPeriodId`, `sourceType`, `projectId`, `costCenterId`, `teamId`, `page`, `size`, `sort=postedAt,asc|desc` only.

- [ ] **Step 1: Write RED scoped visibility tests**

Mirror BudgetQueryService: no applicable LEDGER_READ=403; ORG grant sees org Ledger; typed grants see only matching target Entries; out-of-scope/cross-org detail=404. A mixed-scope Posting response contains only caller-visible Entries and computes any totals from visible Entries only.

- [ ] **Step 2: Implement Ledger-owned read projections**

`LedgerQueryMapper` may JOIN source tables to build read-only lineage; `ledger.application` does not call foreign Mappers. Pagination/filter/sort stay whitelisted.

- [ ] **Step 3: Implement lineage**

```text
Provider: Entry -> Posting -> AllocationLine/Decision -> ChargeFact -> RawProviderRecord -> ImportAttempt -> ImportBatch -> Evidence
Expense:  Entry -> Posting -> AllocationLine/Decision -> ExpenseClaim -> Evidence(if attached)
Correction: add CorrectionGroup -> target/reversed Entry -> original business lineage
```

Expense must not fabricate Provider raw/import lineage.

- [ ] **Step 4: Run GREEN and commit**

```powershell
.\mvnw.cmd -Dit.test=LedgerQueryIntegrationTest,LedgerLineageApiIntegrationTest verify
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

**Interfaces:** `CorrectionResult correct(AuthenticatedUser user, CorrectionCommand command, String idempotencyKey)`. Command fields: targetEntryId, correctionPeriodId, mode `REVERSAL_ONLY|REPLACE`, reasonCode, optional reasonText, optional replacement amount/currency/exactly-one target.

- [ ] **Step 1: Write REVERSAL_ONLY RED test**

Historical Entry/Posting unchanged; OPEN correction period gets CORRECTION Posting + REVERSAL `-target.amount`, copied dimensions/source lineage, `reverses_entry_id=target`.

- [ ] **Step 2: Write REPLACE RED test**

Same reversal plus one replacement Entry; replacement currency equals target currency; exact/ORG/no-budget selection applies in correction period; no Commitment consume.

- [ ] **Step 3: Implement correction transaction**

```text
validate Idempotency-Key/request hash
reserve api_idempotency
read target identity
lock chosen correction BillingPeriod; require OPEN
resolve/lock correction Budgets id ASC
lock target Entry + parent Posting
reject already-reversed target
insert CorrectionGroup
insert CORRECTION Posting key CORRECTION:{groupId}
insert reversal; optional replacement
apply signed actual deltas only to correction-period Budgets
audit LEDGER_CORRECTION_POSTED
finalize idempotency response
commit
```

- [ ] **Step 4: Add conflict/security/rollback cases**

Same key/same hash replay, same key/different hash 409, double reversal 409, CLOSED correction period 409, FX replacement 400, missing LEDGER_CORRECT 403, cross-org 404, audit failure rolls everything back.

- [ ] **Step 5: Run GREEN and commit**

```powershell
.\mvnw.cmd -Dit.test=LedgerCorrectionIntegrationTest,LedgerCorrectionApiIntegrationTest verify
git add backend/src/main/java/com/aicostops/ledger backend/src/test/java/com/aicostops/ledger/LedgerCorrectionIntegrationTest.java backend/src/test/java/com/aicostops/ledger/LedgerCorrectionApiIntegrationTest.java
git commit -m "feat(m5): implement correction posting"
```

---

### Task 9: AIC-052 — Executable Ledger invariants and architecture gate

**Files:**
- Modify: `backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java`
- Create: `backend/src/test/java/com/aicostops/ledger/LedgerImmutabilityArchitectureTest.java`
- Create: `backend/src/test/java/com/aicostops/ledger/LedgerFinancialInvariantIntegrationTest.java`

**Interfaces:** No new production API.

- [ ] **Step 1: Add ArchUnit rules**

```text
ingestion.. must not depend on ledger..
ledger.application.. must not depend on foreign ..infrastructure..
ledger.domain.. stays framework-free
committed Ledger mapper/service API exposes no destructive UPDATE/DELETE path
```

- [ ] **Step 2: Add financial invariant matrix**

Re-prove duplicate Provider/Expense convergence, CLOSED/CLOSING rejection, no-Budget posting, over-budget posting, Commitment+actual+usage atomicity, Expense POSTED+Ledger atomicity, historical Correction immutability, one reversal per target, cross-org lineage privacy.

- [ ] **Step 3: Run GREEN and commit**

```powershell
.\mvnw.cmd -Dtest=ModuleDependencyArchitectureTest,LedgerImmutabilityArchitectureTest test
.\mvnw.cmd -Dit.test=LedgerFinancialInvariantIntegrationTest verify
git add backend/src/test/java/com/aicostops/architecture backend/src/test/java/com/aicostops/ledger/LedgerImmutabilityArchitectureTest.java backend/src/test/java/com/aicostops/ledger/LedgerFinancialInvariantIntegrationTest.java
git commit -m "test(m5): enforce ledger invariants"
```

---

### Task 10: AIC-053 — Ledger frontend API, navigation, list/detail/lineage

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

**Interfaces:** Routes `/ledger`, `/ledger/postings/:id`, `/ledger/entries/:id`, all under `PermissionRoute permission="LEDGER_READ"`. `ledgerApi` preserves ID/money strings.

- [ ] **Step 1: Write RED route/nav tests**

`LEDGER_READ` shows `账本` with icon in desktop/mobile; no permission hides it; direct route permission-gated.

```powershell
Set-Location E:\AI-CostOps\frontend
npm test -- --run src/app/layout/appNavigation.test.tsx src/app/layout/AuthenticatedLayout.test.tsx
```

Expected first run: FAIL.

- [ ] **Step 2: Implement API types, route and navigation shell**

Use existing `apiClient`, `PageResponse`, shared ProblemDetail and shared date/time formatting. Add `/ledger` icon in shared `NAV_ICONS`.

- [ ] **Step 3: Write/implement Ledger page tests**

List: filters, source type, posted time, period, currency-aware amounts without cross-currency totals, entry count, correction marker. Posting detail: server-visible Entries only. Entry detail: Provider/Expense/Correction lineage in Simplified Chinese with stable codes/ids retained where useful.

- [ ] **Step 4: Run GREEN and commit**

```powershell
npm test -- --run src/features/ledger/LedgerPages.test.tsx src/app/layout/appNavigation.test.tsx src/app/layout/AuthenticatedLayout.test.tsx
git add frontend/src/features/ledger frontend/src/app/router/AppRouter.tsx frontend/src/app/layout
git commit -m "feat(m5): add ledger workflow ui"
```

---

### Task 11: Posting/Commitment/Correction UX on existing business pages

**Files:**
- Create: `frontend/src/features/ledger/PostingAction.tsx`
- Create: `frontend/src/features/ledger/PostingAction.test.tsx`
- Create: `frontend/src/features/ledger/CorrectionAction.tsx`
- Create: `frontend/src/features/ledger/CorrectionAction.test.tsx`
- Modify: `frontend/src/features/costs/CostDetailPage.tsx`
- Modify: `frontend/src/features/expenses/ExpenseReviewDetailPage.tsx`
- Modify: `frontend/src/features/expenses/api/expenseApi.ts`
- Modify: `frontend/src/features/expenses/ExpensePages.test.tsx`
- Modify: `frontend/src/features/costs/CostPages.test.tsx`

**Interfaces:** `ledgerApi.postCharge`, `ledgerApi.postExpense`, `ledgerApi.correct`. Financial mutations are never auto-retried by React Query/UI.

- [ ] **Step 1: Write Provider Posting RED test**

Show action only with LEDGER_POST + confirmed-import/CLEAN + confirmed allocation. Backend 409 is shown and current queries are invalidated/refetched; do not silently retry.

- [ ] **Step 2: Implement optional explicit Commitment picker**

For each positive AllocationLine, use existing Budget reads to present exact-target→ORG fallback and query `GET /commitments?budgetId=...`; one optional Commitment per line. No picker for non-positive line/no visible Budget. Backend remains authoritative and revalidates links.

- [ ] **Step 3: Write Expense Posting RED test**

Show only when `status==='APPROVED'`, `postingReady===true`, and user has both EXPENSE_POST+LEDGER_POST. Success refreshes review queue/detail and renders POSTED. Add POSTED to `ExpenseClaimStatus` and Chinese status labels.

- [ ] **Step 4: Write/implement Correction action**

Only LEDGER_CORRECT users see it. Support REVERSAL_ONLY/REPLACE; show immutable original Entry, chosen OPEN correction period, signed effect, same-currency replacement, exactly one target.

- [ ] **Step 5: Run GREEN and commit**

```powershell
npm test -- --run src/features/ledger/PostingAction.test.tsx src/features/ledger/CorrectionAction.test.tsx src/features/expenses/ExpensePages.test.tsx src/features/costs/CostPages.test.tsx
git add frontend/src/features/ledger frontend/src/features/costs frontend/src/features/expenses
git commit -m "feat(m5): connect posting and correction ux"
```

---

### Task 12: OpenAPI and canonical documentation synchronization

**Files:**
- Modify: `docs/02-development/api/openapi.yaml`
- Modify: `docs/02-development/detailed-design/01-module-boundaries.md`
- Modify: `docs/02-development/detailed-design/02-data-model.md`
- Modify: `docs/02-development/detailed-design/03-state-machines.md`
- Modify: `docs/02-development/detailed-design/04-transactions-idempotency-concurrency.md`
- Modify: `docs/02-development/detailed-design/06-permission-matrix.md`
- Modify: `docs/02-development/detailed-design/09-frontend-information-architecture.md`
- Modify: `docs/02-development/implementation/02-issue-backlog.md`
- Create: `backend/src/test/java/com/aicostops/ledger/M5OpenApiContractTest.java`

**Interfaces:** Docs must describe delivered M5 code, not speculative M6 behavior.

- [ ] **Step 1: Write OpenAPI RED contract test**

Assert all M5 paths, request/response schemas, correction-only Idempotency-Key, string IDs, decimal-string money and ProblemDetail responses exist.

- [ ] **Step 2: Update OpenAPI and canonical docs**

Record BillingPeriod staying in budget for M5; Expense APPROVED→POSTED; APPROVED queue retained until POSTED; exact→ORG Budget fallback; positive-only explicit Commitment links; Ledger scopes; immutable Correction; M6 Close/Reconciliation deferred.

- [ ] **Step 3: Run GREEN and commit**

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd -Dtest=M5OpenApiContractTest,*OpenApiContractTest test
git add docs backend/src/test/java/com/aicostops/ledger/M5OpenApiContractTest.java
git commit -m "docs(m5): synchronize immutable ledger contracts"
```

---

### Task 13: Integrated regression, push and evidence handoff

**Files:** No planned production files; this is verification only.

**Interfaces:** Produces evidence for Sol's independent remote review. It does not open or merge the final PR.

- [ ] **Step 1: Full backend verification**

```powershell
Set-Location E:\AI-CostOps\backend
.\mvnw.cmd verify
```

Expected: BUILD SUCCESS; unit, architecture and all IntegrationTest suites pass.

- [ ] **Step 2: Full frontend verification**

```powershell
Set-Location E:\AI-CostOps\frontend
npm test -- --run
npm run lint
npm run build
```

Expected: all exit 0.

- [ ] **Step 3: Prove old migrations untouched and branch hygiene**

```powershell
Set-Location E:\AI-CostOps
git diff origin/main -- backend/src/main/resources/db/migration/V1__foundation_baseline.sql backend/src/main/resources/db/migration/V2__m1_identity_organization_schema.sql backend/src/main/resources/db/migration/V3__seed_v1_roles_permissions.sql backend/src/main/resources/db/migration/V4__m2_evidence_import_schema.sql backend/src/main/resources/db/migration/V5__m2_finance_reviewer_provider_account_read.sql backend/src/main/resources/db/migration/V6__m2_raw_provider_record_usage_window_check.sql backend/src/main/resources/db/migration/V7__m2_import_attempt_lease.sql backend/src/main/resources/db/migration/V8__m3_canonical_cost_foundation.sql backend/src/main/resources/db/migration/V9__m3_duplicate_attribution_foundation.sql backend/src/main/resources/db/migration/V10__m4_expense_approval.sql backend/src/main/resources/db/migration/V11__m4_budget_period_schema.sql backend/src/main/resources/db/migration/V12__m4_budget_commitment_approval.sql
git status --short --branch
git log --oneline origin/main..HEAD
```

Expected: no V1～V12 diff; `.zcode/` and `start-dev.bat` remain local/untracked; semantic M5 commits are visible.

- [ ] **Step 4: Push**

```powershell
git push origin feat/m5-immutable-ledger
```

Expected: remote branch advances.

- [ ] **Step 5: Return exact evidence to Sol**

```text
1. git rev-parse HEAD
2. git status --short --branch
3. git log --oneline origin/main..HEAD
4. backend .\mvnw.cmd verify final BUILD SUCCESS tail
5. frontend npm test -- --run summary
6. frontend npm run lint result
7. frontend npm run build result
8. final focused concurrency/rollback test results
9. confirmation V1～V12 diff is empty
10. any known non-blocking limitation tied explicitly to #87 scope
```

Do not open the final PR unless Sol/user explicitly asks. Sol independently reviews the pushed branch/diff, requests fixes, opens/manages the single final M5 PR, watches GitHub Actions and performs UAT gating. Merge remains prohibited until explicit user authorization.

---

## Plan self-review checklist

Every item below must have code + tests above before M5 implementation is considered ready for Sol review:

```text
AIC-047 Ledger/Correction schema + same-org lineage + immutability
AIC-048 Provider Posting + stable-key convergence + Budget + Commitment + audit
AIC-049 Expense POSTED + posting queue discoverability + atomic Posting
AIC-050 scoped Ledger read + Provider/Expense/Correction lineage
AIC-051 REVERSAL_ONLY/REPLACE correction + idempotency + historical immutability
AIC-052 architecture + financial invariant gates
AIC-053 React Ledger + Posting/Commitment/Correction UX
OpenAPI/canonical docs
full backend/frontend regression
one branch + one final PR
```

No M6 Reconciliation/Close implementation belongs in this plan.