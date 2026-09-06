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

The following second double-count path is equally forbidden:

```text
M15 statement-backed RECONCILIATION_ADJUSTMENT
+
later M13 Settlement for the same Gateway Request
```

M15 introduces a Provider-charge Hybrid posting fence and a request-level Gateway financial terminal resolution. Both normal V1 Provider Charge posting and later M13 Settlement must respect those terminal decisions.

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

## 10. A run may contain unresolved Gateway evidence without a case

Gateway financial uncertainty and aggregate amount discrepancy are related but not identical concepts.

For example:

```text
post-dispatch request
+ UNKNOWN usage
+ no Provider statement charge yet
+ no Ledger entry
```

has unresolved Gateway financial work but may produce no M6 aggregate case because both external and internal monetary truth are absent.

Therefore `reconciliation_evidence` may be attached to a run with `reconciliation_case_id = NULL`, and Gateway financial resolution is anchored to a reconciliation run with an optional case. M15 does not fabricate a zero-amount reconciliation case solely to host unresolved Gateway work.

## 11. Reconciliation algorithm version

M15 changes the canonical algorithm version to:

```text
M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2
```

Old M6 runs remain immutable history. `OPEN_MATERIAL_RECONCILIATION` treats an old algorithm version as stale under the existing rule and requires a new run before a future Close.

## 12. External truth

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

## 13. Internal truth

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

## 14. One aggregate comparison, richer evidence

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

## 15. Matching precedence

M15 uses this order:

```text
1. exact Provider request correlation when provable
2. deterministic provider/currency/BillingPeriod aggregate comparison
3. explicit human-reviewed binding when necessary
4. otherwise UNCLASSIFIED / unresolved
```

There is no fuzzy fallback after level 1 fails.

## 16. Exact Provider request correlation

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

## 17. External correlation profile

`raw_provider_record.provider_record_key` is generic and cannot globally be assumed to mean Provider request id.

M15 defines a bounded Provider/source-schema correlation profile that explicitly declares whether a safe persisted field represents:

```text
PROVIDER_REQUEST_ID
or
NONE
```

The matcher consumes persisted normalized/canonical lineage only. It does not re-read raw Provider payload to invent new semantics.

Current imports that lack request-level Provider ids remain fully supported by aggregate reconciliation. No adapter is forced to fabricate a key.

## 18. Aggregate match

When exact request correlation is absent or incomplete, M15 compares only the deterministic M6 scope:

```text
organization
+ BillingPeriod [start,end)
+ provider_account_id
+ currency
```

The statement side is confirmed canonical Charges in the period. The internal side is Provider-related Ledger entries posted to that BillingPeriod.

A Provider export's day/month/bucket granularity remains authoritative as imported; M15 does not split one statement Charge into synthetic requests.

**An aggregate match never auto-classifies any individual Charge as `RECONCILIATION_EVIDENCE` or `DIRECT_PROVIDER_CHARGE`.** Aggregate arithmetic proves a scope-level relationship, not per-Charge ownership. When an individual Charge requires a posting disposition and no exact correlation exists, the disposition remains a reviewed manual decision.

## 19. Human-reviewed binding

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

## 20. Difference codes

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

## 21. Evidence-gated classification

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

May be classified only when persisted history proves that authoritative external cost evidence became confirmed after the BillingPeriod had already closed. If the current import model does not retain a precise enough confirmation timestamp for a Provider/schema, the classifier remains `UNCLASSIFIED` rather than deriving one from an unrelated update timestamp.

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

## 22. Multiple explanations per case

One aggregate case may contain multiple evidence items and multiple difference codes.

M15 does not force a single guessed root cause for the whole provider/currency discrepancy.

---

# Part E — Provider Charge Hybrid Posting Fence

## 23. Hybrid overlap

A Charge is a Hybrid candidate when, for its organization/provider account/currency/BillingPeriod, durable Gateway facts contain at least one potentially billable route attempt whose status is not `PLANNED` and not `SAFE_NO_BILLABLE_EXECUTION`.

