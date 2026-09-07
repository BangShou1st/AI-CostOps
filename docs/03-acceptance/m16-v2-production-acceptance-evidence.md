# M16 — V2 Production Acceptance Evidence

**Status:** NOT STARTED  
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
| Exact final PR head | NOT YET AVAILABLE |
| Final Sol-reviewed SHA | NOT YET AVAILABLE |

## 2. Global financial invariants

| Invariant | Required | Observed | Status | Evidence |
| --- | ---: | ---: | --- | --- |
| Lost settlement | 0 | NOT RUN | NOT RUN | — |
| Duplicate Ledger effect | 0 | NOT RUN | NOT RUN | — |
| Silent Reservation leak | 0 | NOT RUN | NOT RUN | — |
| Race-induced Budget overspend | 0 | NOT RUN | NOT RUN | — |
| Blind Provider redispatch | 0 | NOT RUN | NOT RUN | — |
| Provider secret leak | 0 | NOT RUN | NOT RUN | — |
| Gateway key leak | 0 | NOT RUN | NOT RUN | — |
| Prompt/completion leak | 0 | NOT RUN | NOT RUN | — |

## 3. Acceptance matrix

| ID | Scenario | Required result | Status | Exact SHA | Evidence |
| --- | --- | --- | --- | --- | --- |
| A01 | Production topology boot | All required runtime services healthy | NOT RUN | — | — |
| A02 | Gateway readiness | Correct dependency/correctness readiness | NOT RUN | — | — |
| A03 | Gateway DB least privilege | Allowed runtime succeeds; forbidden financial mutation denied by MySQL | NOT RUN | — | — |
| A04 | Unsafe production config | Startup rejected | NOT RUN | — | — |
| B01 | 100-way identical replay | One Provider operation; one durable request identity | NOT RUN | — | — |
| B02 | Budget concurrent exhaustion | No race overspend | NOT RUN | — | — |
| B03 | Stepped non-stream load | Bounded stable operation through measured envelope | NOT RUN | — | — |
| B04 | Concurrent SSE | Configured stream bound enforced | NOT RUN | — | — |
| B05 | Overload | Bounded safe rejection | NOT RUN | — | — |
| C01 | MySQL down before dispatch | Zero Provider calls | NOT RUN | — | — |
| C02 | MySQL failure after dispatch | Uncertainty preserved; zero blind redispatch | NOT RUN | — | — |
| C03 | MySQL restart | Runtime reconnects; financial facts intact | NOT RUN | — | — |
| C04 | Redis outage | Mandatory dependency behavior fails closed | NOT RUN | — | — |
| C05 | Redis state loss | No fabricated monetary availability | NOT RUN | — | — |
| C06 | Gateway restart | Durable request recovery | NOT RUN | — | — |
| C07 | Backend restart | Settlement recovery | NOT RUN | — | — |
| C08 | Settlement retry | Exactly one final Ledger outcome or explicit reconciliation requirement | NOT RUN | — | — |
| C09 | Expired Reservation | RELEASED or PENDING_HOLD according to evidence | NOT RUN | — | — |
| D01 | Certified safe Provider failure | Eligible safe failover only | NOT RUN | — | — |
| D02 | Billable-possible Provider failure | Automatic failover stops | NOT RUN | — | — |
| D03 | Credential revoke | Future work blocked; incurred work preserved | NOT RUN | — | — |
| D04 | Settlement vs Close | Deterministic convergence | NOT RUN | — | — |
| D05 | Reconciliation vs Close | Deterministic convergence | NOT RUN | — | — |
| D06 | Statement difference | Governed append-only correction | NOT RUN | — | — |
| E01 | Prometheus | Backend + Gateway scraped | PASS | 9e585ed | §9 |
| E02 | Alerts | Injected failures produce intended signals | PASS | 9e585ed | §9 |
| E03 | Leak scan | Zero forbidden sentinel leakage | NOT RUN | — | — |
| E04 | V2 restore | Full durable financial lineage recoverable without Redis | PASS | 9e585ed | §11 |
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

NOT RUN.

### Health/readiness results

NOT RUN.

### Gateway production configuration negative tests

NOT RUN.

## 5. Gateway database least-privilege evidence

### Effective Gateway grants

NOT RUN.

### Positive allowed operations

NOT RUN.

### Forbidden SQL denied by MySQL

NOT RUN.

The evidence must demonstrate DB-engine enforcement for Control-Plane-owned financial truth; Java code comments or architecture tests alone are insufficient.

