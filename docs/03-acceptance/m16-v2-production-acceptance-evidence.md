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
| E01 | Prometheus | Backend + Gateway scraped | NOT RUN | — | — |
| E02 | Alerts | Injected failures produce intended signals | NOT RUN | — | — |
| E03 | Leak scan | Zero forbidden sentinel leakage | NOT RUN | — | — |
| E04 | V2 restore | Full durable financial lineage recoverable without Redis | NOT RUN | — | — |
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

NOT RUN.

### Gateway metrics

NOT RUN.

### Alert injection results

NOT RUN.

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

NOT RUN.

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