Currency is resolved from the frozen Gateway Pricing/Settlement context; no FX comparison is attempted.

## 24. Posting rule

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

## 25. Posting-fence race

The posting transaction keeps the established financial lock order beginning with BillingPeriod.

After the BillingPeriod lock is held, the Hybrid overlap/disposition gate is revalidated before the source Charge is posted. Gateway dispatch also uses the BillingPeriod financial fence, so a new potentially billable Gateway attempt cannot race in after the posting decision for the same period.

A conservative false block is acceptable and retriable. A false allow that double-counts cost is not.

## 26. Charge disposition

M15 persists an immutable final disposition for a Charge only when a decision is needed:

```text
RECONCILIATION_EVIDENCE
DIRECT_PROVIDER_CHARGE
```

Decision sources are bounded:

```text
LEGACY_POSTED
SYSTEM_EXACT
MANUAL
```

`SYSTEM_EXACT` is allowed only after the exact Provider-request correlation rules in section 16 succeed. Aggregate matching never creates a system disposition for an individual Charge.

`DIRECT_PROVIDER_CHARGE` means the entire canonical Charge may be handled by the existing V1 allocation/posting workflow. It must not be used to split an aggregate statement bucket that mixes Gateway and direct traffic.

Incorrect historical decisions are corrected through append-only financial correction/reconciliation; the disposition row is not silently rewritten.

## 27. Legacy compatibility

V23 backfills already-posted `PROVIDER_CHARGE` sources as:

```text
DIRECT_PROVIDER_CHARGE
+ LEGACY_POSTED
```

so M15 does not reinterpret existing committed Ledger history as duplicate Gateway evidence.

---

# Part F — Financial Resolution Paths

## 28. Path 1: settled request mismatch → existing Correction

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

## 29. Path 2: no historical entry / aggregate difference → Reconciliation Adjustment

Existing Correction requires a target historical Ledger Entry. It must not be abused when none exists.

M15 adds first-class Ledger source:

```text
RECONCILIATION_ADJUSTMENT
```

A Reconciliation Adjustment is append-only, human-triggered and linked to exactly one reconciliation run; it may additionally reference the aggregate case that motivated it.

Two adjustment scopes exist:

```text
CASE_FULL
GATEWAY_REQUEST
```

## 30. CASE_FULL adjustment

A `CASE_FULL` adjustment resolves the entire current aggregate difference for one case.

Before posting, the service recomputes/validates that the reconciliation run's financial basis is still current. If current truth no longer hashes to the run basis, the command fails with a stale-basis conflict and requires a new run.

The required signed amount is exactly:

```text
external_amount - internal_amount
```

The request may contain one or more explicit allocation lines, but their signed scale-8 amounts must sum exactly to the required amount.

A zero required adjustment is not posted; the case must be resolved by explanation or another evidence action.

A successful `CASE_FULL` adjustment may mark that historical case `RESOLVED` atomically for audit/history, but the financial mutation still makes the run basis stale and a new run is mandatory before Close.

## 31. GATEWAY_REQUEST adjustment

A `GATEWAY_REQUEST` adjustment resolves one strongly-bound unresolved Gateway request. It may represent only part of the aggregate provider/currency difference.

It requires exact automatic correlation or an explicit reviewed binding to one Gateway Request and authoritative Provider amount evidence.

The signed request adjustment is derived only from that request's authoritative external amount minus already-posted internal amount attributable to the same request. It is never derived by dividing the aggregate case difference.

Its target is frozen from the Gateway request:

```text
financial_scope_type
financial_scope_id
```

The client cannot retarget the request during resolution.

A successful request-level adjustment records resolution evidence but **does not automatically mark the whole aggregate reconciliation case RESOLVED**, because sibling Gateway/evidence items may remain. The mutation makes the old run stale and a new reconciliation run becomes the canonical aggregate state.

## 32. Aggregate target rules

For `CASE_FULL`, when no request ownership is provable, the reviewer may supply one or more explicit adjustment lines.

Every line:

```text
uses the case currency
has exactly one PROJECT | TEAM | COST_CENTER target
uses exact scale-8 signed money
```

