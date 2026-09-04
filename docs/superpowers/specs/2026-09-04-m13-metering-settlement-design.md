# M13 Metering & Settlement Design

> Status: **APPROVED DESIGN BASELINE — written spec pending final human review**  
> Date: 2026-09-04  
> Baseline: `main@7cd80cdf55d3aec279971f72dd423ba6f68c5272`  
> Depends on: M11 Gateway Edge + M12 Identity / Attribution / Budget Reservation  
> Delivery: **two independently reviewable implementation PRs: M13-A + M13-B**

## 1. Goal

M13 closes the realtime financial loop deliberately left open by M11/M12:

```text
Gateway Credential
→ durable Gateway Request
→ durable Route Attempt with frozen Provider / Provider Model / Pricing Version
→ MySQL-authoritative Budget Reservation when applicable
→ committed DISPATCH_INTENT fence
→ Provider I/O
→ immutable Gateway Usage Fact + typed dimensions
→ Backend Gateway Settlement
→ immutable Ledger Posting
→ Budget Actual / optional explicitly-bound Commitment consumption
→ Reservation FINALIZED
→ Gateway Close blocker released
```

The governing correctness rule is:

> Provider execution that may have incurred cost is never silently converted to zero, lost, re-priced with a newer Pricing Version, or dropped because realtime usage is missing or a reservation was too small.

M13 is split in two so Provider metering is reviewed independently from the financial posting transaction.

---

## 2. Existing truth that M13 must reuse

M13 starts from `main@7cd80cdf55d3aec279971f72dd423ba6f68c5272` and reuses:

```text
gateway_request
gateway_route_attempt
budget_reservation
pricing_version
pricing_rate
billing_period
budget
budget_commitment
ledger_posting
ledger_entry
audit_event
```

Existing seams remain authoritative:

```text
GatewayRequestService
DispatchFenceService
GatewayRequestLifecycleService
StreamingLifecycleService
MimoChatAdapter
BlockingIoScheduler
BillingPeriodFinancialWriteFence
LedgerBudgetPort / LedgerBudgetService
existing immutable Ledger persistence
existing audit atomicity
existing CloseBlockerProvider framework
```

M13 must not:

```text
edit V1-V19 migrations
replace M12 TX1/TX2 admission + dispatch fence
move financial truth to Redis
invent a second Budget-selection policy
read the newest/current Pricing Version at settlement time
manufacture Provider statement / raw_provider_record / charge_fact rows for realtime Gateway traffic
persist prompt/completion/reasoning content for financial metering
introduce Kafka/RabbitMQ as a correctness dependency
implement M14 routing/failover/DSL
implement M15 Provider-statement reconciliation
implement FX
infer unrelated Commitments
```

Backend remains the sole production Flyway runner. Gateway never runs Flyway.

---

## 3. Two-stage implementation boundary

### 3.1 M13-A — Provider Metering & Immutable Usage Facts

M13-A ends at durable Gateway metering truth:

```text
Provider result/stream
→ provider-neutral metering observation
→ gateway_usage_fact
→ gateway_usage_dimension
→ gateway_request.current_usage_fact_id
```

M13-A includes:

- `V20__m13_gateway_usage_fact.sql`;
- immutable/revisioned usage facts;
- typed normalized usage dimensions;
- non-streaming MiMo usage extraction;
- streaming MiMo terminal usage extraction;
- FINAL / INCOMPLETE / UNKNOWN classification;
- usage-effective-time provenance;
- bounded safe Provider metadata;
- local atomic usage+lifecycle finalization;
- conservative timeout/cancel/failure behavior;
- metering metrics/security/schema/integration/concurrency coverage.

M13-A explicitly does **not** create Settlement, change Ledger schema, increment Budget Actual, consume Commitment, or FINALIZE reservations.

Therefore M13-A alone still leaves successful requests financially unresolved under the existing M12 close blocker. This is an accepted intermediate state and is no worse than the current M12 baseline.

### 3.2 M13-B — Settlement / Ledger / Budget Actual / Reservation Finalization

M13-B consumes only durable M13-A facts:

```text
current FINAL Usage Fact
→ Gateway Settlement
→ frozen Pricing calculation
→ SYSTEM Ledger Posting
→ Budget Actual / optional explicitly-bound Commitment
→ Reservation FINALIZED
→ Settlement SETTLED
```

M13-B includes:

- `V21__m13_gateway_settlement.sql`;
- `gateway_settlement`;
- `GATEWAY_SETTLEMENT` Ledger source lineage;
- SYSTEM posting actor semantics;
- DB-backed discovery/retry;
- frozen-pricing calculation;
- one atomic MySQL financial transaction;
- full Actual posting even when actual exceeds reservation;
- atomic reservation FINALIZED;
- Close blocker refinement;
- replay/concurrency/failure/rounding/security coverage.

