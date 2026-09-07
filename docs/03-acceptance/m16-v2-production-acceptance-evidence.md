# M16 — V2 Production Acceptance Evidence

**Status:** MACHINE ACCEPTANCE COMPLETE / EXTERNAL GATES BLOCKED
**Primary Issue:** #151  
**Branch:** `feat/m16-v2-production-acceptance`  
**Design baseline:** `d287be1217430d415fe02c80110c79e136d8772c`  
**Target release:** `v2.0.0`

> This document is an evidence ledger, not a planning document. Do not mark a row PASS without reproducible evidence from the exact tested SHA. Browser observations never override failed machine/database financial invariants.

## 1. Exact tested revision

| Field | Value |
| --- | --- |
| Branch | `feat/m16-v2-production-acceptance` |
| Starting baseline | `d287be1217430d415fe02c80110c79e136d8772c` |
| Machine-acceptance SHA | `fe18f97` (+ working-tree M16 changes below; final SHA after commit) |
| Exact final PR head | NOT YET AVAILABLE (after push) |
| Final Sol-reviewed SHA | NOT YET AVAILABLE |

Working-tree M16 changes at machine-acceptance time (all reviewed below):

- `gateway/.../ChatCompletionController.java` — B04 product fix (stream permit held for whole SSE lifetime)
- `gateway/.../StreamPermitCeilingIntegrationTest.java` — new B04 RED/GREEN regression
- `scripts/m16-production-acceptance.ps1` — single orchestrator (new)
- `scripts/m16/invoke-m16-load.ps1` — B03/B04 harness (new)
- `scripts/m16/invoke-m16-b01-idempotency.ps1`, `invoke-m16-b02-budget.ps1`, `invoke-m16-failure.ps1`, `invoke-m16-crash.ps1`, `invoke-m16-provider-revoke.ps1`, `invoke-m16-leakscan.ps1` — scenario harnesses (new)
- `scripts/m16/mock-provider/mock_provider.py` — hold/release barrier for B04 (modified)
- `scripts/m16/invoke-m16-restore.ps1` — E04 drill (modified: dump encoding fix)

## 2. Global financial invariants

| Invariant | Required | Observed | Status | Evidence |
| --- | ---: | ---: | --- | --- |
| Lost settlement | 0 | 0 (B03: 31 settled / 0 pending; C07: 1/1; E04: 304/304) | PASS | §6, §7, §11 |
| Duplicate Ledger effect | 0 | 0 (posting:entry:settled = 1:1:1, no dup keys) | PASS | §6 |
| Silent Reservation leak | 0 | 0 (B05 held 1022.36/1200; C09 recovery converges) | PASS | §6, §7 |
| Race-induced Budget overspend | 0 | 0 (B02 held == total 31.9488; B05 held <= total) | PASS | §6 |
| Blind Provider redispatch | 0 | 0 (B01 ops=1; C02/C06 replay 409 ops=1; D02 replay 409) | PASS | §6, §7, §8 |
| Provider secret leak | 0 | 0 sentinels in gateway logs/metrics | PASS | §10 |
| Gateway key leak | 0 | 0 sentinels in logs/metrics/error bodies | PASS | §10 |
| Prompt/completion leak | 0 | 0 forbidden; client body carries completion by design | PASS | §10 |

## 3. Acceptance matrix

