# AIC-086 — Request Identity, Financial Scope, Idempotency & State Machine

> Status: **FROZEN CANDIDATE**  
> Depends on AIC-084 runtime ownership and AIC-085 credential/catalog/pricing contracts.

## 1. Purpose

A billable Gateway request must have a durable identity before Provider execution and must never rely on scattered booleans to infer whether replay is financially safe.

This design separates three different lifecycles:

```text
Gateway Request execution
Gateway Usage Fact / metering
Gateway Settlement / financial result
```

They are correlated, but they are not one cross-deployable status enum.

---

## 2. Request context

After Gateway credential authentication, every accepted request resolves:

```text
organization_id
principal_type
organization_member_id or service_identity_id
credential_id
project_id
financial_scope_type
financial_scope_id
logical_model_id
requested API surface
client idempotency identity
trace_id
```

Before Provider dispatch it additionally resolves:

```text
provider_account_id
provider_model_id
pricing_version_id
pricing_currency
route_decision_id
budget_reservation_id NULL when explicitly unbudgeted
```

These resolved ids are persisted as immutable/snapshot identity on the request. Later admin changes do not rewrite historical request identity.

---

## 3. Project ownership vs financial target

A request always belongs to one Project because Project is the primary operational ownership context.

Financial attribution is exactly one V1-compatible Ledger target:

```text
PROJECT
TEAM
COST_CENTER
```

Required fields:

```text
financial_scope_type
financial_scope_id
```

Rules:

1. Default financial scope is the credential Project.
2. A Team/Cost Center override must be pre-governed by the credential configuration.
3. The target must be same-org and active/eligible.
4. Budget selection and Ledger posting use the financial scope, not a guess from optional context fields.
5. Optional Team/Cost Center context may still be retained for reporting/routing but does not create multiple Ledger targets.

---

## 4. Public request id

Every durable Gateway request receives a server-generated opaque public request id.

Requirements:

```text
unique
non-secret
safe in logs/audit
stable across idempotent replay
not an auto-increment DB id exposed as the only external identity
```

Exact printable format is frozen in AIC-092.

The internal DB primary key may remain BIGINT for project consistency.

---

## 5. Trace id

`trace_id` is observability correlation, not business idempotency.

A client retry may have a different trace id but must converge to the same Gateway request when the same valid idempotency key is reused.

Do not use trace id as the Settlement business key.

---

## 6. Idempotency-Key policy

V2 billable Data Plane POST requests require an AI-CostOps Gateway idempotency key.

Reason: without a stable client-supplied retry identity, two semantically identical requests may be intentional separate purchases, so the Gateway cannot safely deduplicate by content alone.

The machine contract in AIC-092 must expose the required header explicitly.

Logical uniqueness:

```text
(org_id, credential_id, idempotency_key_digest)
```

The raw idempotency key does not need to be persisted if a keyed digest is used.

---

## 7. Privacy-safe request fingerprint

The idempotency key is also bound to the request being protected.

Persist a keyed fingerprint, conceptually:

```text
HMAC-SHA-256(
  method + path + canonical compatibility surface + raw request body bytes,
  dedicated request-fingerprint secret
)
```

The fingerprint secret is independent from the Gateway credential digest pepper.

Why keyed HMAC:

```text
prompt/request body is not persisted
plain low-entropy content hash is avoided
same key cannot be reused with a different request silently
```

Same idempotency key + different fingerprint:

```text
409 deterministic idempotency conflict
```

AIC-092 freezes exact error code and maximum key length.

---

## 8. Billing idempotency vs response replay

Financial/request idempotency is mandatory.

Byte-for-byte response replay is not mandatory because Prompt/Completion bodies are not stored by default.

Therefore:

```text
same idempotency key
→ never duplicate Provider dispatch after the financial safety fence
→ never duplicate reservation/Settlement/Ledger

but

completed response body no longer retained
→ may return explicit replay/recovery status + original request id
→ does not re-dispatch Provider merely to recreate response bytes
```

A future encrypted short response cache is a separate retention/privacy feature.

---

## 9. Durable request row creation point

Pre-auth garbage traffic does not create financial request rows.

Sequence:

```text
1. authenticate Gateway credential
2. validate principal/project/financial scope/model permission
3. rate/quota pre-check as required
4. create/replay durable gateway_request with state VALIDATED
5. reserve Budget when policy requires
6. freeze Provider/model/Pricing route
7. persist dispatch-intent fence
8. only then send potentially billable Provider I/O
```

