# M11 Gateway Edge MVP — Final One-Shot Implementation Plan

> **Authoritative execution plan.** This file supersedes `2026-09-02-m11-gateway-edge-mvp-plan.md` wherever they differ. Use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Execute Tasks 1 through 10 continuously on the named feature branch. One-shot means one milestone, one branch and one final PR; each task still follows TDD, verification and a focused commit.

## Goal

Deliver the entire M11 Gateway Edge MVP:

```text
AI-CostOps Gateway key
→ bounded OpenAI-compatible POST /v1/chat/completions
→ durable request + route attempt
→ committed BillingPeriod / DISPATCH_INTENT financial fence
→ one MiMo Provider adapter
→ non-streaming JSON or SSE streaming
→ durable request/route terminal state
```

M11 also delivers request-status recovery, basic Redis rate limiting, production-safe configuration, safe logs/metrics, Gateway Docker build, CI/Security gates, local smoke and sanitized real-MiMo smoke evidence.

M11 does not implement Budget Reservation, durable Usage Fact, Settlement, Ledger posting or multi-Provider failover.

## Frozen authority

Read before coding:

- `PROJECT_CONTEXT.md`
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

Business invariants in the M10 detailed design outrank implementation convenience.

## Branch and non-negotiable constraints

- Work only on `feat/m11-gateway-edge-mvp`.
- Baseline is `main@9480b84071ae362c0a192190cd072b4beb0ae595`.
- `.zcode` = KEEP / DO NOT TOUCH.
- Never move/recreate tag `v1.1.0`.
- Never push directly to `main`.
- Backend remains the sole production Flyway runner; Gateway has no Flyway dependency.
- Gateway JDBC/MyBatis work never runs on Reactor Netty event-loop threads.
- MySQL is durable identity/financial truth; Redis is runtime coordination only.
- M11 schema wave is exactly: `service_identity`, `gateway_credential`, `gateway_credential_model`, `provider_credential`, `provider_catalog`, `model_catalog`, `provider_model`, `pricing_version`, `pricing_rate`, `gateway_request`, `gateway_route_attempt`.
- Do not implement `budget_reservation`, `gateway_usage_fact`, `gateway_settlement`, Ledger `GATEWAY_SETTLEMENT`, SYSTEM ledger actor changes, Budget Actual mutation, Commitment consumption, M14 routing/failover or M15 reconciliation.
- `budget_enforcement_mode=REQUIRED` fails closed before Provider I/O in M11 because M12 Reservation does not exist yet. M11 dev bootstrap credentials use `OPTIONAL` only.
- Before any possibly billable Provider call, commit `gateway_request.billing_period_id`, request `DISPATCH_INTENT` and route-attempt `DISPATCH_INTENT` while holding the OPEN BillingPeriod financial fence.
- After `DISPATCH_INTENT`, no automatic Provider retry/failover for 429, 5xx, timeout, reset or unknown outcome.
- Prompt/completion content is transient and not persisted by default.
- Missing usage is omitted and never fabricated as zero.
- Never log/evidence: Authorization, raw Gateway key, raw Idempotency-Key, HMAC keys, raw Provider key, Provider ciphertext/nonce, KEK, prompt or completion.
- Request fingerprint uses raw UTF-8 body bytes. Do not canonicalize JSON.
- Preserve unrelated user changes. Never use `git reset --hard`, global Docker prune or destructive volume cleanup without explicit authorization.

## Exact environment contract

