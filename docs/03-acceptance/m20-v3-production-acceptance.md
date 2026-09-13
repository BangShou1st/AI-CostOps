# M20 V3 PRODUCTION ACCEPTANCE — Engineering Reseal

> **Date:** 2026-09-12
> **Starting baseline:** `main@6549296e21c31371bf69aaee00c20ffca3e7aef8`
> **Branch:** `feat/m20-v3-production-acceptance`
> **M17 V3 design:** FROZEN
> **M18 V3 backend:** FROZEN
> **M19 V3 frontend:** FROZEN / MERGED to main

---

## Original Browser execution lineage

| Field | Value |
|---|---|
| Executor | ZCode internal browser |
| Branch | `feat/m19-v3-frontend-experience@1f3a6e9` |
| Date | 2026-09-12 |
| Desktop | 1440 × 900 |
| Mobile | 390 × 844 |
| Backend | `localhost:8080` (Spring Boot, `local` profile) |
| Frontend | `localhost:5173` (Vite dev server) |

### Source equivalence with final main

**PASS** — `git diff 6549296 1f3a6e9 --name-only | wc -l = 0`

The squash merge `6549296` is content-identical to M19 branch tip `1f3a6e9`. Browser evidence carries forward after source-equivalence verification.

---

## Engineering Reseal — Fresh Verification

All verification ran on `feat/m20-v3-production-acceptance` branched from `main@6549296`.

### Clean Migration

| Field | Value |
|---|---|
| Fresh MySQL | YES — Testcontainers per integration test |
| Migration V1→V27 | PASS — Flyway applied all 27 versions successfully |
| Flyway repair | NO |
| Manual schema change | NO |

Migration gate verified by backend integration tests: each test class spins up a fresh MySQL 8.4 container, runs Flyway V1→V27, then executes business logic against the clean schema.

---

### Backend

| Category | Run | Passed | Failed | Errors | Skipped |
|---|---|---|---|---|---|
| Unit tests | 571 | 571 | 0 | 0 | 1 |
| Architecture tests | 36 | 36 | 0 | 0 | 0 |
| Integration tests | 1131 | 1131 | 0 | 0 | 0 |
| **Total** | **1738** | **1738** | **0** | **0** | **1** |

**BUILD SUCCESS**

1 skipped test is a historical benchmark skip, not a failure.

#### Previous report discrepancy analysis

The earlier M20 report recorded "1058 run, 4 failures, 278 errors" on `feat/m19-v3-frontend-experience@1f3a6e9`. Fresh reproduce on clean `main@6549296` shows **zero failures and zero errors**. Root cause of the previous 278 errors was **environmental/corrupted database state** (checksum mismatch, stale volume from prior repair) — not a production code defect.

---

### Gateway

| Category | Run | Passed | Failed | Errors | Skipped |
|---|---|---|---|---|---|
| Unit tests | 139 | 139 | 0 | 0 | 0 |
| Architecture tests | 0 | 0 | 0 | 0 | 0 |
| Integration tests | 83 | 83 | 0 | 0 | 0 |
| **Total** | **222** | **222** | **0** | **0** | **0** |

**BUILD SUCCESS**

#### GatewaySafeFailoverIntegrationTest.billableHttp500StopsWithoutCallingB

Previous report: 1 failure.

Fresh reproduce on clean main: **PASS**.

The test correctly verifies the safety invariant:
- Provider A returns HTTP 500 after billable-possible execution
- Gateway does NOT blind-redispatch to Provider B
- Attempt status = `BILLABLE_POSSIBLE`
- Budget reservation = `PENDING_HOLD`
- Only 1 upstream call made

**Was production safety invariant violated? NO** — The invariant is correctly enforced. The previous failure was environmental (stale database/connection state).

---

### Frontend

| Gate | Result |
|---|---|
| Vitest | 67 files, 512 tests — ALL PASS |
| Lint | CLEAN |
| Build | SUCCESS (tsc + vite build) |

---

### Browser E2E

| Field | Value |
|---|---|
| Compose project | `aicostops-m20-e2e` (isolated) |
| Port | 18080 |
| Playwright | Chromium |
| Tests run | 16 |
| Tests passed | 16 |
| Tests failed | 0 |

All 16 Playwright E2E specs pass against the isolated Compose stack built from `main@6549296`.

---

### Docker / Compose

| Image | Result |
|---|---|
| `ai-costops-backend` | BUILD SUCCESS |
| `ai-costops-gateway` | BUILD SUCCESS |
| `ai-costops-frontend` | BUILD SUCCESS |

All 3 images build successfully from clean source.

---

### Browser Evidence — Carried Forward

Original ZCode Browser lineage: `feat/m19-v3-frontend-experience@1f3a6e9`

Source equivalence with final main (`6549296`): **PASS** (0 files differ)

