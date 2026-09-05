# M14 Multi-provider Routing / Resilience Design

> Status: **APPROVED DESIGN BASELINE**  
> Date: 2026-09-05  
> Baseline: `main@3eee76de4dc5a366f2dcbe0228e0f23159e12d47`  
> Depends on: M11 Gateway Edge + M12 Identity / Attribution / Budget Reservation + M13 Metering / Settlement  
> Delivery: **one complete M14 implementation milestone, one feature branch, one final runtime PR**

## 1. Goal

M14 turns the M11-M13 single-MiMo data plane into deterministic, auditable multi-Provider routing with bounded health-aware selection and evidence-based safe failover without weakening the financial invariants already proved by M12/M13.

Final M14 behavior:

```text
AI-CostOps logical model
→ exact project routing policy, else org-default policy
→ deterministic ordered Provider candidates
→ static/capability/privacy/configuration eligibility
→ runtime circuit filtering
→ frozen Provider Account / Provider Model / Pricing Version
→ per-attempt Budget admission / Reservation
→ durable DISPATCH_INTENT
→ exactly one Provider call for that attempt
→ success
   OR positively-proven SAFE_NO_BILLABLE_EXECUTION
   OR conservative BILLABLE_POSSIBLE
→ only SAFE may release the old Reservation and advance to the next candidate
→ M13 usage + settlement continue against the actual billable/completed attempt
```

M14 optimizes in this order:

```text
correctness
→ policy compliance
→ predictable behavior
→ auditability
→ availability
→ cost/latency optimization later
```

The governing invariant remains:

> A new Provider attempt is legal only when every earlier attempt for the Gateway Request is durably `SAFE_NO_BILLABLE_EXECUTION` and no earlier effective Reservation remains. Unknown safety is `BILLABLE_POSSIBLE` and stops automatic failover.

---

## 2. Delivery shape

The product request is one complete M14 delivery, not separate M14-A/B/C milestones.

Implementation is still internally ordered by dependency:

```text
schema + routing administration
→ deterministic static router
→ Provider adapter registry + OpenAI realtime adapter
→ health/circuit coordination
→ transport safety classification
→ SAFE transition + Reservation release
→ bounded next-attempt orchestration
→ streaming/non-streaming safe failover
→ M13/Close/idempotency/concurrency proof
→ frontend routing policy administration
→ full CI/Security/UAT evidence
```

No intermediate state is considered M14 complete. Dangerous behavior such as automatic failover is not enabled until its preceding safety pieces and tests exist on the feature branch.

---

## 3. Existing truth that M14 must reuse

M14 starts from the merged M13 baseline and reuses:

```text
gateway_request
gateway_route_attempt
budget_reservation
gateway_usage_fact
gateway_usage_dimension
gateway_settlement
pricing_version / pricing_rate
provider_catalog / provider_model / provider_account / provider_credential
model_catalog / gateway_credential_model
billing_period / budget / ledger
```

Existing correctness seams remain authoritative:

```text
RequestIdentityService
BlockingIoScheduler
BudgetReservation / Budget lock rules
DispatchFenceService
GatewayUsageFinalizationService
StreamingLifecycleService
ReservationRecoveryService
BillingPeriod Close serialization
M13 Settlement + Ledger + Budget Actual transaction
Gateway Close blocker framework
```

M14 must not:

```text
edit V1-V21 migrations
move financial truth to Redis
introduce FX
introduce parallel billable hedging
introduce opaque scoring / RL / routing DSL
allow clients to select Provider credentials/endpoints/raw Provider model ids
retry a Provider merely because of timeout / 429 / 5xx
reuse or retarget an old Reservation for a new route
re-price a historical Route Attempt
create background Provider calls after the client request process has died
replace M13 one-request final settlement semantics
persist prompts/completions/reasoning for routing observability
expose Provider secrets in APIs, UI, logs, metrics or route facts
```

---

## 4. Current implementation gaps M14 must deliberately correct

### 4.1 Hardcoded single Provider

`GatewayRequestService` currently resolves only `MIMO`, and `ChatCompletionController` injects one `ProviderChatAdapter` directly. M14 removes the hardcoded Provider code and introduces policy-driven candidate selection plus an adapter registry.

### 4.2 Provider-specific authentication leaks into the generic layer

`ProviderCallContext` currently contains `providerKeyHeader`, and `ChatCompletionController` constructs a context assuming `API_KEY` / `api-key` semantics. M14 removes the header name from the generic context. Each Adapter owns its authentication wire format.

### 4.3 Attempt 1 is hardcoded

`GatewayRequestMapper` currently inserts `attempt_no=1` and reads `findFirstAttempt`. M14 adds generic append-only attempt allocation and current-attempt convergence.

### 4.4 Current lifecycle prematurely marks BILLABLE_POSSIBLE

`GatewayRequestLifecycleService.beginUpstream()` currently changes the attempt to `BILLABLE_POSSIBLE` before Provider I/O. That was safe for M11-M13 because automatic retry was forbidden, but it makes M14 positive pre-write safety impossible to represent.

M14 changes the lifecycle rule to:

```text
TX2 commits attempt DISPATCH_INTENT
→ request may move to UPSTREAM_ACTIVE
→ attempt remains DISPATCH_INTENT while transport is unresolved
→ terminal Provider evidence decides:
     COMPLETED
     SAFE_NO_BILLABLE_EXECUTION
     BILLABLE_POSSIBLE
```

Unknown or failed classification is always `BILLABLE_POSSIBLE`.

### 4.5 Candidate budget failure currently terminates the whole request

M12 `BudgetReservationService` correctly marks single-route budget rejection as `REJECTED_BUDGET`. With multiple routes this cannot happen on the first candidate, because another candidate may have different price/currency and a legal Budget.

