# AIC-091 — Security, Privacy, Audit, Observability & Deployment

> Status: **FROZEN CANDIDATE**  
> Extends the V1.1/M9 production-hardening baseline to the new Gateway Data Plane.

## 1. Security posture

Gateway is an internet/API-facing billable Data Plane. Its default posture is:

```text
deny by default
fail closed before billable dispatch when correctness/security dependencies are unavailable
minimize secrets/content exposure
persist financial identifiers, not prompt content
use least-privilege DB access
bound concurrency and cardinality
```

---

## 2. Data classification

### SECRET

Never written to ordinary DB fields, logs, audit metadata, metrics or traces:

```text
raw Gateway API key
Gateway credential digest pepper
request-fingerprint HMAC key
raw Provider API key/token
Provider credential encryption KEK
Authorization header
DB/Redis/Object-storage passwords
TLS private keys
```

### USER CONTENT

Default transient-only:

```text
prompt/messages/input content
completion/output content
tool arguments/results containing user content
attachments/body payload content
```

V2 Core does not persist this class merely for observability, idempotency or financial reconciliation.

### FINANCIAL / OPERATIONAL DURABLE

Persisted because correctness/reconciliation requires it:

```text
Gateway request ids and immutable identity
financial scope
route attempts
Provider account/model ids
Provider request id when available
Pricing Version id
normalized usage dimensions
Reservation
Settlement amounts
Ledger lineage
bounded failure/status codes
```

---

## 3. Prompt/Completion default non-persistence

Forbidden by default:

```text
request body in logs
response body in logs
prompt in audit_event
completion in audit_event
prompt/completion in metrics labels
prompt/completion copied to MinIO/S3
```

AIC-086 financial idempotency uses a keyed HMAC fingerprint instead of content retention.

A future content-observability feature requires a separate design covering classification, consent/notice, access control, retention, encryption and deletion.

---

## 4. Gateway key security

AIC-085 rules are mandatory:

```text
raw Gateway key returned once
database stores prefix + keyed digest only
digest comparison constant-time
pepper/key lives outside MySQL
digest version explicit
```

Production requires a non-default high-entropy credential-digest key/pepper.

Do not reuse JWT signing key, Provider KEK or request-fingerprint key for this purpose.

---

## 5. Idempotency fingerprint security

Request fingerprint uses a dedicated keyed HMAC secret.

Purpose:

```text
bind Idempotency-Key to the exact accepted request
without storing prompt/body plaintext
```

Do not expose the fingerprint to clients.

Do not use it as a global content-dedup identifier across organizations.

Dedicated secret rotation must not invalidate historical financial uniqueness: existing persisted fingerprints remain comparison evidence under a recorded fingerprint-key version if rotation is supported. AIC-092 freezes exact version fields.

---

## 6. Provider credential security

Provider credentials use authenticated encryption under AIC-085.

Production requirements:

```text
KEK supplied outside MySQL
no default KEK
key version explicit
ciphertext never logged
raw Provider secret never returned after creation/rotation
Gateway decrypts only inside narrow Provider credential boundary
```

Provider secrets are never forwarded to the client.

A Provider Adapter receives only the credential material it needs for the selected Provider attempt.

---

## 7. Provider endpoint / SSRF boundary

Clients cannot supply arbitrary upstream URLs.

Provider base endpoints come only from server-governed Provider configuration/catalog.

Production rules:

```text
HTTPS required for external Provider endpoints
redirects to unapproved schemes/hosts disabled or revalidated
client request cannot override host/scheme
Provider credential is injected only after destination is resolved to an approved Provider configuration
```

Local mock/test endpoints may use explicit non-production profiles.

This prevents an OpenAI-compatible `base_url` field from becoming a generic authenticated SSRF proxy.

---

## 8. Gateway authentication flow

Initial V2 Core favors direct MySQL credential truth over a cache because the request already requires durable MySQL work before dispatch.

Flow:

```text
parse prefix
→ lookup same credential row
→ verify keyed digest
→ validate ACTIVE/not expired
→ validate principal/project/financial scope
→ continue
```

