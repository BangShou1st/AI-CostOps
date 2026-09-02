# M11 Gateway Edge MVP — Final One-Shot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans`. Execute Tasks 1→10 continuously on the named branch. One-shot means one milestone/branch/final PR; it does **not** mean one giant commit or skipping tests.

**Goal:** Deliver the entire M11 Gateway Edge MVP: a second Java deployable (`gateway/`) using Spring WebFlux/Reactor Netty, internal hash-only Gateway credentials, one MiMo Provider adapter, bounded OpenAI-compatible Chat Completions, non-streaming + SSE, durable request/route evidence, a committed pre-Provider dispatch fence, idempotency, basic Redis rate limiting, safe observability, CI/Security coverage, and sanitized real-MiMo smoke evidence.

**Architecture:** Backend remains the Control Plane and sole Flyway/admin-data writer. Gateway is the Data Plane: it reads governed credential/catalog/pricing rows, writes only M11 Gateway runtime facts (`gateway_request`, `gateway_route_attempt`), and performs Provider I/O. JDBC/MyBatis stays synchronous but is always offloaded from Reactor Netty to a dedicated bounded scheduler. M11 does not implement Budget Reservation, durable Usage Fact, Settlement, Ledger posting, or multi-Provider failover.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring WebFlux/Reactor Netty, Spring Security, MyBatis 4.1.0, MySQL 8.4, Reactive Redis, Micrometer/Prometheus, JUnit 5, Testcontainers, ArchUnit, Docker, GitHub Actions.

**Authoritative specs:**
- `docs/02-development/v2-detailed-design/README.md`
- `docs/02-development/v2-detailed-design/01-scope-runtime-boundary.md`
- `docs/02-development/v2-detailed-design/02-credentials-catalog-pricing.md`
- `docs/02-development/v2-detailed-design/03-request-state-machine.md`
- `docs/02-development/v2-detailed-design/04-budget-redis-atomicity.md`
- `docs/02-development/v2-detailed-design/05-provider-streaming-metering.md`
- `docs/02-development/v2-detailed-design/06-settlement-financial-boundary.md`
- `docs/02-development/v2-detailed-design/07-routing-resilience.md`
- `docs/02-development/v2-detailed-design/08-security-observability-deployment.md`
- `docs/02-development/v2-detailed-design/09-data-api-migration-testing.md`
- `docs/02-development/api/gateway-openapi.yaml`
- `docs/03-acceptance/m10-design-freeze-matrix.md`

This file is the final execution plan and supersedes the earlier draft `docs/superpowers/plans/2026-09-02-m11-gateway-edge-mvp-plan.md` wherever the two differ.

## Global constraints

- Branch: `feat/m11-gateway-edge-mvp`, based on `main@9480b84071ae362c0a192190cd072b4beb0ae595`.
- `.zcode` = KEEP / DO NOT TOUCH.
- `v1.1.0` tag = immutable; never move/recreate it.
- Backend is the sole production Flyway runner. `gateway/` must have **no Flyway dependency and no migration execution**.
- MySQL = durable identity/financial truth. Redis = recoverable runtime coordination only.
- M11 must create the frozen M11 schema wave only: `service_identity`, `gateway_credential`, `gateway_credential_model`, `provider_credential`, `provider_catalog`, `model_catalog`, `provider_model`, `pricing_version`, `pricing_rate`, `gateway_request`, `gateway_route_attempt`.
- Do **not** create/implement in M11: `budget_reservation`, `gateway_usage_fact`, `gateway_settlement`, Ledger `GATEWAY_SETTLEMENT`, SYSTEM ledger actor changes, Budget Actual mutation, Commitment consumption, multi-Provider routing/failover, hybrid reconciliation.
- Because M12 Budget Reservation is not implemented yet, `gateway_credential.budget_enforcement_mode=REQUIRED` must fail closed before Provider I/O in M11. Local M11 bootstrap uses `OPTIONAL` only. Never claim production budget enforcement in M11.
- Every possibly billable Provider request must commit `gateway_request.billing_period_id`, `gateway_request.state=DISPATCH_INTENT`, and `gateway_route_attempt.status=DISPATCH_INTENT` while holding the same OPEN BillingPeriod financial fence **before** network I/O.
- From `DISPATCH_INTENT` onward, M11 performs no automatic Provider retry/failover for 429/5xx/timeout/reset/unknown outcomes.
- Prompt/completion content is transient and not persisted by default. Missing Provider usage is never fabricated as zero.
- Never log or emit in evidence: Authorization header, raw Gateway key, raw `Idempotency-Key`, credential/request HMAC keys, raw Provider key, Provider ciphertext/nonce, KEK, prompt, completion.
- Public request body is frozen text-only Chat Completions subset. Unknown fields are rejected rather than ignored.
- The raw UTF-8 request body bytes are part of `request_fingerprint`; do not canonicalize JSON before hashing.
- All JDBC/MyBatis work must execute on a dedicated bounded scheduler, never `reactor-http-*` event-loop threads.
- TDD per task: failing test → verify failure → minimal implementation → focused PASS → commit.
- Preserve unrelated local user changes. Never use `git reset --hard`, global Docker prune, or destructive volume cleanup without explicit authorization.

