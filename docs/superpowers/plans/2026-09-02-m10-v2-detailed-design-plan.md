# M10 V2 Detailed Design Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Freeze the V2 Gateway design so M11-M15 implementation can proceed without unresolved ownership, financial correctness, API, data-model, Redis, retry, privacy, or recovery decisions.

**Architecture:** Keep the approved monorepo with a Spring MVC Control Plane and a Spring WebFlux/Reactor Netty Gateway Data Plane. MySQL remains the durable financial system of record; Gateway owns durable request/usage facts and MySQL-authoritative short-lived budget reservations, while CostOps Core owns final Settlement/Ledger/Budget actual/Commitment/Period truth. Redis remains recoverable runtime coordination and never becomes the source of financial authorization.

**Tech Stack:** Java 21; existing Spring Boot 4.1.0 project baseline; Spring MVC Control Plane; Spring WebFlux + Reactor Netty Gateway direction; synchronous MyBatis/JDBC financial persistence offloaded from Netty event-loop threads; MySQL 8.4; Redis; Flyway owned by Control Plane deployment; MinIO/S3 Evidence; OpenAPI 3.1; JUnit/Testcontainers/ArchUnit; Docker Compose/GitHub Actions for later implementation evidence.

**Spec:** `docs/superpowers/specs/2026-09-02-m10-v2-detailed-design-program.md` plus normative independent review `docs/superpowers/specs/2026-09-02-m10-independent-architecture-review.md`

## Global Constraints

- V1/v1.1.0 remains RELEASED/FROZEN; do not move or recreate the `v1.1.0` tag.
- Local repository path is `E:\project\AI-CostOps`.
- M10 is documentation/design only: no Gateway runtime code, Backend/Frontend feature code, Flyway production migration, runtime dependency upgrade, Redis Lua production script, or Provider implementation.
- MySQL remains durable identity and financial truth.
- POSTED Ledger stays immutable; corrections remain append-only.
- Already-incurred Provider cost must never become zero merely because budget, Redis, MySQL-after-dispatch, routing, streaming, or settlement failed.
- Gateway must not directly mutate Ledger, Budget actual, Commitment usage, Period close state, or final Settlement state.
- CostOps Core must not rewrite Gateway request/usage facts.
- Budget Reservation correctness is MySQL-authoritative; Redis may accelerate/coordinate but cannot authorize spend by itself.
- Gateway request/usage persistence and final Settlement/Ledger persistence use explicit single-writer table ownership.
- Gateway blocking DB work must not execute on Reactor Netty event-loop threads.
- Control Plane/backend deployment is the single Flyway migration owner.
- No automatic FX engine is introduced; budget-controlled realtime amounts require same-currency semantics.
- Prompt/Completion content is not persisted by default and is not written to ordinary log/audit/metrics.
- Do not introduce Kafka, RabbitMQ, Kubernetes, service mesh, R2DBC, or a new language without measured evidence and a separate approved design change.
- All Windows commands in handoff/execution docs use PowerShell.

---

## File Structure / Delivery Map

M10 is one documentation branch and one principal PR. Stable IDs remain AIC-084 through AIC-093.

| Stable ID | Delivery | Primary files |
|---|---|---|
| AIC-084 | Scope/runtime ownership | `docs/02-development/v2-detailed-design/README.md`, `01-scope-runtime-boundary.md` |
| AIC-085 | Credentials/catalog/pricing | `02-credentials-catalog-pricing.md` |
| AIC-086 | Identity/idempotency/request states | `03-request-state-machine.md` |
| AIC-087 | Reservation/Redis correctness | `04-budget-redis-atomicity.md` |
| AIC-088 | Provider/streaming/metering | `05-provider-streaming-metering.md` |
| AIC-089 | Settlement/financial boundary | `06-settlement-financial-boundary.md` |
| AIC-090 | Routing/resilience | `07-routing-resilience.md` |
| AIC-091 | Security/observability/deployment | `08-security-observability-deployment.md` |
| AIC-092 | Data/API/migration/testing | `09-data-api-migration-testing.md`, `docs/02-development/api/README.md`, `docs/02-development/api/gateway-openapi.yaml` |
| AIC-093 | Final freeze | `docs/03-acceptance/m10-design-freeze-matrix.md`, then milestone-state docs only after acceptance |

