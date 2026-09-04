# M13 Metering & Settlement Design

> Status: **APPROVED DESIGN BASELINE — written spec pending final human review**  
> Date: 2026-09-04  
> Baseline: `main@7cd80cdf55d3aec279971f72dd423ba6f68c5272`  
> Depends on: M11 Gateway Edge + M12 Identity / Attribution / Budget Reservation  
> Delivery shape: **two independently reviewable implementation PRs: M13-A + M13-B**

## 1. Purpose

M13 closes the realtime financial loop that M11/M12 deliberately left open:

```text
Gateway Credential
→ durable Gateway Request
→ durable Route Attempt with frozen Provider / Provider Model / Pricing Version
→ MySQL-authoritative Budget Reservation when applicable
→ committed DISPATCH_INTENT financial fence
→ Provider I/O
→ immutable Gateway Usage Fact + typed normalized dimensions
→ Backend Gateway Settlement
→ immutable Ledger Posting
→ Budget Actual / optional explicitly-bound Commitment consumption
→ Reservation FINALIZED
→ Gateway financial Close blocker released
```

The defining correctness rule is:

> Provider execution that may have incurred cost is never silently converted to zero, lost, re-priced with a newer Pricing Version, or dropped merely because realtime usage is missing or a reservation was too small.

M13 is intentionally split into two implementation waves so Provider metering can be reviewed independently from the financial posting transaction.

---

## 2. Existing baseline and compatibility constraints

The implementation starts from `main@7cd80cdf55d3aec279971f72dd423ba6f68c5272`.

Existing durable truth that M13 must reuse rather than redesign:

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

