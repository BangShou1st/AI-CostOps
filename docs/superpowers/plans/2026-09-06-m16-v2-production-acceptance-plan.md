# M16 V2 Production Acceptance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove and harden the existing M11–M15 V2 runtime to production-acceptance quality for `v2.0.0` without adding new business scope.

**Architecture:** Preserve the existing Backend Control Plane + Gateway Data Plane + MySQL financial truth + Redis operational-state model. M16 adds production deployment proof, least-privilege DB enforcement, load/failure/recovery harnesses, observability, V2 restore, AI Browser UAT, and only minimal production fixes demonstrated by RED acceptance evidence.

**Tech Stack:** Java 21, Spring Boot, Spring MVC, Spring WebFlux/Reactor Netty, MyBatis/JDBC, MySQL 8.4, Redis, React/TypeScript, Docker Compose, Prometheus/Grafana, Playwright, PowerShell.

**Spec:** `docs/superpowers/specs/2026-09-06-m16-v2-production-acceptance-design.md`

## Global Constraints

- Baseline: `d287be1217430d415fe02c80110c79e136d8772c`.
- Primary Issue: #151.
- Branch: `feat/m16-v2-production-acceptance`.
- MySQL Ledger remains final financial truth.
- Redis is never monetary truth.
- No Provider I/O before committed `DISPATCH_INTENT`.
- No Provider I/O inside DB transactions.
- `BILLABLE_POSSIBLE` remains the conservative result when billable execution cannot be excluded.
- Automatic failover only after positive `SAFE_NO_BILLABLE_EXECUTION` evidence.
- No blind Provider redispatch.
- Missing usage is never interpreted as zero.
- Money remains BigDecimal/DECIMAL/decimal-string.
- Prompt/completion are not persisted by default.
- Do not add V24 unless an observed RED proves a schema change is required.
- No Kafka, Kubernetes, service mesh, speculative R2DBC rewrite, or microservice expansion.
- No sleeps, flaky retries, disabled tests, or weakened assertions to obtain green builds.
- Never use `git reset --hard`, `git clean -fd`, `git clean -fdx`, rebase `main`, force-push, or global Docker prune.
- Never merge without explicit user approval after Sol reviews the exact PR head.

---

## File Structure

Expected M16-owned or M16-modified areas:

- `docs/superpowers/specs/2026-09-06-m16-v2-production-acceptance-design.md` — frozen acceptance contract.
- `docs/superpowers/plans/2026-09-06-m16-v2-production-acceptance-plan.md` — implementation sequence.
- `docs/03-acceptance/m16-v2-production-acceptance-evidence.md` — final evidence ledger.
- `scripts/m16-production-acceptance.ps1` — top-level acceptance orchestrator.
- `scripts/m16/` — focused load/failure/verification helpers.
- root Compose and/or a focused acceptance overlay — complete V2 topology including Gateway.
- `deploy/production/` — production DB identity/provisioning/runbook updates.
- `deploy/observability/prometheus/` — Gateway scrape and alert rules.
- `deploy/observability/grafana/` — Gateway production dashboard updates if repository conventions use file-backed dashboards.
- Gateway production validator and its tests — datasource credential fail-fast hardening.
- `.github/workflows/ci.yml` and/or a focused M16 workflow only if needed to make the complete V2 acceptance executable in hosted CI.
- project/roadmap/production documentation — status refresh only after gates truly pass.

Keep files focused; do not reorganize unrelated M11–M15 implementation.

---

### Task 1: Baseline verification and M16 branch preflight

**Files:** No production code changes.

**Produces:** Verified implementation starting point.

- [ ] **Step 1: Run the PowerShell preflight**

```powershell
Set-Location "E:\AI-CostOps"
git status
git branch --show-current
git fetch origin --prune
git switch feat/m16-v2-production-acceptance
git pull --ff-only origin feat/m16-v2-production-acceptance
git rev-parse HEAD
git rev-parse origin/main
git status
```

Expected: branch is `feat/m16-v2-production-acceptance`; no tracked local modifications are lost. Existing untracked local helper files such as `.zcode/` or `start-dev.bat` must not be staged accidentally.

- [ ] **Step 2: Read the frozen sources before coding**

Read M10–M15 detailed design and acceptance evidence plus this M16 spec/plan. Do not redesign already accepted financial semantics.

- [ ] **Step 3: Record starting SHA in the evidence file**

Record branch SHA and `origin/main` SHA. If `main` advanced after the branch was created, do not rebase. Report the delta and decide whether a normal merge/update from main is needed before implementation.

