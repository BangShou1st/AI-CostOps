# M11 Gateway Edge MVP — Acceptance Evidence

> Final acceptance recorded on `feat/m11-gateway-edge-mvp`.
> No `PASS` is claimed for any check that was not actually run in this final pass.

## 1. Final branch HEAD

```text
code HEAD (schema/code/CI/docs of the milestone): 3ad6603
final branch HEAD at evidence update: 51df21c
base: main (M10 design merged via PR #129/#130)
```

## 2. Commit list (implementation, chronological)

```text
12d96a4 feat(gateway): bootstrap M11 WebFlux data plane
ed0d34e feat(db): add M11 gateway edge foundation schema
583cc58 feat(gateway-admin): provision M11 credentials safely
b7cf23b feat(gateway): enforce durable dispatch and close safety
52e14c1 feat(gateway): proxy MiMo chat completions
5383134 feat(gateway): add bounded MiMo SSE streaming
fe32bd4 feat(gateway): bound M11 rate and stream concurrency
f863066 feat(gateway): expose safe M11 status and telemetry
cfa40a5 ci(gateway): gate M11 runtime and security
3ad6603 fix(backend): accept gateway close blocker code in period close checks
<evidence docs commit>
```

## 3. Changed files / scope

```text
124 files changed, 21840 insertions(+), 107 deletions(-) vs main
gateway/   WebFlux data plane (bootstrap, auth, dispatch fence, MiMo adapter,
           SSE streaming, Redis token bucket, status API, observability,
           Dockerfile, tests)
backend/   V18 M11 schema wave (+ close-blocker CHECK extension), dev
           provisioning, gateway_admin crypto/bootstrap, close safety,
           E2E close assertions
.github/   ci.yml gateway jobs + docker image, security.yml CodeQL/Trivy
           gateway coverage, codeql-config.yml gateway/src
docs/      runbook + this evidence
scripts/   smoke-m11-gateway.ps1
README.md  Gateway runbook pointer
```

## 4. Backend unit

```text
command: .\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
result:  469 PASS, 0 failures, 1 skipped
```

## 5. Backend architecture

```text
command: .\mvnw.cmd -B "-Dgroups=architecture" test
result:  34 PASS
```

## 6. Backend integration

```text
command: .\mvnw.cmd -B "-Dgroups=integration" verify
result:  829 PASS, 0 failures
```

## 7. Gateway unit

```text
command: .\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
result:  69 PASS (includes the 5 ArchUnit architecture tests, which carry no
         JUnit tag and therefore execute inside the unit run here)
```

## 8. Gateway architecture

```text
The Gateway architecture tests are ArchUnit-only (@ArchTest, no JUnit tag),
so "-Dgroups=architecture" yields 0 and they actually execute in the unit
command above: 5 ArchUnit rules PASS (no backend-class/Flyway/global-scheduler
dependencies, plain MyBatis mappers).
```

## 9. Gateway integration

```text
command: .\mvnw.cmd -B "-Dgroups=integration" verify
result:  34 PASS = 13 surefire tagged (ChatCompletionController 7,
         GatewayRequestStatusController 4, GatewayRedaction 2)
         + 21 failsafe (MimoStreaming 6, RedisTokenBucketRateLimiter 5,
         StreamingLifecycle 2, DispatchFence 3, GatewayRequestIdempotency 3,
         BlockingIoScheduler 2)
```

## 10. Frontend lint/test/build

```text
npm run lint            PASS
npm test -- --run       432 PASS (47 files)
npm run build           PASS
```

## 11. Gateway Docker build

```text
docker build --tag ai-costops-gateway:m11-local-check gateway   PASS
image size ~518MB; container run verified: Started GatewayApplication,
liveness {"status":"UP"} on 18081 (readiness also UP)
disposable image removed after evidence recorded
```

## 12. Local application Docker build count

