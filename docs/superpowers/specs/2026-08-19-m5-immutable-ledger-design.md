# M5 Immutable Ledger Design

> Status: proposed design for user review before implementation planning.
>
> Delivery issue: #87 — `M5 Immutable Ledger — AIC-047 ~ AIC-053`
>
> Delivery branch: `feat/m5-immutable-ledger`
>
> Baseline: `main@a835cd4b213fd85709e67ae957ba9b28da505137`

## 1. Goal and delivery model

M5 turns the M4 finance workflow from “approved / allocated / posting-ready” into durable accounting truth.

The milestone remains the canonical AIC-047～AIC-053 scope:

```text
AIC-047 Ledger / Correction Schema
AIC-048 Provider Charge Posting Transaction
AIC-049 Expense Posting Transaction
AIC-050 Ledger Query / Lineage API
AIC-051 Correction Posting
AIC-052 Ledger Invariant / Architecture Test
AIC-053 Ledger React Workflow
```

For this repository’s current execution model, the seven canonical work IDs are delivered as **one branch and one final PR**, with semantic commits retained internally. M6 Reconciliation / Close is explicitly excluded.

## 2. M4 reality audit: design rules that must be adapted

Early design remains directionally correct, but M5 must bind to the implementation that actually exists on `main`.

### 2.1 BillingPeriod stays in the Budget module for M5

The blueprint describes a future `period` module, but M4 already established:

```text
com.aicostops.budget.domain.BillingPeriod
com.aicostops.budget.application.BillingPeriodOpenGuard
com.aicostops.budget.infrastructure.BillingPeriodMapper
```

M5 does **not** move these classes merely to make package names match an older diagram. M6 may later extract a dedicated period boundary if the close implementation makes that worthwhile.

The existing `BillingPeriodOpenGuard` resolves a covering period and checks OPEN, but it does not lock the row. M5 Posting therefore uses the existing guard for deterministic resolution, then obtains the same period with `selectByIdForUpdate` (through a Budget application seam) and revalidates OPEN inside the financial transaction. This is the serialization point against future AIC-058 close/reopen.

### 2.2 Allocation implementation is already split between `allocation` and `attribution`

The live code uses:

```text
com.aicostops.allocation       workflow/API/orchestration adapters
com.aicostops.attribution      allocation domain + repository/application ports
```

M5 preserves this shape. Ledger application code must not make cross-module MyBatis mappers its default integration surface.

A posting-specific application seam is introduced so Ledger can obtain and later lock/revalidate:

```text
confirmed AllocationDecision
ordered AllocationLines
subject type/id
current-allocation pointer equality
source amount/currency
source effective date/time
provider confirmed-import + CLEAN gates
```

The existing `AllocationSubjectPort.assertConfirmEligible` is **not** reused as a posting guard because it is intentionally a pre-confirm contract requiring no current confirmed pointer. Posting requires the opposite: the current pointer must equal the confirmed decision being posted.

### 2.3 Expense state evolves forward from the real M4 state

Current M4 code/schema stops at:

```text
DRAFT / SUBMITTED / NEEDS_INFO / APPROVED / REJECTED / CANCELED
```

M5 adds only the state required by AIC-049:

```text
APPROVED -> POSTED
```

`POSTED` is terminal. M5 does **not** add a VOID command merely because an early state-machine document mentioned `VOIDED`; no M5 backlog item owns that command.

The live field name remains `current_allocation_decision_id`. `postingReady` remains a backend-derived read-model property and is never accepted from the client as authority.

### 2.4 Ledger permissions already exist in seed data but need policy activation

The V3 permission catalog already contains:

```text
EXPENSE_POST
LEDGER_READ
LEDGER_POST
LEDGER_CORRECT
```

The current applicable-scope policy does not yet map the Ledger permissions. M5 freezes:

```text
LEDGER_READ     ORG | PROJECT | TEAM | COST_CENTER
LEDGER_POST     ORG
LEDGER_CORRECT  ORG
EXPENSE_POST    ORG   (existing)
```

Rationale:

- read can be safely projected by LedgerEntry target scope;
- posting may contain several allocation lines across different scopes and must remain one all-or-nothing transaction;
- correction is a sensitive finance-admin action;
- SYSTEM_ADMIN keeps its existing non-finance boundary.

Provider Charge Posting requires `LEDGER_POST @ ORG`.

Expense Posting requires **both**:

```text
EXPENSE_POST @ ORG
LEDGER_POST @ ORG
```

