# M17 — V3 Model Provider Hub & Cost Intelligence Detailed Design

> Status: **DESIGN FREEZE CANDIDATE**  
> Issue: #153  
> Baseline: `v2.0.0` / `main@93e359687c2d0e81b096d7a73cc8e04e16a7ecbe`  
> Target release: `v3.0.0`  
> M17 is design-only. No runtime feature implementation belongs in this milestone.

## 1. Product intent

V3 evolves AI-CostOps from reliable spend control/reconciliation into a governed AI cost intelligence product without weakening any V2 financial or Provider-execution guarantees.

V3 has two product pillars:

```text
Model Provider Hub
  -> configure safe model connections
  -> built-in OpenCode Zen template
  -> custom OpenAI-compatible connections
  -> discover / probe / promote models
  -> bind exact credentials / pricing / routing

Cost Intelligence
  -> anomaly detection
  -> forecast and budget risk
  -> savings recommendations
  -> AI Advisor explanations
```

The relationship is deliberate:

```text
Deterministic financial facts
        |
        +--> Cost Intelligence engines
        |        |
        |        +--> anomaly / forecast / savings
        |
        +--> bounded Advisor evidence envelope
                 |
                 +--> governed model inference
                          |
                          +--> narrative explanation only
```

The LLM is never a financial authority.

## 2. Provenance and lessons incorporated

V3 takes product inspiration, not code ownership, from two sibling projects:

- `BangShou1st/ai-collab`: useful product idea that users should be able to configure model connections; its per-model configuration shape is intentionally not copied because it mixes connection, credential, model and capability state.
- `BangShou1st/spec-agent`: useful OpenCode Zen experience including a provider-specific transport boundary, dynamic `/models` discovery, safe credential projection, provider-specific User-Agent policy and explicit DIRECT transport evidence.

AI-CostOps remains the owning architecture. Existing V2 concepts remain authoritative:

```text
provider_catalog
provider_account
provider_credential
model_catalog
provider_model
pricing_version / pricing_rate
routing_policy / routing_policy_candidate
gateway_request / gateway_route_attempt
gateway_usage_fact / gateway_settlement
budget / ledger / reconciliation
```

No parallel `llm_config`, `model_secret`, or second model-routing subsystem is introduced.

## 3. Frozen V3.0 scope

### 3.1 In scope

```text
Provider Connection profiles with immutable activated versions
OpenCode Zen built-in Provider Template
Custom OpenAI-compatible Chat Completions connection
Bearer / API-key-header / no-auth custom authentication
Encrypted Provider Credential reuse
Dynamic model discovery when `/models` exists
Manual model registration when discovery is unavailable
Model probe and verified capability evidence
Organization-private custom logical/provider models
Provider connection -> pricing -> routing readiness bridge
Custom-endpoint SSRF defense
OpenCode DIRECT_ONLY egress policy
Deterministic anomaly detection
Deterministic forecast
Budget risk projection
Deterministic counterfactual savings recommendations
AI Advisor model/profile configuration
Governed Advisor inference and cost attribution
Recommendation lifecycle and audit
Backend-first OpenAPI contract freeze
Product Design-led frontend after backend freeze
```

### 3.2 Explicit non-goals

```text
OpenAI Responses API
Anthropic Messages API
Gemini-native protocol
Embeddings
MCP
Agent/tool execution
arbitrary custom request-body transform DSL
arbitrary custom header JSON
browser-configurable HTTP proxy
local/private model endpoints by default
model training / fine-tuning
Python ML sidecar
Kafka / Elasticsearch / Kubernetes solely for V3
SAML / SCIM
ERP / GL
Full FOCUS conformance
automatic FX
multi-region
AI-autonomous routing activation
AI-autonomous savings execution
```

These require a later design reopening, not hidden expansion inside M18/M19.

## 4. Inherited non-negotiable invariants

V3 inherits all V2 safety and financial invariants:

```text
MySQL Ledger is final financial truth.
Redis/cache is never monetary truth.
Financial money uses DECIMAL / BigDecimal / API decimal-string.
POSTED Ledger effects are immutable; corrections append new lineage.
Provider I/O occurs only after durable DISPATCH_INTENT.
Provider I/O never occurs inside a DB transaction.
No blind redispatch after possible billable execution.
No billable parallel hedging.
Unknown provider execution => BILLABLE_POSSIBLE.
Only positively proven SAFE_NO_BILLABLE_EXECUTION permits failover/redispatch.
Missing usage != zero.
Prompt/completion content is not persisted by default.
Provider secret / Gateway raw key / digest / Authorization / JWT / raw idempotency key never leaks.
```

New V3 invariants:

```text
User-declared model capability != verified capability.
Live `/models` availability != pricing truth.
A model name suffix such as `-free` != financial truth.
Connection health != financial/routing readiness.
LLM narrative != financial fact.
Advisor failure must not break deterministic intelligence.
OpenCode Zen built-in traffic must not use user/system proxy configuration.
Custom endpoint validation must prevent SSRF including redirects and DNS rebinding-style target changes.
An organization-private custom model must never be visible/routable cross-org.
```

## 5. Provider Hub domain model

### 5.1 Separation of concerns

The provider configuration domain is split into four distinct concerns:

```text
Provider Account
= organization commercial/provider identity

Provider Connection Profile
= how AI-CostOps reaches that provider endpoint

Provider Credential
= encrypted secret used by the connection

Provider Model
= exact provider wire model mapped to an AI-CostOps logical model
```

One Provider Connection may expose many models. A secret belongs to the Provider Account/connection context, not to each individual model row.

### 5.2 `provider_connection_profile`

New organization-scoped, versioned entity:

```text
id                       BIGINT PK
org_id                   BIGINT NOT NULL
provider_account_id      BIGINT NOT NULL
version                  INT NOT NULL
connection_kind          BUILTIN | CUSTOM
template_code            OPENCODE_ZEN | NULL
protocol_code            OPENAI_CHAT_COMPLETIONS
base_url                 VARCHAR(...)
completion_path          VARCHAR(...)
models_path              VARCHAR(...) NULL
auth_type                BEARER | API_KEY_HEADER | NONE
auth_header_name         VARCHAR(...) NULL
network_policy           DIRECT_ONLY | DIRECT_PUBLIC_ONLY
user_agent               VARCHAR(...) NULL
connect_timeout_ms       INT NOT NULL
response_timeout_ms      INT NOT NULL
status                   DRAFT | ACTIVE | RETIRED
created_by               BIGINT NOT NULL
created_at               DATETIME(6) NOT NULL
activated_at             DATETIME(6) NULL
retired_at               DATETIME(6) NULL
```

Required integrity:

```text
UNIQUE(org_id, provider_account_id, version)
at most one ACTIVE profile per provider account
same-org provider_account FK
version is server-assigned and monotonic
ACTIVE and RETIRED rows are immutable
only DRAFT is editable
activation retires the previous ACTIVE version atomically
```

`template_code=OPENCODE_ZEN` requires `connection_kind=BUILTIN`, `network_policy=DIRECT_ONLY`, provider-owned endpoint/path/user-agent defaults and no browser override of those fields.

`connection_kind=CUSTOM` requires `template_code=NULL` and `network_policy=DIRECT_PUBLIC_ONLY` in V3.0.

### 5.3 Provider Credential reuse

V3 reuses existing `provider_credential` encryption, rotation and revocation semantics.

V3 may extend bounded credential metadata only when required to represent authentication safely. Credential plaintext is never stored outside the existing encryption boundary and is never returned by read APIs after creation/rotation.

Authentication mapping:

```text
BEARER
  credential_type = BEARER_TOKEN
  outbound = Authorization: Bearer <secret>

API_KEY_HEADER
  credential_type = API_KEY
  outbound = <validated auth_header_name>: <secret>

NONE
  no active Provider Credential required
```

Forbidden user-controlled header names include at least:

```text
Authorization
Proxy-Authorization
Proxy-Connection
Host
Content-Length
Connection
Cookie
Set-Cookie
Transfer-Encoding
```

`Authorization` is managed by `BEARER`; arbitrary header JSON does not exist in V3.0.

## 6. Provider Template architecture

Provider-specific defaults do not live in generic business services.

Conceptual boundary:

```text
ProviderTemplateRegistry
  OPENCODE_ZEN -> OpenCodeZenProviderTemplate

ProviderProtocolRegistry
  OPENAI_CHAT_COMPLETIONS -> OpenAiChatCompletionsProtocol
```

A Provider Template owns safe defaults and restrictions. A protocol adapter owns wire request/response parsing. This prevents provider-name `if` statements from spreading through routing/business code.

### 6.1 OpenCode Zen V3.0 template

Frozen defaults:

```text
template_code    = OPENCODE_ZEN
base_url         = https://opencode.ai/zen/v1
models_path      = /models
network_policy   = DIRECT_ONLY
auth_type        = BEARER
```

Provider-specific User-Agent is server-owned template configuration, not an editable browser field. The implementation may update that server-owned default through a normal release/config change without exposing arbitrary user header control.

OpenCode Zen currently exposes multiple API protocol families. V3.0 enables only models positively classified as compatible with `OPENAI_CHAT_COMPLETIONS`. Models requiring Responses, Messages or another native surface may be shown as unsupported/unavailable but are not promoted or routed in V3.0.

### 6.2 OpenCode live discovery and financial classification

`GET /models` is availability/catalog evidence, not pricing truth.

A versioned server-owned OpenCode template manifest may carry verified metadata such as:

```text
provider_model_name
protocol_code
pricing_classification = VERIFIED_FREE | PAID | UNKNOWN
verified_at
manifest_version
```

Rules:

```text
Live model exists + manifest supports Chat Completions
=> eligible for probe

Live model exists but manifest has no pricing classification
=> AVAILABLE / PRICING_UNKNOWN

Model id ends in `-free`
=> no automatic financial conclusion

VERIFIED_FREE
=> UI may offer creation of an explicit zero-price DRAFT Pricing Version
=> user/admin must activate that Pricing Version before production routing
```

The database Pricing Version remains the dispatch-time financial truth.

## 7. Custom OpenAI-compatible connection

V3.0 supports one generic protocol: OpenAI-compatible `POST /chat/completions`.

User-editable normal fields:

```text
display name
base URL
credential/auth choice
optional model discovery on/off
```

Advanced bounded fields:

```text
completion path
models path
User-Agent (non-secret)
connect timeout
response timeout
```

No arbitrary request-template DSL exists. The request body follows the same bounded Chat Completions subset already supported by the Gateway contract.

## 8. SSRF and network policy

### 8.1 `DIRECT_PUBLIC_ONLY`

Custom provider endpoints are direct public Internet destinations by default.

Activation/probe must reject targets resolving to or redirecting to at least:

```text
loopback / localhost
RFC1918 private ranges
link-local
multicast / broadcast-like non-unicast targets
IPv6 loopback/link-local/unique-local where applicable
cloud metadata targets such as 169.254.169.254
URL userinfo credentials
non-http(s) schemes
```

Both initial DNS resolution and every redirect target are validated. A validated hostname cannot bypass policy by redirecting to a private destination. Response redirects are bounded and never followed without re-validation.

Private/local endpoints require a future explicit deployment-level design. A browser administrator cannot disable SSRF policy.

### 8.2 OpenCode `DIRECT_ONLY`

The OpenCode HTTP client must be constructed so outbound requests do not inherit:

```text
HTTP_PROXY
HTTPS_PROXY
ALL_PROXY
http.proxyHost / http.proxyPort
https.proxyHost / https.proxyPort
OS/browser/IDE proxy settings
```

Acceptance intentionally configures a poison proxy at `127.0.0.1:7897` while routing OpenCode traffic to a controlled test upstream. Required proof:

```text
controlled OpenCode upstream request count = expected
poison proxy request count = 0
```

A regression that touches the proxy fails M18/M20 acceptance.

## 9. Model discovery, probe and promotion

### 9.1 `provider_model_discovery`

New organization-scoped observation entity. It is not routing truth.

Logical fields:

```text
id
org_id
provider_connection_profile_id
provider_model_name
display_name NULL
source = LIVE_DISCOVERY | MANUAL
availability = AVAILABLE | UNAVAILABLE | UNKNOWN
protocol_code
pricing_classification = VERIFIED_FREE | PAID | UNKNOWN
declared_capabilities_json
verified_capabilities_json
last_seen_at
last_probed_at
last_probe_status
last_probe_error_code NULL
```

