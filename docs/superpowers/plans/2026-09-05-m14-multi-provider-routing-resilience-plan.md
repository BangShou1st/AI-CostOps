# M14 Multi-provider Routing / Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver one complete M14 implementation that adds deterministic routing-policy administration, MiMo + OpenAI realtime adapters, health-aware circuit breaking, and evidence-based safe failover while preserving M12/M13 financial correctness.

**Architecture:** The Backend Control Plane owns immutable/versioned `routing_policy` configuration. The Gateway Data Plane resolves one frozen policy per request, plans append-only Route Attempts, performs per-attempt budget admission and dispatch fencing, invokes an adapter through a registry, and advances only after positive `SAFE_NO_BILLABLE_EXECUTION` evidence. Circuit state lives in Redis/local runtime only and can filter future routes but never changes financial history.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring WebFlux/Reactor Netty, MyBatis, MySQL 8, Redis, Flyway, JUnit 5/Testcontainers, React 19, TypeScript 6, Ant Design 6, TanStack Query 5, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-05-m14-multi-provider-routing-resilience-design.md`

## Global Constraints

- Base design is frozen against `main@3eee76de4dc5a366f2dcbe0228e0f23159e12d47`; if `origin/main` advanced before execution, stop and reconcile the diff before coding.
- One M14 feature branch and one final runtime PR; task commits are review gates, not separate product milestones.
- Never edit Flyway V1-V21. M14 starts at `V22__m14_multi_provider_routing.sql` only if V22 is still free.
- Only positive proof of `SAFE_NO_BILLABLE_EXECUTION` permits another Provider attempt. Unknown means `BILLABLE_POSSIBLE`.
- No parallel billable hedging. No Reactor/WebClient automatic retry around Provider calls.
- One candidate `(provider_account_id, provider_model_id)` may be attempted at most once per Gateway Request.
- A request's routing policy version freezes at attempt 1 and is reused for every safe failover attempt.
- Every attempt freezes Provider Account, Provider Model and Pricing Version.
- Old effective Reservation must be RELEASED before a fresh failover Reservation may exist.
- M12 TX1/TX2 remain separate. Provider I/O never runs inside a DB transaction.
- Financial lock order remains BillingPeriod → Budget → Reservation; M13 settlement remains BillingPeriod → Budget → Commitment → Reservation → Settlement → Ledger.
- Redis circuit state is not financial truth. Redis loss may reduce routing quality only.
- No FX. A currency-changing failover must find a legal Budget in the new pricing currency.
- Provider secrets/prompt/completion/reasoning never enter routing APIs, UI, logs, metrics, audit metadata or route-decision payloads.
- `ACTIVE`/`RETIRED` routing-policy versions are immutable; changes happen through a new DRAFT revision.
- Exact project ACTIVE policy overrides org-default. If an exact project policy exists but has no eligible candidate, never fall through to org-default.
- `PROVIDER_ACCOUNT_READ` / `PROVIDER_ACCOUNT_MANAGE` are reused for routing-policy read/mutation; do not invent a new permission family in M14.
- OpenAI is the second certified realtime adapter. Production logic must not hardcode a current OpenAI model name; use the frozen DB `provider_model_name`.
- Normal CI must not require a real Provider secret or internet access; Provider certification uses local mock upstreams.
- Keep all MyBatis/JDBC work off Reactor Netty event-loop threads using the existing bounded DB executor/transaction seams.
- All frontend user-facing routing-policy copy added by M14 is Chinese-localized, matching existing Settings UI conventions.

---

## File / Responsibility Map

### Backend Control Plane

- `backend/src/main/resources/db/migration/V22__m14_multi_provider_routing.sql` — routing-policy schema, M11 route FK/reason evolution, safe MiMo legacy backfill.
- `backend/src/main/java/com/aicostops/routing/domain/RoutingPolicyStatus.java` — DRAFT/ACTIVE/RETIRED enum.
- `backend/src/main/java/com/aicostops/routing/domain/RoutingPolicy.java` — safe policy projection.
- `backend/src/main/java/com/aicostops/routing/domain/RoutingPolicyCandidate.java` — safe candidate projection.
- `backend/src/main/java/com/aicostops/routing/infrastructure/RoutingPolicyMapper.java` — MyBatis reads/writes/locks for policy administration.
- `backend/src/main/java/com/aicostops/routing/application/RoutingPolicyService.java` — authorization, versioning, DRAFT mutation, activation transaction.
- `backend/src/main/java/com/aicostops/routing/api/RoutingPolicyController.java` — bounded REST API.
- `backend/src/main/java/com/aicostops/routing/api/RoutingPolicyDtos.java` — request/response records only; never credential material.
- `backend/src/main/java/com/aicostops/gatewayadmin/infrastructure/DevGatewayBootstrap.java` — ensure local MiMo routing policy; optional explicitly configured OpenAI dev route.
- `backend/src/main/java/com/aicostops/gatewayadmin/infrastructure/GatewayAdminMapper.java` — genericized dev provisioning helpers only.
- `backend/src/main/java/com/aicostops/reconciliation/infrastructure/GatewayCloseBlockerMapper.java` — SAFE-chain Close refinement.

### Gateway Data Plane

- `gateway/src/main/java/com/aicostops/gateway/routing/RoutingPolicyMapper.java` — read-only Data Plane policy/candidate/pricing projection.
- `gateway/src/main/java/com/aicostops/gateway/routing/ResolvedRoutingPolicy.java` — immutable frozen policy/candidate records.
- `gateway/src/main/java/com/aicostops/gateway/routing/RoutingPolicyResolver.java` — exact-project then org-default resolution.
- `gateway/src/main/java/com/aicostops/gateway/routing/CandidateEligibilityEvaluator.java` — static/capability/config eligibility.
- `gateway/src/main/java/com/aicostops/gateway/routing/DeterministicRouteSelector.java` — priority/id ordering and already-attempted exclusion.
- `gateway/src/main/java/com/aicostops/gateway/request/RouteAttemptCoordinator.java` — generic append-only planning, current-pointer convergence, SAFE/BILLABLE terminal writes.
- `gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestOrchestrator.java` — request identity + candidate loop + admission/fence/failover coordination.
- `gateway/src/main/java/com/aicostops/gateway/request/DispatchFenceService.java` — per-attempt TX2 including later attempts.
- `gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestLifecycleService.java` — request lifecycle without preemptively marking attempt billable.
- `gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservationService.java` — candidate-scoped TX1 result; no premature whole-request rejection.
- `gateway/src/main/java/com/aicostops/gateway/budget/SafeReservationReleaseService.java` — verified SAFE release using financial lock order.
- `gateway/src/main/java/com/aicostops/gateway/budget/ReservationRecoveryService.java` — recognize durable SAFE as release proof; never background redispatch.
- `gateway/src/main/java/com/aicostops/gateway/provider/ProviderChatAdapter.java` — adapter identity + complete/stream boundary.
- `gateway/src/main/java/com/aicostops/gateway/provider/ProviderChatAdapterRegistry.java` — adapter-code registry, duplicate fail-fast.
- `gateway/src/main/java/com/aicostops/gateway/provider/ProviderCallContext.java` — provider-neutral call context; no header-name leak.
- `gateway/src/main/java/com/aicostops/gateway/provider/ProviderSafetyOutcome.java` — SAFE/BILLABLE enum.
- `gateway/src/main/java/com/aicostops/gateway/provider/ProviderSafetyReason.java` — bounded execution reason enum.
- `gateway/src/main/java/com/aicostops/gateway/provider/ProviderHealthSignal.java` — bounded future-health signal enum.
- `gateway/src/main/java/com/aicostops/gateway/provider/ProviderExecutionException.java` — typed failure carrying safety + health evidence.
- `gateway/src/main/java/com/aicostops/gateway/provider/mimo/MimoChatAdapter.java` — MiMo wire/auth + certified safety matrix.
- `gateway/src/main/java/com/aicostops/gateway/provider/openai/OpenAiChatAdapter.java` — OpenAI wire/auth/usage/request-id adapter.
- `gateway/src/main/java/com/aicostops/gateway/provider/openai/OpenAiWireDtos.java` — bounded OpenAI request/response DTOs.
- `gateway/src/main/java/com/aicostops/gateway/provider/openai/OpenAiSseDecoder.java` — incremental SSE decoder.
- `gateway/src/main/java/com/aicostops/gateway/provider/openai/OpenAiEndpointPolicy.java` — production endpoint allowlist.
- `gateway/src/main/java/com/aicostops/gateway/resilience/RouteCircuitKey.java` — org/account/model circuit key.
- `gateway/src/main/java/com/aicostops/gateway/resilience/CircuitState.java` — CLOSED/OPEN/HALF_OPEN.
- `gateway/src/main/java/com/aicostops/gateway/resilience/CircuitBreakerService.java` — before-call/record outcome API.
- `gateway/src/main/java/com/aicostops/gateway/resilience/RedisCircuitBreakerService.java` — Redis atomic transitions + bounded local fallback.
- `gateway/src/main/java/com/aicostops/gateway/config/GatewayProperties.java` — circuit thresholds/durations.
- `gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java` — API/rate/quota/response only; delegates routing/provider orchestration.
- `gateway/src/main/java/com/aicostops/gateway/observability/GatewayMetrics.java` — bounded routing/safety/circuit metrics.

### Frontend

- `frontend/src/features/settings/routingPolicies/types.ts` — safe routing DTO types.
- `frontend/src/features/settings/routingPolicies/RoutingPoliciesPage.tsx` — list/current policy/revision/activation UI.
- `frontend/src/features/settings/routingPolicies/RoutingPolicyEditor.tsx` — DRAFT scope/candidate editor.
- `frontend/src/features/settings/routingPolicies/RoutingPoliciesPage.test.tsx` — behavior tests.
- `frontend/src/features/settings/api/settingsApi.ts` — routing endpoints.
- `frontend/src/features/settings/api/settingsKeys.ts` — routing query keys.
- `frontend/src/features/settings/permissions.ts` — Settings navigation entry.
- `frontend/src/app/router/AppRouter.tsx` — `/settings/routing-policies` route.
- `frontend/src/app/layout/AuthenticatedLayout.tsx` — icon entry.
- `frontend/e2e/routing-policies.spec.ts` — browser admin flow.

---

### Task 1: Prove and land V22 routing schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V22__m14_multi_provider_routing.sql`
- Create: `backend/src/test/java/com/aicostops/routing/RoutingPolicySchemaIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/gatewayadmin/GatewayM11SchemaIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/gatewayadmin/GatewayM12ReservationSchemaIntegrationTest.java`
- Modify: any migration-version assertion test that currently expects V21.

