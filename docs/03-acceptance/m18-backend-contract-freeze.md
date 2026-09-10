# M18 — V3 Backend / OpenAPI Contract Freeze

> Status: **FROZEN** for M19 frontend development. Branch:
> `feat/m18-v3-backend-complete`. Machine contract:
> [`m18-openapi.yaml`](./m18-openapi.yaml). Gate:
> `backend/src/test/java/com/aicostops/contract/M18OpenApiContractTest.java`.

## 1. Conventions (unchanged V1/V2 + V3)

- Base path `/api/v1`. Auth: Bearer session. Pagination
  `{items, page, size, totalElements, totalPages}`; default `page=0&size=50`.
- Errors: shared RFC-9457 problem with stable `code` (see section 6).
- Money: **decimal string** everywhere (`"30.00"`), never float/double.
  Persisted as `DECIMAL(20,8)` / computed with `BigDecimal`.
- Timestamps: ISO-8601 UTC. IDs: `int64`. Secrets: never returned, never
  logged, never audited (only labels/ids/statuses).
- Org scoping is server-side on every read; cross-org access is rejected.

## 2. Provider Hub

| Method & path | Permission | Result |
|---|---|---|
| `GET /provider-templates` | PROVIDER_ACCOUNT_READ | Templates + `lockedFields` |
| `GET /provider-connections?page&size` | PROVIDER_ACCOUNT_READ | Page(Connection) |
| `POST /provider-connections` | PROVIDER_ACCOUNT_MANAGE | 201 Draft Connection, server version |
| `GET /provider-connections/{id}` | PROVIDER_ACCOUNT_READ | Connection |
| `PUT /provider-connections/{id}` | PROVIDER_ACCOUNT_MANAGE | DRAFT-only update |
| `GET /provider-connections/{id}/revisions` | PROVIDER_ACCOUNT_READ | Version list |
| `POST /provider-connections/{id}/revisions` | PROVIDER_ACCOUNT_MANAGE | 201 new DRAFT |
| `POST /provider-connections/{id}/activate` | PROVIDER_ACCOUNT_MANAGE | Retire-prev + activate, one tx |
| `POST /provider-connections/{id}/probe` | PROVIDER_ACCOUNT_READ | `{status:PASS|FAIL, errorCode?, checkedAt}` |
| `GET .../{id}/credentials` | PROVIDER_ACCOUNT_READ | Safe labels only |
| `POST .../{id}/credentials {rawSecret,safeLabel}` | PROVIDER_ACCOUNT_MANAGE | 201 metadata only |
| `POST .../{id}/credentials/rotate` | PROVIDER_ACCOUNT_MANAGE | Successor ACTIVE, prev revoked |
| `POST .../{id}/credentials/{credentialId}/revoke` | PROVIDER_ACCOUNT_MANAGE | 204 |

Connection lifecycle `DRAFT → ACTIVE → RETIRED`; ACTIVE/RETIRED immutable;
one ACTIVE per account (DB partial-unique); activation validates SSRF.
`OPENCODE_ZEN` rows are `BUILTIN` with server-owned base URL / paths /
Bearer / `DIRECT_ONLY` / User-Agent — browser overrides rejected.
Custom rows are `OPENAI_CHAT_COMPLETIONS` + `DIRECT_PUBLIC_ONLY` with
`BEARER | API_KEY_HEADER | NONE`; forbidden auth headers rejected.

## 3. Models: discovery / probe / promotion

| Method & path | Permission | Result |
|---|---|---|
| `GET .../{id}/models` | PROVIDER_ACCOUNT_READ | Observations |
| `POST .../{id}/models/refresh {modelNames[]}` | PROVIDER_ACCOUNT_MANAGE | Upsert AVAILABLE, mark missing UNAVAILABLE |
| `POST .../{id}/models/manual {modelName}` | PROVIDER_ACCOUNT_MANAGE | 201 MANUAL observation |
| `POST .../{id}/models/{discoveryId}/probe` | PROVIDER_ACCOUNT_MANAGE | `{pass, capabilities{CHAT_COMPLETIONS,SSE_STREAMING,USAGE,STRUCTURED_JSON}, errorCode}` |
| `POST .../{id}/models/{discoveryId}/promote` | PROVIDER_ACCOUNT_MANAGE | 201 `{logicalModelId, providerModelId, pricingReady, routingReady}` |

Capabilities are `VERIFIED | UNSUPPORTED | UNKNOWN`; only probe evidence
writes VERIFIED. Discovery never creates pricing/routing. Promotion creates
org-private catalog rows bound to the exact account; private keys must not
shadow global keys. Production routing additionally requires ACTIVE
account/credential/model, ACTIVE pricing version, ACTIVE routing policy and
an ACTIVE connection profile.

## 4. Cost Intelligence (derived evidence, never Ledger truth)

| Method & path | Permission | Result |
|---|---|---|
| `GET /cost-intelligence/summary?currency` | COST_READ | `{runId, status, anomalyCount, forecastCount, openRecommendations}` (`STALE` w/o run) |
| `GET /cost-intelligence/anomalies?currency` | COST_READ | Median/MAD/robust-z anomalies + drivers |
| `GET /cost-intelligence/forecasts?currency` | COST_READ | `{projectedAmount(decimal-string), method, historyBucketCount, confidence, observedThrough}` |
| `GET /cost-intelligence/budget-risks?scopeType&scopeId&currency` | BUDGET_READ | `{immediateExposure, projectedPeriodEnd, budgetTotal, currency, risk}` |
| `GET /cost-intelligence/recommendations?currency` | COST_READ | Counterfactuals, decimal-string money |
| `POST .../recommendations/{id}/acknowledge` | COST_READ | OPEN→ACKNOWLEDGED |
| `POST .../recommendations/{id}/dismiss` | COST_READ | OPEN/ACK→DISMISSED |
| `POST .../recommendations/{id}/mark-applied?routingPolicyId` | COST_READ | ACK→APPLIED + linkage |