Discovery refresh updates observation state but never mutates an already-used Pricing Version or historical Route Attempt.

### 9.2 Declared vs verified capabilities

At minimum V3.0 recognizes:

```text
CHAT_COMPLETIONS
SSE_STREAMING
USAGE
STRUCTURED_JSON
```

User/template declarations are hints. Only backend probe evidence can populate verified capability state.

A capability may be:

```text
VERIFIED
UNSUPPORTED
UNKNOWN
```

Probe uses bounded synthetic content and must not persist prompt/completion content.

### 9.3 Promotion

Discovery does not automatically create routable catalog truth.

Promotion transaction creates or links:

```text
organization-visible logical model
provider_model mapping
safe connection association
```

Production routing still requires all existing V2 gates including Provider Account ACTIVE, Provider Credential eligibility, Provider Model ACTIVE/routing-eligible, active Pricing Version and an ACTIVE Routing Policy candidate.

## 10. Organization-private custom catalog entries

V2 catalogs were global/server-governed. V3 custom models require safe organization ownership.

The V3 migration extends logical/provider model ownership with nullable org ownership:

```text
model_catalog.owner_org_id NULL
provider_model.owner_org_id NULL

NULL      => system-global catalog entry
non-NULL  => organization-private entry
```

All V3 reads resolve visibility as:

```text
owner_org_id IS NULL
OR owner_org_id = current_org_id
```

Cross-org references are rejected transactionally and, where possible, reinforced by composite uniqueness/FKs/helper keys.

A custom model key is unique within its organization namespace. A private model cannot be selected by another organization's Gateway credential or routing policy.

## 11. Connection version lineage in Gateway execution

Gateway Route Attempt gains the exact `provider_connection_profile_id` used for an upstream attempt.

The dispatch snapshot therefore includes:

```text
provider_account_id
provider_model_id
provider_connection_profile_id
provider_credential identity/version
pricing_version_id
routing_policy_id/version
```

Changing endpoint/profile later never rewrites historical attempts.

Provider connection changes use DRAFT -> ACTIVE version activation. Existing in-flight requests continue using their already-frozen route/connection snapshot.

## 12. Readiness model

Connection status and production readiness are separate.

Safe projection exposes distinct dimensions:

```text
Lifecycle: DRAFT | ACTIVE | RETIRED
Health: UNKNOWN | HEALTHY | DEGRADED | FAILED
Credential: READY | MISSING | REVOKED
Model: DISCOVERED | VERIFIED | PROMOTED | DISABLED
Pricing: MISSING | DRAFT | ACTIVE
Routing: NOT_REFERENCED | DRAFT_ONLY | ACTIVE
```

A successful connection probe does not imply production routing eligibility.

## 13. Deterministic Cost Intelligence source of truth

V3 does not introduce a new financial ledger or duplicate monetary truth table.

Authoritative money comes from POSTED Ledger effects and existing Gateway Settlement/Reconciliation lineage. Supporting dimensions explain the cost by organization/project/team/cost center/provider/model.

V3 Core queries existing facts/read paths first. A materialized analytics read model is introduced only if measured performance proves direct indexed aggregation is insufficient.

No cross-currency aggregation is performed without an explicit grouping by currency because V3.0 has no automatic FX engine.

## 14. Anomaly detection

`CostAnomalyEngine` is deterministic and explainable; no LLM or trained ML model decides whether a cost anomaly exists.

Frozen V3.0 baseline:

```text
analysis grains:
  organization
  project
  provider
  logical model

lookback:
  28 completed daily buckets

minimum history:
  14 completed non-future buckets

baseline:
  median

dispersion:
  MAD (median absolute deviation)

score:
  robust z-score
```

An anomaly is emitted only when all configured fixed product conditions are met:

```text
statistical score threshold
AND percentage delta threshold
AND absolute currency materiality threshold
```

This prevents tiny amounts from becoming noisy alerts due only to percentage change.

Monetary values remain BigDecimal/DECIMAL. Statistical scores may use double because they are non-financial derived signals.

### 14.1 Driver analysis

For each detected anomaly, deterministic contribution analysis ranks actual deltas by bounded dimensions such as model/provider/project. Advisor text may explain these computed drivers but cannot invent a new driver or amount.

