# M6 Reconciliation & Close Design

> Status: approved design, implementation planning may begin.
>
> Delivery issue: #89 — `M6 Reconciliation & Close — AIC-054 ~ AIC-059`
>
> Delivery branch: `feat/m6-reconciliation-close`
>
> Baseline: `main@16d4b66fffed24e0e45681967bed7612bb14040b`

## 1. Goal and delivery model

M6 turns the immutable M5 Ledger into a controllable period-end finance process:

```text
provider/canonical financial truth
        ↕ reconcile
immutable internal Ledger truth
        ↓
review / explain material differences
        ↓
freeze a BillingPeriod against new financial truth
        ↓
run deterministic close blockers
        ↓
CLOSED, or return safely to OPEN
```

The milestone keeps the canonical backlog scope:

```text
AIC-054 Reconciliation / Close Schema
AIC-055 Reconciliation Run / Matching Baseline
AIC-056 Reconciliation Case Lifecycle
AIC-057 Close Blocker Provider
AIC-058 BillingPeriod Close / Reopen Coordinator
AIC-059 Reconciliation / Close React
```

Repository delivery remains **one milestone branch and one final squash PR**. Internal commits and Sol review checkpoints are grouped as:

```text
AIC-054 ~ AIC-056  Reconciliation Core
AIC-057 ~ AIC-058  Close Core
AIC-059            Frontend / UAT
```

M7 reporting/workbench and later end-to-end expansion remain out of scope.

## 2. Implementation reality audit: rules M6 must bind to

M6 is designed from the implementation on the baseline commit, not from an older diagram in isolation.

### 2.1 BillingPeriod already contains the M6 state foundation

The live model already has:

```text
status = OPEN | CLOSING | CLOSED
close_generation
closing_started_at
closed_at
reopened_at
version
```

M6 does **not** add a `BillingPeriod.BLOCKED` state. A blocked or failed Close is represented by the durable CloseRun result while the period returns:

```text
CLOSING -> OPEN
```

`BLOCKED` belongs to `period_close_run`, not to the BillingPeriod state machine.

BillingPeriod remains owned by `com.aicostops.budget` in M6. The milestone introduces narrow application seams for reconciliation/close and write fencing; it does not perform a package migration merely to match an early blueprint.

### 2.2 M5 already established BillingPeriod as the financial serialization point

The following committed financial mutations already lock BillingPeriod first and require `OPEN` before the write:

```text
Provider Charge posting
Expense posting
Ledger correction (correction period)
Commitment activation / approval
Commitment release
```

Their established lock order starts with the period row. M6 reuses this invariant instead of adding Redis/distributed locks.

Committed normal-posting replay remains a deliberate exception: an authorized replay of an already committed immutable posting may return the persisted result even when its period is `CLOSING` or `CLOSED`. Close fences **new truth**, not read/replay of old truth.

### 2.3 Some existing mutations are not yet Close-safe

The live code has commands that can alter period-end state without locking/requiring an OPEN BillingPeriod. Known examples include:

```text
Budget create / total update
Commitment request
Expense approval
Provider Import admission / retry / confirm
Duplicate scan / duplicate exclusion where the included external truth changes
Reconciliation run start (new M6 command)
```

The implementation plan must reconcile every command that can change reconciliation basis or turn a Close blocker from PASS to FAIL. M6 is not complete merely because Ledger posting itself is fenced.

### 2.4 Correction has no pending workflow

M5 `correction_group` is atomically created with:

```text
status = POSTED
```

There is no durable draft/request/pending correction state. M6 therefore does not invent one merely to satisfy the blocker name `PENDING_CORRECTIONS`.

For V1, that blocker is explicit and deterministic:

```text
PASS
itemCount = 0
notApplicable = true
reason = current correction model persists only committed POSTED corrections
```

A future real pending-correction workflow can replace that provider without changing the canonical blocker code.

### 2.5 M6 permission seed already exists

V3 already contains:

```text
RECONCILIATION_READ
RECONCILIATION_RUN
RECONCILIATION_RESOLVE
PERIOD_READ
PERIOD_CLOSE
PERIOD_REOPEN
```

and role assignments already preserve the intended finance boundary:

```text
FINANCE_REVIEWER: reconciliation read/run/resolve + period read
FINANCE_ADMIN:    above + period close/reopen
SYSTEM_ADMIN:     no implicit finance authority
```

M6 must **not duplicate seed rows**. It activates these permission codes in `M1AdminPermissionPolicy` with V1 applicable scope `ORG`.

### 2.6 ExternalDocument is useful evidence but not a universal monetary truth source

The canonical implementation already demonstrates heterogeneous provider evidence:

- OpenAI Costs can produce canonical `charge_fact` without an `external_document` total.
- Some billing summaries can have a reported total while `currency` and canonical `period_start/period_end` remain null and provider month information stays in metadata.

M6 therefore cannot use `external_document WHERE period_start = billingPeriod.start` as a universal matcher.