| ID | Scenario | Required result | Status | Exact SHA | Evidence |
| --- | --- | --- | --- | --- | --- |
| A01 | Production topology boot | All required runtime services healthy | PASS | fe18f97+wt | §4 |
| A02 | Gateway readiness | Correct dependency/correctness readiness | PASS | fe18f97+wt | §4 |
| A03 | Gateway DB least privilege | Allowed runtime succeeds; forbidden financial mutation denied by MySQL | PASS | fe18f97+wt | §5 |
| A04 | Unsafe production config | Startup rejected | PASS | fe18f97+wt | §4 |
| B01 | 100-way identical replay | One Provider operation; one durable request identity | PASS | fe18f97+wt | §6 |
| B02 | Budget concurrent exhaustion | No race overspend | PASS | fe18f97+wt | §6 |
| B03 | Stepped non-stream load | Bounded stable operation through measured envelope | PASS | fe18f97+wt | §6 |
| B04 | Concurrent SSE | Configured stream bound enforced | PASS | fe18f97+wt | §6 |
| B05 | Overload | Bounded safe rejection | PASS | fe18f97+wt | §6 |
| C01 | MySQL down before dispatch | Zero Provider calls | PASS | fe18f97+wt | §7 |
| C02 | MySQL failure after dispatch | Uncertainty preserved; zero blind redispatch | PASS | fe18f97+wt | §7 |
| C03 | MySQL restart | Runtime reconnects; financial facts intact | PASS | fe18f97+wt | §7 |
| C04 | Redis outage | Mandatory dependency behavior fails closed | PASS | fe18f97+wt | §7 |
| C05 | Redis state loss | No fabricated monetary availability | PASS | fe18f97+wt | §7 |
| C06 | Gateway restart | Durable request recovery | PASS | fe18f97+wt | §7 |
| C07 | Backend restart | Settlement recovery | PASS | fe18f97+wt | §7 |
| C08 | Settlement retry | Exactly one final Ledger outcome or explicit reconciliation requirement | PASS | fe18f97+wt | §7 |
| C09 | Expired Reservation | RELEASED or PENDING_HOLD according to evidence | PASS | fe18f97+wt | §7 |
| D01 | Certified safe Provider failure | Eligible safe failover only | PASS | fe18f97+wt | §8 |
| D02 | Billable-possible Provider failure | Automatic failover stops | PASS | fe18f97+wt | §8 |
| D03 | Credential revoke | Future work blocked; incurred work preserved | PASS | fe18f97+wt | §8 |
| D04 | Settlement vs Close | Deterministic convergence | PASS | fe18f97+wt | §8 |
| D05 | Reconciliation vs Close | Deterministic convergence | PASS | fe18f97+wt | §8 |
| D06 | Statement difference | Governed append-only correction | PASS | fe18f97+wt | §8 |
| E01 | Prometheus | Backend + Gateway scraped | PASS | fe18f97+wt | §9 |
| E02 | Alerts | Injected failures produce intended signals | PASS | fe18f97+wt | §9 |
| E03 | Leak scan | Zero forbidden sentinel leakage | PASS | fe18f97+wt | §10 |
| E04 | V2 restore | Full durable financial lineage recoverable without Redis | PASS | fe18f97+wt | §11 |
| F01 | Browser UAT — administrative setup | PASS | NOT RUN | — | — |
| F02 | Browser UAT — Gateway lifecycle | PASS + matching durable truth | NOT RUN | — | — |
| F03 | Browser UAT — Budget exhaustion | PASS + no overspend | NOT RUN | — | — |
| F04 | Browser UAT — Credential revoke | PASS + incurred work settles exactly once | NOT RUN | — | — |
| F05 | Browser UAT — Reconciliation | PASS + governed correction semantics | NOT RUN | — | — |
| F06 | Browser UAT — Permissions | PASS + server-side authorization | NOT RUN | — | — |
| F07 | Browser UAT — usability/visual | PASS | NOT RUN | — | — |
| G01 | Full local regression | PASS | NOT RUN | — | — |
| G02 | Docker images | PASS | NOT RUN | — | — |
| G03 | Hosted CI | GREEN | NOT RUN | — | — |
| G04 | Hosted Security | GREEN | NOT RUN | — | — |
| G05 | CodeQL | GREEN | NOT RUN | — | — |
| G06 | Trivy | GREEN | NOT RUN | — | — |
| G07 | P0/P1 blockers | 0 | NOT RUN | — | — |