## 6. Load / concurrency / streaming evidence

### 100-way identical replay

NOT RUN.

Required final counts:

```text
gateway_request identity       = 1
effective Reservation          <= 1
economically billable attempt  <= 1
Provider operation             = 1
duplicate Ledger effect        = 0
```

### Budget concurrency

NOT RUN.

### Non-stream load envelope

NOT RUN.

### SSE/active-stream envelope

NOT RUN.

### Measured operating envelope

NOT RUN.

Do not state an arbitrary throughput SLO. Record the observed safe operating range, first saturation point, dominant bottleneck, and recommended configuration.

## 7. Failure / restart / recovery evidence

### MySQL pre-dispatch failure

NOT RUN.

### MySQL post-dispatch failure

NOT RUN.

### MySQL restart

NOT RUN.

### Redis outage / restart / state loss

NOT RUN.

### Gateway crash windows

NOT RUN.

### Backend settlement crash/restart

NOT RUN.

### Reservation recovery

NOT RUN.

### Settlement retry

NOT RUN.

## 8. Provider / routing resilience evidence

### Certified SAFE failure

NOT RUN.

### BILLABLE_POSSIBLE failure

NOT RUN.

### Client disconnect

NOT RUN.

### Live credential revoke

NOT RUN.

## 9. Observability / alert evidence

### Prometheus targets

E01 PASS at HEAD `9e585ed` (+ working-tree M16 changes). Prometheus
v2.54.1 on the isolated `m16-accept-net` scraped BOTH `m16-backend-accept:8080`
and `m16-gateway-accept:8081` at `/actuator/prometheus`, both `health="up"`
(`scrapePool` m16-backend / m16-gateway, 5s interval in acceptance).
Verified live: `gateway_request_total` and
`aicostops_reconciliation_run_total` both queryable through Prometheus.
Committed `deploy/observability/prometheus/prometheus.yml` now carries both
`aicostops-backend` (`backend:8080`) and `aicostops-gateway`
(`gateway:8081`) jobs.

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

E02 PASS (injection-verified). Committed
`deploy/observability/prometheus/alerts.yml` adds group `aicostops-m16`
(7 rules, all `health="ok"` in Prometheus): billable-possible safety
spike, UNKNOWN-usage spike, metering-unknown spike, Redis dependency
error, settlement-retry backlog, reconciliation-required backlog,
PENDING_HOLD recovery wave. Thresholds are linked to M16 measurements
(see rule comments), not invented SLOs. Injection: mock Provider
`http500` x5 (non-stream), observed 5x502 + exactly 5 Provider operations
+ `gateway_provider_safety_total{BILLABLE_POSSIBLE}=5` +
`gateway_usage_total{UNKNOWN}=5` + `gateway_metering_unknown_total=5`
(D02 semantics: no blind redispatch). 6th injection hit the expected
circuit-open 403, then recovered to 200 after cooldown. Firing proven via
`/api/v1/alerts`: `M16GatewayUnknownUsageSpike` FIRING and
`M16GatewayMeteringUnknownSpike` FIRING (value ~2.09 in 2m window at
query time). Backlog rules correctly stayed inactive (zero settlement
retries / zero RECONCILIATION_REQUIRED in acceptance = healthy).

Alert thresholds must be linked to M16 measurements where applicable.

## 10. Security / privacy leak scan

### Synthetic sentinels

Use only fake values. Never paste real secrets into this document.

### Surfaces scanned

NOT RUN.

### Result

NOT RUN.

Required:

```text
raw Gateway key leakage    = 0
Provider secret leakage    = 0
Authorization leakage      = 0
raw Idempotency-Key leak   = 0
prompt leakage             = 0
completion leakage         = 0
```

## 11. V2 backup / restore evidence

E04 PASS at HEAD `9e585ed` (+ untracked `scripts/m16/invoke-m16-restore.ps1`).
Executed 2026-09-07 (UTC) against the isolated M16 acceptance stack
(MySQL `m16accept` on 127.0.0.1:13307, Redis DB1 flushed empty, Gateway
`:18081`, mock Provider, Backend settlement worker on `:18080` with Flyway
disabled because the M16 seed database carries V1..V23 tables without a
Flyway history table).