---

### Task 1 / AIC-084: Freeze scope, runtime, DB and ownership boundaries

**Files:**
- Create: `docs/02-development/v2-detailed-design/README.md`
- Create: `docs/02-development/v2-detailed-design/01-scope-runtime-boundary.md`

**Interfaces:**
- Consumes: V1 modules and the approved Control Plane/Data Plane direction.
- Produces: table ownership, runtime dependency direction, DB-access rules, Flyway ownership, deployable responsibilities, and milestone boundary used by every later M10 document.

- [ ] **Step 1: Write the V2 detailed-design index**

The README must state the authority hierarchy:

```text
V1 invariants
→ M10 detailed design
→ machine-readable API contracts
→ M11+ implementation plans
```

It must list AIC-084..093 and mark every detailed-design file as design-only until AIC-093 freeze.

- [ ] **Step 2: Freeze runtime ownership**

Document exactly:

```text
backend / Control Plane owns:
IAM, organization, admin, catalog administration, budget administration,
final Settlement, Ledger, Budget actual, Commitment usage, Period Close,
Reconciliation, Audit query/reporting.

gateway / Data Plane owns:
OpenAI-compatible edge, Gateway credential auth, request identity,
rate/quota runtime checks, budget reservation orchestration,
provider dispatch/streaming, usage capture, durable request/usage facts,
routing runtime state and Gateway metrics.
```

- [ ] **Step 3: Freeze MySQL table single-writer ownership**

Required direction:

```text
Gateway writer:
- gateway_request
- gateway_usage_fact
- budget_reservation

CostOps Core writer:
- gateway_settlement
- ledger_posting / ledger_entry
- budget.actual_amount
- budget_commitment_usage
- billing_period close/reopen state
```

Reads across boundaries are allowed only through narrow documented repository/port contracts.

- [ ] **Step 4: Freeze DB runtime strategy**

Document:

```text
Gateway uses bounded synchronous JDBC/MyBatis transaction seams.
Blocking MySQL operations are offloaded from Reactor Netty event-loop threads.
R2DBC is not introduced in V2 Core without measured need.
DB concurrency is bounded by connection pool + scheduler.
```

- [ ] **Step 5: Freeze migration/deployment ownership**

Document that backend/Control Plane deployment is the sole production Flyway runner. Gateway DB credentials are least-privilege and cannot write financial truth tables.

- [ ] **Step 6: Verify against current main implementation**

Check the document explicitly references/aligns with:

```text
ProviderChargePostingService
LedgerBudgetPort / LedgerBudgetService
CloseBlockerProvider
LedgerSourceType
V13 Ledger schema
```

Expected: no claim that the current V1 implementation already supports Gateway Settlement.

- [ ] **Step 7: Commit AIC-084 docs**

Commit message:

```text
docs(m10): freeze scope and runtime ownership
```

---

### Task 2 / AIC-085: Freeze credential, principal, Provider/model and pricing contracts

**Files:**
- Create: `docs/02-development/v2-detailed-design/02-credentials-catalog-pricing.md`

**Interfaces:**
- Consumes: AIC-084 ownership boundary.
- Produces: credential/principal/catalog/pricing context consumed by request identity, reservation, metering and routing.

- [ ] **Step 1: Define Gateway Principal and Credential separately**

Freeze a minimal principal model:

```text
HUMAN_MEMBER -> organization_member
SERVICE      -> dedicated governed service identity
```

Gateway Credential is an authentication secret bound to one principal; the credential itself is not the business identity.

- [ ] **Step 2: Define Gateway Credential lifecycle**

Required fields/semantics include prefix, hash/digest, principal binding, org/project binding, financial-scope policy, status, expiry, rotation, revoke, last-used metadata. Raw key is returned once only.

- [ ] **Step 3: Define Provider Credential handling**

Freeze encrypted/external-secret-reference storage, provider account binding, rotation, redaction and audit. Plaintext DB storage is forbidden.

- [ ] **Step 4: Define Provider and Model Catalog**

