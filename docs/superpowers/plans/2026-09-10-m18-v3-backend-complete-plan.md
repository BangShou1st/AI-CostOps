# M18 — V3 Backend Complete Implementation Plan

> Baseline: `2c2b53d1dd63a0bf32cb2f33efc42fec4339aba4` branch `feat/m18-v3-backend-complete`.
> Normative: `2026-09-10-m17-v3-model-provider-hub-cost-intelligence-design.md` + `2026-09-10-m17-v3-runtime-contract-clarifications.md` (clarifications win).
> No V3 frontend. V1-V23 immutable. V3 migrations from V24+.

## 0. Facts established from code (2026-09-10)

- Endpoint authority today: `RoutingPolicyMapper` resolves candidate base_url from global `provider_catalog.base_url`; `GatewayRequestService.DispatchResult` carries URL; `ChatCompletionController` builds `ProviderCallContext(adapterCode, providerAccountId, providerModelId, providerModelName, pricingVersionId, currency, baseUrl, credentialType, secret, routeDecisionId)`.
- `provider_model` / `model_catalog` are global (no org_id). `provider_credential` = API_KEY|BEARER_TOKEN encrypted. `gateway_credential.principal_type` = HUMAN_MEMBER|SERVICE. `gateway_route_attempt` has routing_policy lineage but no connection-profile lineage.
- Routing: `routing_policy` + `routing_policy_candidate(provider_account_id, provider_model_id)` with ACTIVE/DISABLED; eligibility = account ACTIVE + model ACTIVE/routing_eligible + credential ACTIVE + pricing ACTIVE + catalog ACTIVE.
- Gateway: WebFlux gateway module (`gateway/src/main/java/com/aicostops/gateway`), adapters `MimoChatAdapter` / `OpenAiChatAdapter` + `ProviderChatAdapterRegistry`; budget admission TX1 before DISPATCH_INTENT; no blind redispatch; BILLABLE_POSSIBLE semantics in `ProviderSafetyOutcome`.
- Backend: Spring Boot 4.1 / Java 21 / MyBatis mappers / Flyway V1-V23 / springdoc OpenAPI. Controllers under `/api/v1/*` with `AuthenticatedUser` + `PageResponse` conventions.

## 1. Work breakdown (TDD, checkpoint commits)

### P1 — V24 Provider Hub schema + endpoint-authority migration
Create `backend/src/main/resources/db/migration/V24__m18_provider_hub.sql`:
- `provider_connection_profile(org_id, provider_account_id, version, connection_kind BUILTIN|CUSTOM, template_code NULL|OPENCODE_ZEN|CUSTOM_OPENAI_COMPATIBLE, protocol_code, base_url, completion_path, models_path, auth_type BEARER|API_KEY_HEADER|NONE, auth_header_name, network_policy DIRECT_ONLY|DIRECT_PUBLIC_ONLY, user_agent, timeouts, status DRAFT|ACTIVE|RETIRED, created_by, timestamps)`; UNIQUE(org,account,version); partial-unique single ACTIVE per account via `active_slot` generated column; immutability enforced in service (ACTIVE/RETIRED reject writes) + CHECK constraints.
- Backfill: for every ACTIVE provider_account with ACTIVE credential + eligible model + ACTIVE pricing, insert v1 ACTIVE profile from `provider_catalog.base_url` + adapter defaults (MIMO/OPENAI -> CUSTOM_OPENAI_COMPATIBLE protocol OPENAI_CHAT_COMPLETIONS, DIRECT_PUBLIC_ONLY, completion `/chat/completions`). Idempotent `INSERT ... SELECT ... WHERE NOT EXISTS`.
- Seed global catalog rows: `OPENCODE_ZEN` (adapter OPENCODE_ZEN, base `https://opencode.ai/zen/v1`), `CUSTOM_OPENAI_COMPATIBLE` (adapter CUSTOM_OPENAI, base `https://example.invalid` placeholder disabled).
- `model_catalog.owner_org_id NULL`, `model_catalog.namespace_key` generated helper for dual-namespace uniqueness; `provider_model.owner_org_id`, `provider_model.provider_account_id`, `provider_model.namespace_key` helper; global vs org-private uniqueness via helper columns (not MySQL NULL semantics).
- `provider_model_discovery(org_id, provider_connection_profile_id, provider_model_name, source LIVE_DISCOVERY|MANUAL, availability, protocol_code, pricing_classification, declared/verified capabilities JSON, last_seen/probed, probe status/error)` UNIQUE(org, profile, name).
- `gateway_route_attempt.provider_connection_profile_id NULL` + FK; old rows stay NULL.
- `gateway_credential.credential_origin USER_ISSUED|INTERNAL_SYSTEM` default USER_ISSUED; `service_identity` seed `AICOSTOPS_ADVISOR` per org lazily in service (not migration).
Tests: `V24MigrationTest` (Flyway clean migrate), `V23ToV24CompatibilityTest` (pre-V24 MiMo/OpenAI fixture -> migrate -> same endpoint/eligibility + ACTIVE v1).

