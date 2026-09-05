# M15 Hybrid Reconciliation Design

> Status: **APPROVED DESIGN BASELINE**  
> Date: 2026-09-05  
> Issue: #148  
> Branch: `feat/m15-hybrid-reconciliation`  
> Baseline: `main@502b8aa38a70a0afc4751097365ec6543592280f`  
> Depends on: M6 Reconciliation / Period Close, M11 Gateway Edge, M12 Reservation, M13 Metering / Settlement, M14 Multi-provider Routing / Resilience  
> Delivery: **one complete M15 implementation milestone, one feature branch, one final runtime PR**

## 1. Goal

M15 turns the existing M6 period reconciliation into the canonical hybrid reconciliation system for both V1 Provider statement costs and V2 realtime Gateway costs.

The governing flow is:

```text
Provider statement import
→ canonical Charge truth
                       ┐
                       ├→ existing reconciliation_run / reconciliation_case
Gateway request        │
→ route attempt        │
→ usage fact           │
→ Gateway Settlement   │
→ immutable Ledger ────┘
        ↓
reviewed explanation / append-only correction / reconciliation adjustment
        ↓
rerun reconciliation when financial truth changed
        ↓
existing Close blockers
```

M15 does **not** create a second reconciliation lifecycle. `reconciliation_run`, `reconciliation_case`, their existing `OPEN -> INVESTIGATING -> RESOLVED` lifecycle, and the existing Close framework remain canonical.

---

## 2. Existing truth M15 must reuse

### Provider / V1 truth

```text
provider_evidence
import_batch / import_attempt
raw_provider_record
charge_fact
allocation_decision / allocation_line
PROVIDER_CHARGE Ledger posting
Duplicate Review
```

Confirmed Provider statement charges remain authoritative external cost evidence. M15 does not manufacture synthetic Provider imports for Gateway traffic.

### Gateway / V2 truth

```text
gateway_request
gateway_route_attempt
gateway_usage_fact
gateway_usage_dimension
budget_reservation
gateway_settlement
GATEWAY_SETTLEMENT Ledger posting
```

M11-M14 already retain request, route, Provider account/model, Provider request id when available, usage effective time, Pricing Version, normalized usage, Settlement amount/rounding, BillingPeriod and Ledger lineage.

### Financial governance

```text
BillingPeriodFinancialWriteFence
LedgerBudgetPort
CommitmentConsumeService
LedgerCorrectionService
api_idempotency
Audit
CloseBlockerProvider
OPEN_MATERIAL_RECONCILIATION
PENDING_GATEWAY_FINANCIAL_WORK
```

No Redis value becomes financial truth in M15.

---

## 3. Non-goals

M15 does not add:

```text
FX
routing redesign
retry/failover redesign
new Provider realtime adapters
parallel hedging
prompt/completion/reasoning persistence
fuzzy or probabilistic financial matching
automatic pro-rata allocation of unexplained statement differences
automatic BillingPeriod reopen
destructive Settlement or Ledger rewrites
Redis-authoritative financial state
```

Gateway production code is unchanged by default. Backend reads the existing Gateway facts. A Gateway production change is allowed only if implementation proves a required already-frozen M15 lineage fact is not actually persisted.

---

# Part A — Financial Invariants

## 4. One financial history, never two

A Provider statement does not replace or rewrite an already committed realtime Settlement.

```text
SETTLED Gateway Settlement
→ immutable

POSTED Ledger entry
→ immutable

later Provider statement difference
→ reconciliation evidence
→ append-only financial correction when required
```

M15 never changes the historical `usage_effective_at`, Pricing Version, BillingPeriod, posted amount, or lineage of an already committed Gateway Settlement.

## 5. No double counting

A Provider statement Charge may describe cost already represented by Gateway Settlement Ledger entries.

Therefore this must never happen for the same real cost:

```text
GATEWAY_SETTLEMENT Ledger amount
+
full PROVIDER_CHARGE posting of the matching/overlapping statement Charge
```

M15 introduces a Provider-charge Hybrid posting fence. A statement Charge that overlaps possible Gateway financial truth is not normally postable through the V1 `PROVIDER_CHARGE` path until an explicit durable disposition proves that the whole Charge is a direct non-Gateway Provider charge.

## 6. No invented request ownership

Aggregate Provider statement records are not silently assigned to individual Gateway requests.

Forbidden matching includes:

```text
nearest timestamp
nearest amount
same model + approximately same amount
first request in the bucket
pro-rata spread across requests
probabilistic / ML matching
```

If evidence cannot prove request identity, M15 remains aggregate and/or requires a human-reviewed binding.

## 7. No statement absence = zero rule

The absence of a Provider statement line is never automatic proof that an unresolved post-dispatch Gateway request cost zero.

`DISPATCH_INTENT`, `BILLABLE_POSSIBLE`, `INCOMPLETE`, `UNKNOWN`, `RECONCILIATION_REQUIRED` and `PENDING_HOLD` stay conservative until positive evidence or an explicit reviewed financial resolution exists.

## 8. Exact money only

Provider statement amounts, Ledger amounts and adjustments remain `BigDecimal` / `DECIMAL`.

```text
Ledger/Budget money: scale 8
Gateway raw calculation: existing DECIMAL(38,18)
no float/double
no invented FX
```

