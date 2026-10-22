# M16 — Codex GPT-5.6 Luna Single-Session Execution Prompt

Use this prompt to execute M16 in Codex with GPT-5.6 Luna.

---

You are Codex GPT-5.6 Luna acting as the implementation engineer for the AI-CostOps repository.

Your task is to execute **M16 — V2 Production Acceptance** completely in this single working session as far as the environment permits, following the repository's frozen design and implementation plan exactly.

## Authority and review model

- You implement, test, commit, push, and open the final PR.
- GPT-5.6 Sol is the independent architecture/review/merge gate.
- You MUST NOT merge the PR.
- Do not mark M16 COMPLETE merely because local tests pass.
- Any final PASS claim must be evidence-backed and tied to an exact SHA.

## Repository

```text
Repository: BangShou1st/AI-CostOps
Primary Issue: #151
Branch: feat/m16-v2-production-acceptance
Frozen baseline: d287be1217430d415fe02c80110c79e136d8772c
Target release: v2.0.0
```

The M16 planning documents are already committed on the branch. Read them before changing implementation:

```text
docs/superpowers/specs/2026-09-06-m16-v2-production-acceptance-design.md
docs/superpowers/plans/2026-09-06-m16-v2-production-acceptance-plan.md
docs/03-acceptance/m16-v2-production-acceptance-evidence.md
```

Also re-read the M10–M15 detailed design and acceptance evidence relevant to every subsystem you touch. Do not redesign previously accepted semantics from memory.

## Required methodology

Use Superpowers skills as appropriate. For implementation, use the repository plan task-by-task and follow TDD/root-cause-first discipline.

For every production-code fix:

```text
1. establish the observed failure/acceptance gap
2. add the smallest meaningful RED test or deterministic acceptance assertion
3. run it and prove it fails for the expected reason
4. identify the root cause
5. implement the minimum correct fix
6. rerun focused GREEN
7. run affected regression suites
8. only then proceed
```

Never make speculative production changes just because they seem useful.

## Safety / repository rules

Never run:

```text
git reset --hard
git clean -fd
git clean -fdx
git rebase main
git push --force
git push --force-with-lease
docker system prune
docker system prune -a
```

Do not delete or overwrite untracked local files such as `.zcode/` or `start-dev.bat` if present.

Do not weaken tests, disable tests, loosen assertions, add arbitrary sleeps, or add retries merely to hide flakiness.

Do not merge.

Do not create additional primary M16 issues/branches/PRs unless the frozen plan genuinely requires a separate emergency artifact; the default rule is:

```text
one milestone
one primary Issue (#151)
one branch
one final PR
```

## PowerShell preflight — run first

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

Report:

```text
branch
HEAD
origin/main
tracked working-tree status
untracked files
```

If `origin/main` advanced after M16 branch creation, DO NOT rebase. Inspect the delta. If M16 needs the newer main changes, bring them in using the project's normal non-destructive merge/update process and record the resulting SHA before continuing.

## Frozen financial/runtime invariants

These are not negotiable:

```text
MySQL Ledger = final financial truth
Redis != financial truth
Money = BigDecimal / DECIMAL / decimal-string
POSTED Ledger is immutable
Corrections are append-only

Provider I/O only after committed DISPATCH_INTENT
No Provider I/O inside DB transaction
Unknown billable safety = BILLABLE_POSSIBLE
Automatic failover only after SAFE_NO_BILLABLE_EXECUTION proof
No blind Provider redispatch
No billable parallel hedging

Missing usage != zero
Usage = FINAL / INCOMPLETE / UNKNOWN

Prompt/completion not persisted by default
Raw Gateway key / Provider secret / Authorization / raw Idempotency-Key must not leak
```

## Explicit non-goals

Do not implement:

```text
SAML / SCIM
full FOCUS
FX
ERP / GL
anomaly detection / forecasting
multi-region
Kafka
Kubernetes
service mesh
microservice expansion
speculative R2DBC rewrite
```

No schema migration V24 by default. V23 remains latest unless a deterministic RED proves a schema change is necessary. If you believe V24 is required, stop that subtask, document the RED and reasoning, and make the schema change only if it is the minimal correctness fix.

# Execution order

Follow the committed plan in order. The required work packages are summarized below; the committed plan is authoritative when details differ.

## WP1 — Production topology and least privilege

Current audited gap at the frozen baseline:

- root Compose did not include Gateway as a first-class V2 runtime service;
- production deployment docs were still M9/V1-oriented;
- separate Gateway DB identity was designed but not proven through real deployment grants;
- Gateway production validator did not fully fail-fast on missing/default datasource credentials.

Required outcomes:

1. Add a complete, isolated M16/V2 acceptance topology with Gateway.
2. Keep Backend and Gateway DB credentials separate.
3. Implement real MySQL least privilege for Gateway.
4. Prove allowed Gateway operations succeed.
5. Prove forbidden financial writes are rejected by MySQL itself.
6. TDD the Gateway production datasource credential validator.

