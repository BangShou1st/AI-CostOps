# M13-A Gateway Metering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist trustworthy Provider usage as immutable Gateway Usage Facts and typed dimensions, with durable streaming terminal ordering and no silent zero-cost fallback.

**Architecture:** Backend remains the sole Flyway owner and adds V20. Gateway owns provider parsing, usage classification, append-only persistence and request/route terminal convergence. Provider I/O stays outside DB transactions; all MyBatis/JDBC stays on `BlockingIoScheduler`. M13-A ends before Settlement/Ledger/Budget Actual/reservation FINALIZED.

**Tech Stack:** Java 21, Spring Boot, Spring WebFlux, MyBatis, MySQL 8, Flyway, Reactor, JUnit 5, AssertJ, Testcontainers, Maven Wrapper 3.9.11.

**Spec:** `docs/superpowers/specs/2026-09-04-m13-metering-settlement-design.md`

## Global Constraints

- Base implementation branch on `main@7cd80cdf55d3aec279971f72dd423ba6f68c5272` unless `main` advanced only through explicitly approved repository-maintenance commits; record the actual base SHA.
- Issue: `#137 feat(m13a): persist immutable gateway usage facts`.
- Add only `V20__m13_gateway_usage_fact.sql`; never edit V1-V19.
- Backend remains the sole production Flyway runner; Gateway never runs Flyway.
- No `gateway_settlement`, Ledger schema/source/actor change, Budget Actual mutation, Commitment consumption or reservation FINALIZED in M13-A.
- Missing/partial Provider usage is `INCOMPLETE/UNKNOWN`, never zero.
- FINAL requires every dimension priced by the frozen Route Attempt Pricing Version to have trustworthy normalized quantity.
- Never re-resolve latest/current Pricing Version after route creation.
- No Provider I/O in a DB transaction; no automatic post-dispatch retry.
- Streaming client `[DONE]` is emitted only after durable local metering+lifecycle commit succeeds.
- All MyBatis/JDBC is off Reactor Netty event-loop threads.
- No prompt/completion/reasoning content, raw Gateway key, Provider key, Authorization header or arbitrary upstream body may be persisted/logged.
- Use TDD: failing test first, verify RED, minimal implementation, verify GREEN, then refactor.

---

### Task 1: V20 usage schema and cleanup ordering

**Files:**
- Create: `backend/src/main/resources/db/migration/V20__m13_gateway_usage_fact.sql`
- Create: `backend/src/test/java/com/aicostops/gatewayadmin/GatewayM13UsageSchemaIntegrationTest.java`
- Modify: `gateway/src/test/java/com/aicostops/gateway/testsupport/GatewayTestFixture.java`

**Interfaces:**
- Produces tables `gateway_usage_fact`, `gateway_usage_dimension` and nullable `gateway_request.current_usage_fact_id`.
- Same-org FKs and generated FINAL uniqueness are authoritative for later tasks.

- [ ] **Step 1: Write RED schema tests** proving: two tables exist; `current_usage_fact_id` exists; `(id,org_id)` unique keys; same-org FKs; `UNIQUE(org_id,request_id,sequence)`; generated `final_slot`; `UNIQUE(org_id,request_id,final_slot)`; status/provenance/dimension/currency/quantity checks; cross-org insert fails; second FINAL for one request fails.

- [ ] **Step 2: Run only the new schema test before V20 exists.**

```powershell
Set-Location "E:\project\AI-CostOps\backend"
.\mvnw.cmd -Dtest=GatewayM13UsageSchemaIntegrationTest -Dgroups=integration test
```

Expected: FAIL because V20 tables/column do not exist.

- [ ] **Step 3: Implement V20 exactly from the spec.** Important FK order: create usage tables first, then add `gateway_request.current_usage_fact_id` FK; the circular request/fact relation is valid only after both tables exist. `gateway_usage_dimension` uses `DECIMAL(30,8)` and no floating point columns.

- [ ] **Step 4: Update Gateway test cleanup in FK-safe order.** Before deleting usage facts, set `gateway_request.current_usage_fact_id=NULL`; then delete dimensions, facts, reservations, requests/attempts in existing safe order. Do not change production cleaner code.

