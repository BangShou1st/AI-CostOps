# AIC-093 — M10 V2 Detailed Design Freeze Matrix

- Date: 2026-09-02
- Repository: `BangShou1st/AI-CostOps`
- Design branch: `docs/m10-v2-detailed-design`
- Baseline: `main@a144210c7110aa2b924b5ef5393686ba329537bd`
- V1 stable release: `v1.1.0` -> `102f287da9bfc922ffaabb1b7244a973a0f813eb`
- Principal PR: #129
- Local repository path: `E:\project\AI-CostOps`

## 1. Status vocabulary

Every topic below uses exactly one of:

```text
FROZEN
DEFERRED WITH EXPLICIT NON-BLOCKING BOUNDARY
BLOCKED
```

A correctness/API/data-ownership choice may not be deferred to implementation.

---

## 2. Financial and runtime invariants

| Topic | Status | Frozen decision |
|---|---|---|
| Final financial truth | **FROZEN** | MySQL remains durable identity/financial truth; Redis is never Ledger/Budget/Settlement final truth |
| Ledger history | **FROZEN** | POSTED immutable; corrections append-only |
| Already-incurred Provider cost | **FROZEN** | Never silently discarded or converted to zero because of budget, reservation, routing, Redis/MySQL transient failure, timeout or disconnect |
| Runtime topology | **FROZEN** | One monorepo; Backend Spring MVC Control Plane + Gateway Spring WebFlux/Reactor Netty Data Plane; two deployables; one financial truth |
| Gateway blocking DB boundary | **FROZEN** | Narrow synchronous JDBC/MyBatis transaction seams are offloaded from Reactor Netty event loops; R2DBC is not a V2 Core prerequisite |
| Schema migration owner | **FROZEN** | Backend/Control Plane is sole production Flyway owner; Gateway startup only checks schema compatibility |
| DB privilege boundary | **FROZEN** | Gateway runtime DB identity cannot write Ledger, Budget Actual, Commitment usage, final Settlement or Period close/reopen state |
| MQ dependency | **FROZEN** | No MQ required for correctness; DB-backed discovery/recovery is authoritative; future wake-up notification may be lossy |

---

## 3. Identity, credential, catalog and pricing

| Topic | Status | Frozen decision |
|---|---|---|
| Principal model | **FROZEN** | `HUMAN_MEMBER` reuses organization member; `SERVICE` uses narrow `service_identity` |
| Gateway credential storage | **FROZEN** | Raw key returned once; lookup prefix + versioned HMAC-SHA-256 digest only; dedicated external digest key |
| Gateway key format | **FROZEN** | `aic_<12 lowercase Crockford-Base32>_<43 Base64URL chars>` with 32-byte random secret |
| Provider credential | **FROZEN** | Encrypted at rest under authenticated encryption / external versioned KEK; never client-visible/plaintext persisted |
| Credential financial scope | **FROZEN** | One Project ownership context plus exactly one financial target: PROJECT/TEAM/COST_CENTER |
| Budget enforcement | **FROZEN** | Credential-owned `REQUIRED` or `OPTIONAL`; inference client cannot override |
| Model access | **FROZEN** | Explicit-only credential-model relation; empty relation does not mean all models |
| Logical vs Provider model | **FROZEN** | Client logical model is separated from Provider wire model id |
| Provider endpoint | **FROZEN** | Server-governed catalog only; client cannot provide arbitrary base URL; production external Provider is HTTPS |
| Pricing version | **FROZEN** | Immutable after use; route attempt freezes exact Provider Account/Model/Pricing Version |
| Pricing dimensions | **FROZEN** | V2 Core typed set: INPUT_TOKEN, OUTPUT_TOKEN, CACHED_INPUT_TOKEN, REQUEST |
| FX | **FROZEN** | No implicit FX; Budget/Reservation/Settlement currency must match pricing for budget-controlled request |

---

## 4. Request identity and idempotency

| Topic | Status | Frozen decision |
|---|---|---|
| Public request id | **FROZEN** | `gwr_<lowercase UUIDv4>` |
| Idempotency header | **FROZEN** | Billable `POST /v1/chat/completions` requires `Idempotency-Key`, 1..128 visible ASCII chars |
| Raw idempotency retention | **FROZEN** | Raw key not persisted/logged; keyed digest stored in MySQL |
| Request fingerprint | **FROZEN** | Domain-separated keyed HMAC over exact accepted method/path/raw UTF-8 JSON bytes; prompt body itself not retained |
| Duplicate same request | **FROZEN** | Converges to same durable request; never creates duplicate reservation/Provider dispatch/Settlement/Ledger |
| Same key different body | **FROZEN** | Deterministic 409 `GATEWAY_IDEMPOTENCY_CONFLICT` |
| Response replay | **FROZEN** | Financial idempotency does not imply stored completion replay; response body is not persisted by default |
| Dispatch safety fence | **FROZEN** | Durable `DISPATCH_INTENT` commits before potentially billable Provider I/O |
| Post-fence client replay | **FROZEN** | Never blindly re-dispatches Provider merely because original response was lost |
| Request vs route truth | **FROZEN** | `gateway_request` owns stable client/business identity; Provider/Pricing truth belongs to append-only `gateway_route_attempt` |

