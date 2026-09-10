# M18 — V3 Backend / OpenAPI Contract Freeze (Fourth Repair Candidate)

> Status: **FINAL REPAIR CANDIDATE — final repair round, pending GPT-5.6 Sol acceptance (do not mark FROZEN)**.
> (fourth-review findings on HEAD `d8bbf1e5b69dbc2e66f845ac6aa0909902508ebc`)**. Branch: `feat/m18-v3-backend-complete`.
> Machine contract: [`m18-openapi.yaml`](./m18-openapi.yaml). Gate:
> `backend/src/test/java/com/aicostops/contract/M18OpenApiContractTest.java`.
>
> This revision is the final repair for Issue #155 fourth review (reviewed HEAD
> `d8bbf1e5b69dbc2e66f845ac6aa0909902508ebc`, P0=0/P1=4/P2=1). It closes all remaining
> items with fail-closed semantics and preserves all prior regressions. Do not treat as
> FROZEN until GPT-5.6 Sol accepts the new remote HEAD. Migration chain remains V1-V27.

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
| `POST /provider-connections/{id}/probe` | PROVIDER_ACCOUNT_MANAGE | `{status:PASS|FAIL, errorCode?, checkedAt}` with configured credential and server-owned UA |
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
| `POST .../{id}/models/refresh {fetchLive:true}` | PROVIDER_ACCOUNT_MANAGE | Provider-backed live snapshot only; client modelNames never become LIVE_DISCOVERY; `fetchLive:false` is rejected with zero mutation; catalog over 500 fails closed with zero mutation |
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
| `POST .../recommendations/{id}/acknowledge` | BUDGET_MANAGE | OPEN→ACKNOWLEDGED |
| `POST .../recommendations/{id}/dismiss` | BUDGET_MANAGE | OPEN/ACK→DISMISSED |
| `POST .../recommendations/{id}/mark-applied?routingPolicyId` | BUDGET_MANAGE | ACK→APPLIED + validated same-org ACTIVE linkage (human action only) |

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

## 10. M18 repair deltas (Issue #155, old HEAD `6ec8012`)

- P0 Advisor identity: every ACTIVE profile revision rotates a fresh `INTERNAL_SYSTEM`
  credential (`gateway_credential.advisor_profile_id`, V26) carrying exact project / financial
  scope / budget mode / allowed model; predecessors REVOKED; worker uses ACTIVE-profile-bound
  row and fails on `PROFILE_CREDENTIAL_MISMATCH` (never `LIMIT 1` reuse).
- P1 org isolation: provider-model visibility is global OR same-org private; `financialScopeId`
  ownership/type/ACTIVE validated per PROJECT/TEAM/COST_CENTER; cross-org private rejected.
- P1 DNS rebinding: gateway dispatch uses transport-level `PublicOnlyAddressResolverGroup`
  (public-unicast-only, hostname preserved for Host/SNI/cert); control-plane re-validates with
  `resolvePublicAddresses` immediately before connect; redirects re-validated per hop.
- P1 redirect secrets: authenticated cross-origin redirects rejected (`ENDPOINT_BLOCKED`) without
  forwarding Bearer/API-key; same-origin redirects still re-validated.
- P1 discovery/probe: live `/models` and connection probe share bounded authenticated transport
  (base/paths/policy/credential/server UA `opencode/1.18.21`/timeouts/SSRF/body bound);
  `fetchLive=true` and probe require `PROVIDER_ACCOUNT_MANAGE` before any I/O; provider failure
  never masquerades as empty catalog (only successful snapshots mark UNAVAILABLE).
- P1 OpenCode: server-owned UA unified to `opencode/1.18.21` (template/adapter/probe/discovery
  single source of truth); `noProxy()`/DIRECT_ONLY retained with poison-proxy proof.
- P1 ledger truth: cost series are effective POSTED `ledger_entry.amount` lineage (settlement
  postings + signed correction reversal/replacement + reconciliation adjustments); provider/model
  grains require exact settlement lineage (CASE_FULL adjustments excluded there, included in
  org/project/team/cost-center via entry dimensions); currencies never mixed; advisor excluded
  from trigger roots only.
- P1 budget risk: TEAM/COST_CENTER use own forecast grains (no org fallback; missing history =
  zero future usage).