### 2.7 Frontend Import status types lag the backend

Backend `ImportBatchStatus` includes `READY_FOR_REVIEW` and `CONFIRMED`, while the current browser type still reflects an earlier M2 subset. AIC-059 may correct this integration mismatch because Close blockers link users back into Import workflow; it does not reopen the M2/M3 business model.

## 3. Frozen financial truth model

### 3.1 External financial truth

For M6 V1:

> **External financial truth is confirmed-import canonical `charge_fact` lineage.**

A contributing Charge must be traceable through:

```text
ChargeFact
→ RawProviderRecord
→ ImportAttempt
→ ImportBatch
```

and the ImportBatch must satisfy:

```text
status = CONFIRMED
confirmed_attempt_id = charge lineage attempt id
```

The provider account is derived from `import_batch.provider_account_id`; it is never guessed from provider code or display name.

External amount uses canonical Charge signed money exactly as persisted:

```text
DECIMAL(20,8)
BigDecimal
currency CHAR(3)
```

Provider/account/currency totals never use float/double.

Terminally excluded canonical charges do not contribute to current economic external truth:

```text
EXCLUDED_DUPLICATE
EXCLUDED_NONCOST
```

`CLEAN` contributes. `SUSPECTED_DUPLICATE` remains provider-reported truth while unresolved; the separate duplicate blocker prevents period close until review is completed.

A Charge belongs to a BillingPeriod using the exact same effective-time rule as Provider Ledger posting:

```text
postingEffectiveAt = charge_fact.period_start
period_start <= postingEffectiveAt < period_end
```

A period-less Charge is never assigned to a period by upload time/current time/document guess.

`external_document` is supporting statement/invoice evidence and may be shown in lineage or later validation, but it is **not the universal V1 monetary matcher**.

### 3.2 Internal financial truth

For provider reconciliation:

> **Internal truth is the signed immutable Ledger net truth posted into the target BillingPeriod and attributable to a Provider Charge lineage.**

The aggregation includes LedgerEntries whose parent `ledger_posting.billing_period_id` is the target period and whose source lineage contains `source_charge_fact_id`.

This includes:

```text
normal Provider Charge entries
Provider-charge correction reversal entries
Provider-charge correction adjustment entries
```

because correction entries preserve the original Charge source lineage. Expense-only entries are not mixed into Provider reconciliation.

A correction posted in a later period changes that later period's internal truth; it never rewrites historical period truth. Any resulting statement difference is explained through a ReconciliationCase rather than retroactive Ledger mutation.

### 3.3 V1 matching unit

The deterministic matching key is:

```text
organization
+ billingPeriod
+ providerAccountId
+ currency
```

M6 V1 intentionally does **not** introduce:

```text
FX conversion
Project/Team/CostCenter reconciliation dimensions
generic matching DSL
provider-specific formula guessing
```

### 3.4 Presence and amount semantics

Each side is aggregated as:

```text
present = contributing row count > 0
rowCount
signedAmount = exact scale-8 sum
```

Presence is not inferred from `amount != 0`: a real group whose signed net is zero still exists.

Per key:

```text
external present, internal absent  -> MISSING_INTERNAL
external absent, internal present  -> MISSING_EXTERNAL
both present and abs(diff) > tolerance -> AMOUNT_MISMATCH
both present and abs(diff) <= tolerance -> MATCHED
```

where:

```text
difference = internalAmount - externalAmount
```

The sign is frozen so positive difference means internal Ledger exceeds current external canonical truth.

### 3.5 Tolerance is server-owned policy

Tolerance must not become a browser-controlled way to make a large mismatch disappear.

V1 uses a server-side `ReconciliationTolerancePolicy` with:

```text
default = 0.00000000
non-negative DECIMAL(20,8)
```

The implementation may expose a deployment/configuration property for a non-zero tolerance, but the Run API does **not** accept arbitrary caller tolerance.

Each ReconciliationRun snapshots the exact applied tolerance. Close freshness additionally requires the current policy value to equal the Run snapshot; a policy change requires a new Run.

## 4. Reconciliation architecture

M6 creates:

```text
com.aicostops.reconciliation/
├── api/
├── application/
├── domain/
└── infrastructure/
```

### 4.1 Reconciliation owns

```text
ReconciliationRun
ReconciliationCase
PeriodCloseRun
PeriodCloseCheck
matching orchestration
case lifecycle
close blocker registry/coordinator
reconciliation/close query APIs
audit ports for M6 actions
```

### 4.2 Existing modules remain owners of their facts

```text
ingestion    owns ImportBatch/Attempt truth
cost         owns Charge/Duplicate truth
allocation   owns AllocationDecision truth
expense      owns ExpenseClaim truth
budget       owns BillingPeriod/Budget/Commitment truth and period mutation
ledger       owns immutable accounting truth
reconciliation owns comparison / close coordination, not those source rows
```