## 15. Forecasting

V3.0 uses a bounded deterministic forecast implementation; it does not add a Python service or opaque AutoML.

Primary method:

```text
DAMPED_HOLT
```

Fallback for insufficient but minimally usable history:

```text
RECENT_RUN_RATE
```

Every forecast response includes:

```text
currency
observed_through
projected_period_end_amount
method
history_bucket_count
confidence = LOW | MEDIUM | HIGH
```

`confidence` is a product classification derived from fixed data-coverage/error rules; it is not an LLM confidence claim.

Forecast output is explicitly marked `DERIVED_ESTIMATE`, never Ledger truth.

## 16. Budget risk projection

Budget risk is calculated from explicit components rather than only comparing forecast to total budget.

Projection exposes:

```text
actual
outstanding_commitments
active_reservations
forecast_future_usage
budget_total
```

Two views:

```text
Immediate Exposure
= actual + outstanding commitments + effective active reservations

Projected Period End
= actual + outstanding commitments + forecast future usage
```

Active reservations are not blindly duplicated into the remaining-period forecast.

Risk enum:

```text
LOW
WATCH
HIGH
OVER_BUDGET
```

Threshold policy is bounded/versioned application configuration, not a generic user-defined rule DSL in V3.0.

## 17. Savings recommendation engine

Savings recommendations are deterministic counterfactual calculations.

Candidate comparison is allowed only within an explicitly equivalent AI-CostOps logical model. The engine never assumes semantically different model ids are interchangeable merely because one is cheaper.

Calculation:

```text
historical normalized usage dimensions
  INPUT_TOKEN
  OUTPUT_TOKEN
  CACHED_INPUT_TOKEN
  REQUEST
        |
        +--> current Pricing Version replay
        +--> candidate Pricing Version replay
                 |
                 +--> exact BigDecimal counterfactual cost
```

Recommendation evidence stores at least:

```text
currency
evidence window
current provider/model/pricing version
candidate provider/model/pricing version
historical usage fingerprint
current counterfactual cost
candidate counterfactual cost
potential_saving_amount
potential_saving_percent
calculated_at
```

No LLM calculates these values.

### 17.1 Recommendation lifecycle

```text
OPEN
ACKNOWLEDGED
DISMISSED
APPLIED
EXPIRED
```

V3.0 never activates a routing policy automatically. Applying a recommendation means a human/admin creates or activates the normal Routing Policy revision through existing governed controls. Recommendation state may record linkage to the resulting policy revision.

`potential_saving` is never presented as realized saving until later actual financial evidence proves realization.

## 18. AI Advisor boundary

AI Advisor is optional enhancement, not a dependency of deterministic intelligence.

### 18.1 Evidence envelope

Advisor receives a bounded server-generated `AdvisorEvidenceEnvelope`, never arbitrary database dumps.

Conceptual contract:

```text
subject_type
subject_id
currency
facts[]
drivers[]
recommendation_evidence[]
fact_reference_ids[]
generated_at
schema_version
```

All authoritative financial values are already computed before inference.

LLM output schema contains narrative fields only:

```text
summary
drivers_explanation
recommended_actions[]
warnings[]
fact_references[]
```

The response schema intentionally contains no authoritative money fields. UI monetary amounts always come from deterministic backend objects, never parsed from generated prose.

### 18.2 Prompt/privacy policy

Advisor prompts contain bounded structured cost facts and safe labels/identifiers only. Raw user prompts/completions from governed workload traffic are never included.

Default persistence stores:

```text
advisor job metadata
model/provider/request lineage
result status
safe output hash / bounded narrative if product explicitly requires display persistence
```

V3.0 design preference is to persist the bounded generated explanation required for product history while never persisting upstream workload prompt/completion contents. Stored Advisor narrative is treated as generated product content, sanitized and size bounded. Provider raw response bodies and Authorization material are never persisted.

## 19. Governed Advisor execution

Backend must not bypass Gateway and directly decrypt Provider secrets for Advisor calls.

Conceptual flow:

```text
Backend deterministic insight
  -> create advisor_inference_job
  -> commit

Gateway internal Advisor worker
  -> claim job in short DB transaction
  -> commit claim
  -> create normal governed Gateway execution identity/snapshot
  -> durable dispatch intent
  -> Provider I/O
  -> usage normalization
  -> settlement
  -> Ledger
  -> finish advisor job
```

No Provider I/O occurs while claiming/updating the job transaction.

### 19.1 Internal Advisor identity

V3 extends Gateway credential provenance with a narrow internal origin:

```text
USER_ISSUED
INTERNAL_SYSTEM
```

Advisor uses an organization-scoped Service Identity such as `AICOSTOPS_ADVISOR` and an `INTERNAL_SYSTEM` Gateway credential identity.

The external Bearer authentication filter must never authenticate `INTERNAL_SYSTEM` credentials. They are database/internal identities only and have no redisplayable external raw secret.

Advisor profile binds explicit:

```text
organization
project
financial scope
budget enforcement mode
logical model
```

Thus Advisor usage is visible in the same budget/settlement/Ledger system as other model usage.

### 19.2 No hidden retry

If an Advisor Provider execution is `BILLABLE_POSSIBLE`, malformed, timed out after dispatch, or returns invalid structured output, V3 Core does not silently call the model again.

The job fails with a safe reason while deterministic insight remains usable. A user-authorized Retry creates a new governed execution and new financial lineage.

### 19.3 Feedback-loop prevention

Advisor's own cost remains part of organization financial totals and forecast truth, but anomaly root-cause and savings candidate generation exclude the `AICOSTOPS_ADVISOR` Service Identity by default to prevent recursive self-trigger loops.

Advisor overhead is separately reportable.

## 20. V3 durable entities

M18 implementation is expected to use V24+ migrations. V1–V23 are immutable.

Logical V3 additions/extensions:

```text
V24 Provider Hub
  provider_connection_profile
  provider_model_discovery
  model_catalog owner_org_id/namespace support
  provider_model owner_org_id/connection support
  gateway_route_attempt provider_connection_profile_id
  bounded provider credential/auth metadata if required

V25 Cost Intelligence / Advisor
  cost_anomaly
  cost_forecast_snapshot
  savings_recommendation
  ai_advisor_profile
  advisor_inference_job
  advisor_explanation
  gateway credential origin/internal advisor identity support
```

Exact physical split may remain V24/V25 or use additional monotonically numbered migrations if one migration becomes operationally unsafe; no existing migration may be edited.

Derived snapshots are evidence/read history, not replacement financial truth.

## 21. Permissions

Existing Provider Hub administration reuses conservative ORG-scoped permissions:

```text
PROVIDER_ACCOUNT_READ
PROVIDER_ACCOUNT_MANAGE
```

Cost Intelligence reads reuse scoped financial permissions where applicable:

```text
COST_READ
BUDGET_READ
```

V3 adds explicit AI-spend permissions:

```text
AI_ADVISOR_USE
AI_ADVISOR_MANAGE
```

Semantics:

```text
AI_ADVISOR_USE
  trigger/retry one Advisor explanation when underlying financial data is already readable

AI_ADVISOR_MANAGE
  configure Advisor profile, model, project, financial scope and budget enforcement
```

Read-only financial permission never implicitly grants ability to create new AI spend.

## 22. Audit

Audit at least:

```text
PROVIDER_CONNECTION_CREATED
PROVIDER_CONNECTION_REVISION_CREATED
PROVIDER_CONNECTION_ACTIVATED
PROVIDER_CREDENTIAL_CREATED / ROTATED / REVOKED
PROVIDER_MODEL_DISCOVERED refresh summary
PROVIDER_MODEL_PROMOTED / DISABLED
AI_ADVISOR_PROFILE_UPDATED
AI_ADVISOR_EXPLANATION_REQUESTED / COMPLETED / FAILED
SAVINGS_RECOMMENDATION_ACKNOWLEDGED / DISMISSED / APPLIED
```

Audit metadata includes IDs, safe labels, statuses, template/protocol codes and safe result codes only.

Never audit secrets, ciphertext, prompt bodies, provider raw responses, Authorization values or workload prompt/completion content.

## 23. Backend API surface

M18 freezes backend/OpenAPI before M19 frontend implementation.

Provider templates/connections:

```text
GET  /api/v1/provider-templates
GET  /api/v1/provider-connections
POST /api/v1/provider-connections
GET  /api/v1/provider-connections/{id}
POST /api/v1/provider-connections/{id}/revisions
PUT  /api/v1/provider-connections/{id}                 # DRAFT only
POST /api/v1/provider-connections/{id}/activate
POST /api/v1/provider-connections/{id}/probe
POST /api/v1/provider-connections/{id}/credentials
POST /api/v1/provider-connections/{id}/credentials/rotate
POST /api/v1/provider-connections/{id}/credentials/revoke
```

Models:

```text
GET  /api/v1/provider-connections/{id}/models
POST /api/v1/provider-connections/{id}/models/refresh
POST /api/v1/provider-connections/{id}/models/manual
POST /api/v1/provider-connections/{id}/models/{discoveryId}/probe
POST /api/v1/provider-connections/{id}/models/{discoveryId}/promote
```

Cost Intelligence:

```text
GET /api/v1/cost-intelligence/summary
GET /api/v1/cost-intelligence/anomalies
GET /api/v1/cost-intelligence/forecasts
GET /api/v1/cost-intelligence/budget-risks
GET /api/v1/cost-intelligence/recommendations
POST /api/v1/cost-intelligence/recommendations/{id}/acknowledge
POST /api/v1/cost-intelligence/recommendations/{id}/dismiss
POST /api/v1/cost-intelligence/recommendations/{id}/mark-applied
```

Advisor:

```text
GET  /api/v1/ai-advisor/profile
PUT  /api/v1/ai-advisor/profile
POST /api/v1/ai-advisor/explanations
GET  /api/v1/ai-advisor/explanations/{id}
POST /api/v1/ai-advisor/explanations/{id}/retry
```

Every read is same-org/scope filtered server-side. No API returns decrypted secret/ciphertext/nonce/raw Authorization or raw Provider response.

## 24. Error model

Provider configuration/probe uses bounded diagnosable error codes, including:

```text
CONNECTION_DNS_FAILED
CONNECTION_TLS_FAILED
CONNECTION_TIMEOUT
AUTHENTICATION_FAILED
MODEL_NOT_FOUND
PROTOCOL_UNSUPPORTED
RATE_LIMITED
PROVIDER_UNAVAILABLE
INVALID_PROVIDER_RESPONSE
ENDPOINT_BLOCKED
SSRF_TARGET_REJECTED
MODEL_NOT_VERIFIED
PRICING_NOT_READY
ROUTING_NOT_READY
```

Safe diagnostics may include endpoint host, path, model id, HTTP status and selected allowlisted request IDs, but never arbitrary response bodies or credential material.

## 25. Frontend product contract

M19 begins only after M18 OpenAPI/backend acceptance is frozen.

Information architecture:

```text
AI / Intelligence
  Cost Intelligence
    Overview
    Anomalies
    Forecast
    Savings
  AI Advisor

Settings
  Model Providers
    Provider Gallery
    Connections
    Models
  Model Pricing
  Routing Policies
```

Provider creation is a guided wizard rather than a giant low-level form:

```text
1. Choose Provider
2. Connection / Credential
3. Discover or enter Model
4. Probe / verify
5. Review readiness and save
```

OpenCode skips server-owned technical fields and presents a short guided flow.

Built-in Provider cards use approved provider brand assets. Custom providers use generated initials/iconography in V3.0; custom image upload/object storage is out of scope.

Product Design workflow in M19:

```text
load current AI-CostOps visual context
create exactly three distinct visual directions
user selects one direction
implement selected direction
run design QA + Browser E2E + accessibility checks
```

## 26. Test strategy

### 26.1 Provider Hub

Required automated coverage includes:

```text
version activation concurrency
same-org isolation
secret never returned/logged
credential rotation lineage
custom auth header validation
SSRF IP/scheme/userinfo rejection
DNS resolves-to-private rejection
redirect-to-private rejection
OpenCode poison proxy 7897 receives zero requests
OpenCode template fields not user-overridable
model discovery is not pricing truth
unverified free-looking id does not create zero pricing
probe capability verification
promotion requires correct org/connection
historical route attempt retains connection version
```

### 26.2 Cost Intelligence