The existing M6 difference convention remains:

```text
difference_amount = internal_amount - external_amount
```

Therefore the signed amount needed to make internal equal external is:

```text
required_adjustment = external_amount - internal_amount
                    = -difference_amount
```

---

# Part B — Canonical Reconciliation Model

## 9. M6 run/case remains canonical

M15 keeps the M6 key:

```text
organization
+ billing period
+ provider account
+ currency
```

and keeps the existing structural case types:

```text
MISSING_INTERNAL
MISSING_EXTERNAL
AMOUNT_MISMATCH
```

M15 adds evidence and explanation under a case; it does not multiply cases per request and does not remove the existing unique `(run, provider_account, currency)` model.

One provider/currency case may therefore contain many request-level evidence items.

## 10. Reconciliation algorithm version

M15 changes the canonical algorithm version to:

```text
M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2
```

Old M6 runs remain immutable history. `OPEN_MATERIAL_RECONCILIATION` treats an old algorithm version as stale under the existing rule and requires a new run before a future Close.

## 11. External truth

External truth remains confirmed Provider statement canonical Charge truth.

The existing financial inclusion semantics remain:

```text
confirmed import attempt only
provider_account_id required
Charge review status CLEAN or SUSPECTED_DUPLICATE
Charge period_start inside BillingPeriod [start,end)
aggregate by provider account + currency
```

`SUSPECTED_DUPLICATE` remains visible to reconciliation while the separate existing Duplicate Close blocker prevents final Close until duplicate review is resolved.

A Charge classified as `RECONCILIATION_EVIDENCE` or `DIRECT_PROVIDER_CHARGE` is still part of the external Provider statement total. Disposition controls whether that Charge itself may become a Ledger source; it does not erase authoritative external truth.

## 12. Internal truth

M15 internal truth is the immutable Ledger, not Gateway tables by themselves.

For one BillingPeriod, provider account and currency, internal truth includes signed Ledger entries whose direct source lineage is one of:

```text
source_charge_fact_id
→ provider account from Import lineage

source_gateway_settlement_id
→ provider account from gateway_settlement

source_reconciliation_adjustment_id
→ provider account from reconciliation_adjustment
```

Correction entries remain included because a correction preserves the direct source lineage of the historical entry it corrects.

The internal adapter must not filter by parent `ledger_posting.source_type`; it groups by the entry's preserved direct source lineage so append-only corrections contribute correctly.

Expense entries are never Provider reconciliation truth.

## 13. One aggregate comparison, richer evidence

The canonical amount result is still aggregate:

```text
external Provider statement total
vs
internal Provider-related Ledger total
```

Request-level exact evidence is explanatory and enables safer resolution, but it does not replace the provider/currency aggregate arithmetic.

This avoids pretending that current Provider exports provide request-level granularity when they do not.

---

# Part C — Matching

## 14. Matching precedence

M15 uses this order:

```text
1. exact Provider request correlation when provable
2. deterministic provider/currency/BillingPeriod aggregate comparison
3. explicit human-reviewed binding when necessary
4. otherwise UNCLASSIFIED / unresolved
```

There is no fuzzy fallback after level 1 fails.

## 15. Exact Provider request correlation

Automatic request-level correlation is legal only when all of the following are true:

```text
same organization
same provider account
same currency
external import profile certifies a field as PROVIDER_REQUEST_ID
exact bounded Provider request id equality
exactly one eligible external Charge
exactly one eligible Gateway request/attempt
Gateway attempt is not PLANNED
Gateway attempt is not SAFE_NO_BILLABLE_EXECUTION
no conflicting route/usage/settlement lineage
```

Provider request id comparison is exact/case-sensitive; do not lowercase, trim meaningful characters, hash-match approximately, or normalize Provider-specific syntax.

Eligible Gateway attempts may be:

```text
DISPATCH_INTENT
BILLABLE_POSSIBLE
COMPLETED
```

when the exact Provider request id proves correlation. `SAFE_NO_BILLABLE_EXECUTION` is never a billable match candidate.

If the same Provider request id has multiple external Charges or multiple Gateway candidates, automatic matching fails closed and emits duplicate/ambiguous evidence.

## 16. External correlation profile

`raw_provider_record.provider_record_key` is generic and cannot globally be assumed to mean Provider request id.

M15 defines a bounded Provider/source-schema correlation profile that explicitly declares whether a safe persisted field represents:

```text
PROVIDER_REQUEST_ID
or
NONE
```

The matcher consumes persisted normalized/canonical lineage only. It does not re-read raw Provider payload to invent new semantics.

Current imports that lack request-level Provider ids remain fully supported by aggregate reconciliation. No adapter is forced to fabricate a key.

## 17. Aggregate match

When exact request correlation is absent or incomplete, M15 compares only the deterministic M6 scope:

```text
organization
+ BillingPeriod [start,end)
+ provider_account_id
+ currency
```

The statement side is confirmed canonical Charges in the period. The internal side is Provider-related Ledger entries posted to that BillingPeriod.

A Provider export's day/month/bucket granularity remains authoritative as imported; M15 does not split one statement Charge into synthetic requests.

## 18. Human-reviewed binding

A user with reconciliation resolution permission may explicitly bind a statement Charge to an unresolved Gateway request when automatic matching is impossible but the operator has external evidence.

