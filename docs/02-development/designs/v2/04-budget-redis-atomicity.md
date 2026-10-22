# AIC-087 — Budget Reservation, Commitment Binding & Redis Atomicity

> Status: **FROZEN CANDIDATE**  
> Depends on AIC-084 ownership, AIC-085 Pricing/Credential context and AIC-086 request/idempotency state.

## 1. Core decision

Budget Reservation correctness is MySQL-authoritative.

Redis is not allowed to independently authorize spend because V1 durable Budget Actual and Commitment state are already mutated in MySQL.

Request-time financial control uses:

```text
Realtime Available
= Total
- Actual
- Outstanding Commitments
- Effective Active Reservations
```

All terms used for an authorization decision are observed inside a MySQL transaction while the selected Budget row is locked.

---

## 2. Why Redis-only Reservation is rejected

A Redis Lua script can atomically compare Redis keys with other Redis keys.

It cannot atomically compare against concurrent V1 MySQL transactions that perform:

```text
budget.actual_amount += Ledger entry amount
commitment consumption
BillingPeriod financial fencing
```

A design where MySQL holds Actual/Commitments while Redis alone holds authoritative reservations creates a cross-store race and hidden dual-write correctness dependency.

Therefore:

```text
Redis-only reservation authority = NOT ALLOWED
```

---

## 3. Durable reservation entity

Logical table:

```text
budget_reservation
```

Minimum fields:

```text
id
org_id
request_id
billing_period_id
budget_id
financial_scope_type
financial_scope_id
currency
reserved_amount
commitment_id NULL
commitment_backed_amount
status
version
expires_at
created_at
updated_at
released_at NULL
finalized_at NULL
```

Constraints:

```text
reserved_amount > 0
commitment_backed_amount >= 0
commitment_backed_amount <= reserved_amount
currency = selected Budget currency
UNIQUE(org_id, request_id)
same-org FK boundaries
```

Money uses `DECIMAL(20,8)` / `BigDecimal`.

---

## 4. Reservation states

Frozen durable states:

```text
ACTIVE
PENDING_HOLD
RELEASED
FINALIZED
```

### ACTIVE

Request is allowed to consume the reservation before/while Provider work is in progress.

It reduces request-time availability unless an already-SETTLED financial result makes the reservation economically replaced by Actual.

### PENDING_HOLD

Reservation TTL elapsed or recovery encountered uncertainty after possible billable execution.

It remains financially conservative and continues to hold availability until Settlement/reconciliation makes release/finalization safe.

### RELEASED

Terminal no-cost/safely-unused reservation. It no longer affects availability.

### FINALIZED

Cleanup state indicating a durable Settlement has replaced the hold with Actual/Commitment effects.

`FINALIZED` is convenient lifecycle metadata, but correctness must not require the cleanup update to happen in the same transaction as Settlement because Gateway owns the reservation row while Backend owns final Settlement.

---

## 5. Effective active reservation calculation

To preserve single-writer ownership while avoiding double-counting after Settlement, request-time availability treats a reservation as effective active only when:

```text
reservation.status IN (ACTIVE, PENDING_HOLD)
AND
there is no SETTLED gateway_settlement for the same request/settlement identity
```

Why:

```text
before Settlement
→ reservation holds capacity

after Backend Settlement transaction commits
→ Budget.actual_amount already includes the cost
→ settled reservation must stop reducing availability even if Gateway cleanup has not yet changed ACTIVE -> FINALIZED
```

This derived rule makes Redis/Gateway cleanup latency irrelevant to financial correctness.

Gateway may later mark the row `FINALIZED` after observing durable Settlement.

---

## 6. Budget selection

Reuse the current V1 deterministic rule:

```text
exact financial scope + BillingPeriod + currency
→ ORG Budget + same BillingPeriod + currency
→ no Budget
```

Financial scope is the exactly-one target from AIC-086.

Do not select Budget using optional Project/Team/Cost Center fields by heuristic.

---

## 7. Budget enforcement mode