## 4. Production topology evidence

### Service inventory

A01 PASS. Isolated `m16accept` stack on `m16-accept-net`: `m16-mysql-accept`
(MySQL 8.4), `m16-redis-accept` (Redis 8.8.1), `m16-backend-accept` (:18080),
`m16-gateway-accept` (:18081, `gw_m16` least-privilege identity, ceiling 128),
`m16-mock-provider` (:18089, deterministic hold/release barrier), `m16-prometheus`
(:19090). `verify-m16-topology.ps1`: `M16_TOPOLOGY_GREEN`.

### Health/readiness results

A02 PASS. Gateway + Backend `/actuator/health/readiness` both 200.
Gateway image rebuilt from current sources (`ai-costops-gateway:m16`) after
the B04 permit fix; readiness re-verified 200 with `gateway_active_streams 0.0`.

### Gateway production configuration negative tests

A04 PASS. `GatewayProductionConfigurationValidatorTest` GREEN via orchestrator
(maven-unit gate). Production fail-fast covers HMAC/KEK/datasource/resource bounds.

## 5. Gateway database least-privilege evidence

### Effective Gateway grants

A03 PASS (`M16_PRIVILEGE_GREEN`). `SHOW GRANTS`: no GRANT OPTION, no DELETE,
Gateway-owned writes on 5 tables only (`budget_reservation`, `gateway_request`,
`gateway_route_attempt`, `gateway_usage_fact`, `gateway_usage_dimension`).

### Positive allowed operations

SELECT on budget/billing_period/ledger_posting; SELECT ... FOR UPDATE on
budget/billing_period/gateway_request; gateway_request INSERT+UPDATE (rolled back).

### Forbidden SQL denied by MySQL

All 9 negatives denied with ERROR 1142: budget UPDATE, billing_period close,
ledger_posting INSERT, ledger_entry INSERT, gateway_settlement INSERT,
provider_credential UPDATE, gateway_usage_fact DELETE, DDL CREATE, DDL DROP.

The evidence demonstrates DB-engine enforcement for Control-Plane-owned financial truth; Java code comments or architecture tests alone are insufficient.

## 6. Load / concurrency / streaming evidence

### 100-way identical replay

B01 PASS. `HTTP 200 x 1, HTTP 409 x 99`, `provider_operations=1`,
`gateway_requests=1`, `effective_reservations=1`, `billable_attempts=1`, `http_200=1`.

```text
gateway_request identity       = 1
effective Reservation          = 1 (<= 1)
economically billable attempt  = 1 (<= 1)
Provider operation             = 1
duplicate Ledger effect        = 0
```

### Budget concurrency

B02 PASS. 1-slot REQUIRED budget: `HTTP 200 x 1, HTTP 429 x 19`,
`provider_operations=1`, `held=31.9488/total=31.9488` (no overspend).

### Non-stream load envelope

B03 PASS. Steps 1/5/20 all `provider_ops == http_200` (1/5/20), no 5xx,
p50/p95/p99 recorded per step (e.g. step=20: p50=627ms p95=761ms p99=761ms).
Settlement exactly-once: `requests=37 settled=31 pending=0
ledger_posting=31 ledger_entry=31`, dup keys 0, cardinality 1:1:1, all postings
`SYSTEM/GATEWAY_SETTLEMENT`. (The old `ledger must be 0` assertion was a harness
bug: Backend settlement legitimately posts Ledger; fixed to exactly-once checks.)

### SSE/active-stream envelope

B04 PASS. Live ceiling 128 sampled burst `HTTP 200 x 5, HTTP 403 x 3`
(`provider_ops=5`, OPTIONAL-mode budget 403 by design — saturation, not product
failure). Deterministic ceiling proof on scratch probe (ceiling=2, same image):
`active_streams=2.0 provider_ops=2 mock_held=2`, ceiling+1 `HTTP 429
GATEWAY_RATE_LIMITED` with `ops=2` (zero new dispatch), holders complete 200
2/2, gauge returns to 0.0, fresh stream 200 with `[DONE]`.