This is a durable audited decision, never an automatic heuristic.

The server still validates:

```text
same organization
same Provider account
currency compatibility
request has a non-SAFE possible-billable attempt
statement Charge is confirmed and not excluded
no conflicting final binding exists
```

---

# Part D — Difference Evidence

## 19. Difference codes

M15 evidence supports this bounded vocabulary:

```text
PRICING_DRIFT
DISCOUNT
ROUNDING
PROVIDER_CORRECTION
LATE_CHARGE
BILLING_PERIOD_MISMATCH
MISSING_GATEWAY_USAGE
UNKNOWN_PROVIDER_CHARGE
DUPLICATE_EXTERNAL_CHARGE
UNCLASSIFIED
```

These codes describe evidence. They do not automatically mutate financial history.

## 20. Evidence-gated classification

Automatic classification is allowed only when stored facts prove it.

### ROUNDING

Automatic only when the Provider statement monetary quantum/profile and the saved Gateway `calculated_amount_raw`, `posted_amount` and `rounding_delta` mathematically explain the difference exactly.

### DISCOUNT

Automatic only when a certified Provider import field explicitly represents a discount/credit component and the amount reconciles exactly.

### PRICING_DRIFT

Automatic only when persisted Provider pricing/usage evidence is sufficient to reproduce the Provider amount and prove that it differs from the frozen Gateway Pricing Version result.

A mere amount mismatch is not pricing drift.

### PROVIDER_CORRECTION

Automatic only when the statement schema has an explicit correction/refund/credit semantic. A negative amount by itself may be supporting evidence but is not enough when the Provider schema is ambiguous.

### LATE_CHARGE

May be classified when authoritative external cost evidence first becomes confirmed after the BillingPeriod was already closed, using persisted period close and import-confirmation timestamps.

### BILLING_PERIOD_MISMATCH

Used when strong request correlation exists but authoritative Provider period/timestamp evidence maps to a different period than the frozen Gateway financial BillingPeriod.

The historical Gateway period is not rewritten.

### MISSING_GATEWAY_USAGE

Requires strong Gateway correlation to a request whose usable current financial observation is absent, `INCOMPLETE` or `UNKNOWN`.

### UNKNOWN_PROVIDER_CHARGE

Used for authoritative external cost in a Hybrid scope that cannot be tied to existing Gateway truth and is not yet proven to be a direct non-Gateway Charge.

### DUPLICATE_EXTERNAL_CHARGE

Integrates existing Duplicate Review evidence and/or duplicate strong external identifiers. M15 does not create a second duplicate state machine.

### UNCLASSIFIED

Mandatory fail-closed fallback whenever evidence is insufficient.

## 21. Multiple explanations per case

One aggregate case may contain multiple evidence items and multiple difference codes.

M15 does not force a single guessed root cause for the whole provider/currency discrepancy.

---

# Part E — Provider Charge Hybrid Posting Fence

## 22. Hybrid overlap

A Charge is a Hybrid candidate when, for its organization/provider account/currency/BillingPeriod, durable Gateway facts contain at least one potentially billable route attempt whose status is not `PLANNED` and not `SAFE_NO_BILLABLE_EXECUTION`.

Currency is resolved from the frozen Gateway Pricing/Settlement context; no FX comparison is attempted.

## 23. Posting rule

Before a new V1 Provider Charge Ledger posting, `ProviderChargePostingService` must enforce:

```text
existing PROVIDER_CHARGE posting
→ replay existing result

explicit DIRECT_PROVIDER_CHARGE disposition
→ normal existing V1 posting may proceed

no Hybrid overlap
→ normal existing V1 posting may proceed

Hybrid overlap + no DIRECT disposition
→ reject with a bounded Hybrid reconciliation-required conflict
```

A `RECONCILIATION_EVIDENCE` disposition is permanently non-postable through the normal V1 charge path.

## 24. Posting-fence race

The posting transaction keeps the established financial lock order beginning with BillingPeriod.

After the BillingPeriod lock is held, the Hybrid overlap/disposition gate is revalidated before the source Charge is posted. Gateway dispatch also uses the BillingPeriod financial fence, so a new potentially billable Gateway attempt cannot race in after the posting decision for the same period.

A conservative false block is acceptable and retriable. A false allow that double-counts cost is not.

## 25. Charge disposition

M15 persists an immutable final disposition for a Charge only when a decision is needed:

```text
RECONCILIATION_EVIDENCE
DIRECT_PROVIDER_CHARGE
```

Decision sources are bounded:

```text
LEGACY_POSTED
SYSTEM_EXACT
SYSTEM_AGGREGATE_MATCH
MANUAL
```

`DIRECT_PROVIDER_CHARGE` means the entire canonical Charge may be handled by the existing V1 allocation/posting workflow. It must not be used to split an aggregate statement bucket that mixes Gateway and direct traffic.

Incorrect historical decisions are corrected through append-only financial correction/reconciliation; the disposition row is not silently rewritten.

## 26. Legacy compatibility

V23 backfills already-posted `PROVIDER_CHARGE` sources as:

```text
DIRECT_PROVIDER_CHARGE
+ LEGACY_POSTED
```

so M15 does not reinterpret existing committed Ledger history as duplicate Gateway evidence.