### 4.3 Cross-module reads use narrow application contracts

`reconciliation.application` must not import another module's MyBatis mapper as its default integration surface.

Owning modules expose narrow read contracts for the exact M6 need, such as conceptually:

```text
confirmed external financial aggregates
current internal provider-ledger aggregates
open Import blocker facts
open Duplicate blocker facts
unallocated Charge blocker facts
approved-unposted Expense blocker facts
Ledger integrity result
BillingPeriod close/write-fence operations
```

Names are finalized in the implementation plan after file-level mapping, but the dependency direction is frozen: application-to-application/domain seams, not orchestration reaching through other modules' persistence classes.

### 4.4 Close-aware write fence

Budget owns a narrow BillingPeriod financial write-fence seam used by command/workflow packages that can change close truth.

The architecture gate may be refined to allow those specific workflow packages to consume `budget.application` period fencing, while keeping canonical cost/ingestion domain and normalization code independent from Budget planning logic.

No generic global `common/service/utils` or Redis correctness lock is introduced.

## 5. V16 schema

M6 starts with `V16`. V1-V15 are immutable.

V16 creates exactly the four canonical M6 tables and may add supporting indexes/composite keys required for same-org integrity and period-scoped performance.

### 5.1 `reconciliation_run`

Required fields:

```text
id BIGINT AI PK
org_id
billing_period_id
status
algorithm_version
tolerance_amount DECIMAL(20,8)
basis_hash CHAR(64) NULL
summary_json JSON NOT NULL
created_by_member_id
started_at
finished_at NULL
error_code NULL
error_summary NULL
created_at
updated_at
```

Frozen statuses:

```text
CREATED
RUNNING
COMPLETED
FAILED
```

V1 algorithm version is a stable explicit string such as:

```text
M6_PERIOD_PROVIDER_CURRENCY_V1
```

`COMPLETED` requires a non-null `basis_hash` and `finished_at`.

`FAILED` requires `finished_at` and a bounded secret-free error summary. A failed Run never deletes older completed history.

Required same-org integrity includes BillingPeriod and creator member FKs.

Indexes include at least:

```text
(org_id, billing_period_id, started_at DESC, id DESC)
(org_id, billing_period_id, status, id DESC)
```

### 5.2 `reconciliation_case`

Required fields:

```text
id BIGINT AI PK
org_id
reconciliation_run_id
provider_account_id
currency CHAR(3)
case_type
external_amount NULL DECIMAL(20,8)
internal_amount NULL DECIMAL(20,8)
difference_amount DECIMAL(20,8)
external_row_count BIGINT
internal_row_count BIGINT
status
reason_code NULL
resolution_note NULL
resolved_by_member_id NULL
resolved_at NULL
created_at
updated_at
```

Frozen V1 case types:

```text
MISSING_INTERNAL
MISSING_EXTERNAL
AMOUNT_MISMATCH
```

`DUPLICATE_CANDIDATE` and `UNALLOCATED` are **not duplicated as ReconciliationCase types** in V1; those already have owning workflows and appear as Close blockers.

Frozen lifecycle:

```text
OPEN -> INVESTIGATING -> RESOLVED
INVESTIGATING -> OPEN
```

`RESOLVED` is terminal within that Run. New evidence/data produces a new Run/Case instead of reopening old reconciliation history.

Resolve requires all of:

```text
reason_code
nonblank resolution_note
actor
resolved_at
```

and never mutates Ledger automatically.

All persisted V1 cases are material by construction because cases are only created outside configured tolerance. Within-tolerance matches live in Run summary, not as informational Case rows.

Required uniqueness:

```text
UQ(reconciliation_run_id, provider_account_id, currency)
```

V16 may add `UNIQUE(provider_account.id, provider_account.org_id)` before using a same-org composite FK because M1 did not originally expose that composite target.

### 5.3 `period_close_run`

Required fields:

```text
id BIGINT AI PK
org_id
billing_period_id
close_generation BIGINT
attempt_no INT
status
reconciliation_run_id NULL
started_by_member_id
started_at
finished_at NULL
error_code NULL
error_summary NULL
created_at
updated_at
```

Frozen statuses:

```text
CHECKING
BLOCKED
CLOSED
FAILED
```

`BLOCKED` is a valid business outcome; `FAILED` is a technical evaluation/coordinator failure. Both return the BillingPeriod to `OPEN`.

Close generation semantics:

```text
BillingPeriod.close_generation identifies the closure cycle.
Initial cycle = 0.
A blocked/failed Close does NOT increment generation.
Each new attempt in the same generation increments attempt_no.
Privileged Reopen increments BillingPeriod.close_generation by exactly 1.
The next Close in the reopened generation starts at attempt_no = 1.
```

Required uniqueness:

```text
UQ(org_id, billing_period_id, close_generation, attempt_no)
```

A terminal CloseRun is immutable. Only an existing `CHECKING` run is resumable.

### 5.4 `period_close_check`

