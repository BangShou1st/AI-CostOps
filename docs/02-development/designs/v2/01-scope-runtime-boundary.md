# AIC-084 — V2 Scope & Runtime Boundary

> Status: **FROZEN CANDIDATE**  
> This document freezes the runtime, durable-state and database-ownership boundary used by AIC-085 through AIC-092. It does not authorize production Gateway code before AIC-093.

## 1. Product boundary

V2 adds request-time control and realtime cost capture to the V1 financial system. It does not replace V1.

```text
Post-billing Provider statement path
Provider Import
→ Canonical Cost / Charge
→ Allocation
→ Ledger
→ Reconciliation / Close

Realtime Gateway path
Client
→ Gateway
→ Provider
→ Usage Fact
→ Settlement
→ Existing Ledger / Budget / Close
```

Both paths converge on the existing MySQL financial truth.

## 2. Deployables

```text
frontend/
= Admin UI

backend/
= Control Plane + CostOps Core
= Spring MVC

gateway/
= Realtime Data Plane
= Spring WebFlux + Reactor Netty
```

This is a runtime separation for different workload shapes, not a microservice program.

### Control Plane workload

```text
short admin/API requests
transaction-heavy financial workflows
correctness and audit first
reporting / operations
```

### Data Plane workload

```text
long-lived provider streams
high connection concurrency
provider network latency
backpressure / disconnect handling
low-overhead request path
```

## 3. Control Plane / CostOps Core ownership

`backend` remains authoritative for:

```text
IAM
Organization
Project / Team / Cost Center administration
Gateway credential administration
Service identity administration
Provider Account metadata administration
Provider / Model Catalog administration
Pricing Version administration
Budget administration
Final Gateway Settlement
Immutable Ledger
Budget actual mutation
Commitment consumption
Reconciliation
BillingPeriod Close / Reopen
Audit query / reporting
Admin Workbench
```

The Control Plane may read Gateway-owned durable facts for administration, settlement, close blocking, reconciliation and reporting. It must not destructively rewrite them.

## 4. Gateway Data Plane ownership

`gateway` owns the runtime request path:

```text
OpenAI-compatible Data Plane endpoint
Gateway credential authentication
principal / request identity resolution
request attribution and financial-scope resolution
rate limit / quota runtime checks
budget reservation orchestration
provider/model resolution
provider dispatch
non-streaming proxy
SSE streaming proxy
client/provider disconnect handling
realtime usage capture
usage normalization
Gateway request/usage durable facts
routing runtime state
Gateway runtime metrics
```

Gateway does not own final financial Settlement or Ledger truth.

## 5. Provider Adapter ownership

A Provider Adapter is part of the Gateway Data Plane codebase and owns Provider-specific wire semantics only:

```text
Provider endpoint/request translation
Provider authentication injection
Provider model id translation
Provider response translation
Provider streaming event translation
Provider usage extraction
Provider request-id extraction
Provider error mapping
Provider-specific retry-safety evidence
```

A Provider Adapter must not:

```text
mutate Ledger
mutate Budget actual
consume Commitments
close/reopen BillingPeriods
invent FX
make final reconciliation decisions
persist Prompt/Completion content by default
```

## 6. Durable state classes

### 6.1 Gateway durable facts

Gateway is the single writer for:

```text
gateway_request
gateway_usage_fact
budget_reservation
```

These are durable because request-time correctness and crash recovery require them.

They are not equivalent to final financial truth:

```text
gateway_request
= what the Gateway attempted / observed

gateway_usage_fact
= normalized realtime Provider usage observation

budget_reservation
= short-lived request-time financial control hold
```

### 6.2 CostOps Core durable financial results

Backend/CostOps Core is the single writer for:

```text
gateway_settlement
ledger_posting
ledger_entry
budget.actual_amount
budget_commitment_usage
billing_period close/reopen state
financial audit events produced by Settlement
```

`gateway_settlement` is a durable financial result derived from Gateway usage facts and the frozen Pricing Version. It is not written by Gateway.

### 6.3 Administrative truth

Backend/Control Plane is the single writer for:

```text
gateway_principal / service identity administration
gateway_credential administration
provider credential metadata/reference
provider/model catalog
pricing version
budget definitions
routing policy administration
```

Gateway may read/cache the runtime projection of this data.

## 7. Single-writer table matrix

| Durable object | Writer | Other runtime access |
|---|---|---|
| `gateway_request` | Gateway | Backend read |
| `gateway_usage_fact` | Gateway | Backend read |
| `budget_reservation` | Gateway | Backend read; settlement may finalize through an explicitly owned financial port if AIC-087/AIC-089 require it |
| `gateway_settlement` | Backend | Gateway read only if a runtime response/status contract needs it |
| Gateway Credential admin state | Backend | Gateway read/cache |
| Provider/Model/Pricing admin state | Backend | Gateway read/cache |
| `ledger_posting` / `ledger_entry` | Backend | Gateway no write |
| `budget.actual_amount` | Backend | Gateway read/lock for reservation calculation, no update |
| `budget_commitment_usage` | Backend | Gateway no write |
| BillingPeriod state | Backend | Gateway read/lock through reservation contract, no close/reopen write |