- [ ] **Step 5: Re-run schema test and nearby M11/M12 schema tests.**

```powershell
.\mvnw.cmd -Dtest=GatewayM13UsageSchemaIntegrationTest,GatewayM12ReservationSchemaIntegrationTest,GatewayM11SchemaIntegrationTest -Dgroups=integration test
```

Expected: all PASS.

- [ ] **Step 6: Commit.**

```powershell
git add backend/src/main/resources/db/migration/V20__m13_gateway_usage_fact.sql backend/src/test/java/com/aicostops/gatewayadmin/GatewayM13UsageSchemaIntegrationTest.java gateway/src/test/java/com/aicostops/gateway/testsupport/GatewayTestFixture.java
git commit -m "feat(m13): add gateway usage schema"
```

---

### Task 2: Provider-neutral streaming metering event model

**Files:**
- Create: `gateway/src/main/java/com/aicostops/gateway/provider/ProviderChatStreamEvent.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/provider/ProviderChatAdapter.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/provider/mimo/MimoWireDtos.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/provider/mimo/MimoChatAdapter.java`
- Modify/Test: `gateway/src/test/java/com/aicostops/gateway/provider/mimo/MimoChatAdapterTest.java`

**Interfaces:**
- `ProviderChatStreamEvent` is a sealed provider-neutral contract with `Delta`, `Metering`, `Done` variants.
- `Metering` carries bounded Provider usage only, not content.
- `ProviderChatAdapter.stream(...)` returns `Flux<ProviderChatStreamEvent>`.

- [ ] **Step 1: Add RED adapter tests** for a MiMo usage-only SSE frame with `choices:[]` and `usage.prompt_tokens/completion_tokens/total_tokens`; assert it becomes `Metering`, not `Delta`. Add RED tests that `[DONE]` becomes `Done`, normal content becomes `Delta`, malformed usage does not fabricate zeros.

- [ ] **Step 2: Run the adapter test and capture RED.**

```powershell
Set-Location "E:\project\AI-CostOps\gateway"
.\mvnw.cmd -Dtest=MimoChatAdapterTest test
```

- [ ] **Step 3: Implement the sealed event model.** Required shape:

```java
public sealed interface ProviderChatStreamEvent {
    record Delta(String upstreamId, long created, String providerModel,
                 String deltaContent) implements ProviderChatStreamEvent {}
    record Metering(String upstreamId, long created, String providerModel,
                    Integer promptTokens, Integer completionTokens,
                    Integer totalTokens) implements ProviderChatStreamEvent {}
    record Done() implements ProviderChatStreamEvent {}
}
```

Do not persist or accumulate deltas here.

- [ ] **Step 4: Extend `WireChunk` with nullable `WireUsage usage` and map usage-only frames before attempting client-visible delta mapping.** Do not derive missing token values from `total_tokens`.

- [ ] **Step 5: Re-run adapter tests.** Expected PASS.

- [ ] **Step 6: Commit.**

```powershell
git add gateway/src/main/java/com/aicostops/gateway/provider gateway/src/main/java/com/aicostops/gateway/provider/mimo gateway/src/test/java/com/aicostops/gateway/provider/mimo/MimoChatAdapterTest.java
git commit -m "feat(gateway): normalize streaming metering events"
```

---

### Task 3: Usage classification against frozen pricing dimensions

**Files:**
- Create: `gateway/src/main/java/com/aicostops/gateway/metering/GatewayUsageStatus.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/metering/GatewayUsageDimension.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/metering/GatewayUsageObservation.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/metering/GatewayUsageClassifier.java`
- Create: `gateway/src/test/java/com/aicostops/gateway/metering/GatewayUsageClassifierTest.java`

**Interfaces:**
- `GatewayUsageClassifier.classify(requiredPricingDimensions, observation)` returns status plus immutable normalized dimensions.
- Supported dimension codes are exactly `INPUT_TOKEN`, `OUTPUT_TOKEN`, `CACHED_INPUT_TOKEN`, `REQUEST`.