M14 separates:

```text
candidate admission failure
!=
whole-request budget rejection
```

Candidate-level admission failure is a durable pre-Provider SAFE route outcome. The request becomes `REJECTED_BUDGET` only after deterministic candidate exhaustion establishes that no eligible route can be financially admitted.

---

# Part A — Durable Routing Policy

## 5. V22 migration

M14 adds exactly one schema migration unless implementation discovers a pre-existing V22 on a newer verified main:

```text
backend/src/main/resources/db/migration/V22__m14_multi_provider_routing.sql
```

V22 creates:

```text
routing_policy
routing_policy_candidate
```

and evolves `gateway_route_attempt` by adding:

```text
route_reason_code VARCHAR(64) NULL
FK (routing_policy_id, org_id) -> routing_policy(id, org_id)
```

Historical M11-M13 attempts keep `routing_policy_id=NULL` and `route_reason_code=NULL`. Every new M14 runtime attempt must carry a policy id and bounded route reason.

Do not overload `safety_reason_code`: routing selection reason and execution-safety reason are different facts.

### 5.1 `routing_policy`

Logical contract remains the M10-frozen schema:

```text
id            BIGINT AUTO_INCREMENT PK
org_id        BIGINT NOT NULL
project_id    BIGINT NULL
model_id      BIGINT NOT NULL
version       INT NOT NULL
status        VARCHAR(32) NOT NULL
created_at    DATETIME(6) NOT NULL
activated_at  DATETIME(6) NULL
```

Statuses:

```text
DRAFT
ACTIVE
RETIRED
```

Same-org integrity:

```text
UNIQUE(id, org_id)
FK org_id -> organization
FK (project_id, org_id) -> project(id, org_id) when project_id is non-null
FK model_id -> model_catalog
```

Because MySQL nullable UNIQUE semantics do not prevent duplicate org-default rows, V22 uses physical generated scope helpers (same pattern as the M12 effective-slot technique):

```text
project_scope_key = COALESCE(project_id, 0)
active_slot = CASE WHEN status='ACTIVE' THEN 1 ELSE NULL END
```

with uniqueness that enforces both:

```text
one version number per exact org/project/model scope
at most one ACTIVE policy per exact org/project/model scope
```

The logical API still exposes `project_id=NULL` for org-default policy; helper columns are storage enforcement only.

### 5.2 `routing_policy_candidate`

Frozen logical contract:

```text
id                    BIGINT AUTO_INCREMENT PK
org_id                BIGINT NOT NULL
routing_policy_id     BIGINT NOT NULL
provider_account_id   BIGINT NOT NULL
provider_model_id     BIGINT NOT NULL
priority              INT NOT NULL
status                VARCHAR(32) NOT NULL
privacy_region_code   VARCHAR(64) NULL
created_at            DATETIME(6) NOT NULL
```

Constraints:

```text
UNIQUE(routing_policy_id, provider_account_id, provider_model_id)
CHECK priority >= 0
CHECK status IN ('ACTIVE','DISABLED')
FK (routing_policy_id, org_id) -> routing_policy(id, org_id)
FK (provider_account_id, org_id) -> provider_account(id, org_id)
FK provider_model_id -> provider_model(id)
```

Activation validates that the Provider Account's `provider_code` matches the selected Provider Model's Provider code. This cross-table business invariant is transactionally validated because the global `provider_model` row is not org-owned.

### 5.3 Backward-compatible policy backfill

A production deployment must not silently turn an already-working single-MiMo Gateway into “no route”. V22 therefore backfills an org-default version-1 ACTIVE policy only for an org/model pair for which the pre-M14 runtime already had a currently eligible MiMo route.

The backfill preserves the old behavior by selecting the single MiMo route that the old runtime would have selected (the deterministic first currently-active pricing-backed route) and inserting it as priority 0.

No policy is invented for org/model pairs that did not have a valid old route.

---

## 6. Policy immutability and activation

`ACTIVE` and `RETIRED` policy versions and their candidate sets are immutable.

Changes use:

```text
ACTIVE vN
→ create/clone DRAFT vN+1
→ edit DRAFT candidates
→ activate vN+1 atomically
→ prior exact-scope ACTIVE becomes RETIRED
```

Version is server-assigned, monotonic per exact scope. Clients do not choose version numbers.

Activation transaction:

```text
lock organization row (serializes low-frequency routing administration per org)
→ validate project/model ownership and state
→ validate DRAFT policy + candidate same-org/provider-model relationships
→ require >= 1 ACTIVE candidate
→ validate candidate runtime configuration is structurally provisioned
→ RETIRE current exact-scope ACTIVE if present
→ ACTIVATE target DRAFT
→ commit
```

DB unique constraints remain the final convergence authority for concurrent activation.

A policy already referenced by any Route Attempt is never destructively changed or deleted.

---

## 7. Policy resolution precedence

For one Gateway Request and logical model:

```text
1. exact ACTIVE policy for (org, project, logical model)
2. only when no exact project policy exists: ACTIVE org-default policy
   (org, project=NULL, logical model)
3. if neither exists: fail closed before Provider I/O
```

Critical compliance rule:

> If an exact project policy exists but all of its candidates are currently ineligible or OPEN, the runtime does **not** fall back to the org-default policy. Doing so would bypass the project's explicit policy.

The policy chosen for attempt 1 is frozen for the entire Gateway Request. Safe failover attempts continue using the same `routing_policy_id` / version even if another policy version becomes ACTIVE concurrently.

---

## 8. Routing administration API and UI

M14 includes a bounded operational Control Plane so routing is not database-only configuration.

Authorization reuses existing conservative ORG-scoped permissions:

```text
PROVIDER_ACCOUNT_READ    → read routing policies/options
PROVIDER_ACCOUNT_MANAGE  → create/edit/activate routing policies
```

M14 does not invent a new permission family.

Backend APIs:

```text
GET  /api/v1/routing-policies
GET  /api/v1/routing-policies/{id}
POST /api/v1/routing-policies
POST /api/v1/routing-policies/{id}/revisions
PUT  /api/v1/routing-policies/{id}
POST /api/v1/routing-policies/{id}/activate
GET  /api/v1/routing-options?modelId=...
```

Rules:

```text
POST creates a DRAFT
revision endpoint clones immutable policy/candidates into next DRAFT version
PUT replaces only DRAFT candidate configuration
activate is the only path to ACTIVE
no API ever returns Provider ciphertext/nonce/decrypted secret
routing-options is a safe projection only
```

The frontend adds:

```text
/settings/routing-policies
```

under Settings. It provides Chinese-localized policy scope/model/version/status views, ordered candidate editing, provider/model readiness warnings, draft revision and activation. ACTIVE history is read-only.

M14 does not add a Provider secret editor or generic pricing editor. Provider catalog/model/pricing/credential provisioning remains server-governed; routing administration chooses only among provisioned safe projections.

`privacy_region_code` is retained and editable as policy-governed candidate metadata. There is currently no frozen client/request field that supplies an independent “required region”, so M14 does not fabricate one. Presence of a candidate in the ACTIVE policy is the current operator compliance decision; future explicit region requirements may filter this metadata without schema replacement.

---

# Part B — Deterministic Data-plane Routing

## 9. Candidate ordering

Within the frozen policy:

```text
ACTIVE candidates
ORDER BY priority ASC, id ASC
```

No randomization, adaptive scoring, cost sorting, latency sorting or replica-local tie-break exists in M14 Core.

Each `(provider_account_id, provider_model_id)` candidate may be attempted at most once per Gateway Request. This bounds the entire safe-failover chain by the policy candidate count.

---

## 10. Candidate eligibility

The runtime evaluates every candidate against bounded explicit conditions:

```text
same organization
policy/candidate ACTIVE
Provider Catalog ACTIVE
Provider Account ACTIVE
active Provider Credential exists
Provider Model ACTIVE and routing_eligible=true
Provider Model maps to the same logical model
Adapter exists in ProviderChatAdapterRegistry
CHAT_COMPLETIONS capability supported
SSE_STREAMING supported when client asks stream=true
Pricing Version resolvable for this Provider Account/Model at decision time
candidate not already attempted by this Gateway Request
Circuit not OPEN; HALF_OPEN requires probe lease
```

Budget admission is a transactional eligibility step and therefore occurs only after a concrete candidate has been planned as a Route Attempt.

Ineligible candidates that are rejected before a Route Attempt is planned are observed only through bounded reason metrics/logs, not giant durable decision payloads.

---

## 11. Route reason facts

`route_reason_code` is a fixed bounded enum:

```text
INITIAL_PRIMARY
INITIAL_FALLBACK
SAFE_FAILOVER
```

`safety_reason_code` is separately bounded by the safety classifier, for example:

```text
BUDGET_NO_MATCH_PRE_PROVIDER
BUDGET_INSUFFICIENT_PRE_PROVIDER
BUDGET_BOUND_UNSAFE_PRE_PROVIDER
LOCAL_PRE_NETWORK_FAILURE
DNS_PRE_CONNECT
CONNECT_REFUSED_PRE_WRITE
CONNECT_TIMEOUT_PRE_WRITE
TLS_HANDSHAKE_PRE_HTTP_WRITE
HTTP_RESPONSE_RECEIVED
HEADER_TIMEOUT_WRITE_POSSIBLE
READ_TIMEOUT
STREAM_TIMEOUT
CONNECTION_RESET_WRITE_POSSIBLE
MALFORMED_PROVIDER_RESPONSE
CLIENT_CANCEL_AFTER_DISPATCH
UNKNOWN_POST_DISPATCH
```

The Java domain uses enums / validated mapping. Unknown transport evidence maps to `BILLABLE_POSSIBLE` with `UNKNOWN_POST_DISPATCH`, never to SAFE.

---

## 12. Attempt planning and request-level state

`gateway_route_attempt` remains the append-only authority. `gateway_request.current_route_attempt_id` is only a convenience pointer.

Planning a candidate:

```text
create PLANNED attempt with:
  attempt_no
  route_decision_id
  routing_policy_id
  route_reason_code
  provider_account_id
  provider_model_id
  pricing_version_id
→ update current_route_attempt_id
```

Attempt allocation is serialized by the Gateway Request row and protected by the existing unique `(org_id, request_id, attempt_no)` constraint. A concurrent duplicate converges rather than creating parallel attempts.

Request-level state does not move backward merely because a prior route became SAFE.

During a live safe-failover chain:

```text
request may remain DISPATCH_INTENT / UPSTREAM_ACTIVE
while a new PLANNED/RESERVED attempt is prepared
```

If candidate exhaustion proves that every attempt is SAFE and every Reservation is RELEASED (there is no possible billable execution), the request terminates in the existing `FAILED_PRE_DISPATCH` state. The wording means “failed before any billable Provider execution”, even if a durable dispatch intent existed for a route whose transport was later positively proved never to have reached billable execution.

This avoids adding a competing request-level financial truth; the authoritative safety evidence remains on Route Attempts.

---

# Part C — Budget Admission and Dispatch Fences

## 13. Per-candidate admission

M14 preserves the M12 TX1 financial lock order:

```text
BillingPeriod
→ Budget
→ Reservation write
```

and adds request/attempt coordination without reversing financial locks.

A selected candidate first gets a PLANNED attempt because a Reservation is per Route Attempt.