Every target is validated as an ACTIVE same-organization target using existing allocation target rules.

M15 never infers a split or remainder.

## 33. Adjustment period rules

If the reconciled BillingPeriod is `OPEN`, a new `CASE_FULL` adjustment for that case must post into that same period. A same-period `GATEWAY_REQUEST` adjustment likewise posts into the request's original OPEN period.

If the historical/reconciled period is `CLOSED`, M15 never reopens it automatically. The reviewer must either:

```text
A. explicitly reopen the historical period using existing PERIOD_REOPEN governance,
   then post into that reopened period;

or

B. choose another currently OPEN BillingPeriod as the correction period.
```

When the historical and correction periods differ, both period rows are locked in ascending id order before financial mutation. This prevents deadlocks with concurrent Close/Reopen operations.

## 34. Budget behavior

Reconciliation Adjustment reuses existing Budget selection and `budget.actual_amount` mutation rules.

For each adjustment line:

```text
exact financial scope + currency Budget
→ ORG fallback + same currency
→ no Budget
```

A missing Budget does not erase already-incurred authoritative cost; the Ledger adjustment may still post unbudgeted under the same incurred-cost principle used by Gateway Settlement/Correction.

Signed adjustment amount changes Budget Actual by the same signed amount when a Budget exists.

## 35. Commitment behavior

No aggregate `CASE_FULL` adjustment infers or consumes a Commitment.

For an exact unresolved Gateway request in the **same original OPEN period**, an explicitly-bound existing reservation Commitment may be consumed using the existing bounded consume primitive after revalidation and only for positive incurred adjustment amount.

For a cross-period adjustment, the historical Commitment is not consumed in the new period. The old hold is finalized/released through explicit Gateway financial resolution instead.

## 36. Adjustment idempotency

All financial M15 write endpoints require `Idempotency-Key`.

Reuse the existing shared `api_idempotency` persistence pattern with a new bounded operation code rather than creating a second idempotency table.

Same key + same canonical request replays the committed result. Same key + different request hash is a deterministic conflict.

One committed adjustment converges to one stable Ledger posting key:

```text
RECONCILIATION_ADJUSTMENT:{adjustmentId}
```

## 37. Financial action actor

Reconciliation Adjustment is a reviewed finance action:

```text
posting_actor_type = MEMBER
posted_by_member_id = current organization member
```

It does not impersonate SYSTEM and it does not reuse the original Gateway principal as the finance actor.

---

# Part G — Gateway Financial Resolution

## 38. Why a separate Gateway resolution is required

Normal M13 Settlement only discovers current `FINAL` usage. Therefore possible-billable requests may exist with:

```text
no usage fact
INCOMPLETE
UNKNOWN
FINAL + RECONCILIATION_REQUIRED
```

They may also retain `PENDING_HOLD` and block Close.

M15 needs an explicit reviewed terminal financial decision without rewriting Gateway request/usage/Settlement facts.

## 39. Resolution eligibility — never compete with normal M13 Settlement

M15 Gateway financial resolution is **not** an alternate fast path around normal Settlement.

It is eligible only when the request has a non-SAFE possible-billable attempt and one of these is true:

```text
A. current financial observation is absent, INCOMPLETE or UNKNOWN

or

B. a Gateway Settlement already exists in RECONCILIATION_REQUIRED
```

It is explicitly **not eligible** when:

```text
route attempt = SAFE_NO_BILLABLE_EXECUTION
current usage is ordinary FINAL and no Settlement exists yet
Settlement status = PENDING
Settlement status = RETRYABLE_FAILED
Settlement status = SETTLED
```

For ordinary current `FINAL` usage, M13 Settlement owns the normal path. `PENDING` / `RETRYABLE_FAILED` must continue through the existing worker/retry semantics rather than being overridden by a reviewer.

## 40. Request source lock and terminal precedence

Gateway financial resolution locks the Gateway Request source row after the BillingPeriod/Budget/Reservation financial locks and re-reads:

```text
current_route_attempt_id
current_usage_fact_id
current route status
current usage status
current Settlement status when present
existing gateway_financial_resolution
```

This source lock serializes resolution against late Gateway usage publication/finalization on the same request without allowing Backend to mutate Gateway-owned request fields.

If normal M13 Settlement has become `SETTLED`, that committed Settlement wins and M15 resolution aborts/converges without an adjustment.

## 41. A committed resolution is terminal for future M13 Settlement

Once `gateway_financial_resolution` commits, M13 must never later post a normal Gateway Settlement for the same request.

M15 therefore evolves Backend Settlement correctness in two places:

```text
GatewaySettlementDiscoveryService
→ excludes requests with an existing gateway_financial_resolution

GatewaySettlementService
→ revalidates that no gateway_financial_resolution exists before financial posting
```

This is defense in depth: discovery avoids creating work, while Settlement transaction revalidation closes stale-discovery/concurrency windows.

A late appended `FINAL` Gateway usage fact after a statement-backed resolution remains immutable operational/reconciliation evidence; it does **not** reopen the financial terminal decision or create a second Ledger posting. A later Provider statement may create a new reconciliation difference/correction if authoritative truth changes again.

## 42. Gateway financial resolution record

M15 adds one immutable final resolution per Gateway Request:

```text
gateway_financial_resolution
```

It records safe bounded lineage to:

```text
reconciliation run
optional reconciliation case
request
actual possible-billable route attempt
current usage fact when present
Gateway Settlement when present
statement Charge when strongly bound
Reconciliation Adjustment when posted
reservation when present
review actor/reason/time
```

M14 guarantees at most one attempt per request can be possible-billable/completed, so one resolution per request is sufficient.

## 43. Resolution types

Exactly these initial types are supported:

```text
STATEMENT_ADJUSTMENT_POSTED
NO_CHARGE_CONFIRMED
```

### STATEMENT_ADJUSTMENT_POSTED

Authoritative reviewed evidence establishes a financial amount for unresolved Gateway work and a `GATEWAY_REQUEST` Reconciliation Adjustment has committed.

If an effective Reservation exists, it becomes `FINALIZED` in the same financial resolution transaction.

### NO_CHARGE_CONFIRMED

Positive reviewed evidence proves the possible-billable Gateway attempt produced no Provider charge.

No Ledger amount is invented. Any effective Reservation is changed to `RELEASED` under the existing Budget/reservation lock discipline.

Statement absence alone can never automatically create this resolution.

## 44. Settlement/usage history is not rewritten

A `RECONCILIATION_REQUIRED` Settlement remains historical `RECONCILIATION_REQUIRED` after M15 review.

An `INCOMPLETE`/`UNKNOWN` usage fact remains historical `INCOMPLETE`/`UNKNOWN`.

A later `FINAL` usage revision may remain visible as later operational evidence, but it cannot create a second financial terminal path after a committed M15 resolution.

## 45. Gateway resolution need not have a reconciliation case

Every Gateway financial resolution references the reconciliation run that reviewed it.

`reconciliation_case_id` is nullable because `NO_CHARGE_CONFIRMED` or unresolved Gateway financial work can legitimately exist when there is no external/internal amount discrepancy and therefore no M6 aggregate case.

When a case exists, the resolution links to it as evidence. Resolving one request never implies that every other evidence item in the same provider/currency case is resolved.

## 46. Gateway Close blocker evolution

`PENDING_GATEWAY_FINANCIAL_WORK` continues to block the existing unresolved states **unless** the exact request has a valid immutable M15 `gateway_financial_resolution`.

A valid resolution also requires no still-effective reservation contradicting the recorded reservation outcome.

M15 does not add a ninth Close blocker.

## 47. Gateway resolution transaction

For `STATEMENT_ADJUSTMENT_POSTED`, the financial transaction uses deterministic locking:

```text
BillingPeriod row(s), ascending id
→ selected Budget(s), ascending id
→ explicitly-bound Commitment when same-period exact resolution requires it
→ bound Reservation
→ reconciliation run / optional case / resolution identity
→ Gateway Request source row
→ current usage/Settlement source truth
→ Reconciliation Adjustment
→ Ledger uniqueness/insertion
→ Budget Actual / optional explicit Commitment consumption
→ Reservation FINALIZED
→ Audit
→ gateway_financial_resolution insert
→ resolution evidence insert
→ commit
```

For `NO_CHARGE_CONFIRMED`:

```text
original BillingPeriod
→ bound Budget when present
→ Reservation when present
→ reconciliation run / optional case / resolution identity
→ Gateway Request source row
→ current usage/Settlement source truth
→ Reservation RELEASED when effective
→ Audit
→ gateway_financial_resolution insert
→ resolution evidence insert
→ commit
```

No Provider call and no Redis mutation occurs inside these transactions.

---

# Part H — Case Lifecycle and Staleness

## 48. Existing case lifecycle stays

```text
OPEN
→ INVESTIGATING
→ RESOLVED
```

M15 keeps the existing explicit reason code + resolution note requirement.

## 49. Case-level actions vs evidence-item actions

A provider/currency case may contain multiple request/evidence items. Therefore M15 separates actions that resolve the whole aggregate case from actions that resolve one evidence item.

### Whole-case actions

```text
ACCEPT_EXPLAINED_DIFFERENCE
POST_CASE_FULL_ADJUSTMENT
```

These may mark the case `RESOLVED` only after validating the action applies to the whole current case. `POST_CASE_FULL_ADJUSTMENT` additionally requires the run basis to still be current immediately before posting.

### Evidence-item actions

```text
DECIDE_CHARGE_DISPOSITION
LINK_CORRECTION
RESOLVE_GATEWAY_FINANCIAL_WORK
```

These append immutable resolution evidence. They do **not** automatically mark the whole case `RESOLVED` merely because one request/Charge has been handled.

A reviewer may subsequently accept a remaining explained difference, or a financial mutation may make the run stale and require a new run.

## 50. Financial mutation forces rerun

Any Correction, Provider Charge posting, or Reconciliation Adjustment changes current internal financial truth.

The existing Close staleness rule remains authoritative:

```text
latest reconciliation basis hash
!=
current truth hash
→ FINANCIAL_BASIS_CHANGED
→ Close fails
```

Therefore a financial resolution is followed by a new reconciliation run before Close. M15 never patches the old run's basis hash.

An aggregate financial action may mark its historical case resolved for audit, but that does not bypass basis staleness.

`ACCEPT_EXPLAINED_DIFFERENCE` makes no financial change, so a resolved current case can satisfy the existing Close rule when the basis itself remains unchanged.

## 51. Stale-basis rules for commands

Commands that derive money from an aggregate case (`POST_CASE_FULL_ADJUSTMENT`) require a current run basis.

Request-level financial resolution derives money only from its strongly-bound request/statement evidence and current request-specific internal financial truth; it does not divide or trust the stale aggregate case difference. It still records the originating run and forces a rerun after mutation.

This permits several independently proven request resolutions without guessing aggregate allocation while preventing a stale aggregate case amount from being posted.

---

# Part I — OPEN / CLOSED BillingPeriod Semantics

## 52. Reconciliation is evidence work, not always a financial write

M6 currently starts a run only through `lockOpenById`. M15 changes run admission to:

```text
OPEN   → allowed
CLOSED → allowed, evidence/read-only reconciliation
CLOSING → rejected
```

Starting a run briefly locks the BillingPeriod row so Close/Reopen cannot race with run identity creation.

The snapshot remains repeatable-read and read-only.

## 53. CLOSED run never reopens automatically

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

## 54. Reopen interaction

Existing `PERIOD_REOPEN` permission/reason/audit remains the only way to reopen a CLOSED period.

If a period is reopened after a CLOSED evidence run, later Close uses the current latest reconciliation run and current basis. Any intervening financial mutation makes an older run stale in the normal way.

---

# Part J — V23 Schema

## 55. Migration ownership

At the approved baseline, V22 is the highest migration.

M15 adds exactly:

```text
backend/src/main/resources/db/migration/V23__m15_hybrid_reconciliation.sql
```

unless a newer verified `main` consumes V23 before implementation begins.

V1-V22 are immutable.

## 56. `provider_charge_disposition`

Logical fields:

```text
id
org_id
charge_fact_id

disposition = RECONCILIATION_EVIDENCE | DIRECT_PROVIDER_CHARGE
decision_source = LEGACY_POSTED | SYSTEM_EXACT | MANUAL
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
SYSTEM_EXACT requires reconciliation evidence proving exact correlation
system/legacy sources do not impersonate a member
```

V23 backfills existing `PROVIDER_CHARGE` Ledger source Charge ids as `DIRECT_PROVIDER_CHARGE / LEGACY_POSTED`.

## 57. `reconciliation_adjustment`

Logical fields:

```text
id
org_id
reconciliation_run_id
reconciliation_case_id NULL
adjustment_key
adjustment_scope = CASE_FULL | GATEWAY_REQUEST
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
same-org FKs for run/case/provider account/period/request/attempt/statement Charge/member
CASE_FULL requires reconciliation_case_id and no gateway_request_id
GATEWAY_REQUEST requires gateway_request_id + route_attempt_id
```

Allocation lines live in immutable `ledger_entry` rows; no duplicate adjustment-line table is required.

## 58. Ledger forward extension

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

## 59. `gateway_financial_resolution`

Logical fields:

```text
id
org_id
reconciliation_run_id
reconciliation_case_id NULL
request_id
route_attempt_id
usage_fact_id NULL
gateway_settlement_id NULL
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

Checks require a `GATEWAY_REQUEST` adjustment lineage for `STATEMENT_ADJUSTMENT_POSTED` and forbid an adjustment for `NO_CHARGE_CONFIRMED`.

All relational references are same-organization where an org-owned parent exists.

## 60. `reconciliation_evidence`

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
GATEWAY_UNRESOLVED
MANUAL_BINDING
RESOLUTION_ACTION
```

Required uniqueness:

```text
UNIQUE(org_id, reconciliation_run_id, evidence_key)
```

`evidence_key` is deterministic and bounded. It contains no secret or user content.

No raw Prompt/Completion/provider body is stored here.

## 61. No new idempotency table

M15 financial actions reuse `api_idempotency` through a genericized/narrow M15 adapter and distinct operation codes such as:

```text
RECONCILIATION_ADJUSTMENT
GATEWAY_FINANCIAL_RESOLUTION
RECONCILIATION_CHARGE_DISPOSITION
```

---

# Part K — API / Permission Contract

## 62. Existing APIs remain compatible

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

## 63. M15 APIs

Add:

```text
GET  /api/v1/reconciliation-runs/{runId}/evidence
GET  /api/v1/reconciliation-cases/{caseId}/evidence
POST /api/v1/reconciliation-cases/{caseId}/charge-dispositions
POST /api/v1/reconciliation-cases/{caseId}/adjustments
POST /api/v1/reconciliation-runs/{runId}/gateway-resolutions
POST /api/v1/reconciliation-cases/{caseId}/link-correction
```

The run-level Gateway resolution endpoint is intentional: unresolved Gateway work can exist without an aggregate case. When a relevant case exists, the server stores/returns its id as optional lineage.

Financial POSTs require `Idempotency-Key`.

All identifier fields remain decimal strings in JSON, matching existing frontend/API convention.

## 64. Permissions

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