Anomaly requires robust-z ≥ 3 AND |Δ%| ≥ 20 AND |Δ| ≥ currency
materiality. Forecast is `DAMPED_HOLT` (≥14 buckets) else
`RECENT_RUN_RATE` (≥3), confidence LOW/MEDIUM/HIGH. Budget risk
`LOW|WATCH|HIGH|OVER_BUDGET`; reservations never extrapolated. Savings
compare only within one logical model; `potential_saving(_percent)` are
counterfactual, never realized. Runs converge in MySQL
`(org, date, currency, version)`; scheduler proposes, DB disposes.
Currencies never mix (no FX). Advisor spend is excluded from trigger and
candidate roots by default.

## 5. AI Advisor (governed, metered)

| Method & path | Permission | Result |
|---|---|---|
| `GET /ai-advisor/profile` | AI_ADVISOR_USE | ACTIVE profile |
| `PUT /ai-advisor/profile` | AI_ADVISOR_MANAGE | New version ACTIVE, prev retired, identity ensured |
| `POST /ai-advisor/explanations` | AI_ADVISOR_USE | 201 PENDING job + attempt 1 |
| `GET /ai-advisor/explanations/{id}` | AI_ADVISOR_USE | Job + attempts + narrative |
| `POST /ai-advisor/explanations/{id}/retry` | AI_ADVISOR_USE | New attempt, fresh Gateway lineage |

Job states `PENDING|CLAIMED|DISPATCHING|RUNNING|COMPLETED|FAILED` with
fencing token + 5-minute lease. Claim commits before Provider I/O.
Lease-expired jobs without a Gateway link are safely reclaimable; linked
jobs converge on the existing execution — never blind-redispatch when
`BILLABLE_POSSIBLE`. Every attempt creates its own Gateway request, budget
admission, usage, settlement and Ledger lineage (advisor spend is queryable
and inside org totals). `INTERNAL_SYSTEM` credentials cannot authenticate
through the public bearer filter. Output schema is narrative-only
(`summary, driversExplanation, recommendedActions, warnings,
factReferences`); money fields, unknown fields, unknown fact refs and
oversize/control characters are rejected; only validated narrative persists.
Provider failures degrade only the explanation — deterministic intelligence
stays available.

## 6. Stable error codes (V3 additions)

`ENDPOINT_BLOCKED, PROVIDER_UNAVAILABLE, MODEL_NOT_VERIFIED,
PRICING_NOT_READY, ROUTING_NOT_READY` plus probe/transport codes
`DNS_FAILED, TLS_FAILED, CONNECTION_TIMEOUT, AUTHENTICATION_FAILED,
MODEL_NOT_FOUND, PROTOCOL_UNSUPPORTED, RATE_LIMITED, INVALID_RESPONSE,
SSRF_TARGET_REJECTED` (transport-mapped). Raw provider bodies are never
surfaced.

## 7. Permissions (V3 additions)

`AI_ADVISOR_USE` (trigger/retry explanations),
`AI_ADVISOR_MANAGE` (configure profile/scope); granted to SYSTEM_ADMIN and
FINANCE_ADMIN. `COST_READ` never implies model-invocation spend.

## 8. Audit events (V3 additions)

`PROVIDER_CONNECTION_CREATED / _REVISION_CREATED / _ACTIVATED / _PROBED,
PROVIDER_CREDENTIAL_CREATED / _ROTATED / _REVOKED, PROVIDER_MODEL_DISCOVERED /
_PROBED / _PROMOTED, AI_ADVISOR_PROFILE_UPDATED,
AI_ADVISOR_EXPLANATION_REQUESTED / _COMPLETED / _FAILED,
SAVINGS_RECOMMENDATION_ACKNOWLEDGED / _DISMISSED / _APPLIED` — payloads
carry ids/labels/statuses/codes only.

## 9. Validation result

- `M18OpenApiContractTest`: frozen YAML paths ⊆ implemented controller
  paths and vice versa for the V3 scope; money fields are string/BigDecimal;
  no secret-bearing components in API projections.
- Migration chain V1→V25 verified on real MySQL 8.0 (clean apply),
  V23→V24 backfill equivalence (same endpoint/status), single-ACTIVE
  enforcement, org-private coexistence.
- `OpenCodeDirectOnlyTest`: poison-proxy zero-hit with upstream served
  (normal + streaming) and server-owned User-Agent asserted.
- `CustomEndpointValidatorTest`, `CostIntelligenceEnginesTest`,
  `AdvisorOutputValidatorTest`: green without containers.
- Container-backed suites (migration compat, endpoint authority, claim
  race, full module suites) are implemented for hosted CI; Docker is
  unavailable in this sandbox so they are NOT RUN here (see final report).

## 10. Non-goals restated

No V3 frontend, Responses/Messages/Gemini/Embeddings APIs, header/body
DSLs, browser proxy controls, private-endpoint enablement, FX, ML sidecars
or autonomous routing/savings activation. Reopening any frozen choice
requires a design change, not an implementation shortcut.