### P2 — Connection versioning domain + API
Create backend package `com.aicostops.providerhub` (api/application/domain/infrastructure):
- `ProviderConnectionService`: create(DRAFT v-next monotonic), updateDraft (ACTIVE/RETIRED immutable reject), activate (transaction: retire previous ACTIVE + activate target DRAFT, `SELECT ... FOR UPDATE` on account), probe (explicit only, bounded timeouts, SSRF pre-check, no secret log).
- `ProviderTemplateRegistry`: OPENCODE_ZEN (server-owned base/models path/UA/DIRECT_ONLY/BEARER) + CUSTOM_OPENAI_COMPATIBLE defaults; templates own restrictions (non-overridable fields rejected).
- `CustomEndpointValidator` (see P4) invoked on create/update/activate/probe.
- Controllers: `GET /api/v1/provider-templates`, `GET/POST /api/v1/provider-connections`, `GET/PUT /api/v1/provider-connections/{id}`, `POST .../revisions`, `POST .../activate`, `POST .../probe`, credential create/rotate/revoke passthrough to existing `provider_credential` semantics with safe projection (never return ciphertext/secret).
- RBAC: PROVIDER_ACCOUNT_READ (reads/probe), PROVIDER_ACCOUNT_MANAGE (mutate). Audit events PROVIDER_CONNECTION_*.
Tests: versioning single-ACTIVE, ACTIVE immutability, monotonic versions, cross-org isolation, secret-safe projection, forbidden-header rejection.

### P3 — Org-private models + discovery/probe/promotion
- `ModelDiscoveryService`: refresh (upsert AVAILABLE + mark missing UNAVAILABLE, never delete), manual register, probe capabilities (CHAT_COMPLETIONS/SSE_STREAMING/USAGE/STRUCTURED_JSON -> VERIFIED|UNSUPPORTED|UNKNOWN; user can never set VERIFIED directly), promote (transaction: resolve/create org-private logical model + org-private provider_model bound to exact account; reject private key shadowing global key; verify pair (account,model) on routing activation).
- Endpoints: `GET/POST .../models`, `POST .../models/refresh`, `POST .../models/manual`, `POST .../models/{discoveryId}/probe`, `POST .../models/{discoveryId}/promote`.
- Manifest: `OpenCodeManifest` (manifest_version, entries: model matcher, protocol, pricing_class VERIFIED_FREE|PAID|UNKNOWN, verified_at); `-free` suffix never implies zero price; VERIFIED_FREE only offers DRAFT zero-price version creation.
Tests: discovery refresh semantics, manual model, missing->UNAVAILABLE, probe caps, promotion isolation (A/model-x vs B/model-x; orgA vs orgB), shadow-global rejection, routing pair check.