```text
local Docker application image builds performed: 1 (ai-costops-gateway, once)
Gateway final Docker build: PASS
Backend/Frontend image builds locally: 0
repeated compose --build loop used: NO
global Docker prune used: NO
destructive volume cleanup used: NO
```

## 13. Mock provider smoke

```text
.\scripts\smoke-m11-gateway.ps1 -BaseUrl http://localhost:8081 -EnvFile .env.m11
(Gateway native local profile + local mock MiMo upstream on 127.0.0.1:19999)
liveness/readiness        PASS
nonStreamingChat          PASS (chat.completion, usage present)
idempotencyReplay         PASS (409 GATEWAY_RESPONSE_NOT_RETAINED, no re-dispatch)
sseStream                 PASS (SSE reached data: [DONE])
requestStatus             PASS (TRANSPORT_COMPLETED)
```

## 14. Real MiMo smoke

```text
BLOCKED: missing external MiMo credential (AICOSTOPS_MIMO_API_KEY not set).
No fake PASS is recorded. Same smoke path ran against the local mock above;
the real-provider certification path remains open for an operator-provided key.
```

## 15. CI

```text
GitHub Actions on PR #131 (final run): all 15 checks PASS
  backend-unit / backend-architecture / backend-integration
  gateway-unit / gateway-architecture / gateway-integration
  frontend-lint / frontend-test / frontend-build
  docker-build (backend + frontend + gateway images)
  browser-e2e
  codeql (java-kotlin) / codeql (javascript-typescript) / CodeQL summary
```

## 16. Security

```text
Security workflow on PR #131: PASS
  CodeQL: 2 High alerts (Disabled Spring CSRF protection on the bearer
    API-key data plane) triaged as intentional and excluded in
    .github/codeql/codeql-config.yml with rationale (AIC-091 API-key
    surface, no cookies/sessions); remaining scans clean
  Trivy: vuln+misconfig+secret fs scan and backend/frontend/gateway image
    scans PASS at HIGH/CRITICAL exit-code 1 after pinning Netty
    4.2.16.Final in gateway/pom.xml
```

## 17. PR number + URL

```text
PR #131 — https://github.com/BangShou1st/AI-CostOps/pull/131
```

## 18. Remaining limitations / blockers

```text
- Real MiMo smoke BLOCKED (no AICOSTOPS_MIMO_API_KEY in this environment).
- M11 is an Edge MVP: no Budget Reservation (REQUIRED credentials fail
  closed), no usage facts/Settlement (usage absent stays absent, request
  status reports null metering/settlement), one Provider (MiMo), no
  multi-provider routing/failover, no automatic retry after DISPATCH_INTENT.
- MiMo streaming final-usage certification is deferred to M13.
- CI/Security final status on the PR depends on GitHub runner execution.
```

## 19. git status

```text
on branch feat/m11-gateway-edge-mvp, synced with origin; clean working tree
```

## Frozen adversarial checklist (final pass)

```text
[x] Provider I/O cannot happen before committed DISPATCH_INTENT
[x] same idempotency identity cannot create a second billable attempt
[x] same key + different raw body conflicts
[x] post-dispatch 429/5xx/timeout/reset never auto-retries
[x] client disconnect is CANCELED_AFTER_DISPATCH + BILLABLE_POSSIBLE
[x] missing usage is never zero-filled
[x] REQUIRED budget credential cannot bypass absent M12 Reservation
[x] mandatory Redis limiter cannot fail open
[x] JDBC/MyBatis is off Netty event loop
[x] possibly billable M11 work blocks BillingPeriod Close
[x] prompt/completion/secrets absent from logs/metrics/evidence
[x] Gateway cannot run Flyway
[x] no M12/M13/Ledger functionality leaked into M11
[x] existing Backend tests green
[x] existing Frontend lint/tests/build green
[x] Gateway unit/integration/architecture tests green
[x] Gateway OpenAPI contract tests green
[x] Gateway Docker build green
[x] CI and Security workflows cover Gateway
```