- P1 savings: counterfactual replay uses the real source-route usage vector against every
  production-ready candidate (zero-usage cheaper candidates still recommendable); readiness gates
  require ACTIVE account/model/routing-eligible/connection/pricing/routing/correct ownership and
  credential-or-NONE.
- P1 recommendations: ACK/DISMISS/APPLIED require `BUDGET_MANAGE` (COST_READ never mutates);
  `mark-applied` validates same-org ACTIVE policy, matching logical model and ACTIVE candidate
  linkage; records human action only, never auto-creates policy.
- P1 advisor evidence: `POST /ai-advisor/explanations` accepts subjectType/subjectId only;
  backend loads deterministic facts by org and fingerprints the envelope (malicious money/refs
  rejected; foreign/missing subjects rejected).
- P1 streaming: bounded `stream=true` probe requires `text/event-stream` plus a legal chunk;
  VERIFIED/UNSUPPORTED/UNKNOWN per capability (never inferred from non-streaming).

## 11. Non-goals restated

No V3 frontend, Responses/Messages/Gemini/Embeddings APIs, header/body
DSLs, browser proxy controls, private-endpoint enablement, FX, ML sidecars
or autonomous routing/savings activation. Reopening any frozen choice
requires a design change, not an implementation shortcut.

## 12. Third repair round deltas (Issue #155 second review, reviewed HEAD `8ceea10`)

- P0 job-profile freeze (V27): `advisor_inference_job.advisor_profile_id` binds each job to
  its exact profile revision; `updateProfile` supersedes still-PENDING jobs as
  `FAILED/PROFILE_SUPERSEDED` (an old job never silently executes under a new profile);
  new requests bind exactly to the ACTIVE revision with a rotated execution principal.
- P1 immutable evidence (V27): `advisor_evidence_snapshot` persists the bounded
  server-generated facts/drivers/summary the model receives; the job fingerprint must equal
  the snapshot fingerprint before any Provider I/O (`EVIDENCE_INTEGRITY_FAILED`);
  the worker prompt is built from the snapshot only; client-supplied money is never trusted;
  foreign/missing subjects are rejected; snapshot money is stored as JSON strings so
  `12.50` never degrades to `12.5`.
- P1 savings flag (V27): `savings_recommendation.routing_change_required` marks candidates
  that are production-ready but not referenced by any ACTIVE routing revision; the
  recommendations API surfaces it as `routingChangeRequired` (boolean); APPLIED linkage
  still requires a human-created ACTIVE routing link.
- P1 ledger grains: `providerDaily` additionally attributes direct Provider Charge entries
  through the deterministic confirmed import lineage
  (`charge_fact → raw_provider_record → import_attempt → import_batch.provider_account_id`);
  charge entries without that lineage stay visible in org/project/team/cost-center totals
  but are never invented in the provider grain. Settlement/usage attribution tolerates a
  missing `gateway_request` row (LEFT JOIN; advisor exclusion preserved).
- Fix: advisor `INTERNAL_SYSTEM` credential prefix fits `gateway_credential.credential_prefix`
  `CHAR(12)` (`aic_` + 8 hex chars).
- Migration chain is now V1→V27 (27 successful migrations on clean MySQL 8.4).

## 13. Fourth repair round deltas (Issue 155 third review, reviewed HEAD c899c23)

Status: FOURTH REPAIR CANDIDATE, pending GPT-5.6 Sol independent review. P0 stays 0.
This round closes P1-1..P1-8 plus P2-1 with fail-closed semantics and Provider I/O zero on all integrity failures.

Evidence exact snapshot (P1-1/P1-2 finding A+B): canonical fingerprint is now one frozen JSON document covering schemaVersion, subjectType, subjectId, envelope currency, per-fact factId/label/amount-plain/currency, per-driver reference-id/dimension/key/delta-plain/currency, plus forecast/budgetRisk/savings summaries. Amounts use BigDecimal toPlainString. DB ids, timestamps, whitespace never hashed. Backend AdvisorEvidence (SCHEMA_VERSION 2) and gateway EvidenceFingerprint mirror byte for byte; frozen vector updated. Gateway derives allowed refs from verified snapshot only; job evidence_refs_json must equal snapshot-derived set, else EVIDENCE_INTEGRITY_FAILED with zero Provider I/O. Tamper negatives cover fact amount/label/currency, driver id/dimension/key/currency, summary, plus extra-id refs.

