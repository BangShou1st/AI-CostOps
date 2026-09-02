# M11 Gateway Edge MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the complete M11 Gateway Edge MVP in one feature branch: a real Java 21 / Spring WebFlux Gateway with hash-only internal credential authentication, one MiMo Provider adapter, bounded OpenAI-compatible Chat Completions, non-streaming and SSE streaming, durable request/route evidence, the pre-Provider dispatch fence, idempotency, safe runtime limits, CI/security coverage, and a real-provider smoke path.

**Architecture:** Add `gateway/` as the second Java deployable in the monorepo. Backend remains the only Flyway owner and the only writer of Gateway administrative/catalog/secret rows; Gateway reads those rows and writes only `gateway_request` / `gateway_route_attempt` in M11. Blocking MyBatis/JDBC work is isolated from Reactor Netty on a bounded scheduler. Provider I/O is reactive through `WebClient`; once `DISPATCH_INTENT` commits, M11 never performs blind automatic Provider retry/failover.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring WebFlux/Reactor Netty, Spring Security, Plain MyBatis 4.1.0, MySQL 8.4, Reactive Redis/Lua for rate limiting, Micrometer/Prometheus, JUnit 5, Testcontainers, ArchUnit, Docker, GitHub Actions.

**Spec:** `docs/02-development/v2-detailed-design/README.md`, especially AIC-084..AIC-092; machine HTTP contract: `docs/02-development/api/gateway-openapi.yaml`.

## Global Constraints

- Work only on branch `feat/m11-gateway-edge-mvp`, based on `main@9480b84071ae362c0a192190cd072b4beb0ae595`.
- `.zcode` is KEEP / DO NOT TOUCH.
- Do not modify or recreate tag `v1.1.0`.
- Backend is the sole production Flyway runner. Gateway must not contain Flyway or run migrations.
- MySQL remains identity/financial durable truth. Redis is runtime coordination only.
- M11 does **not** implement `budget_reservation`, `gateway_usage_fact`, `gateway_settlement`, Ledger `GATEWAY_SETTLEMENT`, Commitment consumption, multi-Provider routing, or M15 reconciliation.
- M11 must still use the frozen M11 schema wave: `service_identity`, `gateway_credential`, `gateway_credential_model`, `provider_credential`, `provider_catalog`, `model_catalog`, `provider_model`, `pricing_version`, `pricing_rate`, `gateway_request`, `gateway_route_attempt`.
- M11 is Edge MVP, not a loophole around M12 financial controls. Dev/test bootstrap credentials use `OPTIONAL`; `REQUIRED` credentials fail closed in M11 before Provider I/O. Do not claim production budget enforcement until M12.
- Every potentially billable Provider call must commit `gateway_request.state=DISPATCH_INTENT`, `gateway_route_attempt.status=DISPATCH_INTENT`, and `gateway_request.billing_period_id` while holding the OPEN BillingPeriod row lock **before** Provider network I/O.
- After `DISPATCH_INTENT`, no generic 429/5xx/timeout retry and no automatic failover in M11.
- Prompt/completion content, Authorization headers, raw Gateway keys, raw idempotency keys, Provider secrets, secret digests, ciphertext, nonce, KEK and HMAC keys must never appear in normal logs, audit metadata, metrics labels, committed fixtures, screenshots, or acceptance evidence.
- `POST /v1/chat/completions` accepts only the frozen text subset: `model`, `messages`, optional `max_completion_tokens`, optional `stream`; unknown fields are rejected.
- `Idempotency-Key` is mandatory and raw request bytes are part of the fingerprint. Do not canonicalize JSON before hashing.
- Keep Java blocking DB operations off Reactor Netty event-loop threads.
- Use TDD. Every task ends with focused tests + a commit. Do not accumulate the entire milestone into one commit.
- Do not ask the human for design choices already frozen by M10. Stop only for a real external blocker such as unavailable required credentials or an irreconcilable spec contradiction.

---

## M11 Exit Criteria

M11 is complete only when all are true:

```text
AIC-094  Gateway deployable + M11 schema foundation             PASS
AIC-095  Credential/Provider provisioning + crypto boundary     PASS
AIC-096  Auth + idempotency + durable request/dispatch fence    PASS
AIC-097  MiMo non-streaming Chat Completions                    PASS
AIC-098  MiMo SSE streaming / disconnect / timeout semantics    PASS
AIC-099  Redis rate limit + bounded runtime resources           PASS
AIC-100  Status API + safe observability                        PASS
AIC-101  CI / Security / Docker / local runbook / smoke         PASS
AIC-102  Final M11 acceptance                                   PASS
```

The final branch must be one PR to `main`. Do not merge it locally and do not push directly to `main`.

---

# File/Module Map

Create the second deployable:

```text
gateway/
├─ pom.xml
├─ mvnw / mvnw.cmd / .mvn/
├─ Dockerfile
└─ src/
   ├─ main/java/com/aicostops/gateway/
   │  ├─ GatewayApplication.java
   │  ├─ config/
   │  ├─ auth/
   │  ├─ request/
   │  ├─ routing/
   │  ├─ provider/
   │  │  └─ mimo/
   │  ├─ ratelimit/
   │  ├─ persistence/
   │  ├─ observability/
   │  └─ web/
   ├─ main/resources/
   │  ├─ application.yml
   │  ├─ application-local.yml
   │  ├─ application-prod.yml
   │  └─ mapper/
   └─ test/java/com/aicostops/gateway/
```

Backend additions are narrow and Control-Plane-owned:

```text
backend/src/main/resources/db/migration/V18__m11_gateway_edge_foundation.sql
backend/src/main/java/com/aicostops/gatewayadmin/infrastructure/DevGatewayBootstrap.java
backend/src/main/java/com/aicostops/gatewayadmin/security/ProviderCredentialEncryptor.java
backend/src/test/.../GatewayM11SchemaIntegrationTest.java
backend/src/test/.../DevGatewayBootstrapTest.java
```

If `V18` is no longer the next free migration when execution begins, use the next free version without renumbering/changing V1-V17.

---

### Task 1 — AIC-094: Bootstrap the Gateway deployable and architecture boundaries

**Files:**
- Create: `gateway/pom.xml`
- Copy/adapt: `backend/.mvn/**`, `backend/mvnw`, `backend/mvnw.cmd` -> `gateway/`
- Create: `gateway/src/main/java/com/aicostops/gateway/GatewayApplication.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/config/GatewayProperties.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/config/GatewayBlockingIoScheduler.java`
- Create: `gateway/src/main/resources/application.yml`
- Create: `gateway/src/main/resources/application-local.yml`
- Create: `gateway/src/main/resources/application-prod.yml`
- Create: `gateway/src/test/java/com/aicostops/gateway/architecture/GatewayArchitectureTest.java`
- Create: `gateway/src/test/java/com/aicostops/gateway/GatewayApplicationTest.java`

**Dependencies:**

`gateway/pom.xml` must pin the existing project baselines:

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.1.0</version>
  <relativePath />
</parent>
<groupId>com.aicostops</groupId>
<artifactId>gateway</artifactId>
<version>2.0.0-SNAPSHOT</version>
<properties>
  <java.version>21</java.version>
  <mybatis-spring-boot.version>4.1.0</mybatis-spring-boot.version>
  <testcontainers.version>2.0.5</testcontainers.version>
  <archunit.version>1.4.2</archunit.version>