---

# Part F — Financial Resolution Paths

## 27. Path 1: settled request mismatch → existing Correction

When strong evidence identifies an already `SETTLED` Gateway request and a historical Ledger Entry exists:

```text
historical Gateway Ledger Entry
→ existing LedgerCorrectionService
→ REVERSAL
→ optional replacement
```

M15 fixes the existing lineage gap: correction reversal/replacement entries must preserve `source_gateway_settlement_id` exactly as Provider-charge corrections already preserve `source_charge_fact_id`.

A Correction never rewrites the original Settlement.

After any Correction changes Ledger truth, the previous reconciliation basis is stale. A new reconciliation run is required before Close.

## 28. Path 2: no historical entry / aggregate difference → Reconciliation Adjustment

Existing Correction requires a target historical Ledger Entry. It must not be abused when none exists.

M15 adds first-class Ledger source:

```text
RECONCILIATION_ADJUSTMENT
```

A Reconciliation Adjustment is append-only, human-triggered and linked to exactly one reconciliation case.

It is used for:

```text
statement-backed cost for INCOMPLETE/UNKNOWN/no-Settlement Gateway work
aggregate Provider statement excess/credit that cannot be assigned to one request
reviewed late-charge adjustment
other material case difference where no existing entry is the correct correction target
```

## 29. Adjustment amount

For a case-level full adjustment:

```text
required_adjustment = external_amount - internal_amount
```

The request may contain one or more allocation lines, but their signed scale-8 amounts must sum exactly to `required_adjustment`.

All lines use the case currency and each line has exactly one financial target:

```text
PROJECT | TEAM | COST_CENTER
```

No FX and no hidden remainder are allowed.

## 30. Adjustment target rules

### Exact unresolved Gateway request

When the adjustment resolves one strongly-bound Gateway request, the target is frozen from the Gateway request:

```text
financial_scope_type
financial_scope_id
```

The client cannot retarget that cost to another scope during resolution.

### Aggregate case

When no request ownership is provable, the reviewer may supply one or more explicit adjustment lines. M15 does not infer a split.

Every target is validated as an ACTIVE same-organization target using existing allocation target rules.

## 31. Adjustment period rules

If the reconciled BillingPeriod is `OPEN`, a new adjustment for that case must post into that same period.

If the reconciled period is `CLOSED`, M15 never reopens it automatically. The reviewer must either:

```text
A. explicitly reopen the historical period using existing PERIOD_REOPEN governance,
   then post into that reopened period;

or

B. choose another currently OPEN BillingPeriod as the correction period.
```

When the historical and correction periods differ, both period rows are locked in ascending id order before financial mutation. This prevents deadlocks with concurrent Close/Reopen operations.

## 32. Budget behavior

Reconciliation Adjustment reuses existing Budget selection and `budget.actual_amount` mutation rules.

For each adjustment line:

```text
exact financial scope + currency Budget
→ ORG fallback + same currency
→ no Budget
```

A missing Budget does not erase already-incurred authoritative cost; the Ledger adjustment may still post unbudgeted under the same incurred-cost principle used by Gateway Settlement/Correction.

Signed adjustment amount changes Budget Actual by the same signed amount when a Budget exists.

## 33. Commitment behavior

No aggregate Reconciliation Adjustment infers or consumes a Commitment.

For an exact unresolved Gateway request in the **same original OPEN period**, an explicitly-bound existing reservation Commitment may be consumed using the existing bounded consume primitive after revalidation.

For a cross-period adjustment, the historical Commitment is not consumed in the new period. The old hold is finalized/released through explicit Gateway financial resolution instead.

## 34. Adjustment idempotency

All financial M15 write endpoints require `Idempotency-Key`.

Reuse the existing shared `api_idempotency` persistence pattern with a new bounded operation code rather than creating a second idempotency table.

Same key + same canonical request replays the committed result. Same key + different request hash is a deterministic conflict.

One committed adjustment converges to one stable Ledger posting key:

```text
RECONCILIATION_ADJUSTMENT:{adjustmentId}
```

## 35. Financial action actor

Reconciliation Adjustment is a reviewed finance action:

```text
posting_actor_type = MEMBER
posted_by_member_id = current organization member
```

It does not impersonate SYSTEM and it does not reuse the original Gateway principal as the finance actor.

---

# Part G — Gateway Financial Resolution

## 36. Why a separate Gateway resolution is required

Normal M13 Settlement only discovers current `FINAL` usage. Therefore possible-billable requests may exist with:

```text
no usage fact
INCOMPLETE
UNKNOWN
FINAL + RECONCILIATION_REQUIRED
```

They may also retain `PENDING_HOLD` and block Close.

M15 needs an explicit reviewed terminal financial decision without rewriting Gateway request/usage/Settlement facts.

## 37. Gateway financial resolution record

M15 adds one immutable final resolution per Gateway Request:

```text
gateway_financial_resolution
```

It records safe bounded lineage to:

```text
request
actual possible-billable route attempt
current usage fact when present
Gateway Settlement when present
reconciliation case
statement Charge when strongly bound
Reconciliation Adjustment when posted
reservation when present
review actor/reason/time
```

M14 guarantees at most one attempt per request can be possible-billable/completed, so one resolution per request is sufficient.

## 38. Resolution types