Separate logical model identity from Provider model id. Include capability and routing eligibility needed by M11-M14 without inventing a generic policy DSL.

- [ ] **Step 5: Define immutable Pricing Version**

Include effective interval, provider/model, currency and explicit pricing dimensions such as input/output/cached input/request fees where supported. Used versions are immutable; correction requires a new version.

- [ ] **Step 6: Freeze no-FX rule**

Realtime budget authorization and Settlement use the Pricing Version currency. No implicit FX conversion exists in V2 Core.

- [ ] **Step 7: Commit**

```text
docs(m10): define credentials catalog and pricing
```

---

### Task 3 / AIC-086: Freeze identity, financial scope, idempotency and request state machine

**Files:**
- Create: `docs/02-development/v2-detailed-design/03-request-state-machine.md`

**Interfaces:**
- Consumes: AIC-084 runtime ownership and AIC-085 identity/catalog models.
- Produces: immutable request identity, financial scope, dispatch fence and replay rules used by reservation, metering and settlement.

- [ ] **Step 1: Separate request context from financial scope**

Freeze:

```text
request context: org + project + optional team/cost-center + principal + credential
financial target: exactly one PROJECT | TEAM | COST_CENTER
```

Default financial target is Project unless a governed credential policy selects another allowed target.

- [ ] **Step 2: Define request/business ids**

Specify request id, trace id, client idempotency key and keyed/HMAC request fingerprint. Prompt text is not persisted.

- [ ] **Step 3: Define durable dispatch-intent fence**

The request must persist a durable `UPSTREAM_DISPATCH_INTENT` or equivalent state before potentially billable Provider I/O.

- [ ] **Step 4: Define request states and transitions**

At minimum include pre-dispatch reject states, RESERVED, durable dispatch intent, streaming/response, FINAL/INCOMPLETE/UNKNOWN metering outcome. Settlement states stay in AIC-089, not this enum.

- [ ] **Step 5: Define billing idempotency**

Same key + different fingerprint is conflict. Same key at/after dispatch fence never creates a new Provider call. Response replay is explicitly not guaranteed when response content is not retained.

- [ ] **Step 6: Define crash/recovery classification**

Crash after dispatch intent but before usage persistence becomes possible-billable orphan, never zero-cost.

- [ ] **Step 7: Commit**

```text
docs(m10): define request identity and state machine
```

---

### Task 4 / AIC-087: Freeze Budget Reservation and Redis atomicity

**Files:**
- Create: `docs/02-development/v2-detailed-design/04-budget-redis-atomicity.md`

**Interfaces:**
- Consumes: financial scope, pricing context and request identity.
- Produces: authoritative reserve/release/finalize contract consumed by dispatch and Settlement.

- [ ] **Step 1: Define durable `budget_reservation` model and state machine**

Use MySQL as authority. Include ACTIVE, PENDING_HOLD, FINALIZED/RELEASED/EXPIRED-safe semantics without assuming TTL means no cost.

- [ ] **Step 2: Freeze the reserve transaction**

Use deterministic lock order compatible with V1 financial locks. Lock selected Budget before computing:

```text
Realtime Available
= total - actual - outstanding commitments - durable active reservations
```

- [ ] **Step 3: Freeze budget selection**

Reuse V1 semantics:

```text
exact financial scope + currency
→ ORG + same currency fallback
→ none
```

Strict budget-required requests reject if none; explicitly unbudgeted-allowed requests may proceed without reservation.

- [ ] **Step 4: Freeze reservation estimate**

Define conservative upper-bound calculation from Pricing Version plus enforceable request limits. Unknown/unbounded cost under strict policy fails closed.

- [ ] **Step 5: Freeze overrun behavior**

Final actual above reservation still posts in full; emit explicit overrun/reconciliation signal and allow Budget to become overBudget.

- [ ] **Step 6: Freeze explicit Commitment binding**

No inferred consumption. Optional commitment id is validated against selected Budget and existing V1 consumption semantics.

- [ ] **Step 7: Freeze Redis Lua/atomicity scope**

Redis Lua may atomically implement rate/quota/idempotency coordination or expiry hints. It must not be the authoritative Budget reservation arithmetic.