Each Gateway Credential resolves a budget enforcement mode:

```text
REQUIRED
OPTIONAL
```

This is a governed Control Plane property; it is not a client-supplied request switch.

### REQUIRED

If no eligible same-currency Budget exists or safe reservation cannot be established:

```text
reject before DISPATCH_INTENT
```

### OPTIONAL

If no eligible Budget exists:

```text
request may proceed unbudgeted
budget_reservation_id = NULL
any incurred cost still settles/posts in source pricing currency
```

If a Budget does exist, OPTIONAL still reserves against it; OPTIONAL is not a way to bypass an existing exhausted Budget. AIC-092 may refine this policy only if product review proves a different semantics is required.

---

## 8. No FX

Budget-controlled request requires:

```text
Pricing Version currency
= Reservation currency
= selected Budget currency
```

No automatic conversion exists.

A different-currency Budget is treated as no matching Budget.

Already-incurred cost is never converted by an undocumented rate merely to make Budget math fit.

---

## 9. Reservation upper bound

A reservation is a defensible cost upper bound under the supported Gateway request subset, not an average-cost guess.

Let normalized Pricing Version dimensions be exact BigDecimal rates.

Conceptually:

```text
reserved_amount
= upper_bound(input dimensions)
+ upper_bound(output dimensions)
+ upper_bound(request/fixed dimensions)
+ other explicitly supported bounded dimensions
```

### 9.1 Input bound

A Provider/Model adapter must define one supported reservation estimator strategy for the M11 request surface.

Preferred order:

```text
1. trusted deterministic tokenizer/count for the exact model/provider
2. documented conservative upper bound derived from accepted request representation
3. reject under REQUIRED budget policy if no safe bound exists
```

Do not use a guessed average characters-per-token ratio as a financial safety bound.

### 9.2 Output bound

Gateway must have an enforceable output ceiling before dispatch.

For the selected API surface, either:

```text
client supplies supported max-output field within policy
or
Gateway applies a documented organization/credential/model hard maximum that is sent/enforced upstream
```

A request with no enforceable finite output bound cannot use strict financial reservation.

### 9.3 Unsupported dimensions

If Provider billing depends on a dimension that the Adapter/Pricing Version cannot safely bound:

```text
REQUIRED budget mode
→ fail closed before dispatch

OPTIONAL mode
→ may proceed only if explicitly allowed unbudgeted/uncapped by policy
→ still meter/settle actual cost later
```

---

## 10. Reservation overrun

Reservation is a control bound based on known rules. It is not permission to falsify actual cost.

If final calculated/provider-reconciled actual exceeds reservation:

```text
post full incurred actual
Budget may become overBudget
mark reservation overrun metric/audit/reconciliation signal
never cap Ledger amount to reservation
```

Potential causes include:

```text
Provider billing semantics drift
malformed/missing realtime usage
unexpected billable dimension
pricing discrepancy
Provider correction
```

AIC-089/AIC-088 classify the exact recovery path.

---

## 11. Reserve transaction — no Commitment binding

Required lock order is compatible with V1 financial ordering:

```text
BillingPeriod
→ Budget(s) sorted
→ Commitment(s) sorted when used
→ reservation row/business uniqueness
```

For ordinary reservation:

```text
1. resolve request financial effective period/context
2. lock OPEN BillingPeriod when policy requires a current financial period
3. resolve exact/ORG Budget by financial scope + currency
4. lock selected Budget row FOR UPDATE
5. read durable Budget Total/Actual
6. calculate Outstanding Commitments
7. calculate Effective Active Reservations under the same locked Budget
8. Realtime Available = Total - Actual - Outstanding Commitments - Effective Reservations
9. if reserved_amount > Realtime Available -> REJECTED_BUDGET
10. insert/replay budget_reservation under UNIQUE(org, request)
11. commit
```

The selected Budget lock serializes this calculation with V1 Ledger Actual updates using the same Budget.

---

## 12. Explicit Commitment binding