Exactly these initial types are supported:

```text
STATEMENT_ADJUSTMENT_POSTED
NO_CHARGE_CONFIRMED
```

### STATEMENT_ADJUSTMENT_POSTED

Authoritative reviewed evidence establishes a financial amount for unresolved Gateway work and an append-only Reconciliation Adjustment has committed.

If an effective Reservation exists, it becomes `FINALIZED` in the same financial resolution transaction.

### NO_CHARGE_CONFIRMED

Positive reviewed evidence proves the possible-billable Gateway attempt produced no Provider charge.

No Ledger amount is invented. Any effective Reservation is changed to `RELEASED` under the existing Budget/reservation lock discipline.

Statement absence alone can never automatically create this resolution.

## 39. Settlement status is not rewritten

A `RECONCILIATION_REQUIRED` Settlement remains historical `RECONCILIATION_REQUIRED` after M15 review.

An `INCOMPLETE`/`UNKNOWN` usage fact remains historical `INCOMPLETE`/`UNKNOWN`.

The new resolution is the downstream financial terminal fact used by Close/recovery.

## 40. Gateway Close blocker evolution

`PENDING_GATEWAY_FINANCIAL_WORK` continues to block the existing unresolved states **unless** the exact request has a valid M15 `gateway_financial_resolution`.

A valid resolution also requires no still-effective reservation contradicting the recorded reservation outcome.

M15 does not add a ninth Close blocker.

## 41. Gateway resolution transaction

For `STATEMENT_ADJUSTMENT_POSTED`, the financial transaction uses deterministic locking:

```text
BillingPeriod row(s), ascending id
→ selected Budget(s), ascending id
→ explicitly-bound Commitment when same-period exact resolution requires it
→ bound Reservation
→ reconciliation case / resolution identity
→ Reconciliation Adjustment
→ Ledger uniqueness/insertion
→ Budget Actual / optional explicit Commitment consumption
→ Reservation FINALIZED
→ Audit
→ gateway_financial_resolution insert
→ case resolution metadata
→ commit
```

For `NO_CHARGE_CONFIRMED`:

```text
original BillingPeriod
→ bound Budget
→ Reservation
→ reconciliation case / resolution identity
→ Reservation RELEASED
→ Audit
→ gateway_financial_resolution insert
→ case resolution metadata
→ commit
```

No Provider call and no Redis mutation occurs inside these transactions.

---

# Part H — Case Lifecycle and Staleness

## 42. Existing case lifecycle stays

```text
OPEN
→ INVESTIGATING
→ RESOLVED
```

M15 keeps the existing explicit reason code + resolution note requirement.

## 43. Resolution actions

A case can be resolved by a bounded action:

```text
ACCEPT_EXPLAINED_DIFFERENCE
LINK_CORRECTION
POST_RECONCILIATION_ADJUSTMENT
RESOLVE_GATEWAY_FINANCIAL_WORK
```

### ACCEPT_EXPLAINED_DIFFERENCE

Creates no financial mutation. This is the explicit reviewed exception path for a difference intentionally accepted without changing Ledger truth.

### LINK_CORRECTION

Links an already-committed append-only Correction to the case after same-org/provider/currency/lineage validation.

### POST_RECONCILIATION_ADJUSTMENT

Posts the exact required signed adjustment and resolves the case atomically.

### RESOLVE_GATEWAY_FINANCIAL_WORK

Creates the immutable Gateway financial resolution and, for a positive/credit financial amount, the required adjustment in one transaction.

## 44. Financial mutation forces rerun

Any Correction, Provider Charge posting, or Reconciliation Adjustment changes current internal financial truth.

The existing Close staleness rule remains authoritative:

```text
latest reconciliation basis hash
!=
current truth hash
→ FINANCIAL_BASIS_CHANGED
→ Close fails
```

Therefore a financial resolution is followed by a new reconciliation run before Close. M15 never tries to patch the old run's basis hash.

`ACCEPT_EXPLAINED_DIFFERENCE` makes no financial change, so a resolved current case can satisfy the existing Close rule when the basis itself remains unchanged.

---

# Part I — OPEN / CLOSED BillingPeriod Semantics

## 45. Reconciliation is evidence work, not always a financial write

M6 currently starts a run only through `lockOpenById`. M15 changes run admission to:

```text
OPEN   → allowed
CLOSED → allowed, evidence/read-only reconciliation
CLOSING → rejected
```

Starting a run briefly locks the BillingPeriod row so Close/Reopen cannot race with run identity creation.

The snapshot remains repeatable-read and read-only.

## 46. CLOSED run never reopens automatically

A reconciliation run against a CLOSED period may discover late statement evidence, Provider correction, period mismatch or legacy unresolved Gateway work.

The run may create cases/evidence but performs no automatic:

```text
reopen
Ledger correction
Ledger adjustment
Budget mutation
reservation mutation
```

All financial action remains an explicit reviewed command.

## 47. Reopen interaction

Existing `PERIOD_REOPEN` permission/reason/audit remains the only way to reopen a CLOSED period.

If a period is reopened after a CLOSED evidence run, later Close uses the current latest reconciliation run and current basis. Any intervening financial mutation makes an older run stale in the normal way.

---

# Part J — V23 Schema

## 48. Migration ownership