Source truth (all produced through production paths, no SQL fabrication):
`gateway_request`=6, `gateway_route_attempt`=96, `gateway_usage_fact`=54,
`gateway_usage_dimension`=102, `budget_reservation`=57, `budget`=23,
`billing_period`=23, `gateway_credential`=24, `routing_policy`=23,
`routing_policy_candidate`=23, `pricing_version`=23, `pricing_rate`=46,
`provider_account`=23, `provider_credential`=4, `ledger_posting`=6,
`ledger_entry`=6, `gateway_settlement`=6 (all SETTLED, one per request,
exactly-once), `reconciliation_run`=2, `reconciliation_case`=2,
`reconciliation_evidence`=2 (AGGREGATE_SCOPE/MISSING_EXTERNAL), no
`provider_charge_disposition`/`reconciliation_adjustment`/
`gateway_financial_resolution` (no statement charges imported, so none
expected). Settlement/Ledger/Budget-actual/Reservation-FINALIZED were
produced by the real `GatewaySettlementWorker` poll; reconciliation runs
were produced by real `POST /api/v1/reconciliation-runs` calls with a
formally-authorized FINANCE_ADMIN identity (org-scoped role_assignment +
HS256 JWT), one org per user (single-active-membership constraint).

Drill: `scripts/m16/invoke-m16-restore.ps1 -Suffix e04fresh` dumped the
source with `mysqldump --single-transaction --routines --triggers` (WITHOUT
`--databases`, so the dump carries no `USE m16accept` and loads into the
isolated DB), loaded into `m16restore_<stamp>` on the same server, granted
the `gw_m16` runtime identity there, verified all 21 lineage tables match,
booted a Gateway on the restored DB with EMPTY Redis (DB1 FLUSHDB):
readiness 200, restored request status API 200, new work 200 with exactly
one additional Provider operation. Source DB untouched (counts unchanged,
no restored request id present in source); isolated restore DB dropped
afterwards; dump temp file removed; Redis DB1 left empty (DBSIZE=0).

Script bugs fixed during the drill (RED->GREEN, script-only, no
production change): (1) `Invoke-Root ("sql", $db)` tuple-as-single-arg
replaced by named `-Sql/-Db` calls; (2) `--databases` dump re-applied
`USE m16accept` on load and wrote back into the source — removed;
(3) inline `("..."+$restoreDb+"...")` group expression on a
backtick-continued `docker run` line mis-parsed and ate the image
reference (`docker: invalid reference format`) — URL pre-assembled into
`$restoreUrl` and passed as a plain `-e "SPRING_DATASOURCE_URL=$restoreUrl"`.

Final script run output: `M16_E04_PASS (V2 lineage restored; empty-Redis
Gateway converges)`. E04 = PASS.

The restored environment must include the durable V2 financial lineage required by the frozen M16 spec and must remain financially correct with empty/lost Redis state.

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

### Backend

NOT RUN.

### Gateway

NOT RUN.

### Frontend

NOT RUN.

### High-risk repeated race suites

NOT RUN.

## 15. Hosted gates

| Gate | Run / job | Exact SHA | Result |
| --- | --- | --- | --- |
| CI | — | — | NOT RUN |
| Security | — | — | NOT RUN |
| CodeQL Java/Kotlin | — | — | NOT RUN |
| CodeQL JS/TS | — | — | NOT RUN |
| Trivy filesystem | — | — | NOT RUN |
| Trivy backend image | — | — | NOT RUN |
| Trivy frontend image | — | — | NOT RUN |
| Trivy gateway image | — | — | NOT RUN |
| Browser E2E | — | — | NOT RUN |
| M16 hosted acceptance, if added | — | — | NOT RUN |

## 16. Findings

### P0

None recorded yet; M16 execution has not started.

### P1

None recorded yet; M16 execution has not started.

### P2 / limitations

- Real Provider certification remains to be performed.
- Production-grade exact reconciliation correlation remains dependent on operator-certified provider/source-schema correlation profile where applicable.

These are pre-existing acceptance obligations, not evidence of PASS.

## 17. Final release gate

Current result:

```text
M16 = NOT STARTED
V2 Production Acceptance = NOT PASSED
v2.0.0 = NOT YET RELEASE CANDIDATE
```

This section may change to PASS only after:

- every mandatory acceptance-matrix row is PASS;
- global financial invariants are all satisfied;
- hosted CI/Security/CodeQL/Trivy are green on the exact final PR head;
- P0=0 and P1=0;
- Sol independently reviews that exact head and records `SOL FINAL REVIEW = PASS`;
- any subsequent commit triggers a new final review.

Merge is not part of acceptance execution. It requires separate explicit user authorization.