- [ ] **Step 8: Define fencing/recovery**

Stale workers cannot release/finalize another reservation. Possible-billable requests retain conservative holds until durable Settlement/reconciliation gives a safe release decision.

- [ ] **Step 9: Commit**

```text
docs(m10): define reservation and redis atomicity
```

---

### Task 5 / AIC-088: Freeze Provider Adapter, streaming and metering semantics

**Files:**
- Create: `docs/02-development/v2-detailed-design/05-provider-streaming-metering.md`

**Interfaces:**
- Consumes: model/pricing context and request state.
- Produces: provider-neutral usage facts and billing-effective timestamp consumed by Settlement/reconciliation.

- [ ] **Step 1: Define Provider Adapter boundary**

Provider-specific wire request/response/usage semantics stay inside adapters. Adapters never post financial Ledger entries.

- [ ] **Step 2: Define streaming failure taxonomy**

Cover client disconnect, Provider disconnect, malformed SSE, missing final usage, connect/header/idle/hard timeouts and cancel propagation.

- [ ] **Step 3: Define metering outcome**

Exactly:

```text
FINAL
INCOMPLETE
UNKNOWN
```

Missing usage cannot silently become zero.

- [ ] **Step 4: Define append-only usage fact/revision semantics**

A FINAL fact is immutable. Later Provider evidence or corrections append a superseding/correction lineage rather than destructive rewrite.

- [ ] **Step 5: Freeze `usage_effective_at`**

Define Provider-authoritative timestamp when available, otherwise documented dispatch-boundary fallback. This timestamp drives pricing version, BillingPeriod and reconciliation matching.

- [ ] **Step 6: Preserve hybrid reconciliation keys**

Keep safe bounded provider account, provider request id, model, effective time, normalized usage and settlement correlation ids.

- [ ] **Step 7: Commit**

```text
docs(m10): define provider streaming and metering
```

---

### Task 6 / AIC-089: Freeze Settlement and financial posting boundary

**Files:**
- Create: `docs/02-development/v2-detailed-design/06-settlement-financial-boundary.md`

**Interfaces:**
- Consumes: usage facts, Pricing Version, Reservation and existing V1 financial seams.
- Produces: idempotent final Settlement/Ledger contract and Period Close integration.

- [ ] **Step 1: Define `gateway_settlement` and worker ownership**

Backend/CostOps Core is the single writer. Use DB-backed discovery with row locks/SKIP LOCKED style work claiming; correctness does not depend on MQ.

- [ ] **Step 2: Define Settlement state machine**

At minimum terminal `SETTLED`, retryable failure, and reconciliation-required outcomes. Processing/claim semantics must not create a stuck truth if a worker crashes.

- [ ] **Step 3: Add first-class Gateway Ledger source design**

Freeze:

```text
LedgerSourceType.GATEWAY_SETTLEMENT
ledger_posting source_id -> settlement identity
ledger_entry source_gateway_settlement_id same-org lineage
```

Do not manufacture `charge_fact` or raw import evidence.

- [ ] **Step 4: Define automated posting actor**

System Settlement uses explicit `SYSTEM` posting actor semantics, not a fake member. Preserve existing V1 member actor history.

- [ ] **Step 5: Define financial transaction**

Within one MySQL transaction where possible:

```text
lock period/budget/commitment
validate settlement idempotency
insert posting/entry
increment Budget actual
consume explicit Commitment if bound
write audit
mark settlement SETTLED
```

Redis release/finalization occurs after durable commit and is retryable.

- [ ] **Step 6: Define posting uniqueness**

One settlement -> one Ledger posting using stable business key + DB uniqueness, including concurrent replay.

- [ ] **Step 7: Extend Period Close design**

Unresolved possible-billable Gateway requests/usage/settlement become a `CloseBlockerProvider` source for the affected period.

- [ ] **Step 8: Define closed-period fallback**

Historical/race cases that reach CLOSED remain reconciliation-required and never bypass Period Guard.

- [ ] **Step 9: Commit**

```text
docs(m10): define settlement financial boundary
```

---

### Task 7 / AIC-090: Freeze routing, retry and resilience semantics