At the approved baseline, V22 is the highest migration.

M15 adds exactly:

```text
backend/src/main/resources/db/migration/V23__m15_hybrid_reconciliation.sql
```

unless a newer verified `main` consumes V23 before implementation begins.

V1-V22 are immutable.

## 49. `provider_charge_disposition`

Logical fields:

```text
id
org_id
charge_fact_id

disposition = RECONCILIATION_EVIDENCE | DIRECT_PROVIDER_CHARGE
decision_source = LEGACY_POSTED | SYSTEM_EXACT | SYSTEM_AGGREGATE_MATCH | MANUAL
reconciliation_run_id NULL
reconciliation_case_id NULL
decided_by_member_id NULL
reason_code NULL
resolution_note NULL
created_at
```

Required constraints:

```text
UNIQUE(org_id, charge_fact_id)
same-org FK to charge_fact
same-org optional FK to reconciliation_run/case/member
MANUAL requires member + bounded reason/note
system/legacy sources do not impersonate a member
```

V23 backfills existing `PROVIDER_CHARGE` Ledger source Charge ids as `DIRECT_PROVIDER_CHARGE / LEGACY_POSTED`.

## 50. `reconciliation_adjustment`

Logical fields:

```text
id
org_id
reconciliation_case_id
adjustment_key
provider_account_id
currency
amount
adjustment_period_id
gateway_request_id NULL
gateway_route_attempt_id NULL
statement_charge_fact_id NULL
created_by_member_id
reason_code
reason_note
created_at
```

Required constraints:

```text
UNIQUE(id, org_id)
UNIQUE(org_id, adjustment_key)
amount != 0 and fits DECIMAL(20,8)
currency uppercase CHAR(3)
same-org FKs for case/provider account/period/request/attempt/statement Charge/member
```

Allocation lines live in immutable `ledger_entry` rows; no duplicate adjustment-line table is required.

## 51. Ledger forward extension

V23 extends:

```text
ledger_posting.source_type += RECONCILIATION_ADJUSTMENT
ledger_posting.source_id = reconciliation_adjustment.id

ledger_entry.source_reconciliation_adjustment_id NULL
→ same-org FK reconciliation_adjustment
```

The direct-source XOR becomes at most one of:

```text
source_charge_fact_id
source_expense_claim_id
source_gateway_settlement_id
source_reconciliation_adjustment_id
```

Correction rows preserve exactly the source lineage of their corrected historical entry.

## 52. `gateway_financial_resolution`

Logical fields:

```text
id
org_id
request_id
route_attempt_id
usage_fact_id NULL
gateway_settlement_id NULL
reconciliation_case_id
statement_charge_fact_id NULL
reconciliation_adjustment_id NULL
reservation_id NULL
resolution_type
reservation_outcome
resolved_by_member_id
reason_code
reason_note
resolved_at
created_at
```

Required uniqueness:

```text
UNIQUE(org_id, request_id)
```

Resolution values:

```text
STATEMENT_ADJUSTMENT_POSTED
NO_CHARGE_CONFIRMED
```

Reservation outcome values:

```text
FINALIZED
RELEASED
NONE
```

Checks require adjustment lineage for `STATEMENT_ADJUSTMENT_POSTED` and forbid an adjustment for `NO_CHARGE_CONFIRMED`.

All relational references are same-organization where an org-owned parent exists.

## 53. `reconciliation_evidence`

This is immutable, bounded lineage/evidence, not a free-form evidence dump.

Logical fields:

```text
id
org_id
reconciliation_run_id
reconciliation_case_id NULL
evidence_key
provider_account_id
currency
match_kind
difference_kind NULL
charge_fact_id NULL
gateway_request_id NULL
gateway_route_attempt_id NULL
gateway_usage_fact_id NULL
gateway_settlement_id NULL
correction_group_id NULL
reconciliation_adjustment_id NULL
gateway_financial_resolution_id NULL
ledger_posting_id NULL
provider_request_id NULL
external_amount NULL
internal_amount NULL
difference_amount NULL
created_at
```

Match kinds:

```text
EXACT_PROVIDER_REQUEST
AGGREGATE_SCOPE
MANUAL_BINDING
RESOLUTION_ACTION
```

Required uniqueness:

```text
UNIQUE(org_id, reconciliation_run_id, evidence_key)
```

`evidence_key` is deterministic and bounded. It contains no secret or user content.

No raw Prompt/Completion/provider body is stored here.

## 54. No new idempotency table

M15 financial actions reuse `api_idempotency` through a genericized/narrow M15 adapter and distinct operation codes such as:

```text
RECONCILIATION_ADJUSTMENT
GATEWAY_FINANCIAL_RESOLUTION
RECONCILIATION_CHARGE_DISPOSITION
```

---

# Part K — API / Permission Contract

## 55. Existing APIs remain compatible

Keep:

```text
POST /api/v1/reconciliation-runs
GET  /api/v1/reconciliation-runs
GET  /api/v1/reconciliation-runs/{id}
GET  /api/v1/reconciliation-cases
GET  /api/v1/reconciliation-cases/{id}
POST /api/v1/reconciliation-cases/{id}/investigate
POST /api/v1/reconciliation-cases/{id}/return-open
POST /api/v1/reconciliation-cases/{id}/resolve
```

