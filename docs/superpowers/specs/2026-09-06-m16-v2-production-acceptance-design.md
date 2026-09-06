# M16 — V2 Production Acceptance Design Freeze

**Status:** FROZEN FOR IMPLEMENTATION  
**Target:** `v2.0.0`  
**Primary Issue:** #151  
**Implementation branch:** `feat/m16-v2-production-acceptance`  
**Baseline:** `d287be1217430d415fe02c80110c79e136d8772c`

## 1. Goal

M16 is the final V2 production-acceptance milestone. It does not add a new CostOps business model. It proves that the M11–M15 runtime remains financially correct, concurrency-safe, recoverable, observable, secure, and usable under production-like operation, load, dependency failure, process restart, credential revocation, routing failover, settlement retry, and reconciliation.

M16 may harden production/runtime behavior when an acceptance RED exposes a real defect. It must not redesign already-frozen M11–M15 financial semantics without evidence.

M16 PASS is required before declaring `v2.0.0` release-candidate ready.

## 2. Frozen invariants

The following rules are immutable acceptance constraints:

- MySQL Ledger remains final financial truth.
- Redis is operational state, never monetary truth.
- Monetary values remain `BigDecimal` / `DECIMAL` / decimal strings.
- Posted Ledger facts are immutable; corrections are append-only.
- Provider I/O occurs only after durable committed `DISPATCH_INTENT`.
- Provider I/O never occurs inside a DB transaction.
- Unknown execution safety is `BILLABLE_POSSIBLE`.
- Automatic failover is permitted only after positive `SAFE_NO_BILLABLE_EXECUTION` evidence.
- Blind Provider redispatch is forbidden.
- Billable parallel hedging is forbidden.
- Missing usage is not zero.
- Usage state remains explicit: `FINAL`, `INCOMPLETE`, or `UNKNOWN`.
- Prompt/completion content is not persisted by default.
- Raw Gateway keys, Provider secrets, Authorization values, raw Idempotency-Key values, prompts, and completions must not leak into logs, metrics, audit evidence, or error payloads.

## 3. Scope posture

M16 is **prove + harden**.

Allowed:

- production deployment wiring;
- separate least-privilege runtime DB identities;
- production configuration fail-fast validation;
- load/concurrency/streaming/overload acceptance harnesses;
- controlled failure injection and restart/recovery tests;
- observability, dashboards, and alert wiring;
- V2 backup/restore acceptance;
- AI Browser black-box UAT;
- real Provider certification with sanitized evidence;
- minimal fixes proven necessary by RED acceptance evidence.

Explicit non-goals:

- SAML/SCIM;
- full FOCUS support;
- FX support;
- ERP/GL integration;
- anomaly detection or forecasting;
- multi-region design;
- speculative R2DBC rewrite;
- Kafka;
- Kubernetes or service mesh;
- microservice expansion.

No major architecture change is permitted without measured M16 evidence.

## 4. Migration rule

M16 does not require a new schema migration by default.

- V23 remains the latest migration.
- V24 is allowed only if an observed RED acceptance case proves that correctness or recovery cannot be achieved without a schema change.

## 5. Work packages

### WP1 — Production topology and least privilege

Production acceptance must exercise, at minimum:

- Frontend;
- Backend / Control Plane;
- Gateway / Data Plane;
- MySQL;
- Redis;
- MinIO/S3-compatible evidence storage where required;
- Prometheus;
- Grafana;
- deterministic controlled mock Provider.

Gateway must be a first-class service in the V2 acceptance topology.

Backend and Gateway must use separate database identities.

Gateway runtime DB identity may perform only the reads/locks/writes required by existing Gateway runtime ownership. It must not be able to directly mutate Control-Plane-owned financial truth such as Ledger postings/entries, Budget actual/committed amounts, Settlement ownership, or BillingPeriod close/reopen state.

M16 must include real MySQL permission-denied evidence, not only Java architecture assertions.

### WP2 — Load, concurrency, streaming, and overload

M16 does not invent an arbitrary throughput SLO. It measures a safe operating envelope with stepped load:

`baseline -> increasing concurrency -> configured range -> saturation -> controlled rejection`.

Record at least:

- throughput;
- p50/p95/p99 latency;
- active streams;
- DB pool utilization;
- blocking-DB scheduler/queue pressure;
- Provider latency;
- Redis latency;
- relevant CPU/memory observations;
- bounded error classes.

Correctness is the primary PASS condition.

Mandatory idempotency case:

- 100 concurrent requests;
- same credential;
- same Idempotency-Key;
- same exact request body.

Expected final truth:

- exactly one `gateway_request` identity;
- at most one effective reservation;
- at most one economically billable route attempt;
- exactly one Provider operation;
- no duplicate Ledger effect.

Budget-concurrency and streaming-overload tests must prove no race-induced overspend, no unbounded resource growth, and safe rejection.

### WP3 — Failure, restart, and recovery

Mandatory controlled failures include:

- MySQL unavailable before dispatch;
- MySQL failure after durable `DISPATCH_INTENT`;
- MySQL restart;
- Redis unavailable;
- Redis restart/state loss;
- Gateway process restart at controlled lifecycle windows;
- Backend process restart during settlement processing;
- Provider timeout/outage;
- client disconnect;
- reservation expiry/recovery;
- settlement retry/recovery.

Rules:

- pre-dispatch uncertainty must result in zero Provider calls;
- post-dispatch uncertainty must never be treated as zero cost without proof;
- post-dispatch uncertainty must never trigger blind redispatch;
- Redis loss must not fabricate Budget availability;
- durable MySQL facts drive recovery after process restart.

### WP4 — Security, privacy, and production configuration

Production startup must fail fast for unsafe configuration, including:

- missing/default Gateway credential HMAC secret;
- missing request-fingerprint HMAC secret;
- missing Provider KEK;
- dev bootstrap enabled;
- dev raw key configured;
- unsafe Provider endpoint;
- unsafe/default/missing Gateway datasource credential;
- invalid timeout/concurrency/resource bounds.

A mandatory RED/GREEN case is required for the currently incomplete Gateway datasource production-credential validation.

After the acceptance run, scan logs, metrics, mock Provider logs, audit/evidence artifacts, error envelopes, and browser artifacts for forbidden secret/content sentinels.

### WP5 — Observability, alerting, backup, and restore

Prometheus must scrape both Backend and Gateway.

Minimum Gateway signals include:

- request/outcome;
- active streams;
- Provider errors/timeouts;
- Redis errors;
- usage `FINAL`/`INCOMPLETE`/`UNKNOWN`;
- reservation and `PENDING_HOLD` recovery;
- routing/failover/circuit state;
- settlement backlog/retry;
- `RECONCILIATION_REQUIRED` backlog;
- MySQL dependency/pool state where exposed;
- blocking scheduler saturation.

Minimum alert categories include:

- `UNKNOWN` usage spike;
- settlement retry backlog;
- reconciliation-required backlog;
- stale `PENDING_HOLD`;
- Provider failure spike;
- Redis dependency errors;
- MySQL dependency errors;
- blocking scheduler saturation;
- financial close blockers.

Alert thresholds must be justified by M16 measurements rather than invented production SLOs.

The V2 restore drill must prove that Gateway request/route/usage/reservation/settlement and M15 reconciliation lineage can be recovered from durable storage, with Redis empty or lost. KEK/HMAC secret recovery remains external to the DB backup.

### WP6 — AI Browser black-box UAT

AI Browser UAT is a mandatory M16 gate but never overrides machine financial invariants.

The browser operator acts as a user/administrator/finance operator and must not use source-code inspection as evidence that a UI workflow passed.

Each scenario records:

- tested SHA;
- actor/role;
- performed browser steps;
- observed UI result;
- sanitized screenshots/evidence references;
- relevant request/business identifiers;
- API result where relevant;
- machine/database invariant result;
- PASS/FAIL.

Mandatory scenarios:

#### UAT-01 — Administrative setup

Login and perform governed setup required for a V2 request lifecycle: organization/project context, Budget, Service Identity, Gateway Credential, Provider/Model/Pricing, and Routing configuration.

Verify secret exposure is governed and raw secret is not recoverable after the intended creation surface/refresh/re-login behavior.

#### UAT-02 — Real Gateway lifecycle

Drive a Gateway request through Reservation -> Provider -> Usage -> Settlement -> Ledger -> Budget Actual and confirm Control Plane UI values match durable truth.

#### UAT-03 — Budget exhaustion

Consume a deliberately constrained Budget and prove later work is rejected before Provider dispatch with no race overspend.

#### UAT-04 — Credential revoke

Allow request A to incur work, revoke the credential through governed Control Plane workflow, prove new request B is rejected, and prove already-incurred request A still reaches exactly one legitimate financial outcome.

#### UAT-05 — Reconciliation

Import Provider statement evidence, run reconciliation, inspect exact/difference/unresolved cases, perform governed resolution, rerun reconciliation, verify financial effects, and exercise CLOSED-period correction behavior.

#### UAT-06 — Permissions

Exercise at least Admin, finance/reconciliation operator, read-only, and unauthorized access. Verify navigation, actions, direct URL access, and server-side authorization.

#### UAT-07 — Human usability / visual acceptance