M13-B does not add Provider failover, Provider statement reconciliation, FX, or new Commitment selection/binding policy.

---

## 4. Writer ownership

### Gateway may write

```text
gateway_request lifecycle fields
gateway_route_attempt lifecycle fields
gateway_request.current_usage_fact_id
gateway_usage_fact
gateway_usage_dimension
budget_reservation creation during M12 admission
budget_reservation ACTIVE -> RELEASED for proven pre-dispatch safe release
budget_reservation ACTIVE -> PENDING_HOLD for unresolved possible-billable work
```

Gateway must not write:

```text
Budget actual_amount
Budget committed_amount
Ledger posting/entry
gateway_settlement
```

### Backend / CostOps owns

```text
gateway_settlement
GATEWAY_SETTLEMENT Ledger posting/entry
Budget actual_amount mutation
explicitly-bound Commitment consumption
financial settlement audit
```

M13 adds one narrow exception:

> During the M13 Settlement financial transaction, Backend may transition **only the Settlement-bound reservation** from `ACTIVE` or `PENDING_HOLD` to `FINALIZED`, setting `finalized_at` and incrementing `version`.

Backend may not create, release, resize, retarget, re-currency, or otherwise administer Gateway reservations.

This exception exists solely so Ledger + Actual + Audit + Reservation FINALIZED + Settlement SETTLED commit together.

---

# Part A — M13-A Metering

## 5. V20 schema

M13-A adds exactly:

```text
backend/src/main/resources/db/migration/V20__m13_gateway_usage_fact.sql
```

V20 creates:

```text
gateway_usage_fact
gateway_usage_dimension
```

and adds:

```text
gateway_request.current_usage_fact_id
```

No Ledger, Settlement, Budget, or reservation-column change belongs in V20.

Every organization-owned table follows the existing same-org integrity convention:

```text
PRIMARY KEY (id)
UNIQUE (id, org_id)
composite same-org FK (..., org_id) -> parent(id, org_id)
```

### 5.1 `gateway_usage_fact`

```text
id                          BIGINT AUTO_INCREMENT PK
org_id                      BIGINT NOT NULL
request_id                  BIGINT NOT NULL
route_attempt_id            BIGINT NOT NULL
sequence                    INT NOT NULL
status                      VARCHAR(16) NOT NULL
supersedes_usage_fact_id    BIGINT NULL
provider_request_id         VARCHAR(255) NULL
usage_effective_at          DATETIME(6) NOT NULL
usage_effective_at_source   VARCHAR(48) NOT NULL
pricing_version_id          BIGINT NOT NULL
currency                    CHAR(3) NOT NULL
safe_provider_metadata_json JSON NULL
observed_at                 DATETIME(6) NOT NULL
created_at                  DATETIME(6) NOT NULL
final_slot                  generated nullable slot
```

Required constraints:

```text
UNIQUE(org_id, request_id, sequence)
UNIQUE(org_id, request_id, final_slot)
sequence >= 1
status IN (FINAL, INCOMPLETE, UNKNOWN)
usage_effective_at_source IN (
  PROVIDER_BILLING_TIMESTAMP,
  PROVIDER_REQUEST_TIMESTAMP,
  GATEWAY_DISPATCH_INTENT_TIMESTAMP
)
currency matches ^[A-Z]{3}$
final_slot = CASE WHEN status='FINAL' THEN 1 ELSE NULL END
```

Same-org FKs include request, route attempt, superseded usage fact, and pricing version.

### 5.2 `gateway_usage_dimension`

```text
id              BIGINT AUTO_INCREMENT PK
org_id          BIGINT NOT NULL
usage_fact_id   BIGINT NOT NULL
dimension_code  VARCHAR(64) NOT NULL
quantity        DECIMAL(30,8) NOT NULL
provenance      VARCHAR(32) NOT NULL
```

Required constraints:

```text
UNIQUE(usage_fact_id, dimension_code)
dimension_code IN (INPUT_TOKEN, OUTPUT_TOKEN, CACHED_INPUT_TOKEN, REQUEST)
quantity >= 0
provenance IN (PROVIDER_FINAL, PROVIDER_PARTIAL, GATEWAY_DETERMINISTIC)
```

No floating-point quantity is permitted.

### 5.3 `gateway_request.current_usage_fact_id`

This is a mutable Gateway-owned convenience pointer only:

- nullable before durable metering exists;
- same-org FK to `gateway_usage_fact(id, org_id)`;
- updated only in the same local transaction that inserts the new fact/dimensions;
- never replaces append-only lineage;
- Backend may read but never write it.

---

## 6. Metering statuses

Exactly three statuses exist:

```text
FINAL
INCOMPLETE
UNKNOWN
```

### FINAL

FINAL means all quantities required to deterministically price the request under the **exact frozen Pricing Version on the Route Attempt** are trustworthy and present.

Publication condition:

```text
required Pricing Rate dimensions
⊆ trustworthy normalized dimensions
```

A Provider usage object by itself is not sufficient.

### INCOMPLETE

Some credible billable quantities exist, but one or more quantities required by the frozen Pricing Version are missing or partial.

INCOMPLETE is never settled as zero.

### UNKNOWN

Provider execution may have been billable, but no sufficiently reliable normalized quantity is available.

UNKNOWN is explicit financial uncertainty, not absence of cost.

Missing usage after dispatch, timeout, disconnect, malformed final usage, or a post-dispatch DB/process failure never defaults to zero.

---

## 7. Dimension semantics

Supported normalized dimensions:

```text
INPUT_TOKEN
OUTPUT_TOKEN
CACHED_INPUT_TOKEN
REQUEST
```

Rules:

- quantities are exact integer/`BigDecimal` values;
- no dimension is inferred from an unrelated field;
- absence is not zero unless Provider semantics positively prove zero;
- reservation estimates are never treated as actual metering;
- `REQUEST=1` may be `GATEWAY_DETERMINISTIC` only when the frozen Pricing Version prices a request fee and one billable dispatch is deterministically established;
- `CACHED_INPUT_TOKEN` is usable only when Provider evidence explicitly exposes a trustworthy cached-token count or a separately certified deterministic mechanism exists.

The current Gateway test Pricing Version contains `INPUT_TOKEN + OUTPUT_TOKEN`. Current official MiMo examples expose `prompt_tokens + completion_tokens`, including a terminal streaming usage chunk, so these dimensions can be certified for FINAL when values are valid.

Current official examples do **not** demonstrate a cached-token quantity; `prompt_tokens_details` is shown empty. Therefore a Pricing Version containing `CACHED_INPUT_TOKEN` must remain INCOMPLETE unless explicit tested MiMo evidence for that quantity is added. Never assume cached quantity is zero.

---

## 8. MiMo certification and provider-neutral stream events

Provider-specific parsing remains inside `MimoChatAdapter` / MiMo DTOs.

### Non-streaming

Current MiMo examples expose:

```text
id
created
model
usage.prompt_tokens
usage.completion_tokens
usage.total_tokens
```

`total_tokens` is a consistency field. Do not derive a missing prompt/output component from it unless the Provider contract is separately certified to make that transformation exact and safe.

### Streaming

Verified against Xiaomi MiMo official documentation on 2026-09-04. The official streaming example shows:

```text
content/reasoning chunks
→ finish_reason chunk with usage = null
→ usage-only chunk with choices = [] and full usage
→ data: [DONE]
```

M13-A therefore models provider-neutral stream events conceptually as:

```text
DELTA
METERING
DONE
```

Rules:

- DELTA is client-forwardable content/control delta under the existing public contract;
- METERING updates only bounded metering state and is not treated as completion text;
- DONE is protocol completion;
- no full completion buffering is introduced;
- all provider event parsing remains subject to existing bounded event/body limits;
- M13 does not add unsupported client parameters merely to obtain usage.

A stream that reaches `[DONE]` without sufficient usage is INCOMPLETE/UNKNOWN, never zero.

### Provider request id / safe metadata

Do not fabricate `provider_request_id` from an identifier whose Provider semantics are not documented as a request identity.

If a trustworthy request id is unavailable, null is valid. A bounded non-secret upstream completion id may be retained only through the explicit safe-metadata allowlist when required for support/reconciliation.

Safe metadata serialized size remains capped at 8 KiB and never contains prompt/completion/reasoning content, Authorization/provider keys, secret-bearing headers, or arbitrary upstream bodies.

---

## 9. Usage effective time

Every fact stores immutable:

```text
usage_effective_at
usage_effective_at_source
```

Precedence:

```text
1. PROVIDER_BILLING_TIMESTAMP
2. PROVIDER_REQUEST_TIMESTAMP
3. GATEWAY_DISPATCH_INTENT_TIMESTAMP
```

For current MiMo, a documented/validated `created` request timestamp may be `PROVIDER_REQUEST_TIMESTAMP`; otherwise use durable `dispatch_intent_at`. Never use response completion time merely for convenience.

The persisted `gateway_request.billing_period_id` remains the normal M13-B financial period fence. Provider time evidence is retained for reconciliation; it does not silently move an already-authorized request to another BillingPeriod.

---

## 10. Append-only revisions

Usage facts are immutable after insertion.

Allowed progression:

```text
INCOMPLETE/UNKNOWN sequence 1
→ better evidence
→ sequence 2 with supersedes_usage_fact_id = sequence 1 id
→ current_usage_fact_id = sequence 2 id
```

Rules:

- no destructive update of old facts/dimensions;
- sequence strictly increases per request;
- a new fact supersedes the current fact when one exists;
- once current fact is FINAL, normal Gateway realtime code appends no further fact;
- at most one FINAL is additionally enforced by generated `final_slot` uniqueness;
- later Provider-statement corrections belong to M15, not a competing FINAL fact;
- concurrent append attempts converge through real MySQL uniqueness + transaction checks.

Normal M13-A runtime may publish only sequence 1; the revision contract must still be implemented/tested because recovery and M13-B depend on it.

---

## 11. Local Gateway finalization transaction

No Provider call runs inside a DB transaction.

After Provider I/O reaches a terminal condition, Gateway uses a narrow local MySQL transaction to converge metering and lifecycle state.

### Successful non-streaming

```text
Provider response received
→ BEGIN local DB tx
→ validate request/route frozen lineage
→ append Usage Fact + dimensions
→ update current_usage_fact_id
→ mark route COMPLETED
→ mark request TRANSPORT_COMPLETED
→ COMMIT
→ only then return HTTP success to client
```

### Successful streaming

Content chunks may continue to stream incrementally. The terminal sequence is stricter:

```text
observe upstream final METERING
→ observe upstream DONE
→ BEGIN local DB tx
→ append Usage Fact + dimensions
→ update current_usage_fact_id
→ mark route COMPLETED
→ mark request TRANSPORT_COMPLETED
→ COMMIT
→ only then emit downstream [DONE]
```

This intentionally changes the current M11 order. The client must not receive the final success marker before durable metering/lifecycle state commits.

If that local commit fails after Provider execution:

- do not emit downstream `[DONE]`;
- do not re-dispatch Provider;
- retain/leave durable possible-billable request/route evidence from the pre-existing dispatch fence;
- later recovery/Close blocker handles the uncertainty.

### Post-dispatch failure/cancel/timeout

When safe usage evidence exists, the local terminal transaction may append FINAL/INCOMPLETE/UNKNOWN according to evidence, update the current pointer, persist the appropriate failure/cancel/timeout request state, and move an ACTIVE reservation to PENDING_HOLD when the financial outcome remains unresolved.

A Provider may have supplied exact FINAL usage even when transport later ends abnormally. In that case the usage fact may be FINAL while the request transport state is failed; M13-B can still settle the known incurred cost.

If local persistence fails, no second Provider call is issued. Explicit uncertainty is the correctness mechanism at the unavoidable external-side-effect boundary.

---

## 12. Reactive/blocking boundary

All MyBatis/JDBC remains off Reactor Netty event-loop threads through `BlockingIoScheduler`.

The local finalization transaction uses `TransactionTemplate` or an equivalent already-proven project pattern.

Do not:

```text
.block() on the event loop
rely on @Transactional self-invocation
block waiting for work re-dispatched onto the same bounded blocking scheduler
```

This preserves the M12 transaction/deadlock fixes.

---

## 13. M13-A reservation consequence

M13-A never FINALIZES reservations.

- successful transport + FINAL usage may leave reservation ACTIVE until M13-B;
- INCOMPLETE/UNKNOWN possible-billable work becomes/remains PENDING_HOLD conservatively;
- proven pre-dispatch no-provider-call paths retain M12 RELEASED behavior;
- missing usage never causes RELEASED by itself.

---

# Part B — M13-B Settlement

## 14. V21 schema

M13-B adds exactly:

```text
backend/src/main/resources/db/migration/V21__m13_gateway_settlement.sql
```

V21 creates `gateway_settlement` and forward-alters Ledger. It does not rewrite V20 facts.

### 14.1 `gateway_settlement`

```text
id                          BIGINT AUTO_INCREMENT PK
org_id                      BIGINT NOT NULL
settlement_key              VARCHAR(96) NOT NULL
request_id                  BIGINT NOT NULL
route_attempt_id            BIGINT NOT NULL
usage_fact_id               BIGINT NOT NULL
reservation_id              BIGINT NULL
billing_period_id           BIGINT NOT NULL
financial_scope_type        VARCHAR(32) NOT NULL
financial_scope_id          BIGINT NOT NULL
provider_account_id         BIGINT NOT NULL
provider_model_id           BIGINT NOT NULL
pricing_version_id          BIGINT NOT NULL
currency                    CHAR(3) NOT NULL
calculated_amount_raw       DECIMAL(38,18) NULL
posted_amount               DECIMAL(20,8) NULL
rounding_delta              DECIMAL(38,18) NULL
status                      VARCHAR(32) NOT NULL
attempt_count               INT NOT NULL DEFAULT 0
next_attempt_at             DATETIME(6) NULL
last_error_code             VARCHAR(64) NULL
ledger_posting_id           BIGINT NULL
created_at                  DATETIME(6) NOT NULL
settled_at                  DATETIME(6) NULL
reconciliation_required_at  DATETIME(6) NULL
updated_at                  DATETIME(6) NOT NULL
```

Required uniqueness:

```text
UNIQUE(org_id, settlement_key)
UNIQUE(org_id, request_id)
UNIQUE(org_id, usage_fact_id)
```

Same-org FKs cover request, route attempt, usage fact, optional reservation, billing period, Provider account/model/pricing lineage where organization-scoped, and `ledger_posting_id` after the Ledger posting exists.

Exact business keys:

```text
settlement_key = GATEWAY_REQUEST:<public_request_id>
ledger posting_key = GATEWAY_SETTLEMENT:<decimal settlement id>
```

### 14.2 Settlement state machine

States:

```text
PENDING
RETRYABLE_FAILED
RECONCILIATION_REQUIRED
SETTLED
```

Allowed automatic transitions:

```text
PENDING -> SETTLED | RETRYABLE_FAILED | RECONCILIATION_REQUIRED
RETRYABLE_FAILED -> SETTLED | RETRYABLE_FAILED | RECONCILIATION_REQUIRED
```

`SETTLED` is terminal. `RECONCILIATION_REQUIRED` is terminal for the automatic realtime worker and requires explicit later governance/reconciliation.

`SETTLED` requires deterministic amounts, `ledger_posting_id`, and `settled_at`.

No persistent PROCESSING truth is introduced.

---

## 15. Discovery without lock inversion

Normal eligibility:

```text
gateway_request.current_usage_fact_id -> current fact
current fact.status = FINAL
no existing Settlement for the request
```

Discovery is DB-backed and bounded:

```text
1. discover current FINAL facts lacking Settlement
2. insert PENDING Settlement under business uniqueness
3. duplicate insert converges
4. discover PENDING / eligible RETRYABLE_FAILED settlement ids
5. process with the global financial lock order
```

A message/wakeup may be added later, but correctness never depends on it.

Critical rule:

> Worker discovery must not take a long-lived Settlement `FOR UPDATE` claim before BillingPeriod/Budget/Reservation locks. That would invert the global financial lock order.

Candidate IDs may be read without holding the financial source lock. Concurrent workers converge through uniqueness and the ordered transaction below.

---

## 16. Frozen pricing lineage

Settlement never asks for the current/latest price.

It cross-validates:

```text
Settlement.route_attempt_id
→ gateway_route_attempt.provider_account_id
→ gateway_route_attempt.provider_model_id
→ gateway_route_attempt.pricing_version_id

UsageFact.route_attempt_id must match
UsageFact.pricing_version_id must match
UsageFact.currency must match frozen Pricing Version currency
```

A mismatch is never repaired by choosing another price. It is an integrity/reconciliation condition.

---

## 17. Cost calculation and quantization

For each Pricing Rate in the frozen Pricing Version:

```text
dimension_raw_cost
= exact_quantity
  * exact_unit_price
  / exact_unit_quantity
```

Rules:

- `BigDecimal` only;
- no float/double;
- every priced dimension must exist on the FINAL fact;
- no missing quantity becomes zero;
- raw calculation remains compatible with `DECIMAL(38,18)`;
- unsupported range fails safely.

For positive incurred cost:

```text
posted_amount
= calculated_amount_raw rounded UP / away from zero to scale 8
rounding_delta
= posted_amount - calculated_amount_raw
```

A non-zero positive cost must never quantize to `0.00000000`.

Negative realtime Provider credits are outside M13 scope.

---

## 18. Budget / reservation lineage

If `reservation_id` is non-null, Settlement does **not** re-run Budget selection.

It uses and verifies the request-time governed binding:

```text
reservation.budget_id
reservation.billing_period_id
reservation.financial_scope_type
reservation.financial_scope_id
reservation.currency
```

For M12 OPTIONAL unbudgeted requests:

```text
reservation_id = NULL
Ledger still posts full incurred cost
Budget Actual mutation = none
```

Already incurred cost is never erased because no Budget exists.

Normal bound-reservation states accepted for fresh settlement are:

```text
ACTIVE
PENDING_HOLD
```

If a bound reservation is unexpectedly RELEASED before a known billable FINAL fact is settled, do not rewrite that history or silently select a new reservation; mark the Settlement `RECONCILIATION_REQUIRED`.

A FINALIZED reservation is valid only as a replay/convergence state with the matching already-SETTLED financial result. FINALIZED without matching settled lineage is an integrity condition, not permission to post again.

---

## 19. Global financial lock order

Normal M13-B transaction order is frozen as:

```text
1. BillingPeriod
2. Budget (when present)
3. explicitly-bound Commitment (when present)
4. BudgetReservation (when present)
5. GatewaySettlement
6. Ledger posting convergence / immutable Ledger inserts
```

Within any category containing multiple ids, lock ascending ids.

Do not acquire Reservation before Budget. Do not acquire Settlement before BillingPeriod. Do not introduce a worker claim that reverses this order.

---

## 20. Atomic Settlement transaction

For one PENDING / eligible RETRYABLE_FAILED Settlement:

```text
BEGIN
1. lock persisted BillingPeriod through BillingPeriodFinancialWriteFence
2. verify OPEN under the same durable row used by Close
3. lock bound Budget when present
4. lock explicit Commitment when present
5. lock bound BudgetReservation when present
6. lock GatewaySettlement
7. re-read/cross-validate Request / Route Attempt / Usage Fact / Pricing Version / Reservation lineage
8. converge on stable Ledger posting_key
9. calculate raw + posted amount
10. insert SYSTEM Ledger posting
11. insert exactly one Gateway Settlement Ledger entry
12. increment Budget actual by full posted amount when Budget exists
13. consume only an explicitly-bound Commitment using existing V1 semantics
14. write financial audit
15. transition bound reservation ACTIVE/PENDING_HOLD -> FINALIZED, set finalized_at, increment version
16. set Settlement SETTLED + amounts + ledger_posting_id + settled_at
COMMIT
```

No Provider or Redis operation occurs inside this transaction.

### Reservation overrun

If:

```text
posted_amount > reserved_amount
```

post the **full actual amount** anyway. The Budget may become over-budget; already-incurred Provider cost is never rejected because the reservation was too small.

### Atomic FINALIZED handoff

Ledger insertion + Budget Actual + explicit Commitment mutation + Audit + Reservation FINALIZED + Settlement SETTLED are one transaction.

Therefore normal committed state cannot expose:

```text
Actual already contains charge
AND
same effective reservation still remains ACTIVE/PENDING_HOLD
```

Rollback rolls all of them back together.

---

## 21. Ledger forward contract

M13-B adds:

```text
LedgerSourceType.GATEWAY_SETTLEMENT
```

V21 semantics:

```text
ledger_posting.source_type allows GATEWAY_SETTLEMENT
ledger_posting.posting_actor_type VARCHAR(16) NOT NULL
posting_actor_type IN (MEMBER, SYSTEM)
existing rows backfill MEMBER
posted_by_member_id becomes nullable
MEMBER -> posted_by_member_id IS NOT NULL
SYSTEM -> posted_by_member_id IS NULL
```

Gateway Settlement posting:

```text
source_type = GATEWAY_SETTLEMENT
source_id = gateway_settlement.id
allocation_decision_id = NULL
posting_actor_type = SYSTEM
posted_by_member_id = NULL
```

`ledger_entry` gains:

```text
source_gateway_settlement_id BIGINT NULL
same-org FK -> gateway_settlement(id, org_id)
```

Normal Gateway entry:

```text
source_gateway_settlement_id = settlement id
source_charge_fact_id = NULL
source_expense_claim_id = NULL
allocation_line_id = NULL
exactly one financial target column for PROJECT / TEAM / COST_CENTER
budget_id = bound Budget id when present, otherwise NULL
```

The direct-primary-source integrity rule becomes “at most one direct primary source” across Provider Charge / Expense Claim / Gateway Settlement so existing correction lineage remains valid.

Do not synthesize V1 Provider-charge/allocation objects merely to reuse `ProviderChargePostingService`. Add a narrow Gateway Settlement orchestration while reusing the proven period/Budget/Ledger seams.

---

## 22. SYSTEM audit semantics

`audit_event.actor_user_id` is already nullable; do not invent a fake organization member.

Representative event:

```text
GATEWAY_SETTLEMENT_POSTED
```

Safe bounded metadata may include request/settlement/usage ids, Provider account/model ids, Pricing Version id, financial scope, amount/currency, and reservation-overrun boolean.

Prompt/completion/raw keys/arbitrary Provider bodies never enter settlement audit metadata.

Audit participates in the same financial transaction; audit failure rolls the transaction back.

---

## 23. Commitment handling

M13 does not infer/create Commitment bindings.

Current M12 reservations have:

```text
commitment_id = NULL
commitment_backed_amount = 0
```

If an explicitly governed non-null `commitment_id` exists, Settlement may consume it only after same-org/Budget/status validation under the frozen lock order.

Consumption follows existing V1 semantics:

```text
consumed = min(posted amount, remaining commitment amount)
```

Full actual cost still posts if Commitment is exhausted. No unrelated Commitment search/inference is permitted.

---

## 24. Retry / idempotency

Business uniqueness is authoritative:

```text
one Gateway Request -> at most one realtime Settlement
one FINAL Usage Fact -> at most one realtime Settlement
one Settlement -> at most one Ledger posting key
one committed transaction -> one Budget Actual increment
```

If commit succeeded but worker lost the result, retry converges to SETTLED/existing Ledger posting and performs no second Actual/Commitment/reservation/audit mutation.

Transient DB deadlock/serialization/dependency failure may record after rollback:

```text
status = RETRYABLE_FAILED
attempt_count += 1
next_attempt_at = bounded backoff
last_error_code = bounded code
```