The existing simple `/resolve` path maps to `ACCEPT_EXPLAINED_DIFFERENCE` for backward compatibility, with M15 evidence classification added to the request/response contract as optional bounded fields.

## 56. M15 APIs

Add:

```text
GET  /api/v1/reconciliation-cases/{id}/evidence
POST /api/v1/reconciliation-cases/{id}/charge-dispositions
POST /api/v1/reconciliation-cases/{id}/adjustments
POST /api/v1/reconciliation-cases/{id}/gateway-resolutions
POST /api/v1/reconciliation-cases/{id}/link-correction
```

Financial POSTs require `Idempotency-Key`.

All identifier fields remain decimal strings in JSON, matching existing frontend/API convention.

## 57. Permissions

Reuse existing permission families; do not invent a new M15 permission namespace.

```text
RECONCILIATION_READ
→ read runs/cases/evidence

RECONCILIATION_RUN
→ create OPEN/CLOSED evidence runs

RECONCILIATION_RESOLVE
→ investigate/classify/accept/link evidence and decide charge disposition

RECONCILIATION_RESOLVE + LEDGER_CORRECT
→ post reconciliation adjustment
→ resolve Gateway financial work / no-charge decision

LEDGER_POST
→ existing direct Provider Charge posting after DIRECT disposition

LEDGER_CORRECT
→ existing Ledger Correction

PERIOD_REOPEN
→ existing explicit reopen only
```

A user lacking the financial permission receives the normal authorization failure; the UI hides/disables the financial action.

---

# Part L — Frontend

## 58. Reconciliation pages evolve, not duplicate

Extend the existing reconciliation feature rather than adding a second Hybrid section.

### Run page

Show:

```text
algorithm version
BillingPeriod status
provider/currency summary
matched/discrepancy counts
Hybrid scope count
exact evidence count
unresolved Gateway financial count
```

### Case detail

Show:

```text
external/internal/difference
structural case type
bounded difference evidence
statement Charge references
Gateway request / route / usage / Settlement lineage when available
current Ledger source and correction lineage
charge posting-fence/disposition state
available reviewed resolution actions
```

CLOSED-period cases display an explicit banner that reconciliation does not reopen history automatically.

## 59. Ledger frontend debt fixed in M15

The frontend Ledger contract must recognize all Backend Ledger sources:

```text
PROVIDER_CHARGE
EXPENSE_CLAIM
CORRECTION
GATEWAY_SETTLEMENT
RECONCILIATION_ADJUSTMENT
```

Entry/detail lineage also exposes:

```text
sourceGatewaySettlementId
sourceReconciliationAdjustmentId
```

and safe reconciliation references needed to navigate back to the case.

No Provider credential or request content is exposed.

---

# Part M — Close / Concurrency / Failure Safety

## 60. Close blockers

M15 reuses:

```text
OPEN_MATERIAL_RECONCILIATION
PENDING_GATEWAY_FINANCIAL_WORK
```

No new blocker code is added.

`OPEN_MATERIAL_RECONCILIATION` continues to enforce:

```text
latest run exists
run COMPLETED
algorithm/tolerance current
basis hash current
latest-run cases all RESOLVED
```

`PENDING_GATEWAY_FINANCIAL_WORK` recognizes a valid immutable `gateway_financial_resolution` as reviewed terminal financial truth for that request.

## 61. Financial lock order

M15 must never acquire Ledger/source locks before BillingPeriod/Budget locks.

Single-period adjustment:

```text
BillingPeriod
→ sorted Budgets
→ explicit Commitment when applicable
→ Reservation when applicable
→ source/case/resolution identity
→ Ledger uniqueness/insertion
```

Cross-period historical adjustment:

```text
BillingPeriods sorted by id
→ sorted Budgets
→ Reservation / explicit Commitment as applicable
→ source/case/resolution identity
→ Ledger
```

This is tested against Close/Reopen and existing M13 Settlement lock behavior on real MySQL.

## 62. Required races

Real MySQL integration tests must prove at least:

```text
Provider Charge posting vs existing Gateway Hybrid overlap
→ posting fence wins; no double count

Provider Charge posting vs new Gateway dispatch
→ shared period fence yields either safe direct posting before any billable Gateway work,
   or Hybrid block after durable Gateway intent; never ambiguous double posting

Reconciliation Adjustment vs Close
→ adjustment commits first and Close sees changed/stale truth,
   or Close wins and adjustment cannot write to a non-OPEN correction period

CLOSED-period reconciliation vs Reopen
→ stable period state is observed; no automatic reopen

Gateway financial resolution vs duplicate reviewer commands
→ one resolution / one adjustment / one Budget mutation / one reservation terminal transition

Gateway financial resolution vs M13 Settlement retry
→ one financial terminal path wins; no Settlement + Adjustment double posting

Correction vs M15 reconciliation rerun
→ basis change is detected; old run is stale
```

## 63. Settlement-vs-resolution conflict

Before committing a Gateway financial resolution, M15 re-reads Gateway Settlement under the relevant source lock.

If the request became normally `SETTLED` concurrently, M15 does not also post a reconciliation adjustment for missing financial truth. It fails/converges to the newly settled history and requires a fresh reconciliation run if a statement difference remains.

This prevents `SETTLED + missing-usage adjustment` double accounting.