**Interfaces:**
- Consumes: M10 frozen logical routing schema, V18 `gateway_route_attempt`, current V21 main schema.
- Produces: `routing_policy`, `routing_policy_candidate`, `gateway_route_attempt.route_reason_code`, same-org FK from route attempt to policy, legacy MiMo org-default policy backfill.

- [ ] **Step 1: Write RED schema tests before creating V22**

Create tests that assert the missing tables/column/FK and concurrency-enforced uniqueness. The central assertions must include:

```java
assertThat(queryTables()).contains("routing_policy", "routing_policy_candidate");
assertThat(queryColumns("gateway_route_attempt")).contains("route_reason_code");
assertThat(countConstraint("fk_gateway_route_attempt_policy_org")).isEqualTo(1);
```

Add insert tests for:

```text
same (org, NULL-project, model, version) twice → duplicate rejected
same exact scope two ACTIVE rows → duplicate rejected
same candidate policy/account/model twice → duplicate rejected
cross-org provider_account candidate → FK rejected
historical route attempt with routing_policy_id NULL → still valid
```

- [ ] **Step 2: Run the focused backend integration test and prove RED**

PowerShell:

```powershell
Set-Location "E:\project\AI-CostOps\backend"
.\mvnw.cmd -Dit.test=RoutingPolicySchemaIntegrationTest verify
```

Expected: FAIL because `routing_policy` / `routing_policy_candidate` / `route_reason_code` do not exist.

- [ ] **Step 3: Implement V22 with physical nullable-scope enforcement**

Use logical columns from the spec plus generated helpers:

```sql
project_scope_key BIGINT GENERATED ALWAYS AS (COALESCE(project_id, 0)) STORED,
active_slot TINYINT GENERATED ALWAYS AS (
    CASE WHEN status='ACTIVE' THEN 1 ELSE NULL END
) STORED,
CONSTRAINT uq_routing_policy_scope_version
    UNIQUE (org_id, project_scope_key, model_id, version),
CONSTRAINT uq_routing_policy_scope_active
    UNIQUE (org_id, project_scope_key, model_id, active_slot)
```

Add `UNIQUE(id,org_id)`, same-org FKs and checks from the spec. Add:

```sql
ALTER TABLE gateway_route_attempt
    ADD COLUMN route_reason_code VARCHAR(64) NULL AFTER route_decision_id,
    ADD CONSTRAINT fk_gateway_route_attempt_policy_org
      FOREIGN KEY (routing_policy_id, org_id)
      REFERENCES routing_policy (id, org_id);
```

Do not add a candidate id to `gateway_route_attempt`; policy + provider account/model identifies the selected candidate.

- [ ] **Step 4: Implement a safe legacy MiMo backfill in V22**

Backfill only org/model pairs whose pre-M14 query had an eligible MiMo route. Use a window/CTE or deterministic subquery to rank the same eligible join by the old effective pricing order and create:

```text
org-default policy version 1 ACTIVE
one priority-0 MiMo candidate equal to the old selected route
```

Do not fabricate policies for org/model pairs with no eligible old route.

- [ ] **Step 5: Update old schema tests from “routing tables absent” to “present from V22”**

Keep historical M11/M12 intent comments but assert current schema truth. Do not weaken any V18/V19 constraints.

- [ ] **Step 6: Run schema tests GREEN**

```powershell
.\mvnw.cmd -Dit.test=RoutingPolicySchemaIntegrationTest,GatewayM11SchemaIntegrationTest,GatewayM12ReservationSchemaIntegrationTest verify
```

Expected: PASS, no Flyway checksum change for V1-V21.

- [ ] **Step 7: Verify migration diff and commit**

```powershell
Set-Location "E:\project\AI-CostOps"
git diff --check
git diff origin/main -- backend/src/main/resources/db/migration
git add backend/src/main/resources/db/migration/V22__m14_multi_provider_routing.sql backend/src/test/java
git commit -m "feat(m14): add routing policy schema"
```

Expected migration diff: V22 added; V1-V21 unchanged.

---

### Task 2: Build immutable Routing Policy Control Plane

**Files:**
- Create: `backend/src/main/java/com/aicostops/routing/domain/RoutingPolicyStatus.java`
- Create: `backend/src/main/java/com/aicostops/routing/domain/RoutingPolicy.java`
- Create: `backend/src/main/java/com/aicostops/routing/domain/RoutingPolicyCandidate.java`
- Create: `backend/src/main/java/com/aicostops/routing/infrastructure/RoutingPolicyMapper.java`
- Create: `backend/src/main/java/com/aicostops/routing/application/RoutingPolicyService.java`
- Create: `backend/src/main/java/com/aicostops/routing/api/RoutingPolicyDtos.java`
- Create: `backend/src/main/java/com/aicostops/routing/api/RoutingPolicyController.java`
- Create: `backend/src/test/java/com/aicostops/routing/RoutingPolicyApiIntegrationTest.java`
- Modify: `backend/src/main/java/com/aicostops/organization/application/OrganizationAuditPort.java`
- Modify: implementation of `OrganizationAuditPort` / audit adapter and its tests.

**Interfaces:**
- Produces REST:
  - `GET /api/v1/routing-policies`
  - `GET /api/v1/routing-policies/{id}`
  - `POST /api/v1/routing-policies`
  - `POST /api/v1/routing-policies/{id}/revisions`
  - `PUT /api/v1/routing-policies/{id}`
  - `POST /api/v1/routing-policies/{id}/activate`
  - `GET /api/v1/routing-options?modelId=<id>`