- [ ] **Step 1: Write RED unit tests** covering: prompt+completion with INPUT+OUTPUT => FINAL; missing output => INCOMPLETE; no trustworthy quantity => UNKNOWN; CACHED_INPUT_TOKEN required but absent => INCOMPLETE; unsupported pricing dimension => fail closed/non-FINAL; REQUEST fee => deterministic quantity 1 only for a proven dispatched request; negative Provider quantity => reject as malformed/non-FINAL; zero is accepted only when Provider explicitly reports zero.

- [ ] **Step 2: Run classifier test and verify RED.**

```powershell
.\mvnw.cmd -Dtest=GatewayUsageClassifierTest test
```

- [ ] **Step 3: Implement with `BigDecimal`/integer-derived values only.** No `double`/`float`; no total-token subtraction; no cached-token inference.

- [ ] **Step 4: Re-run classifier test.** Expected PASS.

- [ ] **Step 5: Commit.**

```powershell
git add gateway/src/main/java/com/aicostops/gateway/metering gateway/src/test/java/com/aicostops/gateway/metering/GatewayUsageClassifierTest.java
git commit -m "feat(gateway): classify frozen-pricing usage"
```

---

### Task 4: Append-only usage persistence and local transaction

**Files:**
- Create: `gateway/src/main/java/com/aicostops/gateway/persistence/GatewayUsageMapper.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/metering/GatewayUsageFinalizationService.java`
- Create: `gateway/src/test/java/com/aicostops/gateway/metering/GatewayUsageFinalizationIntegrationTest.java`
- Modify only if required for existing lifecycle SQL reuse: `gateway/src/main/java/com/aicostops/gateway/persistence/GatewayRequestMapper.java`

**Interfaces:**
- Public reactive entry points schedule blocking work through `BlockingIoScheduler`.
- Core transaction uses `TransactionTemplate`, not self-invoked `@Transactional`.
- Successful terminal operation atomically inserts fact + dimensions, updates current pointer and request/route terminal state.

- [ ] **Step 1: Write RED integration tests** for: sequence 1 insert; current pointer set; immutable dimensions; INCOMPLETE then FINAL appends sequence 2 with supersedes link; second FINAL converges/fails safely without duplicate; concurrent same terminal observation produces one authoritative current fact; injected failure before request terminal update rolls back fact/dimensions/pointer; frozen `pricing_version_id`/currency/route attempt are copied from authorized dispatch lineage rather than looked up as current.

- [ ] **Step 2: Run focused integration test and verify RED.**

```powershell
.\mvnw.cmd -Dtest=GatewayUsageFinalizationIntegrationTest -Dgroups=integration test
```

- [ ] **Step 3: Implement mapper methods.** Reads/writes are org-qualified. Lock only the request/route rows needed to serialize one request's append sequence. Use DB uniqueness to converge duplicate publication. No Settlement/Ledger/Budget Actual SQL exists in this mapper.

- [ ] **Step 4: Implement `GatewayUsageFinalizationService` with a synchronous transaction core called only from `blockingIo.call/run`.** Do not call `.block()` from Reactor. Re-read the frozen route lineage inside the transaction before publication.

- [ ] **Step 5: Re-run focused integration test.** Expected PASS.

- [ ] **Step 6: Commit.**

```powershell
git add gateway/src/main/java/com/aicostops/gateway/persistence/GatewayUsageMapper.java gateway/src/main/java/com/aicostops/gateway/metering/GatewayUsageFinalizationService.java gateway/src/test/java/com/aicostops/gateway/metering/GatewayUsageFinalizationIntegrationTest.java gateway/src/main/java/com/aicostops/gateway/persistence/GatewayRequestMapper.java
git commit -m "feat(gateway): persist immutable usage facts"
```

---

### Task 5: Non-streaming success must persist usage before HTTP success

**Files:**
- Modify: `gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java`
- Modify/Test: `gateway/src/test/java/com/aicostops/gateway/web/ChatCompletionControllerTest.java`
- Add integration test if existing controller integration class is the better repository pattern.

**Interfaces:**
- `ProviderChatCompletion.usage()` is normalized through `GatewayUsageClassifier`.
- HTTP 2xx is returned only after local usage+lifecycle transaction commits.

