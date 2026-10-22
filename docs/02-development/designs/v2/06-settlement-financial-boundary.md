# AIC-089 — Settlement, Ledger Integration & Period-Close Boundary

> Status: **FROZEN CANDIDATE**  
> Depends on AIC-087 reservation and AIC-088 FINAL usage facts.

## 1. Purpose

Realtime Gateway observations become financial truth only through CostOps Core Settlement.

```text
Gateway Request
→ current FINAL Usage Fact
→ Gateway Settlement
→ immutable Ledger Posting
→ Budget Actual / optional Commitment consumption
```

Gateway never directly writes the final Ledger/Actual/Commitment result.

---

## 2. Settlement ownership

`gateway_settlement` is written only by Backend/CostOps Core.

The Gateway may read Settlement status for cleanup/status projection but does not mutate it.

The settlement worker is DB-backed. Correctness does not require RabbitMQ/Kafka.

Discovery source:

```text
current FINAL gateway_usage_fact
with no terminal Settlement for the same Gateway Request
```

---

## 3. One request, one realtime Settlement

Normal V2 rule:

```text
one Gateway Request
→ at most one realtime FINAL Usage Fact that seals the realtime usage chain
→ at most one gateway_settlement
→ at most one Ledger Posting
```

An INCOMPLETE/UNKNOWN fact may later be superseded by one FINAL realtime fact.

Once a FINAL fact has been accepted as Settlement input, the Gateway does not append a second competing FINAL fact. Later Provider statement corrections belong to Hybrid Reconciliation/Correction, not a rewritten realtime Settlement.

Database uniqueness must enforce:

```text
UNIQUE(org_id, request_id)
UNIQUE(org_id, usage_fact_id)
```

on Settlement or equivalent business constraints.

---

## 4. Settlement logical model

Minimum fields:

```text
id
org_id
settlement_key
request_id
usage_fact_id
reservation_id NULL
billing_period_id
financial_scope_type
financial_scope_id
provider_account_id
provider_model_id
pricing_version_id
currency
calculated_amount_raw
posted_amount
rounding_delta
status
attempt_count
next_attempt_at NULL
last_error_code NULL
created_at
settled_at NULL
reconciliation_required_at NULL
ledger_posting_id NULL
```

`calculated_amount_raw` uses a wider exact decimal than Ledger money so per-token arithmetic does not disappear before accounting quantization. Exact SQL precision is frozen in AIC-092.

`posted_amount` uses the existing Ledger/Budget money quantum:

```text
DECIMAL(...,8)
```

---

## 5. Settlement states

Frozen durable states:

```text
PENDING
RETRYABLE_FAILED
RECONCILIATION_REQUIRED
SETTLED
```

No persistent `PROCESSING` truth is required.

Worker ownership is obtained by MySQL row lock / `FOR UPDATE SKIP LOCKED` style claiming inside short transactions. A worker crash releases the DB lock automatically instead of leaving an ambiguous PROCESSING state.

### PENDING

Ready for deterministic financial processing.

### RETRYABLE_FAILED

Last processing attempt failed for a transient/retryable dependency/concurrency reason. The Settlement remains financially unresolved.

### RECONCILIATION_REQUIRED

Automatic realtime posting cannot safely complete because of a semantic/external-truth condition such as:

```text
BillingPeriod already CLOSED in a historical/race case
Pricing/usage incompatibility that cannot be deterministically calculated
amount outside supported accounting representation/policy
explicit external correction required
```

This is not zero cost.

### SETTLED

Terminal realtime financial result. Ledger posting and Budget/Commitment financial mutations committed atomically with this status.

---

## 6. DB-backed discovery and creation

A bounded worker loop discovers current FINAL usage facts.

Conceptual flow:

```text
1. select eligible FINAL usage facts with no Settlement
2. insert Settlement PENDING using business uniqueness
3. duplicate insert converges to existing Settlement
4. process PENDING/eligible RETRYABLE_FAILED rows in bounded batches
```

A wake-up notification may be added later, but DB discovery remains authoritative so a lost notification cannot lose money.

---

## 7. Settlement key

Use a stable immutable business key derived from Gateway Request identity, conceptually:

```text
GATEWAY_REQUEST:{publicRequestId}
```

Ledger posting key is derived from Settlement identity, conceptually:

```text
GATEWAY_SETTLEMENT:{settlementId}
```

AIC-092 freezes exact key length/format.

The keys are not secrets.

---

## 8. Pricing calculation

Settlement uses the exact Pricing Version frozen on the request/usage fact.

For each normalized usage dimension:

```text
dimension_raw_cost
= exact_quantity
  * exact_unit_price
  / exact_unit_quantity
```

Calculation uses `BigDecimal`; no float/double.