Candidate admission outcome:

```text
RESERVED
UNBUDGETED (only existing OPTIONAL semantics)
REJECTED_BUDGET
REJECTED_DEPENDENCY
```

M14 changes ownership of terminal request rejection:

```text
BudgetReservationService / admission transaction
→ returns candidate outcome
→ never permanently rejects the whole request merely because this candidate failed

Routing/orchestration layer
→ may try the next deterministic candidate because no Provider I/O occurred
→ only after candidate exhaustion decides final request error/state
```

For a candidate rejected before Provider I/O:

```text
attempt PLANNED
→ SAFE_NO_BILLABLE_EXECUTION
safety_reason_code = bounded budget/pre-provider reason
```

No Reservation exists in a rejected admission.

On successful admission, the existing first-request semantics are retained:

```text
VALIDATED → RESERVED
```

For a later safe-failover attempt the request is not moved backward to RESERVED; the new attempt and Reservation carry the new financial context.

---

## 14. BillingPeriod continuity

The first successful financial admission fixes `gateway_request.billing_period_id` as today.

A later safe failover:

```text
reuses the same request BillingPeriod
resolves fresh Provider/model/Pricing Version for the new candidate
reserves against a Budget in the new pricing currency within that same BillingPeriod
```

It does not silently migrate one logical request to a newer BillingPeriod.

If that original period is no longer OPEN when a fresh M14 admission/dispatch fence is attempted, no new Provider attempt is sent. `CLOSING` is transient contention but does not authorize bypass; `CLOSED` also forbids a fresh dispatch. If all earlier attempts are SAFE, the request can terminate with no billable work.

---

## 15. TX2 per attempt

Every potentially billable Provider attempt must have its own committed dispatch fence.

TX2 remains:

```text
lock same BillingPeriod FOR UPDATE
→ verify it is OPEN
→ verify the selected attempt is current and PLANNED
→ verify matching ACTIVE Reservation when RESERVED
   or the existing explicitly-allowed OPTIONAL-unbudgeted outcome
→ mark route attempt DISPATCH_INTENT
→ for first attempt, move request to DISPATCH_INTENT and set first dispatch_intent_at
→ for later safe-failover attempts, keep the already-forward request state/timestamp
→ COMMIT
→ only now Provider I/O is legal
```

No Provider call runs inside a DB transaction.

---

# Part D — Provider Adapter Boundary

## 16. Adapter registry

`ProviderChatAdapter` gains a stable adapter identity, and runtime injection changes from a single adapter to:

```text
ProviderChatAdapterRegistry
  MIMO   -> MimoChatAdapter
  OPENAI -> OpenAiChatAdapter
```

Startup fails fast on duplicate adapter codes.

A routing candidate whose `provider_catalog.adapter_code` has no registered Adapter is ineligible.

Router never performs Provider I/O. Adapter never selects Budget, releases Reservation, creates Route Attempts or posts financial truth.

---

## 17. ProviderCallContext

Generic context becomes provider-neutral:

```text
adapterCode
providerAccountId
providerModelId
providerModelName
pricingVersionId
currency
baseUrl
credentialType
providerSecret
routeDecisionId (safe correlation identity)
```

Remove `providerKeyHeader`.

Adapters validate credential type:

```text
MiMo   -> API_KEY -> api-key header
OpenAI -> BEARER_TOKEN -> Authorization: Bearer
```

The decrypted secret remains ephemeral, never logged, never included in `toString()`, metrics, exception messages or persisted route facts.

---

## 18. Second realtime Provider: OpenAI

M14 certifies OpenAI as the second Gateway realtime Provider Adapter.

Reasons for selection, verified against current official OpenAI API documentation during M14 design:

```text
Chat Completions API matches the existing Gateway public surface
Bearer authentication exercises the provider-neutral credential boundary
streaming uses SSE
stream_options.include_usage provides a terminal usage chunk before [DONE]
interrupted streams may lack terminal usage, which M13 already models conservatively
x-request-id is available for support correlation
X-Client-Request-Id can be supplied for correlation even when a response id is unavailable
```

OpenAI wire behavior:

```text
POST {baseUrl}/chat/completions
Authorization: Bearer <ephemeral decrypted secret>
stream=true → add stream_options.include_usage=true server-side
X-Client-Request-Id: <bounded route decision identity>
```

The Adapter uses the DB-frozen `provider_model_name`; M14 does not hardcode a live OpenAI model name into routing logic.

Provider response/request IDs are correlation evidence only. They are never treated as proof that billing did or did not occur.

Tests use a local/mock upstream; no real OpenAI secret is committed or required by normal CI.

Production endpoint policy allows only the certified OpenAI HTTPS endpoint for the OPENAI adapter. Test/dev profiles may use bounded local mock endpoints.

---

## 19. Provider-specific safety contract

Adapters surface a typed terminal execution failure rather than collapsing everything to `GatewayErrorException`.

Conceptual contract:

```text
ProviderExecutionFailure
  safetyOutcome:
    SAFE_NO_BILLABLE_EXECUTION
    BILLABLE_POSSIBLE
  safetyReasonCode
  healthSignal
  providerRequestId?        // safe bounded correlation only
  httpStatus?               // bounded integer only
  responseStarted
  internal cause            // not returned/logged with secrets
```

Success is represented by normal completion/stream events. Safety classification is only for failure paths.

The orchestrator never derives SAFE from a generic exception after the Adapter has already erased transport phase evidence.

---

## 20. Retry-safety matrices

Both MiMo and OpenAI ship tested matrices.

The only automatically-failover-eligible classes in M14 Core are positive pre-write/pre-HTTP proofs such as:

```text
local failure before network participation
DNS resolution failure before connection
TCP connection refused before request bytes
connect timeout before request bytes
TLS handshake failure before HTTP request bytes
```

A Provider-specific status is SAFE only when official/current Provider contract plus tests positively guarantee no inference execution. M14 does not currently certify any HTTP response status as SAFE for OpenAI or MiMo.

Therefore these remain `BILLABLE_POSSIBLE` by default:

```text
any HTTP response after request dispatch, including 429 / 5xx
response-header timeout when write may have started
read timeout
stream idle/hard timeout
connection reset after write may have started
malformed Provider response after processing may have occurred
client disconnect/cancel after dispatch
unknown transport failure after DISPATCH_INTENT
```

A failed classifier or unknown throwable is `BILLABLE_POSSIBLE`.

No `WebClient.retry(...)` / Reactor retry operator is allowed around billable Provider calls.

---

# Part E — Health-aware Routing / Circuit Breaker

## 21. Circuit key and state

Circuit state is operational runtime coordination, not financial truth.

Key scope:

```text
(org_id, provider_account_id, provider_model_id)
```

This is intentionally narrower than Provider-global health because credentials/accounts/models can fail independently.

States:

```text
CLOSED
OPEN
HALF_OPEN
```

Redis key namespace:

```text
aicostops:gateway:circuit:v1:<orgId>:<providerAccountId>:<providerModelId>
```

No request id, prompt, completion or secret is stored.

---

## 22. Circuit algorithm

Validated GatewayProperties defaults:

```text
failure threshold: 5 consecutive qualifying failures
OPEN duration: 30 seconds
HALF_OPEN probe lease: 15 seconds
```

These are bounded runtime configuration, not routing-policy DSL fields.

Qualifying health failures for future selection include bounded classes:

```text
pre-connect/connect/TLS failures
Provider 429 pressure
Provider 5xx
Provider timeout
Provider protocol/malformed response
credential/model/endpoint rejection that clearly makes this route unusable for future calls
```

Normal prompt/request validation errors and client cancellation do not count as Provider-health failures.

401/403/route-level 404 configuration rejection may open the candidate immediately because repeating the same server-governed route is predictably unhealthy; this still has no effect on the current attempt's conservative billing-safety outcome.

Successful Provider completion closes/resets the circuit.

Circuit state affects only future candidate selection. It never changes an already-created attempt, Reservation, Usage Fact, Settlement or Ledger entry.

---

## 23. HALF_OPEN coordination

After OPEN duration:

```text
one replica acquires a Redis HALF_OPEN probe lease
→ one normal user request may select that route
→ no synthetic billable health-check request is generated
→ success closes
→ qualifying failure reopens
```

Other replicas treat HALF_OPEN without the lease as temporarily ineligible.

A HALF_OPEN probe is a normal billable attempt and follows the same dispatch/safety/settlement rules. It is never assumed safe because it is a probe.

---

## 24. Redis outage behavior

Redis circuit coordination loss must not become financial corruption.

Circuit service therefore degrades to a bounded local in-memory breaker per replica when Redis is unavailable. This may reduce fleet-wide routing quality but does not change:

```text
Provider attempt safety
Reservation correctness
Usage/Settlement/Ledger truth
```

Circuit Redis dependency errors are measured with bounded labels. M14 does not fail open around Budget, Pricing, Credential, policy or privacy requirements.

---

# Part F — SAFE Failover State Machine

## 25. Terminal attempt transitions

Allowed forward transitions:

```text
PLANNED
  → DISPATCH_INTENT
  → SAFE_NO_BILLABLE_EXECUTION   // local/budget pre-provider rejection may also PLANNED → SAFE

DISPATCH_INTENT
  → SAFE_NO_BILLABLE_EXECUTION   // positive transport pre-write evidence
  → BILLABLE_POSSIBLE            // uncertainty / post-write possibility
  → COMPLETED                    // successful provider transport + durable M13 finalization path

BILLABLE_POSSIBLE
  → COMPLETED                    // existing convergence when exact success evidence is finalized
```

No state goes backward.

`beginUpstream` may advance only the request to `UPSTREAM_ACTIVE`; it does not pre-label the attempt billable.

---

## 26. Safe failover sequence

For an attempt with positive SAFE evidence:

```text
1. persist attempt SAFE_NO_BILLABLE_EXECUTION + safety_reason_code
2. record circuit/health signal for future routing
3. if an ACTIVE Reservation exists:
     release it under BillingPeriod → Budget → Reservation lock order
4. verify no ACTIVE/PENDING_HOLD Reservation remains for the request
5. select the next not-yet-attempted candidate from the same frozen policy
6. resolve fresh Pricing Version at the new route-decision time
7. create new PLANNED attempt
8. run fresh candidate budget admission using the new currency/price
9. commit that attempt's TX2 DISPATCH_INTENT
10. call exactly that Adapter once
```

Provider A's Reservation is never resized/retargeted/reused for Provider B.

If candidate B has a different currency, the same existing Budget-selection semantics run in B's currency. No FX exists. If a REQUIRED budget cannot safely reserve B, B becomes a SAFE pre-provider rejected attempt and deterministic selection may continue to C.

---

## 27. Reservation release and crash convergence

SAFE and Reservation release are deliberately durable steps. A process can crash between them.

Recovery rule extends M12 reservation recovery:

```text
attempt SAFE_NO_BILLABLE_EXECUTION
+ ACTIVE reservation
→ BillingPeriod → Budget → Reservation lock
→ RELEASED
```

A SAFE attempt is stronger evidence than TTL. It is therefore legal to release even when the request-level state is post-dispatch.

Recovery never starts a new Provider call in the background. If the live request process dies, recovery releases definitely-safe holds and converges the request to a no-billable terminal state rather than spending money with no live client response path.