## Exact M11 environment contract

Use these names consistently in Backend/Gateway/runbook/tests. Secret values are never committed.

```text
AICOSTOPS_GATEWAY_PORT=8081
AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1=<Base64 of exactly 32 random bytes>
AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1=<Base64 of exactly 32 random bytes>
AICOSTOPS_PROVIDER_KEK_V1=<Base64 of exactly 32 random bytes>
AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED=false
AICOSTOPS_GATEWAY_DEV_RAW_KEY=<valid aic_<prefix>_<secret> key; dev/test only>
AICOSTOPS_MIMO_API_KEY=<real MiMo key; optional except real-provider acceptance>
AICOSTOPS_GATEWAY_RATE_LIMIT_ENABLED=true
AICOSTOPS_GATEWAY_RATE_LIMIT_CAPACITY=60
AICOSTOPS_GATEWAY_RATE_LIMIT_REFILL_PER_SECOND=1
AICOSTOPS_GATEWAY_DB_THREADS=12
AICOSTOPS_GATEWAY_DB_QUEUE_CAPACITY=256
AICOSTOPS_GATEWAY_DB_POOL_MAX=12
AICOSTOPS_GATEWAY_MAX_ACTIVE_STREAMS=128
AICOSTOPS_GATEWAY_MAX_REQUEST_BYTES=1048576
AICOSTOPS_GATEWAY_MAX_HEADER_BYTES=16384
AICOSTOPS_GATEWAY_MAX_IN_MEMORY_BYTES=16777216
AICOSTOPS_GATEWAY_CONNECT_TIMEOUT_MS=5000
AICOSTOPS_GATEWAY_HEADER_TIMEOUT_MS=60000
AICOSTOPS_GATEWAY_STREAM_IDLE_TIMEOUT_MS=60000
AICOSTOPS_GATEWAY_HARD_TIMEOUT_MS=600000
```

Existing MySQL/Redis environment variables remain the connection source; do not add duplicate credential conventions.

## Exit IDs

```text
AIC-094  Gateway deployable + exact M11 schema wave
AIC-095  Backend-owned dev provisioning + crypto boundary
AIC-096  Gateway auth/idempotency/request/dispatch fence + Close blocker
AIC-097  MiMo non-streaming Chat Completions
AIC-098  MiMo SSE/disconnect/timeouts
AIC-099  Redis rate limit + bounded runtime resources
AIC-100  Request status + safe error/metrics/logging/config validation
AIC-101  Docker/CI/Security/runbook/smoke
AIC-102  Final M11 acceptance and one PR
```

---

## Task 1 — AIC-094: Bootstrap the second deployable

**Create exactly:**
- `gateway/pom.xml`
- `gateway/mvnw`
- `gateway/mvnw.cmd`
- `gateway/.mvn/wrapper/maven-wrapper.properties` (adapt/copy existing wrapper assets from `backend/`)
- `gateway/src/main/java/com/aicostops/gateway/GatewayApplication.java`
- `gateway/src/main/java/com/aicostops/gateway/config/GatewayProperties.java`
- `gateway/src/main/java/com/aicostops/gateway/config/BlockingIoScheduler.java`
- `gateway/src/main/java/com/aicostops/gateway/config/GatewayBlockingIoScheduler.java`
- `gateway/src/main/resources/application.yml`
- `gateway/src/main/resources/application-local.yml`
- `gateway/src/main/resources/application-prod.yml`
- `gateway/src/test/java/com/aicostops/gateway/GatewayApplicationTest.java`
- `gateway/src/test/java/com/aicostops/gateway/architecture/GatewayArchitectureTest.java`