Required fields:

```text
id BIGINT AI PK
org_id
period_close_run_id
blocker_code
result
item_count BIGINT
summary_json JSON NOT NULL
evaluated_at
created_at
```

Canonical blocker codes are exactly:

```text
OPEN_IMPORTS
UNRESOLVED_DUPLICATES
UNALLOCATED_CHARGES
UNPOSTED_APPROVED_EXPENSES
OPEN_MATERIAL_RECONCILIATION
PENDING_CORRECTIONS
LEDGER_INTEGRITY
```

Result values:

```text
PASS
FAIL
ERROR
```

`ERROR` is distinct from a business blocker. Any `ERROR` makes the CloseRun `FAILED`, never `BLOCKED`/`CLOSED`.

Required uniqueness:

```text
UQ(period_close_run_id, blocker_code)
```

A finalized Close attempt persists **exactly seven** Check rows. The application verifies completeness before it can write terminal Run/Period state.

### 5.5 Supporting V16 indexes

The implementation plan must confirm existing index coverage before adding duplicates. Expected M6-specific additions include at least the equivalent of:

```text
ledger_posting(org_id, billing_period_id, id)
expense_claim(org_id, status, expense_date, id)
import_batch(org_id, status, period_start, period_end, id)
```

Existing Duplicate/Charge/Import indexes are reused where they already satisfy the query shape.

## 6. Reconciliation Run

### 6.1 API behavior

Conceptual endpoint:

```text
POST /api/v1/reconciliation-runs
Permission: RECONCILIATION_RUN @ ORG
Body: { billingPeriodId: "..." }
```

The client does not submit monetary totals, matching groups, tolerance, or status.

The command requires an organization-visible BillingPeriod and starts only while the period is `OPEN`. A `CLOSING`/`CLOSED` period cannot start a new Run.

Repeated explicit Run commands are allowed and create new historical Runs; rerunning never updates old Case evidence in place.

### 6.2 Deterministic snapshot

A Run reads external and internal aggregates from a consistent database snapshot and canonicalizes each matching row in deterministic order:

```text
providerAccountId ASC
currency ASC
```

The implementation computes a SHA-256 `basis_hash` from a canonical representation containing, per key:

```text
providerAccountId
currency
externalPresent
externalRowCount
externalAmount scale-8
internalPresent
internalRowCount
internalAmount scale-8
```

plus the explicit algorithm version. No display name, current clock, localized text, or unordered JSON participates in the hash.

The hash represents **financial truth basis**, not Case workflow state.

### 6.3 Cases and summary

For every discrepancy outside tolerance, insert exactly one ReconciliationCase for the key.

`summary_json` is non-authoritative presentation metadata and contains bounded counters such as:

```text
externalGroupCount
internalGroupCount
matchedGroupCount
withinToleranceCount
caseCount
missingInternalCount
missingExternalCount
amountMismatchCount
```

Authoritative money remains typed columns/aggregates, not JSON arithmetic.

### 6.4 Run failure

A matching/infrastructure failure produces a durable `FAILED` Run when possible, with no partial Case set exposed as completed truth.

A technical failure is not represented as a material ReconciliationCase.

## 7. Reconciliation Case lifecycle

### 7.1 Authorization and privacy

Reads require `RECONCILIATION_READ @ ORG`.

State changes require `RECONCILIATION_RESOLVE @ ORG` and use a fresh authorization context because reconciliation resolution is finance-sensitive.

Cross-org Case/Run detail is privacy-preserving 404 after applicable permission is established.

### 7.2 Commands

Conceptual commands:

```text
OPEN -> INVESTIGATING
INVESTIGATING -> OPEN
INVESTIGATING -> RESOLVED
```

The Case row is locked and current status revalidated inside one transaction. Illegal/stale transitions return 409 `STATE_CONFLICT`.

Resolve records reason/note/actor/time and appends audit in the same transaction.

### 7.3 Resolution does not repair accounting

A Case resolution is an explanation/acceptance of a detected discrepancy. It never:

```text
updates LedgerEntry
updates LedgerPosting
changes Budget actual
creates a Correction automatically
changes canonical Charge money
```

If finance must repair accounting, the user performs the existing M5 Correction workflow in an OPEN period and runs Reconciliation again when needed.

## 8. The seven Close blockers

All providers return a bounded result:

```text
blockerCode
PASS | FAIL
itemCount
secret-free summary metadata
```

Evaluation exceptions are captured by the coordinator as `ERROR` Check results.

### 8.1 `OPEN_IMPORTS`

FAIL when a nonterminal ImportBatch is relevant to the target period.

For V1, only these statuses are treated as safely terminal for Close:

```text
CONFIRMED
CANCELED
```

Therefore period-relevant batches in any of the following block:

```text
PENDING
PROCESSING
PARSED            // historical legacy value
READY_FOR_REVIEW
FAILED            // unresolved provider evidence; must retry or explicitly cancel where legal
```