This avoids treating a claim lifecycle mutation and a ledger mutation as unrelated privileges.

## 3. Module architecture

M5 creates:

```text
com.aicostops.ledger/
├── api/
├── application/
├── domain/
└── infrastructure/
```

### 3.1 Ledger owns

```text
LedgerPosting
LedgerEntry
CorrectionGroup
posting orchestration
correction orchestration
ledger query / lineage read models
immutable-ledger invariants
ledger audit port
```

### 3.2 Ledger consumes narrow seams

The orchestration layer may depend on narrow application/domain contracts from:

```text
allocation / attribution
budget
cost posting source seam
expense posting source seam
iam authorization context
shared primitives
```

The intent is not to create abstract ports for every SELECT. The rule is narrower: **cross-module financial mutation/locking semantics belong to the owning module and are exposed to Ledger through an application contract; Ledger application does not import another module’s infrastructure mapper.**

### 3.3 Existing modules remain owners of their facts

```text
cost       owns ChargeFact and confirmed-import/review eligibility
allocation owns AllocationDecision / AllocationLine workflow truth
expense    owns ExpenseClaim state transition to POSTED
budget     owns BillingPeriod, Budget, Commitment counters and consume primitive
ledger     owns accounting history and orchestrates the ACID transaction
```

## 4. Ledger schema

M5 adds forward-only migrations after V12. V1～V12 are never edited.

### 4.1 `ledger_posting`

Required columns:

```text
id
org_id
posting_key
source_type
source_id
allocation_decision_id NULL
billing_period_id
status
posted_by_member_id
posted_at
created_at
```

Frozen values:

```text
source_type = PROVIDER_CHARGE | EXPENSE_CLAIM | CORRECTION
status      = POSTED
```

Only committed postings are persisted; there is no PENDING ledger row. A failed transaction leaves no posting.

Required uniqueness/integrity:

```text
UQ(org_id, posting_key)
UQ(id, org_id)
same-org BillingPeriod FK
same-org AllocationDecision FK where non-null
same-org poster OrganizationMember FK
```

Stable keys:

```text
CHARGE:{chargeFactId}:ALLOCATION:{allocationDecisionId}
EXPENSE:{expenseClaimId}
CORRECTION:{correctionGroupId}
```

### 4.2 `ledger_entry`

The early data-model list omitted the mechanical parent link; M5 makes it explicit because Entry→Posting lineage is mandatory.

Required columns:

```text
id
org_id
posting_id
entry_index
entry_type
amount
currency
project_id NULL
cost_center_id NULL
team_id NULL
budget_id NULL
source_charge_fact_id NULL
source_expense_claim_id NULL
allocation_line_id NULL
correction_group_id NULL
reverses_entry_id NULL
created_at
```

Types:

```text
COST
CREDIT
ADJUSTMENT
REVERSAL
```

Signed amount semantics remain:

```text
positive = cost / upward adjustment
negative = credit / reversal / reduction
```

Every normal Provider/Expense posting creates **one LedgerEntry per AllocationLine**, ordered by `line_index`; `entry_index` equals that line index. Each normal entry copies the line’s exact amount, currency and exactly-one target.

Required invariants:

```text
UQ(posting_id, entry_index)
UQ(id, org_id)
posting same-org FK
budget same-org FK when non-null
charge / expense same-org source FK when non-null
allocation-line same-org FK when non-null
reverses-entry same-org self FK when non-null
correction-group same-org FK when non-null
```

The migration may add the composite unique target needed on `allocation_line(id, org_id)` before adding its same-org Ledger FK.

For a normal Provider entry:

```text
source_charge_fact_id != null
source_expense_claim_id = null
allocation_line_id != null
correction_group_id = null
```

For a normal Expense entry:

```text
source_charge_fact_id = null
source_expense_claim_id != null
allocation_line_id != null
correction_group_id = null
```

Correction entries preserve the original business-source reference when one exists; they carry `correction_group_id`. The reversal line sets `reverses_entry_id` to the exact target entry.

### 4.3 `correction_group`

Required columns:

```text
id
org_id
correction_key
reason_code
reason_text NULL
target_entry_id
target_posting_id
status
created_by_member_id
created_at
```

M5 uses entry-level correction as the precise unit. `target_posting_id` is denormalized immutable lineage to the target entry’s parent posting and must match it when the command executes.

Frozen status:

```text
POSTED
```

