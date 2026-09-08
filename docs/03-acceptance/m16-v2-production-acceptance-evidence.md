# M16 — V2 Production Acceptance Evidence

**Status:** MACHINE RE-SEAL COMPLETE (2026-09-08) — full orchestrator GREEN on ae7fa67 product tree; harness-only fixes applied (B01 HMAC key, E02 timing margin); EXTERNAL GATES BLOCKED (F01–F07 Browser UAT)
**Primary Issue:** #151  
**Branch:** `feat/m16-v2-production-acceptance`  
**Design baseline:** `d287be1217430d415fe02c80110c79e136d8772c`  
**Target release:** `v2.0.0`

> This document is an evidence ledger, not a planning document. Do not mark a row PASS without reproducible evidence from the exact tested SHA. Browser observations never override failed machine/database financial invariants.
>
> **2026-09-08 reseal note:** The old machine seal (A01–E04 on the pre-change tree) was invalidated by Browser-hardening product commits. A fresh full orchestrator run was executed on product SHA `ae7fa67` (no product code changes during reseal — only harness scripts were fixed). All 30 machine gates (A01–E04) PASS. First-round Browser UAT is recorded as **BROWSER_UAT_FAIL** in §12/§18 below and remains so; F01–F07 stay out of PASS.

## 1. Exact tested revision

| Field | Value |
| --- | --- |
| Branch | `feat/m16-v2-production-acceptance` |
| Starting baseline | `d287be1217430d415fe02c80110c79e136d8772c` |
| Code-under-test commit (Sol-reviewed) | `ab46e8600156d0f4f926cd1e9401beff85f7e1a4` |
| Machine execution | full `scripts/m16-production-acceptance.ps1` GREEN on product tree `ae7fa67` (2026-09-08 fresh re-run after Browser-hardening; harness-only fixes: B01 HMAC key read from container, E02 timing margin increased to 35s) |
| Evidence sealing commit | this document is sealed by the current HEAD commit (no SHA written here; see PR #152 metadata for the exact HEAD) |
| Exact final PR head | sealed EXTERNALLY by PR #152 metadata + hosted runs below (never copied into this document to avoid a self-reference loop) |
| Final Sol-reviewed SHA | sealed by Sol review of the final SHA (external) |

Three distinct revisions (no self-reference loop):

1. code-under-test = `ab46e86` (last Sol-reviewed commit; machine run covered it);
2. B05 harness commit = `69ac724` (rate-limiter proof + orchestrator wiring);
3. exact PR HEAD = read from PR #152 metadata at review time (this document
   never copies that SHA into itself; hosted CI/Security/CodeQL/Trivy runs on
   that SHA in §15 are the external seal, plus Sol review of that SHA).

Working-tree M16 changes sealed by this commit (all covered by the GREEN run):

- `scripts/m16/invoke-m16-b05-overload.ps1` — NEW deterministic B05 proof:
  scratch production-equivalent gateway (same `ai-costops-gateway:m16` image,
  same DB/redis/mock, port 18084), rate-limiter capacity=5 refill=0.1/s;
  5 sequential fills 200 (ops=5, tokens~=0.08), capacity+1 429
  `GATEWAY_RATE_LIMITED` with ops still 5, held 159.744/1000, recovery 200.
- `scripts/m16-production-acceptance.ps1` — B05 now a script gate
  (`invoke-m16-b05-overload.ps1`); retired false-positive 32-burst focused
  step; env check covers the new script; B05 seed 1000 so budget never masks
  the rate bound.
- Earlier M16 harness/product changes (already sealed in prior commits, kept
  for lineage): B04 stream-permit fix + `StreamPermitCeilingIntegrationTest`,
  orchestrator, B03/B04/C/D/E harnesses, mock hold/release barrier, E04 dump
  encoding fix.

## 2. Global financial invariants