All required Pricing Version dimensions must be satisfied by the FINAL usage fact.

Unknown/unpriced required dimensions do not silently become zero.

---

## 9. Accounting quantum and explicit rounding

V1 Ledger/Budget money is scale 8 and currently rejects silent non-representable amounts. Per-token pricing can mathematically produce more than eight decimal places, so V2 must make quantization explicit rather than rely on accidental `setScale` behavior.

Settlement stores both:

```text
calculated_amount_raw
= high-precision exact calculation

posted_amount
= scale-8 accounting amount written to Ledger/Budget

rounding_delta
= posted_amount - calculated_amount_raw
```

### 9.1 Frozen quantization rule

For positive incurred cost:

```text
posted_amount
= calculated_amount_raw rounded UP (away from zero) to scale 8
```

For a negative Provider credit if realtime credits are later supported:

```text
posted_amount
= calculated_amount_raw rounded DOWN (away from zero) to scale 8
```

This conservative rule guarantees that a non-zero incurred cost never becomes `0.00000000` merely because the Ledger quantum is coarser than Provider token pricing.

`rounding_delta` is retained so Hybrid Reconciliation can later correct aggregate Provider invoice differences explicitly.

If a future V2 release changes the accounting quantum, that requires a separate forward financial migration and cannot be done implicitly by changing Java rounding.

### 9.2 Reservation quantization relationship

AIC-087 reservation must also round a positive upper-bound estimate UP to scale 8. Reservation therefore never under-reserves solely due to accounting quantization.

---

## 10. Financial effective time and BillingPeriod

Settlement uses `usage_effective_at` from AIC-088.

Before Provider dispatch, Gateway also resolves the BillingPeriod containing the dispatch-effective fallback timestamp and persists `billing_period_id` on the request/reservation context.

If the Provider later supplies an authoritative effective timestamp that maps to a different BillingPeriod, Settlement does not silently move history across periods. It marks the mismatch as an explicit reconciliation/financial-review condition unless the frozen AIC-092 policy proves a deterministic safe rule.

Default V2 Core rule:

```text
BillingPeriod financial fence for dispatch
= period containing GATEWAY_DISPATCH_INTENT_TIMESTAMP

normal Settlement period
= the same persisted billing_period_id
```

Provider billing timestamp remains a reconciliation key/evidence field. This avoids changing the period after a request was already authorized against a locked/open period.

---

## 11. Dispatch vs Period Close serialization

Every potentially billable Gateway request, including explicitly unbudgeted requests, must acquire the same BillingPeriod financial write fence before committing `DISPATCH_INTENT`.

Required sequence:

```text
lock OPEN BillingPeriod containing dispatch effective time
→ ensure request/reservation is financially eligible
→ commit request DISPATCH_INTENT + persisted billing_period_id
→ release DB lock
→ send Provider request
```

Period Close uses the same BillingPeriod locking/fence boundary.

Therefore the race converges:

### Dispatch wins period lock first

```text
DISPATCH_INTENT commits
→ Close subsequently scans Gateway blocker
→ unresolved possible-billable request blocks close
```

### Close wins period lock first

```text
period enters closing/closed path
→ Gateway cannot commit a new DISPATCH_INTENT for that period
→ request fails before Provider I/O
```

This prevents a request from becoming newly billable after Close performed its blocker decision for the same period.

---

## 12. First-class Ledger source

Current V1 Ledger source types are Provider statement charge, Expense Claim and Correction. Realtime Settlement gets its own source:

```text
GATEWAY_SETTLEMENT
```

Forward schema direction:

```text
ledger_posting.source_type
+= GATEWAY_SETTLEMENT

ledger_posting.source_id
= gateway_settlement.id

ledger_entry.source_gateway_settlement_id
= same-org FK to gateway_settlement
```

Do not manufacture `raw_provider_record`, `charge_fact` or `allocation_decision` rows for realtime traffic.

Gateway Settlement normal Ledger entry:

```text
allocation_decision_id = NULL
exactly one financial target from request financial_scope
source_gateway_settlement_id = settlement id
```

---

## 13. Ledger posting actor

Realtime Settlement is system work.

Forward Ledger actor semantics:

```text
posting_actor_type = MEMBER | SYSTEM
posted_by_member_id nullable
```

Consistency:

```text
MEMBER -> posted_by_member_id NOT NULL
SYSTEM -> posted_by_member_id NULL
```

Existing V1 rows migrate/backfill as MEMBER.

Gateway Settlement uses SYSTEM.

Do not create a fake finance member or reuse credential owner as the posting actor.

The originating principal/credential/request remains in Gateway Settlement lineage and audit metadata.

---

## 14. Financial target

One Gateway Settlement creates one primary Ledger entry for the request financial scope:

```text
PROJECT
or TEAM
or COST_CENTER
```

