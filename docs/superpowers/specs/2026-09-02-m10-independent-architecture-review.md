# AI-CostOps M10 — Independent Architecture Review Decisions

- Date: 2026-09-02
- Review target: `docs/superpowers/specs/2026-09-02-m10-v2-detailed-design-program.md`
- Repository baseline: `main@a144210c7110aa2b924b5ef5393686ba329537bd`
- Design branch: `docs/m10-v2-detailed-design`
- Status: **NORMATIVE M10 REVIEW INPUT — MUST BE FOLDED INTO FINAL DETAILED DESIGN BEFORE AIC-093 FREEZE**

This document records independent architecture review findings against the real V1 implementation, not only against earlier V2 planning documents. It does not authorize Gateway feature coding. If a later M10 detailed-design document conflicts with this review, the conflict must be resolved explicitly before M10 can be frozen.

---

## 1. Review evidence

The review checked the current `main` implementation and confirmed these V1 facts:

```text
ProviderChargePostingService
- locks BillingPeriod
- resolves exact-scope Budget with ORG fallback
- locks Budget and Commitment rows
- inserts immutable Ledger posting/entries
- increments Budget.actual_amount
- consumes explicit Commitment links
- writes Audit
- performs the financial mutations inside one MySQL transaction

LedgerSourceType
= PROVIDER_CHARGE | EXPENSE_CLAIM | CORRECTION

ledger_posting
- source_type/source_id
- allocation_decision_id optional
- posted_by_member_id NOT NULL

ledger_entry
- exactly one financial target: PROJECT | TEAM | COST_CENTER
- source_charge_fact_id / source_expense_claim_id lineage
- allocation_line_id optional

charge_fact
- requires raw_record_id
- therefore cannot be reused as a fake realtime Gateway source without manufacturing Import/Evidence lineage

CloseBlockerProvider
- already exists as the V1 extension seam for BillingPeriod close blockers
```

These implementation facts constrain V2 design.

---

# 2. Decision R1 — Realtime Settlement gets first-class Ledger lineage

## Problem

The current Ledger supports only Provider statement charge, Expense Claim, and Correction sources. A realtime Gateway Settlement is not a `charge_fact`: `charge_fact` is intentionally tied to `raw_provider_record` and the Provider Import evidence chain.

Creating synthetic `raw_provider_record`/`charge_fact` rows merely to reuse `ProviderChargePostingService` would corrupt the meaning of the V1 evidence lineage.

## Frozen direction

V2 must add a first-class financial source:

```text
LedgerSourceType.GATEWAY_SETTLEMENT
```

The M13 forward migration must provide explicit same-org lineage from Ledger to `gateway_settlement` rather than pretending a realtime request is a Provider statement import.

Minimum schema direction:

```text
ledger_posting.source_type += GATEWAY_SETTLEMENT
ledger_posting.source_id   = gateway_settlement.id

ledger_entry.source_gateway_settlement_id NULL
  -> same-org FK to gateway_settlement

source lineage constraint
  -> at most one of charge_fact / expense_claim / gateway_settlement
     for normal source lineage
```

Correction rows continue to use the existing Correction lineage rules.

## Implementation boundary

Do not call the existing `ProviderChargePostingService` with synthetic objects. M13 should add a narrow Gateway Settlement posting orchestration inside the existing CostOps financial/Ledger module and reuse the proven V1 seams and invariants:

```text
BillingPeriodFinancialWriteFence
LedgerBudgetPort
CommitmentConsumeService
LedgerPostingMapper / equivalent narrow repository
Ledger Audit
fixed financial lock ordering
stable posting key + DB uniqueness
```

The business posting key must be derived from the immutable settlement identity, for example:

```text
GATEWAY_SETTLEMENT:{settlementId}
```

The exact string is frozen in AIC-089/AIC-092, but one settlement must converge to one posting under replay and concurrency.

---

# 3. Decision R2 — Automated financial posting must not impersonate a human member

## Problem

`ledger_posting.posted_by_member_id` is currently `NOT NULL`, because all V1 postings are human-triggered. Realtime settlement is performed by a system worker and can also represent a service identity request.