---

### Task 2: Complete V2 production-acceptance topology

**Files:**
- Modify: root `compose.yaml` and/or create a dedicated M16 acceptance overlay following existing repository conventions.
- Modify: `.env.example` only where safe/documentational.
- Modify/Create: `deploy/production/` runtime documentation/configuration as required.
- Test: Docker Compose config/health verification.

**Produces:** A production-like topology containing Backend, Frontend, Gateway, MySQL, Redis, MinIO/evidence storage as required, Prometheus, Grafana, and a controlled deterministic Provider endpoint for acceptance.

- [ ] **Step 1: Write a failing topology verification first**

Create a script/test that fails on current M16 branch if the composed service set does not contain a healthy `gateway` service and does not expose the expected backend/gateway health endpoints.

- [ ] **Step 2: Run it and record RED**

Expected current RED: the prior root Compose topology did not include Gateway as a first-class service.

- [ ] **Step 3: Add the minimum topology wiring**

Do not redesign application boundaries. Reuse existing Dockerfiles and environment conventions.

- [ ] **Step 4: Validate configuration**

```powershell
Set-Location "E:\AI-CostOps"
docker compose config
```

Expected: valid configuration, no accidental secret values committed.

- [ ] **Step 5: Boot isolated stack**

```powershell
docker compose up -d --build --wait --wait-timeout 360
docker compose ps
```

Expected: required services healthy.

- [ ] **Step 6: Commit the independently testable topology deliverable**

Use a focused commit message such as `feat(m16): add complete V2 acceptance topology`.

---

### Task 3: Gateway production datasource fail-fast — RED then GREEN

**Files:**
- Modify: `gateway/src/test/java/com/aicostops/gateway/config/GatewayProductionConfigurationValidatorTest.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/config/GatewayProductionConfigurationValidator.java`
- Modify only if necessary: `gateway/src/main/resources/application-prod.yml`

**Produces:** Production startup rejects missing/default/unsafe Gateway datasource credentials while preserving existing HMAC/KEK/resource checks.

- [ ] **Step 1: Add failing tests**

Required cases:

```text
prod + missing datasource username -> reject
prod + missing datasource password -> reject
prod + change-me-local-only password -> reject
prod + explicit safe acceptance credential -> pass
```

- [ ] **Step 2: Run focused test and require RED**

```powershell
Set-Location "E:\AI-CostOps\gateway"
.\mvnw.cmd -B "-Dtest=GatewayProductionConfigurationValidatorTest" test
```

Expected before implementation: at least the newly required datasource-safety assertions fail for the correct reason.

- [ ] **Step 3: Implement minimum validation**

Do not introduce a new secret-management system. Validate the effective production datasource values using the existing startup-validator pattern.

- [ ] **Step 4: Re-run focused test and require GREEN**

Same command; expected `BUILD SUCCESS`.

- [ ] **Step 5: Run all Gateway unit/architecture tests affected by config validation**

- [ ] **Step 6: Commit**

`fix(gateway): reject unsafe production datasource credentials`

---

### Task 4: Real MySQL least-privilege Gateway identity

**Files:**
- Create/Modify: focused DB provisioning under `deploy/production/` or the repository's established initialization path.
- Create: a real MySQL privilege acceptance test/script under `scripts/m16/`.

**Consumes:** Existing Gateway mapper ownership; do not broaden Gateway writes for convenience.

**Produces:** Separate Gateway runtime DB credential enforced by MySQL.

- [ ] **Step 1: Derive required Gateway privileges from actual mapper SQL**

List every table/operation required by Gateway runtime. Treat this as an allowlist.

- [ ] **Step 2: Create the least-privilege user/grants in the isolated acceptance environment**

Backend migration/financial identity remains separate.

- [ ] **Step 3: Positive acceptance using Gateway credential**

Prove required reads/locks and Gateway-owned writes succeed.

- [ ] **Step 4: Negative acceptance using Gateway credential**

Explicitly attempt forbidden financial mutations. Include at minimum Budget actual/committed, Ledger, Settlement, and BillingPeriod close/reopen mutations.

Expected: MySQL permission denial.

- [ ] **Step 5: Record the exact grants and denied operations in M16 evidence**

- [ ] **Step 6: Commit**

`feat(m16): enforce gateway database least privilege`

---

### Task 5: Production acceptance orchestrator

**Files:**
- Create: `scripts/m16-production-acceptance.ps1`
- Create: focused scripts under `scripts/m16/`
- Modify: `docs/03-acceptance/m16-v2-production-acceptance-evidence.md`