Existing runtime seams that remain authoritative:

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
replace the M12 TX1/TX2 admission + dispatch-fence design
move financial truth to Redis
invent a second Budget-selection policy
read a newer/current Pricing Version at settlement time
manufacture Provider statement / raw_provider_record / charge_fact rows for realtime Gateway traffic
persist prompt/completion content for financial metering
introduce Kafka/RabbitMQ as a correctness dependency
implement M14 generic routing/failover/DSL
implement M15 Provider-statement reconciliation
implement FX
infer unrelated Commitments
```

Backend remains the sole production Flyway runner. Gateway never runs Flyway.

---

## 3. Two-stage delivery boundary

### 3.1 M13-A — Provider Metering & Immutable Usage Facts

M13-A ends at durable Gateway metering truth.

```text
Provider transport/result
→ provider-neutral usage observation
→ gateway_usage_fact
→ gateway_usage_dimension
→ gateway_request.current_usage_fact_id
```

M13-A includes:

- forward migration `V20__m13_gateway_usage_fact.sql`;
- immutable/revisioned usage facts;
- typed normalized usage dimensions;
- non-streaming MiMo usage extraction;
- streaming MiMo final usage extraction;
- FINAL / INCOMPLETE / UNKNOWN classification;
- usage-effective-time provenance;
- bounded safe Provider metadata only;
- atomic local DB finalization of usage fact + Gateway terminal lifecycle where possible;
- explicit conservative behavior for post-dispatch timeout/cancel/failure;
- Gateway metering metrics/security tests;
- schema/integration/concurrency tests.

M13-A explicitly does **not** create Settlement, modify Ledger schema, increment Budget Actual, consume Commitment, or FINALIZE reservations.

Therefore the existing M12 close blocker remains conservative after M13-A: successfully metered requests may still block Close until M13-B posts the financial result. This is an accepted intermediate deployment state and is no worse than the current M12 baseline.

### 3.2 M13-B — Settlement / Ledger / Budget Actual / Reservation Finalization

M13-B consumes only durable M13-A usage facts.

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

- forward migration `V21__m13_gateway_settlement.sql`;
- `gateway_settlement`;
- Ledger `GATEWAY_SETTLEMENT` source lineage;
- SYSTEM posting actor semantics;
- deterministic settlement discovery/retry;
- frozen-pricing cost calculation;
- one atomic MySQL financial transaction;
- Budget Actual posting of the full incurred amount even when it exceeds reservation;
- reservation FINALIZED in the same financial transaction;
- Close blocker refinement;
- replay/concurrency/failure/rounding/security tests.

M13-B does not add Provider failover, Provider statement reconciliation, FX, or new commitment-selection behavior.

---

## 4. Ownership matrix

### 4.1 Gateway-owned durable writes

Gateway may write:

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

### 4.2 Backend / CostOps-owned durable writes

Backend owns:

```text
gateway_settlement
Ledger posting/entry for GATEWAY_SETTLEMENT
Budget actual_amount mutation
explicitly-bound Commitment consumption
financial settlement audit
```

M13 adds one deliberately narrow cross-owner exception:

> During the normal M13 Settlement financial transaction, Backend may transition **only the Settlement-bound reservation** from `ACTIVE` or `PENDING_HOLD` to `FINALIZED`, setting `finalized_at` and incrementing its optimistic `version`.

Backend must not:

```text
create a reservation
select a replacement reservation
release a reservation
change its amount/scope/currency/budget binding
move FINALIZED back to an effective state
```

This narrow exception exists so Ledger + Budget Actual + Reservation FINALIZED + Settlement SETTLED commit atomically, eliminating a crash window where Actual is already posted while an effective reservation still subtracts availability.

---

# Part A — M13-A Metering Design

## 5. V20 schema wave

M13-A adds exactly one migration:

```text
backend/src/main/resources/db/migration/V20__m13_gateway_usage_fact.sql
```

V20 creates:

```text
gateway_usage_fact
gateway_usage_dimension
```

and forward-alters:

```text
gateway_request.current_usage_fact_id
```

No Ledger, Budget, Settlement, or reservation-column alteration belongs in V20.

Every organization-owned table follows the existing same-org integrity convention:

```text
PRIMARY KEY (id)
UNIQUE (id, org_id)
composite same-org FK (..., org_id) -> parent(id, org_id)
```

### 5.1 `gateway_usage_fact`

Logical schema:

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

Same-org FKs include:

```text
request_id -> gateway_request
route_attempt_id -> gateway_route_attempt
supersedes_usage_fact_id -> gateway_usage_fact
pricing_version_id -> pricing_version
```

The generated final slot enforces at most one realtime FINAL usage fact per request.

### 5.2 `gateway_usage_dimension`

Logical schema:

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

This is a Gateway-owned convenience pointer only.

Rules:

- nullable before durable metering exists;
- same-org FK to `gateway_usage_fact(id, org_id)`;
- updated only after the fact + dimensions are successfully inserted;
- never replaces append-only lineage;
- Backend may read but never write it.

---

## 6. Metering state model

Exactly three usage-fact statuses exist:

```text
FINAL
INCOMPLETE
UNKNOWN
```

### FINAL

FINAL means the Gateway has sufficient **trustworthy normalized dimensions to deterministically price the request under the exact frozen Pricing Version on the Route Attempt**.

FINAL is not merely “Provider returned a usage object.”

Publication rule:

```text
required Pricing Rate dimensions
⊆ trustworthy normalized observed dimensions
```

If any Pricing Rate dimension cannot be represented from trustworthy evidence, the fact is not FINAL.

### INCOMPLETE

INCOMPLETE means some credible billable quantities are known, but at least one quantity required for deterministic final pricing is missing or only partial.

Examples:

- partial stream usage;
- one pricing dimension missing;
- trustworthy input tokens but unknown final output tokens;
- a Provider usage structure is present but incomplete for the frozen Pricing Version.

INCOMPLETE is never settled as zero.

### UNKNOWN

UNKNOWN means Provider execution may have been billable but no sufficiently reliable normalized quantity is available.

Examples:

- post-dispatch connection/stream failure with no trustworthy usage;
- process/DB failure after Provider dispatch before usage persistence;
- malformed final usage that cannot be safely normalized.

UNKNOWN is explicit financial uncertainty, not absence of cost.

---

## 7. Usage dimension semantics

M13 supports the frozen vocabulary:

```text
INPUT_TOKEN
OUTPUT_TOKEN
CACHED_INPUT_TOKEN
REQUEST
```

Rules:

- quantities are exact `BigDecimal`/integer-derived values, never float/double;
- no dimension is inferred from an unrelated field;
- absence is not zero unless Provider semantics positively prove zero;
- reservation estimates are not actual metering evidence;
- `REQUEST=1` may be `GATEWAY_DETERMINISTIC` when the Pricing Version explicitly prices each successfully dispatched request and the Provider contract makes one billable request identity unambiguous;
- `CACHED_INPUT_TOKEN` may be FINAL only when current Provider evidence explicitly exposes a trustworthy cache-hit token count or another certified deterministic mechanism exists.

For the current MiMo integration, the default test Pricing Version contains `INPUT_TOKEN + OUTPUT_TOKEN`. Current official MiMo response examples expose `prompt_tokens + completion_tokens`, including the final streaming usage chunk, so these two dimensions can be certified for FINAL when values are valid.

Current official MiMo examples do **not** demonstrate a trustworthy cached-token count; `prompt_tokens_details` is shown empty in the documented examples. Therefore a future Pricing Version that includes `CACHED_INPUT_TOKEN` must remain INCOMPLETE unless the MiMo adapter gains explicit tested evidence for that quantity. Do not assume cache-hit quantity is zero.

---

## 8. MiMo Provider certification contract

Provider-specific wire semantics remain inside `MimoChatAdapter` and MiMo wire DTOs.

### 8.1 Non-streaming

The current MiMo response exposes:

```text
id
created
model
usage.prompt_tokens
usage.completion_tokens
usage.total_tokens
```

M13-A normalizes only fields it can prove.

`total_tokens` is a consistency/check field; financial dimensions are independently represented as input/output dimensions. Do not derive one missing component from total unless the Provider contract is explicitly certified to make that subtraction exact and safe.

### 8.2 Streaming

Current official MiMo documentation (verified 2026-09-04, page updated 2026-07-15) shows the terminal sequence:

```text
normal content/reasoning chunks
→ finish_reason chunk with usage = null
→ usage-only chunk with choices = [] and full usage
→ data: [DONE]
```

This supersedes the older M10 uncertainty that public docs had not proven final streaming usage.

M13-A must model streaming Provider output as distinct provider-neutral event kinds rather than pretending every SSE frame is a client-visible text delta. Conceptually:

```text
DELTA
METERING
DONE
```

Requirements:

- `DELTA` is forwardable downstream;
- `METERING` updates bounded metering state and is not treated as content;
- `DONE` marks protocol completion;
- no full completion buffering is introduced;
- usage/control frames remain bounded by existing event-size limits;
- downstream compatibility must not expose new unsupported client parameters merely to obtain usage.

A Provider stream that reaches `[DONE]` without sufficient usage becomes INCOMPLETE/UNKNOWN according to evidence; `[DONE]` alone does not imply zero cost.

### 8.3 Provider request id and safe metadata

Do not fabricate `provider_request_id` from an identifier whose semantics are not documented as a Provider request identity.

If MiMo exposes a trustworthy request identifier, store it exactly. Otherwise null is valid.

A bounded non-secret upstream completion identifier may be retained in `safe_provider_metadata_json` only if required for support/reconciliation and explicitly allowlisted.

The safe metadata serialized limit remains 8 KiB.

Forbidden metadata includes:

```text
prompt
completion/reasoning content
Authorization/provider API key
request headers containing secrets
raw arbitrary provider error bodies
```

---

## 9. Usage effective time

Every usage fact stores immutable:

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

For current MiMo:

- use a documented/validated Provider billing timestamp if one is later certified;
- otherwise a valid Provider request timestamp such as the documented response `created` may be `PROVIDER_REQUEST_TIMESTAMP`;
- otherwise fall back to the durable Gateway `dispatch_intent_at`.

Never use response completion time merely because it is convenient.

The persisted `gateway_request.billing_period_id` remains the normal financial period fence for M13-B. If Provider effective-time evidence maps to a different period, Settlement does not silently move cost across periods; the discrepancy is reconciliation evidence and may require `RECONCILIATION_REQUIRED` under the conditions in Part B.

---

## 10. Append-only revision semantics

Usage facts are immutable after insert.

Normal sequence:

```text
UNKNOWN/INCOMPLETE sequence 1
→ better evidence arrives
→ append sequence 2
→ supersedes_usage_fact_id = sequence 1 id
→ current_usage_fact_id = sequence 2 id
```

Rules:

- never update quantities/status of an existing fact;
- sequence is monotonically increasing per request;
- a newly appended fact must supersede the current fact when a current fact exists;
- once the current fact is FINAL, Gateway does not append another realtime usage fact;
- later Provider-statement corrections belong to M15 and do not rewrite/append a competing realtime FINAL fact;
- DB uniqueness plus application checks must converge concurrent append attempts.

Normal M13-A runtime may publish only sequence 1; the revision contract must still be tested because M13-B/recovery depends on it.

---

## 11. Gateway local finalization boundary

No external Provider operation runs inside a DB transaction.

After Provider I/O produces a terminal transport result, M13-A uses a narrow local MySQL transaction to converge durable metering and Gateway lifecycle state whenever possible.

For successful non-streaming or clean streaming completion:

```text
BEGIN local Gateway DB tx
→ validate request/route frozen lineage
→ append usage fact
→ append dimensions
→ update gateway_request.current_usage_fact_id
→ mark route attempt COMPLETED
→ mark request TRANSPORT_COMPLETED
COMMIT
```

For post-dispatch terminal failure/cancel/timeout when safe usage evidence exists:

```text
BEGIN local Gateway DB tx
→ append FINAL / INCOMPLETE / UNKNOWN fact as supported by evidence
→ update current_usage_fact_id
→ persist the matching post-dispatch request terminal state
→ keep route BILLABLE_POSSIBLE unless exact existing lifecycle semantics allow COMPLETED
→ ACTIVE reservation becomes PENDING_HOLD when financial outcome remains unresolved
COMMIT
```

If local persistence fails after Provider dispatch, Gateway must not issue another Provider request. Durable request/route state left from the dispatch fence is already sufficient to classify the request as possible-billable; Close remains blocked and recovery/reconciliation can converge later.

This accepted external-side-effect gap is handled by explicit uncertainty, not by pretending to have distributed transactions.

---

## 12. Reactive / blocking boundary

All MyBatis/JDBC work remains off the Reactor Netty event loop.

M13-A uses the existing `BlockingIoScheduler` boundary and a transaction component (`TransactionTemplate` or equivalent existing project pattern) for local usage finalization.

Do not:

```text
.block() on the event loop
self-invoke an @Transactional method expecting a transaction
re-dispatch the same blocking operation onto the same bounded scheduler and block waiting for itself
```

This preserves the M12 deadlock/self-invocation fixes.

---

## 13. M13-A reservation consequence

M13-A does not FINALIZE reservations.

Rules:

- successful transport + FINAL usage: reservation may remain ACTIVE until M13-B settles it;
- INCOMPLETE/UNKNOWN or other possible-billable uncertainty: Gateway moves an ACTIVE reservation to PENDING_HOLD when the terminal path can do so safely;
- proven pre-dispatch no-provider-call paths retain M12 RELEASED behavior;
- missing usage never causes RELEASED by itself.

---

# Part B — M13-B Settlement Design

## 14. V21 schema wave

M13-B adds exactly one migration:

```text
backend/src/main/resources/db/migration/V21__m13_gateway_settlement.sql
```

V21 creates:

```text
gateway_settlement
```

and forward-alters Ledger for first-class realtime settlement lineage.

V21 does not alter M13-A usage facts except by adding required foreign keys from new Backend-owned structures.

### 14.1 `gateway_settlement`

Logical schema:

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

States:

```text
PENDING
RETRYABLE_FAILED
RECONCILIATION_REQUIRED
SETTLED
```

`SETTLED` requires deterministic amounts, `ledger_posting_id`, and `settled_at`.

Exact keys:

```text
settlement_key = GATEWAY_REQUEST:<public_request_id>
ledger posting_key = GATEWAY_SETTLEMENT:<decimal settlement id>
```

No persistent `PROCESSING` truth is introduced.

---

## 15. Settlement eligibility and discovery

Normal realtime settlement requires:

```text
gateway_request.current_usage_fact_id -> current usage fact
current usage fact.status = FINAL
no existing terminal Settlement for the request
```

Discovery is DB-backed and bounded. A notification may wake a worker later, but correctness never depends on a message broker or notification delivery.

Conceptual loop:

```text
1. discover bounded current FINAL facts lacking a Settlement
2. insert PENDING Settlement using business uniqueness
3. duplicate insert converges to the existing row
4. discover bounded PENDING / eligible RETRYABLE_FAILED settlement ids
5. process each using the global financial lock order
```

Important lock-order correction versus older provisional wording:

> The worker must **not** take a long-lived Settlement `FOR UPDATE` claim before acquiring BillingPeriod/Budget/Reservation locks, because that would invert the project-wide financial lock order.

Worker discovery may read/select candidate IDs without holding the financial transaction lock. Duplicate workers converge under deterministic database uniqueness and the source-lock order below.

---

## 16. Frozen pricing lineage

Settlement never asks “what is the current price?”

It re-reads and cross-validates immutable lineage:

```text
Settlement.route_attempt_id
→ gateway_route_attempt.provider_account_id
→ gateway_route_attempt.provider_model_id
→ gateway_route_attempt.pricing_version_id