Period relevance:

- complete Batch period bounds that overlap the target half-open period => relevant;
- a nonterminal Batch with missing/partial period bounds => conservatively relevant to any currently closing period in the organization;
- a complete non-overlapping Batch => irrelevant.

### 8.2 `UNRESOLVED_DUPLICATES`

FAIL when an `OPEN` DuplicateCandidate has an endpoint Charge whose posting effective time (`charge_fact.period_start`) belongs to the target BillingPeriod.

Terminal candidate states do not block:

```text
KEPT_CLEAN
CONFIRMED_DUPLICATE
SUPERSEDED
```

### 8.3 `UNALLOCATED_CHARGES`

FAIL for a current economic Charge that:

```text
belongs to confirmed Import lineage
review_status = CLEAN
posting effective time belongs to target BillingPeriod
has no valid current CONFIRMED AllocationDecision
```

Terminal exclusions and unresolved suspected duplicates are handled by their owning semantics/blocker and are not double-counted here.

### 8.4 `UNPOSTED_APPROVED_EXPENSES`

FAIL for:

```text
expense_claim.status = APPROVED
```

whose effective instant is:

```text
expense_date at 00:00:00 UTC
```

inside the target BillingPeriod. The blocker must use the same UTC rule as M5 Expense posting; browser timezone is never authoritative.

### 8.5 `OPEN_MATERIAL_RECONCILIATION`

PASS only when all conditions hold:

1. the latest ReconciliationRun for the BillingPeriod is `COMPLETED`;
2. its `algorithm_version` is the current M6 version;
3. its snapshotted tolerance equals the current server tolerance policy;
4. recomputing current external/internal truth under `CLOSING` produces the same `basis_hash`;
5. that Run has no Case in `OPEN` or `INVESTIGATING`.

No Run, a later FAILED/RUNNING Run, a stale basis, changed tolerance policy, or unresolved Case => FAIL.

The coordinator never silently falls back from a newer failed/stale Run to an older completed Run.

### 8.6 `PENDING_CORRECTIONS`

V1 deterministic provider:

```text
PASS
itemCount = 0
notApplicable = true
```

because the current M5 correction model persists only atomically committed `POSTED` CorrectionGroups. This is documented in the Check summary rather than hidden.

### 8.7 `LEDGER_INTEGRITY`

This is a finite, period-scoped accounting invariant matrix, **not** a generic database health scan.

At minimum it verifies:

1. every LedgerPosting in the period has one or more LedgerEntries;
2. normal Provider/Expense posting Entry count/index/money/currency/source/target lineage matches the confirmed AllocationLines it claims to represent;
3. normal source identity is consistent (`source_id` and entry source pointer agree for the posting type);
4. correction postings have exactly one valid reversal of the target amount (`reversal = -target.amount`) and at most the M5-defined replacement entry, with correct CorrectionGroup/target linkage;
5. no historical target is committed as reversed by more than one CorrectionGroup;
6. for each Budget in the period, `budget.actual_amount` equals the signed sum of LedgerEntries linked to that Budget;
7. `budget.committed_amount` is consistent with the outstanding remaining amounts of commitments whose state contributes to the committed counter; the check validates consistency but does not require the counter to be zero.

Any mismatch is a FAIL with bounded counts/sample identifiers. The provider does not repair data.

## 9. CLOSING monotonicity and write fencing

### 9.1 Core invariant

Once a period commits:

```text
OPEN -> CLOSING
```

no concurrent command may:

```text
change the reconciliation financial basis for that period
or
turn any already-PASS Close condition into FAIL
```

This is the correctness property behind Close.

### 9.2 Known-period writes

A command with a known target BillingPeriod/effective time uses the Budget-owned period write fence:

```text
resolve unique period
→ BillingPeriod FOR UPDATE
→ require OPEN
→ continue existing command locks/mutation
```

Locking stays period-first. Commands must not introduce a `Budget -> Period` or resource-first reverse lock order.

### 9.3 Unknown-period Import admission

Provider Import can begin before its period is known. For this path, M6 uses a short organization admission serialization point:

```text
Organization row FOR UPDATE
→ require no BillingPeriod of the organization is CLOSING
→ create/retry/confirm the relevant admission mutation as applicable
```

Close begin uses the same order:

```text
Organization row FOR UPDATE
→ target BillingPeriod FOR UPDATE
→ OPEN -> CLOSING
```

This prevents the classic race:

```text
Close observes no open Import
while a new unknown-period Import commits immediately after the check.
```

No command may lock BillingPeriod and then later request this organization admission lock; global lock order is organization (when needed) before period.

### 9.4 Minimum mutation reconciliation

The implementation plan must enumerate and test all commands that can affect basis/blockers. The minimum baseline audit includes:

```text
ProviderImport create
Import retry
Import confirm
Duplicate scan persistence
Duplicate exclude / any review mutation that changes included external truth
Budget create/update
Commitment request
Expense approve
Reconciliation run start
Provider/Expense posting (already fenced)
Correction (already fenced)
Commitment approve/release (already fenced)
```