- Produces immutable ACTIVE history used by Gateway runtime reads in Task 4.

- [ ] **Step 1: Write API RED tests for authorization, versioning and activation**

Required tests:

```java
@Test void readerCanListButCannotMutate() { ... }
@Test void managerCreatesDraftWithServerAssignedVersion() { ... }
@Test void activePolicyCannotBeEdited() { ... }
@Test void revisionClonesCandidatesAndIncrementsVersion() { ... }
@Test void activationRetiresPriorExactScopeOnly() { ... }
@Test void twoConcurrentActivationsLeaveOneActive() { ... }
@Test void crossOrgCandidateIsRejected() { ... }
@Test void providerAccountAndProviderModelCodesMustMatch() { ... }
@Test void routeOptionsNeverExposeCredentialCiphertext() { ... }
```

Assert audit event types:

```text
ROUTING_POLICY_CREATED
ROUTING_POLICY_REVISED
ROUTING_POLICY_UPDATED
ROUTING_POLICY_ACTIVATED
```

with safe ids/version/status only.

- [ ] **Step 2: Run focused API integration RED**

```powershell
Set-Location "E:\project\AI-CostOps\backend"
.\mvnw.cmd -Dit.test=RoutingPolicyApiIntegrationTest verify
```

Expected: FAIL because routing API/classes do not exist.

- [ ] **Step 3: Implement safe DTO/domain types**

Use records like:

```java
public record RoutingPolicyCandidate(
        long id,
        long providerAccountId,
        long providerModelId,
        int priority,
        String status,
        String privacyRegionCode) {}

public record RoutingPolicy(
        long id,
        long organizationId,
        Long projectId,
        long modelId,
        int version,
        RoutingPolicyStatus status,
        List<RoutingPolicyCandidate> candidates) {}
```

Request DTOs contain only project/model/candidate ids, integer priority/status and optional privacy region. No secret/ciphertext/nonce/external raw metadata fields.

- [ ] **Step 4: Implement mapper and transactional service**

`RoutingPolicyService` public methods must require:

```java
authorization.requireOrg(context, "PROVIDER_ACCOUNT_READ");
authorization.requireOrg(context, "PROVIDER_ACCOUNT_MANAGE");
```

as appropriate.

Activation transaction order:

```text
lock organization
validate DRAFT + same-org project/model/candidates
require >=1 ACTIVE candidate
retire current exact-scope ACTIVE
activate DRAFT
append audit
commit
```

Never modify candidate rows after policy status leaves DRAFT.

- [ ] **Step 5: Implement routing-options safe projection**

Return provisioned route readiness only:

```text
providerAccountId / displayName / providerCode
providerModelId / providerModelName
routingEligible
credentialReady boolean
pricingReady boolean
current pricing currencies (bounded list if already exposed safely)
```

Never return encrypted/decrypted credential material.

- [ ] **Step 6: Run RED/GREEN suite and full organization authorization regressions**

```powershell
.\mvnw.cmd -Dit.test=RoutingPolicyApiIntegrationTest,ProviderAccountApiIntegrationTest verify
.\mvnw.cmd test
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
Set-Location "E:\project\AI-CostOps"
git add backend/src/main/java/com/aicostops/routing backend/src/test/java/com/aicostops/routing backend/src/main/java/com/aicostops/organization backend/src/main/java/com/aicostops/audit
git commit -m "feat(m14): add routing policy control plane"
```

---

### Task 3: Add Routing Policy Settings UI

**Files:**
- Create: `frontend/src/features/settings/routingPolicies/types.ts`
- Create: `frontend/src/features/settings/routingPolicies/RoutingPoliciesPage.tsx`
- Create: `frontend/src/features/settings/routingPolicies/RoutingPolicyEditor.tsx`
- Create: `frontend/src/features/settings/routingPolicies/RoutingPoliciesPage.test.tsx`
- Modify: `frontend/src/features/settings/api/settingsApi.ts`
- Modify: `frontend/src/features/settings/api/settingsKeys.ts`
- Modify: `frontend/src/features/settings/permissions.ts`
- Modify: `frontend/src/app/router/AppRouter.tsx`
- Modify: `frontend/src/app/layout/AuthenticatedLayout.tsx`
- Create: `frontend/e2e/routing-policies.spec.ts`

**Interfaces:**
- Consumes Task 2 REST API.
- Produces `/settings/routing-policies` guarded by `PROVIDER_ACCOUNT_READ`; mutations additionally gated by `PROVIDER_ACCOUNT_MANAGE`.

- [ ] **Step 1: Write Vitest RED tests**

Cover:

```text
reader sees ACTIVE/DRAFT history but no mutation controls
manager can create revision from ACTIVE
DRAFT candidate rows sort/display by priority
manager can change priority/status and save DRAFT
activate confirmation calls activation endpoint
ACTIVE candidate editor is disabled
API errors render explicit Chinese error state
```

- [ ] **Step 2: Run RED**

```powershell
Set-Location "E:\project\AI-CostOps\frontend"
npm test -- --run src/features/settings/routingPolicies/RoutingPoliciesPage.test.tsx
```

Expected: FAIL because page does not exist.

- [ ] **Step 3: Add typed API methods and query keys**

Use methods with exact intent:

```ts
listRoutingPolicies(params)
getRoutingPolicy(id)
createRoutingPolicy(input)
createRoutingPolicyRevision(id)
updateRoutingPolicy(id, input)
activateRoutingPolicy(id)
listRoutingOptions(modelId)
```

Do not add any Provider-secret fields to frontend types.

- [ ] **Step 4: Implement page/editor and route**

Chinese copy must clearly distinguish:

```text
组织默认策略
项目策略
草稿 / 已启用 / 已退役
候选优先级（数字越小越优先）
创建新版本
启用版本
```

Show readiness warnings but do not allow the UI to bypass backend activation validation.

- [ ] **Step 5: Add Playwright browser flow**

The E2E scenario must:

```text
login as manager
open 路由策略
create/clone DRAFT
reorder candidates
activate
reload
verify new ACTIVE and old RETIRED
verify ACTIVE editor read-only
```

- [ ] **Step 6: Run frontend focused/full checks GREEN**

```powershell
npm test -- --run src/features/settings/routingPolicies/RoutingPoliciesPage.test.tsx
npm run lint
npm run build
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add frontend
git commit -m "feat(m14): add routing policy settings UI"
```

---

### Task 4: Implement Gateway policy resolution and deterministic candidate selection

**Files:**
- Create: `gateway/src/main/java/com/aicostops/gateway/routing/RoutingPolicyMapper.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/routing/ResolvedRoutingPolicy.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/routing/RoutingPolicyResolver.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/routing/CandidateEligibilityEvaluator.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/routing/DeterministicRouteSelector.java`
- Create: `gateway/src/test/java/com/aicostops/gateway/routing/RoutingPolicyResolverTest.java`
- Create: `gateway/src/test/java/com/aicostops/gateway/routing/DeterministicRouteSelectorTest.java`
- Create: `gateway/src/test/java/com/aicostops/gateway/routing/RoutingPolicyIntegrationTest.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/persistence/GatewayReadMapper.java` to remove the M11 hardcoded-provider route query from runtime use.

**Interfaces:**
- Produces:

```java
ResolvedRoutingPolicy resolve(long orgId, long projectId, long logicalModelId, Instant now);
List<ResolvedRoutingPolicy.Candidate> orderedCandidates(ResolvedRoutingPolicy policy);
CandidateEligibility evaluate(Candidate candidate, RequestCapabilities capabilities, Set<RouteIdentity> attempted, Instant now);
```

- [ ] **Step 1: Write RED tests for policy precedence and ordering**

Required assertions:

```java
assertThat(resolver.resolve(org, project, model, now).id()).isEqualTo(projectPolicyId);
assertThat(selector.orderedCandidates(policy))
    .extracting(Candidate::id)
    .containsExactly(priority0LowerId, priority0HigherId, priority1Id);
```