UsageFact.route_attempt_id must match
UsageFact.pricing_version_id must match
UsageFact.currency must match frozen Pricing Version currency
```

Any mismatch is a semantic integrity failure and must not be silently repaired by selecting another price. It becomes `RECONCILIATION_REQUIRED` or an integrity failure according to whether the condition is recoverable without external truth.

Pricing Versions already referenced by Route Attempts remain immutable under the existing catalog contract.

---

## 17. Cost calculation

For every Pricing Rate in the frozen Pricing Version:

```text
dimension_raw_cost
= exact_quantity
  * exact_unit_price
  / exact_unit_quantity
```

Requirements:

- Java `BigDecimal` only;
- no float/double;
- every priced dimension must exist on the FINAL usage fact;
- unpriced/unknown required data never becomes zero;
- sum raw dimension cost at high precision compatible with `DECIMAL(38,18)`;
- validate supported range before posting.

### 17.1 Accounting quantization

For positive incurred cost:

```text
posted_amount
= calculated_amount_raw quantized UP / away from zero to scale 8
```

Store:

```text
calculated_amount_raw
posted_amount
rounding_delta = posted_amount - calculated_amount_raw
```

A non-zero positive Provider cost must never become `0.00000000` due to accounting quantization.

Negative realtime Provider credits remain out of M13 scope unless explicitly enabled by a later design; M13 does not opportunistically accept them.

---

## 18. Budget and reservation lineage

If `reservation_id` is non-null, Settlement does **not** re-run Budget selection.

It must use the exact request-time governed binding:

```text
reservation.budget_id
reservation.billing_period_id
reservation.financial_scope_type
reservation.financial_scope_id
reservation.currency
```

and verify it matches Gateway Request / Settlement lineage.

This prevents request-time PROJECT/TEAM/COST_CENTER governance from being reinterpreted during financial posting.

For a M12 OPTIONAL request that was explicitly allowed unbudgeted:

```text
reservation_id = NULL
Ledger still posts full incurred cost
Budget Actual mutation = none
```

Already incurred cost is never erased because no Budget exists.

---

## 19. Global Settlement financial lock order

Normal M13-B settlement uses one deterministic MySQL lock order:

```text
1. BillingPeriod
2. Budget (if reservation/budget exists)
3. explicitly-bound Commitment (if any)
4. BudgetReservation (if any)
5. GatewaySettlement
6. Ledger posting convergence / immutable insert seam
```

Within a category containing multiple ids, lock sorted ascending ids. Current M13 creates one primary financial target, so the normal Gateway path uses at most one Budget and one explicit Commitment.

Do not acquire Reservation before Budget. Do not acquire Settlement before BillingPeriod. Do not introduce a worker claim lock that inverts this order.

This order is compatible with the existing project principle:

```text
BillingPeriod
→ sorted Budgets
→ sorted Commitments
→ Gateway financial source
→ Ledger
```

---

## 20. Atomic Settlement financial transaction

For one normal PENDING/eligible RETRYABLE_FAILED Settlement:

```text
BEGIN
1. lock persisted BillingPeriod through BillingPeriodFinancialWriteFence
2. verify the period is OPEN under the same durable row used by Close
3. lock bound Budget when reservation exists
4. lock explicit Commitment when reservation.commitment_id exists
5. lock bound BudgetReservation when reservation exists
6. lock GatewaySettlement
7. re-read/cross-validate immutable Request / Route Attempt / Usage Fact / Pricing Version / Reservation lineage
8. converge on existing Ledger posting by stable posting_key
9. deterministically calculate raw + posted amount
10. insert SYSTEM Ledger posting
11. insert exactly one Gateway Settlement Ledger entry
12. increment Budget actual by the full posted amount when a Budget exists
13. consume only an explicitly-bound Commitment using existing V1 semantics
14. write financial audit event
15. transition bound reservation ACTIVE/PENDING_HOLD -> FINALIZED, set finalized_at, increment version
16. set gateway_settlement SETTLED + amounts + ledger_posting_id + settled_at
COMMIT
```

No Redis operation and no Provider call occurs inside this transaction.

### 20.1 Reservation overrun

If:

```text
posted_amount > reserved_amount
```

Settlement still posts the **full actual incurred amount**.

Consequences may include:

```text
Budget actual exceeds total Budget
reservation_overrun metric/audit flag = true
Commitment only consumes its remaining amount
```

But the Ledger/Actual post is not rejected. Reservation was an authorization estimate, not a cap on already incurred financial truth.

### 20.2 Reservation FINALIZED atomicity

Reservation finalization is part of the same transaction as:

```text
Ledger insertion
Budget Actual mutation
Commitment mutation if applicable
Audit
Settlement SETTLED
```

Therefore after commit there is no normal state in which:

```text
Budget Actual already includes the charge
AND
an ACTIVE/PENDING_HOLD reservation for the same Settlement still reduces availability
```

If the transaction rolls back, all of those changes roll back together.

---

## 21. Ledger forward contract

M13-B extends:

```text
LedgerSourceType += GATEWAY_SETTLEMENT
```

V21 forward schema semantics:

```text
ledger_posting.source_type allows GATEWAY_SETTLEMENT
ledger_posting.posting_actor_type VARCHAR(16) NOT NULL
posting_actor_type IN (MEMBER, SYSTEM)
existing rows backfilled MEMBER
ledger_posting.posted_by_member_id becomes nullable
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
exactly one financial target column corresponding to PROJECT / TEAM / COST_CENTER
budget_id = bound Budget id when one exists, otherwise NULL
```

The direct-primary-source integrity check becomes “at most one direct primary source” across Provider Charge / Expense Claim / Gateway Settlement because correction entries may preserve original lineage under existing correction rules.

Do not synthesize V1 Provider charge/allocation objects merely to reuse `ProviderChargePostingService`.

M13 should add a narrow Gateway Settlement posting orchestration while reusing the proven Budget/period/Ledger seams.

---

## 22. SYSTEM audit semantics

`audit_event.actor_user_id` is already nullable and can represent system activity; do not invent a fake organization member.

Representative event:

```text
GATEWAY_SETTLEMENT_POSTED
```

Safe metadata may include bounded ids/values such as:

```text
request id
settlement id
usage fact id
provider account/model ids
pricing version id
financial scope type/id
posted amount/currency
reservation overrun boolean
```

Never include prompt/completion/raw credentials/arbitrary upstream bodies.

Audit persistence participates in the same financial transaction. Audit failure rolls back the settlement financial mutation.

---

## 23. Commitment handling

M13 does not infer or create Commitment bindings.

Current M12 reservation rows have:

```text
commitment_id = NULL
commitment_backed_amount = 0
```

If a future/explicitly-created reservation contains a non-null governed `commitment_id`, Settlement may consume it only after locking and validating:

```text
same org
compatible selected Budget
consumable status
explicit binding present on the reservation
```

Consumption uses existing V1 semantics:

```text
consumed = min(posted amount, remaining commitment amount)
```

Full incurred cost still posts even if the Commitment is exhausted.

No unrelated Commitment search/inference is permitted.

---

## 24. Settlement retry and idempotency

Database/business uniqueness is the authority.

Replay rules:

```text
one Gateway Request -> at most one realtime Settlement
one FINAL Usage Fact -> at most one realtime Settlement
one Settlement -> at most one Ledger posting key
one committed Settlement transaction -> one Budget Actual increment
```

If a transaction committed but the worker lost the result:

```text
retry reads SETTLED / existing posting
→ converges
→ no second Ledger entry
→ no second Budget increment
→ no second Commitment consumption
→ no second reservation finalization
→ no duplicate audit event
```

Transient deadlock/serialization/dependency failures may become:

```text
RETRYABLE_FAILED
attempt_count += 1
next_attempt_at = bounded backoff
last_error_code = bounded enum/code
```

Failure recording occurs only after the failed financial transaction has rolled back.

No stack trace or secret-bearing free-form Provider body is stored in Settlement.

---

## 25. `RECONCILIATION_REQUIRED`

Automatic realtime posting stops instead of inventing financial truth when deterministic safe completion is impossible.

Representative conditions:

```text
persisted BillingPeriod is already CLOSED due legacy/manual/historical inconsistency
Usage Fact / Route Attempt / Pricing lineage conflicts
FINAL fact cannot actually satisfy the frozen Pricing Version
amount outside supported accounting representation/policy
financial effective-time evidence conflicts with immutable period context in a way requiring external review
unexpected explicit Commitment lineage conflict
```

No Ledger posting is manufactured to hide the conflict.

M15 or explicit existing reopen/correction governance resolves external-truth differences later.

---

## 26. Close blocker after M13-B

M13-B refines `PENDING_GATEWAY_FINANCIAL_WORK` rather than adding a new enum.

Normal Close is blocked when any same-period Gateway request has unresolved financial work, including:

```text
post-DISPATCH_INTENT request with no current usage fact
current usage status INCOMPLETE
current usage status UNKNOWN
current FINAL usage fact with no Settlement
Settlement PENDING
Settlement RETRYABLE_FAILED
Settlement RECONCILIATION_REQUIRED
ACTIVE or PENDING_HOLD reservation that has not been atomically finalized/released
```

A request may be transport-failed yet financially resolved if it has a trustworthy FINAL fact and SETTLED Settlement. Transport state alone must no longer block after M13-B when durable financial truth is terminal.

`RELEASED` and `FINALIZED` reservations do not block by themselves.

### 26.1 Close/Settlement serialization

Settlement and Close use the exact same durable BillingPeriod row/fence.

Valid race outcomes:

```text
Settlement locks period first
→ settles + FINALIZES reservation
→ Close later scans and may pass