**Dependency baseline:** Spring Boot 4.1.0 / Java 21 / MyBatis 4.1.0 / Testcontainers 2.0.5 / ArchUnit 1.4.2. Use WebFlux, validation, security, reactive Redis, actuator, Prometheus, MyBatis, MySQL runtime, Boot tests, Spring Security tests, Testcontainers and ArchUnit only. Do not add Flyway/R2DBC/MQ/Kafka/Kubernetes/Resilience4j.

Define the interface without undefined helper types:

```java
public interface BlockingIoScheduler extends AutoCloseable {
    <T> reactor.core.publisher.Mono<T> call(java.util.concurrent.Callable<T> operation);
    reactor.core.publisher.Mono<Void> run(java.lang.Runnable operation);
}
```

Back it with a dedicated bounded Reactor scheduler using the configured `DB_THREADS` and `DB_QUEUE_CAPACITY`; do not use the shared global bounded-elastic scheduler as the application boundary.

**Tests first:**
- Spring context starts with test config.
- Architecture test proves `gateway` does not depend on `com.aicostops` Backend code outside its own package tree.
- Architecture/dependency test proves no Flyway class/dependency is present.
- Configuration rejects non-positive scheduler/pool/body/header/stream bounds.

**Verify:** `gateway\mvnw.cmd -B test`.

**Commit:** `feat(gateway): bootstrap M11 WebFlux data plane`.

---

## Task 2 — AIC-094: Add the exact M11 schema in Backend Flyway

**Create:**
- `backend/src/main/resources/db/migration/V18__m11_gateway_edge_foundation.sql` (if V18 is still next free; otherwise use the next free version)
- `backend/src/test/java/com/aicostops/gatewayadmin/GatewayM11SchemaIntegrationTest.java`

Implement the exact AIC-092 columns/types/checks/same-org FKs for:

```text
service_identity
gateway_credential
gateway_credential_model
provider_credential
provider_catalog
model_catalog
provider_model
pricing_version
pricing_rate
gateway_request
gateway_route_attempt
```

Do not edit V1-V17. Do not create M12/M13/Ledger tables.

**Must prove with real MySQL failing inserts/concurrency where applicable:**
- credential prefix uniqueness and principal XOR;
- explicit credential-model relation path;
- public request ID uniqueness;
- `(org_id, credential_id, idempotency_key_digest)` uniqueness;
- request state CHECK exactly matches M10;
- route attempt `(org_id,request_id,attempt_no)` and route-decision uniqueness;
- route status CHECK exactly matches M10;
- same-org FK violations fail;
- `billing_period_id` exists for later dispatch fencing;
- historical V1 migrations/checksums and normal V1 tables remain usable.

**Verify:** `backend\mvnw.cmd -B "-Dgroups=integration" verify`.

**Commit:** `feat(db): add M11 gateway edge foundation schema`.

---

## Task 3 — AIC-095: Backend-owned dev provisioning and Provider-secret encryption

**Create:**
- `backend/src/main/java/com/aicostops/gatewayadmin/security/ProviderCredentialEncryptor.java`
- `backend/src/main/java/com/aicostops/gatewayadmin/infrastructure/GatewayAdminMapper.java`
- `backend/src/main/java/com/aicostops/gatewayadmin/infrastructure/DevGatewayBootstrap.java`
- `backend/src/test/java/com/aicostops/gatewayadmin/security/ProviderCredentialEncryptorTest.java`
- `backend/src/test/java/com/aicostops/gatewayadmin/infrastructure/DevGatewayBootstrapIntegrationTest.java`

**Modify:**
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-prod.yml`
- `backend/src/main/java/com/aicostops/config/ProductionConfigurationValidator.java`
- `backend/src/test/java/com/aicostops/config/ProductionConfigurationValidatorTest.java`
- `.env.example` with placeholders only; never real secrets.

Provider secret crypto format:

```text
AES/GCM/NoPadding
key = Base64-decoded exactly 32 bytes from AICOSTOPS_PROVIDER_KEK_V1
nonce = cryptographically random 12 bytes
GCM tag = 128 bits
ciphertext column = JCE ciphertext+tag
encryption_key_version = 1
```

AAD used by both Backend encryptor and Gateway decryptor:

```text
UTF-8("aicostops:v2:provider-credential:v1\0"
      + orgId + "\0"
      + providerAccountId + "\0"
      + credentialType + "\0"
      + encryptionKeyVersion)