Also assert exact project policy with zero eligible candidates does not resolve org policy.

- [ ] **Step 2: Run focused Gateway RED**

```powershell
Set-Location "E:\project\AI-CostOps\gateway"
.\mvnw.cmd -Dtest=RoutingPolicyResolverTest,DeterministicRouteSelectorTest test
```

Expected: FAIL because types are missing.

- [ ] **Step 3: Implement read projection and resolver**

`ResolvedRoutingPolicy` must include immutable policy id/version/scope and immutable candidate list. Runtime query returns no secret bytes; credential presence is a boolean/readiness fact until the chosen route builds its call context.

- [ ] **Step 4: Implement static eligibility and stable ordering**

Eligibility reasons are a bounded enum in code, including:

```text
ACCOUNT_INACTIVE
CREDENTIAL_MISSING
MODEL_INACTIVE
MODEL_NOT_ROUTING_ELIGIBLE
LOGICAL_MODEL_MISMATCH
ADAPTER_UNAVAILABLE
CHAT_CAPABILITY_MISMATCH
STREAM_CAPABILITY_MISMATCH
PRICING_UNAVAILABLE
ALREADY_ATTEMPTED
```

Circuit filtering is added in Task 8; keep its seam explicit instead of hardcoding “healthy=true”.

- [ ] **Step 5: Run unit + MySQL policy read tests GREEN**

```powershell
.\mvnw.cmd -Dtest=RoutingPolicyResolverTest,DeterministicRouteSelectorTest test
.\mvnw.cmd -Dit.test=RoutingPolicyIntegrationTest verify
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
Set-Location "E:\project\AI-CostOps"
git add gateway/src/main/java/com/aicostops/gateway/routing gateway/src/test/java/com/aicostops/gateway/routing gateway/src/main/java/com/aicostops/gateway/persistence/GatewayReadMapper.java
git commit -m "feat(m14): add deterministic routing policy resolution"
```

---

### Task 5: Refactor Provider boundary and add adapter registry without behavior change

**Files:**
- Modify: `gateway/src/main/java/com/aicostops/gateway/provider/ProviderChatAdapter.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/provider/ProviderChatAdapterRegistry.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/provider/ProviderCallContext.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/provider/ProviderSafetyOutcome.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/provider/ProviderSafetyReason.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/provider/ProviderHealthSignal.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/provider/ProviderExecutionException.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/provider/mimo/MimoChatAdapter.java`
- Modify: MiMo adapter/unit/integration tests.
- Create: `gateway/src/test/java/com/aicostops/gateway/provider/ProviderChatAdapterRegistryTest.java`

**Interfaces:**
- `ProviderChatAdapter` adds:

```java
String adapterCode();
Mono<ProviderChatCompletion> complete(ProviderCallContext context, ChatCompletionCommand command);
Flux<ProviderChatStreamEvent> stream(ProviderCallContext context, ChatCompletionCommand command);
```

- `ProviderExecutionException` constructor exposes only bounded evidence:

```java
ProviderExecutionException(
    ProviderSafetyOutcome safetyOutcome,
    ProviderSafetyReason safetyReason,
    ProviderHealthSignal healthSignal,
    Integer httpStatus,
    String providerRequestId,
    boolean responseStarted,
    Throwable cause)
```

- [ ] **Step 1: Write registry/context RED tests**

Test duplicate adapter code startup rejection and lookup failure. Test `ProviderCallContext.toString()` contains neither secret bytes nor credential value.

- [ ] **Step 2: Write MiMo safety RED matrix**

At minimum inject root causes for:

```text
UnknownHostException → SAFE / DNS_PRE_CONNECT
ConnectException → SAFE / CONNECT_REFUSED_PRE_WRITE
ConnectTimeoutException → SAFE / CONNECT_TIMEOUT_PRE_WRITE
SSLHandshakeException before HTTP → SAFE / TLS_HANDSHAKE_PRE_HTTP_WRITE
HTTP 429 → BILLABLE_POSSIBLE / HTTP_RESPONSE_RECEIVED
HTTP 500 → BILLABLE_POSSIBLE / HTTP_RESPONSE_RECEIVED
generic timeout → BILLABLE_POSSIBLE
malformed response → BILLABLE_POSSIBLE
unknown throwable → BILLABLE_POSSIBLE
```

- [ ] **Step 3: Run RED**

```powershell
Set-Location "E:\project\AI-CostOps\gateway"
.\mvnw.cmd -Dtest=ProviderChatAdapterRegistryTest,MimoChatAdapterTest test
```

Expected: FAIL for new interface/types.

- [ ] **Step 4: Implement registry and neutral context**

Registry implementation shape:

```java
@Component
public final class ProviderChatAdapterRegistry {
    private final Map<String, ProviderChatAdapter> byCode;

    public ProviderChatAdapterRegistry(List<ProviderChatAdapter> adapters) {
        // normalize uppercase, reject duplicate, freeze Map.copyOf
    }

    public ProviderChatAdapter require(String adapterCode) { ... }
    public boolean contains(String adapterCode) { ... }
}
```

Remove `providerKeyHeader` from `ProviderCallContext`. Add safe `routeDecisionId` correlation field.

- [ ] **Step 5: Move MiMo auth ownership fully into MiMo adapter**

MiMo validates `credentialType.equals("API_KEY")` and sends exactly:

```java
.header("api-key", new String(context.providerSecret(), StandardCharsets.UTF_8))
```

No generic/controller code decides this header.

- [ ] **Step 6: Implement conservative MiMo typed failure mapping and run GREEN**

Never map a generic timeout/IOException SAFE. Preserve the original cause internally but expose only bounded Gateway errors upward.

```powershell
.\mvnw.cmd -Dtest=ProviderChatAdapterRegistryTest,MimoChatAdapterTest test
.\mvnw.cmd test
```

- [ ] **Step 7: Commit**

```powershell
git add gateway/src/main/java/com/aicostops/gateway/provider gateway/src/test/java/com/aicostops/gateway/provider
git commit -m "refactor(m14): make provider boundary multi-adapter safe"
```

---

### Task 6: Add certified OpenAI realtime Chat Completions adapter

**Files:**
- Create: `gateway/src/main/java/com/aicostops/gateway/provider/openai/OpenAiChatAdapter.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/provider/openai/OpenAiWireDtos.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/provider/openai/OpenAiSseDecoder.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/provider/openai/OpenAiEndpointPolicy.java`
- Create: `gateway/src/test/java/com/aicostops/gateway/provider/openai/OpenAiChatAdapterTest.java`
- Create: `gateway/src/test/java/com/aicostops/gateway/provider/openai/OpenAiSseDecoderTest.java`
- Modify: adapter architecture tests if component allowlists are explicit.

**Interfaces:**
- `adapterCode()` returns exactly `OPENAI`.
- Requires `credentialType=BEARER_TOKEN`.
- `ProviderChatCompletion.providerRequestId` receives trustworthy `x-request-id` response header when present.
- Streaming emits existing provider-neutral `Delta`, `Metering`, `Done` events.

- [ ] **Step 1: Write RED wire/auth tests against local mock server**

Assert request includes:

```text
POST /v1/chat/completions (based on configured test base URL)
Authorization: Bearer <test secret>
X-Client-Request-Id: <routeDecisionId>
model = context.providerModelName
stream=true when requested
stream_options.include_usage=true for streaming
```

Do not assert a hardcoded live model name.

- [ ] **Step 2: Write RED response/stream tests**

Test:

```text
non-streaming choices + usage normalization
x-request-id capture
stream delta parsing
terminal usage-only chunk is Metering and is not content
[DONE] becomes Done
clean EOF without [DONE] is failure
interrupted stream without usage never fabricates zero
Provider error body is redacted
```

- [ ] **Step 3: Write the same safety matrix RED for OpenAI**

All HTTP statuses including 429/5xx remain `BILLABLE_POSSIBLE` in M14 certification. Only positive pre-connect/pre-write classes are SAFE.

- [ ] **Step 4: Run RED**