```text
AICOSTOPS_GATEWAY_PORT=8081
AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1=<Base64 exactly 32 random bytes>
AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1=<Base64 exactly 32 random bytes>
AICOSTOPS_PROVIDER_KEK_V1=<Base64 exactly 32 random bytes>
AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED=false
AICOSTOPS_GATEWAY_DEV_RAW_KEY=<valid aic_<prefix>_<secret>; dev/test only>
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

Use the repository's existing MySQL and Redis connection property conventions. In production, Backend and Gateway may receive different DB credentials through the same property names; Gateway's DB user must be least-privilege and must not be able to write Ledger/Budget/Commitment/Settlement/admin truth.

## Exit IDs

```text
AIC-094 Gateway deployable + exact M11 schema wave
AIC-095 Backend-owned dev provisioning + crypto boundary
AIC-096 Auth + idempotency + dispatch fence + period-close safety
AIC-097 MiMo non-streaming Chat Completions
AIC-098 MiMo SSE + disconnect + timeout semantics
AIC-099 Atomic Redis rate limit + bounded resources
AIC-100 Status API + safe errors/logging/metrics/config
AIC-101 Docker + CI + Security + runbook + smoke
AIC-102 Final M11 acceptance + final PR
```

---

# Task 1 — AIC-094: Bootstrap `gateway/`

Create:

- `gateway/pom.xml`
- `gateway/mvnw`
- `gateway/mvnw.cmd`
- `gateway/.mvn/wrapper/maven-wrapper.properties`
- `gateway/src/main/java/com/aicostops/gateway/GatewayApplication.java`
- `gateway/src/main/java/com/aicostops/gateway/config/GatewayProperties.java`
- `gateway/src/main/java/com/aicostops/gateway/config/BlockingIoScheduler.java`
- `gateway/src/main/java/com/aicostops/gateway/config/GatewayBlockingIoScheduler.java`
- `gateway/src/main/resources/application.yml`
- `gateway/src/main/resources/application-local.yml`
- `gateway/src/main/resources/application-prod.yml`
- `gateway/src/test/java/com/aicostops/gateway/GatewayApplicationTest.java`
- `gateway/src/test/java/com/aicostops/gateway/architecture/GatewayArchitectureTest.java`
- `gateway/src/test/java/com/aicostops/gateway/config/GatewayPropertiesTest.java`

Pin Spring Boot 4.1.0, Java 21, MyBatis 4.1.0, Testcontainers 2.0.5 and ArchUnit 1.4.2. Use WebFlux, validation, security, reactive Redis, actuator, Prometheus, MyBatis, MySQL runtime, Spring Boot tests, Spring Security tests, Testcontainers and ArchUnit. Do not add Flyway, R2DBC, RabbitMQ, Kafka, Kubernetes or Resilience4j.

Define:

```java
public interface BlockingIoScheduler extends AutoCloseable {
    <T> reactor.core.publisher.Mono<T> call(java.util.concurrent.Callable<T> operation);
    reactor.core.publisher.Mono<Void> run(java.lang.Runnable operation);
}
```

Implement one dedicated bounded Reactor scheduler using configured DB thread/queue limits; do not expose the shared global bounded-elastic scheduler as the DB boundary.

TDD:

1. Write context/architecture/config tests first and observe failure.
2. Prove Gateway has no Backend-class dependency and no Flyway dependency.
3. Prove non-positive/unbounded DB/body/header/stream limits reject startup.
4. Implement minimal application, liveness/readiness and Prometheus exposure.
5. Run `gateway\mvnw.cmd -B test`.

Commit: `feat(gateway): bootstrap M11 WebFlux data plane`.

---

# Task 2 — AIC-094: Backend-owned M11 Flyway schema

Create:

- `backend/src/main/resources/db/migration/V18__m11_gateway_edge_foundation.sql` if V18 is still next free; otherwise use the next free version.
- `backend/src/test/java/com/aicostops/gatewayadmin/GatewayM11SchemaIntegrationTest.java`

Implement every AIC-092 column/type/index/check/same-org FK for the eleven M11 tables. Do not alter V1-V17. Do not create M12/M13/Ledger schema.

Test on fresh Testcontainers MySQL:

- credential prefix uniqueness;
- HUMAN_MEMBER/SERVICE principal XOR;
- explicit credential-model relation;
- public request ID uniqueness;
- `(org_id, credential_id, idempotency_key_digest)` uniqueness;
- request-state CHECK;
- route `(org_id, request_id, attempt_no)` uniqueness;
- route-decision uniqueness;
- route-state CHECK;
- same-org FK violation failure;
- exact digest/money/timestamp column types required by AIC-092;
- migrations from empty DB through M11 succeed and V1 tables remain usable.

Run: `backend\mvnw.cmd -B "-Dgroups=integration" verify`.

Commit: `feat(db): add M11 gateway edge foundation schema`.

---

# Task 3 — AIC-095: Backend dev provisioning and crypto

Create:

- `backend/src/main/java/com/aicostops/gatewayadmin/security/ProviderCredentialEncryptor.java`
- `backend/src/main/java/com/aicostops/gatewayadmin/infrastructure/GatewayAdminMapper.java`
- `backend/src/main/java/com/aicostops/gatewayadmin/infrastructure/DevGatewayBootstrap.java`
- `backend/src/test/java/com/aicostops/gatewayadmin/security/ProviderCredentialEncryptorTest.java`
- `backend/src/test/java/com/aicostops/gatewayadmin/infrastructure/DevGatewayBootstrapIntegrationTest.java`

Modify:

- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/application-prod.yml`
- `backend/src/main/java/com/aicostops/config/ProductionConfigurationValidator.java`
- `backend/src/test/java/com/aicostops/config/ProductionConfigurationValidatorTest.java`
- `.env.example` using placeholders only.