The group is created only inside the successful correction transaction; no pending workflow is invented in M5.

`correction_key` is server-generated from the reserved correction command identity; the resulting posting key is `CORRECTION:{correctionGroupId}`.

### 4.4 Finish the M4 commitment lineage FK

V11 intentionally left `budget_commitment_usage.ledger_entry_id` without a foreign key. After `ledger_entry` exists, M5 adds the same-org lineage FK while preserving the existing unique:

```text
UQ(org_id, budget_commitment_id, ledger_entry_id)
```

## 5. Source-effective period resolution

Posting period is determined from the **business source**, not from wall-clock `now`.

### 5.1 Provider Charge

A Provider Charge is posting-eligible only when `charge_fact.period_start` is non-null.

```text
postingEffectiveAt = charge.period_start
BillingPeriod = unique half-open period where
period_start <= postingEffectiveAt < period_end
```

M5 does not guess from import time, file upload time, current clock, or an unrelated document row when the Charge lacks period evidence. A period-less Charge remains visible but not posting-eligible.

### 5.2 Expense

```text
postingEffectiveAt = expense_date at 00:00:00 UTC
```

The project already uses UTC as the application financial-time baseline; `expense_date` remains the employee’s business date.

### 5.3 Correction

The caller explicitly chooses `correctionPeriodId`. The correction transaction locks that period and requires OPEN. The historical target period is never silently reopened or mutated.

## 6. Deterministic Budget selection

M4 established one Budget identity per:

```text
(org, billingPeriod, scopeType, scopeId, currency)
```

AllocationLine currently has exactly one of:

```text
projectId
costCenterId
teamId
```

M5 freezes the following one-entry/one-budget rule:

1. look for the exact target Budget in the posting BillingPeriod and entry currency;
2. if no exact target Budget exists, look for the organization-wide Budget `(scope_type=ORG, scope_id=currentOrgId)` for the same period/currency;
3. if neither exists, set `ledger_entry.budget_id = null` and continue posting.

If both exact and ORG Budgets exist, **exact wins; do not update both**.

This is intentionally not hierarchical roll-up. TEAM does not inherit PROJECT, PROJECT does not inherit COST_CENTER, and M5 does not invent organizational relationships that the authorization model explicitly avoids.

Budget is governance, not Ledger admission. An existing matching Budget is updated even if the new actual makes available negative.

Budget actual mutation:

```text
actual_amount += signed ledger entry amount
version += 1
```

No “available >= amount” guard is used for actual posting.

## 7. Explicit Commitment linkage

The current model has no durable Charge/Expense→Commitment pointer. M5 therefore does not guess which commitment a cost should consume.

Provider and Expense posting commands accept optional links:

```json
{
  "commitmentLinks": [
    {
      "allocationLineId": "123",
      "commitmentId": "456"
    }
  ]
}
```

Rules:

```text
zero or one commitment link per AllocationLine
link allocationLine must belong to the current confirmed decision
linked commitment must belong to the Budget selected for that entry
commitment must be ACTIVE or PARTIALLY_CONSUMED
no selected Budget => a commitment link is invalid
no supplied link => do not consume any commitment
no automatic commitment selection
```

Once the LedgerEntry has been inserted, the outer Posting transaction calls the existing AIC-045 `CommitmentConsumeService` using that entry id and the full entry amount.

Frozen consume rule remains:

```text
consumed = min(entryAmount, remainingAmount)
commitment.remaining -= consumed
budget.committed -= consumed
budget.actual += full signed entry amount
append budget_commitment_usage
```

The actual update occurs exactly once in Ledger orchestration; AIC-045 continues not to update actual itself.

M5 supports at most one explicitly linked commitment per entry. Splitting one entry across several commitments is not introduced.

## 8. Provider Charge Posting command

### 8.1 HTTP contract

```text
POST /api/v1/costs/charges/{chargeFactId}/post
Permission: LEDGER_POST @ ORG
Body: { commitmentLinks: [...] }   // empty list allowed
Success: 200 LedgerPostingDetail
Replay: 200 same persisted posting
```

The client does **not** submit `allocationDecisionId`. The server resolves the Charge’s `current_allocation_decision_id`, requires it to be CONFIRMED and uses it in the stable posting key. This prevents a client from posting a stale/non-current decision.

### 8.2 Pre-read

Outside row locks, load enough immutable/current identity to derive:

```text
charge id
current allocation decision id
charge period_start
allocation lines
target scopes
candidate Budget identities
optional linked commitment ids
```