```powershell
Set-Location "E:\project\AI-CostOps\gateway"
.\mvnw.cmd -Dtest=OpenAiChatAdapterTest,OpenAiSseDecoderTest test
```

- [ ] **Step 5: Implement bounded DTOs/decoder/endpoint policy/adapter**

Request DTO must add stream usage server-side, conceptually:

```java
record StreamOptions(boolean include_usage) {}
```

The public client payload remains AI-CostOps-governed; do not blindly proxy arbitrary OpenAI fields.

Production endpoint policy must reject non-certified scheme/host; test profile may target mock localhost.

- [ ] **Step 6: Run adapter tests + full Gateway unit suite GREEN**

```powershell
.\mvnw.cmd -Dtest=OpenAiChatAdapterTest,OpenAiSseDecoderTest,MimoChatAdapterTest test
.\mvnw.cmd test
```

- [ ] **Step 7: Commit**

```powershell
git add gateway/src/main/java/com/aicostops/gateway/provider/openai gateway/src/test/java/com/aicostops/gateway/provider/openai
git commit -m "feat(m14): add OpenAI realtime provider adapter"
```

---

### Task 7: Implement CLOSED / OPEN / HALF_OPEN circuit coordination

**Files:**
- Create: `gateway/src/main/java/com/aicostops/gateway/resilience/RouteCircuitKey.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/resilience/CircuitState.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/resilience/CircuitDecision.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/resilience/CircuitBreakerService.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/resilience/RedisCircuitBreakerService.java`
- Create: `gateway/src/test/java/com/aicostops/gateway/resilience/RedisCircuitBreakerServiceTest.java`
- Create: `gateway/src/test/java/com/aicostops/gateway/resilience/CircuitBreakerRedisIntegrationTest.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/config/GatewayProperties.java`
- Modify: gateway application YAML/example environment docs as existing config conventions require.

**Interfaces:**

```java
public interface CircuitBreakerService {
    Mono<CircuitDecision> beforeCall(RouteCircuitKey key);
    Mono<Void> recordSuccess(RouteCircuitKey key);
    Mono<Void> recordFailure(RouteCircuitKey key, ProviderHealthSignal signal);
}
```

`CircuitDecision` includes state and `probeAllowed`; no financial data.

- [ ] **Step 1: Write deterministic clock RED state-machine tests**

Defaults from spec:

```text
failureThreshold=5
openDurationMs=30000
halfOpenLeaseMs=15000
```

Tests prove fifth qualifying failure opens, early call denied, after 30s one HALF_OPEN probe allowed, probe success closes, failure reopens.

- [ ] **Step 2: Write Redis multi-instance RED integration test**

Instantiate two services against the same Testcontainers Redis and assert only one acquires HALF_OPEN probe lease.

- [ ] **Step 3: Write Redis-outage local fallback RED test**

Kill/unavailable Redis dependency and assert `beforeCall` uses bounded local state instead of mutating/requesting any DB financial fact.

- [ ] **Step 4: Run RED**

```powershell
Set-Location "E:\project\AI-CostOps\gateway"
.\mvnw.cmd -Dtest=RedisCircuitBreakerServiceTest test
.\mvnw.cmd -Dit.test=CircuitBreakerRedisIntegrationTest verify
```

- [ ] **Step 5: Implement properties with startup validation**

Add:

```java
private int circuitFailureThreshold = 5;
private long circuitOpenDurationMs = 30_000;
private long circuitHalfOpenLeaseMs = 15_000;
```

Validate all >0 like existing bounded limits.

- [ ] **Step 6: Implement Redis atomic transitions + local fallback**

Redis key must be exactly bounded from org/account/model ids. Use one Lua/atomic operation per transition where concurrent read-modify-write matters. Do not store request ids, content or secret material.

Health mapping:

```text
client cancel / normal request 4xx → do not count
401/403/route-level 404 → immediate route OPEN
429/5xx/timeouts/protocol/connectivity → qualifying failure
success → reset/close
```

This mapping affects future routing only; it never changes `ProviderSafetyOutcome`.

- [ ] **Step 7: Run GREEN and commit**

```powershell
.\mvnw.cmd -Dtest=RedisCircuitBreakerServiceTest test
.\mvnw.cmd -Dit.test=CircuitBreakerRedisIntegrationTest verify
Set-Location "E:\project\AI-CostOps"
git add gateway/src/main/java/com/aicostops/gateway/resilience gateway/src/test/java/com/aicostops/gateway/resilience gateway/src/main/java/com/aicostops/gateway/config
git commit -m "feat(m14): add provider route circuit breaker"
```

---

### Task 8: Generalize Route Attempts, candidate admission, SAFE release and TX2

**Files:**
- Create: `gateway/src/main/java/com/aicostops/gateway/request/RouteAttemptCoordinator.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/persistence/GatewayRequestMapper.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/persistence/BudgetReservationMapper.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservationService.java`
- Create: `gateway/src/main/java/com/aicostops/gateway/budget/SafeReservationReleaseService.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/budget/ReservationRecoveryService.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/request/DispatchFenceService.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestLifecycleService.java`
- Create: `gateway/src/test/java/com/aicostops/gateway/request/RouteAttemptCoordinatorIntegrationTest.java`
- Create: `gateway/src/test/java/com/aicostops/gateway/budget/SafeReservationReleaseIntegrationTest.java`
- Modify: existing M12 reservation/idempotency/dispatch-fence tests.

**Interfaces:**

`RouteAttemptCoordinator` produces:

```java
PlannedAttempt plan(
    long orgId,
    long requestId,
    long routingPolicyId,
    String routeReasonCode,
    long providerAccountId,
    long providerModelId,
    long pricingVersionId);

void markSafe(long orgId, long attemptId, ProviderSafetyReason reason);
void markBillablePossible(long orgId, long attemptId, ProviderSafetyReason reason, String providerRequestId);
```

`SafeReservationReleaseService`:

```java
ReleaseResult releaseForSafeAttempt(long orgId, long requestId, long attemptId, long billingPeriodId);
```

- [ ] **Step 1: Write RED generic-attempt tests**

Prove:

```text
attempt 1 inserts normally
attempt 2 requires attempt 1 SAFE
attempt 2 rejected if attempt 1 BILLABLE_POSSIBLE/COMPLETED/DISPATCH_INTENT
attempt 2 rejected while old effective reservation remains
concurrent attempt-2 planners converge to one row
policy id/reason/provider/model/pricing are frozen
```

- [ ] **Step 2: Write RED lifecycle test for the M14 safety fix**

Explicitly assert:

```java
lifecycle.beginUpstream(requestId, orgId, attemptId);
assertThat(findAttempt(attemptId).status()).isEqualTo("DISPATCH_INTENT");
```

`beginUpstream` may move request state to `UPSTREAM_ACTIVE` but must not mark the attempt BILLABLE.

- [ ] **Step 3: Write RED candidate-budget tests**

Candidate A budget rejection must:

```text
commit SAFE attempt A
leave whole request eligible to consider candidate B
not create reservation A
```

Only final initial-candidate exhaustion may mark request `REJECTED_BUDGET`.

Later safe-failover budget exhaustion terminates no-billable chain without moving the request backward to RESERVED.

- [ ] **Step 4: Write RED SAFE release/recovery tests**

Prove financial lock behavior and outcomes:

```text
SAFE + ACTIVE → RELEASED
SAFE + PENDING_HOLD → do not silently rewrite; explicit recovery rule required
BILLABLE_POSSIBLE + expired ACTIVE → PENDING_HOLD, never RELEASED
SAFE recovery never calls Provider
```

- [ ] **Step 5: Refactor mapper and BudgetReservationService**

Remove candidate-level calls to `markRequestRejectedBudget()` from `noBudget`, `reservationImpossible`, and `insufficient`. Return `AdmissionOutcome` after transaction commit.

Change request-binding on successful reservation so:

```text
VALIDATED → RESERVED for first admitted route
DISPATCH_INTENT / UPSTREAM_ACTIVE → keep state for later SAFE failover
billing_period_id must match the already-frozen request period when non-null
current route attempt pointer becomes the new attempt
```