**B04 product bug found and fixed (RED → root cause → minimal GREEN):**
the outer controller Mono's `doFinally` released the stream permit when SSE
headers committed, freeing the ceiling slot while upstream still occupied it
(`active_streams=0.0` with 2 held streams, ceiling+1 wrongly admitted 200).
Fix: streaming permits release ONLY through the Flux body's `doFinally`
(`releaseStreamPermit`); non-stream/early-failure permits keep the outer path
(`releasePermit`). Regression: new `StreamPermitCeilingIntegrationTest`
(RED without fix: `active streams never reached 1`; GREEN with fix 1/1).

### Measured operating envelope

No invented SLO. Observed: 32-way burst on a 1200 budget admits 32/32
(`held=1022.36/1200`, no saturation at this concurrency); live-128 ceiling
never saturated by the 8-stream sample (budget 403s are the binding constraint
in the shared org). First saturation proven deterministically at ceiling=2.
Dominant bottleneck in acceptance is budget/quota policy, not the stream
Semaphore. Recommended: keep `max-active-streams=128` default; size per
upstream capacity with the B04 probe pattern.

## 7. Failure / restart / recovery evidence

### MySQL pre-dispatch failure

C01 PASS. `mysql-down: HTTP 503 provider_ops=0` (fail closed, zero dispatch).

### MySQL post-dispatch failure

C02 PASS. Mid-flight `UPSTREAM_ACTIVE` across MySQL stop/start stays
`UPSTREAM_ACTIVE`, `ops=1`; replay same identity `HTTP 409 ops=1` (no second
execution); fresh work 200.

### MySQL restart

C03 PASS. Readiness 200 after restart; `after-restart: HTTP 200 ops=1`.

### Redis outage / restart / state loss

C04/C05 PASS. `redis-down: HTTP 503 ops=0` (fail closed); after-restart 200/1;
FLUSHALL then `HTTP 200 ops=1` (MySQL truth governs, no fabricated budget).

### Gateway crash windows

C06 PASS. `docker kill -s KILL` mid-`UPSTREAM_ACTIVE`: durable state survives,
replay `HTTP 409 ops=1`, fresh work 200. (`Start-Gateway` uses the rebuilt
`ai-costops-gateway:m16` image, so the B04 fix is active post-restart.)

### Backend settlement crash/restart

C07 PASS. Baseline 200, backend restart, readiness 200, `settled=1
ledger_postings=1 requests=1` (exactly-once convergence).

### Reservation recovery

C09 PASS. `ReservationRecoveryIntegrationTest` 7/7 GREEN (RELEASED vs
PENDING_HOLD per dispatch evidence; TTL never invents no-charge).

### Settlement retry

C08 PASS. `GatewaySettlementTransactionIntegrationTest`
(concurrentWorkers + repeatedSettlement) 2/2 GREEN: one settlement, one
financial mutation, no double post.

## 8. Provider / routing resilience evidence

### Certified SAFE failure

D01 PASS. `GatewaySafeFailoverIntegrationTest` 4/4 GREEN (DNS SAFE failure
releases A and dispatches B once; static skip → INITIAL_FALLBACK).

### BILLABLE_POSSIBLE failure

D02 PASS. `http500 first: HTTP 502 ops=1; replay: HTTP 409 ops=1`, latest
attempt `BILLABLE_POSSIBLE` (no blind redispatch, no failover).

### Client disconnect

Covered by B04 release proof (permits return to 0 on cancel/complete) and the
streaming cancellation integration suites in §14.

### Live credential revoke

D03 PASS. Baseline A 200; revoked-key B `HTTP 401 ops_before=1 ops_after=1`
(zero new dispatch); `requests before=2 after=2` (no new billable state);
A's incurred work settles normally (D02 replay path).