### P4 — SSRF + Custom auth boundary
Create `CustomEndpointValidator` (backend) + gateway `SsrfSafeHttpClientFactory`:
- Reject: localhost/loopback, RFC1918, link-local 169.254/0, cloud metadata, IPv6 loopback/link-local/unique-local, multicast, userinfo, non-http(s); validate hostname syntax, DNS A/AAAA initial + every redirect hop; anti-rebinding: resolve-then-connect with IP pinning strategy per current stack (Apache HttpClient / Reactor Netty custom DNS resolver; keep TLS SNI/verification on).
- Redirects: max 3, re-validate each hop, same timeout budget.
- Auth: BEARER/API_KEY_HEADER/NONE typed config; forbidden headers blocklist (Authorization, Proxy-*, Host, Content-Length, Connection, Cookie, Set-Cookie, Transfer-Encoding); Bearer Authorization server-generated.
- No `allowLocalhost` production flag; test-only seam `TestEndpointBypass` (package-visible, `@VisibleForTesting`, never wired in prod config).
Tests: parameterized SSRF (localhost, 127/8, 10/8, 172.16/12, 192.168/16, 169.254.169.254, ::1, fe80::/10, fc00::/7, userinfo, ftp:, DNS->private via fake resolver, redirect->private via WireMock), forbidden headers, auth modes.

### P5 — Gateway endpoint-authority switch + adapters
Modify gateway:
- `GatewayReadMapper`/`RoutingPolicyResolver`/`ResolvedRoutingPolicy.Candidate`: add `providerConnectionProfileId, baseUrl, completionPath, protocolCode, networkPolicy, adapterCode` from ACTIVE profile (JOIN provider_connection_profile status=ACTIVE). `provider_catalog.base_url` no longer read at dispatch.
- `ProviderCallContext`: add `providerConnectionProfileId, completionPath, protocolCode, networkPolicy`.
- `GatewayRequestMapper.insertRouteAttempt`: persist `provider_connection_profile_id` (required for new attempts; NULL only for pre-V24 history).
- New adapters: `OpenCodeZenChatAdapter` (OPENAI_CHAT_COMPLETIONS payload, server-owned UA, DIRECT_ONLY transport) + `GenericOpenAiCompatibleChatAdapter` (bounded body, validated auth header injection). Registry: MIMO->Mimo, OPENAI->OpenAi, OPENCODE_ZEN->Zen, CUSTOM_OPENAI_COMPATIBLE->Generic.
- `GatewayWebClientConfiguration`: two WebClient builders — `directPublicOnly` (SSRF-validated per-call) and `directOnly` (proxy-disabled: `System.clearProperty` not needed; build Reactor Netty `HttpClient` with `.noProxy()`, ignore env `HTTP(S)_PROXY`/`ALL_PROXY` and JVM proxy props).
- `ChatCompletionController`/orchestrator: pass connection fields; route-attempt lineage frozen at dispatch.
Tests: routing equivalence (MiMo/OpenAI same endpoint post-V24), lineage persisted, legacy base_url ignored (mutate catalog base_url -> dispatch unchanged), adapter registry mapping.

### P6 — OpenCode DIRECT_ONLY proof
- `OpenCodeDirectOnlyTest` (gateway integration): poison proxy stub on 127.0.0.1:7897 (record hits), controlled upstream WireMock (record hits); set env/JVM proxy props to poison; run normal + streaming calls via Zen adapter; assert upstream>0 and poison==0.
- Deterministic fallback: if sandbox cannot bind 7897, assert Netty `proxyProvider==null` + `System.getProperty(http.proxyHost)` ignored via custom `ProxyProvider` absence check + documented evidence. Prefer real bind.