Provider credential encryption:

```text
AES/GCM/NoPadding
key = Base64-decoded exactly 32 bytes from AICOSTOPS_PROVIDER_KEK_V1
nonce = SecureRandom 12 bytes
GCM tag = 128 bits
ciphertext column = JCE ciphertext + tag
encryption_key_version = 1
AAD = UTF-8("aicostops:v2:provider-credential:v1\0" + orgId + "\0" + providerAccountId + "\0" + credentialType + "\0" + encryptionKeyVersion)
```

Gateway key:

```text
shape = ^aic_[0-9a-hjkmnp-tv-z]{12}_[A-Za-z0-9_-]{43}$
digest = HMAC-SHA-256(secret-part, Base64-decoded AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1)
raw key never persisted
```

`DevGatewayBootstrap` only runs in existing dev mode when `AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED=true`. It idempotently provisions one local SERVICE identity, one OPTIONAL Gateway credential, explicit `default-chat` model relation, MiMo catalog/model mapping, active Pricing Version/rates, and encrypted Provider credential when `AICOSTOPS_MIMO_API_KEY` is present.

M11 MiMo baseline:

```text
provider_code=MIMO
base_url=https://api.xiaomimimo.com/v1
provider_model_name=mimo-v2.5-pro
upstream auth header=api-key
logical model key=default-chat for dev bootstrap
```

Do not add a production admin CRUD API in M11.

TDD: crypto round-trip, tamper, wrong AAD, wrong key, bootstrap idempotency, no plaintext secret persistence/logs, production rejects Gateway dev bootstrap and malformed key material.

Commit: `feat(gateway-admin): provision M11 credentials safely`.

---

# Task 4 — AIC-096: Auth, idempotency, durable dispatch and period-close safety

Create Gateway code:

- `gateway/src/main/java/com/aicostops/gateway/auth/GatewayApiKeyParser.java`
- `gateway/src/main/java/com/aicostops/gateway/auth/GatewayPrincipal.java`
- `gateway/src/main/java/com/aicostops/gateway/auth/GatewayAuthenticationManager.java`
- `gateway/src/main/java/com/aicostops/gateway/persistence/GatewayReadMapper.java`
- `gateway/src/main/java/com/aicostops/gateway/persistence/GatewayRequestMapper.java`
- `gateway/src/main/java/com/aicostops/gateway/request/RequestIdentityService.java`
- `gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestService.java`
- `gateway/src/main/java/com/aicostops/gateway/request/DispatchFenceService.java`
- `gateway/src/main/java/com/aicostops/gateway/web/GatewayErrorHandler.java`