If implementation inspection finds another command able to introduce a new blocker/change basis, it must use the same fence before M6 can close.

Commands that can only reduce a blocker without changing financial basis may remain legal during CLOSING, but Close is allowed to return a conservative BLOCKED result if it read the state before that cleanup committed.

## 10. Close coordinator

M6 uses a durable **CLOSING + resumable coordinator**. It does not hold one giant database transaction while every blocker query runs.

### 10.1 Begin / resume transaction

Close command requires `PERIOD_CLOSE @ ORG` with a fresh authorization context.

Transaction:

```text
Organization admission row FOR UPDATE
→ BillingPeriod FOR UPDATE
```

Then:

**If period is CLOSED**

```text
return the current successful Close result semantically
(no new run, no new audit)
```

This makes response-loss/retry safe.

**If period is CLOSING**

```text
require exactly one latest CHECKING CloseRun for the current generation
resume that run
```

Missing/ambiguous CHECKING state is an integrity/state conflict, never guessed around.

**If period is OPEN**

```text
attempt_no = max(current generation attempts) + 1
insert PeriodCloseRun(status=CHECKING)
set period.status=CLOSING
set closing_started_at=now
increment period.version
append close-start audit where appropriate
commit
```

A new attempt after a prior `BLOCKED`/`FAILED` run creates a new Run in the **same generation** with `attempt_no + 1`.

### 10.2 Evaluate outside the period lock

After `CLOSING` is durable, evaluate all seven blocker providers. Relevant truth is now fenced against new basis/blocker introduction.

Every provider is evaluated independently so the final diagnostic snapshot contains all seven codes even when one provider encounters a technical error.

Evaluation produces seven in-memory results:

```text
PASS / FAIL / ERROR
itemCount
summary
```

A hard process crash here leaves only:

```text
BillingPeriod = CLOSING
PeriodCloseRun = CHECKING
```

with no terminal Check set. A later Close command resumes the same Run and recomputes from current durable truth; no timeout heuristic is required.

### 10.3 Finalize transaction

Lock:

```text
BillingPeriod FOR UPDATE
PeriodCloseRun FOR UPDATE
```

and revalidate:

```text
period.status = CLOSING
run.status = CHECKING
run generation = period.close_generation
```

Insert exactly seven `period_close_check` rows in the same transaction as final state.

Decision order:

```text
any ERROR
  -> CloseRun FAILED
  -> BillingPeriod CLOSING -> OPEN

else any FAIL
  -> CloseRun BLOCKED
  -> BillingPeriod CLOSING -> OPEN

else
  -> CloseRun CLOSED
  -> BillingPeriod CLOSING -> CLOSED
```

For BLOCKED/FAILED returning to OPEN:

```text
closing_started_at = NULL
period.version += 1
```

The CloseRun retains its own start/end timestamps.

For successful CLOSED:

```text
period.closed_at = now
period.status = CLOSED
period.version += 1
```

The latest successful run retains the reconciliation run identity selected by the reconciliation blocker.

All terminal transitions and audit events commit atomically.

### 10.4 No automatic accounting repair

Close never auto-cancels Imports, resolves Duplicates, posts Expenses, changes Allocations, resolves Cases, or creates Corrections. It reports blockers and leaves each owning workflow authoritative.

## 11. Reopen

### 11.1 Command

Conceptual endpoint:

```text
POST /api/v1/billing-periods/{periodId}/reopen
Permission: PERIOD_REOPEN @ ORG
Body:
{
  "reasonCode": "...",
  "reasonNote": "nonblank human explanation"
}
```

Both reason fields are required in V1 because Reopen is a privileged exception to a closed financial period.

### 11.2 Transaction

```text
BillingPeriod FOR UPDATE
→ require CLOSED
→ require latest successful CloseRun matches current generation
→ status = OPEN
→ close_generation += 1 exactly once
→ reopened_at = now
→ closing_started_at = NULL
→ version += 1
→ append audit with old/new generation + reason
→ commit
```

`closed_at` remains historical evidence of the last close; the next successful close replaces it with the newer close timestamp.

Reopen never:

```text
deletes/updates old CloseRuns
updates old CloseChecks
updates ReconciliationRuns/Cases
rewrites Ledger history
```

A repeated Reopen after the period is already OPEN returns 409 state conflict; it is not treated as another generation increment.

## 12. API and authorization contract

Exact DTO names are finalized in the implementation plan/OpenAPI change, but V1 resources are frozen around these capabilities:

```text
POST /api/v1/reconciliation-runs
GET  /api/v1/reconciliation-runs
GET  /api/v1/reconciliation-runs/{runId}

GET  /api/v1/reconciliation-cases
GET  /api/v1/reconciliation-cases/{caseId}
POST /api/v1/reconciliation-cases/{caseId}/investigate
POST /api/v1/reconciliation-cases/{caseId}/return-open
POST /api/v1/reconciliation-cases/{caseId}/resolve

GET  /api/v1/billing-periods/{periodId}/close-readiness
GET  /api/v1/billing-periods/{periodId}/close-runs
GET  /api/v1/billing-periods/{periodId}/close-runs/{runId}
POST /api/v1/billing-periods/{periodId}/close
POST /api/v1/billing-periods/{periodId}/reopen
```

`close-readiness` is an informational preview only. It does **not** transition the period or create authoritative CloseChecks. The actual Close command always enters `CLOSING` and re-evaluates all seven blockers.

Permissions:

```text
RECONCILIATION_READ     ORG
RECONCILIATION_RUN      ORG
RECONCILIATION_RESOLVE  ORG
PERIOD_READ             ORG
PERIOD_CLOSE            ORG
PERIOD_REOPEN           ORG
```

Sensitive mutation commands use fresh authorization context. SYSTEM_ADMIN remains outside finance authority unless explicitly assigned a finance role/grant.

Every new route must be explicitly listed in `SecurityConfiguration`; `anyRequest().denyAll()` remains the final safety default.

API conventions remain:

```text
BIGINT id -> JSON decimal string
money -> scale-8 decimal string
Instant -> UTC timestamp payload
business DATE stays date-only
ProblemDetail / existing ProblemCode conventions
```

## 13. Audit and data disclosure

M6 mutation audit includes at least:

```text
RECONCILIATION_RUN_COMPLETED / FAILED
RECONCILIATION_CASE_INVESTIGATING
RECONCILIATION_CASE_RETURNED_OPEN
RECONCILIATION_CASE_RESOLVED
PERIOD_CLOSE_STARTED
PERIOD_CLOSE_BLOCKED
PERIOD_CLOSED
PERIOD_CLOSE_FAILED
PERIOD_REOPENED
```

Exact event naming may follow existing repository naming conventions, but semantics above are mandatory.

Audit metadata is secret-free and bounded. It may contain ids, status transitions, counts, currency and generation/attempt numbers; it must not copy raw provider payloads, credentials or arbitrary evidence content.

CloseCheck summaries likewise store only bounded diagnostic metadata/sample ids. Evidence/raw payload detail remains in the owning secured workflow.

## 14. Frontend contract (AIC-059)

M6 React is a finance workflow, not a browser-side accounting engine.

### 14.1 Reconciliation

The UI provides:

```text
Run list/history by BillingPeriod
explicit Run action
Run summary (status, algorithm, tolerance, basis time/result counts)
Case list filtered by status/type/provider/currency
Case investigate / return-open / resolve workflow
external/internal/difference amounts from backend
links back to relevant provider/import/ledger context where available
```

The browser never recomputes financial totals, differences, tolerance decisions or basis hash using JavaScript numbers.

### 14.2 Period Close

For BillingPeriod, show:

```text
OPEN / CLOSING / CLOSED
close generation
latest Close attempt
seven blocker results with count and explanation
Close action when permitted
Reopen action only for CLOSED + permitted user
Close history by generation/attempt
```

`BLOCKED` is rendered as a Close attempt result, not as a fake period status.

### 14.3 Integration quality

AIC-059 also corrects touched integration drift such as the browser Import status union missing backend `READY_FOR_REVIEW` / `CONFIRMED`.

All new user-visible status/action/error copy is Chinese in the established frontend style. Backend enum/code values remain stable English contract values.

UTC Instants are formatted through the project's shared browser time-display convention; raw ISO strings must not be sprayed into M6 pages. `expense_date` remains date-only and must not shift due to timezone conversion.

## 15. Concurrency and crash matrix

The following outcomes are frozen and must be proven with real MySQL integration tests.

### 15.1 Posting vs Close

**Posting wins period lock first**

```text
posting commits
Close waits
Close subsequently evaluates the committed Ledger truth
```

**Close reaches CLOSING first**

```text
new posting locks/reloads period
sees CLOSING
rejects with PERIOD_NOT_OPEN/state conflict
```

An already committed posting replay still returns persisted history.

### 15.2 Close vs truth-changing workflow

For every reconciled write-fence command (Import confirm, Expense approve, Duplicate exclusion, Budget mutation, Commitment request, etc.):

```text
writer wins fence first -> Close sees resulting truth/blocker
Close wins fence first  -> writer cannot commit a basis/blocker-introducing mutation
```

No test may prove this only with mocks; MySQL row-lock behavior is part of acceptance.

### 15.3 Close crash/retry

```text
OPEN -> CLOSING + CHECKING committed
process stops before finalize
next Close request
-> resumes same CHECKING run
-> no duplicate attempt
-> exactly seven terminal checks
```

If response is lost after successful final commit:

```text
next Close request sees CLOSED
-> returns existing successful close result
-> no new run/audit
```

### 15.4 Blocked / failed Close