## 9. Observability / alert evidence

### Prometheus targets

E01 PASS at `fe18f97`+working-tree (full-run gate).
`prometheus scrapes m16-gateway + m16-backend up=1`. Prometheus v2.54.1 on the
isolated `m16-accept-net` scraped BOTH `m16-backend-accept:8080` and
`m16-gateway-accept:8081` at `/actuator/prometheus`, both `health="up"`.
Committed `deploy/observability/prometheus/prometheus.yml` carries both jobs.

### Gateway metrics

Verified live Gateway exports: `gateway_request_total`,
`gateway_usage_total{FINAL,UNKNOWN}`, `gateway_metering_unknown_total`,
`gateway_provider_safety_total`, `gateway_routing_decision_total`,
`gateway_quota_total`, `gateway_reservation_recovery_total`, plus JVM/HTTP/
Hikari built-ins. Backend exports `aicostops_reconciliation_run_total`
(and the full `aicostops.gateway.*` settlement/reconciliation catalog when
the worker emits them). M16 added the missing `gateway_active_streams`
gauge (RED->GREEN: `GatewayMetricsTest.activeStreamsGaugeReflectsLimiterState`
+ `GatewayMetrics.bindActiveStreams` + `GatewayResourceLimiter.activeStreams`
+ `GatewayActiveStreamsConfiguration` startup binding; `GatewayMetricsTest`
2/2, `GatewayResourceLimiterTest` 4/4 GREEN). Gateway image rebuilt
(`ai-costops-gateway:m16`) and live-scrape confirmed: direct
`/actuator/prometheus` exposes `gateway_active_streams 0.0`, and Prometheus
query `gateway_active_streams{job="m16-gateway"}` returns 0. Full Gateway
unit suite 113/113 GREEN after the change.

### Alert injection results

E02 PASS at `fe18f97`+working-tree (full-run gate, `focused step
alert-injection`). `http500` x3 → `502 x 3`; `/api/v1/alerts` shows
`M16GatewayProviderSafetyBillablePossible:firing`,
`M16GatewayUnknownUsageSpike:firing`, `M16GatewayMeteringUnknownSpike:firing`.
Thresholds linked to M16 measurements (see rule comments), not invented SLOs.
(Earlier drill at `9e585ed` additionally proved circuit-open 403 on the 6th
injection with recovery to 200; backlog rules correctly inactive when healthy.)

## 10. Security / privacy leak scan

E03 PASS at `fe18f97`+working-tree (full-run gate,
`invoke-m16-leakscan.ps1 exit 0`, `M16_E03_PASS`).

### Synthetic sentinels

Use only fake values. Never paste real secrets into this document.

### Surfaces scanned

Gateway logs, mock Provider logs, `/actuator/prometheus` snapshot, error
envelopes (success 200 + failure 401 paths).

### Result

Zero forbidden leakage. Per-surface: gateway-logs raw-key/prompt/idempotency
clean; metrics raw-key/prompt clean; error-body raw-key/prompt clean; provider
secret absent from gateway logs/metrics. Two `info` notes (by design, not
leaks): the client response body carries the completion, and the mock (as the
Provider stand-in) sees prompts — a real Provider sees prompts by design.

```text
raw Gateway key leakage    = 0
Provider secret leakage    = 0
Authorization leakage      = 0
raw Idempotency-Key leak   = 0
prompt leakage             = 0
completion leakage         = 0
```

## 11. V2 backup / restore evidence

E04 PASS at `fe18f97`+working-tree (full-run gate, 2026-09-07 UTC) against the
isolated M16 acceptance stack (MySQL `m16accept` on 127.0.0.1:13307, Gateway
`:18081`, mock Provider, Backend settlement worker on `:18080`).