## 64. Failure atomicity

Injected failure after any of these steps must roll the whole financial transaction back:

```text
adjustment row insertion
Ledger posting/entry insertion
Budget Actual mutation
explicit Commitment consumption
Reservation FINALIZED/RELEASED
Audit
Gateway financial resolution insert
case resolution update
```

No partial financial terminal state may remain.

---

# Part N — Audit / Metrics / Privacy

## 65. Audit

Representative bounded events:

```text
RECONCILIATION_CHARGE_DISPOSITION_DECIDED
RECONCILIATION_ADJUSTMENT_POSTED
RECONCILIATION_CORRECTION_LINKED
GATEWAY_FINANCIAL_RESOLVED
```

Metadata may contain IDs, provider code/account id, reason/difference codes, amount/currency, period ids and resolution type.

Never include:

```text
Provider secret
Gateway raw key
Authorization header
Prompt
Completion
reasoning
raw Provider response body
```

## 66. Metrics

Bounded metrics include:

```text
reconciliation_hybrid_match_total{match_kind,outcome}
reconciliation_difference_total{difference_kind}
reconciliation_adjustment_total{outcome}
gateway_financial_resolution_total{resolution_type,outcome}
provider_charge_hybrid_fence_total{outcome}
```

Never use org/request/case/Provider-request ids as metric labels.

---

# Part O — Required Acceptance Evidence

## 67. Schema

Real MySQL 8.4 Flyway test proves:

```text
V1-V22 unchanged
V23 applies cleanly
same-org FKs
bounded CHECKs
Ledger source XOR
legacy Provider Charge disposition backfill
business uniqueness
```

## 68. Aggregate reconciliation

Tests prove:

```text
Gateway Settlement Ledger is included in internal truth
Provider Charge Ledger is still included
Corrections preserve and contribute through source lineage
Reconciliation Adjustment contributes through source lineage
mixed direct + Gateway provider/currency scope aggregates correctly
```

## 69. Matching safety

Tests prove:

```text
exact request id + unique lineage → exact evidence
SAFE attempt → never exact billable match
ambiguous duplicate Provider request id → no automatic binding
missing Provider request id → aggregate only
amount/time proximity → never creates exact match
```

## 70. Double-count prevention

Tests prove:

```text
Hybrid-overlap Charge without DIRECT disposition → V1 posting blocked
DIRECT disposition → existing V1 posting works
non-Hybrid Provider Charge → existing V1 posting works unchanged
already-posted legacy Charge remains replayable and is not reclassified as Gateway evidence
```

## 71. Financial resolution

Tests prove:

```text
SETTLED mismatch → original Settlement unchanged + append-only Correction
Gateway correction preserves source_gateway_settlement_id
missing/UNKNOWN Gateway cost → reviewed Adjustment + resolution + hold finalization
positive no-charge proof → no Ledger posting + hold release
aggregate difference → explicit lines only; no inferred pro-rata split
cross-period CLOSED case → no auto reopen; adjustment only into explicit OPEN correction period
```

## 72. Close

Tests prove:

```text
unresolved Hybrid case blocks Close
financial action changes basis → old run stale
rerun clean/resolved → Close may pass
valid Gateway financial resolution clears only that request's Gateway blocker
invalid/missing resolution keeps blocker
```

## 73. Regression matrix

M15 final verification includes:

```text
backend unit
backend architecture
backend full integration
Gateway unit/architecture/integration regression
frontend test/lint/build
browser E2E
Docker build
git diff --check
hosted CI
hosted Security / CodeQL / Trivy
```

M14 routing/failover/provider-call behavior must remain green without needing new production Gateway behavior.

---

# Part P — Definition of Done

## 74. M15 complete only when all are true

```text
[freeze] one canonical M6-evolved reconciliation run/case lifecycle
[freeze] external statement truth vs Provider/Gateway/Adjustment Ledger truth
[freeze] exact matching requires strong unique evidence
[freeze] aggregate matching never invents request ownership
[freeze] required difference vocabulary is represented and evidence-gated
[freeze] Provider Charge Hybrid posting fence prevents realtime + statement double count
[freeze] existing committed Provider Charge history remains compatible
[freeze] SETTLED Gateway history is never rewritten
[freeze] Gateway corrections preserve source_gateway_settlement_id
[freeze] no-history/aggregate differences use first-class RECONCILIATION_ADJUSTMENT
[freeze] unresolved Gateway work has explicit reviewed gateway_financial_resolution
[freeze] PENDING_HOLD is finalized/released only by a valid financial terminal path
[freeze] OPEN and CLOSED periods may be reconciled; CLOSING may not
[freeze] CLOSED period is never automatically reopened
[freeze] financial mutations force reconciliation rerun through basis staleness
[freeze] existing Close blockers are reused; no redundant blocker
[freeze] idempotency, atomicity and real-MySQL races prevent duplicate financial effects
[freeze] frontend exposes Gateway/Adjustment lineage without secrets
[freeze] V23 is the only M15 migration from the approved baseline
```

Any implementation that uses fuzzy matching, posts the same Provider statement Charge on top of already-covered Gateway cost, mutates `SETTLED` history, silently reassigns BillingPeriod, treats statement absence as zero, or resolves possible-billable Gateway work without durable reviewed evidence violates this design.