Retry lineage and attempt lifecycle (P1-2 findings C+D+E): explicit retry clears job gateway_request_id, claim_token, claim_expires_at, failure_code, started_at, completed_at and increments attempt_count, then inserts fresh PENDING attempt; old attempt rows stay immutable (FAILED+G1 preserved, new PENDING). Job+attempt gateway link is one short TransactionTemplate unit with fencing-token check and both-rows-affected assertion; Provider I/O only after commit; half-link rolls back to both NULL. Attempts move PENDING to RUNNING to COMPLETED/FAILED on both backend and gateway; terminal jobs never leave RUNNING attempts. Claim queries require gateway_request_id IS NULL for PENDING reclaim, so stale G1 can never be mistaken for new attempt.

Exact V27 credential (P2-1 finding F): V27 jobs with non-null advisor_profile_id use only the exact profile-bound ACTIVE credential; missing or revoked yields IDENTITY_MISSING with zero dispatch. Legacy jobs with null binding keep old fallback. Negative test proves another INTERNAL_SYSTEM credential with same scope is never substituted.

Public Internet policy (P1-3 finding G): backend CustomEndpointValidator and gateway DispatchEndpointGuard plus both resolver groups share one explicit globally-routable policy. IPv4 blocks 0/8, 10/8, 100.64/10, 127/8, 169.254/16, 172.16/12, 192.0.0/24, 192.0.2/24, 192.168/16, 198.18/15, 198.51.100/24, 203.0.113/24, 224/4, 240/4, 255.255.255.255. IPv6 blocks unspecified, loopback, ULA fc00/7, link-local fe80/10, multicast, documentation 2001:db8/32, non-2000/3 globals, with IPv4-mapped embedded checks. Tests pin 8.8.8.8 allowed and 10.0.0.1, 100.64.0.1, 127.0.0.1, 169.254.169.254, 192.0.2.1, 198.18.0.1, 203.0.113.1, ULA blocked on both sides. Controlled loopback stays test-only via injected lenient policy; no production bypass flag.

Discovery refresh (P1-4 finding H): POST models/refresh is live-only. fetchLive=false or absent body with client modelNames is VALIDATION_FAILED with zero mutation. Client names never become LIVE_DISCOVERY. Manual registration stays on POST models/manual with source MANUAL. OpenAPI updated.

Discovery parsing (P1-5 findings I+J): missing modelsPath yields VALIDATION_FAILED with zero Provider I/O and zero mutation (never empty snapshot). Parser is strict typed JSON for shape data:[id], bounded 500, id 1..200 chars, deduped. Invalid JSON, wrong shape, missing id, blank id all fail closed preserving prior availability. Valid empty data:[] is the only true empty catalog that may mark prior AVAILABLE UNAVAILABLE. Integration covers 500, malformed, wrong shape, missing id, valid empty, valid 2.

Capability probe (P1-6 findings K+L): streaming VERIFIED requires text/event-stream plus at least one legal choices-array chunk plus terminal data:[DONE]; truncated stream with chunk but no terminal is UNKNOWN, never VERIFIED; wrong content-type is UNSUPPORTED; malformed SSE is UNSUPPORTED/UNKNOWN. Non-stream CHAT_COMPLETIONS VERIFIED requires parsed root object with choices array, at least one choice with message.content string; USAGE VERIFIED requires numeric prompt_tokens/completion_tokens/total_tokens at least zero; STRUCTURED_JSON VERIFIED parses inner message.content JSON and requires boolean ok true. Substring matching removed. Negatives cover fake choices string, non-array choices, missing usage, truncated SSE.

Promotion gate (P1-8 finding M): promote requires AVAILABLE plus protocol OPENAI_CHAT_COMPLETIONS plus last_probe_status PASS plus verified capabilities containing CHAT_COMPLETIONS, else MODEL_NOT_VERIFIED. Manual unprobed and failed-probe promotes are rejected. Verified promote succeeds. No user-declared capability becomes routable.