If a future Redis cache is added:

```text
cache miss -> MySQL
cache cannot resurrect revoked credential
revocation propagation bounded/fail-safe
```

Revocation blocks new requests. Already-dispatched incurred work still meters/settles.

---

## 9. Authorization boundary

Gateway key authorizes only the bound inference context.

It does not grant Control Plane finance/admin permissions.

Control Plane browser JWT/RBAC tokens are not accepted as a substitute for Gateway API keys on the billable Data Plane unless a future explicit API contract designs that path.

Gateway validates:

```text
org
principal
project
financial scope
logical model permission
budget enforcement mode
```

Provider routing remains server-governed.

---

## 10. Least-privilege database users

Production should separate at least:

```text
backend migration/financial DB identity
gateway runtime DB identity
```

Gateway DB identity may:

```text
read required identity/catalog/pricing/budget/period projections
lock allowed Budget/Period/Commitment rows for reservation correctness
write gateway_request
gateway_route_attempt
gateway_usage_fact
budget_reservation
Gateway-owned runtime metadata
```

Gateway DB identity may not directly write:

```text
ledger_posting
ledger_entry
budget.actual_amount
budget_commitment_usage
gateway_settlement
billing_period close/reopen state
```

Privilege verification becomes a deployment/security test in AIC-092/M16.

---

## 11. Schema migration ownership

Production Flyway runs from one owner only:

```text
backend / Control Plane deployment
```

Gateway startup verifies expected schema compatibility and fails fast if the required forward schema is absent.

Gateway never races Backend with an independent production migration runner.

---

## 12. Production secret/configuration validator

Gateway gets a production startup validator analogous to the existing M9 Backend hardening.

It must fail startup for at least:

```text
missing/default Gateway credential digest key
missing/default request fingerprint HMAC key
missing/default Provider credential KEK when encrypted Provider credentials are enabled
insecure external Provider scheme
unsafe dev/mock Provider endpoint under prod policy
missing Redis auth when deployment policy requires it
missing/unsafe Gateway DB credential
unsafe management endpoint exposure
unbounded/invalid timeout or concurrency configuration
```

No production secret appears in committed YAML.

Exact property/env names are frozen in AIC-092.

---

## 13. TLS / ingress boundary

Production topology remains Docker-first and ingress/TLS terminated:

```text
Internet / enterprise clients
        |
      TLS
        |
   Ingress / reverse proxy
      /             \
Control Plane      Gateway
```

External Data Plane requests use HTTPS.

Backend/management endpoints are not exposed merely because Gateway is public.

Prometheus/management exposure follows explicit protected/isolated production policy.

Kubernetes is not required.

---

## 14. CORS/browser posture

Gateway Data Plane is an API-key surface, not a cookie-authenticated browser product.

Default production posture:

```text
no wildcard CORS
no credentialed browser CORS by default
```

If browser-based direct Gateway calls become a product requirement, origin policy and secret exposure risk require explicit design; do not casually expose long-lived Gateway keys to frontend JavaScript.

---

## 15. Dependency failure policy

### MySQL unavailable before durable request/financial fence

```text
fail closed
no Provider dispatch
```

### MySQL unavailable after `DISPATCH_INTENT`

```text
never convert to zero cost
never blindly redispatch
bounded persistence retry for already-observed metadata/usage
existing durable dispatch fact remains recovery evidence
UNKNOWN/reconciliation path if exact usage is lost
```

### Redis unavailable

```text
never fabricate Budget availability
mandatory rate/quota policy fails closed
MySQL-authoritative Reservation remains authoritative
```

### Provider unavailable/fails

Use AIC-090 retry-safety classification. Unknown billable safety means no automatic second Provider call.

---

## 16. Backpressure and resource limits

Gateway must bound resources per request and globally.

Required configuration classes:

```text
maximum request body size
maximum accepted header size through ingress/runtime policy
maximum concurrent active streams
maximum pending blocking-DB tasks
DB connection pool size
Provider connection pool limits
connect/header/idle/hard timeouts
bounded in-memory downstream buffering
```

Do not place blocking MySQL operations on Reactor Netty event-loop threads.

Overload rejects before Provider dispatch where possible.

Exact numeric defaults are implementation/performance configuration and are validated by M16 load evidence; absence of any bound is not acceptable.

---

## 17. Structured logging

Gateway logs use bounded event names and structured fields.

Allowed correlation fields include:

```text
trace_id
request_id
route_attempt_id
route_decision_id
credential_id
principal_type
org_id
project_id
financial_scope_type
provider_code
logical_model_key from bounded catalog
reservation_id
usage_fact_id
settlement_id when known
error_code
```

Forbidden values:

```text
Authorization
raw Gateway key
Gateway digest
raw Provider key/ciphertext
prompt
completion
full request/response body
unredacted arbitrary Provider error body
```

IDs may appear in logs for traceability, but not as high-cardinality metric labels.

---

## 18. Error logging/redaction

Exception logging must not rely on `toString()` of request DTOs, WebClient request objects or Provider response bodies.

Provider error mapping uses an allowlist:

```text
HTTP status
bounded Provider error code when classified safe
Provider request id
AI-CostOps problem code
```

Unexpected error body content is omitted/redacted.

Stack traces remain server-side operational logs under production log access controls and must not contain raw secrets through exception messages.

---

## 19. Audit strategy

High-value administrative/financial changes use durable `audit_event`.

Audit at least:

```text
service identity lifecycle
Gateway credential issue/rotate/revoke/disable
Provider credential lifecycle
Provider/model/routing policy changes
Pricing Version lifecycle
Gateway Settlement posting/reconciliation resolution
security-sensitive configuration/admin changes when application-managed
```

Do not emit a heavy global audit row for every stream chunk/token.

`gateway_request`/`gateway_route_attempt`/`gateway_usage_fact` are already durable runtime evidence for high-volume request history.

System Settlement audit uses `actor_user_id = NULL` plus explicit event/subject metadata; no fake human actor.

---

## 20. Audit metadata allowlist

Allowed examples:

```text
stable ids
bounded enum/status
provider code
logical model key
amount/currency for financial events
reason code
```

Forbidden:

```text
prompt/completion
raw secrets
digests/peppers/KEKs
Provider ciphertext
Authorization headers
free-form upstream body
```

Audit failure during financial Settlement rolls back the Settlement financial transaction as defined in AIC-089.

---

## 21. Metrics

Reuse Micrometer/Prometheus conventions from M9.

Minimum Gateway metric families:

```text
request total / outcome
active streams
request latency
time to first token
stream duration
provider latency
provider 429/5xx
connect/header/idle/hard timeout
client cancel
rate/quota reject
budget reject
reservation active/pending/released/overrun
usage FINAL/INCOMPLETE/UNKNOWN
settlement success/retry/reconciliation-required
routing attempts/failover suppressed
circuit state
Redis latency/errors
MySQL pool/transaction latency/errors
blocking DB scheduler queue/saturation
```

---

## 22. Metric cardinality

Allowed labels are bounded enums/catalog values such as:

```text
outcome
provider_code
reason_code
timeout_class
state
principal_type
```

Default forbidden labels:

```text
request_id
trace_id
org_id
user/member/service id
credential id
project/team/cost-center id
Provider request id
free-form model string
error message
```

A logical-model label may be used only when the configured catalog has an intentionally bounded cardinality and the dashboard need justifies it; provider/model-specific deep diagnosis can use logs instead.

---

## 23. Trace correlation

AIC-091 does not require a new distributed-tracing backend.

Required baseline:

```text
trace_id + request_id propagated through Gateway logs
Provider request id captured when available
request/usage/settlement/ledger lineage queryable in MySQL
```

OpenTelemetry/exporter infrastructure may be added only if it solves an observed operational need and follows the infrastructure guardrail.

---

## 24. Retention baseline