No stack trace or secret-bearing free-form Provider body is persisted.

---

## 25. `RECONCILIATION_REQUIRED`

Automatic realtime posting stops instead of inventing truth for semantic/external-history conflicts such as:

```text
persisted BillingPeriod unexpectedly CLOSED
Usage / Route / Pricing lineage mismatch
FINAL fact cannot actually satisfy frozen Pricing Version
bound reservation unexpectedly RELEASED
FINALIZED reservation without matching settled result
amount outside supported accounting representation
period/effective-time conflict requiring external review
explicit Commitment lineage conflict
```

No fake Ledger posting is created to hide the conflict. Later M15 or existing reopen/correction governance handles external-truth resolution.

---

## 26. Close blocker after M13-B

Reuse `PENDING_GATEWAY_FINANCIAL_WORK`; do not add a new enum.

Normal Close is blocked by any same-period unresolved Gateway work, including:

```text
post-DISPATCH_INTENT request with no current usage fact
current usage INCOMPLETE
current usage UNKNOWN
current FINAL usage with no Settlement
Settlement PENDING
Settlement RETRYABLE_FAILED
Settlement RECONCILIATION_REQUIRED
ACTIVE/PENDING_HOLD reservation not atomically finalized/released
```

A transport-failed request may be financially resolved if it has trustworthy FINAL usage and SETTLED Settlement. Transport state alone must not remain a blocker once durable financial truth is terminal.

RELEASED/FINALIZED reservations do not block by themselves.

Settlement and Close serialize on the same durable BillingPeriod row:

```text
Settlement wins period lock
→ commits financial truth
→ Close later scans and may pass

Close wins period lock
→ unresolved Gateway work blocks Close
or a legacy inconsistency prevents normal settlement
→ Settlement never bypasses/reopens the period automatically
```

M13-B must include a real MySQL Settlement-vs-Close concurrency test.

---

## 27. Security and observability

Never persist:

```text
prompt
completion
reasoning content
raw Gateway key
Provider API key
Authorization header
unbounded Provider error/response body
```

All tenant-owned reads/writes are org-qualified and protected by same-org integrity.

Backend Settlement never decrypts Provider credentials.

Metric labels are bounded enumerations/catalog values only. Recommended metrics:

```text
gateway_usage_total{status}
gateway_metering_incomplete_total{provider_code,reason_code}
gateway_metering_unknown_total{provider_code,reason_code}
gateway_provider_usage_parse_error_total{provider_code}
gateway_settlement_total{status}
gateway_settlement_retry_total{reason_code}
gateway_settlement_reconciliation_required_total{reason_code}
gateway_reservation_overrun_total{provider_code}
```

Never label metrics by request/org/credential/provider-request ids or free-form model names.

---

## 28. Public API

M13 does not expand inference-client financial authority.

Client still cannot submit:

```text
project/team/cost-center id
budget id
currency
pricing version id
provider account/model id
reservation id
```

Missing Provider usage is not rewritten into zero usage. Existing public compatibility may keep `usage = null` when usage is absent.

M13 does not require public financial amounts in Gateway status responses. Any operational projection remains bounded state-only data by default.

---

## 29. M13-A acceptance

M13-A must prove:

- V20 only; V1-V19 unchanged;
- same-org, sequence, FINAL uniqueness and dimension constraints on real MySQL;
- exact non-stream prompt/output usage -> FINAL for default INPUT+OUTPUT Pricing Version;
- missing/malformed required usage -> INCOMPLETE/UNKNOWN, never zero;
- frozen Route Attempt pricing lineage copied, never re-resolved;
- streaming usage-only chunk parsed as METERING, not text;
- valid usage + upstream DONE -> local DB commit -> downstream DONE, in that order;
- DB failure after Provider completion emits no downstream DONE and never re-dispatches Provider;
- upstream DONE without required usage -> non-FINAL;
- disconnect before final usage -> non-FINAL;
- `CACHED_INPUT_TOKEN` Pricing Rate without certified cached quantity -> INCOMPLETE;
- INCOMPLETE/UNKNOWN -> later FINAL appends a new immutable fact and supersedes the old fact;
- no second FINAL / no normal append after current FINAL;
- usage+lifecycle local finalization is transactional;
- uncertain post-dispatch reservation becomes/remains PENDING_HOLD;
- all MyBatis/JDBC off Reactor event loop;
- Gateway architecture still forbids Ledger/Budget Actual/Settlement writes;
- no content/secrets persisted.

---

## 30. M13-B acceptance

M13-B must prove:

- V21 only; V1-V20 unchanged;
- Settlement request/usage uniqueness;
- existing Ledger rows backfill MEMBER actor correctly;
- SYSTEM/MEMBER actor checks and Gateway Settlement lineage;
- newer ACTIVE price does not affect settlement of an old frozen Route Attempt;
- missing priced dimension never becomes zero;
- exact raw precision, scale-8 upward quantization, durable rounding delta;
- tiny positive raw cost posts at least `0.00000001`;
- Ledger + entry + Actual + Audit + FINALIZED + SETTLED atomic commit;
- injected failure at financial seams rolls back without partial mutation;
- actual > reserved posts full actual;
- OPTIONAL unbudgeted FINAL usage posts Ledger with no Budget mutation;
- no Budget re-selection when reservation exists;
- explicit Commitment only; never inferred;
- concurrent duplicate workers produce one Settlement/Ledger/Actual/Audit/finalization;
- simulated lost response retry converges;
- lock order is BillingPeriod -> Budget -> Commitment -> Reservation -> Settlement -> Ledger;
- no Reservation->Budget or Settlement->BillingPeriod inversion;
- real MySQL Settlement-vs-Close race converges safely;
- no usage / INCOMPLETE / UNKNOWN / unsettled FINAL / PENDING / RETRYABLE_FAILED / RECONCILIATION_REQUIRED blocks Close;
- SETTLED + FINALIZED releases Gateway financial blocker;
- transport failure + exact FINAL + SETTLED is financially terminal;
- Backend does not read Provider secrets;
- no content/secrets in Usage/Settlement/Audit/logs;
- bounded metrics only.

---

## 31. Deployment sequencing

### M13-A

```text
1. deploy Backend migration runner with V20
2. verify V20 applied
3. deploy Gateway M13-A code
```

### M13-B

```text
1. deploy Backend with V21 + Settlement code
2. V21 applies before worker activation
3. worker discovers durable FINAL facts, including facts accumulated during M13-A-only interval
```

Existing historical M11/M12 requests without trustworthy FINAL usage remain unresolved. M13 never fabricates historical zero usage.

---

## 32. Non-scope

```text
M14 generic routing admin/failover
multi-Provider settlement engine beyond narrow MiMo vertical slice
M15 Provider-statement reconciliation
FX
negative realtime Provider credits
split financial allocation for one Gateway request
new Commitment binding policy
message broker as correctness dependency
prompt/completion persistence
historical estimated usage converted to fake actual cost
```

---

## 33. Superseded provisional decisions

### M13 schema is split into V20 + V21

Older documents described one conceptual “Wave M13”. Final implementation uses:

```text
V20 = usage facts + dimensions + request pointer
V21 = settlement + Ledger forward extension
```

The final schema intent is unchanged; the split gives M13-A and M13-B independently deployable/reviewable boundaries.

### Reservation finalization is atomic in Backend Settlement

Older AIC-089 wording allowed Backend to post Actual and Gateway to FINALIZE later. Final M13 instead requires:

```text
Backend Settlement tx
→ Ledger + Actual + Audit + Reservation FINALIZED + Settlement SETTLED
→ one commit
```

Backend receives no broader reservation administration authority.

---

## 34. External MiMo evidence

Verified on 2026-09-04 against Xiaomi MiMo official documentation:

```text
https://platform.xiaomimimo.com/docs/en-US/usage-guide/passing-back-reasoning_content
```

The documented streaming example shows a terminal usage-only chunk containing:

```text
choices: []
usage.prompt_tokens
usage.completion_tokens
usage.total_tokens
```

followed by `data: [DONE]`.

The same documentation shows non-streaming usage fields. Its current examples show empty `prompt_tokens_details`, not a certified cached-token quantity; M13 therefore does not infer `CACHED_INPUT_TOKEN`.

Current MiMo pricing pages distinguish cache-hit and cache-miss input pricing, which is further reason never to guess cached usage as zero when a frozen Pricing Version models it explicitly.

---

## 35. Definition of Done

M13 is complete only when both M13-A and M13-B are merged and independently evidenced:

```text
[freeze] Provider usage becomes immutable durable Gateway facts
[freeze] FINAL / INCOMPLETE / UNKNOWN are the only metering statuses
[freeze] missing usage never defaults to zero
[freeze] streaming terminal success waits for durable local metering commit before downstream DONE
[freeze] frozen Route Attempt Pricing Version is the only realtime settlement price
[freeze] Settlement is Backend/CostOps-owned and DB-backed
[freeze] Ledger source is first-class GATEWAY_SETTLEMENT
[freeze] SYSTEM actor replaces fake-member posting
[freeze] full actual posts even when actual > reservation
[freeze] Budget is never re-selected during Settlement when reservation exists
[freeze] Ledger + Actual + Audit + Reservation FINALIZED + Settlement SETTLED are atomic
[freeze] retries never double-post Ledger or Actual
[freeze] unresolved usage/settlement blocks Close
[freeze] Close and Settlement serialize on the same BillingPeriod fence
[freeze] no prompt/completion/raw secrets are persisted for metering/settlement
[freeze] M13-A and M13-B each pass focused, full CI and Security verification
```