No mutation is based solely on this pre-read; everything relevant is revalidated under locks.

### 8.3 Transaction lock order

```text
resolve period identity
→ BillingPeriod FOR UPDATE
→ require OPEN
→ matching Budgets FOR UPDATE sorted by id
→ linked Commitments FOR UPDATE sorted by id
→ ChargeFact FOR UPDATE
→ AllocationDecision FOR UPDATE
→ AllocationLines FOR UPDATE ordered by line_index
→ revalidate full posting eligibility
→ check/return existing posting_key
→ insert LedgerPosting
→ insert LedgerEntries
→ update Budget actual counters
→ consume explicitly linked Commitments
→ append audit
→ commit
```

If existing posting key is found after the same current allocation has been resolved, return the existing posting with no second mutation/audit.

### 8.4 Revalidation

Under lock require:

```text
Charge exists in current org
ImportBatch is CONFIRMED and charge belongs to confirmed attempt lineage
charge.review_status = CLEAN
charge.current_allocation_decision_id = locked decision id
AllocationDecision.status = CONFIRMED
AllocationDecision.subject = this Charge
AllocationLines non-empty
SUM(lines) = charge amount exactly
all line currencies = charge currency
all targets remain valid identities
period remains OPEN
commitment links still point to selected Budgets and consumable commitments
```

### 8.5 Entries

One entry per line:

```text
entry_type = COST when amount >= 0, otherwise CREDIT
amount = allocation_line.allocated_amount exactly
currency = allocation_line.currency
one target copied from allocation line
budget_id = deterministic selected Budget or null
source_charge_fact_id = charge id
allocation_line_id = line id
```

Provider posting never fails merely because no Budget exists or because actual becomes over-budget.

## 9. Expense Posting command

### 9.1 HTTP contract

```text
POST /api/v1/expenses/{expenseId}/post
Permissions: EXPENSE_POST @ ORG + LEDGER_POST @ ORG
Body: { commitmentLinks: [...] }
Success / replay: 200 LedgerPostingDetail
```

Stable key:

```text
EXPENSE:{expenseClaimId}
```

### 9.2 Preconditions

Under transaction locks require:

```text
ExpenseClaim.status = APPROVED
current_allocation_decision_id != null
current AllocationDecision.status = CONFIRMED
AllocationDecision.subject = this Expense
SUM(lines) = claim amount exactly
line currencies = claim currency
period covering expense_date is OPEN
```

The existing backend `postingReady` read model should evaluate true immediately before a valid post, but the command revalidates the underlying facts and never trusts the boolean sent by a client.

### 9.3 Atomic terminal transition

The same transaction performs:

```text
insert posting + entries
budget actual update
optional commitment consume
audit
ExpenseClaim APPROVED -> POSTED
commit
```

If any step fails, the claim remains APPROVED and no Ledger rows/counter mutation survive.

The POSTED transition increments the Expense version, preserving the existing versioned lifecycle convention.

## 10. Idempotency and concurrency

### 10.1 Provider / Expense normal posting

Normal posting uses the natural `posting_key` unique constraint as the durable idempotency anchor. No caller Idempotency-Key is required for these two commands.

Concurrency guarantee:

```text
two concurrent identical posting attempts
→ at most one committed LedgerPosting
→ one set of Entries
→ one set of Budget/Commitment mutations
→ one audit event
→ loser returns the committed posting after convergence
```

Bounded MySQL deadlock retry follows the existing project pattern (maximum 3 attempts) only for retryable deadlock/serialization losers; business conflicts are not blindly retried.

### 10.2 Correction

Correction has no pre-existing natural source key and therefore requires:

```http
Idempotency-Key: <opaque nonblank caller key>
```

It reuses `api_idempotency` with an M5 operation such as `LEDGER_CORRECTION`. The request hash covers org/actor, target entry, correction period, correction mode, reason and replacement payload. Same key/same hash replays; same key/different hash returns 409.

## 11. Ledger Query / Lineage API

### 11.1 Endpoints

```text
GET /api/v1/ledger/postings
GET /api/v1/ledger/postings/{postingId}
GET /api/v1/ledger/entries
GET /api/v1/ledger/entries/{entryId}
```

All require `LEDGER_READ`.

List filters remain explicit and whitelisted. M5 supports at least:

```text
billingPeriodId
sourceType
projectId
costCenterId
teamId
page
size
sort=postedAt,asc|desc   // default desc
```