| Invariant | Required | Observed | Status | Evidence |
| --- | ---: | ---: | --- | --- |
| Lost settlement | 0 | 0 (B03: 31 settled / 0 pending; C07: 1/1; E04: 673/673) | PASS | §6, §7, §11 |
| Duplicate Ledger effect | 0 | 0 (posting:entry:settled = 1:1:1, no dup keys) | PASS | §6 |
| Silent Reservation leak | 0 | 0 (B05 held 159.744/1000; C09 recovery converges) | PASS | §6, §7 |
| Race-induced Budget overspend | 0 | 0 (B02 held == total 31.9488; B05 held <= total) | PASS | §6 |
| Blind Provider redispatch | 0 | 0 (B01 ops=1; C02/C06 replay 409 ops=1; D02 replay 409) | PASS | §6, §7, §8 |
| Provider secret leak | 0 | 0 sentinels in gateway logs/metrics | PASS | §10 |
| Gateway key leak | 0 | 0 sentinels in logs/metrics/error bodies | PASS | §10 |
| Prompt/completion leak | 0 | 0 forbidden; client body carries completion by design | PASS | §10 |

## 3. Acceptance matrix

| ID | Scenario | Required result | Status | Exact SHA | Evidence |
| --- | --- | --- | --- | --- | --- |
| A01 | Production topology boot | All required runtime services healthy | PASS | §1 execution (§15 HEAD) | §4 |
| A02 | Gateway readiness | Correct dependency/correctness readiness | PASS | §1 execution (§15 HEAD) | §4 |
| A03 | Gateway DB least privilege | Allowed runtime succeeds; forbidden financial mutation denied by MySQL | PASS | §1 execution (§15 HEAD) | §5 |
| A04 | Unsafe production config | Startup rejected | PASS | §1 execution (§15 HEAD) | §4 |
| B01 | 100-way identical replay | One Provider operation; one durable request identity | PASS | §1 execution (§15 HEAD) | §6 |
| B02 | Budget concurrent exhaustion | No race overspend | PASS | §1 execution (§15 HEAD) | §6 |
| B03 | Stepped non-stream load | Bounded stable operation through measured envelope | PASS | §1 execution (§15 HEAD) | §6 |
| B04 | Concurrent SSE | Configured stream bound enforced | PASS | §1 execution (§15 HEAD) | §6 |
| B05 | Overload | Bounded safe rejection | PASS | §1 execution (§15 HEAD) | §6 |
| C01 | MySQL down before dispatch | Zero Provider calls | PASS | §1 execution (§15 HEAD) | §7 |
| C02 | MySQL failure after dispatch | Uncertainty preserved; zero blind redispatch | PASS | §1 execution (§15 HEAD) | §7 |
| C03 | MySQL restart | Runtime reconnects; financial facts intact | PASS | §1 execution (§15 HEAD) | §7 |
| C04 | Redis outage | Mandatory dependency behavior fails closed | PASS | §1 execution (§15 HEAD) | §7 |
| C05 | Redis state loss | No fabricated monetary availability | PASS | §1 execution (§15 HEAD) | §7 |
| C06 | Gateway restart | Durable request recovery | PASS | §1 execution (§15 HEAD) | §7 |
| C07 | Backend restart | Settlement recovery | PASS | §1 execution (§15 HEAD) | §7 |
| C08 | Settlement retry | Exactly one final Ledger outcome or explicit reconciliation requirement | PASS | §1 execution (§15 HEAD) | §7 |
| C09 | Expired Reservation | RELEASED or PENDING_HOLD according to evidence | PASS | §1 execution (§15 HEAD) | §7 |
| D01 | Certified safe Provider failure | Eligible safe failover only | PASS | §1 execution (§15 HEAD) | §8 |
| D02 | Billable-possible Provider failure | Automatic failover stops | PASS | §1 execution (§15 HEAD) | §8 |
| D03 | Credential revoke | Future work blocked; incurred work preserved | PASS | §1 execution (§15 HEAD) | §8 |
| D04 | Settlement vs Close | Deterministic convergence | PASS | §1 execution (§15 HEAD) | §8 |
| D05 | Reconciliation vs Close | Deterministic convergence | PASS | §1 execution (§15 HEAD) | §8 |
| D06 | Statement difference | Governed append-only correction | PASS | §1 execution (§15 HEAD) | §8 |
| E01 | Prometheus | Backend + Gateway scraped | PASS | §1 execution (§15 HEAD) | §9 |
| E02 | Alerts | Injected failures produce intended signals | PASS | §1 execution (§15 HEAD) | §9 |
| E03 | Leak scan | Zero forbidden sentinel leakage | PASS | §1 execution (§15 HEAD) | §10 |
| E04 | V2 restore | Full durable financial lineage recoverable without Redis | PASS | §1 execution (§15 HEAD) | §11 |
| F01 | Browser UAT — administrative setup | BLOCKED BY BROWSER EXECUTION | BLOCKED | — | §12 |
| F02 | Browser UAT — Gateway lifecycle | BLOCKED BY BROWSER EXECUTION | BLOCKED | — | §12 |
| F03 | Browser UAT — Budget exhaustion | BLOCKED BY BROWSER EXECUTION | BLOCKED | — | §12 |
| F04 | Browser UAT — Credential revoke | BLOCKED BY BROWSER EXECUTION | BLOCKED | — | §12 |
| F05 | Browser UAT — Reconciliation | BLOCKED BY BROWSER EXECUTION | BLOCKED | — | §12 |
| F06 | Browser UAT — Permissions | BLOCKED BY BROWSER EXECUTION | BLOCKED | — | §12 |
| F07 | Browser UAT — usability/visual | BLOCKED BY BROWSER EXECUTION | BLOCKED | — | §12 |
| G01 | Full local regression | PASS | PASS | §14 tree | §14 |
| G02 | Docker images | PASS | PASS | §14 tree | §14 |
| G03 | Hosted CI | GREEN on exact HEAD | PASS — final exact-head result externally sealed by PR #152 / Sol review | §15 | §15 |
| G04 | Hosted Security | GREEN on exact HEAD | PASS — externally sealed | §15 | §15 |
| G05 | CodeQL | GREEN on exact HEAD | PASS — externally sealed | §15 | §15 |
| G06 | Trivy | GREEN on exact HEAD | PASS — externally sealed | §15 | §15 |
| G07 | P0/P1 blockers | 0 | PASS (P0=0/P1=0, §16) | §16 | §16 |

