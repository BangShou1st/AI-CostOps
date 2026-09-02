# AIC-088 — Provider Adapter, Streaming & Realtime Metering

> Status: **FROZEN CANDIDATE**  
> Depends on AIC-085 catalog/pricing, AIC-086 request lifecycle and AIC-087 reservation semantics.

## 1. Purpose

Gateway must turn Provider-specific request/stream/usage behavior into a small provider-neutral financial observation model without pretending missing usage is zero.

The data path is:

```text
Gateway Request
→ Provider Adapter
→ Provider transport / stream
→ normalized usage observation
→ durable gateway_usage_fact
→ CostOps Settlement
```

Provider Adapter owns wire semantics. CostOps Core owns final financial posting.

---

## 2. Provider Adapter interface boundary

Each Adapter implements one narrow provider contract with these responsibilities:

```text
resolve Provider endpoint
inject Provider authentication
map logical model -> Provider model id
validate supported request subset
translate request
perform/prepare non-streaming upstream exchange
parse non-streaming response
parse SSE/event stream
extract Provider request id
extract usage fields
normalize Provider errors
classify retry safety evidence
expose Provider billing/effective timestamp when available
```

Adapter must not:

```text
select Budget
mutate Reservation directly
calculate Ledger posting
mutate Budget actual
consume Commitment
close/reopen BillingPeriod
perform final reconciliation
invent FX
persist Prompt/Completion content by default
```

---

## 3. OpenAI-compatible edge does not imply identical Provider wire APIs

The client-facing Gateway contract is frozen separately in AIC-092.

Internally:

```text
client compatibility DTO
→ canonical Gateway request model
→ Provider Adapter translation
```

Provider-specific unknown fields are never passed through blindly.

Unsupported client fields are rejected explicitly by the Gateway contract instead of silently discarded.

---

## 4. Streaming is first-class

The Gateway must design streaming before implementation, not treat it as a non-streaming response split into chunks.

Required cases:

```text
normal SSE stream
provider sends final usage
provider never sends final usage
client disconnects
provider disconnects
malformed SSE/event
provider returns HTTP error before stream
provider returns error event after stream starts
connect timeout
response-header timeout
stream idle timeout
request hard deadline
Gateway shutdown/cancel
backpressure / slow client
```

Every termination path maps to an explicit request transport state plus metering status.

---

## 5. Timeout taxonomy

Do not use one generic timeout.

### Connect timeout

Maximum time to establish the Provider connection.

If no potentially billable Provider execution can have started, retry may be safe subject to AIC-090 Provider evidence.

### Response-header timeout

Maximum time from request dispatch to receipt of upstream response headers / initial response signal.

A timeout here is not automatically safe to retry: Provider may have accepted and started billable work before the Gateway observed a header.

### Stream idle timeout

Maximum interval with no upstream stream data after streaming started.

Timeout implies possible billable partial execution.

### Hard deadline

Maximum wall-clock lifetime of the Gateway request independent of stream activity.

When exceeded, Gateway cancels upstream best-effort but still treats already-dispatched usage as potentially billable.

AIC-090 freezes which timeout classes can ever trigger automatic retry.

---

## 6. Client disconnect semantics

Client disconnect after `DISPATCH_INTENT` does not prove the Provider stopped billing.

Gateway behavior:

```text
1. stop/downstream response delivery
2. propagate upstream cancel when Provider/client contract supports it
3. continue enough local finalization to persist any usage/provider request id already observed
4. classify metering FINAL / INCOMPLETE / UNKNOWN
5. never release Reservation solely because client disconnected
```

If cancel propagation is confirmed before Provider billable execution by a Provider-specific contract, recovery may eventually release safely; otherwise use conservative metering/reconciliation semantics.

---

## 7. Provider disconnect semantics

Provider disconnect after possible billable execution becomes:

```text
FAILED_AFTER_DISPATCH
+
FINAL / INCOMPLETE / UNKNOWN usage classification
```

It is not automatically a safe retry signal.

If Provider sent exact final usage before disconnect, the usage can be FINAL even though transport ended abnormally.

If only partial usage is known, classify INCOMPLETE.

If no usable usage is available after possible billable execution, classify UNKNOWN.

---

## 8. Metering outcome model

Exactly three financial-observation classifications exist:

```text
FINAL
INCOMPLETE
UNKNOWN
```

### FINAL

Gateway has all normalized dimensions required by the frozen Pricing Version to calculate deterministic realtime cost.

Requirements:

```text
all required quantities known
pricing dimensions compatible
usage values valid/exact
currency/pricing context already frozen
```

### INCOMPLETE

Gateway observed some credible billable usage but lacks one or more dimensions required for a correct final realtime cost.

Examples:

```text
partial token counts
missing completion usage after partial stream
known request fee but unknown output tokens
Provider usage object missing one required dimension
```

INCOMPLETE is not settled as zero.

### UNKNOWN

Provider execution may have been billable, but Gateway cannot derive reliable realtime quantities.

Examples:

```text
crash/DB outage after dispatch with no recoverable usage
stream failed before any trustworthy usage object
Provider accepted request but contract exposes no usage after failure
```

UNKNOWN is explicit financial uncertainty, not absence of cost.

---

## 9. No zero-by-default rule

The following conditions must never be translated to zero cost merely because usage is missing:

```text
client disconnect
provider disconnect
header timeout after dispatch
idle timeout
hard deadline
Gateway crash after dispatch
MySQL outage after dispatch
malformed/missing final usage
```

Zero is valid only when Provider semantics and observed evidence prove the normalized billable quantity/cost is actually zero.

---

## 10. Durable usage fact model

`gateway_usage_fact` is an append-only Gateway observation record.

A request may have more than one usage fact revision/observation as better realtime evidence arrives.

Minimum logical fields:

```text
id
org_id
request_id
sequence
status = FINAL | INCOMPLETE | UNKNOWN
supersedes_usage_fact_id NULL
provider_account_id
provider_model_id
provider_request_id NULL
usage_effective_at
usage_effective_at_source
pricing_version_id
currency
normalized_usage_json or typed child dimensions
safe_provider_usage_metadata NULL
observed_at
created_at
```

Required uniqueness:

```text
UNIQUE(org_id, request_id, sequence)
```

AIC-092 freezes exact schema and whether normalized dimensions use typed child rows or bounded JSON. Financial amounts themselves remain typed DECIMAL/BigDecimal, never floating point.

---

## 11. Append-only/revision semantics

Usage observations are never destructively rewritten after publication to the Settlement reader.

If better realtime evidence arrives:

```text
old fact
→ new fact with higher sequence
→ supersedes_usage_fact_id = old fact id
```

The Gateway request may hold a Gateway-owned `current_usage_fact_id` pointer for efficient lookup.

Rules:

```text
FINAL fact is immutable
INCOMPLETE/UNKNOWN fact is immutable history
new evidence appends a new fact
Backend/CostOps never edits Gateway usage facts
```

Provider statement reconciliation in M15 does not rewrite `gateway_usage_fact`; it creates reconciliation/correction results downstream.

---

## 12. Settlement eligibility

Normal realtime Settlement requires:

```text
current usage fact status = FINAL
```

INCOMPLETE/UNKNOWN remain financially unresolved and feed:

```text
reservation PENDING_HOLD when necessary
close blocker
recovery/reconciliation queue
metrics/alerts
M15 Provider statement reconciliation
```

If later realtime evidence upgrades INCOMPLETE/UNKNOWN to FINAL before statement reconciliation, Settlement may proceed from the new immutable FINAL fact.

---

## 13. Normalized usage dimensions

Gateway owns a small provider-neutral vocabulary sufficient for frozen Pricing Versions.