---

## 5. Budget Reservation and Redis

| Topic | Status | Frozen decision |
|---|---|---|
| Reservation authority | **FROZEN** | MySQL-authoritative; Redis-only monetary reservation rejected |
| Availability equation | **FROZEN** | Total - Actual - Outstanding Commitments - Effective Active Reservations |
| Serialization | **FROZEN** | Reservation uses same Budget-row locking domain as V1 Actual/Commitment mutation |
| Budget selection | **FROZEN** | Exact financial scope + same currency -> ORG same-currency fallback -> no Budget |
| Reservation identity | **FROZEN** | Per Route Attempt; `UNIQUE(org, route_attempt)` plus one effective ACTIVE/PENDING_HOLD per request |
| Reservation estimate | **FROZEN** | Defensible enforced upper bound; no average chars/token financial guess |
| Output ceiling | **FROZEN** | Every dispatched request has finite governed maximum output tokens enforced upstream |
| Reservation overrun | **FROZEN** | Full incurred Actual still posts; never cap Ledger to reserved amount; emit overrun/reconciliation evidence |
| Commitment | **FROZEN** | Binding is explicit and governed; never infer/consume unrelated Commitment |
| TTL | **FROZEN** | Recovery trigger only; possible-billable expiry becomes PENDING_HOLD instead of blind release |
| Post-settlement cleanup lag | **FROZEN** | Durably SETTLED request economically excludes old hold immediately; later Gateway lifecycle cleanup may mark FINALIZED |
| Redis roles | **FROZEN** | Rate/quota, short cache, provider health/circuit, expiry hints and ephemeral coordination only |

---

## 6. Streaming, Provider and metering

| Topic | Status | Frozen decision |
|---|---|---|
| Provider Adapter boundary | **FROZEN** | Owns Provider wire/request/response/stream/usage/error semantics; never owns final financial posting |
| Streaming | **FROZEN** | SSE/backpressure/client disconnect/provider disconnect/separate timeout classes are first-class |
| Metering statuses | **FROZEN** | Exactly FINAL / INCOMPLETE / UNKNOWN |
| Missing usage | **FROZEN** | Never defaults to zero; public success `usage` may be absent while financial metering becomes INCOMPLETE/UNKNOWN |
| Usage storage | **FROZEN** | Append-only `gateway_usage_fact` + typed `gateway_usage_dimension`; later evidence appends rather than rewrites |
| FINAL uniqueness | **FROZEN** | At most one realtime FINAL fact per request before Settlement; later invoice difference is M15 reconciliation/correction |
| Effective time | **FROZEN** | Explicit `usage_effective_at` + source; dispatch-intent period is normal financial fence/Settlement period; Provider timestamp retained as reconciliation evidence |
| Provider request id | **FROZEN** | Retain when available for support/reconciliation; not a replacement for AI-CostOps request id |
| Content retention | **FROZEN** | Prompt/completion not persisted for metering/reconciliation by default |
| Post-dispatch MySQL outage | **FROZEN** | Bounded persistence retry only; never second Provider dispatch; lost exact usage degrades to explicit UNKNOWN/reconciliation |

---

## 7. Settlement, Ledger and close