Using the Gateway credential owner or an arbitrary finance member as `posted_by_member_id` would create false audit history.

## Frozen direction

M10 must introduce explicit posting actor semantics for V2.

Preferred forward-compatible direction:

```text
ledger_posting.posting_actor_type = MEMBER | SYSTEM
posted_by_member_id nullable for SYSTEM

existing V1 rows => MEMBER + existing posted_by_member_id
Gateway Settlement => SYSTEM + posted_by_member_id NULL
```

A schema CHECK must keep the two forms consistent.

`audit_event.actor_user_id` is already nullable and can represent system events without inventing a fake user. Gateway/Settlement audit metadata must still carry safe identifiers such as request/credential/settlement ids, never secrets or prompt content.

Do not create a fake `organization_member` solely to satisfy the old Ledger column.

---

# 4. Decision R3 — MySQL is authoritative for Budget Reservation correctness

## Problem

The V1 Budget durable terms are in MySQL:

```text
Total
Actual
Outstanding Commitments
```

V1 Ledger posting can change `actual_amount` and commitment consumption inside MySQL transactions. If V2 stores `Active Reservations` only in Redis, a Redis Lua script cannot atomically observe concurrent MySQL Actual/Commitment changes.

Therefore the following promise cannot be proven with Redis-only reservations:

```text
Realtime Available
= Total - Actual - Outstanding Commitments - Active Reservations

and

no overspend caused by reservation race
```

Cross-store dual-write would make correctness worse and would implicitly turn Redis into part of financial correctness.

## Frozen direction

V2 reservation correctness is **MySQL-authoritative**, while Redis remains runtime acceleration/coordination.

Introduce a durable short-lived control record such as:

```text
budget_reservation
- id
- org_id
- request_id
- budget_id
- amount
- currency
- status
- fencing_version
- expires_at
- created_at
- finalized_at/released_at
```

The exact model is frozen in AIC-087/AIC-092.

Reserve transaction:

```text
1. resolve and lock BillingPeriod if required by policy
2. resolve exact-scope Budget with the existing ORG fallback semantics
3. lock selected Budget row
4. read durable Actual + Outstanding Commitments
5. sum durable ACTIVE/PENDING-HOLD reservations for the locked Budget
6. calculate Realtime Available
7. insert/replay the reservation under a request/business uniqueness constraint
8. commit
9. only then may the request proceed toward Provider dispatch
```

All reservation acquire/release/finalize paths for a Budget use the same Budget-row lock ordering, so they serialize with V1 Ledger `actual_amount` updates.

This keeps the core safety proof inside one MySQL transaction domain.

## Redis role after this decision

Redis remains valid for:

```text
rate limit
quota windows
short credential cache
short idempotency coordination
provider health/circuit state
reservation expiry wake-up hint / cache
request ephemeral coordination
```

Redis may cache reservation information, but a Redis value must never be the sole reason a request is considered budget-authorized.

`Redis unavailable` therefore cannot fabricate budget availability. Rate-limit/quota policy may still fail closed independently.

## Evidence gate

Do not replace this with Redis-authoritative reservation later merely for throughput. First benchmark MySQL reservation contention. Only a measured bottleneck may justify a new optimization design.

---

# 5. Decision R4 — Durable dispatch intent is required before billable upstream I/O

## Problem

There is no distributed transaction between AI-CostOps MySQL and an external Provider. MySQL can fail after the Provider may have accepted a request.

If the only durable marker is written after the Provider call, a crash/outage can leave no local evidence that billable execution may have happened.

## Frozen direction

Before the first potentially billable upstream byte is intentionally sent, Gateway must commit a durable request state that means:

```text
UPSTREAM_DISPATCH_INTENT
= this request is now financially unsafe to blindly replay
```

Representative lifecycle:

```text
RECEIVED
→ AUTHENTICATED
→ ATTRIBUTED
→ RESERVED
→ UPSTREAM_DISPATCH_INTENT   [durable commit]
→ upstream network I/O
→ UPSTREAM_ACCEPTED / STREAMING / RESPONSE_RECEIVED
→ usage fact
```