If an attempt is `BILLABLE_POSSIBLE`, recovery must never release the hold merely because TTL expired. It becomes/remains `PENDING_HOLD` and M13/M15 reconciliation owns resolution.

---

## 28. No-safe-failover terminal cases

Automatic failover stops immediately when:

```text
current attempt = BILLABLE_POSSIBLE
client canceled/disconnected after dispatch
current Provider success completed
no next candidate exists
original BillingPeriod is no longer OPEN for a fresh dispatch
policy/capability/configuration eliminates remaining candidates
all remaining candidates fail Budget admission
```

If every attempted route is SAFE and all holds are RELEASED, no financial work remains and the request may converge to `FAILED_PRE_DISPATCH`.

If any attempt is `BILLABLE_POSSIBLE`, the request uses the existing post-dispatch failure/timeout/cancel states and remains Close-blocking until financial uncertainty is resolved.

---

# Part G — Streaming and M13 Metering

## 29. Streaming failover boundary

Streaming failover is allowed only before any evidence makes the first route `BILLABLE_POSSIBLE`.

Typical legal case:

```text
TX2 for Provider A
→ A DNS/connect/TLS pre-write failure positively SAFE
→ release A hold
→ plan/reserve/fence Provider B
→ B stream starts
```

Once Provider A returns an HTTP response, stream bytes, metering, or another post-write signal, A is not SAFE under the current matrices and there is no automatic B call.

No partial stream from A is ever followed by content from B under the same response.

The downstream `[DONE]` rule from M13 remains unchanged: it is emitted only after terminal usage/lifecycle persistence commits.

---

## 30. OpenAI metering integration

OpenAI Adapter maps provider-neutral usage exactly into the M13 types:

```text
prompt/input tokens       → INPUT_TOKEN
completion/output tokens  → OUTPUT_TOKEN
```

Other dimensions are populated only when current official Provider evidence gives exact trustworthy quantities. Absence is not zero.

For streaming, terminal usage is requested with `stream_options.include_usage=true`. If the stream is interrupted and terminal usage does not arrive, M13 emits `INCOMPLETE` / `UNKNOWN` as appropriate; M14 does not retry because that interruption is `BILLABLE_POSSIBLE`.

M13's frozen Pricing Version on the actual route attempt remains the only settlement pricing source.

Earlier SAFE attempts never produce billable Usage Facts or Settlements.

---

## 31. One final financial outcome per request

M14 preserves the M13 invariant:

```text
many historical SAFE attempts are allowed
at most one attempt may become possibly billable/completed
that attempt owns any Usage Fact
one current FINAL usage may settle once
one Gateway Settlement posts once
```

The system never ends with two automatic potentially-billable attempts for one Gateway Request.

---

# Part H — Idempotency and Concurrency

## 32. Client replay

The existing Gateway `Idempotency-Key` remains request identity.

Replay behavior:

```text
same key + different fingerprint → IDEMPOTENCY_CONFLICT
same key + active processing → existing in-progress response/error
same key + completed request → existing response-not-retained semantics
same key + terminal all-SAFE failure → deterministic terminal failure; no Provider redispatch
same key + BILLABLE_POSSIBLE uncertainty → no Provider redispatch
```

A client replay never creates route attempt N+1.

Only the currently executing server-owned failover coordinator may advance after durable SAFE evidence.

---

## 33. Attempt concurrency authority

For every new attempt:

```text
lock/converge Gateway Request chain
→ verify current attempt and all predecessors
→ require every predecessor SAFE for attempt_no > 1
→ require no effective old Reservation
→ allocate next attempt_no
→ insert attempt
→ update current pointer
```

DB uniqueness remains the final guard:

```text
UNIQUE(org_id, request_id, attempt_no)
UNIQUE(org_id, route_decision_id)
```

Two workers cannot dispatch different Provider attempts concurrently for the same request.

---

## 34. Lock-order rules

M14 never reverses existing financial order.

Budget admission / release:

```text
BillingPeriod
→ Budget
→ Reservation
```

M13 settlement remains:

```text
BillingPeriod
→ Budget
→ explicit Commitment
→ Reservation
→ GatewaySettlement
→ Ledger
```

Request/attempt coordination is kept out of long financial waits wherever possible. When a transaction must touch request/attempt state and financial rows, implementation must acquire BillingPeriod before Budget/Reservation and prove the exact added row-lock order with real MySQL tests.

TX1 and TX2 remain separate. No Provider network I/O occurs while DB locks are held.

---

## 35. Close interaction

M14 refines the Close blocker so historical SAFE attempts do not create false unresolved financial work.

Close must still block on:

```text
any effective ACTIVE/PENDING_HOLD Reservation
any current/past BILLABLE_POSSIBLE route whose financial work is unresolved
current FINAL usage without SETTLED settlement
INCOMPLETE/UNKNOWN possible-billable usage
PENDING / RETRYABLE_FAILED / RECONCILIATION_REQUIRED settlement
```

Close must not block solely because:

```text
a Route Attempt is SAFE_NO_BILLABLE_EXECUTION
a Reservation is RELEASED
a request has an all-SAFE no-billable terminal chain
```

Settlement-vs-Close lock ordering proven in M13 remains unchanged.

---

# Part I — Control-plane / Data-plane Boundaries

## 36. Ownership

### Backend Control Plane owns

```text
routing_policy / routing_policy_candidate CRUD + activation
safe routing-option projection
policy audit events
Flyway V22
M13 settlement/ledger/actual as before
```

### Gateway Data Plane owns

```text
policy resolution reads
candidate eligibility reads
Route Attempt planning/lifecycle
Provider credential decryption for one call
Budget Reservation create/release/hold within existing Gateway boundary
TX2 dispatch fence
Provider adapter registry/I/O
circuit/health runtime state
transport safety classification
safe failover orchestration
M13 usage facts/lifecycle as before
```