- [ ] **Step 6: Generalize TX2**

`commitDispatchFence` verifies the current attempt and its admission. First attempt may move request to DISPATCH_INTENT; later attempt keeps the already-forward request state but still moves the new route attempt PLANNED → DISPATCH_INTENT in the same BillingPeriod-locked transaction.

- [ ] **Step 7: Implement SAFE release with exact financial lock order**

Transaction:

```text
lock BillingPeriod
→ locate/lock Budget
→ lock Reservation
→ re-read/verify route attempt SAFE
→ RELEASE ACTIVE reservation
→ commit
```

No new route is planned inside this release transaction.

- [ ] **Step 8: Run focused M12/M14 GREEN**

```powershell
Set-Location "E:\project\AI-CostOps\gateway"
.\mvnw.cmd -Dit.test=RouteAttemptCoordinatorIntegrationTest,SafeReservationReleaseIntegrationTest,DispatchFenceIntegrationTest,ReservationRecoveryIntegrationTest,GatewayRequestIdempotencyIntegrationTest verify
```

Expected: PASS.

- [ ] **Step 9: Commit**

```powershell
Set-Location "E:\project\AI-CostOps"
git add gateway/src/main/java/com/aicostops/gateway/request gateway/src/main/java/com/aicostops/gateway/budget gateway/src/main/java/com/aicostops/gateway/persistence gateway/src/test/java/com/aicostops/gateway/request gateway/src/test/java/com/aicostops/gateway/budget
git commit -m "feat(m14): make route attempts safe-failover capable"
```

---

### Task 9: Build the request orchestrator and deterministic pre-dispatch candidate loop

**Files:**
- Create: `gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestOrchestrator.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestService.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/routing/CandidateEligibilityEvaluator.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java`
- Create: `gateway/src/test/java/com/aicostops/gateway/request/GatewayRoutingOrchestratorIntegrationTest.java`

**Interfaces:**

`GatewayRequestService` becomes request identity/catalog/period convergence and no longer chooses `MIMO`.

`GatewayRequestOrchestrator` exposes an initial preparation operation conceptually:

```java
Mono<PreparedDispatch> prepareInitial(AuthorizeCommand command, boolean streaming);
Mono<PreparedDispatch> prepareNextSafe(PreparedDispatch previous, ProviderExecutionException safeFailure);
```

`PreparedDispatch` carries only server-governed route/attempt/admission fields required for one Provider call.

- [ ] **Step 1: Write RED initial-routing integration tests**

Cases:

```text
project policy beats org default
no project policy → org default
exact project policy with all candidates ineligible → fail, no org fallback
candidate A missing pricing → skip to B without attempt A
candidate A budget rejected → SAFE attempt A then reserve/fence B
candidate A OPEN → skip to B
same priority → lower candidate id wins
no eligible candidates → no Provider I/O
```

Use mock adapters that count invocations; pre-dispatch tests must assert zero Provider calls.

- [ ] **Step 2: Run RED**

```powershell
Set-Location "E:\project\AI-CostOps\gateway"
.\mvnw.cmd -Dit.test=GatewayRoutingOrchestratorIntegrationTest verify
```

- [ ] **Step 3: Remove `M11_PROVIDER_CODE = "MIMO"` from runtime authorization**

`GatewayRequestService` must not call `findRouteCandidate(..., MIMO, ...)`.

It should return durable request identity + logical-model/billing-period context to the orchestrator.

- [ ] **Step 4: Implement initial deterministic candidate loop**

For each ordered candidate:

```text
static eligibility
→ circuit decision
→ resolve fresh active Pricing Version
→ plan attempt
→ candidate TX1 admission
→ if pre-provider rejection: mark attempt SAFE and continue
→ TX2 fence
→ return PreparedDispatch
```

Do not decrypt a Provider credential until a route is actually selected/fenced for call construction.

- [ ] **Step 5: Build ProviderCallContext server-side after route selection**

Decrypt only the chosen active Provider credential. Context contains `credentialType` and secret bytes; adapter owns header formatting.

- [ ] **Step 6: Run GREEN plus old request/auth tests**

```powershell
.\mvnw.cmd -Dit.test=GatewayRoutingOrchestratorIntegrationTest,GatewayRequestIdempotencyIntegrationTest verify
.\mvnw.cmd test
```

- [ ] **Step 7: Commit**

```powershell
Set-Location "E:\project\AI-CostOps"
git add gateway/src/main/java/com/aicostops/gateway/request gateway/src/main/java/com/aicostops/gateway/routing gateway/src/main/java/com/aicostops/gateway/web gateway/src/test/java/com/aicostops/gateway/request
git commit -m "feat(m14): route gateway requests by policy"
```

---

### Task 10: Implement non-streaming evidence-based safe failover

**Files:**
- Modify: `gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestOrchestrator.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/metering/GatewayUsageFinalizationService.java` only if a small explicit interface split is required; do not change M13 financial semantics.
- Create: `gateway/src/test/java/com/aicostops/gateway/request/GatewaySafeFailoverIntegrationTest.java`
- Modify: Provider mock/test support.

**Interfaces:**
- Safe adapter failure returns to orchestrator; billable/unknown failure enters existing M13 failure finalization.
- `prepareNextSafe` always uses the first attempt's frozen `routing_policy_id` and excludes all already-attempted candidates.

- [ ] **Step 1: Write RED SAFE A → B success test**

Fixture:

```text
policy: A priority 0, B priority 1
A mock adapter throws SAFE/DNS_PRE_CONNECT
B mock adapter succeeds with exact usage
```

Assert in order:

```text
A attempt DISPATCH_INTENT → SAFE
A reservation ACTIVE → RELEASED
B attempt uses same routing_policy_id, attempt_no=2
B gets fresh pricing/reservation
B TX2 committed before invocation
only B has usage fact/settlement lineage
```

- [ ] **Step 2: Write RED price/currency failover tests**

A and B have different Pricing Versions and reserved amounts. Assert B reservation uses B's frozen pricing.

For currency change:

```text
A USD SAFE
B CNY
no CNY Budget + REQUIRED → B never dispatched
```

If C candidate can legally reserve, deterministic loop may advance without Provider I/O on budget-rejected B.

- [ ] **Step 3: Write RED conservative stop tests**

Each of these on A must assert B invocation count stays zero:

```text
HTTP 429
HTTP 500
read/header/stream-equivalent timeout after write possible
connection reset after write possible
malformed response
unknown ProviderExecutionException
```

- [ ] **Step 4: Run RED**

```powershell
Set-Location "E:\project\AI-CostOps\gateway"
.\mvnw.cmd -Dit.test=GatewaySafeFailoverIntegrationTest verify
```

- [ ] **Step 5: Implement non-streaming loop**

Pseudo-structure to preserve:

```java
return invoke(prepared)
    .onErrorResume(ProviderExecutionException.class, failure -> {
        if (failure.safetyOutcome() == BILLABLE_POSSIBLE) {
            return finalizeBillableFailureAndError(prepared, failure);
        }
        return markSafe(prepared, failure)
            .then(releaseSafeReservation(prepared))
            .then(prepareNextSafe(prepared, failure))
            .flatMap(this::invokeNonStreaming);
    });
```

Do not catch a generic Throwable and call next Provider. Generic/unknown errors must be converted to BILLABLE_POSSIBLE finalization.

- [ ] **Step 6: Wire circuit success/failure signals independently of billing safety**

For example, HTTP 500 is:

```text
current attempt billing safety = BILLABLE_POSSIBLE → stop failover
future route health signal = provider failure → increment/open circuit
```

Keep those decisions separate in code/tests.

- [ ] **Step 7: Run GREEN and M13 metering/settlement regressions**

```powershell
.\mvnw.cmd -Dit.test=GatewaySafeFailoverIntegrationTest,GatewayUsageFinalizationIntegrationTest verify
.\mvnw.cmd test
```

- [ ] **Step 8: Commit**

```powershell
Set-Location "E:\project\AI-CostOps"
git add gateway
git commit -m "feat(m14): add evidence-based safe provider failover"
```