No SQL-like filter language is exposed.

### 11.2 Scoped visibility

An ORG `LEDGER_READ` grant sees the organization ledger.

A PROJECT / TEAM / COST_CENTER `LEDGER_READ` grant sees only entries whose matching target id is granted. Posting list/detail visibility is derived from visible entries; a posting with several lines may be partially visible in a scoped list/detail, and aggregate totals must be computed only from visible entries for that caller.

Cross-org and out-of-scope detail returns privacy-preserving 404. Missing applicable permission returns 403 before resource disclosure.

### 11.3 Lineage

Entry detail is the flagship lineage read model.

Provider path:

```text
LedgerEntry
→ LedgerPosting
→ AllocationLine / AllocationDecision
→ ChargeFact
→ RawProviderRecord
→ ImportAttempt
→ ImportBatch
→ Evidence
```

Expense path:

```text
LedgerEntry
→ LedgerPosting
→ AllocationLine / AllocationDecision
→ ExpenseClaim
→ Expense Evidence (when attached)
```

Expense lineage must **not** fabricate RawProviderRecord/Import lineage that does not exist.

Correction path adds:

```text
CorrectionGroup
→ target/reversed LedgerEntry
→ original business-source lineage
```

Money is serialized as scale-8 decimal strings; all IDs are JSON strings.

## 12. Correction Posting

### 12.1 HTTP contract

```text
POST /api/v1/ledger/corrections
Permission: LEDGER_CORRECT @ ORG
Idempotency-Key: required
```

M5 corrects one LedgerEntry per command.

Request shape:

```json
{
  "targetEntryId": "123",
  "correctionPeriodId": "456",
  "mode": "REVERSAL_ONLY",
  "reasonCode": "ALLOCATION_ERROR",
  "reasonText": "Optional human explanation",
  "replacement": null
}
```

or:

```json
{
  "targetEntryId": "123",
  "correctionPeriodId": "456",
  "mode": "REPLACE",
  "reasonCode": "ALLOCATION_ERROR",
  "reasonText": "Move to the correct project",
  "replacement": {
    "amount": "100.00000000",
    "currency": "CNY",
    "projectId": "999",
    "costCenterId": null,
    "teamId": null
  }
}
```

`replacement` must provide exactly one target and exactly representable DECIMAL(20,8) money. M5 replacement currency must equal the target entry currency; FX correction is not invented.

### 12.2 Transaction

```text
reserve idempotency
→ read historical target identity
→ lock correction BillingPeriod
→ require OPEN
→ resolve/lock correction-period Budgets sorted by id
→ lock target LedgerEntry + target Posting
→ verify target has not already been reversed by another committed correction
→ insert CorrectionGroup
→ insert CORRECTION LedgerPosting
→ insert REVERSAL entry amount = -target.amount, copying target dimensions/source lineage
→ if REPLACE: insert ADJUSTMENT/COST/CREDIT replacement entry with requested amount/target
→ update only correction-period Budget actual counters using the signed new entries
→ audit
→ finalize idempotency response
→ commit
```

The historical posting, historical entry, historical period and historical Budget counters are never changed.

The reversal entry sets `reverses_entry_id = targetEntryId`. M5 enforces one committed reversal per target entry; if a later correction is needed, it targets the replacement/correction entry instead of double-reversing the same historical row.

Correction does not consume Commitments.

## 13. Audit

M5 introduces Ledger audit events through an application `LedgerAuditPort` with an Audit adapter, following existing modules.

At minimum:

```text
LEDGER_CHARGE_POSTED
LEDGER_EXPENSE_POSTED
LEDGER_CORRECTION_POSTED
```

Metadata is secret-free and limited to stable ids/codes/counts/currency; no raw provider payload, evidence bytes, token, password, API key, or free-form hidden source data is copied.

Audit insertion is part of the same MySQL transaction. Audit failure rolls back Posting/Correction and all Budget/Expense/Commitment mutations.

## 14. Error behavior

M5 continues using existing global ProblemDetail semantics.

Use existing codes where they already fit:

```text
403 FORBIDDEN                   missing applicable permission
404 RESOURCE_NOT_FOUND          cross-org / out-of-scope / absent visible resource
409 PERIOD_NOT_OPEN             CLOSING/CLOSED period
409 ALLOCATION_NOT_ELIGIBLE     missing/non-current/non-confirmed allocation or source eligibility failure
409 STATE_CONFLICT              invalid financial state not covered by a stronger existing code
400 VALIDATION_FAILED           malformed commitment link / correction replacement request
```