Inspect blank screens, layout overflow, stuck loading, duplicate submit behavior, refresh recovery, financial formatting, long identifiers, readable errors, financial state labels, and confirmation on dangerous actions.

### WP7 — Final release evidence

Final evidence must include:

- production topology evidence;
- DB privilege evidence;
- load/concurrency/overload evidence;
- failure/recovery evidence;
- security/leak evidence;
- observability/alert evidence;
- V2 backup/restore evidence;
- Browser UAT evidence;
- real Provider certification or an explicit unresolved release blocker;
- complete local regression evidence;
- hosted CI/Security/CodeQL/Trivy evidence;
- exact reviewed SHA;
- final Sol independent review.

## 6. Acceptance matrix

| ID | Scenario | Required result |
| --- | --- | --- |
| A01 | Production topology boot | All required runtime services healthy |
| A02 | Gateway readiness | Correctly reflects required dependency/correctness readiness |
| A03 | Gateway DB least privilege | Allowed runtime works; forbidden financial mutation denied by MySQL |
| A04 | Unsafe production config | Startup rejected |
| B01 | 100-way identical replay | One Provider operation; one durable request identity |
| B02 | Budget concurrent exhaustion | No race overspend |
| B03 | Stepped non-stream load | Bounded stable operation through measured envelope |
| B04 | Concurrent SSE | Configured stream bound enforced |
| B05 | Overload | Bounded safe rejection rather than unbounded growth |
| C01 | MySQL down before dispatch | Zero Provider calls |
| C02 | MySQL failure after dispatch | Uncertainty preserved; zero blind redispatch |
| C03 | MySQL restart | Runtime reconnects; financial facts intact |
| C04 | Redis outage | Mandatory dependency behavior fails closed |
| C05 | Redis state loss | No fabricated monetary availability |
| C06 | Gateway restart | Durable request recovery |
| C07 | Backend restart | Settlement recovery |
| C08 | Settlement retry | Exactly one final Ledger outcome or explicit reconciliation requirement |
| C09 | Expired Reservation | RELEASED or PENDING_HOLD according to dispatch evidence |
| D01 | Certified safe Provider failure | Eligible safe failover only |
| D02 | Provider failure with billable uncertainty | Automatic failover stops |
| D03 | Credential revoke | Future work blocked; incurred work preserved |
| D04 | Settlement vs Close | Deterministic financial convergence |
| D05 | Reconciliation vs Close | Deterministic convergence |
| D06 | Statement difference | Governed append-only correction |
| E01 | Prometheus | Backend + Gateway scraped |
| E02 | Alerts | Injected failures produce intended signals |
| E03 | Leak scan | Zero forbidden sentinel leakage |
| E04 | V2 restore | Full durable financial lineage recoverable without Redis |
| F01-F07 | AI Browser UAT | All mandatory UAT scenarios PASS |
| G01 | Full local regression | PASS |
| G02 | Docker images | PASS |
| G03 | Hosted CI | PASS |
| G04 | Hosted Security | PASS |
| G05 | CodeQL | PASS |
| G06 | Trivy | PASS |
| G07 | P0/P1 blockers | 0 |

## 7. Performance interpretation

M16 must report the measured safe operating envelope, first saturation point, dominant bottleneck, recommended concurrency configuration, DB-pool/blocking-scheduler relationship, and recommended active-stream bound.

A major runtime architecture rewrite is out of scope unless M16 measurements prove the current bounded WebFlux + JDBC/MyBatis design cannot meet reasonable operation requirements.

## 8. Final PASS definition

M16 can be marked COMPLETE/ACCEPTED only when all of the following are true:

```text
lost settlement                  = 0
duplicate ledger                 = 0
silent reservation leak          = 0
race-induced budget overspend    = 0
blind Provider redispatch        = 0
provider secret leak             = 0
gateway key leak                 = 0
prompt/completion leak           = 0

Production topology              = PASS
Gateway DB least privilege       = PASS
Load/concurrency/overload        = PASS
Failure/recovery                 = PASS
Security/privacy                 = PASS
Observability/alerts             = PASS
Backup/restore                   = PASS
AI Browser UAT                   = PASS
Real Provider certification      = PASS

Hosted CI                        = GREEN
Hosted Security                  = GREEN
CodeQL                           = GREEN
Trivy                            = GREEN
P0                               = 0
P1                               = 0
```

Only then may project documentation state:

```text
M16 = COMPLETE / ACCEPTED
V2 Production Acceptance = PASS
v2.0.0 = RELEASE CANDIDATE
```

Final merge remains a separate explicit user-authorized action after Sol independently reviews the exact PR head and all hosted gates.