---

### Task 11: Implement streaming safe failover without mixed Provider output

**Files:**
- Modify: `gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestOrchestrator.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java`
- Modify: `gateway/src/main/java/com/aicostops/gateway/request/StreamingLifecycleService.java` only when needed to keep existing cancel/timeout terminalization.
- Create: `gateway/src/test/java/com/aicostops/gateway/request/GatewayStreamingSafeFailoverIntegrationTest.java`
- Modify: existing streaming/M13 tests.

**Interfaces:**
- Stream orchestration may fail over only before any event/HTTP evidence turns the prior attempt BILLABLE_POSSIBLE.
- Existing provider-neutral `Flux<ProviderChatStreamEvent>` and downstream `[DONE]` contract remain.

- [ ] **Step 1: Write RED stream A SAFE pre-connect → B stream success**

Assert:

```text
A emits no upstream content/event
A SAFE + reservation released
B becomes attempt 2
client receives only B deltas
exactly one downstream [DONE]
B terminal usage persists before downstream [DONE]
```

- [ ] **Step 2: Write RED no-mixing tests**

A produces any HTTP/stream response then fails. Assert:

```text
A = BILLABLE_POSSIBLE
no B call
no B content mixed into downstream stream
M13 best available usage observation retained
```

- [ ] **Step 3: Write RED client-cancel test**

Cancel after A dispatch:

```text
A = BILLABLE_POSSIBLE
reservation becomes/remains PENDING_HOLD via failure finalization/recovery path
circuit receives no client-cancel health penalty
B invocation count = 0
```

- [ ] **Step 4: Run RED**

```powershell
Set-Location "E:\project\AI-CostOps\gateway"
.\mvnw.cmd -Dit.test=GatewayStreamingSafeFailoverIntegrationTest verify
```

- [ ] **Step 5: Implement stream failover composition**

Use deferred recursion/iteration that subscribes to the next Provider only after SAFE persistence and release complete. Do not use `.retry()`.

A SAFE failure must not call `GatewayUsageFinalizationService.finalizeFailure`, because no Provider could have executed. A BILLABLE failure must continue to call existing M13 failure finalization with the best observed usage.

- [ ] **Step 6: Run GREEN + old streaming suites**

```powershell
.\mvnw.cmd -Dit.test=GatewayStreamingSafeFailoverIntegrationTest,GatewayStreamingMeteringIntegrationTest verify
.\mvnw.cmd test
```

- [ ] **Step 7: Commit**

```powershell
Set-Location "E:\project\AI-CostOps"
git add gateway
git commit -m "feat(m14): add safe streaming provider failover"
```

---

### Task 12: Prove replay, crash recovery, Close and financial lineage concurrency

**Files:**
- Modify: `gateway/src/test/java/.../GatewayRequestIdempotencyIntegrationTest.java`
- Modify: `gateway/src/test/java/.../ReservationRecoveryIntegrationTest.java`
- Create: `gateway/src/test/java/com/aicostops/gateway/request/GatewayFailoverConcurrencyIntegrationTest.java`
- Modify: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/GatewayCloseBlockerMapper.java`
- Modify: `backend/src/test/java/com/aicostops/reconciliation/GatewayFinancialWorkCloseIntegrationTest.java`
- Modify: M13 settlement integration tests for multi-attempt lineage.

**Interfaces:**
- Close considers route-attempt safety/history plus existing Usage/Settlement/Reservation facts.
- Client replay never invokes `prepareNextSafe`.

- [ ] **Step 1: Write RED replay matrix**

Tests:

```text
same idempotency key while safe failover executing → converges/in-progress, no parallel Provider
replay after all-SAFE terminal failure → no Provider call
replay after BILLABLE_POSSIBLE → no Provider call
replay after completed B → existing response-not-retained semantics
```

- [ ] **Step 2: Write RED process-gap recovery fixtures**

Persist DB states representing:

```text
SAFE + ACTIVE reservation (crash before release)
SAFE + RELEASED (crash before next route)
PLANNED + ACTIVE reservation (crash before TX2)
BILLABLE_POSSIBLE + expired ACTIVE reservation
```

Expected:

```text
first → RELEASED, no Provider
second → terminal no-charge convergence, no Provider
third → pre-dispatch release/FAILED_PRE_DISPATCH, no Provider
fourth → PENDING_HOLD, no Provider
```

- [ ] **Step 3: Write RED real-MySQL two-worker attempt race**

Use barriers/latches and separate transactions to prove two workers cannot create two attempt 2 rows or two effective Reservations.

- [ ] **Step 4: Write RED Close matrix**

Backend test cases:

```text
SAFE attempt + RELEASED hold + no usage → does not block
SAFE A + completed/settled B + FINALIZED B hold → does not block
BILLABLE_POSSIBLE current attempt + no final settlement → blocks
ACTIVE/PENDING_HOLD any attempt → blocks
```

- [ ] **Step 5: Refine Close query using route safety, not request state alone**

Do not globally exempt a post-dispatch request merely because one historical attempt is SAFE. Close exemption requires no unresolved possible-billable attempt and no effective hold.

- [ ] **Step 6: Run focused Gateway + Backend real-MySQL suites GREEN**

```powershell
Set-Location "E:\project\AI-CostOps\gateway"
.\mvnw.cmd -Dit.test=GatewayFailoverConcurrencyIntegrationTest,GatewayRequestIdempotencyIntegrationTest,ReservationRecoveryIntegrationTest verify

Set-Location "E:\project\AI-CostOps\backend"
.\mvnw.cmd -Dit.test=GatewayFinancialWorkCloseIntegrationTest verify
```

- [ ] **Step 7: Commit**

```powershell
Set-Location "E:\project\AI-CostOps"
git add gateway/src/test backend/src/main/java/com/aicostops/reconciliation backend/src/test/java/com/aicostops/reconciliation backend/src/test/java/com/aicostops/gatewaysettlement
git commit -m "test(m14): prove failover replay and financial concurrency"
```

---

### Task 13: Complete dev provisioning, observability, security and architecture guards

**Files:**
- Modify: `backend/src/main/java/com/aicostops/gatewayadmin/infrastructure/DevGatewayBootstrap.java`
- Modify: `backend/src/main/java/com/aicostops/gatewayadmin/infrastructure/GatewayAdminMapper.java`
- Modify: dev bootstrap tests.
- Modify: `gateway/src/main/java/com/aicostops/gateway/observability/GatewayMetrics.java`
- Modify: gateway architecture/security tests.
- Modify: configuration examples/docs used by current dev setup.

**Interfaces:**
- Dev bootstrap always ensures a MiMo org-default routing policy for its current route.
- Optional OpenAI local provisioning is enabled only by explicit dev config and never requires a committed secret.
- Metrics labels are bounded by adapter/reason/state, never DB ids.

- [ ] **Step 1: Write RED dev bootstrap migration test**

With only the existing MiMo dev config, assert bootstrap creates/resolves an ACTIVE org-default routing policy with one MiMo candidate.

With explicit OpenAI dev config fixture, assert a second candidate can be provisioned without logging the secret.

- [ ] **Step 2: Write RED metrics/security architecture tests**

Assert metric names/label keys:

```text
gateway_routing_decision_total(adapter_code, reason)
gateway_candidate_rejection_total(reason)
gateway_provider_safety_total(adapter_code, outcome, reason)
gateway_failover_total(outcome, reason)
gateway_circuit_transition_total(adapter_code, from, to, reason)
gateway_circuit_redis_error_total(operation)
```

Reject labels named `request_id`, `provider_account_id`, `provider_model_id`, `project_id`.

- [ ] **Step 3: Implement genericized dev bootstrap helpers**

Keep MiMo defaults backward compatible. OpenAI values must be explicit config inputs; do not hardcode a production-required OpenAI model name or secret.

- [ ] **Step 4: Implement metrics and bounded logging**

Record candidate rejection/safety/failover/circuit transitions. Error logs may include safe public request/route decision ids but never prompt, completion, encrypted/decrypted credential or arbitrary Provider body.

- [ ] **Step 5: Run Gateway/Backend architecture and security-focused suites**

```powershell
Set-Location "E:\project\AI-CostOps\gateway"
.\mvnw.cmd test
.\mvnw.cmd verify