Gateway must never infer that a request should consume an existing Commitment.

A reservation has either:

```text
commitment_id = NULL
```

or an explicitly governed Commitment binding resolved before reservation. The raw generic client cannot choose an arbitrary Commitment id unless AIC-092 exposes a separately authorized extension.

### 12.1 Bound Commitment validation

The Commitment must:

```text
belong to selected Budget
be same-org
be in a consumable status
match the Budget currency/scope semantics
```

Lock it after the Budget using the existing V1 lock order.

### 12.2 Prevent double reservation of the same Commitment

Calculate:

```text
commitment_reservable
= current remaining commitment amount
- effective active commitment-backed reservations
```

V2 Core rule:

```text
reserved_amount <= commitment_reservable
```

when a Commitment is explicitly bound.

A bound reservation is fully backed by the Commitment:

```text
commitment_backed_amount = reserved_amount
budget incremental reservation impact = 0
```

because the outstanding Commitment already reduces Budget availability.

This avoids temporary double-subtraction.

If the request upper bound cannot fit in the remaining unreserved Commitment, the binding is rejected; the governed caller may use a new request without that Commitment binding if policy permits.

### 12.3 Settlement consume

At Settlement, reuse existing V1 semantics:

```text
consumed = min(actual Ledger entry amount, remaining commitment amount)
```

If actual exceeds the reserved/remaining Commitment due an overrun, the full actual still posts; the excess reduces Budget availability through Actual and is flagged as overrun.

---

## 13. Reservation replay/idempotency

Reservation uniqueness:

```text
UNIQUE(org_id, request_id)
```

Same idempotent Gateway request:

```text
same reservation identity
same Budget/financial scope/currency/pricing context
no duplicate hold
```

If the request immutable pricing/financial context does not match the existing reservation, fail with state conflict rather than mutate the reservation to a different financial meaning after the fact.

---

## 14. Fencing/version

Every mutable reservation transition uses optimistic/fencing version:

```text
version BIGINT
```

Update pattern conceptually:

```text
UPDATE ...
SET status=?, version=version+1
WHERE id=? AND version=? AND status IN (...allowed predecessors...)
```

A stale recovery process cannot release or finalize a reservation after ownership/state changed.

MySQL row locks plus version checks provide deterministic convergence.

---

## 15. TTL is a recovery trigger, not proof of no cost

`expires_at` means:

> This reservation requires recovery attention after this time if it has not been terminally accounted for.

It does not mean:

```text
TTL expired => release money
```

Recovery decision uses durable request/usage/Settlement state.

### 15.1 Safe expiry

If request never crossed `DISPATCH_INTENT` and no possible billable usage exists:

```text
ACTIVE -> RELEASED
```

### 15.2 Possible-billable expiry

If request reached/passed `DISPATCH_INTENT` and no safe no-cost evidence exists:

```text
ACTIVE -> PENDING_HOLD
```

The hold remains effective until FINAL Settlement, Provider/reconciliation evidence, or reviewed safe release.

### 15.3 Already settled

If durable `gateway_settlement.status = SETTLED`:

```text
reservation is economically finalized immediately by derived availability rule
Gateway cleanup may mark ACTIVE/PENDING_HOLD -> FINALIZED
```

---

## 16. Redis role

Redis remains runtime infrastructure, not financial authorization truth.

Allowed V2 Redis domains:

```text
rate limit token bucket
quota counters/windows
short idempotency lookup cache
credential/catalog short cache if later justified
provider health/circuit state
reservation expiry wake-up hints
request ephemeral coordination
```

Redis data loss must be recoverable from durable sources or acceptable as loss of runtime convenience.

---

## 17. Rate limit atomic contract

Initial Gateway runtime limiter uses an atomic token-bucket style contract per governed key.

Example logical key namespace:

```text
aicostops:v2:gateway:ratelimit:{credentialId}
```

Do not embed raw API keys in Redis keys.

Lua/script inputs are bounded numeric configuration:

```text
capacity
refill_per_second
now_millis
cost = 1 request unit
```

Output:

```text
ALLOWED + remaining
or
REJECTED + retry_after_millis
```

The exact script is implemented/tested in M11/M12; M10 freezes the atomic behavior, not production Lua source.

### Redis failure

If an enabled mandatory rate-limit policy cannot be evaluated:

```text
fail closed with dependency-unavailable/retryable result
```

Do not silently fail open.

---

## 18. Quota semantics

Redis quota in V2 Core is non-financial operational quota such as:

```text
request count per hour/day
concurrent active stream limit
```

Financial spend limits belong to MySQL Budget/Reservation, not a Redis monetary counter.

A quota key includes only bounded opaque ids and time-window version.

If exact monthly financial quota is needed, model it as Budget, not Redis.

---

## 19. Short idempotency cache

Redis may cache:

```text
credential + idempotency digest -> gateway request id
```

for fast duplicate lookup.

Authority remains MySQL `gateway_request` unique constraint.

Redis loss:

```text
cache miss
→ query MySQL
→ never create duplicate solely because Redis forgot the key
```

---

## 20. Reservation expiry wake-up hint

Redis may hold a sorted-set/timer hint for reservation expiry to reduce DB scanning latency.

Correctness remains:

```text
periodic DB recovery scan
+ durable expires_at
```

If Redis loses the hint, the DB scan still recovers the reservation.

No custom distributed scheduler is required.

---

## 21. Concurrency test requirements

AIC-092 implementation tests must prove on real MySQL:

```text
same Budget, many concurrent reservations
→ serialized by Budget lock
→ no race overspend

V1 Ledger posting vs V2 reservation on same Budget
→ both lock same Budget
→ reservation sees either pre-post or post-post Actual deterministically
→ no phantom availability

same request concurrent reserve
→ one reservation

same Commitment concurrent Gateway reservations
→ total commitment-backed reservations never exceed remaining Commitment

Settlement commit vs new reservation
→ new reservation sees updated Actual
→ settled old reservation not double-counted

stale reservation recovery version
→ cannot release newer owner/state
```

---

## 22. Failure test requirements

```text
Redis down
→ cannot fabricate budget authorization
→ mandatory Redis rate/quota fails closed

MySQL down before reserve
→ no DISPATCH_INTENT
→ no Provider call

Gateway crash after ACTIVE reserve before DISPATCH_INTENT
→ recovery safely RELEASED

Gateway crash after DISPATCH_INTENT
→ expiry becomes PENDING_HOLD, not released blindly

Backend Settlement committed, Gateway cleanup down
→ Actual is truth
→ derived effective-reservation query avoids double count
→ later cleanup FINALIZED
```

---

## 23. Metrics

Bounded metrics include:

```text
reservation_attempt_total{outcome}
reservation_active
reservation_pending_hold
reservation_overrun_total
reservation_recovery_total{outcome}
rate_limit_total{outcome}
quota_total{outcome}
redis_dependency_error_total{operation_class}
```

Do not label metrics with credential id, budget id, org id or request id.

---

## 24. AIC-087 Definition of Done

```text
[freeze] MySQL is reservation correctness authority
[freeze] Budget row lock serializes reservation with V1 Actual mutation
[freeze] effective reservations exclude durably SETTLED requests to avoid double count
[freeze] Redis is runtime coordination/cache only
[freeze] exact/ORG Budget fallback matches V1
[freeze] REQUIRED vs OPTIONAL budget enforcement is explicit
[freeze] no FX
[freeze] reservation is a safe upper bound under supported request surface
[freeze] actual over reservation still posts in full
[freeze] Commitment binding is explicit and cannot double-reserve remaining Commitment
[freeze] TTL triggers recovery; possible-billable TTL becomes PENDING_HOLD
[freeze] version/fencing prevents stale release/finalize
[freeze] Redis loss cannot authorize spend
```

If M12 benchmarks later show Budget-row contention, optimization requires new evidence and must preserve the same financial correctness proof.