```text
business FAIL -> CloseRun BLOCKED + period OPEN
technical ERROR -> CloseRun FAILED + period OPEN
```

No terminal attempt may leave the BillingPeriod stranded in CLOSING.

## 16. Test and acceptance matrix

### 16.1 Schema

Real MySQL migration tests cover:

```text
V16 applies after V15
all four tables exist
status/check constraints
same-org FKs
unique run/case/check identities
generation/attempt checks
resolution consistency
required indexes
```

### 16.2 Reconciliation matching

At minimum:

```text
MATCHED exact
MATCHED within configured tolerance
MISSING_INTERNAL
MISSING_EXTERNAL
AMOUNT_MISMATCH
zero-net group still counts as present
multi-provider / multi-currency deterministic groups
confirmed-import lineage only
excluded Charge behavior
Provider correction entries included as signed internal truth
basis hash deterministic regardless of row-return order
```

### 16.3 Case lifecycle

```text
OPEN -> INVESTIGATING
INVESTIGATING -> OPEN
INVESTIGATING -> RESOLVED
resolve requires reason/note
RESOLVED terminal
stale concurrent transition -> 409
cross-org 404
permission 403
no Ledger mutation on resolve
```

### 16.4 Close blockers

Each canonical blocker gets PASS/FAIL integration coverage, including:

```text
unknown-period active Import conservatively blocks
FAILED period-relevant Import blocks; CANCELED does not
OPEN Duplicate blocks; terminal candidate does not
CLEAN unallocated Charge blocks
APPROVED unposted Expense uses UTC date semantics
missing/stale/failed/latest Reconciliation run blocks
resolved fresh reconciliation passes
PENDING_CORRECTIONS explicit not-applicable PASS
Ledger/Budget counter drift fails integrity
```

### 16.5 Close/Reopen

```text
OPEN -> CLOSING -> CLOSED
BLOCKED -> OPEN
FAILED -> OPEN
resume CHECKING after simulated crash
exactly seven Check rows
attempt_no increments after blocked retry in same generation
CLOSED close retry is semantic success
Reopen CLOSED -> OPEN
Reopen increments generation exactly once
new generation starts attempt 1
old runs/checks/reconciliation/ledger remain unchanged
```

### 16.6 Security / architecture / contract

```text
M1AdminPermissionPolicy contains all six M6 permissions at ORG only
FINANCE_REVIEWER cannot Close/Reopen
FINANCE_ADMIN can
SYSTEM_ADMIN has no implicit finance rights
SecurityConfiguration explicitly authenticates every M6 route
OpenAPI contract tests for IDs/money/statuses
ArchUnit prevents reconciliation application from reaching foreign persistence
ArchUnit permits only the deliberately narrow close-write-fence dependencies
```

### 16.7 Frontend

```text
permission-aware actions
Chinese status/action/error labels
Run/Case/Close state rendering
blocked vs failed distinction
no JS money authority
ID stays string
Import READY_FOR_REVIEW/CONFIRMED type integration
localized time display without shifting DATE fields
query invalidation after run/case/close/reopen mutations
```

Final milestone acceptance includes full backend unit/integration/architecture suites, frontend lint/test/build, Compose smoke, and browser UAT on all supported layouts already used by the repository.

## 17. Explicit non-goals

M6 does not introduce:

```text
FX reconciliation or exchange-rate tables
generic reconciliation DSL
provider-specific guessed billing formulas
a BillingPeriod BLOCKED state
pending/draft Ledger rows
a new pending Correction workflow
automatic Ledger repair from Case resolution
automatic blocker cleanup during Close
Redis/distributed lock as correctness authority
period package migration/refactor for naming aesthetics
M7 reporting dashboards
```

## 18. Design invariants to carry into the implementation plan

The implementation plan must preserve these non-negotiable invariants:

1. `charge_fact` confirmed lineage is V1 external monetary truth; `external_document` is supporting evidence.
2. provider-attributable immutable Ledger net entries in the target period are internal truth.
3. reconciliation matches by `(period, providerAccount, currency)` with server-owned tolerance and deterministic basis hash.
4. Case resolution never mutates Ledger.
5. `BillingPeriod` remains `OPEN | CLOSING | CLOSED`; blocked/failed attempts return it to OPEN.
6. CLOSING makes reconciliation basis/blocker introduction monotonic through MySQL write fencing.
7. Close always persists exactly seven terminal Check rows or stays resumable `CHECKING` after a hard crash.
8. committed posting replay survives CLOSING/CLOSED; only new writes are fenced.
9. Reopen increments generation, preserves all historical runs/checks/Ledger, and requires reason/audit.
10. finance permission boundaries remain explicit; SYSTEM_ADMIN does not become Finance Admin implicitly.
11. all money stays DECIMAL/BigDecimal/string at API boundaries; all business timestamps remain UTC-authoritative.
12. V1-V15 are never edited; M6 begins at V16.