Initial candidate dimensions:

```text
INPUT_TOKEN
OUTPUT_TOKEN
CACHED_INPUT_TOKEN
REQUEST
```

A Provider Adapter maps only fields it can prove.

Do not infer cached tokens or output tokens from unrelated fields.

Unknown Provider billing dimensions cannot be silently collapsed into an existing dimension.

AIC-092 freezes the exact M11 supported dimension set after Provider/client research.

---

## 14. Exact quantity semantics

Usage quantities must be exact integer/decimal values from trustworthy Provider/Gateway computation.

Do not use floating point.

Examples:

```text
token count -> exact integer
request count -> exact integer
future time/storage quantity -> exact decimal with frozen unit
```

Pricing calculation in AIC-089 multiplies exact normalized quantity by exact Pricing Rate using BigDecimal rules.

---

## 15. Provider-reported vs Gateway-estimated usage

Metering records the provenance of each normalized dimension.

Candidate provenance:

```text
PROVIDER_FINAL
PROVIDER_PARTIAL
GATEWAY_DETERMINISTIC
```

`GATEWAY_DETERMINISTIC` is allowed only when the Adapter can deterministically calculate the dimension required by Pricing Version (for example exact input-token count for a frozen tokenizer/model contract).

A heuristic estimate used for Reservation is not automatically valid as FINAL settlement usage.

Reservation estimate and actual metering are separate concepts.

---

## 16. `usage_effective_at`

Financial time must be explicit.

Each current usage fact stores:

```text
usage_effective_at
usage_effective_at_source
```

Frozen source precedence:

```text
1. PROVIDER_BILLING_TIMESTAMP
   when Provider contract exposes one authoritative timestamp for the billable request/usage

2. PROVIDER_REQUEST_TIMESTAMP
   when Provider exposes a trustworthy request timestamp but no billing timestamp

3. GATEWAY_DISPATCH_INTENT_TIMESTAMP
   safe deterministic fallback
```

Do not use response completion time merely because it is convenient: a long stream can cross a BillingPeriod boundary.

The chosen effective time is immutable for the Settlement based on that usage fact.

---

## 17. One financial effective time drives three decisions

The same `usage_effective_at` is used consistently for:

```text
Pricing Version validation/selection context
BillingPeriod selection
Hybrid reconciliation time correlation
```

Provider statement evidence may later reveal a different invoice-period interpretation. M15 records an explicit reconciliation difference/correction; it does not silently rewrite the original realtime time.

---

## 18. Pricing context freeze

Provider/model/Pricing Version is frozen before dispatch under AIC-085/AIC-086.

Usage fact references that exact `pricing_version_id`.

Gateway does not choose a newer “current” price after the request completes.

If the frozen Pricing Version cannot price the observed FINAL dimensions, the fact is not FINAL for settlement purposes; classify/append INCOMPLETE/UNKNOWN and require explicit recovery/design handling.

---

## 19. Provider request id

When Provider exposes a safe request identifier, capture it.

Uses:

```text
support/debug correlation
Provider statement reconciliation
Provider-side incident investigation
retry-safety evidence
```

Rules:

```text
provider request id is not a secret
still treat as bounded operational data
never substitute it for AI-CostOps request id
capture exact value without user-content payload
```

If unavailable, null is valid; reconciliation falls back to other keys.

---

## 20. Hybrid reconciliation keys retained from M11

M15 must not discover that M11 discarded the evidence needed for matching.

Persist safe bounded identifiers when available:

```text
AI-CostOps request id
usage fact id/sequence
provider account id
provider request id
provider model id / wire model name reference
usage_effective_at
normalized usage dimensions
Pricing Version id
currency
route decision id
```

Later Settlement adds amount/currency/settlement id.

Prompt/Completion content is explicitly not required for financial reconciliation.

---

## 21. Safe Provider usage metadata