Create Gateway tests:

- `gateway/src/test/java/com/aicostops/gateway/auth/GatewayAuthenticationManagerTest.java`
- `gateway/src/test/java/com/aicostops/gateway/request/GatewayRequestIdempotencyIntegrationTest.java`
- `gateway/src/test/java/com/aicostops/gateway/request/DispatchFenceIntegrationTest.java`
- `gateway/src/test/java/com/aicostops/gateway/request/BlockingIoSchedulerIntegrationTest.java`

Modify/create Backend close safety:

- modify `backend/src/main/java/com/aicostops/reconciliation/domain/CloseBlockerCode.java` adding `PENDING_GATEWAY_FINANCIAL_WORK`;
- create `backend/src/main/java/com/aicostops/reconciliation/application/blockers/GatewayFinancialWorkBlockerProvider.java`;
- create `backend/src/main/java/com/aicostops/reconciliation/infrastructure/GatewayCloseBlockerMapper.java`;
- create `backend/src/test/java/com/aicostops/reconciliation/application/blockers/GatewayFinancialWorkBlockerProviderTest.java`;
- create `backend/src/test/java/com/aicostops/reconciliation/GatewayFinancialWorkCloseIntegrationTest.java` unless an existing close integration test is clearly the better location.

Authentication validates: prefix/digest constant-time match, ACTIVE/not-expired credential, active principal/project/financial scope, explicit allowed logical model, active Provider Account, active Provider Credential, eligible Provider Model and resolvable Pricing Version.

Request HMAC:

```text
idempotency_key_digest = HMAC(requestKeyV1, "idem\0" + rawIdempotencyKey)
request_fingerprint = HMAC(requestKeyV1, "request\0POST\0/v1/chat/completions\0" + rawUtf8BodyBytes)
```

Idempotency-Key is 1 to 128 visible ASCII, not logged/persisted raw.

Before Provider I/O:

1. authenticate and validate model/commercial context;
2. `REQUIRED` budget credential fails closed in M11;
3. insert/converge request `VALIDATED`;
4. create route attempt 1 `PLANNED` with Provider Account/Provider Model/Pricing Version snapshot;
5. begin short MySQL transaction;
6. lock applicable BillingPeriod `FOR UPDATE` and require `OPEN`;
7. persist `billing_period_id`;
8. request -> `DISPATCH_INTENT`, route -> `DISPATCH_INTENT`, persist timestamps;
9. commit;
10. only then return to Provider-call code.

Idempotency behavior:

```text
same key + same fingerprint + not dispatched -> converge
same key + different fingerprint -> 409 GATEWAY_IDEMPOTENCY_CONFLICT
same key + original in progress -> 409 GATEWAY_REQUEST_IN_PROGRESS
same key after Provider dispatch when body is not retained -> 409 GATEWAY_RESPONSE_NOT_RETAINED
never re-dispatch merely to replay a response
```

M11 Close blocker is intentionally conservative because M13 settlement does not exist. For the target BillingPeriod, block Close when request state is any of:

```text
DISPATCH_INTENT
UPSTREAM_ACTIVE
TRANSPORT_COMPLETED
CANCELED_AFTER_DISPATCH
TIMED_OUT_AFTER_DISPATCH
FAILED_AFTER_DISPATCH
```

M13 may later narrow this using Usage/Settlement truth. M11 must not allow possibly billed work behind a CLOSED period.

Real-MySQL tests prove: concurrent same identity -> one request/attempt; same key different bytes -> conflict; close wins lock -> no dispatch fence; dispatch wins -> blocker fails Close; DB execution thread is not `reactor-http-*`.

Commit: `feat(gateway): enforce durable dispatch and close safety`.

---

# Task 5 — AIC-097: MiMo non-streaming Chat Completions

Create:

- `gateway/src/main/java/com/aicostops/gateway/provider/ProviderChatAdapter.java`
- `gateway/src/main/java/com/aicostops/gateway/provider/ProviderCallContext.java`
- `gateway/src/main/java/com/aicostops/gateway/provider/ProviderCredentialDecryptor.java`
- `gateway/src/main/java/com/aicostops/gateway/provider/mimo/MimoChatAdapter.java`
- `gateway/src/main/java/com/aicostops/gateway/provider/mimo/MimoWireDtos.java`
- `gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java`
- `gateway/src/main/java/com/aicostops/gateway/web/dto/ChatCompletionRequest.java`
- `gateway/src/main/java/com/aicostops/gateway/web/dto/ChatCompletionResponse.java`
- `gateway/src/test/java/com/aicostops/gateway/provider/mimo/MimoChatAdapterTest.java`
- `gateway/src/test/java/com/aicostops/gateway/web/ChatCompletionControllerTest.java`

Provider port:

```java
public interface ProviderChatAdapter {
    reactor.core.publisher.Mono<ProviderChatCompletion> complete(ProviderCallContext context, ChatCompletionCommand command);
    reactor.core.publisher.Flux<ProviderChatChunk> stream(ProviderCallContext context, ChatCompletionCommand command);
}
```

Place `ProviderChatCompletion`, `ProviderChatChunk` and `ChatCompletionCommand` as focused records/classes in `gateway/src/main/java/com/aicostops/gateway/provider/` and `gateway/src/main/java/com/aicostops/gateway/request/`; do not hide Provider secret in record-generated `toString()`.

MiMo request:

```text
POST {server-owned base_url}/chat/completions
api-key: decrypted Provider secret
Content-Type: application/json
wire model = provider_model.provider_model_name
retry = none
```

Client cannot provide upstream URL/Provider ID/Provider key. Production call validates HTTPS and approved MiMo host.

Public request matches `gateway-openapi.yaml` exactly: logical model, text messages, roles developer/system/user/assistant, optional `max_completion_tokens`, optional `stream`; reject unknown fields, request content encoding and >1 MiB decoded body. Missing `max_completion_tokens` requires a finite governed default which is enforced upstream.

State success:

```text
request DISPATCH_INTENT -> UPSTREAM_ACTIVE -> TRANSPORT_COMPLETED
route DISPATCH_INTENT -> BILLABLE_POSSIBLE -> COMPLETED
```

Post-dispatch timeout/failure leaves `BILLABLE_POSSIBLE` and never retries automatically.

Mock-upstream tests prove mapping/auth header, response normalization, 400/401/403/413, and exactly one Provider request for 429/500/503/reset.

Commit: `feat(gateway): proxy MiMo chat completions`.

---

# Task 6 — AIC-098: SSE, disconnect and timeout semantics

Create:

- `gateway/src/main/java/com/aicostops/gateway/provider/mimo/MimoSseDecoder.java`
- `gateway/src/main/java/com/aicostops/gateway/web/GatewaySseEncoder.java`
- `gateway/src/main/java/com/aicostops/gateway/request/StreamingLifecycleService.java`
- `gateway/src/test/java/com/aicostops/gateway/provider/mimo/MimoStreamingIntegrationTest.java`
- `gateway/src/test/java/com/aicostops/gateway/request/StreamingLifecycleIntegrationTest.java`

Modify `MimoChatAdapter` and `ChatCompletionController`.

Use configured connect/header/idle/hard timeouts; no `retryWhen` after dispatch.

Rules:

- `stream=true` upstream and SSE downstream;
- parse/normalize incrementally, never collect full response;
- `[DONE]` once on normal completion;
- missing usage stays absent;
- M11 writes no `gateway_usage_fact`;
- client cancel after dispatch -> request `CANCELED_AFTER_DISPATCH`, route `BILLABLE_POSSIBLE`;
- Provider timeout -> request `TIMED_OUT_AFTER_DISPATCH`, route `BILLABLE_POSSIBLE`;
- once SSE bytes began, persist failure/cancel and terminate; do not replace the stream with a JSON body.