```

Gateway key digest:

```text
raw shape = ^aic_[0-9a-hjkmnp-tv-z]{12}_[A-Za-z0-9_-]{43}$
digest = HMAC-SHA-256(raw secret part, Base64-decoded 32-byte AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1)
raw key never persisted
```

`DevGatewayBootstrap` is enabled only when existing dev mode AND `AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED=true`. It creates/upserts a local SERVICE identity, one OPTIONAL Gateway credential, explicit `default-chat` credential-model relation, MiMo catalog/model mapping, one existing-org Provider Account binding, Pricing Version/rates, and (when `AICOSTOPS_MIMO_API_KEY` is supplied) encrypted Provider credential.

M11 MiMo baseline:

```text
provider_code=MIMO
base_url=https://api.xiaomimimo.com/v1
provider_model_name=mimo-v2.5-pro
upstream auth=api-key
logical model key=default-chat (local bootstrap)
```

Production validation must reject Gateway dev bootstrap and malformed/missing required production key material. No M11 production admin HTTP UI/API is added.

**Tests first:** AES-GCM round-trip, tamper, wrong AAD, wrong key, bootstrap idempotency, raw secret absence from persisted columns/logs, prod rejects dev bootstrap.

**Commit:** `feat(gateway-admin): provision M11 credentials safely`.

---

## Task 4 — AIC-096: Gateway persistence/auth/idempotency/dispatch fence and period-close safety

**Create in Gateway:**
- `gateway/src/main/java/com/aicostops/gateway/auth/GatewayApiKeyParser.java`
- `gateway/src/main/java/com/aicostops/gateway/auth/GatewayPrincipal.java`
- `gateway/src/main/java/com/aicostops/gateway/auth/GatewayAuthenticationManager.java`
- `gateway/src/main/java/com/aicostops/gateway/persistence/GatewayReadMapper.java`
- `gateway/src/main/java/com/aicostops/gateway/persistence/GatewayRequestMapper.java`
- `gateway/src/main/java/com/aicostops/gateway/request/RequestIdentityService.java`
- `gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestService.java`
- `gateway/src/main/java/com/aicostops/gateway/request/DispatchFenceService.java`
- `gateway/src/main/java/com/aicostops/gateway/web/GatewayErrorHandler.java`
- focused unit/integration tests under the corresponding `gateway/src/test/java/com/aicostops/gateway/...` package.

**Modify/create in Backend for Close safety:**
- modify `backend/src/main/java/com/aicostops/reconciliation/domain/CloseBlockerCode.java` to add `PENDING_GATEWAY_FINANCIAL_WORK`;
- create `backend/src/main/java/com/aicostops/reconciliation/application/blockers/GatewayFinancialWorkBlockerProvider.java`;
- create `backend/src/main/java/com/aicostops/reconciliation/infrastructure/GatewayCloseBlockerMapper.java`;
- create `backend/src/test/java/com/aicostops/reconciliation/application/blockers/GatewayFinancialWorkBlockerProviderTest.java`;
- extend an existing BillingPeriod Close integration test or create `backend/src/test/java/com/aicostops/reconciliation/GatewayFinancialWorkCloseIntegrationTest.java`.

**Authentication:** parse prefix/secret, DB lookup by prefix, HMAC the secret with configured key version, constant-time compare; then verify ACTIVE/not-expired credential, principal, project, financial scope, explicit logical-model relation, active Provider account/credential/model/pricing.

**Idempotency:**

```text
idempotency_key_digest = HMAC(requestKeyV1, "idem\0" + rawIdempotencyKey)
request_fingerprint = HMAC(requestKeyV1,
  "request\0" + "POST" + "\0" + "/v1/chat/completions" + "\0" + rawUtf8BodyBytes)
