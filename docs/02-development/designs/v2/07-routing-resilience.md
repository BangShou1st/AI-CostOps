# AIC-090 — Routing, Retry, Failover & Resilience Semantics

> Status: **FROZEN CANDIDATE**  
> M14 implements multi-Provider routing; M10 freezes the semantics now so M11-M13 persist compatible evidence.

## 1. Priority

Routing optimizes in this order:

```text
correctness
→ policy compliance
→ predictable behavior
→ auditability
→ availability
→ cost/latency optimization later
```

V2 Core does not implement opaque “smart routing”.

---

## 2. Client model request

Client requests an AI-CostOps logical model from AIC-085.

Client does not directly select:

```text
Provider credential
Provider account secret
raw Provider endpoint
arbitrary Provider model id
```

A governed extension may later allow a Provider preference, but it cannot bypass catalog, privacy, budget or routing policy.

---

## 3. Routing policy model

V2 Core uses explicit bounded policy data, not a DSL.

Logical administration concepts:

```text
routing_policy
routing_policy_candidate
```

Minimum policy scope:

```text
org
optional project
logical model
status
```

Candidate fields:

```text
provider_account_id
provider_model_id
priority
status
data_region/privacy class when applicable
```

M11 can have exactly one enabled candidate.

M14 may have several ordered candidates.

---

## 4. Routing evolution

Frozen evolution:

```text
M11: one configured Provider candidate
M14 phase 1: static ordered candidates
M14 phase 2: health-aware filtering
later only with evidence: bounded cost/latency-aware choice
```

No reinforcement learning / arbitrary scoring engine / policy DSL is required.

---

## 5. Eligibility filters

A candidate is eligible only if all required checks pass:

```text
same organization
routing policy enabled
Provider Account ACTIVE
Provider Credential ACTIVE
logical model mapping ACTIVE
provider model routing_eligible
required client/API capability supported
required streaming capability supported
privacy/region policy allowed
Pricing Version resolvable
budget/reservation can be safely established for that route
circuit/health state not OPEN when health-aware routing is enabled
```

Failure of one candidate does not mean another candidate is automatically financially safe to try; retry/failover rules below still apply.

---

## 6. Runtime route attempt is a durable fact

One Gateway Request may have multiple Provider attempts only when every previous attempt is conclusively proven non-billable.

Introduce Gateway-owned append-only runtime fact:

```text
gateway_route_attempt
```

Minimum logical fields:

```text
id
org_id
request_id
attempt_no
route_decision_id
provider_account_id
provider_model_id
pricing_version_id
reservation_id NULL
status
safety_reason_code NULL
provider_request_id NULL
created_at
dispatch_intent_at NULL
completed_at NULL
```

Uniqueness:

```text
UNIQUE(org_id, request_id, attempt_no)
UNIQUE(org_id, route_decision_id)
```

A route attempt freezes Provider/model/Pricing Version for that attempt.

Usage Fact points to the actual route attempt that produced/possibly produced billable usage.

---

## 7. Route attempt states

Frozen states:

```text
PLANNED
DISPATCH_INTENT
SAFE_NO_BILLABLE_EXECUTION
BILLABLE_POSSIBLE
COMPLETED
```

### PLANNED

Candidate selected but no potentially billable I/O has been attempted.

### DISPATCH_INTENT

Durably committed immediately before this route attempt begins Provider I/O.

### SAFE_NO_BILLABLE_EXECUTION

Attempt failed, and Adapter/transport evidence proves the Provider could not have begun billable execution.

Only this outcome permits another route attempt under the same Gateway Request.

### BILLABLE_POSSIBLE

Provider may have accepted/executed the request, but transport did not yield a clean completed result.

No automatic second billable route attempt is allowed.

### COMPLETED

Provider attempt completed the supported transport path. Metering may still be FINAL/INCOMPLETE/UNKNOWN according to AIC-088.

---

## 8. Request-level state vs attempt-level state

AIC-086 `gateway_request` tracks the overall request lifecycle.

`gateway_route_attempt` tracks each Provider dispatch.

After the first attempt reaches `DISPATCH_INTENT`, request-level state does not need to move backward to `RESERVED` merely because that attempt is later proven SAFE_NO_BILLABLE_EXECUTION.

A subsequent safe route attempt is represented by a new append-only attempt row.

Invariant:

```text
at most one route attempt for a request may ever be BILLABLE_POSSIBLE or COMPLETED with possible billable execution
```