### P7 — Cost Intelligence (deterministic)
Create `com.aicostops.intelligence`:
- `cost_intelligence_run(org_id, analysis_date, currency, run_version, status PENDING|RUNNING|COMPLETED|FAILED, started, completed, failure_code)` UNIQUE(org,date,currency,version); Spring scheduler + DB claim (SELECT FOR UPDATE SKIP LOCKED) for multi-replica convergence.
- `CostAnomalyEngine`: grain org/project/provider/logical-model; 28 completed daily buckets; min 14; median+MAD+robust-z; thresholds (z + pct + absolute materiality); BigDecimal money; contribution ranking. Golden fixtures `anomaly-golden-*.json`.
- `ForecastEngine`: DAMPED_HOLT primary + RECENT_RUN_RATE fallback; output value/currency/method/window/confidence(LOW|MED|HIGH)/staleness; golden fixtures.
- `BudgetRiskService`: Immediate Exposure = actual+commitments+reservations; Projected = actual+commitments+forecast; risk LOW|WATCH|HIGH|OVER_BUDGET deterministic thresholds; no reservation extrapolation.
- `SavingsEngine`: only same logical-model semantics; replay historical usage dims (INPUT/OUTPUT/CACHED/REQUEST) through current vs candidate ACTIVE pricing; BigDecimal counterfactual; LLM never computes money.
- `RecommendationService`: lifecycle OPEN|ACK|DISMISSED|APPLIED|EXPIRED; store evidence window/pricing lineage/costs/fingerprint/calculated_at; immutable history (new row per recompute); apply links routing revision (no auto-activation).
- APIs: `GET /api/v1/cost-intelligence/summary|anomalies|forecasts|budget-risks|recommendations`, `POST .../recommendations/{id}/acknowledge|dismiss|mark-applied`. RBAC COST_READ/BUDGET_READ. Currency never mixed (group by currency).
Tests: anomaly goldens + MAD edge (zero-MAD) + materiality gate + insufficient history; forecast goldens + fallback; budget risk math; savings BigDecimal + compatibility gate; recommendation lifecycle immutability.

### P8 — Governed AI Advisor
Create `com.aicostops.advisor` (backend) + gateway internal worker:
- `advisor_profile(org_id UNIQUE ACTIVE, provider_model_id, project_id, financial_scope, budget_mode, version, updated)`; one ACTIVE per org.
- `advisor_inference_job(id, org_id, requested_by, subject_type/id, evidence_fingerprint, profile_version, status PENDING|CLAIMED|DISPATCHING|RUNNING|COMPLETED|FAILED, claim_token, claim_expires_at, gateway_request_id, attempt_count, failure_code, timestamps)` + `advisor_inference_attempt` append-only children.
- `AdvisorEvidenceBuilder`: bounded typed envelope (subject/amounts/baseline/delta/drivers/forecast/risk/savings/fact refs); money precomputed.
- `AdvisorOutputValidator`: strict schema (summary/driversExplanation/recommendedActions/warnings/factReferences), length bounds, fact-ref existence, control-char sanitize; persist only validated narrative; never raw body/reasoning/secrets/prompts.
- Claim: `SELECT ... FOR UPDATE SKIP LOCKED` eligible PENDING/expired-lease-without-gateway-link; set CLAIMED + token + lease; commit BEFORE any Provider I/O. Link gateway_request_id transactionally; deterministic internal idempotency key `advisor:{jobId}:{attempt}`.
- Crash recovery: A (no gateway link + lease expired -> PENDING) vs B (gateway linked -> converge, never redispatch; BILLABLE_POSSIBLE stays FAILED-uncertain).
- Retry: explicit POST creates new attempt + new gateway lineage; SAFE_NO_BILLABLE_EXECUTION may use existing safe failover only.
- Gateway: `credential_origin INTERNAL_SYSTEM` rejected by `GatewayBearerWebFilter`; internal trusted path `InternalAdvisorDispatcher` uses service identity AICOSTOPS_ADVISOR; advisor usage flows through normal reservation/usage/settlement/ledger; anomaly/savings exclude `AICOSTOPS_ADVISOR` by default (feedback-loop guard) while org totals include it.
- APIs: `GET/PUT /api/v1/ai-advisor/profile`, `POST /api/v1/ai-advisor/explanations`, `GET .../{id}`, `POST .../{id}/retry`. RBAC AI_ADVISOR_USE (invoke/retry) / AI_ADVISOR_MANAGE (profile); COST_READ alone cannot invoke.
Tests: RBAC matrix, envelope bounds, output validation (oversize/unknown fact refs/control chars), INTERNAL_SYSTEM external-auth rejection, claim race (two workers converge one gateway request), crash-before-link reclaim, crash-after-link no-redispatch, explicit retry new lineage, advisor cost in ledger, failure isolation (provider 429/timeout -> deterministic results intact), feedback-loop exclusion.