| Topic | Status | Frozen decision |
|---|---|---|
| Settlement owner | **FROZEN** | Backend DB-backed worker is sole writer of final `gateway_settlement` |
| Settlement states | **FROZEN** | PENDING / RETRYABLE_FAILED / RECONCILIATION_REQUIRED / SETTLED |
| Settlement uniqueness | **FROZEN** | One request/current FINAL usage -> at most one Settlement; DB uniqueness enforces convergence |
| Ledger source | **FROZEN** | Add first-class `GATEWAY_SETTLEMENT`; do not manufacture Import/RawRecord/ChargeFact lineage |
| Ledger actor | **FROZEN** | Add MEMBER/SYSTEM actor semantics; automated Settlement uses SYSTEM, never fake human member |
| Financial target | **FROZEN** | One PROJECT/TEAM/COST_CENTER Ledger target frozen before dispatch; no split allocation in V2 Core |
| Financial transaction | **FROZEN** | Settlement status + Ledger + Budget Actual + explicit Commitment consume + Audit commit atomically in MySQL |
| Lock order | **FROZEN** | BillingPeriod -> sorted Budgets -> sorted Commitments -> Gateway Reservation/Settlement source -> V1 source/allocation when applicable -> Ledger uniqueness/insertion |
| Accounting precision | **FROZEN** | Raw calc/delta DECIMAL(38,18); posted Ledger/Budget DECIMAL(20,8); positive incurred/reserved amount rounds away from zero/up at scale 8 |
| Replay after commit/lost response | **FROZEN** | Stable Settlement/Ledger keys converge without duplicate Actual/Commitment/Audit |
| Period close race | **FROZEN** | Gateway DISPATCH_INTENT and Close use same BillingPeriod financial fence |
| Gateway Close blocker | **FROZEN** | Possible-billable unresolved request/usage/Settlement/PENDING_HOLD blocks normal Close |
| Historical closed-period anomaly | **FROZEN** | RECONCILIATION_REQUIRED; no automatic period bypass/reopen |

---

## 8. Routing and resilience

| Topic | Status | Frozen decision |
|---|---|---|
| Route attempt history | **FROZEN** | Append-only `gateway_route_attempt` exists from M11 even with one Provider |
| Retry safety | **FROZEN** | Positive evidence required for SAFE_NO_BILLABLE_EXECUTION; unknown safety = BILLABLE_POSSIBLE |
| Failover | **FROZEN** | Another route only after every previous attempt is proven safe and old reservation released; new price/currency re-reserves |
| Hedging | **FROZEN** | Parallel billable hedging forbidden in V2 Core |
| Routing evolution | **FROZEN** | M11 single candidate -> M14 static deterministic ordering -> health-aware; complex cost/latency policy only with evidence |
| Circuit/health state | **FROZEN** | Runtime state may be Redis/local and cannot change financial truth |
| M11 MiMo retry policy | **FROZEN** | No automatic Provider re-dispatch after committed DISPATCH_INTENT; generic Provider 429/5xx retry guidance is not treated as proof of non-billable execution |

---

## 9. Security, privacy, observability and deployment

| Topic | Status | Frozen decision |
|---|---|---|
| Prompt/completion privacy | **FROZEN** | Default transient-only; not ordinary DB/log/audit/metric/S3 evidence |
| Secret separation | **FROZEN** | Gateway digest key, request HMAC key, Provider KEK and JWT keys are distinct secrets |
| Error redaction | **FROZEN** | Arbitrary Provider error bodies are not logged/returned; allowlisted status/code/request id only |
| SSRF boundary | **FROZEN** | Client cannot choose Provider host/scheme; redirects require approved destination validation |
| Production validator | **FROZEN** | Fail startup on unsafe/missing Gateway security/config/dependency/bounds |
| Resource bounds | **FROZEN** | Request/header/concurrent stream/blocking-DB queue/pool/buffer/timeouts are bounded |
| Metrics | **FROZEN** | Bounded enum/catalog labels only; no request/org/user/credential ids as metric labels |
| Backup/restore | **FROZEN** | Durable Gateway/Settlement evidence is in MySQL backup domain; Redis not required to reconstruct financial truth |
| Durable retention | **FROZEN** | V2 Core has no automatic purge of Gateway financial/request evidence before separately reviewed archive/tombstone policy |

---

## 10. API, schema and migration contract

| Topic | Status | Frozen decision |
|---|---|---|
| Machine API ownership | **FROZEN** | Control Plane `openapi.yaml` `/api/v1`; Gateway `gateway-openapi.yaml` `/v1`; a path belongs to one contract only |
| Gateway compatibility claim | **FROZEN** | Explicit bounded OpenAI Chat Completions subset, not Full OpenAI conformance |
| M11 request subset | **FROZEN** | `model`, text `messages`, optional `max_completion_tokens`, `stream`; unknown/unsupported fields rejected |
| Gateway error envelope | **FROZEN** | OpenAI-compatible `error{message,type,param,code}`; request/trace correlation via headers |
| Recovery status API | **FROZEN** | `GET /v1/gateway/requests/{requestId}` returns bounded owned request/metering/settlement status, never content/secrets/finance detail |
| Typed schema | **FROZEN** | Exact logical table/column/constraint contract consolidated in AIC-092 |
| Forward migration waves | **FROZEN** | M11 foundation -> M12 reservation -> M13 usage/Settlement/Ledger/CloseBlocker -> M14 routing admin |
| Flyway version numbers | **DEFERRED WITH EXPLICIT NON-BLOCKING BOUNDARY** | Implementation uses next free versions on then-current main; semantic order is frozen and V1 historical migrations remain untouched |
| Gateway OpenAPI structure | **FROZEN** | OpenAPI 3.1 YAML parses successfully; two operations recognized; all 55 local `$ref` targets resolve; Idempotency-Key required; success usage optional |
| Gateway runtime OpenAPI contract test | **DEFERRED WITH EXPLICIT NON-BLOCKING BOUNDARY** | M11 first implementation gate adds SnakeYAML-based `GatewayOpenApiContractTest`, following existing V1 contract-test pattern; no Backend test code is added to pure-doc M10 |