**Produces:** Re-runnable acceptance entry point with bounded evidence.

- [ ] **Step 1: Implement environment identity and guardrails**

The script records tested SHA, verifies it is operating on the intended branch/worktree, uses an isolated Compose project name, and refuses destructive global cleanup.

- [ ] **Step 2: Implement scenario result format**

Each scenario records ID, start/end time, PASS/FAIL, sanitized IDs, invariant counts, and evidence paths.

- [ ] **Step 3: Implement final aggregate gate**

The script may print `M16_ACCEPTANCE_PASS` only when every mandatory machine-executable scenario passed.

- [ ] **Step 4: Test the harness itself with a deliberate failing invariant**

Expected: aggregate result is failure and `M16_ACCEPTANCE_PASS` is absent.

- [ ] **Step 5: Commit**

`test(m16): add production acceptance harness`

---

### Task 6: HTTP idempotency and budget concurrency acceptance

**Files:** Focused M16 HTTP/load scripts/tests and controlled mock Provider instrumentation.

**Produces:** Network-level proof of idempotency and monetary concurrency safety.

- [ ] **Step 1: Add a synchronized 100-worker identical replay scenario**

Same credential, same Idempotency-Key, same exact body. Use coordinated start, not arbitrary sleeps.

- [ ] **Step 2: Assert Provider operation count**

Expected exactly one Provider operation.

- [ ] **Step 3: Assert durable DB truth**

Expected one request identity, at most one effective reservation, at most one economically billable route attempt, and no duplicate financial effect.

- [ ] **Step 4: Repeat to expose races**

Use deterministic repetition appropriate to runtime cost; do not suppress failures.

- [ ] **Step 5: Add constrained-Budget concurrency**

Prove admitted Provider work never exceeds available Budget under racing requests.

- [ ] **Step 6: Commit**

`test(m16): prove gateway idempotency and budget concurrency`

---

### Task 7: Streaming load and overload envelope

**Files:** Focused load scripts under `scripts/m16/` plus mock Provider behavior.

**Produces:** Measured safe operating envelope and bounded overload behavior.

- [ ] **Step 1: Baseline non-stream load**
- [ ] **Step 2: Increase concurrency stepwise**
- [ ] **Step 3: Run concurrent long-lived SSE streams**
- [ ] **Step 4: Exercise configured active-stream ceiling and ceiling+1**
- [ ] **Step 5: Observe DB pool, blocking scheduler, Provider/Redis latency, throughput, and p50/p95/p99**
- [ ] **Step 6: Verify overload converges to bounded rejection rather than unbounded queue growth**
- [ ] **Step 7: Record first saturation point and dominant bottleneck; do not invent a throughput SLO**
- [ ] **Step 8: Commit**

`test(m16): add load streaming and overload acceptance`

---

### Task 8: Client-disconnect lifecycle acceptance

**Files:** Focused M16 request/stream scenarios.

**Produces:** Black-box proof of cancellation safety around the dispatch fence.

- [ ] **Step 1: Disconnect before Provider dispatch**

Expected zero Provider operation and safe pre-dispatch convergence.

- [ ] **Step 2: Disconnect after durable `DISPATCH_INTENT`**

Expected conservative possible-billable semantics and no automatic second Provider call.

- [ ] **Step 3: Disconnect mid-stream after observed usage**

Expected observed usage is not discarded; missing final usage is not converted to zero.

- [ ] **Step 4: Assert reservation final state is RELEASED or PENDING_HOLD according to evidence**

- [ ] **Step 5: Commit**

`test(m16): verify client disconnect financial safety`

---

### Task 9: MySQL failure and restart acceptance

**Files:** Focused failure-injection scripts/tests.

**Produces:** Production-level proof of fail-closed and durable recovery semantics.

- [ ] **Step 1: Stop/block MySQL before dispatch fence**

Expected: zero Provider operations.

- [ ] **Step 2: Inject MySQL failure after durable dispatch intent**

Expected: uncertainty retained, no fabricated zero usage/cost, no blind redispatch.

- [ ] **Step 3: Restart MySQL**

Expected: Backend/Gateway reconnect; durable facts intact; workers converge without duplicate financial effect.

- [ ] **Step 4: Record request, attempt, reservation, usage, settlement, and Ledger invariants**

- [ ] **Step 5: Commit**

`test(m16): verify mysql failure and recovery`

---

### Task 10: Redis outage, restart, and state-loss acceptance