</properties>
```

Use only the dependencies needed for M11:

```text
spring-boot-starter-webflux
spring-boot-starter-validation
spring-boot-starter-security
spring-boot-starter-data-redis-reactive
spring-boot-starter-actuator
micrometer-registry-prometheus
mybatis-spring-boot-starter:4.1.0
mysql-connector-j (runtime)
spring-boot-starter-test (test)
spring-boot-starter-webflux-test (test)
spring-security-test (test)
testcontainers-junit-jupiter (test)
testcontainers-mysql (test)
archunit-junit5:1.4.2 (test)
```

Do **not** add Flyway, R2DBC, RabbitMQ, Kafka, Resilience4j, Kubernetes libraries, or a second JSON stack.

**Interfaces:**

```java
public interface BlockingIoScheduler extends AutoCloseable {
    <T> Mono<T> call(Callable<T> operation);
    Mono<Void> run(CheckedRunnable operation);
}
```

Back it with one bounded Reactor scheduler, not `Schedulers.boundedElastic()` with invisible global coupling. Initial defaults:

```text
DB scheduler threads = 12
DB scheduler queued tasks = 256
JDBC pool max size = 12
request body max = 1,048,576 bytes
response in-memory max = 16 MiB
HTTP request header max = 16 KiB
global active stream cap = 128
```

All values are environment-overridable but startup validation rejects zero/negative/unbounded values.

- [ ] Write architecture tests first: Gateway web/provider packages must not depend on Backend classes; persistence calls are reachable through application/port abstractions; no Flyway dependency/class exists in Gateway.
- [ ] Run `gateway\mvnw.cmd -B test` and verify the new tests fail before the scaffold is complete.
- [ ] Implement the minimal Spring Boot WebFlux application and bounded blocking scheduler.
- [ ] Add `/actuator/health/liveness`, `/actuator/health/readiness` and Prometheus exposure with production-safe defaults.
- [ ] Run `gateway\mvnw.cmd -B test` and verify PASS.
- [ ] Commit: `feat(gateway): bootstrap M11 WebFlux data plane`.

---

### Task 2 — AIC-094: Add the exact M11 Flyway schema wave in Backend

**Files:**
- Create: `backend/src/main/resources/db/migration/V18__m11_gateway_edge_foundation.sql` (or next free version)
- Create: `backend/src/test/java/com/aicostops/gatewayadmin/GatewayM11SchemaIntegrationTest.java`

**Schema:** Implement the exact AIC-092 M11 wave; do not simplify column types or enum checks:

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

Key constraints that tests must assert:

```text
gateway_credential credential_prefix UNIQUE
principal XOR human/service
gateway_credential_model explicit relation
gateway_request public_request_id UNIQUE
gateway_request UNIQUE(org_id, credential_id, idempotency_key_digest)
gateway_request.billing_period_id nullable only before DISPATCH_INTENT
gateway_route_attempt UNIQUE(org_id, request_id, attempt_no)
gateway_route_attempt UNIQUE(org_id, route_decision_id)
route status = PLANNED | DISPATCH_INTENT | SAFE_NO_BILLABLE_EXECUTION | BILLABLE_POSSIBLE | COMPLETED
same-org FK convention from AIC-092
pricing/request money and digest column types exactly match AIC-092
```

Do not create M12/M13 tables and do not modify Ledger checks.

- [ ] Write `GatewayM11SchemaIntegrationTest` against fresh Testcontainers MySQL; first assert the tables do not exist on the pre-change tree.
- [ ] Add migration using the exact logical schema from `09-data-api-migration-testing.md`.
- [ ] Assert V1 migrations still apply from empty DB through the new migration and V1 tables remain queryable.
- [ ] Assert the key unique/check/same-org constraints with failing inserts, not only INFORMATION_SCHEMA inspection.
- [ ] Run `backend\mvnw.cmd -B "-Dgroups=integration" verify`.
- [ ] Commit: `feat(db): add M11 gateway edge foundation schema`.

---

### Task 3 — AIC-095: Add Backend-owned dev/test provisioning and Provider secret encryption

**Files:**
- Create: `backend/src/main/java/com/aicostops/gatewayadmin/security/ProviderCredentialEncryptor.java`
- Create: `backend/src/main/java/com/aicostops/gatewayadmin/infrastructure/DevGatewayBootstrap.java`
- Create focused MyBatis mapper/repository files under `backend/.../gatewayadmin/`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/resources/application-prod.yml`
- Modify: `.env.example` with synthetic/non-secret placeholders only
- Test: `backend/src/test/java/com/aicostops/gatewayadmin/security/ProviderCredentialEncryptorTest.java`
- Test: `backend/src/test/java/com/aicostops/gatewayadmin/infrastructure/DevGatewayBootstrapIntegrationTest.java`
- Modify production configuration validation so any Gateway dev bootstrap switch is rejected in `prod`.