---

## 11. Provider evidence boundary

| Topic | Status | Frozen decision |
|---|---|---|
| M11 initial Provider | **FROZEN** | MiMo is the initial candidate; logical AI-CostOps model maps to `mimo-v2.5-pro` through catalog, not client Provider id |
| MiMo endpoint/role/max-token surface | **FROZEN** | Current official docs support `/v1/chat/completions`, developer/system/user/assistant roles, streaming and `max_completion_tokens` for current text models |
| MiMo streaming final-usage semantics | **DEFERRED WITH EXPLICIT NON-BLOCKING BOUNDARY** | Official docs prove streaming but not every termination's final usage. M11/M13 sanitized certification must empirically prove it; until then missing final usage is INCOMPLETE/UNKNOWN, never zero |
| Real Provider certification execution | **DEFERRED WITH EXPLICIT NON-BLOCKING BOUNDARY** | Runtime evidence belongs to M11/M13; synthetic mock is not called real certification |

---

## 12. Performance/operational evidence intentionally deferred

| Topic | Status | Boundary |
|---|---|---|
| Exact blocking scheduler/pool/concurrency numeric defaults | **DEFERRED WITH EXPLICIT NON-BLOCKING BOUNDARY** | Implementation picks bounded safe defaults; M16 load evidence tunes values without changing ownership/financial semantics |
| Circuit breaker numeric thresholds | **DEFERRED WITH EXPLICIT NON-BLOCKING BOUNDARY** | M14 implementation/load evidence only; CLOSED/OPEN/HALF_OPEN semantics are frozen |
| Production SLO/alert thresholds | **DEFERRED WITH EXPLICIT NON-BLOCKING BOUNDARY** | Must come from measured operational evidence; M10 does not invent SLO claims |
| Future archive/retention period | **DEFERRED WITH EXPLICIT NON-BLOCKING BOUNDARY** | V2 Core performs no automatic purge until a separate archive/tombstone design preserves financial/idempotency correctness |
| Advanced Provider capabilities | **DEFERRED WITH EXPLICIT NON-BLOCKING BOUNDARY** | Tools, multimodal, Responses API, provider plugins, structured output and advanced thinking controls are outside M11 subset and require later explicit contract expansion |

---

## 13. Verification evidence used for freeze

Independent review checked the real V1 implementation rather than trusting old blueprint text, including:

```text
ProviderChargePostingService financial transaction/lock behavior
LedgerSourceType and V13 Ledger schema
Budget/Commitment row locking and exact/ORG Budget selection
charge_fact mandatory raw-record lineage
existing CloseBlockerProvider extension seam
current migration baseline through V17
existing SnakeYAML OpenAPI contract-test pattern
```

Gateway machine contract structural verification executed against the final AIC-092 shape:

```text
yaml.safe_load                         PASS
OpenAPI version                        3.1.0
operations recognized                 2
local $ref occurrences checked       55
broken local $ref                      0
Idempotency-Key required              PASS
ChatCompletion usage not required     PASS
```

PR purity must be rechecked on the final PR head before merge; M10 permits documentation changes only.

---

## 14. AIC-093 decision

```text
Blocking design topics: 0
Correctness/API/data-ownership topics left to implementation guesswork: 0

AIC-093 DESIGN FREEZE = PASS
M10 DETAILED DESIGN ON BRANCH = FROZEN
M11 BROAD CODING = STILL BLOCKED UNTIL THE M10 PR IS ACCEPTED/MERGED TO MAIN
```

This decision means the architecture/API/data/financial semantics are frozen. It does **not** mean Gateway has been implemented or runtime-tested.

The principal PR remains subject to:

```text
latest-head GitHub checks
final diff purity
explicit human merge authorization
```

No M11 implementation plan/Issues should become active before the M10 design PR lands on `main`.