**Files:** Focused failure-injection scripts/tests.

**Produces:** Proof that Redis failure cannot create monetary authority.

- [ ] **Step 1: Make Redis unavailable during requests**

Expected mandatory operational policy fails closed where required.

- [ ] **Step 2: Restart Redis**

Expected runtime recovers without duplicate Provider execution.

- [ ] **Step 3: Destroy only the isolated acceptance Redis state and restart empty**

Expected: monetary availability is still derived from MySQL durable facts; no spend headroom is fabricated.

- [ ] **Step 4: Commit**

`test(m16): verify redis outage and state-loss safety`

---

### Task 11: Gateway and Backend crash recovery

**Files:** Focused controlled crash/failpoint scenarios using existing hooks where possible.

**Produces:** Durable process-restart recovery proof.

- [ ] **Step 1: Crash Gateway after VALIDATED**
- [ ] **Step 2: Crash Gateway after Reservation but before Provider dispatch**
- [ ] **Step 3: Crash Gateway immediately after committed DISPATCH_INTENT**
- [ ] **Step 4: Crash Gateway during streaming**
- [ ] **Step 5: Crash after usage observation before downstream convergence**
- [ ] **Step 6: Crash Backend across settlement processing boundaries**
- [ ] **Step 7: For every case verify one legitimate final financial outcome and no blind Provider redispatch**
- [ ] **Step 8: Commit**

`test(m16): verify process crash recovery`

---

### Task 12: Reservation and settlement recovery acceptance

**Files:** Focused M16 recovery scenarios plus reuse of existing integration race tests.

**Produces:** Production-level proof that TTL/retry never invents no-charge evidence.

- [ ] **Step 1: Expire safe pre-dispatch ACTIVE reservation**

Expected `RELEASED`.

- [ ] **Step 2: Expire possible-billable ACTIVE reservation**

Expected `PENDING_HOLD`.

- [ ] **Step 3: Exercise settlement retry/restart**

Expected exactly one final settlement/Ledger effect or explicit reconciliation requirement.

- [ ] **Step 4: Repeat high-risk ownership/concurrency suites**

No sleep-based stabilization.

- [ ] **Step 5: Commit**

`test(m16): verify reservation and settlement recovery`

---

### Task 13: Provider outage and routing safety

**Files:** Controlled mock Provider profiles/scenarios.

**Produces:** Runtime proof that only certified safe failures trigger failover.

- [ ] **Step 1: Exercise positive safe-pre-dispatch failures**

Examples where transport evidence can positively exclude billable execution.

- [ ] **Step 2: Exercise ambiguous/write-possible failures**

Examples include post-write timeout/reset and partial/stream failures.

- [ ] **Step 3: Assert safe cases may use eligible next candidate**
- [ ] **Step 4: Assert billable-possible cases stop automatic failover**
- [ ] **Step 5: Assert at most one economically billable Provider execution**
- [ ] **Step 6: Commit**

`test(m16): certify provider failure routing safety`

---

### Task 14: Live credential revocation acceptance

**Files:** M16 end-to-end scenario using existing Control Plane revoke workflow and Gateway HTTP path.

**Produces:** Proof that revocation blocks future work without corrupting incurred work.

- [ ] **Step 1: Create ACTIVE Gateway credential and dispatch request A**
- [ ] **Step 2: Revoke credential through governed Control Plane**
- [ ] **Step 3: Attempt request B with revoked credential**

Expected: B rejected before Provider.

- [ ] **Step 4: Let A converge financially**

Expected: A's already incurred work settles normally and exactly once.

- [ ] **Step 5: Verify no cache/state path resurrects the revoked credential**
- [ ] **Step 6: Commit**

`test(m16): verify live gateway credential revocation`

---

### Task 15: Preserve M13–M15 concurrency and close/reconciliation invariants

**Files:** Existing tests, modified only if a real defect requires it; M16 may add orchestration.

**Produces:** Regression proof that production hardening did not weaken financial ownership.

- [ ] **Step 1: Repeat Settlement vs Close races**
- [ ] **Step 2: Repeat reconciliation resolution vs Close races**
- [ ] **Step 3: Repeat late FINAL usage / financial-resolution races**
- [ ] **Step 4: Repeat routing/failover concurrency races**
- [ ] **Step 5: Treat every regression as a blocker; do not weaken assertions**

---

### Task 16: Gateway observability and alert wiring

**Files:**
- Modify: `deploy/observability/prometheus/prometheus.yml`
- Modify: `deploy/observability/prometheus/alerts.yml`
- Modify/Create: Grafana provisioning/dashboard files following repository conventions.