Tests: multi-chunk success, exact `[DONE]`, cancel, idle timeout, hard timeout, reset after partial chunks, exactly one Provider call, bounded high-volume stream proving no full aggregation.

Commit: `feat(gateway): add bounded MiMo SSE streaming`.

---

# Task 7 — AIC-099: Atomic Redis rate limit and resource bounds

Create:

- `gateway/src/main/resources/redis/gateway-rate-limit.lua`
- `gateway/src/main/java/com/aicostops/gateway/ratelimit/GatewayRateLimiter.java`
- `gateway/src/main/java/com/aicostops/gateway/ratelimit/RedisTokenBucketRateLimiter.java`
- `gateway/src/main/java/com/aicostops/gateway/config/GatewayResourceLimiter.java`
- `gateway/src/test/java/com/aicostops/gateway/ratelimit/RedisTokenBucketRateLimiterIntegrationTest.java`
- `gateway/src/test/java/com/aicostops/gateway/config/GatewayResourceLimiterTest.java`

Redis key: `aicostops:v2:gateway:ratelimit:{credentialId}`. Arguments: capacity, refill_per_second, now_millis, cost=1. Return `[1, remaining, 0]` or `[0, remaining, retry_after_millis]`.

Enabled limiter + Redis failure = `503 GATEWAY_DEPENDENCY_UNAVAILABLE` before Provider I/O; never fail open.

Implement a process-local active-stream semaphore using configured default 128; release on complete/error/cancel.

Tests: burst, refill, concurrent race, Redis-down no Provider call, stream-permit release.

Commit: `feat(gateway): bound M11 rate and stream concurrency`.

---

# Task 8 — AIC-100: Status, API contract, observability and production validation

Create:

- `gateway/src/main/java/com/aicostops/gateway/web/GatewayRequestStatusController.java`
- `gateway/src/main/java/com/aicostops/gateway/web/GatewayErrorCode.java`
- `gateway/src/main/java/com/aicostops/gateway/observability/CorrelationWebFilter.java`
- `gateway/src/main/java/com/aicostops/gateway/observability/GatewayMetrics.java`
- `gateway/src/main/java/com/aicostops/gateway/config/GatewayProductionConfigurationValidator.java`
- `gateway/src/test/java/com/aicostops/gateway/web/GatewayRequestStatusControllerTest.java`
- `gateway/src/test/java/com/aicostops/gateway/web/GatewayOpenApiContractTest.java`
- `gateway/src/test/java/com/aicostops/gateway/observability/GatewayRedactionTest.java`
- `gateway/src/test/java/com/aicostops/gateway/config/GatewayProductionConfigurationValidatorTest.java`

Implement `GET /v1/gateway/requests/{requestId}`. Only owning credential sees status; wrong owner and nonexistent request are both privacy-preserving 404. M11 returns `meteringStatus=null` and `settlementStatus=null`.

Errors and correlation headers match `gateway-openapi.yaml`. Parse that YAML in `GatewayOpenApiContractTest` and lock operation IDs, statuses, Idempotency-Key requirement and success-usage optionality. Reuse the repository's SnakeYAML-style contract-testing approach rather than adding a heavy OpenAPI parser unless proven necessary.

Metrics use only bounded labels such as provider code and small outcome enums. Never use request/trace/credential/org/project/user/provider-request IDs or arbitrary client model strings as labels.

Redaction tests inject sentinel prompt/completion/Gateway-key/Provider-key/idempotency values and prove logs contain none of them.

Production validator rejects malformed/missing Base64 32-byte HMAC/KEK values, dev bootstrap enabled, and invalid resource limits. Provider boundary rejects non-HTTPS/non-approved production MiMo host.

Commit: `feat(gateway): expose safe M11 status and telemetry`.

---

# Task 9 — AIC-101: Docker, CI, Security, local runbook and smoke

Create:

- `gateway/Dockerfile`
- `scripts/smoke-m11-gateway.ps1`
- `docs/02-development/implementation/06-m11-gateway-local-runbook.md`
- `docs/03-acceptance/m11-gateway-edge-evidence.md`

Modify:

- `.github/workflows/ci.yml`
- `.github/workflows/security.yml`
- `.github/codeql/codeql-config.yml`
- `README.md` only for commands/status actually verified.

CI adds independent `gateway-unit`, `gateway-integration`, `gateway-architecture`; Docker job builds Backend, Frontend and Gateway.

Security adds `gateway/src` to CodeQL, Java extractor builds Backend + Gateway, Trivy cache hash includes Gateway pom, filesystem scan excludes only generated `gateway/target`, and image scan covers `ai-costops-gateway:security` for HIGH/CRITICAL vulnerabilities.

Daily dev remains native processes:

```text
Frontend 5173
Backend 8080
Gateway 8081
MySQL 3307 in Docker
Redis 6379 in Docker
MinIO 9000/9001 in Docker
```

Do not add Gateway to `compose.dev.yaml` during M11.

Smoke script never prints secrets. It checks liveness/readiness, one non-streaming request, same-idempotency replay behavior without a second Provider dispatch, one SSE request through `[DONE]`, and status API. If `AICOSTOPS_MIMO_API_KEY` exists, run the same path against real MiMo and record only provider/model/status/state/timestamps.

Commit: `ci(gateway): gate M11 runtime and security`.

---

# Task 10 — AIC-102: Fresh final verification and one final PR

No architecture changes in this task.

Run from `E:\project\AI-CostOps`:

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

Real MiMo smoke is required before the **real Provider path** is marked PASS. If no real key is available, leave only that acceptance item `NOT RUN`; never fabricate PASS. The branch may still be pushed with that external blocker reported.

Final adversarial checklist:

```text
[ ] Provider I/O cannot happen before committed DISPATCH_INTENT
[ ] same idempotency identity cannot create a second billable attempt
[ ] same key + different raw body conflicts
[ ] post-dispatch 429/5xx/timeout/reset never auto-retries
[ ] client disconnect is CANCELED_AFTER_DISPATCH + BILLABLE_POSSIBLE
[ ] missing usage is never zero-filled
[ ] REQUIRED budget credential cannot bypass absent M12 Reservation
[ ] mandatory Redis limiter cannot fail open
[ ] JDBC/MyBatis is off Netty event loop
[ ] possibly billable M11 work blocks BillingPeriod Close
[ ] prompt/completion/secrets absent from logs/metrics/evidence
[ ] Gateway cannot run Flyway
[ ] no M12/M13/Ledger functionality leaked into M11
[ ] existing Backend tests green
[ ] existing Frontend lint/tests/build green
[ ] Gateway unit/integration/architecture tests green
[ ] Gateway OpenAPI contract tests green
[ ] Gateway Docker build green
[ ] CI and Security workflows cover Gateway
```

Update `docs/03-acceptance/m11-gateway-edge-evidence.md` with fresh counts, branch HEAD, exact commands/results, mock smoke, sanitized real-MiMo result and known limitations. No stale counts and no PASS for an unexecuted check.

Commit: `docs(m11): record Gateway Edge MVP acceptance evidence`.

Push:

```powershell
git push -u origin feat/m11-gateway-edge-mvp
```

Open one PR titled `feat(m11): deliver Gateway Edge MVP`. Do not merge it. Report PR URL/number, final branch HEAD, commit list, fresh test counts/results, CI/Security state, sanitized real-MiMo evidence and any explicit limitation. Sol performs independent review; merge requires human authorization.

## Allowed stop conditions

The local agent continues without asking architecture questions already frozen by M10. It may stop only when:

1. frozen M10 documents conflict in a correctness-changing way;
2. the only remaining gate is real MiMo and no real key is available;
3. unrelated local modifications would be overwritten and cannot be safely isolated.

Any ordinary test or CI failure is not a stop condition: diagnose, fix on the same branch, rerun, then continue.