### P9 — RBAC / Audit / Errors / OpenAPI freeze
- Seed permissions `AI_ADVISOR_USE`, `AI_ADVISOR_MANAGE` (V25 migration) + role grants (admin gets both; viewer gets neither).
- `AuditProducer` events: connection create/revision/activate, credential create/rotate/revoke, discovery refresh/promote/disable, advisor profile/request/complete/fail/retry, recommendation ack/dismiss/apply. Payload = IDs/labels/status/template/model id/error code only.
- Error codes: ENDPOINT_BLOCKED/SSRF_TARGET_REJECTED/DNS_FAILED/TLS_FAILED/TIMEOUT/AUTH_FAILED/MODEL_NOT_FOUND/PROTOCOL_UNSUPPORTED/RATE_LIMITED/PROVIDER_UNAVAILABLE/INVALID_RESPONSE/MODEL_NOT_VERIFIED/PRICING_NOT_READY/ROUTING_NOT_READY; never raw provider body.
- OpenAPI: springdoc annotations on new controllers; money = string (BigDecimal serialized as plain string via Jackson config check); secrets never in schemas; `openapi-contract` test asserting paths + enums + error codes; generate `docs/03-acceptance/m18-backend-contract-freeze.md`.

### P10 — Verification + push
- Run: backend unit+integration (Testcontainers MySQL/Redis), gateway unit+integration+architecture, Flyway validate, OpenAPI contract, frontend existing build (no V3 UI). Record exact commands/exit codes/counts.
- Self-review per task sections 48-50; fix P0/P1; `git push origin feat/m18-v3-backend-complete` (no force/merge/PR).

## 2. Migration order
V24 provider hub (+connection profile backfill, catalog seeds, org-private helpers, discovery, route-attempt lineage, credential_origin) -> V25 intelligence/advisor (runs, anomalies, forecasts, recommendations, advisor profile/job/attempt, permissions). One concern per migration file; no edits to V1-V23.

## 3. Backend/Gateway split
Backend owns: profiles, templates, discovery/probe/promotion orchestration, SSRF policy check, pricing/routing readiness, intelligence math, advisor envelope/jobs/audit/RBAC/OpenAPI. Gateway owns: ACTIVE-profile candidate resolution, connection-frozen dispatch, adapter transport (DIRECT_ONLY vs DIRECT_PUBLIC_ONLY), usage/settlement/ledger attribution, INTERNAL_SYSTEM rejection + internal advisor path.

## 4. Interfaces
`ProviderTemplateRegistry.get(code)` -> template defaults+restrictions; `CustomEndpointValidator.validate(url, networkPolicy)` -> allow/reject+code; `ProviderConnectionService` CRUD/activate/probe; `ModelDiscoveryService` refresh/manual/probe/promote; `CostAnomalyEngine.detect(series)`; `ForecastEngine.forecast(series)`; `BudgetRiskService.assess(...)`; `SavingsEngine.compare(...)`; `AdvisorEvidenceBuilder.build(...)`; `AdvisorJobService.request/claim/link/complete/fail/retry`; `AuditProducer.emit(...)`. Gateway consumes `ResolvedCandidate(connectionProfileId, baseUrl, completionPath, protocol, networkPolicy, adapter)`.

## 5. Verification matrix (maps to task section 43 + 49)
See P1-P9 tests; final gate: full backend+gateway suites green on final HEAD, Flyway validate, OpenAPI contract, poison-proxy 7897 zero-hit, SSRF suite, golden fixtures deterministic, advisor crash/idempotency, no TODO/TBD/placeholder in new code, no V3 frontend files.

## 6. Risks
HTTP-client IP-pinning for anti-rebinding depends on stack (Reactor Netty custom resolver); if full pinning is infeasible, implement resolve-then-validate + short-TTL + redirect re-validation and document residual risk honestly (never claim 0 without evidence). 7897 bind may be sandbox-limited -> provide deterministic transport-config proof + real-bind attempt log.