**Produces:** Production-observable Gateway and evidence-backed alert rules.

- [ ] **Step 1: Add Gateway scrape target and verify target is UP**
- [ ] **Step 2: Surface frozen Gateway correctness/recovery signals using existing bounded metrics first**
- [ ] **Step 3: Add only missing bounded metrics demonstrated necessary by M16 acceptance**
- [ ] **Step 4: Add alert rules for UNKNOWN usage, settlement/reconciliation backlog, stale PENDING_HOLD, dependency failures, and saturation**
- [ ] **Step 5: Inject representative failures and capture firing/resolved evidence**
- [ ] **Step 6: Derive thresholds from measured M16 behavior**
- [ ] **Step 7: Commit**

`feat(m16): wire gateway production observability`

---

### Task 17: Security/privacy sentinel leak scan

**Files:** M16 acceptance scripts/evidence only unless a real leak is found.

**Produces:** Deterministic proof that forbidden values are absent from acceptance surfaces.

- [ ] **Step 1: Generate synthetic sentinel secrets/content**

Use unique fake values for Gateway key, Provider key, Authorization token, Idempotency-Key, prompt, and completion.

- [ ] **Step 2: Run representative successful and failure scenarios**
- [ ] **Step 3: Recursively scan allowed evidence surfaces**

Include application logs, metrics snapshots, mock Provider diagnostic logs, audit/evidence outputs, browser traces/screenshots where text extraction is feasible, and error responses.

- [ ] **Step 4: Any prohibited occurrence is RED and must be root-caused/minimally fixed**
- [ ] **Step 5: Commit only if test harness or a proven fix changes**

---

### Task 18: Upgrade restore drill from V1 to V2

**Files:**
- Modify: `scripts/ops/restore-drill.ps1`
- Modify: `deploy/production/README.md` as necessary.

**Produces:** Restore proof for V23-era Gateway and reconciliation lineage.

- [ ] **Step 1: Create V2 source dataset**

Include Gateway request, route, usage, reservation lifecycle, settlement/Ledger, and M15 reconciliation/resolution evidence.

- [ ] **Step 2: Backup using supported production procedure**
- [ ] **Step 3: Restore into an isolated environment**
- [ ] **Step 4: Start with empty/fresh Redis**
- [ ] **Step 5: Compare restored durable financial lineage to source truth**
- [ ] **Step 6: Explicitly document that HMAC/KEK recovery is external to DB backup**
- [ ] **Step 7: Change pass marker from M9-only semantics to a V2/M16-specific pass marker**
- [ ] **Step 8: Commit**

`test(m16): extend restore drill to V2 financial lineage`

---

### Task 19: AI Browser black-box UAT preparation and execution

**Files:**
- Create/Modify: UAT instructions/evidence sections under `docs/03-acceptance/`.
- Modify product code only when Browser UAT reveals a reproducible defect.

**Produces:** Human-like black-box V2 product acceptance.

- [ ] **Step 1: Prepare isolated synthetic acceptance identities/data**
- [ ] **Step 2: Run UAT-01 administrative setup**
- [ ] **Step 3: Run UAT-02 Gateway lifecycle**
- [ ] **Step 4: Run UAT-03 Budget exhaustion**
- [ ] **Step 5: Run UAT-04 Credential revoke**
- [ ] **Step 6: Run UAT-05 Reconciliation and CLOSED-period correction**
- [ ] **Step 7: Run UAT-06 role/permission matrix**
- [ ] **Step 8: Run UAT-07 visual/usability pass**
- [ ] **Step 9: Pair every financial UI PASS with machine/API/DB invariant evidence**
- [ ] **Step 10: Record sanitized screenshots, exact tested SHA, actor, IDs, and result**

A browser observation may never override a failed financial invariant.

---

### Task 20: Real Provider certification

**Files:** Evidence only unless a real Provider incompatibility exposes a defect.

**Produces:** Sanitized real-provider proof for non-stream and streaming paths.

- [ ] **Step 1: Supply Provider secret only via runtime environment; never commit it**
- [ ] **Step 2: Execute one real non-stream request**
- [ ] **Step 3: Execute one real streaming request**
- [ ] **Step 4: Verify usage -> settlement -> Ledger -> Budget Actual convergence**
- [ ] **Step 5: Capture only sanitized metadata and IDs**
- [ ] **Step 6: If M15 exact-correlation profile cannot be certified for the Provider/source schema, record it as an explicit blocker/limitation rather than claiming PASS**