**Files:**
- Create: `docs/02-development/v2-detailed-design/07-routing-resilience.md`

**Interfaces:**
- Consumes: request dispatch fence, provider/model catalog and metering uncertainty.
- Produces: safe M11/M14 routing and retry rules.

- [ ] **Step 1: Define routing decision record**

Record bounded, auditable inputs and `route_decision_id`; no prompt content.

- [ ] **Step 2: Freeze retry boundary**

Only retry automatically before a request crosses the billable dispatch-uncertainty boundary or when the Provider contract proves the operation safe/idempotent.

- [ ] **Step 3: Freeze failover boundary**

Failover requires no possible prior billable execution, compatible model semantics, allowed privacy/region policy and a new reservation/pricing validation if price changes.

- [ ] **Step 4: Freeze timeouts/circuit state**

Connect/header/idle/hard deadlines are separate. Circuit state is recoverable runtime state, never financial truth.

- [ ] **Step 5: Explicitly exclude hedging**

No parallel billable Provider hedging in V2 Core.

- [ ] **Step 6: Commit**

```text
docs(m10): define routing and resilience semantics
```

---

### Task 8 / AIC-091: Freeze security, privacy, audit, observability and deployment

**Files:**
- Create: `docs/02-development/v2-detailed-design/08-security-observability-deployment.md`

**Interfaces:**
- Consumes: all previous domain boundaries.
- Produces: production guardrails and dependency failure semantics.

- [ ] **Step 1: Define secret boundaries**

Gateway raw keys return once; Provider secrets never reach clients; Authorization and all raw keys are redacted from logs/audit/metrics.

- [ ] **Step 2: Define prompt/privacy default**

No Prompt/Completion persistence in Gateway request/usage/audit/ordinary logs. Idempotency uses keyed fingerprints, not raw content.

- [ ] **Step 3: Define least-privilege DB users**

Gateway DB user cannot mutate Ledger/Budget actual/Commitment/Period/Settlement truth. Backend migration/financial user permissions are separate.

- [ ] **Step 4: Define dependency failure policy**

Before dispatch, inability to establish durable request/reservation fails closed. After dispatch intent, failures become visible possible-billable recovery states.

- [ ] **Step 5: Define metrics/log fields**

Use bounded-cardinality metrics and structured IDs. No org/user/request IDs as metric labels where cardinality is unbounded.

- [ ] **Step 6: Define deployable topology**

TLS/Ingress routes Control Plane and Gateway separately. Kubernetes/multi-region are not required.

- [ ] **Step 7: Commit**

```text
docs(m10): define security observability and deployment
```

---

### Task 9 / AIC-092: Consolidate data model, machine API contracts, migrations and tests

**Files:**
- Create: `docs/02-development/v2-detailed-design/09-data-api-migration-testing.md`
- Create: `docs/02-development/api/gateway-openapi.yaml`
- Modify: `docs/02-development/api/README.md`

**Interfaces:**
- Consumes: AIC-084..091.
- Produces: exact implementable schema/API/test contract for M11+.

- [ ] **Step 1: Freeze logical data model**

List exact tables/keys/unique constraints/FKs/checks/ownership for Gateway Credential, service identity if introduced, catalog/pricing, request, usage fact, reservation and settlement.

- [ ] **Step 2: Freeze V1 Ledger forward changes**

Specify source type extension, Gateway settlement lineage and system posting actor migration while preserving all historical V1 rows and migrations unchanged.

- [ ] **Step 3: Freeze migration ordering**

Forward-only; backend is migration runner; Gateway cannot start if required schema contract is missing/incompatible.

- [ ] **Step 4: Freeze the M11 OpenAI-compatible subset**

Research current target-client and Provider compatibility. Choose exactly the M11 endpoint surface and supported request/response/streaming subset; unsupported fields are explicit errors, never silently discarded.

- [ ] **Step 5: Write `gateway-openapi.yaml`**

Machine-readable Data Plane source of truth with separate server/auth/error semantics from Control Plane `/api/v1`.

- [ ] **Step 6: Update API governance**

`api/README.md` must define:

```text
openapi.yaml          = Control Plane HTTP source of truth
gateway-openapi.yaml  = Gateway Data Plane HTTP source of truth
Detailed Design       = business/state/transaction authority
```

- [ ] **Step 7: Freeze failure/concurrency test matrix**

Cover MySQL reservation races, V1 Ledger actual-vs-reservation concurrency, dispatch-intent crash windows, duplicate idempotency, stream disconnect, incomplete usage, Settlement replay, Redis post-commit failure, Close blocker and statement reconciliation keys.

- [ ] **Step 8: Commit**

```text
docs(m10): consolidate data api migration and testing contracts
```

---

### Task 10 / AIC-093: Run final M10 freeze review

**Files:**
- Create: `docs/03-acceptance/m10-design-freeze-matrix.md`
- Modify only after acceptance: `PROJECT_CONTEXT.md`
- Modify only after acceptance: `docs/01-blueprint/product/11-roadmap.md`
- Modify only if needed after acceptance: `README.md`

**Interfaces:**
- Consumes: all AIC-084..092 design artifacts.
- Produces: explicit M10 human/architecture acceptance and M11 readiness.

- [ ] **Step 1: Create requirement-by-requirement freeze matrix**

Every item is one of:

```text
FROZEN
DEFERRED WITH EXPLICIT NON-BLOCKING BOUNDARY
BLOCKED
```

Any correctness/API/data-ownership item left to implementation is `BLOCKED`.

- [ ] **Step 2: Run placeholder scan**

Search M10 docs for:

```text
TBD
TODO
implementation decides
later decide
以后再说
看情况
```

False positives in historical quotations are documented; unresolved decisions are blockers.

- [ ] **Step 3: Run terminology consistency review**

Verify stable meaning of:

```text
Gateway Request
Usage Fact
Budget Reservation
Financial Scope
Pricing Version
Gateway Settlement
Ledger Posting
BillingPeriod
Idempotency Key
Dispatch Intent
```

- [ ] **Step 4: Run invariant trace review**

For every financial invariant, point to exact design section and exact planned test evidence.

- [ ] **Step 5: Verify branch purity**

Compare against main. Expected changed files are docs only; no `backend/`, `frontend/`, `gateway/`, Flyway, Maven, Node or Docker runtime changes.

- [ ] **Step 6: Review PR diff and CI**

PR must be mergeable and any applicable documentation/security checks must be green. Do not claim M10 PASS from old branch CI.

- [ ] **Step 7: Make the final decision**

Use exactly one:

```text
M10 = ACCEPTED / FROZEN / M11 READY
M10 = ACCEPTED WITH DOCUMENTED NON-BLOCKING LIMITATIONS
M10 = BLOCKED
```

Blocking correctness gaps cannot be classified as non-blocking.

- [ ] **Step 8: Only after acceptance update active project state**

Set:

```text
M10 = COMPLETE / FROZEN
M11 = NEXT IMPLEMENTATION MILESTONE
```

Do not mark M11 active before AIC-093 passes.

- [ ] **Step 9: Commit final freeze docs**

```text
docs(m10): close final design freeze
```

---

## Self-review checklist

Before execution is considered complete:

```text
[ ] Every normative review decision R1-R14 maps to a task.
[ ] No Redis-only Budget correctness remains.
[ ] No synthetic charge_fact/raw import lineage is proposed for realtime usage.
[ ] Automated Settlement does not impersonate a human member.
[ ] Dispatch-intent crash window is explicit.
[ ] Financial scope is exactly one Ledger target.
[ ] No undocumented FX exists.
[ ] Reservation upper-bound/overrun behavior is explicit.
[ ] Financial idempotency does not require response-content retention.
[ ] Pending Gateway financial work blocks Period Close.
[ ] Gateway DB operations cannot block Netty event loop.
[ ] Flyway has one owner.
[ ] Hybrid reconciliation keys are retained from M11 onward.
[ ] M11 API compatibility surface is explicit before implementation.
```

## Execution decision

The user has delegated M10 pure-document design work to GPT-5.6 Sol. Execute this plan on the existing isolated branch `docs/m10-v2-detailed-design`; do not merge until AIC-093 final review is complete.