If step 4 cannot commit:

```text
fail closed
no Provider call
```

---

## 10. Gateway Request durable state machine

The durable request state models Data Plane execution only.

Frozen states:

```text
VALIDATED
RESERVED
DISPATCH_INTENT
UPSTREAM_ACTIVE
TRANSPORT_COMPLETED
REJECTED_BUDGET
CANCELED_PRE_DISPATCH
FAILED_PRE_DISPATCH
CANCELED_AFTER_DISPATCH
TIMED_OUT_AFTER_DISPATCH
FAILED_AFTER_DISPATCH
```

`TRANSPORT_COMPLETED` means the client/Provider transport phase ended successfully enough to produce a response path; it does not mean Settlement completed.

### 10.1 Main happy path

```text
VALIDATED
→ RESERVED                 when budget-controlled
→ DISPATCH_INTENT
→ UPSTREAM_ACTIVE
→ TRANSPORT_COMPLETED
```

Explicitly unbudgeted-allowed request:

```text
VALIDATED
→ DISPATCH_INTENT
→ UPSTREAM_ACTIVE
→ TRANSPORT_COMPLETED
```

### 10.2 Budget rejection

```text
VALIDATED
→ REJECTED_BUDGET
```

No Provider I/O is allowed.

A client that changes budget conditions and wants a new attempt uses a new idempotency key. Reusing the same key replays the existing rejected business request result.

### 10.3 Safe pre-dispatch cancellation/failure

```text
VALIDATED / RESERVED
→ CANCELED_PRE_DISPATCH
or
→ FAILED_PRE_DISPATCH
```

No Provider billable dispatch occurred. Reservation can be released under AIC-087 rules.

### 10.4 Post-dispatch uncertain termination

After `DISPATCH_INTENT`, failures are financially conservative:

```text
DISPATCH_INTENT / UPSTREAM_ACTIVE
→ CANCELED_AFTER_DISPATCH
→ TIMED_OUT_AFTER_DISPATCH
→ FAILED_AFTER_DISPATCH
→ TRANSPORT_COMPLETED
```

These states do not imply zero cost. AIC-088 must create/derive FINAL, INCOMPLETE or UNKNOWN metering classification.

---

## 11. Durable dispatch-intent fence

`DISPATCH_INTENT` is the critical financial safety fence.

Definition:

> The Gateway has durably committed that it is about to perform external Provider I/O that may become billable; after this state it is unsafe to blindly replay the Provider operation.

Required order:

```text
MySQL COMMIT DISPATCH_INTENT
→ then Provider network dispatch
```

Never:

```text
Provider call
→ then attempt first durable request marker
```

### 11.1 Crash between intent and network send

This may create a conservative orphan where Provider cost never actually occurred.

That is acceptable because:

```text
false-positive possible-billable orphan
is safer than
silent duplicate billable replay
```

Recovery eventually classifies/reconciles it.

### 11.2 Crash after Provider accepted request

The durable `DISPATCH_INTENT` row already exists and prevents the request from disappearing from recovery scans.

If usage cannot be reconstructed, it becomes `UNKNOWN`, not zero.

---

## 12. `UPSTREAM_ACTIVE` semantics

`UPSTREAM_ACTIVE` is best-effort confirmation that upstream I/O has started/been accepted far enough to observe Provider response activity.

Failure to persist this transition because MySQL is temporarily unavailable does not erase the prior `DISPATCH_INTENT` safety fence.

Provider request id, when observed, is captured in the request/usage correlation fields as soon as safely persistable.

---

## 13. Usage Fact state is separate

AIC-088 owns metering truth:

```text
FINAL
INCOMPLETE
UNKNOWN
```

The request execution state and usage status form a combined read model, for example:

```text
TRANSPORT_COMPLETED + FINAL
FAILED_AFTER_DISPATCH + INCOMPLETE
TIMED_OUT_AFTER_DISPATCH + UNKNOWN
```

Do not add Settlement status values to `gateway_request`.

---

## 14. Settlement state is separate

AIC-089 owns:

```text
Gateway Usage Fact
→ Gateway Settlement
→ Ledger
```

A request can be transport-complete while Settlement is still pending/retrying.