**Crypto contract:**

```text
algorithm = AES/GCM/NoPadding
AES key size = 256 bits
nonce = 12 random bytes per encryption
GCM authentication tag = 128 bits
encryption_key_version = explicit positive integer
ciphertext column stores ciphertext + GCM tag returned by JCE
```

Use deterministic AAD in both Backend encryption and Gateway decryption:

```text
UTF-8("aicostops:v2:provider-credential:v1\0"
      + orgId + "\0"
      + providerAccountId + "\0"
      + credentialType + "\0"
      + encryptionKeyVersion)
```

The KEK is supplied as Base64-encoded 32 bytes outside MySQL. Never fall back to a built-in production key.

**Dev bootstrap:** Keep it disabled by default and production-invalid. When explicitly enabled, Backend may create/upsert a local SERVICE principal, one Gateway credential, MiMo catalog/model mapping, synthetic M11 pricing version/rates, and one encrypted Provider credential for the existing local organization/project/provider account context.

M11 first Provider contract:

```text
provider_code = MIMO
provider_catalog.base_url = https://api.xiaomimimo.com/v1
provider_model_name = mimo-v2.5-pro
upstream auth = api-key
logical model key = default-chat (dev bootstrap only)
```

Use a caller-supplied valid raw Gateway key in the exact AIC-092 shape; persist only its HMAC-SHA-256 digest. Dev/test may use a documented synthetic key in CI. A real MiMo key is supplied only through an environment variable and is encrypted before DB persistence.

- [ ] Write AES-GCM round-trip, tamper-detection, wrong-AAD and wrong-key tests first.
- [ ] Write bootstrap integration test proving raw Gateway/Provider secrets cannot be selected back from any plaintext column.
- [ ] Implement dev bootstrap under a `dev`-only condition; do not create a production provisioning HTTP endpoint in M11.
- [ ] Ensure logs expose only safe IDs/prefix/provider code; no secret values.
- [ ] Run Backend unit + integration tests.
- [ ] Commit: `feat(gateway-admin): provision M11 dev credentials safely`.

---

### Task 4 — AIC-096: Implement Gateway persistence, authentication, idempotency and the durable dispatch fence

**Files:**
- Create Gateway persistence records/mappers for credential/catalog/request/route/period reads and request/route writes.
- Create: `gateway/.../auth/GatewayApiKeyParser.java`
- Create: `gateway/.../auth/GatewayAuthenticationManager.java`
- Create: `gateway/.../auth/GatewayPrincipal.java`
- Create: `gateway/.../request/RequestIdentityService.java`
- Create: `gateway/.../request/GatewayRequestService.java`
- Create: `gateway/.../request/DispatchFenceService.java`
- Create: `gateway/.../web/GatewayErrorHandler.java`
- Tests: unit + MySQL integration concurrency tests.

**Gateway key parser:**

```text
^aic_[0-9a-hjkmnp-tv-z]{12}_[A-Za-z0-9_-]{43}$
```

Lookup by the 12-character public prefix, calculate `HMAC-SHA-256(secret, configured digest key version)`, and compare using `MessageDigest.isEqual` or equivalent constant-time comparison.

**Request HMAC:**

```text
idempotency_key_digest = HMAC(keyVersion, "idem\0" + rawIdempotencyKey)
request_fingerprint = HMAC(keyVersion,
  "request\0" + method + "\0" + canonicalPath + "\0" + rawBodyBytes)
```

Raw `Idempotency-Key` is 1..128 visible ASCII and is never logged/persisted.

**Durable state transaction before Provider I/O:**