The current project source does not define a numeric V2 Gateway retention period, so M10 does not invent a fake compliance number.

V2.0 correctness rule is instead explicit:

```text
no automatic purge job for durable Gateway financial/request evidence in V2 Core
```

Do not automatically delete:

```text
gateway_request
gateway_route_attempt
gateway_usage_fact
budget_reservation lineage
gateway_settlement
Ledger/reconciliation lineage
```

before a separately reviewed retention/archive policy exists.

This prevents an idempotency/reconciliation key from disappearing while delayed Provider statements or financial recovery remain possible.

Operational application logs are not financial truth and follow deployment log rotation; durable financial reconstruction must not depend on retaining logs.

M16 capacity evidence must include growth estimates so a later archive/retention design can be evidence-based.

---

## 25. Idempotency retention consequence

Because durable Gateway request rows are not auto-purged in V2 Core, the DB idempotency uniqueness remains valid beyond Redis/cache TTL.

A cache expiration can never authorize a second Provider dispatch for an old idempotency identity.

A future archive/purge design must preserve a sufficient tombstone/business uniqueness mechanism if it deletes the full request payload-independent row.

---

## 26. Backup/restore

Gateway durable facts and Settlement live in the same MySQL system of record and therefore enter the existing MySQL backup/restore scope.

Redis is not required to reconstruct financial truth after restore.

After MySQL restore:

```text
Gateway request/usage/reservation facts available
Settlement/Ledger truth available
DB-backed recovery can rebuild runtime Redis hints/cache
```

Provider credential ciphertext is part of DB backup, but recovery also requires the external KEK/version secrets through the deployment secret backup/recovery process. Do not store KEK in the DB backup to make restore convenient.

---

## 27. Incident signals

Alerts must be based on observable failure, not invented SLO claims.

High-value signals include:

```text
metering UNKNOWN spike
Settlement retry backlog
RECONCILIATION_REQUIRED backlog
PENDING_HOLD reservation age/count
Provider timeout/error spike
Redis dependency errors
MySQL dependency errors
blocking scheduler saturation
Gateway Close blocker backlog
credential-auth failure anomaly
```

M16 can establish production thresholds from load/operational evidence.

---

## 28. Health endpoints

Liveness must not kill a healthy process merely because a downstream dependency is temporarily unavailable.

Readiness may reflect whether Gateway can safely accept new billable requests:

```text
required MySQL connectivity/schema compatibility
required Redis availability when mandatory rate/quota enabled
critical configuration validity
```

Provider outage should influence routing/availability but not necessarily process liveness.

---

## 29. Graceful shutdown

Gateway shutdown stops accepting new requests first, then gives active streams a bounded drain window.

Already-dispatched requests that cannot finish within the drain window remain possible-billable durable requests and recover through AIC-088/AIC-089 semantics.

Shutdown must not delete Reservation/usage evidence to make metrics look clean.

---

## 30. AIC-091 Definition of Done

```text
[freeze] raw secrets never enter normal logs/audit/metrics
[freeze] Prompt/Completion default transient-only
[freeze] Gateway key digest/fingerprint/Provider encryption keys are separate secrets
[freeze] client cannot choose arbitrary Provider URL
[freeze] Data Plane auth is separate from Control Plane browser auth
[freeze] Gateway DB user cannot write financial truth tables
[freeze] Backend is sole Flyway owner
[freeze] prod startup fails fast on unsafe/missing Gateway security config
[freeze] MySQL failure before dispatch fails closed
[freeze] post-dispatch failures become explicit financial uncertainty
[freeze] Redis cannot authorize spend
[freeze] resource/backpressure bounds are mandatory
[freeze] metrics use bounded labels
[freeze] runtime durable facts replace prompt/body logging as evidence
[freeze] no automatic purge of durable Gateway financial evidence in V2 Core
[freeze] backup/restore does not depend on Redis
```

Any future prompt observability, external Secret Manager, Kubernetes, distributed tracing backend or retention engine is separately designed and is not an implicit M10 dependency.