Redis circuit state is never Control Plane or financial authority.

---

# Part J — Security / Observability

## 37. Security

Required invariants:

```text
no Provider secret in API/UI/log/metric/audit/error body
no prompt/completion/reasoning in routing decision persistence
no arbitrary Provider response bodies in errors
server-governed base URLs only; production endpoint allowlist per Adapter
adapter code cannot be client-controlled
Provider/model/account/candidate same-org validation
routing policy mutation requires existing ORG-scoped manage permission
ACTIVE policy history immutable
```

OpenAI and MiMo endpoint validation prevents SSRF through a malicious routing row in production.

---

## 38. Bounded metrics

Add bounded metrics such as:

```text
gateway_routing_decision_total{adapter_code,reason}
gateway_candidate_rejection_total{reason}
gateway_provider_safety_total{adapter_code,outcome,reason}
gateway_failover_total{outcome,reason}
gateway_circuit_transition_total{adapter_code,from,to,reason}
gateway_circuit_redis_error_total{operation}
```

Never label metrics with:

```text
request id
route decision id
provider account id
provider model id
project id
raw model string from clients
secret/error body/prompt
```

Structured logs may carry safe correlation identifiers such as public request id / route decision id and bounded reason codes, but never Provider secrets or content.

---

# Part K — Failure Matrix

## 39. Required behavior matrix

| Failure | Attempt outcome | Reservation | Auto next candidate? | Circuit effect |
| --- | --- | --- | --- | --- |
| Candidate static/config ineligible before attempt | no attempt | none | yes | none |
| Candidate budget cannot reserve | SAFE | none | yes | none |
| Local serialization/validation proven pre-network | SAFE | RELEASED if any | yes | usually none |
| DNS pre-connect | SAFE | RELEASED | yes | qualifying failure |
| TCP refused / connect timeout pre-write | SAFE | RELEASED | yes | qualifying failure |
| TLS handshake before HTTP bytes | SAFE | RELEASED | yes | qualifying failure |
| HTTP 401/403/404 response | BILLABLE_POSSIBLE | PENDING_HOLD | no | config unhealthy/open |
| HTTP 429 | BILLABLE_POSSIBLE | PENDING_HOLD | no | pressure failure |
| HTTP 5xx | BILLABLE_POSSIBLE | PENDING_HOLD | no | provider failure |
| Header/read/stream timeout after write possible | BILLABLE_POSSIBLE | PENDING_HOLD | no | timeout failure |
| Reset after write possible | BILLABLE_POSSIBLE | PENDING_HOLD | no | provider failure |
| Malformed response after possible execution | BILLABLE_POSSIBLE | PENDING_HOLD | no | protocol failure |
| Client cancel/disconnect after dispatch | BILLABLE_POSSIBLE | PENDING_HOLD | no | no health penalty |
| Successful completion | COMPLETED | ACTIVE until M13 settles/finalizes | no | close/reset |
| Unknown post-dispatch exception | BILLABLE_POSSIBLE | PENDING_HOLD | no | bounded unknown failure |

HTTP rows are intentionally conservative: a status may be operationally useful for circuit health without being positive proof of non-billable execution.

---

# Part L — Test / Acceptance Matrix

## 40. Schema and control plane

Real MySQL tests must prove:

```text
V22 applies after V21 without modifying V1-V21
same-org FKs
nullable org-default scope uniqueness is actually enforced
one ACTIVE exact scope under concurrent activation
ACTIVE/RETIRED immutability at service boundary
candidate provider account/model code compatibility
policy version monotonicity
exact project override and org fallback
exact project policy never silently falls through to org policy
legacy MiMo route backfill preserves pre-M14 behavior
```

API authorization tests cover read/manage/no-permission/cross-org access.

Frontend tests cover list/create revision/edit priorities/activate/read-only history/error states and localized UI.

---

## 41. Deterministic routing

Tests:

```text
same candidate set → same candidate across repetitions
priority ASC + id ASC tie break
inactive candidate skipped
wrong logical model skipped
missing adapter skipped
stream capability mismatch skipped
missing credential skipped
no pricing skipped
OPEN skipped
HALF_OPEN one-probe ownership
new ACTIVE policy after request start does not change that request's failover chain
same candidate never attempted twice in one request
```

---

## 42. Adapter certification

MiMo and OpenAI both require non-streaming and streaming tests for:

```text
wire URL/auth/model/body
bounded error body handling
terminal usage normalization
[DONE] handling
provider request/correlation ids
connect/header/idle/hard timeouts
production endpoint allowlist
secret redaction
retry disabled
```

Safety tests inject each certified transport failure and assert exact SAFE/BILLABLE outcome. No test may infer SAFE merely from a generic `IOException`.

---

## 43. Safe failover financial tests

Real MySQL + mock upstream tests prove:

```text
A SAFE pre-connect → A reservation RELEASED → B fresh reservation → B dispatch
A and B different price → B reservation uses B Pricing Version
A and B different currency → fresh same-currency budget only; no FX
REQUIRED budget unavailable for B → B is not dispatched
B budget reject may fall through to C without Provider I/O on B
A BILLABLE_POSSIBLE timeout → no B attempt
A 429/5xx → no B attempt under current certification
client cancel after A dispatch → no B attempt
old reservation never retargeted/resized
at most one ACTIVE/PENDING_HOLD reservation per request
at most one possibly-billable/completed attempt per request
```

---

## 44. Crash / replay / concurrency tests

Required real MySQL concurrency/failure injection:

```text
two identical client requests race → one request chain, no duplicate dispatch
two workers try to plan next attempt → one attempt_no winner
two policy activations race → one exact ACTIVE
crash after SAFE before release → recovery releases, never background redispatch
crash after release before next attempt → terminal no-charge convergence, no background redispatch
crash after next reservation before TX2 → recovery releases pre-dispatch hold
crash after TX2 before Provider call → only positive transport/local evidence may SAFE; unknown stays conservative
client replay after SAFE terminal chain → no new Provider call
client replay after BILLABLE_POSSIBLE → no new Provider call
Close vs new failover reservation in both lock orders
Close vs dispatch fence in both lock orders
Close ignores all-SAFE/RELEASED history but blocks unresolved billable work
```

---

## 45. Circuit tests

Use deterministic clock + Redis integration where applicable:

```text
5 qualifying consecutive failures → OPEN
OPEN filters future selection
OPEN expiry → HALF_OPEN
one replica/probe lease only
probe success → CLOSED/reset
probe failure → OPEN
request 4xx/client cancel does not poison health
401/403/route-level 404 configuration rejection opens route as designed
Redis outage → bounded local fallback, no financial mutation
Redis reset changes routing quality only, never historical attempt/settlement truth
```

---

## 46. M13 continuity tests

Prove:

```text
OpenAI FINAL usage settles with that attempt's frozen Pricing Version
interrupted OpenAI streaming usage missing → INCOMPLETE/UNKNOWN, no retry
SAFE historical attempts have no Usage Fact/Settlement
one later successful attempt produces the only billable usage lineage
settlement remains idempotent
Budget Actual is not capped by Reservation
successful Settlement FINALIZES only the billable attempt's Reservation
```

---

# Part M — Implementation Boundaries and Files

## 47. Backend likely touch points

```text
backend/src/main/resources/db/migration/V22__m14_multi_provider_routing.sql
backend/src/main/java/com/aicostops/routing/...
backend/src/test/java/com/aicostops/routing/...
backend/src/main/java/com/aicostops/reconciliation/infrastructure/GatewayCloseBlockerMapper.java
backend/src/test/java/com/aicostops/reconciliation/GatewayFinancialWorkCloseIntegrationTest.java
backend/src/main/java/com/aicostops/gatewayadmin/infrastructure/DevGatewayBootstrap.java
backend/src/main/java/com/aicostops/gatewayadmin/infrastructure/GatewayAdminMapper.java
```

`DevGatewayBootstrap` must create/ensure a routing policy for its existing MiMo local route. Optional OpenAI local provisioning is enabled only when explicit dev OpenAI configuration/secret is provided; no real secret or mutable live model name is committed as a production requirement.

---

## 48. Gateway likely touch points

```text
gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestService.java
gateway/src/main/java/com/aicostops/gateway/request/DispatchFenceService.java
gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestLifecycleService.java
gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservationService.java
gateway/src/main/java/com/aicostops/gateway/budget/ReservationRecoveryService.java
gateway/src/main/java/com/aicostops/gateway/persistence/GatewayReadMapper.java
gateway/src/main/java/com/aicostops/gateway/persistence/GatewayRequestMapper.java
gateway/src/main/java/com/aicostops/gateway/persistence/BudgetReservationMapper.java
gateway/src/main/java/com/aicostops/gateway/provider/ProviderCallContext.java
gateway/src/main/java/com/aicostops/gateway/provider/ProviderChatAdapter.java
gateway/src/main/java/com/aicostops/gateway/provider/ProviderChatAdapterRegistry.java
gateway/src/main/java/com/aicostops/gateway/provider/... safety types
gateway/src/main/java/com/aicostops/gateway/provider/openai/...
gateway/src/main/java/com/aicostops/gateway/routing/...
gateway/src/main/java/com/aicostops/gateway/resilience/...
gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java
gateway/src/main/java/com/aicostops/gateway/config/GatewayProperties.java
gateway/src/main/java/com/aicostops/gateway/observability/GatewayMetrics.java
```

Exact new class decomposition is fixed by the implementation plan, not by speculative package sprawl.

---

## 49. Frontend likely touch points

```text
frontend/src/features/settings/routingPolicies/...
frontend/src/features/settings/api/settingsApi.ts
frontend/src/features/settings/api/settingsKeys.ts
frontend/src/features/settings/permissions.ts
frontend/src/app/router/AppRouter.tsx
frontend/src/app/layout/AuthenticatedLayout.tsx
frontend/e2e/...
```

The page follows existing Settings permission/navigation patterns and Chinese localization conventions.

---

# Part N — Completion Gate

## 50. M14 Definition of Done

M14 is complete only when all of the following are true:

```text
routing_policy + routing_policy_candidate durable and admin-operable
project exact / org fallback deterministic and tested
legacy route upgraded safely
MiMo + OpenAI realtime adapters registered
provider-specific auth isolated in adapters
static routing deterministic
health-aware routing + CLOSED/OPEN/HALF_OPEN circuit operational
Redis multi-replica coordination + safe local degradation tested
transport failures carry tested safety evidence
unknown = BILLABLE_POSSIBLE
safe failover releases old hold before fresh route admission
price-changing failover re-reserves from fresh frozen pricing
currency-changing failover uses no FX and fails closed when required budget absent
no 429/5xx/timeout generic retry
no parallel billable hedging
client replay cannot force Provider retry
streaming never mixes partial Providers
M13 usage/settlement lineage remains correct
Close behavior remains correct
real MySQL concurrency/failure tests green
frontend routing policy admin UAT green
no secret/content leakage
bounded metrics/logging
backend/gateway/frontend full tests green
hosted CI green
hosted Security green
acceptance evidence committed
independent Sol final review passes
user explicitly authorizes merge
```

One final squash merge closes M14. No merge occurs merely because implementation or hosted CI is green.