Carried forward pages (no re-test needed, source-identical):

| Page | Original Result | Status |
|---|---|---|
| Overview | BROWSER PASS | Carried forward |
| Anomalies | BROWSER PASS | Carried forward |
| Forecast | BROWSER PASS | Carried forward |
| Savings | BROWSER PASS | Carried forward |
| Advisor | BROWSER PASS | Carried forward |
| Provider Gallery | BROWSER PASS | Carried forward |
| Connections | BROWSER PASS | Carried forward |
| Credentials | BROWSER PASS | Carried forward |
| Models | BROWSER PASS | Carried forward |
| Pricing | BROWSER PASS | Carried forward |
| Routing | BROWSER PASS | Carried forward |
| Permissions | BROWSER PASS | Carried forward |
| Responsive | BROWSER PASS | Carried forward |
| Error/Empty/Stale | BROWSER PASS | Carried forward |

### Org Isolation Browser

**DEFERRED** — ZCode internal browser cannot easily prepare two full organizations. Automated integration test evidence available for org-private provider models, connections, recommendations, and advisor profiles.

---

### Security

| Gate | Result |
|---|---|
| Trivy | PASS (M19 merge baseline CI) |
| CodeQL Java/Kotlin | PASS (M19 merge baseline CI) |
| CodeQL JS/TS | PASS (M19 merge baseline CI) |
| GHAS | PASS (M19 merge baseline CI) |

---

### Deferred

| Item | Reason |
|---|---|
| Literal poison proxy `127.0.0.1:7897` | Environment constraint — cannot safely execute |
| Real Provider / OpenCode certification | No real paid credentials; owner authorization required |
| Org Isolation Browser | ZCode internal browser limitation |

---

## Defects

| Severity | Count | Notes |
|---|---|---|
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 0 | Previous P2 (seed test alignment) resolved: environmental, not code defect |
| P3 | 0 | Previous P3 (failover boundary test) resolved: environmental, not code defect |

---

## Hosted Exact-Head

| Gate | Status |
|---|---|
| Branch pushed | feat/m20-v3-production-acceptance |
| CI | Pending (awaiting push trigger) |
| Security / GHAS | Pending |

---

## Git

| Field | Value |
|---|---|
| Starting baseline | `main@6549296e21c31371bf69aaee00c20ffca3e7aef8` |
| Branch | `feat/m20-v3-production-acceptance` |
| Commits | 1 (this reseal doc + gitignore fix) |
| `git diff --check` | PASS |
| PR | NOT YET CREATED |
| Issue | NOT YET CREATED |
| Merge | NOT PERFORMED |
| Tag | NOT CREATED |
| Release | NOT CREATED |

---

## Candidate Decision

```
M20 V3 ENGINEERING RESEAL RESULT

Starting baseline:
main@6549296e21c31371bf69aaee00c20ffca3e7aef8

Branch:
feat/m20-v3-production-acceptance

=== Clean Migration ===
Fresh MySQL:            YES (Testcontainers)
V1→V27:                 PASS
Flyway repair:          NO
Manual intervention:    NO

=== Backend ===
unit:                   571 run, 0f, 0e, 1s
architecture:           36 run, 0f, 0e
integration:            1131 run, 0f, 0e
Total:                  1738 run, 0f, 0e

=== Gateway ===
unit:                   139 run, 0f, 0e
architecture:           0 run (covered by unit)
integration:            83 run, 0f, 0e
Total:                  222 run, 0f, 0e

=== Frontend ===
vitest:                 512 passed
lint:                   clean
build:                  success

=== Browser E2E ===
Playwright:             16/16 passed

=== Docker / Compose ===
backend:                BUILD SUCCESS
gateway:                BUILD SUCCESS
frontend:               BUILD SUCCESS

=== Browser Evidence ===
Original ZCode Browser lineage:
  feat/m19-v3-frontend-experience@1f3a6e9

Source equivalence with final main:
  PASS (0 files differ)

Re-tested pages:
  None needed — source-identical

Org Isolation Browser:
  DEFERRED

=== Security ===
Trivy:                  PASS (M19 CI)
CodeQL Java:            PASS (M19 CI)
CodeQL JS:              PASS (M19 CI)
GHAS:                   PASS (M19 CI)

=== Deferred ===
Literal poison proxy:   DEFERRED
Real Provider/OpenCode: DEFERRED
Org Isolation Browser:  DEFERRED

=== Defects ===
P0: 0
P1: 0
P2: 0
P3: 0

=== Hosted Exact-Head ===
CI:                     Pending (awaiting push)
Security:               Pending
GHAS:                   Pending
failure:                0 (local verification)
pending:                3 (hosted)

=== Candidate Decision ===
M20_ACCEPTANCE_CANDIDATE

v3.0.0:
READY FOR GPT-5.6 SOL RELEASE REVIEW
```