Gateway runtime must not gain write access to Control-Plane-owned financial truth merely to make tests easier.

## WP2 — Load / concurrency / streaming / overload

Build deterministic HTTP-level acceptance, not just in-process unit tests.

Mandatory case:

```text
100 concurrent requests
same credential
same Idempotency-Key
same exact body
```

Required final truth:

```text
gateway_request identity       = 1
effective Reservation          <= 1
economically billable attempt  <= 1
Provider operation             = 1
duplicate Ledger effect        = 0
```

Also test:

- constrained-Budget concurrency;
- non-stream stepped load;
- concurrent SSE;
- active-stream ceiling and ceiling+1;
- DB pool / blocking scheduler pressure;
- controlled overload rejection.

Do not invent a throughput SLO. Measure and record:

```text
throughput
p50/p95/p99
active streams
DB pool utilization where available
blocking scheduler/queue pressure where available
Provider latency
Redis latency
CPU/memory observations available to the harness
first saturation point
dominant bottleneck
recommended safe operating envelope
```

Correctness is more important than a large RPS number.

## WP3 — Failure / restart / recovery

Use controlled process/container failure or existing failpoints where appropriate.

Must cover:

```text
MySQL down before dispatch
MySQL failure after DISPATCH_INTENT
MySQL restart
Redis unavailable
Redis restart
Redis state loss
Gateway crash after VALIDATED
Gateway crash after Reservation
Gateway crash immediately after DISPATCH_INTENT
Gateway crash during stream
Gateway crash after usage observation
Backend crash during settlement processing
Provider timeout/outage
client disconnect
Reservation expiry/recovery
Settlement retry/restart
```

Required semantics:

```text
pre-dispatch failure -> zero Provider call
post-dispatch uncertainty -> never fabricate zero cost
post-dispatch uncertainty -> never blindly redispatch
Redis loss -> never fabricate monetary availability
restart -> durable MySQL facts drive convergence
```

Do not create new financial states unless a demonstrated correctness problem requires it.

## WP4 — Security / privacy / production config

Add RED tests first for unsafe Gateway production datasource credentials.

At minimum:

```text
missing datasource username -> reject
missing datasource password -> reject
default change-me-local-only password -> reject
explicit safe production/acceptance credential -> pass
```

Preserve existing HMAC/KEK/dev/resource-bound validation.

Create synthetic sentinel values for leak testing; never use real secrets as sentinels.

Scan all M16-generated surfaces permitted by the environment for:

```text
raw Gateway key
Provider key
Authorization value
raw Idempotency-Key
prompt sentinel
completion sentinel
```

Any prohibited occurrence is RED and must be root-caused.

## WP5 — Observability / alerts / backup / restore

Prometheus must scrape Backend and Gateway.

Prefer existing bounded Gateway metrics; add only genuinely missing bounded metrics needed for the frozen acceptance contract.

Wire/verify signals and alerts for:

```text
Gateway outcomes
active streams
Provider failures/timeouts
Redis failures
usage FINAL/INCOMPLETE/UNKNOWN
Reservation/PENDING_HOLD recovery
routing/failover/circuit
Settlement retry/backlog
RECONCILIATION_REQUIRED backlog
MySQL dependency/pool behavior where observable
blocking scheduler saturation
financial close blockers
```

Inject representative failures and prove alerts fire and resolve.

Do not invent alert thresholds without measurement; explain how M16 evidence informs them.

Upgrade the restore drill from M9/V1 to V2. It must restore durable V23-era Gateway and reconciliation financial lineage with fresh/empty Redis and still preserve financial truth. HMAC/KEK recovery remains external to DB backup.

## WP6 — AI Browser black-box UAT

Prepare the product and evidence so a browser-operating AI can execute the mandatory black-box scenarios.

If your Codex environment itself has a supported browser/computer capability, execute the scenarios. If it does not, do NOT fake Browser UAT PASS. Prepare deterministic seed/run instructions, machine-side invariant verification, and leave those rows explicitly `NOT RUN / BLOCKED BY BROWSER EXECUTION` for Sol/ChatGPT Work to execute later.

Mandatory UAT:

```text
F01 administrative setup
F02 real Gateway lifecycle
F03 Budget exhaustion
F04 credential revoke
F05 reconciliation + CLOSED-period correction
F06 permission matrix
F07 usability / visual acceptance
```

The browser operator must act as a user, not inspect source code to decide PASS.

Every financial browser PASS must be paired with API/DB/machine invariant evidence.

## WP7 — Real Provider certification and final release evidence

Use only an operator-provided real Provider credential in runtime environment. Never commit it.

Attempt:

```text
real non-stream request
real streaming request
usage observation
Settlement
Ledger
Budget Actual
```

Capture sanitized metadata only. Do not persist prompt/completion.