---

### Task 21: Full local regression

**Files:** None unless failures require fixes.

**Produces:** Complete local green gate for exact branch head.

- [ ] **Step 1: Backend**

```powershell
Set-Location "E:\AI-CostOps\backend"
.\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
.\mvnw.cmd -B "-Dgroups=architecture" test
.\mvnw.cmd -B "-Dgroups=integration" verify
```

- [ ] **Step 2: Gateway**

```powershell
Set-Location "E:\AI-CostOps\gateway"
.\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
.\mvnw.cmd -B "-Dgroups=architecture" test
.\mvnw.cmd -B "-Dgroups=integration" verify
```

- [ ] **Step 3: Frontend**

```powershell
Set-Location "E:\AI-CostOps\frontend"
npm ci
npm run lint
npm test -- --run --maxWorkers=1
npm run build
```

- [ ] **Step 4: Repository diff hygiene**

```powershell
Set-Location "E:\AI-CostOps"
git diff --check
git status
```

- [ ] **Step 5: Run designated high-risk race suites repeatedly without sleeps**

---

### Task 22: Hosted gates and final PR

**Files:** PR metadata/evidence only.

**Produces:** One final PR for Issue #151.

- [ ] **Step 1: Push branch without force**
- [ ] **Step 2: Open one PR titled `feat(m16): complete V2 production acceptance`**
- [ ] **Step 3: Verify the exact PR head SHA**
- [ ] **Step 4: Require hosted CI green**
- [ ] **Step 5: Require hosted Security green**
- [ ] **Step 6: Require CodeQL Java/Kotlin and JS/TS green**
- [ ] **Step 7: Require Trivy filesystem and backend/frontend/gateway images green**
- [ ] **Step 8: Require browser E2E and any M16 acceptance workflow green**

Any new commit invalidates the prior final-review SHA and requires re-verification.

---

### Task 23: Final evidence and status documentation

**Files:**
- Modify: `docs/03-acceptance/m16-v2-production-acceptance-evidence.md`
- Modify: `PROJECT_CONTEXT.md`
- Modify: `docs/01-blueprint/product/11-roadmap.md`
- Modify: `deploy/production/README.md`
- Modify other runbooks only where M16 changed the production contract.

**Produces:** Auditable V2 release-candidate evidence.

- [ ] **Step 1: Fill every acceptance-matrix row with PASS/FAIL and evidence reference**
- [ ] **Step 2: Record measured operating envelope and observed bottleneck**
- [ ] **Step 3: Record DB grants/denials**
- [ ] **Step 4: Record recovery/restore outcomes**
- [ ] **Step 5: Record Browser UAT and real Provider outcomes**
- [ ] **Step 6: Record exact hosted-run identifiers and exact PR head SHA**
- [ ] **Step 7: Update roadmap/context to `M15 COMPLETE/ACCEPTED` and only mark M16 COMPLETE if all gates truly passed**
- [ ] **Step 8: Keep unresolved limitations explicit**

---

### Task 24: Sol independent final gate

**Files:** No implementation edits during review unless a finding is raised and fixed in a new commit.

**Produces:** Independent PASS/FAIL on the exact PR head.

- [ ] **Step 1: Sol fetches the exact PR head independently**
- [ ] **Step 2: Review changed-file scope and frozen-spec coverage**
- [ ] **Step 3: Re-check production topology and DB privilege enforcement**
- [ ] **Step 4: Re-check financial invariants and failure evidence**
- [ ] **Step 5: Re-check Browser UAT, leak scan, restore, real Provider certification, and hosted gates**
- [ ] **Step 6: Require P0=0 and P1=0**
- [ ] **Step 7: Publish `SOL FINAL REVIEW = PASS` only if the exact reviewed SHA satisfies all gates**

No merge occurs as part of this task. Squash merge is allowed only after the user explicitly says to merge and the reviewed head SHA has not changed.

---

## Self-review checklist

Before declaring this plan complete, verify:

- Every frozen spec work package maps to one or more tasks above.
- No task silently expands V2 business scope.
- Production fixes use RED -> root cause -> minimal GREEN.
- No task requires V24 by default.
- Load testing measures rather than invents SLOs.
- Browser UAT is black-box and paired with machine financial invariants.
- DB privilege is proven by MySQL denial, not merely application code.
- Failure testing distinguishes pre-dispatch safety from post-dispatch uncertainty.
- Final status documentation happens only after evidence is green.
- Merge remains outside the implementation agent's authority.