M5 does not add a new error code merely to rename an existing stable semantic. A genuinely distinct client-recoverable Ledger error discovered during implementation requires an explicit spec update rather than an ad-hoc enum addition.

## 15. Frontend workflow

M5 adds/activates:

```text
/ledger
/ledger/postings/:id
/ledger/entries/:id
```

### 15.1 Ledger list

Show:

```text
posting time
source type
source reference
billing period
currency-aware amounts (never cross-currency sum)
entry count
correction marker where applicable
```

### 15.2 Posting detail

Show header + entries. Each entry links to its full lineage and, where authorized, to source Charge/Expense and Budget/Commitment detail.

### 15.3 Entry detail / lineage

The main content is the lineage chain described in §11.3, rendered in Simplified Chinese presentation while preserving stable backend codes where useful for debugging/audit.

### 15.4 Posting actions

Provider Charge and Expense detail/review pages expose Post only when the user has required permissions and the backend data indicates the source is ready. The backend remains authoritative; a 409 refreshes current state and never auto-retries a financial mutation.

Commitment association is optional and explicit per allocation line. The UI only offers commitments that backend reads identify for the selected Budget; it never guesses one.

### 15.5 Correction UX

Only users with `LEDGER_CORRECT` see Correction actions. The UI supports the two M5 modes:

```text
REVERSAL_ONLY
REPLACE
```

It must clearly show the original immutable entry and the new correction-period effect before submit.

### 15.6 M4 UX baseline remains frozen

M5 reuses:

```text
shared Chinese ProblemDetail presentation
shared date/time formatting
existing Modal/Drawer/Select readability behavior
existing auth/session/cross-tab coordination
```

No broad Auth or navigation redesign is part of M5.

## 16. Architecture / invariant tests

AIC-052 makes the following regressions executable:

```text
ingestion must not depend on ledger
provider adapters must not post ledger directly
ledger history has no application destructive-update/delete path
ledger application does not import other modules' infrastructure mappers
posting period is locked before Budget/Commitment/Source locks
Budgets and Commitments are locked in id order
posting key uniqueness converges concurrent duplicates
closed/closing periods reject normal posting
Budget overrun does not reject actual posting
no Budget does not reject posting
Commitment consume + actual update + usage lineage are atomic
Expense POSTED + Ledger posting are atomic
Correction never updates historical Ledger rows/closed-period Budget actual
correction double reversal of the same target is rejected
cross-org lineage is invisible
money stays BigDecimal/DECIMAL string across API/frontend
```

Real MySQL integration tests are mandatory for transaction/locking/FK/concurrency behavior. H2 is not a financial correctness substitute.

## 17. API/OpenAPI and documentation sync

M5 updates the current API documentation as part of the same branch:

```text
docs/02-development/api/openapi.yaml
interface matrix / relevant API docs
state-machine documentation (APPROVED -> POSTED now implemented)
data-model documentation where implementation adaptation was frozen
transaction/concurrency documentation where M5 makes prior shorthand exact
permission matrix applicable scopes
```

Documentation must describe the implementation actually delivered, especially:

- BillingPeriod remains under Budget for M5;
- Posting source period rules;
- exact-or-ORG-fallback Budget selection;
- explicit optional Commitment linking;
- Ledger permission scopes;
- Expense POSTED state;
- single-entry Correction command contract.

## 18. Out of scope

M5 does not implement:

```text
Reconciliation schema/run/cases
CloseBlockerProvider
BillingPeriod Close / Reopen coordinator
Reconciliation / Close React
Reporting / Workbench read model
M7 end-to-end release flows
Commitment consume standalone HTTP/UI
multiple commitments per one LedgerEntry
FX correction
pending correction approval workflow
Expense VOID command
broad Auth refactor
unrelated M4 UI polish
```

## 19. Acceptance definition

M5 is acceptable only when:

```text
AIC-047～053 are implemented on feat/m5-immutable-ledger
all migrations are forward-only after V12
backend unit/integration/architecture suites pass
frontend lint/test/build pass
financial concurrency/rollback evidence is supplied
Sol independently reviews the remote branch/PR diff
GitHub Actions is green
human browser UAT has no blocking gap
user explicitly authorizes final merge
```

The final PR is opened only after Luna has completed the integrated M5 implementation and local regression evidence is available. The PR may be squash-merged only after explicit user authorization.