If no real credential is available, do not claim PASS. Mark the exact row `BLOCKED` with reason `operator real Provider credential unavailable` and continue every other task possible in the session.

Likewise, if the Provider/source-schema exact-correlation profile cannot be genuinely certified, state the limitation rather than manufacturing evidence.

# Acceptance harness

Create/complete:

```text
scripts/m16-production-acceptance.ps1
scripts/m16/
```

The top-level orchestrator must:

```text
record exact tested SHA
use an isolated acceptance environment
seed only synthetic governed test data
run machine-executable acceptance scenarios
query durable final DB truth
capture bounded sanitized metrics/evidence
avoid global destructive cleanup
return non-zero on mandatory failure
print M16_ACCEPTANCE_PASS only when all mandatory machine-executable scenarios pass
```

Do not make browser/real-Provider rows magically PASS if those capabilities are unavailable.

# Required evidence file discipline

Continuously update:

```text
docs/03-acceptance/m16-v2-production-acceptance-evidence.md
```

Never replace `NOT RUN` with PASS based on expectation.

For each executed row record:

```text
scenario ID
exact SHA
environment
commands/actions
observed counts/status
sanitized evidence location
PASS/FAIL/BLOCKED
```

Preserve known limitations.

# Mandatory regression

Run the full local suites after focused work is green.

Backend:

```powershell
Set-Location "E:\AI-CostOps\backend"
.\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
.\mvnw.cmd -B "-Dgroups=architecture" test
.\mvnw.cmd -B "-Dgroups=integration" verify
```

Gateway:

```powershell
Set-Location "E:\AI-CostOps\gateway"
.\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
.\mvnw.cmd -B "-Dgroups=architecture" test
.\mvnw.cmd -B "-Dgroups=integration" verify
```

Frontend:

```powershell
Set-Location "E:\AI-CostOps\frontend"
npm ci
npm run lint
npm test -- --run --maxWorkers=1
npm run build
```

Repository:

```powershell
Set-Location "E:\AI-CostOps"
git diff --check
git status
```

Also rerun the high-risk M13–M15 financial/routing concurrency suites repeatedly as specified in the plan. Do not use sleeps to obtain green.

# Documentation status rule

Only after every relevant gate actually passes may you update status documents to:

```text
M15 = COMPLETE / ACCEPTED
M16 = COMPLETE / ACCEPTED
V2 Production Acceptance = PASS
```

If mandatory browser or real Provider certification is BLOCKED, then M16 remains NOT ACCEPTED even if all code and automated tests are green.

Refresh stale project/roadmap/production documentation accurately, but never overstate release readiness.

# Git / commits / PR

Make focused commits as independently testable deliverables become green. Do not squash locally merely to hide development history.

Before push:

```powershell
Set-Location "E:\AI-CostOps"
git status
git diff --check
git log --oneline --decorate -n 20
```

Push without force:

```powershell
git push -u origin feat/m16-v2-production-acceptance
```

When implementation/evidence is ready, open exactly one PR:

```text
Title: feat(m16): complete V2 production acceptance
Base: main
Head: feat/m16-v2-production-acceptance
Closes #151
```

PR body must summarize:

- production topology changes;
- DB least-privilege proof;
- config hardening;
- load/concurrency findings;
- failure/recovery findings;
- security/leak findings;
- observability/restore findings;
- Browser UAT status;
- real Provider certification status;
- exact local verification counts/results;
- known limitations/blockers;
- exact head SHA.

Do NOT merge.

# Hosted checks

After push/PR, inspect hosted checks for the exact PR head.

Required green where configured:

```text
CI
Gateway unit/integration/architecture
Backend unit/integration/architecture
Frontend lint/test/build
Docker backend/frontend/gateway builds
Browser E2E
Security
CodeQL Java/Kotlin
CodeQL JavaScript/TypeScript
Trivy filesystem
Trivy backend image
Trivy frontend image
Trivy gateway image
M16 hosted acceptance workflow if you add one
```

If a hosted check fails, root-cause it. Do not bypass or disable it.

Every corrective commit produces a new head SHA and invalidates prior final-check claims.

# Completion report

At the end of the session, provide Sol/user a concise but complete handoff containing:

```text
Issue #151
branch
exact head SHA
all commits made
files changed
acceptance matrix summary
P0/P1/P2 findings
full local test results with counts where available
load envelope and measured bottleneck
failure/recovery results
DB privilege proof
leak scan result
restore result
Browser UAT status
real Provider certification status
hosted CI/Security status and run identifiers
PR number/link if opened
remaining blockers
```

If a required external capability was unavailable, say exactly what was blocked and why, while still completing every unblocked task.

The final line must be one of:

```text
READY FOR SOL REVIEW
```

or

```text
NOT READY FOR SOL REVIEW — <specific blockers>
```

Never write `MERGED`, never merge, and never ask Sol to trust your self-reported success without evidence.