Raw Provider usage payload must not be dumped into an unbounded JSON column by default.

Allowed metadata is an adapter-defined allowlist of fields needed for:

```text
billing interpretation
provider request correlation
reconciliation
support diagnostics
```

Forbidden:

```text
prompt
completion
Authorization
Provider API key
request headers containing secrets
free-form provider error bodies that may echo content
```

AIC-091 freezes retention/redaction.

---

## 22. DB outage after Provider dispatch

Because there is no transaction with the external Provider, exact realtime usage can be temporarily unpersistable.

Gateway behavior while process remains alive:

```text
retry bounded persistence of already-observed safe usage data
without issuing another Provider request
```

Do not add Kafka/MQ by default.

If the process crashes before usage persistence succeeds:

```text
durable request DISPATCH_INTENT remains
→ recovery identifies possible-billable orphan
→ usage classification becomes UNKNOWN unless another durable/Provider source recovers exact usage
→ close blocker / reconciliation path prevents silent loss
```

This is an accepted inherent external-side-effect boundary; correctness comes from explicit uncertainty + later external reconciliation, not fictitious distributed transactions.

---

## 23. Streaming downstream behavior

Gateway must preserve valid SSE framing and backpressure.

Rules:

```text
never buffer the entire completion merely to meter
meter from bounded provider usage/control events
propagate downstream cancellation
bound in-memory queued data for slow clients
provider stream remains subject to idle/hard deadline
```

The exact Reactor operators/implementation are M11 code details, but tests must prove bounded memory/backpressure behavior.

---

## 24. Error body policy

Provider errors are mapped to Gateway errors with a bounded allowlist.

Do not expose Provider credential/account internals.

Do not log or return arbitrary upstream body text if it may contain user content or secrets.

A safe provider error code/status/request id may be retained.

---

## 25. Metering metrics

Bounded metrics include:

```text
gateway_usage_total{status}
gateway_metering_incomplete_total{provider_code,reason_code}
gateway_metering_unknown_total{provider_code,reason_code}
gateway_stream_termination_total{provider_code,outcome}
gateway_provider_usage_parse_error_total{provider_code}
```

`provider_code` is a bounded catalog value.

Never label metrics by:

```text
request id
org id
credential id
provider request id
model free text
```

Model label is allowed only if AIC-091 proves a bounded logical-model catalog cardinality; default is to omit it from low-level metrics.

---

## 26. Required mock Provider test behaviors

AIC-092 test contract must include a controllable upstream supporting:

```text
normal JSON + exact usage
normal SSE + final usage
SSE with usage before disconnect
SSE without final usage
disconnect before first event
disconnect midstream
slow header
idle stream
hard-deadline overrun
429
5xx
malformed JSON/SSE
malformed usage
provider request id present/absent
provider billing timestamp present/absent
client disconnect
```

Each case asserts request state + usage status + retry safety + reservation/settlement consequence.

---

## 27. AIC-088 Definition of Done

```text
[freeze] Provider-specific semantics stay inside Adapter
[freeze] streaming/disconnect/timeouts are first-class
[freeze] metering status is exactly FINAL / INCOMPLETE / UNKNOWN
[freeze] missing usage never defaults to zero
[freeze] usage facts are durable append-only Gateway observations
[freeze] later realtime evidence appends revisions rather than rewriting history
[freeze] normal Settlement requires current FINAL usage fact
[freeze] Reservation estimate is not automatically actual usage
[freeze] usage_effective_at/source is explicit and immutable
[freeze] same effective time drives pricing/period/reconciliation context
[freeze] provider request id and safe reconciliation keys are retained
[freeze] Prompt/Completion are not required/persisted for metering
[freeze] post-dispatch DB outage degrades to explicit uncertainty, never duplicate dispatch
```

Any Provider that cannot meet these minimum semantics may be unsupported for strict realtime financial Settlement until its Adapter contract is explicitly designed and tested.