Set-Location "E:\project\AI-CostOps\backend"
.\mvnw.cmd test
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
Set-Location "E:\project\AI-CostOps"
git add backend/src/main/java/com/aicostops/gatewayadmin backend/src/test gateway/src/main/java/com/aicostops/gateway/observability gateway/src/test docs
git commit -m "feat(m14): harden routing operations and observability"
```

---

### Task 14: Full product E2E and acceptance evidence

**Files:**
- Create/modify: `frontend/e2e/routing-policies.spec.ts`
- Create: `docs/03-acceptance/m14-multi-provider-routing-resilience-evidence.md`
- Modify: any existing smoke/UAT script only when necessary to exercise M14 through supported public/admin APIs.

**Interfaces:**
- Final evidence records exact implementation SHA, test commands/results, migration diff, browser UAT, hosted CI/Security runs after PR push.

- [ ] **Step 1: Run complete local backend verification**

```powershell
Set-Location "E:\project\AI-CostOps\backend"
.\mvnw.cmd test
.\mvnw.cmd verify
```

Expected: zero failures/errors. Record counts in evidence.

- [ ] **Step 2: Run complete local Gateway verification**

```powershell
Set-Location "E:\project\AI-CostOps\gateway"
.\mvnw.cmd test
.\mvnw.cmd verify
```

Expected: zero failures/errors. Record counts.

- [ ] **Step 3: Run complete frontend verification**

```powershell
Set-Location "E:\project\AI-CostOps\frontend"
npm test -- --run
npm run lint
npm run build
npm run test:e2e
```

Expected: PASS.

- [ ] **Step 4: Run repository hygiene and migration immutability checks**

```powershell
Set-Location "E:\project\AI-CostOps"
git diff --check
git status --short
git diff origin/main -- backend/src/main/resources/db/migration
```

Expected:

```text
git diff --check clean
only V22 added in migration diff
V1-V21 untouched
```

- [ ] **Step 5: Execute explicit M14 UAT scenarios**

At minimum prove through browser/API + mock Provider fixture:

```text
routing policy create/revision/activation
project exact override
MiMo primary success
OpenAI primary success
A SAFE pre-connect → B success
A BILLABLE_POSSIBLE timeout/5xx → no B
OPEN candidate skipped
HALF_OPEN single probe behavior
price-changing fresh reservation
currency-changing no-FX rejection
client replay cannot force B
all-SAFE released chain does not block Close
billable uncertainty does block Close
```

- [ ] **Step 6: Write acceptance evidence with exact facts, not claims**

`docs/03-acceptance/m14-multi-provider-routing-resilience-evidence.md` must contain:

```text
base SHA
implementation SHA(s)
V22 schema facts
policy/API/UI DoD
MiMo/OpenAI certification matrix
SAFE/BILLABLE failure matrix with test names
circuit tests
budget/re-reservation tests
replay/concurrency/Close tests
local backend/gateway/frontend results
migration diff proof
secret/content redaction proof
remaining non-scope: FX, hedging, smart routing, client Provider choice
```

Do not include Provider secrets or raw prompt/completion fixtures in evidence.

- [ ] **Step 7: Commit evidence**

```powershell
git add docs/03-acceptance/m14-multi-provider-routing-resilience-evidence.md frontend/e2e
git commit -m "docs(m14): add routing resilience acceptance evidence"
```

---

### Task 15: Final branch verification before PR handoff

**Files:**
- No new product files unless verification exposes a defect; fixes require their own RED/GREEN commit before this task can finish.

**Interfaces:**
- Produces a clean feature branch ready for independent Sol review and hosted CI/Security.

- [ ] **Step 1: Rebase/merge-base sanity check without rewriting published history unexpectedly**

```powershell
Set-Location "E:\project\AI-CostOps"
git fetch origin --prune
git merge-base HEAD origin/main
git log --oneline --decorate origin/main..HEAD
```

Expected: branch contains only M14 design/plan/implementation/evidence commits on the verified main lineage.

- [ ] **Step 2: Run final full verification again after the last commit**

```powershell
Set-Location "E:\project\AI-CostOps\backend"
.\mvnw.cmd test
.\mvnw.cmd verify

Set-Location "E:\project\AI-CostOps\gateway"
.\mvnw.cmd test
.\mvnw.cmd verify

Set-Location "E:\project\AI-CostOps\frontend"
npm test -- --run
npm run lint
npm run build
```

Expected: PASS.

- [ ] **Step 3: Self-review the implementation against the design spec**

Explicitly verify every Section 50 Definition-of-Done item in the spec has a concrete test/evidence entry. In particular search for forbidden shortcuts:

```powershell
Set-Location "E:\project\AI-CostOps"
git grep -n "\.retry(" -- gateway/src/main/java
git grep -n "M11_PROVIDER_CODE" -- gateway/src/main/java
git grep -n "providerKeyHeader" -- gateway/src/main/java
git grep -n "routing_policy" -- backend/src/main/resources/db/migration/V1* backend/src/main/resources/db/migration/V2[01]*
```

Expected:

```text
no automatic Provider retry operator
no hardcoded M11 provider route
no generic providerKeyHeader
no routing-policy edits in V1-V21
```

- [ ] **Step 4: Verify no placeholder debt entered plan-driven implementation**

```powershell
git grep -n -E "TODO|TBD|FIXME|implement later|placeholder" -- backend gateway frontend docs/03-acceptance
```

Review every match; M14-added code/evidence must not contain unresolved implementation placeholders.

- [ ] **Step 5: Push feature branch and open one final PR**

Use the M14 issue number created for this runtime work in the PR body. Do not invent an AIC number.

PR body must summarize:

```text
routing policy/admin UI
OpenAI second adapter
circuit
safety classifier
safe failover + re-reservation
M13/Close continuity
local verification
```

- [ ] **Step 6: Wait only for actual hosted CI/Security results, then record them in evidence through a normal commit if the project evidence convention requires it**

Do not claim hosted green from local tests.

- [ ] **Step 7: Stop for independent Sol final review**

Do **not** merge. The merge gate is:

```text
hosted CI green
hosted Security green
independent Sol code review green
explicit user authorization: 合并 / merge
```

Only then may the PR be squash-merged.

---

## Plan Self-Review

### Spec coverage

This plan has explicit tasks for every M14 design area:

```text
schema/backfill                  → Task 1
Control Plane API/audit          → Task 2
frontend admin                   → Task 3
policy resolution/static router  → Task 4
adapter registry/provider seam   → Task 5
OpenAI adapter                   → Task 6
circuit/Redis/local fallback     → Task 7
attempt/admission/fence/release  → Task 8
initial routing orchestration    → Task 9
non-stream safe failover         → Task 10
stream safe failover/M13 usage   → Task 11
replay/crash/Close/concurrency   → Task 12
bootstrap/metrics/security       → Task 13
E2E/evidence                     → Task 14
full final verification          → Task 15
```

The subsystems are intentionally one plan because they are not independent deliverables: automatic failover is unsafe without the routing schema, attempt state refactor, reservation release, provider safety classification and M13 continuity. The user explicitly requested one complete M14 delivery.

### Placeholder scan

The plan contains no implementation `TODO`, `TBD`, “implement later”, or undefined follow-up stage. Any word such as “placeholder” appears only in the final grep guard that rejects placeholder debt.

### Type consistency

Stable names used throughout the plan:

```text
ProviderChatAdapterRegistry
ProviderSafetyOutcome
ProviderSafetyReason
ProviderHealthSignal
ProviderExecutionException
RouteCircuitKey
CircuitBreakerService
RouteAttemptCoordinator
GatewayRequestOrchestrator
SafeReservationReleaseService
ResolvedRoutingPolicy
RoutingPolicyResolver
CandidateEligibilityEvaluator
DeterministicRouteSelector
```

Do not rename one of these in a later task without updating all consumers/tests in the same commit.