No table may have ambiguous concurrent business ownership.

## 8. Why Budget Reservation is MySQL-authoritative

V1 already changes these durable values in MySQL:

```text
Budget.actual_amount
Budget commitments / commitment usage
```

A Redis-only `Active Reservations` value cannot be atomically compared with a concurrent V1 Ledger posting that changes MySQL Actual/Commitment state.

Therefore V2 financial authorization is proven in MySQL:

```text
Realtime Available
= Total
- Actual
- Outstanding Commitments
- durable Active Reservations
```

The reserve transaction locks the selected Budget row and serializes with V1 financial mutations that lock the same Budget.

Redis remains useful for runtime rate/quota/idempotency/expiry coordination, but Redis cannot independently say that budget is available.

## 9. Existing Budget selection semantics remain authoritative

The current V1 `LedgerBudgetService` selects:

```text
exact financial scope + BillingPeriod + currency
→ ORG fallback + same BillingPeriod + currency
→ no Budget
```

V2 uses the same selection semantics unless a later accepted M10 document explicitly changes the product rule.

Gateway request context is not the same as financial target. A request always has a Project ownership context, but its financial Ledger/Budget target is exactly one:

```text
PROJECT
TEAM
COST_CENTER
```

AIC-085/AIC-086 freeze the credential/principal policy that selects that target.

## 10. Realtime Settlement is not a fake Provider Import

Current V1 `charge_fact` requires `raw_record_id` and belongs to the Import/Evidence lineage.

Realtime Gateway usage must not manufacture:

```text
raw_provider_record
charge_fact
allocation_decision
```

merely to reuse `ProviderChargePostingService`.

V2 creates first-class Settlement/Ledger lineage in AIC-089:

```text
gateway_usage_fact
→ gateway_settlement
→ LedgerSourceType.GATEWAY_SETTLEMENT
→ ledger_posting / ledger_entry
```

This preserves the meaning of both V1 Provider statement evidence and V2 realtime observations.

## 11. Financial posting reuse rule

“Reuse the existing financial domain” means reuse proven financial seams and invariants, not call an incompatible V1 source-specific service with synthetic data.

M13 Gateway Settlement posting must reuse or extend the existing semantics around:

```text
BillingPeriodFinancialWriteFence
LedgerBudgetPort / LedgerBudgetService
CommitmentConsumeService
immutable Ledger persistence
stable posting key + MySQL uniqueness
fixed lock ordering
audit rollback semantics
```

It may introduce a new narrow Gateway Settlement posting orchestration inside the CostOps/Ledger boundary.

## 12. Automated posting actor

V1 `ledger_posting.posted_by_member_id` is human-member-oriented. Gateway Settlement is system work and must not impersonate a human.

AIC-089/AIC-092 must evolve Ledger posting actor semantics to support:

```text
MEMBER
SYSTEM
```

Existing V1 rows remain `MEMBER`. Gateway Settlement uses `SYSTEM` with no fake organization member.

The Audit model already permits a null `actor_user_id`; system events use explicit event type/metadata rather than synthetic users.

## 13. Gateway MySQL access strategy

### 13.1 No blocking DB work on Netty event loop

Gateway stays WebFlux/Reactor Netty for edge/streaming work, but V2 initially reuses synchronous MySQL transaction technology for correctness and maintainability.

```text
Gateway Provider/network I/O
= reactive WebFlux/Reactor Netty

Gateway MySQL transaction seams
= synchronous JDBC/MyBatis

execution rule
= offload blocking DB work to a bounded scheduler / bounded DB pool
```

Blocking MySQL or object-storage work is forbidden on Reactor Netty event-loop threads.

### 13.2 No default R2DBC introduction

The project already has proven JDBC/MyBatis/MySQL lock semantics. R2DBC adds a second persistence model and is not needed to satisfy M11-M13 correctness.

If later load evidence shows the bounded blocking seam is the real bottleneck, a separate evidence-based design may evaluate R2DBC.

## 14. Flyway ownership

There is one schema migration owner:

```text
backend / Control Plane deployment
```

Gateway must not run competing production Flyway migrations.

Deployment ordering for a schema-dependent Gateway release is:

```text
1. apply compatible forward migration through backend migration owner
2. verify schema compatibility
3. deploy Gateway/Backend versions that consume it
```

Gateway startup must fail fast when a required schema contract/version is missing.

## 15. Production database privileges

Gateway uses a separate least-privilege DB credential from the Control Plane migration/financial credential where deployment permits it.

Gateway requires only the operations necessary for:

```text
read credential/catalog/pricing/budget/period runtime projections
create/update its gateway_request / gateway_usage_fact rows
create/update its budget_reservation rows
perform required SELECT ... FOR UPDATE locking inside the reservation contract
```

