# M9 Production Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `v1.1.0` as a production-foundation release that closes high-value V1 audit gaps, adds production configuration guards, observability, browser E2E, security CI, restore drills, scale evidence, and one real Provider certification without changing the V1 financial truth model.

**Architecture:** Keep the existing Java/Spring MVC modular monolith and React frontend unchanged as the V1 runtime architecture. M9 adds cross-cutting production engineering around that runtime; MySQL remains identity/financial truth, Redis remains recoverable session/cache/short-state infrastructure, MinIO/S3 remains Evidence storage, and no Realtime Gateway code is introduced in M9.

**Tech Stack:** Java 21, Spring Boot 4.1.x, Spring MVC, Spring Security, Plain MyBatis, Flyway, MySQL 8.4, Redis, MinIO/S3-compatible storage, Micrometer, Prometheus, Grafana, React 19, TypeScript 6, Vite 8, Playwright Test 1.62.1, Docker Compose, GitHub Actions, CodeQL Action v4, Trivy 0.73.0, JUnit 5, Testcontainers, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-08-27-v1-to-v2-production-gateway-design.md`

## Global Constraints

- V1 remains `COMPLETE / FROZEN`; M9 must not change V1 business semantics unless a verified defect requires a patch.
- Target release is `v1.1.0`; `v1.0.0` and `v1.0.1` tags remain immutable.
- MySQL remains identity and financial final truth.
- Redis failure must never fabricate Ledger, Budget, Billing Period, or Settlement truth.
- POSTED Ledger rows remain immutable; corrections stay append-only.
- M9 does not add Gateway, WebFlux, Netty, Provider routing, realtime reservation, realtime metering, or settlement.
- Do not introduce RabbitMQ/Kafka/Kubernetes by default. Any MQ decision must follow benchmark evidence.
- Preserve existing public API compatibility unless the task explicitly adds an operational endpoint such as Prometheus under authenticated/isolated management exposure.
- Never commit real Provider raw exports, credentials, tokens, certificates, database dumps, or restored evidence objects.
- All performance claims must come from recorded real executions with environment metadata.
- Windows developer commands are PowerShell-first; CI may continue to use Ubuntu runners.
- Preserve V1 frozen evidence documents; write new M9 evidence instead of rewriting historical M8/AIC-073 results.

---

## File Structure / Delivery Map

M9 is split into ten independently reviewable delivery units and stable IDs:

| Stable ID | Delivery | Primary files |
|---|---|---|
| AIC-074 | Audit closure | `organization/*`, `allocation/*`, audit integration tests |
| AIC-075 | Production configuration hardening | `application-prod.yml`, production validation, deployment docs/tests |
| AIC-076 | Application metrics | `pom.xml`, `observability/*`, selected services/tests |
| AIC-077 | Prometheus/Grafana/alerts | `compose.observability.yaml`, `deploy/observability/*`, smoke script |
| AIC-078 | Browser E2E | `frontend/playwright.config.ts`, `frontend/e2e/*`, `package*.json`, CI |
| AIC-079 | Security CI | `.github/workflows/security.yml`, CodeQL/Trivy config/evidence |
| AIC-080 | Backup/restore drills | `scripts/ops/*`, `docs/02-development/operations/*`, acceptance evidence |
| AIC-081 | Scale/read benchmark | new M9 benchmark integration tests + benchmark evidence |
| AIC-082 | Real Provider certification | certification harness, ignore rules, provider evidence report |
| AIC-083 | v1.1 acceptance/release closure | README/context/roadmap + M9 acceptance evidence |

Do not combine AIC-074–083 into one implementation branch. Each ID should normally have one short-lived branch and one principal PR.

---

### Task 1 / AIC-074: Close high-value audit gaps

**Files:**
- Create: `backend/src/main/java/com/aicostops/organization/application/OrganizationAuditPort.java`
- Create: `backend/src/main/java/com/aicostops/organization/infrastructure/AuditOrganizationAdapter.java`
- Modify: `backend/src/main/java/com/aicostops/organization/application/ProviderAccountService.java`
- Modify: `backend/src/main/java/com/aicostops/allocation/application/AllocationAuditPort.java`
- Modify: `backend/src/main/java/com/aicostops/allocation/infrastructure/AuditAllocationAdapter.java`
- Modify: `backend/src/main/java/com/aicostops/allocation/application/AllocationRuleCommandService.java`
- Test: `backend/src/test/java/com/aicostops/organization/api/ProviderAccountApiIntegrationTest.java`
- Test: `backend/src/test/java/com/aicostops/allocation/AllocationRuleApiIntegrationTest.java`
- Test: `backend/src/test/java/com/aicostops/iam/api/RefreshAndLogoutApiIntegrationTest.java`
- Test: `backend/src/test/java/com/aicostops/reconciliation/PeriodReopenIntegrationTest.java`
- Modify evidence after implementation: `docs/superpowers/specs/2026-08-23-m7-audit-sensitive-action-matrix.md` only by appending an M9 follow-up note; do not rewrite its historical M7 matrix.

**Interfaces:**
- Consumes: existing `AuditService.append(String eventType, Long organizationId, Long actorUserId, String subjectType, Long subjectId, Map<String,Object> metadata)`.
- Produces: `OrganizationAuditPort.providerAccountCreated(...)`, `providerAccountUpdated(...)`, `providerAccountArchived(...)`; extends `AllocationAuditPort` with `ruleVersionPublished(...)` and `ruleArchived(...)`.

- [ ] **Step 1: Add failing Provider Account audit assertions**

Extend `ProviderAccountApiIntegrationTest` so create/update/archive mutations query `audit_event` and assert event type, organization, actor, subject id, and secret-safe metadata. The assertion shape must be equivalent to:

```java
assertThat(auditEvents(providerAccountId))
        .extracting(AuditRow::eventType)
        .containsExactly(
                "PROVIDER_ACCOUNT_CREATED",
                "PROVIDER_ACCOUNT_UPDATED",
                "PROVIDER_ACCOUNT_ARCHIVED");
assertThat(auditMetadata(providerAccountId))
        .doesNotContain("secret")
        .doesNotContain("token")
        .doesNotContain("apikey");
```

Run:

```powershell
Set-Location "E:\AI-CostOps\backend"
.\mvnw.cmd -B "-Dit.test=ProviderAccountApiIntegrationTest" failsafe:integration-test
```

Expected: FAIL because no Provider Account audit producer exists.

- [ ] **Step 2: Introduce a narrow organization audit port**

Create:

```java
package com.aicostops.organization.application;

public interface OrganizationAuditPort {
    void providerAccountCreated(long organizationId, long actorUserId,
            long providerAccountId, String providerCode, String status);

    void providerAccountUpdated(long organizationId, long actorUserId,
            long providerAccountId, String status);

    void providerAccountArchived(long organizationId, long actorUserId,
            long providerAccountId, String previousStatus);
}
```

Implement `AuditOrganizationAdapter` using `AuditService`; metadata may contain identifiers/enums only. Do not include `externalAccountRef`, raw metadata JSON, or any secret-neighbor value.

- [ ] **Step 3: Emit Provider Account events in the existing transaction**

Inject `OrganizationAuditPort` into `ProviderAccountService`. Emit create after the created row is read. On update, compare old/new status; emit `PROVIDER_ACCOUNT_ARCHIVED` for transition to `ARCHIVED`, otherwise `PROVIDER_ACCOUNT_UPDATED`. Audit write must participate in the same transaction so rollback removes both mutation and audit.

Run the targeted integration test again; Expected: PASS.

- [ ] **Step 4: Add failing Allocation Rule audit assertions**

Extend `AllocationRuleApiIntegrationTest` to assert:

```text
ALLOCATION_RULE_VERSION_PUBLISHED
ALLOCATION_RULE_ARCHIVED
```

Metadata must include only stable identifiers/version/status and must not include free-form rule payload beyond values already classified as non-secret.

Run:

```powershell
.\mvnw.cmd -B "-Dit.test=AllocationRuleApiIntegrationTest" failsafe:integration-test
```

Expected: FAIL before production code change.

- [ ] **Step 5: Extend AllocationAuditPort and adapter**

Add exact signatures:

```java
void ruleVersionPublished(long organizationId, long actorUserId,
        long allocationRuleId, String ruleKey, int version);

void ruleArchived(long organizationId, long actorUserId,
        long allocationRuleId, String ruleKey, int version);
```

`AuditAllocationAdapter` must write the two event types above via `AuditService`.

- [ ] **Step 6: Wire rule events in AllocationRuleCommandService**

Emit the event only after the persistence transition succeeds. Keep event generation inside the same Spring transaction.

Run the allocation rule test again; Expected: PASS.

- [ ] **Step 7: Strengthen existing platform/reconciliation audit assertions without changing semantics**

Add direct positive DB assertions for the existing events already emitted by logout/session revoke/password-change/reconciliation/close flows. Do not invent new event types in this step unless a real missing producer is demonstrated by a failing test.

Run:

```powershell
.\mvnw.cmd -B "-Dit.test=RefreshAndLogoutApiIntegrationTest,PeriodReopenIntegrationTest" failsafe:integration-test
```

Expected: PASS after assertions are aligned with actual producers.

- [ ] **Step 8: Run the complete backend verification**

```powershell
.\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
.\mvnw.cmd -B "-Dgroups=architecture" test
.\mvnw.cmd -B "-Dgroups=integration" verify
```

Expected: all three commands exit 0.

- [ ] **Step 9: Commit only AIC-074 files**

```powershell
git add -- backend/src/main/java/com/aicostops/organization/application/OrganizationAuditPort.java backend/src/main/java/com/aicostops/organization/infrastructure/AuditOrganizationAdapter.java backend/src/main/java/com/aicostops/organization/application/ProviderAccountService.java backend/src/main/java/com/aicostops/allocation/application/AllocationAuditPort.java backend/src/main/java/com/aicostops/allocation/infrastructure/AuditAllocationAdapter.java backend/src/main/java/com/aicostops/allocation/application/AllocationRuleCommandService.java backend/src/test/java/com/aicostops/organization/api/ProviderAccountApiIntegrationTest.java backend/src/test/java/com/aicostops/allocation/AllocationRuleApiIntegrationTest.java backend/src/test/java/com/aicostops/iam/api/RefreshAndLogoutApiIntegrationTest.java backend/src/test/java/com/aicostops/reconciliation/PeriodReopenIntegrationTest.java docs/superpowers/specs/2026-08-23-m7-audit-sensitive-action-matrix.md
git commit -m "fix(audit): close provider and allocation rule gaps"
```

---

### Task 2 / AIC-075: Harden production configuration and runtime boundary

**Files:**
- Create: `backend/src/main/resources/application-prod.yml`
- Create: `backend/src/main/java/com/aicostops/config/ProductionConfigurationValidator.java`
- Create: `backend/src/test/java/com/aicostops/config/ProductionConfigurationValidatorTest.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `.env.example`
- Create: `deploy/production/README.md`
- Create: `docs/02-development/operations/01-production-configuration.md`

**Interfaces:**
- Consumes: existing `aicostops.auth`, `aicostops.storage`, Redis, datasource configuration properties.
- Produces: a startup validator active only under `prod` that rejects unsafe/missing production configuration before the application becomes ready.

- [ ] **Step 1: Write failing production-guard tests**

Create tests using `ApplicationContextRunner` or a minimal Spring context that cover at least:

```text
prod + blank JWT signing key -> startup rejected
prod + dev bootstrap enabled -> startup rejected
prod + public registration enabled without explicit allow policy -> startup rejected
prod + insecure refresh cookie -> startup rejected
prod + file-backed mailbox path -> startup rejected
prod + localhost-only object-storage defaults -> startup rejected
prod + explicit safe values -> validator passes
```

Representative assertion:

```java
assertThatThrownBy(validator::validate)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("AICOSTOPS_JWT_SIGNING_KEY");
```

Run:

```powershell
.\mvnw.cmd -B "-Dtest=ProductionConfigurationValidatorTest" test
```

Expected: FAIL because validator does not exist.

- [ ] **Step 2: Add `application-prod.yml` with deny-by-default production settings**

The file must set only safe policy defaults, never credentials:

```yaml
aicostops:
  auth:
    allow-public-registration: false
    refresh-cookie-secure: true
    dev-bootstrap-enabled: false
  storage:
    auto-create-bucket: false
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

Do not add example production secrets to the file.

- [ ] **Step 3: Implement `ProductionConfigurationValidator`**

Use `@Profile("prod")` and validate resolved configuration during startup. Error messages name the missing environment variable but never print its value.

The validator must reject dev-only mailbox/bootstrap behaviors and weak defaults, while allowing a reverse proxy/TLS terminator outside the backend process.

- [ ] **Step 4: Document the TLS boundary rather than committing certificates**

`deploy/production/README.md` must define:

```text
Internet/client
→ HTTPS TLS terminator / ingress
→ frontend/control-plane private HTTP hop
```

Requirements:

```text
HTTPS required externally
HSTS at ingress
trusted forwarded headers only
secure refresh cookie
explicit allowed origins
backend not directly internet-exposed
no certificate/private key committed
```

- [ ] **Step 5: Verify production fail-fast and normal local startup remain separate**

Run production guard tests, then the existing unit suite. Expected: PASS.

- [ ] **Step 6: Commit AIC-075**

```powershell
git add -- backend/src/main/resources/application-prod.yml backend/src/main/java/com/aicostops/config/ProductionConfigurationValidator.java backend/src/test/java/com/aicostops/config/ProductionConfigurationValidatorTest.java backend/src/main/resources/application.yml .env.example deploy/production/README.md docs/02-development/operations/01-production-configuration.md
git commit -m "feat(ops): harden production configuration"
```

---

### Task 3 / AIC-076: Add production-grade application metrics

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/aicostops/observability/AiCostOpsMetrics.java`
- Create: `backend/src/test/java/com/aicostops/observability/AiCostOpsMetricsTest.java`
- Modify selected call sites only where a business metric is required: import workflow, login/rate-limit, ledger posting/correction, budget activation conflict, reconciliation, close/reopen.
- Modify: `backend/src/main/resources/application.yml`

**Interfaces:**
- Consumes: Micrometer `MeterRegistry`.
- Produces: stable metric names prefixed `aicostops.`; labels are bounded enums/codes only and never user ids, org ids, provider raw account ids, request ids, email addresses, prompts, or free-form error text.

- [ ] **Step 1: Add the Prometheus registry dependency**

Add to `backend/pom.xml`:

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

Spring Boot manages the compatible Micrometer version.

- [ ] **Step 2: Write metric cardinality tests first**

Test stable names and allowed tags. Representative test:

```java
var registry = new SimpleMeterRegistry();
var metrics = new AiCostOpsMetrics(registry);
metrics.importCompleted("DEEPSEEK", "SUCCEEDED");

assertThat(registry.get("aicostops.import.completed")
        .tags("provider", "DEEPSEEK", "result", "SUCCEEDED")
        .counter().count()).isEqualTo(1.0);
```

Also assert API methods do not accept arbitrary metadata maps or identifiers.

Run targeted test; Expected: FAIL before class exists.

- [ ] **Step 3: Implement the narrow metrics facade**

Expose methods for the M9 metric set:

```java
void loginResult(String result);
void importCompleted(String provider, String result);
void ledgerPosting(String sourceType, String result);
void correction(String mode, String result);
void budgetActivation(String result);
void reconciliationRun(String result);
void periodClose(String result);
void periodReopen(String result);
void dependencyError(String dependency);
```

Use counters/timers as appropriate; do not accept unbounded labels.

- [ ] **Step 4: Wire metrics at existing success/failure decision points**

Only instrument points where the final outcome is known. Do not count a request twice because controller and service both observe it.

Prefer Spring Boot built-in HTTP/JVM/Hikari metrics for infrastructure; do not reimplement those counters.

- [ ] **Step 5: Expose Prometheus only through management configuration**

Local/default exposure may remain `health,info`; `prod`/observability profile exposes `prometheus` according to AIC-075. Do not expose environment/configprops endpoints anonymously.

- [ ] **Step 6: Verify metrics and backend suites**

Run targeted metrics tests plus backend unit/integration/architecture suites. Expected: PASS.

- [ ] **Step 7: Commit AIC-076**

```powershell
git add -- backend/pom.xml backend/src/main/java/com/aicostops/observability backend/src/test/java/com/aicostops/observability backend/src/main/resources/application.yml backend/src/main/java/com/aicostops
git commit -m "feat(observability): add bounded business metrics"
```

Before committing, use `git status --short` and replace the broad last path with the exact modified call-site files; never stage unrelated files.

---

### Task 4 / AIC-077: Add Prometheus, Grafana, and verified alerting

**Files:**
- Create: `compose.observability.yaml`
- Create: `deploy/observability/prometheus/prometheus.yml`
- Create: `deploy/observability/prometheus/alerts.yml`
- Create: `deploy/observability/grafana/provisioning/datasources/prometheus.yml`
- Create: `deploy/observability/grafana/provisioning/dashboards/dashboards.yml`
- Create: `deploy/observability/grafana/dashboards/aicostops-overview.json`
- Create: `scripts/observability-smoke.ps1`
- Create: `docs/02-development/operations/02-observability.md`
- Create evidence: `docs/03-acceptance/m9-observability-evidence.md`

**Interfaces:**
- Consumes: `/actuator/prometheus` from AIC-076.
- Produces: optional Docker Compose observability profile/override; core `compose.yaml` remains usable without Prometheus/Grafana.

- [ ] **Step 1: Add an observability Compose override**

Use Prometheus and Grafana as optional services attached to the existing `aicostops` network. Do not make core backend/frontend startup depend on Grafana.

- [ ] **Step 2: Configure Prometheus scrape**

`prometheus.yml` must scrape backend management metrics using the internal network and a bounded interval such as 15 seconds.

- [ ] **Step 3: Add a minimal operational dashboard**

Dashboard must cover:

```text
HTTP request rate / 5xx
JVM memory / GC
DB pool
Redis dependency error
Import success/failure
Ledger posting/correction
Budget activation conflicts
Reconciliation
Period close/reopen
```

No high-cardinality identifier variables.

- [ ] **Step 4: Add concrete alerts**

At minimum include rules equivalent to:

```yaml
- alert: AiCostOpsHigh5xxRate
  expr: |
    sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
    / clamp_min(sum(rate(http_server_requests_seconds_count[5m])), 1)
    > 0.05
  for: 5m

- alert: AiCostOpsImportFailureSpike
  expr: sum(increase(aicostops_import_completed_total{result!="SUCCEEDED"}[10m])) >= 3
  for: 1m
```

Exact exported Micrometer names must be verified after AIC-076; adjust only to the real names, not by inventing a second metric.

- [ ] **Step 5: Write observability smoke script**

`scripts/observability-smoke.ps1` must:

```text
start core + observability override
wait bounded time for backend/prometheus/grafana readiness
query Prometheus target health
query one aicostops metric
trigger a deterministic synthetic failure supported by dev/test mode
verify the chosen alert becomes pending/firing
verify recovery after the condition is removed
stop only the compose project it started
```

Never run global Docker prune.

- [ ] **Step 6: Run smoke and save real evidence**

Record command, commit SHA, Docker versions, target status, alert transition, and recovery in `m9-observability-evidence.md`. Do not claim an SLO.

- [ ] **Step 7: Commit AIC-077**

Stage only the files listed above and commit:

```powershell
git commit -m "feat(ops): add prometheus grafana and alert smoke"
```

---

### Task 5 / AIC-078: Automate critical browser E2E with Playwright

**Files:**
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Create: `frontend/playwright.config.ts`
- Create: `frontend/e2e/support/auth.ts`
- Create: `frontend/e2e/support/api.ts`
- Create: `frontend/e2e/auth-session.spec.ts`
- Create: `frontend/e2e/import-allocation.spec.ts`
- Create: `frontend/e2e/budget-expense-ledger.spec.ts`
- Create: `frontend/e2e/reconciliation-close.spec.ts`
- Create: `frontend/e2e/authorization-negative.spec.ts`
- Modify: `.github/workflows/ci.yml`
- Create: `docs/03-acceptance/m9-browser-e2e-evidence.md`

**Interfaces:**
- Consumes: existing full Compose dev bootstrap and stable `/api/v1` UI flows.
- Produces: `npm run test:e2e`, HTML/JUnit artifacts, and a required/visible CI job named `browser-e2e` once stable.

- [ ] **Step 1: Install Playwright exactly**

Current reviewed stable package for this plan is `@playwright/test@1.62.1`.

Run:

```powershell
Set-Location "E:\AI-CostOps\frontend"
npm install --save-dev --save-exact @playwright/test@1.62.1
npx playwright install chromium
```

Add scripts:

```json
{
  "test:e2e": "playwright test",
  "test:e2e:headed": "playwright test --headed"
}
```

- [ ] **Step 2: Configure deterministic Chromium E2E**

`playwright.config.ts` must use:

```text
baseURL from AICOSTOPS_E2E_BASE_URL, default http://localhost:8080
Chromium as required CI browser
trace on first retry
screenshot/video on failure only
bounded per-test timeout
no arbitrary sleep
```

- [ ] **Step 3: Write auth/session E2E first**

Cover login, refresh/bootstrap, logout, and expired/unauthorized redirect. Use synthetic dev-bootstrap credentials from CI environment, never hard-code a real password.

Run `npm run test:e2e -- auth-session.spec.ts`; Expected: PASS against full local Compose.

- [ ] **Step 4: Add one critical finance path per spec file**

Minimum coverage:

```text
Import -> review/confirm -> allocation
Budget -> commitment state
Expense -> approval -> posting -> ledger detail/correction
Reconciliation -> close -> CLOSED write rejection -> reopen
Wrong-role/wrong-scope negative path
```

Use API setup helpers only for prerequisite data that the test is not trying to validate; user-visible behavior under test must still be driven through the browser.

- [ ] **Step 5: Add `browser-e2e` CI job**

The job must:

```text
checkout
build/start full Compose with synthetic CI-only environment
wait for health
install frontend dependencies + Chromium
run Playwright
upload playwright report on failure
always docker compose down for that project
```

Do not use `down -v` on shared developer environments; CI ephemeral runners may remove their own project volumes during cleanup.

- [ ] **Step 6: Run frontend regression**

```powershell
npm run lint
npm test -- --run
npm run build
npm run test:e2e
```

Expected: all exit 0.

- [ ] **Step 7: Record evidence and commit AIC-078**

Record browser version, scenario count, pass/fail, and CI run link in `m9-browser-e2e-evidence.md`.

---

### Task 6 / AIC-079: Add continuous security and supply-chain CI

**Files:**
- Create: `.github/workflows/security.yml`
- Create: `.github/codeql/codeql-config.yml`
- Create: `.trivyignore` only for reviewed, time-bounded false positives; do not create it preemptively.
- Create: `docs/03-acceptance/m9-security-ci-evidence.md`
- Modify: `CONTRIBUTING.md`

**Interfaces:**
- Consumes: current Java/TypeScript/Docker build.
- Produces: named CI checks for CodeQL and Trivy filesystem/image scans. Security tooling supplements tests; it does not replace the V1 security integration suite.

- [ ] **Step 1: Add CodeQL v4 workflow**

Use supported `github/codeql-action/*@v4` with languages:

```yaml
strategy:
  matrix:
    language: [java-kotlin, javascript-typescript]
```

Use the repository's normal Java/Node build steps so extraction sees real code.

- [ ] **Step 2: Add Trivy 0.73.0 scans**

Run filesystem scanning for vulnerabilities/misconfiguration/secrets and image scanning after building backend/frontend images.

The policy must fail on `HIGH,CRITICAL` vulnerabilities unless a reviewed exception with rationale and expiry exists. Secret findings are always blocking unless proven synthetic/test-only and explicitly scoped.

Equivalent CLI policy for local reproduction:

```powershell
docker run --rm -v "${PWD}:/workspace" aquasec/trivy:0.73.0 fs --scanners vuln,misconfig,secret --severity HIGH,CRITICAL --exit-code 1 /workspace
```

- [ ] **Step 3: Keep action permissions least-privilege**

Default workflow permissions remain read-only. Grant `security-events: write` only to the CodeQL upload job that needs it.

- [ ] **Step 4: Execute scans and classify every initial finding**

For each blocker: upgrade/fix, prove false positive, or document an explicit accepted risk. Never add a blanket ignore pattern.

- [ ] **Step 5: Update contribution rules**

Document the new check names and local reproduction commands.

- [ ] **Step 6: Save evidence and commit AIC-079**

Evidence includes tool versions, commit SHA, check names, final result, and any accepted risk entry.

---

### Task 7 / AIC-080: Implement non-destructive backup/restore drills

**Files:**
- Create: `scripts/ops/backup-mysql.ps1`
- Create: `scripts/ops/restore-mysql.ps1`
- Create: `scripts/ops/backup-evidence.ps1`
- Create: `scripts/ops/restore-evidence.ps1`
- Create: `scripts/ops/restore-drill.ps1`
- Create: `docs/02-development/operations/03-backup-restore.md`
- Create: `docs/03-acceptance/m9-backup-restore-evidence.md`
- Modify: `.gitignore` to ignore `.local-backups/` and `.local-restore-drill/`.

**Interfaces:**
- Consumes: `compose.yaml`, MySQL 8.4 container, MinIO S3-compatible API.
- Produces: local operator scripts with explicit paths and confirmation boundaries; the drill uses a dedicated Compose project name and never destroys the user's normal project volumes.

- [ ] **Step 1: Write PowerShell parameter validation tests as script self-checks**

Every destructive/restore script must require explicit source/target parameters and reject paths outside its designated local backup root unless `-ForceExplicitPath` is supplied.

- [ ] **Step 2: Implement MySQL logical backup**

Use `mysqldump` from the running MySQL container, stream output to a timestamped file under `.local-backups/mysql/`, and write a SHA-256 sidecar.

The script must never print the database password.

- [ ] **Step 3: Implement MySQL restore into an isolated drill environment**

Restore into a fresh MySQL volume under Compose project name `aicostops-restore-drill-<timestamp>`, not the main `ai-costops` volumes.

- [ ] **Step 4: Implement Evidence mirror backup/restore**

Use a disposable official MinIO Client container attached to the project network. Mirror only the configured Evidence bucket to `.local-backups/evidence/`; restore into the isolated drill MinIO bucket.

- [ ] **Step 5: Implement end-to-end restore verification**

`restore-drill.ps1` must:

```text
create synthetic source data using existing smoke/setup path
backup MySQL + Evidence
start isolated clean restore project
restore both data sources
start backend/frontend against restored services
verify login/financial counts/evidence download/ledger/period state
print M9_RESTORE_DRILL_PASS only after all assertions pass
clean up only isolated drill resources
```

- [ ] **Step 6: Define initial RPO/RTO as engineering objectives, not production promises**

The runbook records measured backup/restore times and proposes an initial operational target with an explicit `engineering objective` label until a real deployment exists.

- [ ] **Step 7: Run the drill and capture evidence**

Evidence includes source commit, data counts before/after, SHA checks, elapsed backup/restore time, Docker versions, and final `M9_RESTORE_DRILL_PASS`.

- [ ] **Step 8: Commit AIC-080**

Never commit `.local-backups` output.

---

### Task 8 / AIC-081: Establish 10k/100k/500k import and read-model scale evidence

**Files:**
- Create: `backend/src/test/java/com/aicostops/M9ImportScaleBenchmarkIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/M9ReportingScaleBenchmarkIntegrationTest.java`
- Create: `docs/03-acceptance/m9-import-scale-benchmark.md`
- Create: `docs/03-acceptance/m9-reporting-scale-benchmark.md`
- Modify production SQL/index/batch files only after the first measured baseline proves a bottleneck and the change has before/after evidence.

**Interfaces:**
- Consumes: existing real MySQL/Redis/MinIO Testcontainers and Provider adapter pipeline.
- Produces: repeatable benchmark harnesses; benchmark tests are opt-in and must not make normal PR integration suites excessively slow.

- [ ] **Step 1: Preserve M8 benchmark history**

Do not change `M8ImportScaleBenchmarkIntegrationTest` or its frozen report to make the old numbers look larger. Build a new M9 harness.

- [ ] **Step 2: Implement generated workloads**

Support exact M9 named scales:

```text
10k input rows
100k input rows
500k input rows
```

Generate fixtures before the timed section. Use existing sanitized DeepSeek schema unless another adapter provides a more representative stable benchmark contract.

- [ ] **Step 3: Record phase metrics**

At minimum capture:

```text
input bytes
upload ms
worker ms
confirm ms
total ms
records/sec
JVM max/used heap sample
GC count/time delta
MySQL statement/query count if instrumentation is available
batch size
worker concurrency
```

Correctness assertions remain mandatory: row counts, canonical counts, aggregate money, state, no duplicate publication, no secret persistence.

- [ ] **Step 4: Make large scales opt-in**

Default CI may run a small correctness sample. Full benchmark is invoked explicitly, for example:

```powershell
.\mvnw.cmd -B "-Dm9.benchmark.scales=10k,100k,500k" "-Dm9.benchmark.runs=3" failsafe:integration-test "-Dit.test=M9ImportScaleBenchmarkIntegrationTest"
```

- [ ] **Step 5: Benchmark reporting/read models**

Populate a large realistic set of facts/ledger entries and time the actual Workbench/monthly aggregation queries. Capture MySQL `EXPLAIN ANALYZE` for the dominant queries.

- [ ] **Step 6: Apply only evidence-backed optimization**

For each change:

```text
baseline correctness PASS
baseline performance recorded
single minimal SQL/index/batch change
same correctness assertions PASS
after performance recorded
regression suite PASS
```

If 500k cannot complete within available developer resources, record the resource ceiling and failure mode; do not label it PASS.

- [ ] **Step 7: Decide the M9 worker architecture**

The report must conclude one of:

```text
KEEP DB-BACKED WORKER
TUNE DB-BACKED WORKER
EVALUATE TRANSACTIONAL OUTBOX
EVALUATE MESSAGE BROKER
```

The conclusion must cite measured evidence. RabbitMQ/Kafka cannot appear simply because M9 is a production milestone.

- [ ] **Step 8: Commit AIC-081**

Commit harness, evidence, and any independently justified optimization together only when the before/after report explains the production change.

---

### Task 9 / AIC-082: Certify one real Provider import without committing real data

**Files:**
- Modify: `.gitignore` to ignore `.local-provider-certification/`.
- Create: `scripts/provider-certification.ps1`
- Create: `docs/02-development/operations/04-provider-certification.md`
- Create: `docs/03-acceptance/m9-provider-certification-<provider>.md` during the real certification run; replace `<provider>` in the filename with the actual lowercase provider code selected for the run, e.g. `deepseek`.
- Modify the selected Provider adapter/tests only if real observed schema reveals a genuine gap.

**Interfaces:**
- Consumes: one user-provided real Provider statement/export stored only under `.local-provider-certification/input/` or an explicitly approved external local path.
- Produces: a redacted schema/mapping/correctness evidence report; raw source never enters Git.

- [ ] **Step 1: Implement the certification harness before receiving/using real input**

The script accepts:

```powershell
-Provider <supported provider code>
-InputPath <local non-git path>
-BaseUrl <local test system>
```

It must verify the input path is ignored/untracked before upload and abort if `git ls-files --error-unmatch` says the file is tracked.

- [ ] **Step 2: Run schema inspection and import using the real file locally**

Record only:

```text
file type
file size
SHA prefix sufficient for local evidence correlation, not full sensitive identifiers
observed sheet/file names after classification
schema fingerprint
parser version
row counts
warnings/errors
canonical counts
currency/amount aggregates
```

Do not copy raw rows, names, API keys, account ids, prompts, or personal data into the report.

- [ ] **Step 3: Reconcile monetary totals**

Compare the Provider-visible statement total with canonical/charge totals according to the adapter's documented semantics. Explain known excluded/non-charge fields.

- [ ] **Step 4: If real schema reveals a mismatch, use TDD before changing adapter code**

Create a minimal sanitized fixture that reproduces only the schema behavior, not real values. First show the test fail, implement minimal adapter change, then show it pass and rerun the real local certification.

- [ ] **Step 5: Human redaction review**

Before committing the evidence report, manually inspect it for account identifiers, keys, emails, invoice identifiers, raw lines, prompt/response content, and other provider-specific sensitive values.

- [ ] **Step 6: Commit AIC-082**

Commit only the script, sanitized fixture/test changes if any, runbook, and redacted report. Never commit the original real export.

---

### Task 10 / AIC-083: M9 final acceptance and `v1.1.0` release closure

**Files:**
- Create: `docs/03-acceptance/aic-083-m9-final-acceptance.md`
- Modify: `docs/03-acceptance/README.md`
- Modify: `README.md`
- Modify: `PROJECT_CONTEXT.md`
- Modify: `docs/01-blueprint/product/11-roadmap.md`
- Modify version metadata only after all acceptance checks pass: `backend/pom.xml`, `frontend/package.json`, `frontend/package-lock.json`.

**Interfaces:**
- Consumes: AIC-074–AIC-082 merged evidence.
- Produces: a single human-readable M9 release decision. This task does not hide failed prerequisites; it records `BLOCKED` if any required evidence is missing.

- [ ] **Step 1: Build the acceptance matrix from actual artifacts**

The matrix must include:

```text
Audit closure
Production config guard
Prometheus metrics
Grafana dashboard
Alert injection/recovery
Browser E2E
Security CI
MySQL restore drill
Evidence restore drill
Provider certification
10k benchmark
100k benchmark
500k benchmark or explicit resource-limited non-pass
Reporting benchmark
Backend full tests
Frontend lint/test/build
Compose smoke
P0/P1 defects
```

- [ ] **Step 2: Run final automated verification from clean main-derived branch**

Backend:

```powershell
Set-Location "E:\AI-CostOps\backend"
.\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
.\mvnw.cmd -B "-Dgroups=architecture" test
.\mvnw.cmd -B "-Dgroups=integration" verify
```

Frontend:

```powershell
Set-Location "E:\AI-CostOps\frontend"
npm ci
npm run lint
npm test -- --run
npm run build
npm run test:e2e
```

Compose/ops:

```powershell
Set-Location "E:\AI-CostOps"
.\scripts\smoke-v1.ps1 -EnvFile .env.example -BaseUrl http://localhost:8080/api/v1
.\scripts\observability-smoke.ps1
.\scripts\ops\restore-drill.ps1
```

Expected: every command required by the acceptance matrix exits 0. Benchmark commands are evaluated by their reports, not by pretending performance thresholds exist where none were approved.

- [ ] **Step 3: Verify GitHub checks**

Confirm the merged candidate SHA has green core CI, browser-e2e, CodeQL, Trivy, and docker-build checks. Save run links/IDs in the final acceptance document.

- [ ] **Step 4: Decide release status honestly**

Use exactly one final state:

```text
M9 = ACCEPTED / RELEASE GO
M9 = ACCEPTED WITH DOCUMENTED NON-BLOCKING LIMITATIONS
M9 = BLOCKED
```

A missing real Provider certification, failed restore drill, failing security check, or unresolved P0/P1 is blocking.

- [ ] **Step 5: Align active docs only after acceptance**

If accepted, update current-state docs to:

```text
Current stable = v1.1.0 (after release is published)
M9 = COMPLETE
M10 = NEXT DESIGN MILESTONE
```

Do not rewrite V1 frozen evidence.

- [ ] **Step 6: Normalize project version metadata**

After acceptance, set backend/frontend release metadata to `1.1.0` in the release PR so runtime/package metadata no longer advertises `0.0.1-SNAPSHOT`/`0.0.1` for the shipped release.

- [ ] **Step 7: Commit release closure**

Use a release/docs commit such as:

```powershell
git commit -m "docs(v1.1): record m9 production acceptance"
```

Tag/release publishing happens only after this PR is merged and main CI is green.

---

## M9 Dependency Graph

```text
AIC-074 Audit Closure --------------------------┐
AIC-075 Production Config ------------------┐   │
AIC-076 Metrics ---------------------┐       │   │
                                    └→ AIC-077 Observability
AIC-078 Browser E2E ---------------------------│
AIC-079 Security CI ---------------------------│
AIC-080 Backup / Restore ----------------------│
AIC-081 Scale Evidence ------------------------│
AIC-082 Provider Certification ----------------│
                                               ▼
                                      AIC-083 Final Acceptance
```

AIC-074, AIC-075, AIC-076, AIC-078, AIC-079, AIC-080, AIC-081, and the certification harness portion of AIC-082 can proceed independently. AIC-077 depends on AIC-076 metrics. AIC-083 depends on all required evidence.

## Recommended Implementation Order

For two collaborators / agentic execution:

```text
Wave 1:
AIC-074 Audit
AIC-075 Production Config
AIC-076 Metrics

Wave 2:
AIC-077 Observability
AIC-078 Browser E2E
AIC-079 Security CI

Wave 3:
AIC-080 Backup/Restore
AIC-081 Scale Benchmark
AIC-082 Provider Certification

Wave 4:
AIC-083 Final Acceptance / v1.1.0
```

Do not start M10 Gateway detailed design as implementation code while AIC-083 is still blocked. M10 design work may be researched in parallel, but no `/gateway` runtime should be committed under an M9 issue.

## Plan Self-Review Result

- Spec coverage: M9 audit, production config, observability, browser E2E, security CI, backup/restore, Provider certification, performance evidence, documentation, and final release acceptance each have an explicit task.
- Scope boundary: no Gateway/WebFlux/Netty/reservation/metering/routing implementation is included.
- Evidence policy: all performance, restore, security, browser, and Provider claims require fresh recorded execution.
- Technology guard: no RabbitMQ/Kafka/Kubernetes is preselected.
- Historical integrity: M8/AIC-073 frozen evidence is preserved.
- Human dependency: AIC-082 requires a real Provider export locally; the plan defines the non-committed handling path and treats missing certification as an AIC-083 blocker rather than fabricating evidence.