1. Authenticate credential and explicit model relation.
2. Validate principal/project/financial scope/provider account/provider credential/model/pricing eligibility from MySQL.
3. M11 edge-only rule: `budget_enforcement_mode=REQUIRED` -> fail closed before dispatch; dev/test M11 credentials are `OPTIONAL`.
4. Apply basic Redis rate precheck (Task 7 may initially provide a test fake; final code must use the real limiter).
5. Insert/replay `gateway_request` in `VALIDATED` using MySQL uniqueness.
6. Create `gateway_route_attempt` attempt 1 as `PLANNED` with frozen Provider Account / Provider Model / Pricing Version.
7. In one short MySQL transaction: lock the applicable OPEN BillingPeriod row `FOR UPDATE`; reject if not OPEN; set `gateway_request.billing_period_id`; transition request + route attempt to `DISPATCH_INTENT`; set timestamp; commit.
8. **Only after commit** may Provider WebClient I/O begin.

Idempotency convergence:

```text
same key + same fingerprint + never dispatched -> converge on same request
same key + different fingerprint -> 409 GATEWAY_IDEMPOTENCY_CONFLICT
same key + original in progress -> 409 GATEWAY_REQUEST_IN_PROGRESS
same key after dispatch/terminal when body is not retained -> 409 GATEWAY_RESPONSE_NOT_RETAINED
never issue a second Provider call merely to replay a response
```

- [ ] Write authentication failure/revocation/expiry/model-deny tests first.
- [ ] Write concurrent same-idempotency integration test against real MySQL; prove one request and at most one attempt.
- [ ] Write BillingPeriod close-vs-dispatch integration test; prove CLOSED wins -> no dispatch intent, dispatch-lock wins -> Close sees unresolved Gateway work later, never Provider I/O before the DB commit.
- [ ] Add an event-loop guard test: repository/MyBatis execution thread name must be the dedicated DB scheduler, not `reactor-http-*`.
- [ ] Implement minimal code to pass.
- [ ] Commit: `feat(gateway): enforce durable request and dispatch identity`.

---

### Task 5 — AIC-097: Implement the MiMo Provider adapter and non-streaming Chat Completions

**Files:**
- Create: `gateway/.../provider/ProviderChatAdapter.java`
- Create: `gateway/.../provider/ProviderCredentialDecryptor.java`
- Create: `gateway/.../provider/mimo/MimoChatAdapter.java`
- Create: `gateway/.../provider/mimo/MimoWireDtos.java`
- Create: `gateway/.../web/ChatCompletionController.java`
- Create frozen public DTOs matching `gateway-openapi.yaml` exactly.
- Tests: adapter unit tests with a local mock HTTP upstream plus WebTestClient API tests.

**Provider port:**

```java
public interface ProviderChatAdapter {
    Mono<ProviderChatCompletion> complete(ProviderCallContext context,
                                          ChatCompletionCommand command);
    Flux<ProviderChatChunk> stream(ProviderCallContext context,
                                   ChatCompletionCommand command);
}
```

`ProviderCallContext` contains safe resolved IDs and decrypted secret only inside the narrow provider call boundary. It must not have a useful `toString()` containing the secret.

**HTTP:**

```text
POST {server-owned baseUrl}/chat/completions
header: api-key: <decrypted MiMo secret>
Content-Type: application/json
model sent upstream: provider_model.provider_model_name
retry operator: NONE
```

Validate the Provider base URL against server-owned catalog configuration; clients can never supply an arbitrary upstream host. Production requires HTTPS and an approved MiMo host.

**M11 public request subset:**

```text
model: logical model key
messages[].role = developer | system | user | assistant
messages[].content = string only
max_completion_tokens optional only when governed finite default resolves
stream = false here
additional JSON fields = reject 400
Content-Encoding = reject
request body > 1 MiB = 413
```

Normalize the upstream response into the frozen AI-CostOps/OpenAI-compatible response. Return the logical model key to the client rather than exposing the Provider routing model as client control.

State on success:

```text
route attempt: DISPATCH_INTENT -> BILLABLE_POSSIBLE -> COMPLETED
request: DISPATCH_INTENT -> UPSTREAM_ACTIVE -> TRANSPORT_COMPLETED
provider_request_id captured when available
```

State on post-dispatch failure:

```text
request = FAILED_AFTER_DISPATCH or TIMED_OUT_AFTER_DISPATCH
route attempt = BILLABLE_POSSIBLE
no automatic Provider retry
```