Gateway DB credentials must not have direct write authority for:

```text
ledger_posting
ledger_entry
budget.actual_amount
budget_commitment_usage
billing_period close/reopen state
gateway_settlement final state
```

Database privilege policy is defense in depth in addition to code/module architecture rules.

## 16. Durable dispatch fence

There is no atomic transaction between MySQL and an external Provider.

Before Gateway intentionally sends the first potentially billable upstream request, it must durably commit a request state meaning:

```text
UPSTREAM_DISPATCH_INTENT
```

or an equivalent frozen name.

After this fence, the request is financially unsafe to blindly replay. A crash between the durable fence and the actual network send creates a conservative orphan, not silent loss.

AIC-086 defines the full state machine and idempotency semantics.

## 17. MySQL failure policy by request phase

### Before durable request/reservation/fence

```text
MySQL unavailable
→ fail closed
→ do not send Provider request
```

### After durable dispatch fence / possible billable execution

```text
MySQL unavailable
→ never report cost as zero
→ do not blindly retry another Provider call
→ existing durable request remains possible-billable recovery evidence
→ recover to FINAL / INCOMPLETE / UNKNOWN usage or reconciliation-required state
```

Provider statement reconciliation is the final external-truth recovery path when exact realtime usage cannot be reconstructed.

## 18. Close integration boundary

BillingPeriod Close remains owned by Backend.

M13 must extend the existing `CloseBlockerProvider` seam so unresolved possible-billable Gateway work can block normal period close.

Gateway never closes/reopens a period itself.

AIC-089 defines exact blocking states and closed-period fallback.

## 19. Redis boundary

Redis V2 may hold:

```text
rate limit
quota windows
short credential cache
short idempotency coordination
provider health/circuit state
reservation expiry wake-up hint/cache
request ephemeral coordination
```

Redis must not be the sole source for:

```text
Budget authorization
Active Reservation amount used for correctness
Final Settlement
Ledger
BillingPeriod
final Pricing Version
```

Redis loss may reduce availability or require conservative fail-closed behavior; it cannot fabricate spend authorization.

## 20. S3 / Evidence boundary

MinIO/S3 remains Evidence storage for the existing CostOps evidence/import domain.

Gateway does not automatically persist Prompt/Completion bodies to S3. Realtime financial reconciliation requires safe bounded request/usage/provider identifiers, not content retention.

A future prompt-observability product requires a separate privacy/retention/encryption/access-control design.

## 21. Shared-code guard

Initial repository stays:

```text
/backend
/gateway
/frontend
```

Do not create a broad `common`, `core`, `foundation`, `platform`, or `utils` module before there are two stable consumers.

A narrow shared module is allowed only when:

1. backend and gateway both truly consume the same stable semantic contract;
2. the contract is small and versionable;
3. sharing does not couple runtime persistence internals.

Possible later candidates include immutable Money/wire identifiers or error constants. Persistence mappers and business services are not shared by default.

## 22. Milestone ownership split

```text
M11 — Gateway Edge MVP
credential auth, bounded OpenAI-compatible edge, one Provider,
non-streaming/SSE, dispatch fence, safe logs/timeouts, basic runtime limits

M12 — Identity / Attribution / Budget Reservation
principal/service identity, financial scope, quota,
MySQL-authoritative reservation, Redis runtime coordination

M13 — Realtime Metering / Settlement
usage normalization, Pricing Version, final Settlement,
GATEWAY_SETTLEMENT Ledger source, close blocker, recovery

M14 — Multi-provider Routing / Resilience
multiple adapters, model mapping, health-aware routing,
bounded retry/failover

M15 — Hybrid Reconciliation
Gateway realtime facts vs Provider statements

M16 — Production Acceptance
load/failure/security/recovery evidence
```

M10 freezes the semantics for all of them without implementing them early.

## 23. Explicit non-goals

This design does not introduce:

```text
microservice explosion
Kafka
RabbitMQ by default
Kubernetes
service mesh
R2DBC by default
multi-region active-active
FX engine
prompt management
RAG
agent workbench
model quality evaluation
```

## 24. AIC-084 Definition of Done

AIC-084 is satisfied only when the following remain true across all later M10 documents:

```text
[freeze] two deployables, one financial truth
[freeze] explicit table single-writer ownership
[freeze] Gateway cannot write Ledger/Budget actual/Commitment/Period/final Settlement
[freeze] Backend cannot rewrite Gateway request/usage facts
[freeze] MySQL-authoritative Budget Reservation correctness
[freeze] Redis is recoverable runtime state, not spend authority
[freeze] realtime Settlement has first-class lineage, not synthetic Provider Import lineage
[freeze] automated Settlement uses SYSTEM actor semantics
[freeze] blocking DB work never runs on Netty event loop
[freeze] backend is sole Flyway migration owner
[freeze] unresolved Gateway financial work integrates with Close blockers
```

Any later design that violates one of these requires reopening AIC-084 and blocks AIC-093 freeze.