This run: seed lineage 200 → SETTLED, dump 1089197 bytes, load rc=0, all 21
lineage tables match, 6 semantic comparisons PASS (`gateway_settlement` 304,
`ledger_posting` 304, `ledger_entry` 304, `budget` 62, `budget_reservation`
386, `reconciliation` 9 rows), 3 expected-zero tables zero on both sides,
restored-gateway readiness 200, restored request status API 200, new work on
restored DB 200, isolated restore DB dropped, source untouched.
`M16_E04_PASS (V2 lineage restored; empty-Redis Gateway converges)`.

Earlier drill at `9e585ed` (kept for lineage): 6 requests / 6 settlements /
6 postings / 6 entries exactly-once, reconciliation runs via real
`POST /api/v1/reconciliation-runs` with FINANCE_ADMIN JWT.

Script bugs fixed across drills (RED->GREEN, script-only, no production
change): (1) `Invoke-Root ("sql", $db)` tuple-as-single-arg → named `-Sql/-Db`;
(2) `--databases` dump re-applied `USE m16accept` — removed; (3) inline group
expression ate the docker image reference — URL pre-assembled; (4) Windows
`--result-file` re-encoded binary digest bytes (load failed `Unknown command
'\?'`) → `cmd /c` byte-redirect + `--hex-blob`.

The restored environment includes the durable V2 financial lineage required by the frozen M16 spec and remains financially correct with empty/lost Redis state.

## 12. AI Browser black-box UAT evidence

For every UAT scenario record:

- exact tested SHA;
- actor/role;
- browser steps;
- observed result;
- sanitized screenshot/evidence reference;
- relevant request/business IDs;
- API result where relevant;
- DB/machine invariant result;
- PASS/FAIL.

### F01 — Administrative setup

NOT RUN.

### F02 — Gateway lifecycle

NOT RUN.

### F03 — Budget exhaustion

NOT RUN.

### F04 — Credential revoke

NOT RUN.

### F05 — Reconciliation / CLOSED-period correction

NOT RUN.

### F06 — Permissions

NOT RUN.

### F07 — Usability / visual acceptance

NOT RUN.

## 13. Real Provider certification

NOT RUN.

Record only sanitized metadata. If a production Provider/source-schema exact-correlation profile cannot be certified, keep the limitation explicit and do not claim PASS.

## 14. Full regression evidence

Local full regression on the machine-acceptance working tree (all GREEN):

### Backend

- unit: 487 tests, 0 failures (1 skipped)
- architecture: 36 tests, 0 failures
- integration: 1033 tests, 0 failures

### Gateway

- unit: 113/113 GREEN (incl. `GatewayMetricsTest`, `GatewayResourceLimiterTest`,
  `GatewayProductionConfigurationValidatorTest`)
- architecture: covered in unit phase (7 architecture tests GREEN)
- integration: 81 tests, 0 failures (incl. new `StreamPermitCeilingIntegrationTest` 1/1)

### Frontend

- lint PASS, 48 test files / 453 tests PASS, build PASS (chunk-size warning only)

### Docker images

- `ai-costops-backend:m16`, `ai-costops-gateway:m16` (with B04 fix),
  `ai-costops-frontend:m16` all build successfully

### High-risk repeated race suites

All GREEN standalone (no sleeps as proof, latch/barrier deterministic):

- `GatewaySettlementTransactionIntegrationTest` 14/14
- `M15HybridRaceMatrixIntegrationTest` 5/5
- `LedgerCorrectionIntegrationTest` 8/8
- `ReservationRecoveryIntegrationTest` 7/7
- `GatewaySafeFailoverIntegrationTest` 4/4
- `StreamPermitCeilingIntegrationTest` 1/1 (new B04 regression)

Prior hosted gates on `b2ef312` (run 34084145157 / 34084145192): CI, Security,
CodeQL Java/Kotlin + JS/TS, Trivy, docker-build, browser-e2e all PASS. New
commit requires fresh hosted runs on the final SHA (see §15).

## 15. Hosted gates