- [ ] Write mock-upstream request mapping test including `api-key`, provider model and `max_completion_tokens`.
- [ ] Write `unknown field -> 400`, `missing Idempotency-Key -> 400`, invalid auth -> 401, denied model -> 403, oversized body -> 413.
- [ ] Write Provider 429/500/503/connection reset tests and assert exactly one upstream request once dispatch intent exists.
- [ ] Implement non-streaming path.
- [ ] Run Gateway unit/integration tests.
- [ ] Commit: `feat(gateway): proxy MiMo chat completions`.

---

### Task 6 — AIC-098: Implement SSE streaming, disconnect and timeout semantics

**Files:**
- Extend `MimoChatAdapter.stream`.
- Create: `gateway/.../provider/mimo/MimoSseDecoder.java`
- Create: `gateway/.../web/GatewaySseEncoder.java`
- Create: `gateway/.../request/StreamingLifecycleService.java`
- Tests: streaming adapter/API tests with controlled local upstream.

**Timeout defaults (environment-overridable, bounded):**

```text
connect timeout = 5 s
response-header timeout = 60 s
stream idle timeout = 60 s
hard request timeout = 600 s
```

Use Reactor Netty/WebClient timeout mechanisms; do not implement generic `retryWhen` after dispatch.

SSE rules:

```text
stream=true is forwarded to MiMo
only data events required for M11 are emitted
JSON chunks are parsed/normalized; Provider secret/headers never reflected
logical model key is returned to client
[DONE] is emitted exactly once after a normal upstream completion
bounded buffering only; do not collect the full stream
```

Usage behavior:

```text
if exact Provider usage is present -> it may be forwarded in the compatible response/chunk shape supported by the contract
if usage is absent -> do not invent zero usage
M11 does not create gateway_usage_fact; durable realtime metering begins in M13
```

Client disconnect after dispatch:

```text
gateway_request = CANCELED_AFTER_DISPATCH
gateway_route_attempt = BILLABLE_POSSIBLE
no claim that cost is zero
```

Provider timeout after dispatch:

```text
gateway_request = TIMED_OUT_AFTER_DISPATCH
gateway_route_attempt = BILLABLE_POSSIBLE
```

If streaming headers/body already began, persist the failure state and terminate the stream; do not pretend an OpenAI JSON error body can replace already-sent SSE bytes.

- [ ] Write multi-chunk + `[DONE]` happy-path test.
- [ ] Write client cancel test and assert durable `CANCELED_AFTER_DISPATCH`.
- [ ] Write idle timeout and hard timeout tests; assert one Provider request and `BILLABLE_POSSIBLE`.
- [ ] Write a high-volume controlled stream test proving no full-response aggregation and bounded memory behavior.
- [ ] Implement streaming path.
- [ ] Commit: `feat(gateway): add bounded MiMo SSE streaming`.

---

### Task 7 — AIC-099: Implement atomic Redis rate limiting and runtime resource bounds

**Files:**
- Create: `gateway/src/main/resources/redis/gateway-rate-limit.lua`
- Create: `gateway/.../ratelimit/GatewayRateLimiter.java`
- Create: `gateway/.../ratelimit/RedisTokenBucketRateLimiter.java`
- Create: `gateway/.../config/GatewayResourceLimiter.java`
- Tests: Redis integration tests plus concurrency tests.

Redis key:

```text
aicostops:v2:gateway:ratelimit:{credentialId}
```

Do not place raw credential keys or prefixes in the Redis key.

Lua input/output contract:

```text
inputs: capacity, refill_per_second, now_millis, cost=1
output allowed: [1, remaining_tokens, 0]
output rejected: [0, remaining_tokens, retry_after_millis]
```

Initial local defaults:

```text
capacity = 60
refill_per_second = 1
```

If rate limiting is enabled and Redis cannot evaluate it, fail closed with `503 GATEWAY_DEPENDENCY_UNAVAILABLE`; do not silently fail open.

Also enforce a process-local global active-stream semaphore of 128 permits in M11 so a single process cannot accept unbounded SSE work. Always release the permit on complete/error/cancel.