Savings rate coverage (P1-7 finding N): replayable requires every positive-quantity usage dimension to have a valid rate with unit_quantity over zero and unit_price at least zero on both current and challenger. Missing rate makes candidate ineligible (skip, never zero). Explicit unit_price zero is valid. Unit tests pin missing INPUT+OUTPUT vs INPUT-only as not replayable, explicit zero as replayable with exact 50.00, plus invalid rates. Integration proves missing OUTPUT yields zero recommendations and explicit zero yields one. BigDecimal exactness preserved.

State-machine and control-plane sweeps (findings 20/21/22): retry/claim/link/pre-dispatch/post-dispatch/success/invalid-response/lease-expiry/supersede/crash paths checked for blind redispatch, stale G1, half-link, terminal mismatch, and PENDING carrying old failure. Discovery/probe/redirect/secret/DNS/proxy/body/timeout share one transport policy on both planes. Pricing dimensions INPUT/OUTPUT/CACHED/REQUEST validated; unknown positive-quantity dimensions fail closed.

Verification on final HEAD: backend mvn verify 1139 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS. Gateway mvn verify 84 tests, 0 failures, 0 errors, 0 skipped, BUILD SUCCESS. Frontend npm ci plus npm run build success with only chunk over 500kB warning. Migration V1 to V27 clean apply validated (27 migrations). git diff check clean. Hosted CI NOT RUN. Literal 127.0.0.1:7897 DEFERRED. Real Provider DEFERRED.

## 14. Final repair round deltas (Issue 155 fourth review, reviewed HEAD d8bbf1e)

Status: FINAL REPAIR CANDIDATE, pending GPT-5.6 Sol acceptance. P0 stays 0. This round closes P1-1..P1-4 plus P2-1.

Terminal convergence (P1-1): explicit transactional terminalizeAttempt for (job, current attempt) with fencing and both-rows-affected asserts; Provider I/O never inside. Backend complete/fail require exactly one row on both sides or rollback (no swallow, no ignored counts). updateProfile supersede fails PENDING jobs plus their PENDING attempts in the same DB transaction with equality assert. Gateway worker pre-dispatch, post-dispatch, success, invalid-narrative and stuck-linked paths all use short TransactionTemplate terminal units. MySQL evidence covers supersede, pre-dispatch evidence failure, injected attempt-failure rollback (completion and failure), success COMPLETED/COMPLETED, and retry G1/G2 immutability.

Catalog bound (P1-2): raw data.size() over 500 fails closed with INVALID_RESPONSE/PROVIDER_UNAVAILABLE and zero discovery mutations (no truncate, no partial UNAVAILABLE). Duplicate-heavy catalogs cannot bypass via unique-count checks. Integration proves 550 AVAILABLE preserved on overflow and 500-model success.

SSE legal chunk (P1-3): VERIFIED requires valid content-type plus at least one bounded legal Chat chunk plus terminal data:[DONE]. Legal chunk mirrors the Gateway wire contract (root object, choices array, choice objects, integer index when present, delta object, string role/content when present, string/null finish_reason). Structurally invalid chunks never VERIFY. Tests cover legal, non-object choice, empty choice, malformed delta, missing delta, truncated without DONE, and wrong content-type.

OpenCode manifest (P1-4, frozen M17 section 17): server-owned versioned OpenCodeModelManifest (MANIFEST_VERSION 2026-09-11-v1) with exact controlled matchers, independent protocol (OPENAI_CHAT_COMPLETIONS/UNSUPPORTED/UNKNOWN) and pricing (VERIFIED_FREE/PAID/UNKNOWN) classifications. No inference from -free or live availability. Live and manual discovery for OPENCODE_ZEN use manifest classification; unknown stays AVAILABLE/UNKNOWN but cannot Chat probe/promote. Promotion requires manifest Chat plus probe PASS plus verified CHAT. Pricing Version remains financial truth; VERIFIED_FREE never auto-creates pricing. Deterministic fixtures prove all five cases without paid traffic. Adapter stays DIRECT_ONLY with noProxy and opencode/1.18.21.

Freeze doc (P2-1): top status, reviewed HEAD, migration count V1-V27, live-only refresh semantics, manifest, terminal and probe semantics made mechanically consistent with runtime and OpenAPI. Status remains FINAL REPAIR CANDIDATE pending Sol acceptance (never ACCEPTED/FROZEN by implementation).