Use deterministic fixtures and golden calculations for:

```text
median/MAD anomaly score
zero-MAD edge case
minimum history
percentage + materiality gates
currency isolation
contribution drivers
Damped Holt forecast deterministic fixture
run-rate fallback
budget exposure vs period-end projection
exact BigDecimal counterfactual pricing
same-logical-model candidate restriction
recommendation lifecycle
```

### 26.3 Advisor

Required:

```text
no financial amount can be sourced from LLM output
bounded evidence envelope only
workload prompt/completion never included
Advisor Provider failure leaves deterministic result available
invalid JSON causes failure, no hidden retry
user Retry creates distinct governed execution
Advisor usage creates normal metering/settlement/Ledger lineage
INTERNAL_SYSTEM credential rejected by external Bearer authentication
Advisor self-cost excluded from recursive anomaly/savings triggers but included in totals
```

## 27. Acceptance gates

### Gate A — Financial invariants

```text
lost settlement = 0
duplicate Ledger effect = 0
silent reservation leak = 0
race budget overspend = 0
financial float authority = 0
```

### Gate B — Provider Hub security

```text
Provider secret leak = 0
cross-org custom model leak = 0
SSRF escape = 0
OpenCode poison-proxy hits = 0
unverified pricing promoted as financial truth = 0
```

### Gate C — AI integrity

```text
LLM-authored financial truth = 0
hidden post-dispatch retry = 0
Advisor failure breaking deterministic intelligence = 0
raw Provider response/Authorization persistence = 0
```

### Gate D — Product/browser

```text
OpenCode guided setup usable
Custom Provider wizard usable
Secret never redisplayed
Provider readiness understandable
Cost Intelligence views understandable
AI narrative clearly separated from deterministic amounts
responsive + keyboard/accessibility acceptance
Browser E2E PASS
```

## 28. Milestone delivery

```text
M17 — V3 Detailed Design & Scope Freeze
  design only

M18 — V3 Backend Complete
  V24+ migrations
  Provider Hub
  OpenCode template
  Custom Chat Completions provider
  model discovery/probe/promotion
  security + SSRF + DIRECT_ONLY
  pricing/routing bridge
  anomaly/forecast/budget risk/savings
  Advisor backend + governed inference
  OpenAPI
  backend acceptance
  => BACKEND CONTRACT FREEZE

M19 — Product Design + Frontend
  three visual directions
  selected provider/gallery/wizard UX
  Cost Intelligence / Advisor UI
  frontend + Browser acceptance

M20 — V3 Production Acceptance
  machine acceptance
  hosted CI/security
  browser UAT
  real-provider certification only when free/non-paid evidence is available
  release decision

v3.0.0
```

A paid external credential is not required merely to release a personal-project V3 if all deterministic/provider protocol behavior is fully covered by local/CI controlled upstreams. Any unavailable real-provider certification is explicitly recorded as deferred/non-PASS rather than relabeled as PASS.

## 29. Design self-review decisions

The following tempting approaches are explicitly rejected:

```text
Copy ai-collab ModelConfiguration wholesale
  -> rejected: connection/model/secret concerns are mixed.

Create second LLM credential/model system
  -> rejected: duplicates V2 Provider/Gateway governance.

Infer free pricing from `-free` suffix
  -> rejected: naming is not commercial truth.

Let `/models` auto-create routing candidates
  -> rejected: discovery is observation, not governed routing truth.

Let users paste arbitrary headers JSON
  -> rejected: secret leakage and protocol/security ambiguity.

Allow localhost/private endpoints by browser toggle
  -> rejected: weakens SSRF boundary.

Call Provider directly from Backend for Advisor
  -> rejected: bypasses Gateway financial/safety lineage.

Use LLM for anomaly/savings arithmetic
  -> rejected: non-deterministic financial authority.

Auto-apply routing savings
  -> rejected: V3.0 remains human-governed.
```

## 30. Freeze declaration

This document is the M17 V3 design source of truth once accepted/merged.

Any M18 implementation requirement that cannot be represented by the contracts above must reopen #153/design before implementation proceeds. In particular, adding a new model protocol, private endpoint support, arbitrary headers, automatic routing actions, automatic FX or another financial truth store is a design change, not an implementation detail.