## 65. Reconciliation pages evolve, not duplicate

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
run-level unresolved Gateway evidence even when no case exists
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
available reviewed whole-case and evidence-item actions
```

CLOSED-period cases display an explicit banner that reconciliation does not reopen history automatically.

## 66. Ledger frontend debt fixed in M15

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

and safe reconciliation references needed to navigate back to the run/case.

No Provider credential or request content is exposed.

---

# Part M — Close / Concurrency / Failure Safety

## 67. Close blockers

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

## 68. Financial lock order

M15 must never acquire Ledger/source locks before BillingPeriod/Budget locks.

Single-period adjustment:

```text
BillingPeriod
→ sorted Budgets
→ explicit Commitment when applicable
→ Reservation when applicable
→ reconciliation identity
→ Gateway Request source row when applicable
→ other source truth
→ Ledger uniqueness/insertion
```

Cross-period historical adjustment:

```text
BillingPeriods sorted by id
→ sorted Budgets
→ Reservation / explicit Commitment as applicable
→ reconciliation identity
→ Gateway Request source row when applicable
→ other source truth
→ Ledger
```

This is tested against Close/Reopen, Gateway usage publication and existing M13 Settlement lock behavior on real MySQL.

## 69. Required races

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

Gateway financial resolution vs late usage FINAL
→ request-row serialization yields either FINAL first and resolution rejects,
   or resolution first and later FINAL cannot create M13 Settlement

Gateway financial resolution vs M13 Settlement retry
→ normal PENDING/RETRYABLE_FAILED Settlement cannot be overridden;
   if Settlement becomes SETTLED it wins and no M15 adjustment is posted

request-level resolution inside multi-evidence case
→ only that request is terminal; sibling evidence never becomes implicitly RESOLVED

Correction vs M15 reconciliation rerun
→ basis change is detected; old run is stale
```

## 70. Settlement-vs-resolution defense in depth

Before committing a Gateway financial resolution, M15 re-reads current usage and Gateway Settlement under the request/source lock.

If the request is in an ordinary M13 Settlement path (`FINAL` with no Settlement yet, `PENDING`, or `RETRYABLE_FAILED`), the M15 resolution command is rejected and cannot post an adjustment.

If the request became normally `SETTLED` concurrently, M15 does not also post a reconciliation adjustment for missing financial truth.

After M15 resolution commits, M13 discovery excludes it and M13 Settlement transaction revalidates resolution absence before posting.

This prevents both directions of `Settlement + Reconciliation Adjustment` double accounting.

## 71. Failure atomicity

Injected failure after any of these steps must roll the whole financial transaction back:

```text
adjustment row insertion
Ledger posting/entry insertion
Budget Actual mutation
explicit Commitment consumption
Reservation FINALIZED/RELEASED
Audit
Gateway financial resolution insert
resolution evidence insert
case resolution update when a whole-case action applies
```

No partial financial terminal state may remain.

---

# Part N — Audit / Metrics / Privacy

## 72. Audit

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

## 73. Metrics

Bounded metrics include:

```text
reconciliation_hybrid_match_total{match_kind,outcome}
reconciliation_difference_total{difference_kind}
reconciliation_adjustment_total{scope,outcome}
gateway_financial_resolution_total{resolution_type,outcome}
provider_charge_hybrid_fence_total{outcome}
```

Never use org/request/case/Provider-request ids as metric labels.

---

# Part O — Required Acceptance Evidence

## 74. Schema

Real MySQL 8.4 Flyway test proves:

```text
V1-V22 unchanged
V23 applies cleanly
same-org FKs
bounded CHECKs
Ledger source XOR
legacy Provider Charge disposition backfill
business uniqueness
CASE_FULL/GATEWAY_REQUEST structural constraints
run-level Gateway resolution with nullable case
```

## 75. Aggregate reconciliation

Tests prove:

```text
Gateway Settlement Ledger is included in internal truth
Provider Charge Ledger is still included
Corrections preserve and contribute through source lineage
Reconciliation Adjustment contributes through source lineage
mixed direct + Gateway provider/currency scope aggregates correctly
unresolved Gateway work can emit run-level evidence without fabricating a case
```

## 76. Matching safety

Tests prove:

```text
exact request id + unique lineage → exact evidence
SAFE attempt → never exact billable match
ambiguous duplicate Provider request id → no automatic binding
missing Provider request id → aggregate only
aggregate match → never creates automatic per-Charge disposition
amount/time proximity → never creates exact match
unsupported late-charge timestamp evidence → UNCLASSIFIED rather than guessed LATE_CHARGE
```

## 77. Double-count prevention

Tests prove:

```text
Hybrid-overlap Charge without DIRECT disposition → V1 posting blocked
DIRECT disposition → existing V1 posting works
non-Hybrid Provider Charge → existing V1 posting works unchanged
already-posted legacy Charge remains replayable and is not reclassified as Gateway evidence
M15-resolved Gateway request + later FINAL usage → no M13 Settlement/Ledger duplicate
```

## 78. Financial resolution

Tests prove:

```text
SETTLED mismatch → original Settlement unchanged + append-only Correction
Gateway correction preserves source_gateway_settlement_id
missing/UNKNOWN Gateway cost → reviewed GATEWAY_REQUEST Adjustment + resolution + hold finalization
positive no-charge proof with no aggregate case → run-level resolution + no Ledger posting + hold release
ordinary FINAL with no Settlement → M15 resolution rejected; normal M13 Settlement owns path
PENDING Settlement → M15 resolution rejected
RETRYABLE_FAILED Settlement → M15 resolution rejected
concurrent M13 SETTLED winner → no M15 adjustment/resolution duplicate
late FINAL after committed M15 resolution → discovery/service refuse normal Settlement
GATEWAY_REQUEST adjustment → never marks sibling case evidence resolved
CASE_FULL amount → exactly current external minus internal and rejects stale basis
aggregate difference → explicit lines only; no inferred pro-rata split
cross-period CLOSED case → no auto reopen; adjustment only into explicit OPEN correction period
```

## 79. Close

Tests prove:

```text
unresolved Hybrid case blocks Close
run-level unresolved Gateway work still blocks through PENDING_GATEWAY_FINANCIAL_WORK
financial action changes basis → old run stale
rerun clean/resolved → Close may pass
valid Gateway financial resolution clears only that request's Gateway blocker
invalid/missing resolution keeps blocker
```

## 80. Regression matrix

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

## 81. M15 complete only when all are true

```text
[freeze] one canonical M6-evolved reconciliation run/case lifecycle
[freeze] run-level Gateway evidence is allowed without fabricating zero-amount cases
[freeze] external statement truth vs Provider/Gateway/Adjustment Ledger truth
[freeze] exact matching requires strong unique evidence
[freeze] aggregate matching never invents request ownership or per-Charge disposition
[freeze] required difference vocabulary is represented and evidence-gated
[freeze] Provider Charge Hybrid posting fence prevents realtime + statement double count
[freeze] existing committed Provider Charge history remains compatible
[freeze] SETTLED Gateway history is never rewritten
[freeze] Gateway corrections preserve source_gateway_settlement_id
[freeze] no-history/aggregate differences use first-class RECONCILIATION_ADJUSTMENT with explicit scope
[freeze] request-level resolution never resolves sibling evidence or trusts aggregate pro-rata
[freeze] aggregate money actions reject stale reconciliation basis
[freeze] unresolved Gateway work has explicit reviewed gateway_financial_resolution
[freeze] M15 resolution never competes with normal FINAL/PENDING/RETRYABLE_FAILED M13 Settlement
[freeze] committed M15 resolution prevents future late-FINAL M13 Settlement double posting
[freeze] PENDING_HOLD is finalized/released only by a valid financial terminal path
[freeze] OPEN and CLOSED periods may be reconciled; CLOSING may not
[freeze] CLOSED period is never automatically reopened
[freeze] financial mutations force reconciliation rerun through basis staleness
[freeze] existing Close blockers are reused; no redundant blocker
[freeze] idempotency, atomicity and real-MySQL races prevent duplicate financial effects
[freeze] frontend exposes Gateway/Adjustment lineage without secrets
[freeze] V23 is the only M15 migration from the approved baseline
```

Any implementation that uses fuzzy matching, posts the same Provider statement Charge on top of already-covered Gateway cost, posts a later normal Settlement after a statement-backed Gateway resolution, mutates `SETTLED` history, silently reassigns BillingPeriod, treats statement absence as zero, overrides a normal M13 Settlement path, resolves sibling evidence implicitly, trusts a stale aggregate amount, or resolves possible-billable Gateway work without durable reviewed evidence violates this design.