```

Raw idempotency key is 1..128 visible ASCII; never persist/log it.

**M11 financial boundary:** if credential budget mode is REQUIRED, return fail-closed policy/dependency error before Provider I/O because M12 reservation is intentionally absent. OPTIONAL may proceed.

**Dispatch fence transaction:**
1. Insert/converge `gateway_request` in `VALIDATED`.
2. Create attempt 1 `PLANNED` with Provider Account/Provider Model/Pricing Version snapshot.
3. In one MySQL transaction lock the applicable BillingPeriod row `FOR UPDATE`.
4. Require period `OPEN`.
5. Persist `gateway_request.billing_period_id`.
6. Transition request and attempt to `DISPATCH_INTENT` and timestamps.
7. Commit.
8. Only after commit return control to the Provider-call layer.

**Close blocker in M11:** because M13 Usage/Settlement does not yet exist, any request for the target period that has crossed the billable fence blocks normal Close. Count states:

```text
DISPATCH_INTENT
UPSTREAM_ACTIVE
TRANSPORT_COMPLETED
CANCELED_AFTER_DISPATCH
TIMED_OUT_AFTER_DISPATCH
FAILED_AFTER_DISPATCH
```

This is deliberately conservative. M13 may narrow the blocker using FINAL usage/Settlement truth, but M11 must never allow a possibly billed request to disappear behind a CLOSED period.

**Concurrency tests on real MySQL:**
- same key/same body simultaneous -> one request and one attempt;
- same key/different body -> deterministic 409;
- close wins period lock -> no `DISPATCH_INTENT`, therefore no Provider call;
- dispatch fence commits first -> `PENDING_GATEWAY_FINANCIAL_WORK` blocks Close;
- DB operation thread is never `reactor-http-*`.

**Commit:** `feat(gateway): enforce durable dispatch and close safety`.

---

## Task 5 — AIC-097: MiMo non-streaming Chat Completions

**Create:**
- `gateway/src/main/java/com/aicostops/gateway/provider/ProviderChatAdapter.java`
- `gateway/src/main/java/com/aicostops/gateway/provider/ProviderCallContext.java`
- `gateway/src/main/java/com/aicostops/gateway/provider/ProviderCredentialDecryptor.java`
- `gateway/src/main/java/com/aicostops/gateway/provider/mimo/MimoChatAdapter.java`
- `gateway/src/main/java/com/aicostops/gateway/provider/mimo/MimoWireDtos.java`
- `gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java`
- `gateway/src/main/java/com/aicostops/gateway/web/dto/ChatCompletionRequest.java`
- `gateway/src/main/java/com/aicostops/gateway/web/dto/ChatCompletionResponse.java`
- matching tests under `gateway/src/test/java/com/aicostops/gateway/provider/mimo/` and `.../web/`.

Provider interface:

```java
public interface ProviderChatAdapter {
    Mono<ProviderChatCompletion> complete(ProviderCallContext context, ChatCompletionCommand command);
    Flux<ProviderChatChunk> stream(ProviderCallContext context, ChatCompletionCommand command);
}
```

`ProviderCallContext` must never expose a secret-bearing `toString()`.

MiMo call:

```text
POST https://api.xiaomimimo.com/v1/chat/completions (host/base URL from server catalog only)
api-key: <decrypted Provider secret>
Content-Type: application/json
model: provider_model.provider_model_name
retry operator: NONE
```

Public DTO must match `gateway-openapi.yaml`: only logical `model`, text messages with roles developer/system/user/assistant, optional `max_completion_tokens`, optional `stream`. Reject unknown fields, content encoding and >1 MiB decoded body. If `max_completion_tokens` is absent, a finite governed model default must exist and be enforced upstream.

State progression on success:

```text
request: DISPATCH_INTENT -> UPSTREAM_ACTIVE -> TRANSPORT_COMPLETED
route: DISPATCH_INTENT -> BILLABLE_POSSIBLE -> COMPLETED
```

Post-dispatch timeout/failure leaves route `BILLABLE_POSSIBLE`; no automatic retry.

**Mock-upstream tests first:** exact provider model mapping, `api-key` header present upstream but never downstream/logged, 200 mapping, unsupported field 400, auth 401, model deny 403, oversized 413, 429/500/503/reset each sends exactly one upstream request.

**Commit:** `feat(gateway): proxy MiMo chat completions`.

---

## Task 6 — AIC-098: SSE streaming/disconnect/timeouts

**Create/modify:**
- `gateway/src/main/java/com/aicostops/gateway/provider/mimo/MimoSseDecoder.java`
- `gateway/src/main/java/com/aicostops/gateway/web/GatewaySseEncoder.java`
- `gateway/src/main/java/com/aicostops/gateway/request/StreamingLifecycleService.java`
- extend `MimoChatAdapter` and `ChatCompletionController`.

Timeout defaults are the exact environment contract above. Use Reactor Netty/WebClient timeout controls; never add generic `retryWhen` after the dispatch fence.

Rules:
- `stream=true` upstream and `text/event-stream` downstream;
- parse/normalize chunks without aggregating the entire completion;
- `[DONE]` exactly once on normal completion;
- if usage is missing, omit it; never emit zero usage as a guess;
- M11 does not persist `gateway_usage_fact`;
- client cancel after dispatch -> request `CANCELED_AFTER_DISPATCH`, route `BILLABLE_POSSIBLE`;
- provider timeout after dispatch -> request `TIMED_OUT_AFTER_DISPATCH`, route `BILLABLE_POSSIBLE`;
- if SSE bytes already started, persist failure/cancel truth and terminate stream rather than attempting a replacement JSON error body.

**Tests first:** multi-chunk happy path, `[DONE]`, client cancel, idle timeout, hard timeout, reset after some chunks, one-upstream-call assertion, high-volume controlled stream proving bounded buffering/no full aggregation.

**Commit:** `feat(gateway): add bounded MiMo SSE streaming`.

---

## Task 7 — AIC-099: Atomic Redis rate limit and runtime resource bounds

**Create:**
- `gateway/src/main/resources/redis/gateway-rate-limit.lua`
- `gateway/src/main/java/com/aicostops/gateway/ratelimit/GatewayRateLimiter.java`
- `gateway/src/main/java/com/aicostops/gateway/ratelimit/RedisTokenBucketRateLimiter.java`
- `gateway/src/main/java/com/aicostops/gateway/config/GatewayResourceLimiter.java`
- Redis integration tests under `gateway/src/test/java/com/aicostops/gateway/ratelimit/`.

Redis key:

```text
aicostops:v2:gateway:ratelimit:{credentialId}
```

Lua arguments: capacity, refill_per_second, now_millis, cost=1. Return `[1, remaining, 0]` or `[0, remaining, retry_after_millis]`. Use server-supplied credential ID only; no raw key/prefix in the key.

If enabled limiter cannot reach/evaluate Redis, fail closed with `503 GATEWAY_DEPENDENCY_UNAVAILABLE` before Provider I/O.

Implement global active-stream semaphore using configured 128 default permits. Release on complete/error/cancel.

**Tests first:** burst, refill, concurrent no-over-capacity, Redis down=no Provider call, stream permit release on success/error/cancel.

**Commit:** `feat(gateway): bound M11 rate and stream concurrency`.

---

## Task 8 — AIC-100: Status API, errors, telemetry, redaction and production validation

**Create:**
- `gateway/src/main/java/com/aicostops/gateway/web/GatewayRequestStatusController.java`
- `gateway/src/main/java/com/aicostops/gateway/web/GatewayErrorCode.java`
- `gateway/src/main/java/com/aicostops/gateway/observability/CorrelationWebFilter.java`
- `gateway/src/main/java/com/aicostops/gateway/observability/GatewayMetrics.java`
- `gateway/src/main/java/com/aicostops/gateway/config/GatewayProductionConfigurationValidator.java`
- corresponding focused tests.

Implement `GET /v1/gateway/requests/{requestId}` exactly. Only the owning credential can see it; wrong owner and absent ID both 404. In M11 `meteringStatus=null` and `settlementStatus=null` because M13 owns them.

Errors use `gateway-openapi.yaml` envelope/codes and correlation headers. Contract test must parse `gateway-openapi.yaml` (use the repository's existing SnakeYAML-style OpenAPI contract-test pattern if convenient) and lock M11 operation IDs/statuses/required headers.

Allowed bounded metric labels include provider code and small outcome enums only. Never label request/trace/credential/org/project/user/provider-request IDs or arbitrary client model text.

Redaction tests inject sentinel secrets/prompt/completion/idempotency values and assert captured logs do not contain them.

Production validator rejects missing/malformed Base64 32-byte HMAC/KEK secrets, dev bootstrap enabled, and non-positive/unbounded runtime limits. Provider call boundary rejects non-HTTPS or non-approved production MiMo hosts.

**Commit:** `feat(gateway): expose safe M11 status and telemetry`.

---

## Task 9 — AIC-101: Docker, CI, Security, runbook and smoke

**Create:**
- `gateway/Dockerfile`
- `scripts/smoke-m11-gateway.ps1`
- `docs/02-development/implementation/06-m11-gateway-local-runbook.md`
- `docs/03-acceptance/m11-gateway-edge-evidence.md`

**Modify:**
- `.github/workflows/ci.yml`
- `.github/workflows/security.yml`
- `.github/codeql/codeql-config.yml`
- `README.md` only for actual M11 run commands/status if implementation is verified.

CI adds separate jobs:

```text
gateway-unit
gateway-integration
gateway-architecture
```

and `docker-build` also builds `ai-costops-gateway:ci`.

Security:
- CodeQL path includes `gateway/src`;
- Java extractor real-builds both Backend and Gateway;
- Trivy cache hash includes `gateway/pom.xml`;
- filesystem scan excludes only generated `gateway/target`, not Gateway source/pom;
- build and HIGH/CRITICAL scan `ai-costops-gateway:security`.

Daily dev remains native processes: Frontend 5173, Backend 8080, Gateway 8081; Docker only MySQL/Redis/MinIO. Do not add Gateway to `compose.dev.yaml` in M11.

Smoke script must not print keys. It checks liveness/readiness, one non-streaming request, duplicate idempotency behavior without redispatch, one SSE request to `[DONE]`, and request status. With `AICOSTOPS_MIMO_API_KEY` present it runs the same path against real MiMo and records only sanitized provider/model/status/state/timestamps.

**Commit:** `ci(gateway): gate M11 runtime and security`.

---

## Task 10 — AIC-102: Final verification, evidence, push and final PR

No architecture changes here. Run fresh verification from branch HEAD.

```powershell
Set-Location "E:\project\AI-CostOps"
git status --short
git log --oneline --decorate -20