No Allocation Decision is required because the target was governed before dispatch and frozen on the Gateway request.

If a future feature needs split allocation of one realtime request across multiple targets, it is not V2 Core and requires a new allocation design.

---

## 15. Settlement financial transaction

For a normal PENDING Settlement, Backend executes one MySQL financial transaction with deterministic lock order compatible with V1:

```text
1. lock persisted BillingPeriod through BillingPeriodFinancialWriteFence
2. lock selected Budget if reservation/budget exists
3. lock explicitly bound Commitment if any
4. lock Settlement row
5. re-read immutable request/usage/pricing/reservation lineage
6. converge on existing Ledger posting by stable key if replay
7. calculate raw amount + explicit scale-8 posted amount
8. insert Ledger posting
9. insert one Ledger entry with Gateway Settlement lineage
10. increment Budget actual by full posted amount when Budget exists
11. consume explicitly bound Commitment with existing V1 semantics
12. write financial Audit event
13. set gateway_settlement SETTLED + ledger_posting_id + amounts
14. commit
```

Where current V1 lock ordering requires a different exact ordering around source rows, AIC-092 must choose one global order and concurrency tests must prove it. The final order cannot be left undocumented.

No external Provider or Redis operation runs inside this financial transaction.

---

## 16. Budget actual behavior

If a Budget exists:

```text
budget.actual_amount += posted_amount
```

Even when:

```text
posted_amount > reserved_amount
Budget becomes overBudget
Commitment is insufficient
```

Already-incurred cost is never rejected from Ledger because Budget is insufficient.

If the request was explicitly allowed unbudgeted and no Budget exists:

```text
Ledger still posts
Budget actual mutation = none
```

This preserves the V1 rule that missing/insufficient Budget does not erase incurred cost.

---

## 17. Reservation handoff without cross-writer mutation

Backend does not need to update Gateway-owned `budget_reservation` inside the Settlement transaction.

Correctness flow:

```text
Before Settlement:
ACTIVE/PENDING_HOLD reservation reduces Realtime Available

Settlement transaction commits:
Budget actual now includes posted_amount
Gateway Settlement status = SETTLED

Immediately after commit:
AIC-087 effective-reservation query excludes reservations whose request has SETTLED financial result

Later Gateway cleanup:
ACTIVE/PENDING_HOLD -> FINALIZED
```

Therefore Redis/Gateway cleanup failure cannot double-subtract budget or roll back financial truth.

---

## 18. Commitment consumption

Commitment is consumed only if reservation/request contains an explicit governed `commitment_id`.

Backend revalidates under lock:

```text
same selected Budget
consumable status
same org
```

Then reuse V1 rule:

```text
consumed
= min(posted Ledger entry amount, remaining Commitment amount)
```

If posted amount exceeds remaining Commitment:

```text
full posted amount still increments Actual
only remaining Commitment is consumed
excess is not discarded
```

No unrelated Commitment is inferred or consumed.

---

## 19. Audit atomicity

Financial settlement audit participates in the same MySQL transaction as Ledger/Actual/Settlement.

Representative event:

```text
GATEWAY_SETTLEMENT_POSTED
```

Safe metadata:

```text
request id
settlement id
usage fact id
provider code/account id
logical/provider model ids
pricing version id
financial scope type/id
posted amount/currency
reservation overrun boolean
```

No prompt/completion/raw keys/provider secret.

Audit failure rolls back the financial transaction, preserving existing V1 audit-atomicity philosophy.

System event uses nullable `actor_user_id` and explicit SYSTEM semantics.

---

## 20. Retry and duplicate convergence

Transient MySQL deadlock/serialization failure may use the same bounded retry philosophy as existing V1 posting.

Every retry first converges by business uniqueness:

```text
settlement unique request/usage
ledger posting unique settlement posting key
```

If a transaction committed but the worker lost the response:

```text
retry finds SETTLED Settlement / existing Ledger posting
→ returns/converges
→ no second Budget increment
→ no second Commitment consumption
→ no duplicate Audit
```

AIC-092 must include concurrent duplicate Settlement tests on real MySQL.

---

## 21. Retryable failure

Transient failures before commit leave no partial financial mutation.

After rollback, a bounded failure-recording transaction may update:

```text
status = RETRYABLE_FAILED
attempt_count += 1
next_attempt_at
last_error_code
```

The worker later retries the same Settlement identity.

Do not persist stack traces/free-form secret-bearing Provider bodies in Settlement.

---

## 22. Reconciliation-required failure

Conditions that cannot be automatically retried to a deterministic correct result become:

```text
RECONCILIATION_REQUIRED
```

Examples:

```text
period already CLOSED due historical/race condition
pricing version cannot represent required FINAL usage dimensions
unsupported money range
realtime evidence conflicts with immutable financial context
```