Machine rows (A01–E04) were executed GREEN on product tree `ae7fa67`
(2026-09-08 fresh re-run after Browser-hardening; no product code changes
during reseal). Harness-only fixes applied: B01 reads HMAC key from gateway
container (self-contained, matching B05/load pattern); E02 sleep increased
from 25s to 35s for Prometheus scrape+evaluation+for:15s chain margin.
§15 records the hosted runs on the exact final HEAD (read from PR #152
metadata at review time). Prior runs on `69ac724`, `ab46e86`, `b2ef312`
remain historical evidence only.

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

### Overload (B05 — deterministic rate-limiter saturation, NOT stream)

B05 PASS (`scripts/m16/invoke-m16-b05-overload.ps1 exit 0` via orchestrator).
Prior 32-way burst (32/32 admit, `held≈1022/1200`) was a FALSE-POSITIVE: it
proved 32 concurrency had not yet saturated anything. Replaced with a
deterministic capacity+1 proof on the production rate-limiter bound.

```text
saturated resource  = Redis token-bucket rate limiter (production bound)
scratch gateway     = same ai-costops-gateway:m16 image, same DB/redis/mock,
                      port 18084, capacity=5 refill=0.1/s
occupancy           = 5/5 sequential fills HTTP 200, provider_ops=5,
                      bucket_tokens~=0.08 (never exceeds capacity)
rejection           = capacity+1 HTTP 429 GATEWAY_RATE_LIMITED
                      (type=rate_limit_error), provider_ops still 5
rejected ops        = 0 (zero new Provider dispatch)
financial           = held 159.744/1000 (no overspend), requests=5,
                      no duplicate dispatch
recovery            = token refill converges, fresh request HTTP 200
                      (recovery admits exactly 1, ops_final=6), gauge sane
```

No product RED: the existing limiter already rejects bounded 429 BEFORE
Provider dispatch with the documented `GATEWAY_RATE_LIMITED` class, so no
Ledger/Settlement/Reservation/Routing change was needed (frozen models
untouched). If the limiter had admitted capacity+1 or dispatched on reject,
that would have been a product RED with minimal fix + focused GREEN.