Close locks period first
→ unresolved Gateway blocker prevents Close
or period becomes unavailable before a legacy settlement starts
→ Settlement does not bypass/reopen it and converges to safe failure/reconciliation handling
```

Forbidden outcome:

```text
period CLOSED
AND
a newly committed normal Settlement/dispatch financial mutation bypassed the shared fence
```

M13-B must include a real MySQL concurrency test for Settlement vs Close in addition to the existing Dispatch vs Close coverage.

---

## 27. Security and data minimization

M13 never persists:

```text
prompt
completion
reasoning content
raw Gateway API key
Provider API key
Authorization header
unbounded Provider response/error payload
```

Usage facts contain only bounded financial/operational observations.

Settlement errors use bounded error codes.

Tenant isolation is enforced by same-org FKs and org-qualified mapper predicates.

Provider secret decryption remains only in Gateway Provider-call code and never enters Backend Settlement.

---

## 28. Metrics and observability

All metric labels remain bounded catalog/enumeration values.

Recommended M13-A metrics:

```text
gateway_usage_total{status}
gateway_metering_incomplete_total{provider_code,reason_code}
gateway_metering_unknown_total{provider_code,reason_code}
gateway_provider_usage_parse_error_total{provider_code}
```

Recommended M13-B metrics:

```text
gateway_settlement_total{status}
gateway_settlement_retry_total{reason_code}
gateway_settlement_reconciliation_required_total{reason_code}
gateway_reservation_overrun_total{provider_code}
```

Never label metrics by:

```text
request id
org id
credential id
provider request id
free-form model string
```

Existing bounded `provider_code` semantics may be reused.

---

## 29. Public API behavior

M13 does not expand the accepted inference request financial surface.

Client still may not submit:

```text
project/team/cost-center ids
budget id
currency
pricing version id
provider account/model id
reservation id
```

All of those remain server-governed/frozen.

Missing realtime usage is not rewritten into a public zero-usage object. Existing response compatibility behavior may continue to return `usage = null` when Provider usage is absent.

No financial amount needs to be added to the public Gateway status API in M13. If state projection is extended for operations, it may expose bounded state-only fields such as metering/settlement status, but must not expose secrets or reservation/financial internals by default.

---

## 30. M13-A verification contract

M13-A must prove at least:

### Schema

- V20 is the only new migration in M13-A;
- V1-V19 unchanged;
- same-org FK violations fail;
- sequence uniqueness enforced;
- FINAL uniqueness enforced;
- typed dimension uniqueness/checks enforced;
- `current_usage_fact_id` same-org integrity enforced.

### Non-streaming metering

- exact prompt/output usage -> FINAL for the default INPUT+OUTPUT Pricing Version;
- missing usage -> UNKNOWN/INCOMPLETE according to available evidence, never zero;
- malformed usage -> explicit non-FINAL result / safe failure classification;
- Provider model/pricing lineage copied from the frozen Route Attempt, not re-resolved;
- response `created` provenance handled per section 9;
- no prompt/completion persisted in usage tables or safe metadata.

### Streaming metering

- content chunks continue streaming incrementally;
- documented usage-only chunk is parsed as metering, not text content;
- full valid terminal usage + `[DONE]` -> FINAL;
- `[DONE]` without required usage -> INCOMPLETE/UNKNOWN, never zero;
- disconnect before final usage -> non-FINAL;
- malformed usage event -> non-FINAL and no fabricated dimensions;
- no full stream buffering;
- client cancellation does not release a reservation merely because the client disconnected.

### Pricing-dimension classification

- default INPUT+OUTPUT rates with exact usage -> FINAL;
- add a test `CACHED_INPUT_TOKEN` Pricing Rate without certified cached quantity -> INCOMPLETE;
- add `REQUEST` rate only when deterministic request quantity is supported; otherwise classification is conservative.

### Append-only/revision

- INCOMPLETE -> later FINAL appends sequence 2 and supersedes sequence 1;
- old fact unchanged;
- concurrent append converges;
- second FINAL rejected by DB/application contract;
- no append after current FINAL through the normal realtime publisher.

### Lifecycle/failure

- usage fact + terminal lifecycle commit together on normal local finalization;
- post-dispatch DB failure never triggers another Provider dispatch;
- unresolved possible-billable path remains close-blocking;
- uncertain reservation becomes/remains PENDING_HOLD conservatively.

### Runtime architecture

- all blocking MyBatis/JDBC off Reactor event loop;
- no `.block()` in request path;
- no transactional self-invocation trap;
- architecture tests enforce Gateway does not write Ledger/Budget Actual/Settlement.

---

## 31. M13-B verification contract

M13-B must prove at least:

### Schema

- V21 is the only new migration in M13-B;
- V1-V20 unchanged;
- Settlement request/usage uniqueness enforced;
- Ledger source check accepts GATEWAY_SETTLEMENT;
- existing Ledger rows backfill MEMBER actor correctly;
- SYSTEM/MEMBER actor consistency enforced;
- Gateway Settlement entry same-org lineage enforced.

### Frozen pricing

- Settlement uses Route Attempt Pricing Version even when a newer ACTIVE Pricing Version exists;
- missing/unmatched dimension cannot silently price as zero;
- raw precision and scale-8 upward quantization are exact;
- tiny non-zero raw cost becomes at least `0.00000001`;
- rounding delta is durable.

### Financial transaction

- Ledger posting + one entry + Budget Actual + Audit + Reservation FINALIZED + Settlement SETTLED commit atomically;
- injected failure at each seam rolls the transaction back with no partial durable financial mutation;
- `posted_amount > reserved_amount` posts full Actual and marks overrun evidence;
- OPTIONAL unbudgeted FINAL usage still posts Ledger with no Budget mutation;
- no Budget re-selection occurs during Settlement;
- explicit Commitment path consumes only bound Commitment, never inferred Commitment.

### Idempotency/concurrency

- concurrent duplicate Settlement workers produce one Settlement, one Ledger posting, one Ledger entry, one Budget increment, one Audit, one reservation finalization;
- retry after simulated lost response converges to existing SETTLED result;
- lock ordering is BillingPeriod -> Budget -> Commitment -> Reservation -> Settlement -> Ledger;
- real MySQL test covers Settlement vs Close;
- no Reservation->Budget lock inversion.

### Close blocker

- no usage fact blocks;
- INCOMPLETE/UNKNOWN blocks;
- FINAL without settlement blocks;
- PENDING/RETRYABLE_FAILED/RECONCILIATION_REQUIRED blocks;
- SETTLED + FINALIZED releases Gateway financial blocker;
- transport failure + exact FINAL + SETTLED is financially terminal and does not remain blocked solely by transport state.

### Security/observability

- no prompt/completion/raw secret in Usage Fact/Settlement/Audit/logs;
- bounded metric labels only;
- Backend cannot decrypt/read Provider credentials as part of Settlement;
- Gateway architecture tests continue to forbid Ledger/Budget Actual/Settlement writes.

---

## 32. Deployment sequencing

### M13-A deploy

```text
1. deploy Backend migration runner with V20
2. verify V20 applied
3. deploy Gateway M13-A code
```

Gateway code must never assume V20 exists before the Backend migration wave has run.

### M13-B deploy

```text
1. deploy Backend with V21 + Settlement code
2. V21 applies before Settlement worker becomes active
3. Settlement discovery begins from durable FINAL usage facts
```

M13-B may settle FINAL facts accumulated during the M13-A-only interval.

No destructive data backfill is required. Existing historical M11/M12 requests without FINAL usage remain unresolved and close-blocking until explicit recovery/reconciliation evidence exists; M13 must not fabricate historical zero usage.

---

## 33. Non-scope

Explicitly deferred:

```text
M14 generic routing administration
M14 automatic failover/retry after safe-no-billable evidence
multi-Provider settlement abstraction beyond the narrow MiMo vertical slice
M15 Provider statement hybrid reconciliation
FX / cross-currency conversion
negative realtime Provider credits
split allocation of one Gateway request across multiple financial targets
new Commitment selection/binding policy
message-broker correctness dependency
prompt/completion persistence
historical estimation that converts unknown usage into fake actual cost
```

---

## 34. Superseded provisional decisions

This spec intentionally supersedes two older provisional directions where the current M13 correctness requirement is stronger.

### 34.1 One M13 migration wave is split into V20 + V21

Older design described the M13 schema as one conceptual migration wave. M13 implementation is now split operationally:

```text
V20 = usage fact + dimensions + request pointer
V21 = settlement + Ledger forward extension
```

This preserves the same final schema while giving M13-A and M13-B independently reviewable deployment boundaries.

### 34.2 Reservation finalization is atomic in Backend Settlement

Older AIC-089 wording allowed:

```text
Backend posts Settlement/Actual
→ effective reservation query excludes SETTLED
→ Gateway later FINALIZES reservation
```

The final M13 decision is stricter:

```text
Backend Settlement tx
→ Ledger + Actual + Audit + Reservation FINALIZED + Settlement SETTLED
→ one commit
```

The Backend permission is limited to this one Settlement-bound transition and does not transfer general reservation ownership.

---

## 35. External Provider evidence used for M13-A certification

Verified on 2026-09-04 against Xiaomi MiMo official documentation:

```text
https://platform.xiaomimimo.com/docs/en-US/usage-guide/passing-back-reasoning_content
```

The documented streaming example shows a usage-only terminal chunk with:

```text
choices: []
usage.prompt_tokens
usage.completion_tokens
usage.total_tokens
```

followed by:

```text
data: [DONE]
```

The same documentation shows non-streaming usage fields. Current examples show `prompt_tokens_details` but do not demonstrate a cached-token quantity, so M13 does not certify `CACHED_INPUT_TOKEN` from those examples.

Provider pricing pages currently distinguish cache-hit vs cache-miss input pricing, which reinforces why cached usage must not be guessed as zero when a Pricing Version models it explicitly.

---

## 36. Definition of Done

M13 is complete only when both implementation waves are merged and the following are true:

```text
[freeze] Provider usage becomes immutable durable Gateway facts
[freeze] FINAL / INCOMPLETE / UNKNOWN are the only metering classifications
[freeze] missing usage never defaults to zero
[freeze] streaming final usage is parsed without buffering completion content
[freeze] frozen Route Attempt Pricing Version is the only realtime settlement price
[freeze] Settlement is Backend/CostOps-owned and DB-backed
[freeze] Ledger source is first-class GATEWAY_SETTLEMENT
[freeze] SYSTEM actor semantics replace fake-member posting
[freeze] full incurred actual posts even when actual > reservation
[freeze] Budget is never re-selected during Settlement when a reservation exists
[freeze] Ledger + Budget Actual + Audit + Reservation FINALIZED + Settlement SETTLED are atomic
[freeze] duplicate/retry paths never double-post Ledger or Budget Actual
[freeze] unresolved usage/settlement continues to block Close
[freeze] Close and Settlement serialize on the same BillingPeriod fence
[freeze] no prompt/completion/raw secrets are persisted for metering/settlement
[freeze] M13-A and M13-B each have independently green focused + full CI/security evidence
```