No Ledger posting is invented to hide the conflict.

Provider statement reconciliation/correction later resolves it.

---

## 23. Gateway Period Close blocker

M13 extends existing V1 `CloseBlockerProvider` with a Gateway financial blocker.

For a candidate BillingPeriod, normal Close is blocked by at least:

```text
request at/after DISPATCH_INTENT with no terminal usage classification
current usage status INCOMPLETE or UNKNOWN
current FINAL usage fact with no SETTLED Settlement
Settlement status PENDING
Settlement status RETRYABLE_FAILED
Settlement status RECONCILIATION_REQUIRED unless an explicit reviewed close exception exists
reservation PENDING_HOLD tied to possible-billable request
```

A simple expired Reservation for a request proven never dispatched can be safely released and should not permanently block Close.

---

## 24. Close-blocker race proof

The blocker alone is not sufficient; it is paired with the shared BillingPeriod row lock from section 11.

Required M13 concurrency test:

```text
Gateway attempts DISPATCH_INTENT
vs
Period Close coordinator
```

Result must be exactly one of:

```text
Gateway dispatch fence commits first
→ Close blocked by durable Gateway unresolved work

Close lock/state transition wins first
→ Gateway rejected before Provider I/O
```

Never:

```text
period CLOSED
AND
new billable request dispatched after blocker scan
```

---

## 25. Closed-period fallback

Despite the normal blocker, CLOSED-period cases can exist from:

```text
legacy data
manual recovery
older software version
external/provider timestamp disagreement
previous defect
```

Settlement must not reopen/bypass the period automatically.

Use:

```text
RECONCILIATION_REQUIRED
```

and later explicit existing Reopen/Correction governance if needed.

---

## 26. Settlement vs Provider final invoice

`SETTLED` means:

> AI-CostOps has deterministically posted the realtime cost according to the frozen Gateway usage observation and Pricing Version.

It does not claim:

> Provider invoice will contain exactly the same amount.

M15 compares:

```text
Gateway realtime Settlement
vs
Provider statement canonical Charge
```

Differences become reconciliation/correction evidence.

---

## 27. Hybrid reconciliation lineage

Settlement retains:

```text
request id
usage fact id
provider account id
provider request id when available
provider model id
usage effective timestamp
pricing version id
calculated raw amount
posted amount
rounding delta
currency
Ledger posting id
```

This is sufficient for financial matching without Prompt/Completion retention.

---

## 28. Metrics

Bounded Settlement metrics:

```text
gateway_settlement_total{outcome}
gateway_settlement_retry_total{reason_code}
gateway_settlement_reconciliation_required_total{reason_code}
gateway_settlement_rounding_total{direction}
gateway_reservation_overrun_total
gateway_close_blocker_total{reason_code}
```

No high-cardinality request/settlement/org ids as metric labels.

---

## 29. Required tests

AIC-092 must require real MySQL tests for:

```text
one FINAL usage -> one Settlement
concurrent Settlement workers -> one Ledger posting
commit response lost -> retry returns existing result
Audit failure -> entire financial mutation rollback
Budget actual exact posted amount
no Budget -> Ledger still posts
reservation overrun -> full posted amount
explicit Commitment consume only
system posting actor
Gateway source lineage FK
period CLOSED -> reconciliation required/no bypass
Close vs DISPATCH_INTENT race
Settlement commit vs reservation cleanup failure
scale >8 raw cost -> explicit quantized posted amount + rounding delta
positive non-zero raw cost -> posted amount never silently zero
```

---

## 30. AIC-089 Definition of Done

```text
[freeze] Backend owns final Settlement and financial posting
[freeze] DB-backed discovery means MQ is not correctness-critical
[freeze] one request/final usage -> at most one Settlement -> at most one Ledger posting
[freeze] no persistent PROCESSING state is required for worker correctness
[freeze] raw high-precision cost and scale-8 posted cost are both retained
[freeze] positive non-zero incurred cost cannot round silently to zero
[freeze] dispatch and Period Close share BillingPeriod locking semantics
[freeze] GATEWAY_SETTLEMENT is first-class Ledger source
[freeze] automated posting actor is SYSTEM, not fake human
[freeze] one frozen financial scope becomes one Ledger target
[freeze] Ledger/Actual/Commitment/Audit/SETTLED commit atomically
[freeze] reservation cleanup is outside transaction and cannot corrupt budget truth
[freeze] missing Budget never erases incurred cost
[freeze] unresolved possible-billable Gateway work blocks normal Period Close
[freeze] CLOSED-period exception becomes explicit reconciliation, never bypass
```

Any later implementation that posts realtime traffic through synthetic `charge_fact` or lets Gateway write Ledger directly violates this frozen boundary.