The frontend/reporting read model may join these states, but neither deployable modifies the other's state column.

---

## 15. Idempotent replay behavior by state

For the same credential + idempotency key + matching fingerprint:

### VALIDATED / RESERVED

```text
reuse same request
never create second reservation
continue existing request only through an owned recovery/continuation path
```

### DISPATCH_INTENT / UPSTREAM_ACTIVE

```text
never issue a second Provider request automatically
return in-progress/uncertain replay response according to API contract
```

### TRANSPORT_COMPLETED

```text
never re-dispatch Provider
if response replay data is unavailable, return explicit completed-idempotency result
with original request id/status
```

### REJECTED_BUDGET / CANCELED_PRE_DISPATCH / FAILED_PRE_DISPATCH

```text
return same terminal business result
new business attempt requires a new idempotency key
```

### post-dispatch failure states

```text
never blindly re-dispatch
return recovery/uncertainty result
financial recovery proceeds through usage/settlement/reconciliation
```

---

## 16. Idempotency retention

Durable request/idempotency identity must outlive the short client retry window and the financial recovery horizon.

Do not rely on Redis TTL as the only idempotency record.

The exact DB retention/archival period is frozen in AIC-091/AIC-092, but it must be long enough that a late client replay or delayed Settlement cannot create a second charge because an idempotency key disappeared prematurely.

Redis may cache short idempotency lookups, but DB uniqueness is authoritative.

---

## 17. Request mutation policy

After `DISPATCH_INTENT`, immutable snapshot fields may not change:

```text
org/principal/credential/project
financial scope
logical model
provider account/model
pricing version
route decision
idempotency identity/fingerprint
```

A pre-dispatch failover/re-route may update the route/pricing snapshot only through an explicit state transition before a new `DISPATCH_INTENT`, and only while no possible billable Provider execution exists.

After billable uncertainty starts, a new route is a new Gateway request/business idempotency identity unless AIC-090 proves the original operation is safely retryable.

---

## 18. Provider internal retry

Internal Gateway retry is not client idempotency.

Provider retry/failover is governed by AIC-090 and may occur only when the operation is proven safe relative to the dispatch/billable boundary.

The request id remains the same only for a safe internal retry that AIC-090 explicitly allows.

---

## 19. Rejected auth/rate-limit traffic

Authentication failures and early rate/quota rejects occur before billable dispatch and need not create `gateway_request` rows solely for finance.

They still produce bounded security/audit/metric signals as defined by AIC-091.

Do not persist request bodies to explain rejected traffic.

---

## 20. Concurrency requirements

AIC-092 tests must prove:

```text
100 concurrent same idempotency key
→ one durable request
→ at most one reservation
→ at most one dispatch intent
→ at most one Provider dispatch

same idempotency key + different request fingerprint
→ conflict

crash after reservation before dispatch intent
→ safely releasable/recoverable

crash after dispatch intent before Provider response
→ no automatic second Provider call
→ possible-billable recovery state
```

---

## 21. Audit/observability fields

Safe structured correlation:

```text
request_id
trace_id
credential_id
principal_type
principal_id
org_id
project_id
financial_scope_type/id
logical_model_id
provider_account_id
provider_model_id
pricing_version_id
route_decision_id
reservation_id
```

These are allowed as structured log/audit fields subject to AIC-091 privacy/cardinality rules.

Prompt/Completion, raw auth headers and secret keys are forbidden.

Metric labels must not use unbounded ids such as request/org/user ids.

---

## 22. AIC-086 Definition of Done

```text
[freeze] Project ownership is separate from one financial target
[freeze] durable request identity exists before Provider dispatch
[freeze] billable POST requires explicit idempotency key
[freeze] idempotency key is bound to keyed request fingerprint
[freeze] DB uniqueness is idempotency authority; Redis is optional cache
[freeze] DISPATCH_INTENT is committed before potentially billable I/O
[freeze] request execution, metering and Settlement are separate state machines
[freeze] post-dispatch failures never imply zero cost
[freeze] same idempotency key after dispatch never blindly re-dispatches Provider
[freeze] financial idempotency does not require response-content persistence
[freeze] immutable route/pricing/financial identity cannot drift after dispatch
```

If later API compatibility review shows a required client cannot supply an idempotency key, AIC-092 must explicitly resolve that client compatibility gap before AIC-093; silently dropping the financial idempotency requirement is not allowed.