Push-Location backend
.\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
.\mvnw.cmd -B "-Dgroups=architecture" test
.\mvnw.cmd -B "-Dgroups=integration" verify
Pop-Location

Push-Location gateway
.\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test
.\mvnw.cmd -B "-Dgroups=architecture" test
.\mvnw.cmd -B "-Dgroups=integration" verify
Pop-Location

Push-Location frontend
npm ci
npm run lint
npm test -- --run
npm run build
Pop-Location

docker build --tag ai-costops-gateway:m11 gateway
.\scripts\smoke-m11-gateway.ps1
```

Real MiMo smoke is required before **Provider path** is marked PASS. If no real key exists, only that acceptance line stays `NOT RUN`; never invent PASS. The agent may still push the branch and report the external blocker.

Final adversarial checklist:

```text
[ ] no Provider I/O before committed DISPATCH_INTENT
[ ] same idempotency identity cannot create a second billable attempt
[ ] same key + different raw body = conflict
[ ] post-dispatch 429/5xx/timeout/reset never auto-retries
[ ] client disconnect = CANCELED_AFTER_DISPATCH + BILLABLE_POSSIBLE
[ ] missing usage is never zero-filled
[ ] REQUIRED budget credential cannot bypass absent M12 Reservation
[ ] mandatory Redis limiter failure cannot fail open
[ ] JDBC/MyBatis work is off Netty event loop
[ ] possibly billable M11 work blocks BillingPeriod Close
[ ] prompts/completions/secrets absent from logs/metrics/evidence
[ ] Gateway cannot run Flyway
[ ] no M12/M13/Ledger implementation leaked into M11
[ ] existing Backend/Frontend tests remain green
[ ] Gateway OpenAPI contract tests pass
[ ] Gateway Docker build passes
[ ] CI and Security workflows actually cover Gateway
```

Update `docs/03-acceptance/m11-gateway-edge-evidence.md` with fresh counts, branch HEAD, test commands/results, mock smoke, sanitized real-MiMo result, and known limitations. No stale counts and no PASS for unexecuted checks.

Commit evidence:

```text
docs(m11): record Gateway Edge MVP acceptance evidence
```

Then:

```powershell
git push -u origin feat/m11-gateway-edge-mvp
```

Create one final PR titled:

```text
feat(m11): deliver Gateway Edge MVP
```

Do not merge it. Report PR number/URL, final branch HEAD, commit list, exact fresh test results, CI/Security status if available, and sanitized real-MiMo evidence. Sol performs independent review and the human gives explicit merge authorization.

## Allowed stop conditions

The local agent should not stop for routine implementation decisions. It may stop only when:

1. frozen M10 documents contradict each other in a correctness-changing way;
2. the only remaining acceptance gate is real MiMo and no real key is available;
3. unrelated local modifications would be overwritten and cannot be safely isolated.

If any test fails, fix it before advancing. If CI fails after push, diagnose/fix on the same branch rather than declaring M11 complete.