Prior exact PR head: `b2ef3129ffb24d33413c5247d4f8ea53226c8175`
(PR #152, https://github.com/BangShou1st/AI-CostOps/pull/152).

| Gate | Run / job | Exact SHA | Result |
| --- | --- | --- | --- |
| CI (backend-unit/arch/integration, gateway-unit/arch/integration, frontend lint/test/build, docker-build, browser-e2e) | 34084145157 | b2ef312 | PASS (all green) |
| Security | 34084145192 (CodeQL+Trivy workflow) | b2ef312 | PASS (see CodeQL/Trivy rows) |
| CodeQL Java/Kotlin | 34084145192 / job 101624864364 | b2ef312 | PASS (4m0s) |
| CodeQL JS/TS | 34084145192 / job 101624864545 | b2ef312 | PASS (1m33s) |
| Trivy (filesystem+images) | 34084145192 / job 101624864578 | b2ef312 | PASS (3m19s) |
| Docker backend/frontend/gateway builds | 34084145157 docker-build | b2ef312 | PASS (1m52s) |
| Browser E2E (hosted job) | 34084145157 / job 101624864509 | b2ef312 | PASS (3m18s, automated suite — NOT a substitute for F01-F07 black-box UAT) |
| M16 hosted acceptance, if added | — | — | NOT RUN (no dedicated M16 workflow added) |

Final SHA `429b8894b37a614e5e863d36545816a683c2b4f8` (PR #152):

- CI run 34117602948: SUCCESS — backend-unit, backend-architecture,
  backend-integration, gateway-unit, gateway-architecture, gateway-integration,
  frontend-lint, frontend-test, frontend-build, docker-build, browser-e2e
  (automated suite — NOT a substitute for F01–F07 black-box UAT).
- Security run 34117602928: SUCCESS — CodeQL java-kotlin, CodeQL
  javascript-typescript, Trivy filesystem+images.

## 16. Findings

### P0

P0 = 0. One product defect found and fixed by M16 acceptance itself (B04 stream
permit early release — see §6); no open P0.

### P1

P1 = 0. Harness-only issues found and fixed (B03 zero-Ledger mis-assertion,
B04 timeout-hold flakiness, B05 budget masking, C09/D01 orchestrator env +
failsafe misattribution, E04 Windows dump encoding); no open P1.

### P2 / limitations

- Real Provider certification remains to be performed (BLOCKED: operator
  credential unavailable; mock never claimed as real).
- F01–F07 Browser black-box UAT remains to be performed (BLOCKED: needs browser
  execution; hosted browser-e2e job is NOT a substitute).
- Production-grade exact reconciliation correlation remains dependent on
  operator-certified provider/source-schema correlation profile where applicable.

These are explicit external-gate blockers, not evidence of PASS.

## 17. Final release gate

Current result:

```text
M16 MACHINE ACCEPTANCE = COMPLETE (A01-A04, B01-B05, C01-C09, D01-D06, E01-E04 all PASS)
M16 EXTERNAL GATES = BLOCKED (F01-F07 browser UAT; real Provider certification)
M16 = NOT COMPLETE (machine complete, external blocked)
V2 Production Acceptance = NOT YET PASSED
v2.0.0 = NOT YET RELEASE CANDIDATE
DO NOT MERGE (merge needs Sol review of the exact final head + user authorization)
```

This section may change to PASS only after:

- every mandatory acceptance-matrix row is PASS (machine rows: done);
- F01–F07 browser UAT PASS + real Provider certification PASS (blocked);
- global financial invariants are all satisfied (done, §2);
- hosted CI/Security/CodeQL/Trivy are green on the exact final PR head (TODO after push);
- P0=0 and P1=0 (done);
- Sol independently reviews that exact head and records `SOL FINAL REVIEW = PASS`;
- any subsequent commit triggers a new final review.

Merge is not part of acceptance execution. It requires separate explicit user authorization.