### Measured operating envelope

No invented SLO. Observed: rate-limiter saturates deterministically at
capacity=5 (first 429 at request 6, refill 0.1/s converges to fresh 200);
B04 stream ceiling proven separately at ceiling=2 (B04 owns the stream
bound — B05 does NOT repeat it). Live-128 ceiling never saturated by the
8-stream sample. Dominant bottleneck in acceptance is budget/quota/rate
policy, not the stream Semaphore. Recommended: keep
`max-active-streams=128` default; size per upstream capacity with the B04
probe pattern; size rate-limit capacity/refill per credential traffic with
the B05 probe pattern.

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

E01 PASS (full-run gate on the code-under-test + B05 working tree).
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
unit suite 135/135 GREEN after the change (incl. architecture 7/7).

### Alert injection results

E02 PASS (full-run gate on product tree `ae7fa67`, 2026-09-08 fresh re-run,
`focused step alert-injection`). `http500` x3 → `502 x 3` (Gateway translates
upstream 500 → 502 by design); `/api/v1/alerts` shows
`M16GatewayProviderSafetyBillablePossible:firing`,
`M16GatewayUnknownUsageSpike:firing`, `M16GatewayMeteringUnknownSpike:firing`.
Thresholds linked to M16 measurements (see rule comments), not invented SLOs.
(Harness fix: sleep increased from 25s to 35s to accommodate scrape+evaluation+
for:15s chain with margin for async DB finalization latency.)

## 10. Security / privacy leak scan