- [ ] Write atomic burst/refill tests on real Redis.
- [ ] Write concurrent requests test proving capacity cannot be exceeded by a race.
- [ ] Write Redis-down test -> no Provider call.
- [ ] Write active stream permit complete/error/cancel release tests.
- [ ] Implement and wire before durable dispatch.
- [ ] Commit: `feat(gateway): bound M11 rate and stream concurrency`.

---

### Task 8 — AIC-100: Implement request status API, safe errors, logging, metrics and production validation

**Files:**
- Create/complete: `gateway/.../web/GatewayRequestStatusController.java`
- Create: `gateway/.../web/GatewayErrorCode.java`
- Create: `gateway/.../observability/GatewayMetrics.java`
- Create: `gateway/.../observability/CorrelationWebFilter.java`
- Create: `gateway/.../config/GatewayProductionConfigurationValidator.java`
- Tests: status ownership/privacy, error envelope, redaction, metric cardinality/config validation.

Implement exactly:

```text
GET /v1/gateway/requests/{requestId}
```

Only the authenticated Gateway credential that owns the request may see status. Wrong credential and nonexistent request both return privacy-preserving 404.

M11 status values:

```text
requestState = frozen gateway_request state
meteringStatus = null (M13 owns durable metering)
settlementStatus = null (M13 owns Settlement)
createdAt / updatedAt = durable timestamps
```

Errors use the frozen OpenAI-compatible envelope and codes from `gateway-openapi.yaml`. Correlation headers:

```text
X-AI-CostOps-Request-Id when a durable request exists
X-Trace-Id always when possible
```

Bounded metrics may include:

```text
gateway_request_total{outcome}
gateway_upstream_total{provider_code,outcome}
gateway_stream_active
rate_limit_total{outcome}
gateway_dependency_error_total{dependency}
```

Never label metrics with request id, trace id, credential id/prefix, org id, project id, user id, Provider request id or raw model text.

Production validator must reject at least:

```text
missing/weak Gateway credential HMAC secret
missing/invalid request-HMAC secret
missing/invalid 32-byte Provider KEK
Gateway dev bootstrap enabled
non-positive/unbounded pool/queue/stream/body/header limits
non-HTTPS production upstream catalog use at call time
```

- [ ] Write API contract tests first, including all operation IDs/status codes/required headers from `gateway-openapi.yaml`.
- [ ] Write redaction tests that deliberately put sentinel prompt/key/idempotency/provider-secret strings into inputs and assert they never occur in captured logs.
- [ ] Implement status/observability/config validation.
- [ ] Commit: `feat(gateway): expose safe M11 status and telemetry`.

---

### Task 9 — AIC-101: Add Docker, CI, Security coverage and local/real-MiMo runbook