The exact names may be normalized in AIC-086, but the semantic fence is mandatory.

Consequences:

1. Crash between durable intent and actual send creates a conservative orphan, not a silent cost loss.
2. Crash/DB outage after Provider acceptance leaves an existing durable request that recovery can classify as possible-billable.
3. A client retry with the same idempotency identity must not start another Provider request once the original request reached the durable dispatch fence.
4. Orphans with no reliable usage become `METERING_UNKNOWN` / reconciliation-required rather than zero-cost.

Provider statement reconciliation remains the final recovery path when exact realtime usage cannot be reconstructed.

---

# 6. Decision R5 — Request ownership and financial allocation scope are different concepts

## Problem

V2 request identity includes a required Project plus optional Team/Cost Center context. The V1 Ledger, however, requires exactly one financial target per Ledger entry:

```text
PROJECT | TEAM | COST_CENTER
```

Persisting all three as if they were simultaneous financial targets would violate V1 Ledger semantics.

## Frozen direction

M10 must model these separately:

```text
request ownership/context:
- organization_id
- project_id               required
- optional team_id
- optional cost_center_id

financial allocation target:
- financial_scope_type     exactly one of PROJECT | TEAM | COST_CENTER
- financial_scope_id       exactly one matching id
```

Default V2 Core behavior:

```text
financial scope defaults to the request Project
```

A Gateway Credential or explicit governed configuration may select Team/Cost Center as the financial scope only when the referenced target belongs to the same organization and is allowed by the credential policy.

Budget selection and final Ledger target use `financial_scope_type/id`, not a guess from whichever optional identifier happens to be present.

This preserves the existing deterministic Budget selection rule:

```text
exact financial scope + currency
→ ORG fallback + same currency
→ no matching budget
```

---

# 7. Decision R6 — No implicit FX in realtime reservation or settlement

V1 has no Automatic FX Engine. V2 must not silently invent one.

Frozen rules:

```text
Pricing Version currency
Reservation currency
Selected Budget currency
Final realtime calculated cost currency
```

must match for a budget-controlled request.

If no same-currency Budget exists:

- strict budget-required policy: reject before dispatch;
- explicitly unbudgeted-allowed policy: request may proceed without a Budget reservation, but any incurred cost still posts in its source currency.

No currency conversion is performed unless a future separately designed FX source/version exists.

Provider statement reconciliation may later explain invoice-currency differences; it must not rewrite the original realtime amount by an undocumented FX rate.

---

# 8. Decision R7 — Reservation amount must be a defensible upper bound, not a guessed average

## Problem

A realtime request must reserve before the final output token count is known. A low estimate can authorize spending that exceeds the reservation even with perfect concurrency control.

## Frozen direction

AIC-087/AIC-088 must define a deterministic reservation estimate from the exact Pricing Version and enforced request limits.

The design must cover:

```text
input usage estimate/count
max output token limit
cached-input assumptions
provider/model pricing dimensions
request count pricing
provider fixed/request fees if any
unsupported or unknown dimensions
```

For models/providers where a safe upper bound cannot be calculated, strict budget control must fail closed or apply a configured hard per-request cost cap that the Gateway can actually enforce upstream.

The final actual cost may still exceed reservation because of Provider billing behavior, pricing drift, malformed/missing usage, or external correction. In that case:

```text
full incurred cost still posts
reservation is finalized/released correctly
budget may become overBudget
an explicit reservation_overrun / reconciliation signal is emitted
```

Never truncate or cap the Ledger amount to the reserved amount.

---

# 9. Decision R8 — Billing idempotency is mandatory; response replay is a separate feature

## Problem

The project requires client retry to avoid duplicate settlement. At the same time, Prompt/Completion bodies are not persisted by default. Therefore the system cannot promise arbitrary completed-response replay without creating a new content-retention product.

## Frozen direction

M10 separates:

```text
financial/request idempotency
!=
response body replay
```

For a supplied Gateway idempotency key:

1. Persist a stable request/business identity before dispatch.
2. Bind the key to a privacy-safe request fingerprint. Use a keyed/HMAC fingerprint rather than storing prompt text or a plain low-entropy body hash.
3. Same key + different fingerprint => deterministic conflict.
4. Same key + request before dispatch => return/reuse the existing request state without duplicate reservation.
5. Same key + request at/after durable dispatch intent => never send a second upstream request merely because the client did not receive the first response.
6. If the original response body is no longer available because default policy does not retain it, the replay response is an explicit idempotency/recovery result referencing the original request identity; this is financially idempotent even if byte-for-byte response replay is unavailable.

Any future encrypted short response cache is a separate privacy/retention decision, not required for financial idempotency.

---

# 10. Decision R9 — Unresolved Gateway usage must block BillingPeriod close

## Problem

A Provider request can be billable while settlement is still pending or usage is incomplete. Closing that BillingPeriod first would force an avoidable late-posting exception.

## Frozen direction

M13 must extend the existing V1 `CloseBlockerProvider` seam with a Gateway financial blocker.

The blocker must detect, for the candidate period, at least:

```text
possible-billable Gateway requests without terminal usage classification
FINAL usage facts without terminal Settlement
RETRYABLE_FAILED Settlement still eligible for retry
active/pending-hold reservations tied to possible-billable requests
```

Normal close is blocked until these are settled, safely released, or explicitly moved into a reviewed reconciliation exception allowed by the close policy.

The worker may still encounter a CLOSED period because of historical data, manual recovery, or races; such cases remain explicit `RECONCILIATION_REQUIRED`. The close blocker reduces avoidable cases but does not erase the fallback rule.

---

# 11. Decision R10 — Financial effective time must be explicit

Gateway requests can cross a calendar/BillingPeriod boundary while streaming. M10 must not select a BillingPeriod using `created_at` by accident.

AIC-088/AIC-089 must freeze an immutable normalized field such as:

```text
usage_effective_at
usage_effective_at_source
```

The Provider Adapter may supply an authoritative Provider billing/request timestamp when the Provider contract exposes one. Otherwise the safe fallback is a documented Gateway timestamp captured at the upstream dispatch boundary.

The same effective time is used consistently for:

```text
Pricing Version selection
BillingPeriod selection
reconciliation matching
```

Provider-specific statement semantics may later generate reconciliation differences; they must not silently rewrite the original settled timestamp.

---

# 12. Decision R11 — Gateway DB access must not block the Reactor Netty event loop

The current repository uses synchronous MyBatis/JDBC and has proven financial locking semantics. M10 should not introduce R2DBC merely to appear reactive.

Initial V2 direction:

```text
Gateway HTTP/streaming = Spring WebFlux + Reactor Netty
Gateway MySQL contract = narrow synchronous JDBC/MyBatis transaction seams
Blocking DB work        = explicitly offloaded from Netty event-loop threads
Concurrency             = bounded by DB pool + bounded scheduler
```

No blocking MySQL/Object-storage call may execute on the Reactor Netty event loop.

R2DBC is not a V2 Core requirement. If M16 load evidence proves the bounded blocking DB seam is insufficient, a later evidence-based redesign may evaluate it.

Current external verification also confirms Spring Boot 4.1.x continues to support the WebFlux + Reactor Netty direction. The repository is pinned to Spring Boot 4.1.0; M10 does not silently upgrade runtime dependencies. A patch upgrade is a separate implementation decision with full regression evidence.

---

# 13. Decision R12 — Schema migration has one owner; runtime DB privileges enforce boundaries

With two deployables sharing one MySQL system of record, both applications must not race to own schema evolution.

Frozen direction:

```text
Control Plane/backend deployment owns Flyway migration execution.
Gateway does not independently run competing Flyway migrations in production.
```

Gateway production DB credentials should be least-privilege and limited to its approved contract, including only the tables/operations needed for:

```text
request/usage facts
budget reservation control
required catalog/pricing reads
```

Gateway credentials must not have direct write permission to:

```text
ledger_posting
ledger_entry
budget.actual_amount
budget commitment consumption
period close state
final settlement state
```

The Backend/CostOps worker owns those financial mutations.

---

# 14. Decision R13 — Commitment consumption is explicit, never inferred

Outstanding Commitments are already part of Budget availability. A Gateway Settlement must not guess that an incurred request should consume a Commitment.

M10 must freeze one of two explicit cases per reservation:

```text
no commitment binding
or
explicit commitment_id binding validated at reservation time
```

If bound, the Commitment must belong to the selected Budget, be consumable, and match the governed scope/currency rules. Final settlement reuses the existing V1 semantics:

```text
consumed = min(actual entry amount, remaining commitment amount)
```

If not bound, the Gateway Settlement updates Actual without reducing an unrelated Commitment. This can be conservative for available budget, but it is truthful and avoids silently consuming the wrong obligation.

---

# 15. Decision R14 — Hybrid reconciliation keys must be designed in M10, not postponed to M15

M15 implements reconciliation logic, but M10 must ensure M11-M13 persist enough immutable evidence to make it possible.

At minimum, retain safe bounded matching data when available:

```text
provider account identity
provider request id
provider/model id
request/usage effective timestamp
normalized usage dimensions
pricing version
settled amount/currency
Gateway request/usage/settlement ids
```

Prompt/Completion content is not required for financial reconciliation and remains excluded by default.

Provider statement import must later match against these facts before deciding whether a statement charge is:

```text
already represented by realtime Settlement
pricing/discount/rounding difference
late correction
missing Gateway usage
unknown external charge
```

This prevents M15 from discovering that the required correlation data was discarded in M11.

---

# 16. API compatibility review note

As of this review date, OpenAI continues to support Chat Completions, while recommending the Responses API for new OpenAI-native integrations. AI-CostOps has an additional requirement: multi-Provider portability.

Therefore M10 must not treat “OpenAI-compatible” as a vague synonym for “whatever OpenAI currently recommends”. AIC-092 must explicitly freeze the M11 compatibility surface after checking the target Provider matrix.

M11 must implement a deliberately bounded subset, with unsupported fields rejected explicitly rather than silently dropped. Supporting both Chat Completions and Responses in M11 is not required unless the Provider/client compatibility matrix proves both are necessary for the MVP.

---

# 17. Required changes to AIC-084 ~ AIC-093

The final M10 detailed design must incorporate these review decisions as follows:

```text
AIC-084
- DB ownership
- Flyway ownership
- DB permission boundary
- blocking DB offload rule

AIC-085
- financial scope binding policy
- no-plaintext Provider secret
- service/human principal semantics

AIC-086
- durable dispatch-intent fence
- billing idempotency vs response replay
- request ownership vs financial scope

AIC-087
- MySQL-authoritative budget_reservation
- Redis limited to recoverable runtime roles
- reservation upper-bound estimation
- explicit commitment binding
- same-currency/no-FX rule

AIC-088
- usage_effective_at
- usage fact revision/correction lineage
- incomplete/unknown metering
- provider reconciliation keys

AIC-089
- GATEWAY_SETTLEMENT Ledger source
- automated SYSTEM posting actor
- same-transaction final financial mutation
- Gateway close blocker
- settlement uniqueness/retry

AIC-090
- dispatch fence is the retry/failover safety boundary

AIC-091
- least-privilege DB users
- migration owner
- system audit actor
- idempotency fingerprint privacy

AIC-092
- exact forward schema changes
- exact Gateway API compatibility subset
- concurrency/failure tests for all decisions above

AIC-093
- cannot freeze if any item above is deferred to “implementation decides”
```

---

# 18. Review verdict

The overall V2 direction remains sound:

```text
Control Plane + Data Plane
same MySQL financial system of record
Gateway durable request/usage facts
Backend durable financial Settlement
no premature MQ
Redis not financial truth
immutable Ledger
append-only correction
```

However, the fourteen decisions in this review are correctness-critical refinements. They must be incorporated into the final detailed design before `M10 = FROZEN` and before broad Gateway coding starts.