Any earlier attempts must be `SAFE_NO_BILLABLE_EXECUTION`.

---

## 9. Retry is evidence-based

Do not define retry as:

```text
if 5xx or timeout -> try again
```

Retry requires an Adapter/transport safety classification.

Frozen safety outcomes:

```text
SAFE_NO_BILLABLE_EXECUTION
BILLABLE_POSSIBLE
```

Unknown safety is treated as:

```text
BILLABLE_POSSIBLE
```

Fail conservative.

---

## 10. Examples that may be SAFE only with proof

Possible examples:

```text
DNS failure before connection
TCP connection refused before request bytes
TLS handshake failure before HTTP request bytes
local validation/serialization failure before network write
Provider-specific 429 contract explicitly guarantees no inference execution
```

These are not globally declared safe merely by HTTP status/name.

The Adapter plus network instrumentation must prove the relevant boundary for that Provider/runtime.

---

## 11. Examples treated as BILLABLE_POSSIBLE by default

```text
response-header timeout after request write may have begun
read timeout
stream idle timeout
hard deadline after dispatch
client disconnect after dispatch
Provider disconnect after request accepted
HTTP 5xx after request body may have reached Provider
malformed response after Provider processing
connection reset after request write
unknown transport exception after dispatch intent
```

No automatic retry/failover occurs merely to improve user success rate.

---

## 12. Provider-specific retry matrix

Each Adapter must publish a tested retry-safety matrix for its supported operations.

Example structure:

| Failure class | Safety | Automatic retry? | Evidence |
|---|---|---|---|
| local pre-network validation | SAFE_NO_BILLABLE_EXECUTION | yes/route again | no Provider I/O |
| connect refused | provider-specific safe classification | only if proven | transport instrumentation |
| HTTP 429 | provider-specific | only if contract proves non-execution | Provider behavior test/doc |
| 5xx after write | BILLABLE_POSSIBLE default | no | possible upstream execution |
| stream disconnect | BILLABLE_POSSIBLE | no | partial execution |

AIC-092 freezes the M11 Provider matrix; M14 adds matrices for later Providers.

---

## 13. Reservation is per route attempt

AIC-087 reservation must be tied to the Provider/Pricing route being financially bounded.

Therefore final schema direction is:

```text
budget_reservation
→ request_id
→ route_attempt_id
```

A request may have more than one historical Reservation only when previous route attempts were proven `SAFE_NO_BILLABLE_EXECUTION` and their reservations were safely RELEASED before the next route is dispatched.

Invariant:

```text
at most one effective ACTIVE/PENDING_HOLD reservation per request
```

### Safe failover to a different price

Before a new route attempt:

```text
1. previous attempt = SAFE_NO_BILLABLE_EXECUTION
2. previous reservation safely RELEASED
3. select new Provider/model/Pricing Version
4. create new reservation using new price/budget/currency context
5. create/commit new route attempt DISPATCH_INTENT
6. dispatch Provider
```

Do not resize an already billable/uncertain Reservation to a different Provider price.

---

## 14. Currency-changing failover

Because V2 has no FX, failover to a candidate with a different Pricing Version currency requires a fresh same-currency Budget selection/reservation.

If REQUIRED budget policy cannot reserve the new currency:

```text
no failover
```

No automatic conversion from the old reservation occurs.

---

## 15. Model substitution

Normal routing maps one logical model to equivalent Provider implementations.

Automatic failover must not change to a materially different logical model.

If Provider models have semantic/capability differences, they are different logical model mappings unless explicit product policy says they are acceptable substitutes.

Streaming/tool/schema capabilities must remain compatible with the accepted client request.

---

## 16. Static routing decision

Initial multi-candidate algorithm:

```text
1. filter ineligible candidates
2. order by explicit integer priority + stable id tie-breaker
3. choose first eligible candidate
```

Deterministic tie-break prevents different Gateway replicas making random unexplained choices.

The decision persists:

```text
route_decision_id
policy id/version
candidate chosen
bounded reason code
provider/model/pricing ids
```

Do not persist arbitrary giant decision payloads.

---

## 17. Health-aware routing

M14 may filter candidates by bounded runtime health state.

State:

```text
CLOSED
OPEN
HALF_OPEN
```

Health/circuit state may live in Redis/local runtime coordination because it is not financial truth.

Redis loss/reset may reduce resilience quality but cannot change settled financial history.

Health state never overrides:

```text
credential status
privacy/region rules
model capability
Pricing Version validity
Budget reservation requirements
```

---

## 18. Circuit breaker

Circuit breaker protects the Gateway from repeatedly selecting a demonstrably failing Provider.

Inputs are bounded operational signals such as:

```text
connect failures
safe Provider unavailable status
known 429 pressure
5xx rate
```

Do not count user validation/4xx errors as Provider health failures.

Circuit OPEN prevents new candidate selection but does not cancel/erase already-dispatched usage or Settlement.

Exact thresholds are configuration/implementation details verified in M14; M10 freezes semantics and bounded state only.

---

## 19. Timeout behavior

Use the AIC-088 separate timeout classes:

```text
connect
response header
stream idle
hard deadline
```

Timeout expiration always records the exact class.

Only a timeout whose Provider/transport evidence proves `SAFE_NO_BILLABLE_EXECUTION` may create another route attempt.

---

## 20. No hedging

V2 Core forbids parallel billable hedging:

```text
send same request to Provider A and B concurrently,
return first response,
pay both if both run
```

This creates deliberately duplicated cost and complex cancellation semantics.

Any future hedging feature requires explicit financial/product design, Budget treatment and user-visible policy.

---

## 21. Client retry does not become Provider retry

Client replay under the same Gateway idempotency key resolves to the existing Gateway Request.

It does not create a fresh route attempt unless the existing server-owned recovery logic has conclusively classified all prior attempts SAFE_NO_BILLABLE_EXECUTION.

A client cannot force failover by repeatedly retrying.

---

## 22. Provider request id and attempt correlation

Each route attempt captures Provider request id when available.

Usage facts point to:

```text
route_attempt_id
provider request id
```

This allows Hybrid Reconciliation to distinguish multiple safe failed attempts from the one potentially billable/completed attempt.

---

## 23. Route decision audit

Do not create high-volume durable global audit events for every token/chunk.

Request/attempt facts themselves are durable operational evidence.

Security/administrative audit covers routing-policy changes.

Runtime structured logs may include:

```text
request id
route decision id
attempt no
provider code
logical model key
bounded route reason
attempt safety outcome
```

No Provider secret or Prompt/Completion.

---

## 24. Metrics

Bounded metrics:

```text
gateway_route_attempt_total{provider_code,outcome}
gateway_route_failover_total{reason_code}
gateway_retry_suppressed_total{reason_code}
gateway_circuit_state{provider_code,state}
gateway_provider_timeout_total{provider_code,timeout_class}
```

Do not label with request/org/credential/route-decision ids.

---

## 25. Failure/concurrency tests

AIC-092/M14 must prove:

```text
safe pre-billable failure
→ old reservation RELEASED
→ new attempt/new reservation allowed

uncertain failure
→ no second attempt
→ reservation remains effective/PENDING_HOLD as needed

same client idempotency replay during uncertain attempt
→ no new Provider call

price-changing failover
→ old reservation released
→ new pricing/reservation recalculated before dispatch

circuit OPEN
→ candidate excluded deterministically

Redis circuit-state loss
→ may change routing availability
→ never changes financial truth
```

---

## 26. M11 scope

M11 uses the same route-attempt model even though it has one Provider.

This avoids later schema/state-machine replacement when M14 adds multiple Providers.

M11 may implement only:

```text
one candidate
one initial route attempt
no automatic failover except a narrowly proven safe transport retry if explicitly accepted by the M11 Provider matrix
```

Conservative option for M11 is no automatic Provider retry after `DISPATCH_INTENT` until safety evidence is certified.

---

## 27. AIC-090 Definition of Done

```text
[freeze] runtime route attempts are append-only durable Gateway facts
[freeze] each attempt freezes Provider/model/Pricing Version
[freeze] usage fact identifies the actual route attempt
[freeze] retry requires positive proof of SAFE_NO_BILLABLE_EXECUTION
[freeze] unknown safety = BILLABLE_POSSIBLE
[freeze] only safe attempts permit another route attempt
[freeze] reservation is per route attempt and old safe reservation releases before failover
[freeze] price/currency-changing failover re-reserves before dispatch
[freeze] static routing is deterministic/explainable
[freeze] health/circuit state is runtime state, not financial truth
[freeze] no parallel billable hedging
[freeze] client retries cannot force duplicate Provider attempts
```

Any Provider Adapter that cannot produce reliable retry-safety evidence defaults to no automatic retry after dispatch intent.