- [ ] **Step 1: Add RED tests** proving Provider 2xx with usage persists FINAL before response; missing usage persists non-FINAL and still never writes zero dimensions; DB finalization failure prevents HTTP success; no second Provider call occurs after DB failure.

- [ ] **Step 2: Run focused controller tests and verify RED.**

```powershell
.\mvnw.cmd -Dtest=ChatCompletionControllerTest test
```

- [ ] **Step 3: Replace direct `lifecycleService.completeSuccess(...)` success path with metering finalization.** The Controller orchestrates; it does not calculate money.

- [ ] **Step 4: Re-run tests.** Expected PASS.

- [ ] **Step 5: Commit.**

```powershell
git add gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java gateway/src/test/java/com/aicostops/gateway/web/ChatCompletionControllerTest.java
git commit -m "feat(gateway): persist nonstream usage before success"
```

---

### Task 6: Streaming terminal ordering and metering persistence

**Files:**
- Modify: `gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/web/GatewaySseEncoder.java` only if needed to accept `Delta` explicitly
- Modify/Test: `gateway/src/test/java/com/aicostops/gateway/web/ChatCompletionControllerTest.java`
- Add/Modify integration streaming test under `gateway/src/test/java/com/aicostops/gateway/` following existing naming.

**Interfaces:**
- DELTA is forwarded immediately.
- METERING is retained only as bounded usage state.
- Upstream DONE triggers durable local finalization; downstream `[DONE]` is emitted only after commit.

- [ ] **Step 1: Write RED tests** proving event order: content delta visible, usage-only event not visible as content, upstream DONE observed, DB commit invoked, downstream DONE emitted afterward. Add failure test where DB commit throws: no downstream DONE and Provider call count remains one.

- [ ] **Step 2: Add RED tests for DONE without sufficient usage => INCOMPLETE/UNKNOWN and never fabricated zero.**

- [ ] **Step 3: Run focused streaming tests and verify RED.**

```powershell
.\mvnw.cmd -Dtest=ChatCompletionControllerTest test
```

- [ ] **Step 4: Implement bounded per-stream metering accumulator containing only token counts/timestamps/ids, never completion text.** Content continues streaming incrementally.

- [ ] **Step 5: Re-run focused tests.** Expected PASS.

- [ ] **Step 6: Commit.**

```powershell
git add gateway/src/main/java/com/aicostops/gateway/web gateway/src/test/java/com/aicostops/gateway/web
git commit -m "feat(gateway): durably finalize streaming usage"
```

---

### Task 7: Post-dispatch uncertainty and reservation PENDING_HOLD

**Files:**
- Modify: `gateway/src/main/java/com/aicostops/gateway/metering/GatewayUsageFinalizationService.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/persistence/BudgetReservationMapper.java` only to reuse/add the narrow ACTIVE -> PENDING_HOLD transition needed by terminal uncertainty
- Modify: `gateway/src/main/java/com/aicostops/gateway/request/StreamingLifecycleService.java` / `GatewayRequestLifecycleService.java` only if their responsibilities are folded into the transactional metering seam
- Add tests: `gateway/src/test/java/com/aicostops/gateway/metering/GatewayUsageFailureIntegrationTest.java`

**Interfaces:**
- Possible-billable post-dispatch uncertainty leaves/places bound reservation in `PENDING_HOLD` within the same local DB terminal transaction when possible.
- No M13-A path sets FINALIZED.

- [ ] **Step 1: Write RED integration tests** for timeout/cancel/failure with no usage => UNKNOWN or durable unresolved evidence + PENDING_HOLD; partial credible usage => INCOMPLETE + PENDING_HOLD; exact FINAL usage followed by abnormal transport may remain FINAL while request terminal transport state records failure; missing usage never RELEASES a reservation.

- [ ] **Step 2: Run focused test and verify RED.**

```powershell
.\mvnw.cmd -Dtest=GatewayUsageFailureIntegrationTest -Dgroups=integration test
```

- [ ] **Step 3: Implement only the narrow transition already permitted by M12 ownership.** Do not touch reserved amount/budget/scope/currency or add FINALIZED SQL.

- [ ] **Step 4: Re-run test.** Expected PASS.