**Files:**
- Create: `gateway/Dockerfile`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/security.yml`
- Modify: `.github/codeql/codeql-config.yml`
- Create: `scripts/smoke-m11-gateway.ps1`
- Create: `docs/02-development/implementation/06-m11-gateway-local-runbook.md`
- Create: `docs/03-acceptance/m11-gateway-edge-evidence.md`

CI must add independent Gateway gates rather than hiding it inside Backend:

```text
gateway-unit         -> gateway ./mvnw -B -DexcludedGroups=architecture,integration test
gateway-integration  -> gateway ./mvnw -B -Dgroups=integration verify
gateway-architecture -> gateway ./mvnw -B -Dgroups=architecture test
docker-build         -> build backend + frontend + gateway images
```

Security changes:

```text
CodeQL paths include gateway/src
Java CodeQL real build builds backend and gateway
Trivy cache hash includes gateway/pom.xml
Trivy filesystem skip only gateway/target, not gateway source/pom
build + HIGH/CRITICAL scan ai-costops-gateway:security
```

Do not add Gateway to `compose.dev.yaml`; daily dev remains native Backend/Frontend/Gateway with Docker only for MySQL/Redis/MinIO. A dedicated full Compose integration can wait until a later production milestone unless needed by an existing authoritative CI test.

Local ports:

```text
Backend  = 8080
Gateway  = 8081
Frontend = 5173
MySQL    = 3307
Redis    = 6379
MinIO    = 9000/9001
```

`scripts/smoke-m11-gateway.ps1` must:

1. refuse to print Gateway/Provider keys;
2. call liveness/readiness;
3. perform one non-streaming request with `Idempotency-Key`;
4. repeat the same idempotency key and prove no blind redispatch (expect the frozen replay/status behavior, not a second Provider call);
5. perform one SSE request and observe `[DONE]`;
6. call request status;
7. if a real MiMo key is provided through environment, run the same path against real MiMo and write only sanitized evidence.

The real-provider evidence records Provider code/model, HTTP outcome, request/route states and timestamps, but never prompt/completion/key/header/ciphertext.

- [ ] Write/adjust CI first and run local equivalents.
- [ ] Build `gateway` Docker image locally.
- [ ] Run full Backend + Gateway + Frontend existing test suites.
- [ ] Run security-relevant local scans if Docker is available.
- [ ] Run the mock-provider smoke path.
- [ ] Run real MiMo smoke when the environment contains the real key. If unavailable, mark only that evidence line `NOT RUN`; do not falsely claim M11 final provider acceptance.
- [ ] Commit: `ci(gateway): gate M11 runtime and security`.

---

### Task 10 — AIC-102: Final M11 verification and handoff

Do not change architecture in this task. It is verification only.

**Required local commands (Windows PowerShell):**

```powershell
Set-Location "E:\project\AI-CostOps"

git status --short
git log --oneline --decorate -15

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
```

Then run the M11 smoke script against the local runtime. Real MiMo acceptance is required before declaring the Provider path `PASS`; never paste the key into the report.

**Final adversarial checks:**

```text
[ ] no Provider call occurs before committed DISPATCH_INTENT
[ ] same Idempotency-Key cannot create a second billable attempt
[ ] different body with same Idempotency-Key returns conflict
[ ] Provider 429/5xx/timeout after dispatch does not auto-retry
[ ] client disconnect after dispatch is CANCELED_AFTER_DISPATCH / BILLABLE_POSSIBLE
[ ] missing usage is never fabricated as zero
[ ] REQUIRED budget credential cannot bypass missing M12 enforcement
[ ] Redis mandatory limiter failure cannot fail open
[ ] Gateway DB work does not execute on Netty event loop
[ ] prompt/completion and all secret classes absent from logs/metrics/evidence
[ ] Gateway cannot run Flyway
[ ] no M12/M13/Ledger tables or behavior accidentally implemented
[ ] existing V1 Backend/Frontend tests remain green
[ ] gateway-openapi contract tests pass
[ ] Docker build passes
[ ] CI and Security workflows cover Gateway
```

Update `docs/03-acceptance/m11-gateway-edge-evidence.md` with exact fresh counts/results and the branch HEAD SHA. Do not write `PASS` for a test that was not freshly executed.

Commit final evidence only after all executed checks are green:

```text
docs(m11): record Gateway Edge MVP acceptance evidence
```

Push the branch and open one PR:

```text
feat(m11): deliver Gateway Edge MVP
```

PR must remain unmerged until independent review confirms M10 contract compliance, diff scope, latest-head CI/Security, real MiMo evidence, and explicit human merge authorization.

---

## Local AI One-Shot Execution Rule

The local agent should execute Tasks 1 -> 10 continuously on `feat/m11-gateway-edge-mvp`. “One-shot” means one uninterrupted milestone execution and one final PR; it does **not** mean skipping intermediate tests or collapsing commits.

If a test fails, fix it before advancing. If the local repository contains unrelated user changes, preserve them and do not reset/delete them. Never use `git reset --hard`, global Docker prune, or `docker compose down -v` unless explicitly authorized.

The only allowed stop conditions are:

```text
1. frozen M10 documents contradict each other in a way that changes correctness;
2. the real MiMo smoke is the only remaining gate and no real key is available;
3. an existing unrelated local modification would be overwritten and cannot be safely isolated.
```

Everything else should be resolved by reading the frozen M10 specs, current source patterns and tests, then continuing.