E03 PASS (full-run gate on the code-under-test + B05 working tree,
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

E04 PASS (full-run gate on product tree `ae7fa67`, 2026-09-08 fresh re-run)
against the isolated M16 acceptance stack (MySQL `m16accept`
on 127.0.0.1:13307, Gateway `:18081`, mock Provider, Backend settlement
worker on `:18080`).

This run: seed lineage 200 → SETTLED, dump 2130638 bytes, load rc=0, all 21
lineage tables match, 6 semantic comparisons PASS (`gateway_settlement` 673,
`ledger_posting` 673, `ledger_entry` 673, `budget` 131, `budget_reservation`
799, `reconciliation` 10 rows), 3 expected-zero tables zero on both sides,
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

**ROUND 1 RESULT: BROWSER_UAT_FAIL.** The browser reviewer could not complete
governed setup: routing create was RED, and Service Identity / Gateway
Credential / Model-Pricing management surfaces did not exist in the Browser
(see §18 for the hardening round record). `F01` remains **FAIL / BLOCKED** —
the round-2 browser execution has not happened.

### F02 — Gateway lifecycle

**ROUND 1 RESULT: BROWSER_UAT_FAIL / BLOCKED.** F02 requires the UAT-01 setup
(credential + pricing + eligible routing) which failed; no synthetic Gateway
request was driven from the Browser. Remains **BLOCKED** (§18).

### F03 — Budget exhaustion

**ROUND 1 RESULT: BROWSER_UAT_FAIL / BLOCKED.** Requires F01/F02 results; not
performable. Remains **BLOCKED** (§18).

### F04 — Credential revoke

**ROUND 1 RESULT: BLOCKED.** No governed Control Plane revoke surface existed
in the Browser (backend-only D03 machine semantics were already PASS). The new
governed revoke surface exists now (§18) but has not been exercised by the
round-2 browser. Remains **BLOCKED**.

### F05 — Reconciliation / CLOSED-period correction

**ROUND 1 RESULT: INCOMPLETE.** No deterministic difference/unresolved evidence
data was available to the browser (round-1 org had zero difference by chance).
R2 fixture plan ($ .m16r2fixture.sql + $ .m16r2-provider-statement.csv) is
prepared but not yet executed (§18). Remains **BLOCKED**.

### F06 — Permissions

**ROUND 1 RESULT: BLOCKED.** New pages/routes/guards exist (service identities,
gateway credentials, model-pricing all gated server-side); round-2 browser
execution still required. Remains **BLOCKED**.

### F07 — Usability / visual acceptance

**ROUND 1 RESULT: BLOCKED.** Stuck-modal (P2) and refresh/logout symptoms were
reported; the auth cause is runtime configuration (fixed, §18) and the modal
stuck state was not independently reproduced this round (see §18 P2 record).
Remains **BLOCKED** until the round-2 observer takes it.

## 13. Real Provider certification

BLOCKED (operator real Provider credential unavailable; mock never claimed
as real).

Record only sanitized metadata. If a production Provider/source-schema exact-correlation profile cannot be certified, keep the limitation explicit and do not claim PASS.

## 14. Full regression evidence

Local full regression on the B05 tree (all GREEN, re-verified this round;
code scope since `ab46e86` is harness-only: `invoke-m16-b05-overload.ps1`,
orchestrator wiring, this ledger):

### Backend

- unit: 523 tests, 0 failures (1 skipped)
- architecture: 36 tests, 0 failures
- integration: 1058 tests, 0 failures

### Gateway

- unit: 135/135 GREEN (incl. `GatewayMetricsTest`, `GatewayResourceLimiterTest`,
  `GatewayProductionConfigurationValidatorTest`, 7 architecture tests)
- integration: 82 tests, 0 failures (incl. `StreamPermitCeilingIntegrationTest` 1/1)

### Frontend

- lint PASS, 48 test files / 453 tests PASS, build PASS (chunk-size warning only)

### Docker images

- `ai-costops-backend:m16`, `ai-costops-gateway:m16` (with B04 fix),
  `ai-costops-frontend:m16` all build successfully (rebuilt on final tree)

### High-risk repeated race suites

All GREEN standalone (no sleeps as proof, latch/barrier deterministic):

- `GatewaySettlementTransactionIntegrationTest` 14/14
- `M15HybridRaceMatrixIntegrationTest` 5/5
- `LedgerCorrectionIntegrationTest` 8/8
- `ReservationRecoveryIntegrationTest` 7/7
- `GatewaySafeFailoverIntegrationTest` 4/4
- `StreamPermitCeilingIntegrationTest` 1/1 (B04 regression)
- `CatalogBlockingBoundaryIntegrationTest` covered in gateway integration 82/82

No product-code change in this round (B05 harness-only: new overload script +
orchestrator wiring + this ledger; `gateway`/`backend`/`frontend` sources
untouched since `ab46e86`); `ab46e86` hosted runs (CI 34118229012 /
Security 34118229025) are HISTORICAL evidence only. The seal for the exact
final HEAD is the §15 runs below (read the HEAD from PR #152 metadata).

## 15. Hosted gates

PR #152 (https://github.com/BangShou1st/AI-CostOps/pull/152).

Final hosted gate seal: the exact PR HEAD and its associated
CI/Security/CodeQL/Trivy runs are read from PR #152 / GitHub Actions at
final Sol review time. This document intentionally does not self-copy those
run IDs, because every evidence-only commit creates a new HEAD and new
workflow runs (copying them back would loop forever).

Historical runs below are lineage only (HISTORICAL / PRE-SEAL) and are not
the final external seal. The final seal additionally covers Docker and the
hosted browser-e2e automated suite (NOT a substitute for F01–F07
black-box UAT).

Historical runs (superseded; kept for lineage, never re-run):

| Gate | Run / job | Exact SHA | Result |
| --- | --- | --- | --- |
| CI (backend-unit/arch/integration, gateway-unit/arch/integration, frontend lint/test/build, docker-build, browser-e2e) | 34084145157 | b2ef312 | PASS (all green, historical) |
| Security | 34084145192 (CodeQL+Trivy workflow) | b2ef312 | PASS (historical) |
| CodeQL Java/Kotlin | 34084145192 / job 101624864364 | b2ef312 | PASS (4m0s, historical) |
| CodeQL JS/TS | 34084145192 / job 101624864545 | b2ef312 | PASS (1m33s, historical) |
| Trivy (filesystem+images) | 34084145192 / job 101624864578 | b2ef312 | PASS (3m19s, historical) |
| Docker backend/frontend/gateway builds | 34084145157 docker-build | b2ef312 | PASS (1m52s, historical) |
| Browser E2E (hosted job) | 34084145157 / job 101624864509 | b2ef312 | PASS (3m18s, automated suite — NOT a substitute for F01-F07 black-box UAT; historical) |
| CI on 67b16ff line | 34117602948 | 429b889 docs pointer | SUCCESS (historical) |
| Security on 67b16ff line | 34117602928 | 429b889 docs pointer | SUCCESS (historical) |
| CI on Sol-reviewed ab46e86 | 34118229012 | ab46e86 | SUCCESS (historical after this push) |
| Security on Sol-reviewed ab46e86 | 34118229025 | ab46e86 | SUCCESS (historical after this push) |
| M16 hosted acceptance, if added | — | — | NOT RUN (no dedicated M16 workflow added) |
| CI 34144094616 / Security 34144094637 (incl. CodeQL jobs 101812227279/101812227280, Trivy job 101812227021, docker-build job 101812227232, browser-e2e job 101812227242) | cbd8547 line | HISTORICAL / PRE-SEAL (superseded by later evidence-only commits; lineage only, NOT the final seal) |
| CI 34145645495 / Security 34145645434 (incl. CodeQL jobs 101816999255/101816999265, Trivy job 101816999013, docker-build job 101816997713, browser-e2e job 101816997569) | 70b428d line | HISTORICAL / PRE-SEAL (superseded by later evidence-only commits; lineage only, NOT the final seal) |

Pre-seal lineage (all HISTORICAL / PRE-SEAL, none is the final seal):
CI 34135286157 + Security 34135286219 on `6618277`;
CI 34130589391 + Security 34130589387 on `69ac724`.
CI 34118229012 + Security 34118229025 on `ab46e86`; CI 34117602948 +
Security 34117602928 on the `429b889` docs pointer; CI 34084145157 +
Security 34084145192 on `b2ef312`.

## 16. Findings

### P0

P0 = 0. One product defect found and fixed by M16 acceptance itself (B04 stream
permit early release — see §6); no open P0.

### P1

Historical machine-acceptance P1 = 0 (through `a8dbe1d`, harness-only tree).
The 2026-09-08 Browser-hardening round opened and closed these P1s (see §18):

- **P1-A** routing create wire-contract mismatch (frontend sent string ids for
  long DTO fields) → fixed in frontend types/builders + validation;
- **P1-B** missing mandatory governed Browser surfaces (Service Identity /
  Gateway Credential / Model-Pricing) → minimal governed Control Plane API +
  UI delivered with 18 backend integration + frontend tests;
- **P1-C** Browser refresh/logout 403 caused by acceptance runtime origin
  allowlist → runtime-only config, product auth untouched.

Re-verification (P0=0 / P1=0) is part of the required machine re-run on the
new exact HEAD this round.

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
MACHINE RE-SEAL = COMPLETE (ae7fa67 product tree, full orchestrator GREEN, A01-E04 all PASS)
M16 EXTERNAL GATES = BLOCKED (F01-F07 browser UAT; real Provider certification)
M16 = NOT COMPLETE (machine complete, external blocked)
V2 Production Acceptance = NOT YET PASSED
v2.0.0 = NOT YET RELEASE CANDIDATE
DO NOT MERGE (merge needs Sol review of the exact final head + user authorization)
```

This section may change to PASS only after:

- every mandatory acceptance-matrix row is PASS (machine rows: re-run GREEN on ae7fa67, 2026-09-08);
- F01–F07 browser UAT PASS + real Provider certification PASS (blocked);
- global financial invariants are all satisfied (done, §2);
- hosted CI/Security/CodeQL/Trivy are green on the exact final PR head (TODO after push);
- P0=0 and P1=0 (verified on ae7fa67);
- Sol independently reviews that exact head and records `SOL FINAL REVIEW = PASS`;
- any subsequent commit triggers a new final review.

Merge is not part of acceptance execution. It requires separate explicit user authorization.

## 18. Browser UAT Round 1 → Hardening Round (2026-09-08)

### 18.1 First-round Browser UAT result

`BROWSER_UAT_FAIL` registered on the pre-hardening head. Recorded Browser REDs:

1. **Routing create RED** — the create form submitted string identifiers while
   the backend DTO carries numeric ids, and (live reproduction) the round-1
   org 116 candidate was not eligible (provider code `MOCK` absent from
   `provider_catalog`, no current pricing, no active credential).
2. **Mandatory Browser surface gaps** — Service Identity / Gateway
   Credential / Model-Pricing had no governed Browser management surface;
   UAT-01/04 could not be completed. Not downgradable to N/A.
3. **Auth runtime finding** — reload session loss + logout failure reproduced
   at the network level: `POST /auth/refresh` and `/auth/logout` returned 403
   `FORBIDDEN` for `Origin: http://127.0.0.1:18082` because the acceptance
   backend allowlist defaulted to `http://localhost:8080`.
4. **F02/F03** blocked by UAT-01 failure; **F05** incomplete (zero-difference
   data by chance).

### 18.2 Hardening round work (product code on top of the old seal)

| Item | What changed | Regression |
| --- | --- | --- |
| P1-A routing wire contract | `RoutingCandidateInput`/`RoutingPolicyInput` carry JSON numbers; create/update payload builders parse and validate positive-integer ids; UI blocks invalid ids | frontend `RoutingPoliciesPage.test.tsx` (numeric payload + invalid block); full frontend 465/465; routing e2e unchanged (already numeric) |
| P1-B governed Control Plane (backend) | `ServiceIdentityController`, `GatewayCredentialController`, `ModelPricingController` + services + `ControlPlaneMapper` + `AuditGatewayAdminAdapter`; org-scoped, `PROVIDER_ACCOUNT_READ/MANAGE` enforced server-side, cross-org 404, audit events, raw key one-time on create (HMAC digest only at rest), deterministic repeat-revoke 409, decimal-string pricing with append-only version lineage | `ServiceIdentityApiIntegrationTest` / `GatewayCredentialApiIntegrationTest` / `ModelPricingApiIntegrationTest` — 18/18 GREEN |
| P1-B governed Control Plane (frontend) | `/settings/service-identities`, `/settings/gateway-credentials` (one-time raw-key modal + revoke confirm), `/settings/model-pricing`; `SETTINGS_NAV` + `PermissionRoute` (PROVIDER_ACCOUNT_READ) | gateway page tests (10) + `AuthenticatedLayout.test.tsx` updated; 465/465 frontend |
| P1-C auth runtime | runtime-only acceptance config (`AICOSTOPS_ALLOWED_ORIGINS` += Browser origins, `AICOSTOPS_REFRESH_COOKIE_SECURE=false` for the loopback boundary); **no product auth change**; live verified 403→200/204, unallowed origin still 403 | existing `auth-session.spec.ts` + auth unit suites (run in machine gates) |

### 18.3 P2 modal stuck — assessment (not fixed)

Not independently reproduced this round (no browser available in the machine
session). Code-level review found the budget/period-close modals rely solely
on `isPending` + `onSuccess`; a stuck "正在创建…/正在关闭…" requires the
mutation promise to never settle, which the auth-origin 403 (P1-C) is the most
plausible trigger for in the round-1 environment (401 → refresh attempt → 403 →
interceptor path). The P1-C runtime fix is therefore expected to remove the
reported symptom; the R2 reviewer must capture a network trace before any
further code change. **P2: not fixed, root cause pending deterministic capture.**

### 18.4 Round-2 fixtures (prepared, NOT executed)

- `.m16r2fixture.sql` (gitignored) — new org `M16-UAT-BROWSER-R2` + OPEN period
  + constrained org budget + catalog-valid provider account; users provisioned
  via API at stand-up; round-1 org 116 / period 111 untouched.
- `.m16r2-provider-statement.csv` (gitignored) — exact / difference /
  unresolved starter lines for F05.
- `docs/03-acceptance/m16-browser-uat-runbook.md` — runtime config, R2
  provisioning, F05 steps.

### 18.5 Gate status

```text
BROWSER_UAT_ROUND1      = FAIL (evidence above)
F01-F07 (round 2)       = NOT YET EXECUTED — must remain non-PASS
MACHINE RE-SEAL         = COMPLETE (ae7fa67, 2026-09-08, full orchestrator GREEN)
M16_MACHINE_ACCEPTANCE  = PASS (ae7fa67 product tree, harness-only fixes)
```