- [ ] **Step 5: Commit.**

```powershell
git add gateway/src/main/java/com/aicostops/gateway gateway/src/test/java/com/aicostops/gateway/metering/GatewayUsageFailureIntegrationTest.java
git commit -m "feat(gateway): hold uncertain postdispatch usage"
```

---

### Task 8: Architecture, security and observability guards

**Files:**
- Modify/add Gateway architecture tests under `gateway/src/test/java/com/aicostops/gateway/architecture/`
- Modify: `gateway/src/main/java/com/aicostops/gateway/observability/GatewayMetrics.java`
- Add focused security test under existing Gateway integration/security test packages

**Interfaces:**
- Metrics use bounded labels only: usage status, provider code, bounded reason code.
- Architecture tests prove Gateway has no Ledger/Budget Actual/Settlement write seam.

- [ ] **Step 1: Add RED/guard tests** for no prompt/completion/key persistence, bounded metadata <= 8 KiB, org-qualified persistence, no raw ids as metric labels, no event-loop blocking DB work, no Ledger/Actual/Settlement writes from Gateway.

- [ ] **Step 2: Implement metrics `gateway_usage_total{status}`, `gateway_metering_incomplete_total{provider_code,reason_code}`, `gateway_metering_unknown_total{provider_code,reason_code}`, `gateway_provider_usage_parse_error_total{provider_code}` using only bounded values.

- [ ] **Step 3: Run unit + architecture suites.**

```powershell
.\mvnw.cmd -DexcludedGroups=architecture,integration test
.\mvnw.cmd -Dgroups=architecture test
```

Expected: both PASS.

- [ ] **Step 4: Commit.**

```powershell
git add gateway/src/main gateway/src/test
git commit -m "test(m13): guard gateway metering boundaries"
```

---

### Task 9: Full M13-A verification and evidence

**Files:**
- Create: `docs/03-acceptance/m13a-gateway-metering-evidence.md`
- Update only documentation needed to record verified commands/results.

- [ ] **Step 1: Run whitespace/diff guard.**

```powershell
Set-Location "E:\project\AI-CostOps"
git diff --check origin/main...HEAD
git status --short
```

- [ ] **Step 2: Run Gateway unit, architecture and integration suites.**

```powershell
Set-Location gateway
.\mvnw.cmd -B -DexcludedGroups=architecture,integration test
.\mvnw.cmd -B -Dgroups=architecture test
.\mvnw.cmd -B -Dgroups=integration verify
```

- [ ] **Step 3: Run Backend unit/architecture/integration because V20 is Backend-owned Flyway schema.**

```powershell
Set-Location ..\backend
.\mvnw.cmd -B -DexcludedGroups=architecture,integration test
.\mvnw.cmd -B -Dgroups=architecture test
.\mvnw.cmd -B -Dgroups=integration verify
```

- [ ] **Step 4: Run repository frontend checks only if CI requires no cross-module skip; otherwise rely on CI but do not claim them locally.**

- [ ] **Step 5: Write evidence with exact implementation SHA, Maven/Java versions, test counts, focused streaming ordering evidence, schema checks, concurrency/failure tests and explicit non-scope assertions.** Do not claim a pass without command output.

- [ ] **Step 6: Commit evidence.**

```powershell
git add docs/03-acceptance/m13a-gateway-metering-evidence.md
git commit -m "docs(m13): record gateway metering evidence"
```

- [ ] **Step 7: Push and open PR to `main` with `Closes #137`. Do not merge.** PR body must contain Scope, Design invariants, Verification, Security, Non-scope, and the implementation/evidence SHA distinction if evidence is a later docs-only commit.

---

## Plan self-review checklist

- Spec coverage: V20, immutable facts, revision semantics, frozen pricing coverage, MiMo non-stream/stream, durable DONE ordering, uncertainty/PENDING_HOLD, reactive boundary, security, metrics and non-scope all have tasks.
- No Settlement/Ledger/Actual/FINALIZED implementation appears in M13-A.
- No V1-V19 migration edit is permitted.
- No placeholder/TODO implementation steps are allowed.
- M13-B begins only after #137 is merged/evidenced.