# AIC-092 — Data Model, Gateway API, Migration & Test Contract

> Status: **FROZEN CANDIDATE**  
> Consolidates AIC-084 ~ AIC-091 into implementation-ready contracts.  
> Baseline reviewed against `main@a144210c7110aa2b924b5ef5393686ba329537bd` and current V1 migrations through V17.

## 1. Purpose

AIC-092 removes the remaining implementation choices that could change correctness, compatibility, ownership or financial lineage.

The frozen runtime chain is:

```text
Gateway Credential
→ durable Gateway Request
→ durable Route Attempt
→ MySQL-authoritative Budget Reservation when applicable
→ Provider I/O after durable DISPATCH_INTENT
→ append-only Usage Fact + typed Usage Dimensions
→ Backend DB-backed Settlement
→ first-class GATEWAY_SETTLEMENT Ledger source
→ Budget Actual / optional Commitment consumption
→ existing Reconciliation / Close governance
```

No Gateway runtime code, production migration or Provider secret is added in M10.

---

## 2. Cross-document decisions resolved here

The following are final M10 resolutions and override earlier provisional wording where necessary.

### 2.1 Route-specific commercial context lives on Route Attempt

`gateway_request` owns stable client/business identity.

Provider-specific mutable-before-safe-failover context lives only on:

```text
gateway_route_attempt
```

Therefore these fields are **not duplicated as immutable request truth**:

```text
provider_account_id
provider_model_id
pricing_version_id
route_decision_id
```

`gateway_request.current_route_attempt_id` is only a Gateway-owned convenience pointer. Historical truth is the append-only attempt chain.

### 2.2 Reservation is per Route Attempt

A request may have multiple historical reservations only when every previous attempt is conclusively `SAFE_NO_BILLABLE_EXECUTION` and its reservation is `RELEASED` before the next attempt.

Authority:

```text
UNIQUE(org_id, route_attempt_id)
```

not `UNIQUE(org_id, request_id)`.

At most one economically effective `ACTIVE/PENDING_HOLD` reservation may exist per request at a time.

### 2.3 Budget enforcement is durable credential policy

`gateway_credential.budget_enforcement_mode` is required:

```text
REQUIRED
OPTIONAL
```

It is never supplied by the inference client.

### 2.4 Credential model access is explicit-only

V2 Core rejects an implicit “empty means all models” allowlist.

Every ACTIVE Gateway Credential must have at least one ACTIVE relation in:

```text
gateway_credential_model
```

A request model not in that relation is rejected before durable dispatch intent.

This is deny-by-default and removes a later threat-review decision from implementation time.

### 2.5 Gateway API error envelope is intentionally separate from Control Plane

Control Plane `/api/v1` continues to use RFC 9457-style `application/problem+json` under the existing project contract.

Gateway Data Plane `/v1` uses an OpenAI-compatible error envelope so standard OpenAI clients can parse failures:

```json
{
  "error": {
    "message": "...",
    "type": "invalid_request_error",
    "param": null,
    "code": "GATEWAY_REQUEST_INVALID"
  }
}
```

Correlation is returned in headers:

```text
X-AI-CostOps-Request-Id
X-Trace-Id
```

The two API documents therefore have different compatibility surfaces by design.

### 2.6 M11 performs no automatic Provider re-dispatch after dispatch intent

The current MiMo documentation recommends retry/backoff for some 429/5xx conditions, but that does not prove absence of billable execution for a particular request.

M11 conservative rule:

```text
once a Route Attempt commits DISPATCH_INTENT
→ no automatic Provider retry/failover
```

M14 may relax this only for Provider/failure classes with positive tested `SAFE_NO_BILLABLE_EXECUTION` evidence.

---

## 3. Identifier and secret formats

### 3.1 Gateway API key

Exact printable format:

```text
aic_<prefix>_<secret>
```

where:

```text
prefix = 12 lowercase Crockford-Base32 characters
secret = 32 cryptographically random bytes encoded Base64URL without padding
       = 43 characters
```

Example shape only:

```text
aic_01jxyzabc234_<43-base64url-chars>
```

Validation regex:

```text
^aic_[0-9a-hjkmnp-tv-z]{12}_[A-Za-z0-9_-]{43}$
```

Rules:

```text
raw key returned once
raw key never persisted
credential_prefix UNIQUE
secret_digest = HMAC-SHA-256(secret, credential-digest key version)
constant-time compare
```

### 3.2 Public Gateway Request id

Exact format:

```text
gwr_<lowercase UUIDv4 canonical form>
```

Length = 40 characters.

This avoids a new ID-generation dependency and remains opaque/non-secret.

### 3.3 Route decision id

Exact format:

```text
grd_<lowercase UUIDv4 canonical form>
```

Length = 40 characters.

### 3.4 Client Idempotency-Key

Header:

```text
Idempotency-Key
```

Required for billable `POST /v1/chat/completions`.

Contract:

```text
1..128 visible ASCII characters
no whitespace/control characters
not logged raw
```

Validation regex:

```text
^[!-~]{1,128}$
```

The Gateway stores no raw key. Using the dedicated request-identity HMAC key and domain separation:

```text
idempotency_key_digest
= HMAC-SHA-256(keyVersion, "idem\0" + rawIdempotencyKey)

request_fingerprint
= HMAC-SHA-256(
    keyVersion,
    "request\0" + method + "\0" + canonicalPath + "\0" + rawJsonBodyBytes)
```

M11 accepts only UTF-8 `application/json` without request content-encoding, so the exact body bytes are unambiguous.

Same idempotency digest + different fingerprint is always conflict; semantic JSON equivalence does not override byte identity.

---

## 4. Money and quantity precision

Existing financial truth remains:

```text
Ledger/Budget posted money = DECIMAL(20,8)
Java = BigDecimal
HTTP financial amounts = decimal strings
```

Realtime raw pricing calculation uses:

```text
DECIMAL(38,18)
```

for:

```text
calculated_amount_raw
rounding_delta
```

Usage quantity uses:

```text
DECIMAL(30,8)
```

Pricing rate:

```text
unit_price     DECIMAL(20,8)
unit_quantity  BIGINT positive
```

V2 Core `unit_quantity` must be an integral power of ten in the application contract:

```text
1 .. 1,000,000,000
```

This keeps supported token/request pricing arithmetic finitely representable within `DECIMAL(38,18)`.

Positive incurred/reserved money quantizes **away from zero/up** to scale 8. A future negative realtime credit, if explicitly supported, quantizes away from zero in the negative direction. Raw and delta remain durable.

---

# Part A — Exact Logical Schema Contract

## 5. Same-organization integrity convention

Every new organization-owned table has:

```text
PRIMARY KEY (id)
UNIQUE (id, org_id)
FOREIGN KEY (..., org_id) -> parent(id, org_id)
```

where the parent is organization-scoped.

This follows existing V1 same-org integrity style and prevents cross-tenant lineage by construction.

All enum strings below are schema CHECK-constrained or equivalently migration-constrained.

---

## 6. Control Plane identity and credential tables

### 6.1 `service_identity`

```text
id                    BIGINT AUTO_INCREMENT PK
org_id                BIGINT NOT NULL
code                  VARCHAR(100) NOT NULL
name                  VARCHAR(200) NOT NULL
status                VARCHAR(32) NOT NULL
created_at            DATETIME(6) NOT NULL
updated_at            DATETIME(6) NOT NULL

UNIQUE(org_id, code)
CHECK status IN (ACTIVE, DISABLED, ARCHIVED)
```

### 6.2 `gateway_credential`

```text
id                         BIGINT AUTO_INCREMENT PK
org_id                     BIGINT NOT NULL
credential_prefix          CHAR(12) NOT NULL
secret_digest              BINARY(32) NOT NULL
secret_digest_version      SMALLINT UNSIGNED NOT NULL
principal_type             VARCHAR(32) NOT NULL
organization_member_id     BIGINT NULL
service_identity_id        BIGINT NULL
project_id                 BIGINT NOT NULL
financial_scope_type       VARCHAR(32) NOT NULL
financial_scope_id         BIGINT NOT NULL
budget_enforcement_mode    VARCHAR(16) NOT NULL
status                     VARCHAR(32) NOT NULL
expires_at                 DATETIME(6) NULL
predecessor_credential_id  BIGINT NULL
created_at                 DATETIME(6) NOT NULL
updated_at                 DATETIME(6) NOT NULL
revoked_at                 DATETIME(6) NULL

UNIQUE(credential_prefix)
CHECK principal_type IN (HUMAN_MEMBER, SERVICE)
CHECK financial_scope_type IN (PROJECT, TEAM, COST_CENTER)
CHECK budget_enforcement_mode IN (REQUIRED, OPTIONAL)
CHECK status IN (ACTIVE, REVOKED, DISABLED)
CHECK exactly one principal FK is non-null and matches principal_type
```

Same-org active-state validation for project/team/cost-center is performed in the Control Plane transaction; cross-table polymorphic scope cannot be expressed as one SQL FK without duplicating columns.

### 6.3 `gateway_credential_model`

```text
credential_id         BIGINT NOT NULL
org_id                BIGINT NOT NULL
model_id              BIGINT NOT NULL
status                VARCHAR(16) NOT NULL
created_at            DATETIME(6) NOT NULL

PRIMARY KEY(credential_id, model_id)
CHECK status IN (ACTIVE, DISABLED)
```

Credential creation/activation transaction must leave at least one ACTIVE model relation.

---

## 7. Provider credential/catalog tables

### 7.1 `provider_credential`

```text
id                       BIGINT AUTO_INCREMENT PK
org_id                   BIGINT NOT NULL
provider_account_id      BIGINT NOT NULL
credential_type          VARCHAR(32) NOT NULL
ciphertext               VARBINARY(2048) NOT NULL
nonce                     BINARY(12) NOT NULL
encryption_key_version   SMALLINT UNSIGNED NOT NULL
safe_label               VARCHAR(200) NULL
status                   VARCHAR(32) NOT NULL
predecessor_credential_id BIGINT NULL
created_at               DATETIME(6) NOT NULL
rotated_at               DATETIME(6) NULL
revoked_at               DATETIME(6) NULL

CHECK credential_type IN (API_KEY, BEARER_TOKEN)
CHECK status IN (ACTIVE, REVOKED, DISABLED)
```

At most one ACTIVE credential per Provider Account is enforced under a locked Provider Account transaction in V2 Core. Historical rows are retained.

### 7.2 `provider_catalog`

```text
provider_code         VARCHAR(100) PK
name                  VARCHAR(200) NOT NULL
adapter_code          VARCHAR(100) NOT NULL
base_url              VARCHAR(500) NOT NULL
status                VARCHAR(32) NOT NULL
capabilities_json     JSON NOT NULL
created_at            DATETIME(6) NOT NULL
updated_at            DATETIME(6) NOT NULL

CHECK status IN (ACTIVE, DISABLED)
```

Production validation requires HTTPS and an Adapter-governed approved host. Client requests never supply `base_url`.

Initial M11 Provider entry is MiMo with the official Pay-As-You-Go base API host. Alternative Token Plan/dedicated endpoints require explicit server-side Provider configuration work; they are not accepted from inference request payloads.

### 7.3 `model_catalog`

```text
id                         BIGINT AUTO_INCREMENT PK
model_key                  VARCHAR(100) NOT NULL
name                       VARCHAR(200) NOT NULL
status                     VARCHAR(32) NOT NULL
capabilities_json          JSON NOT NULL
default_max_output_tokens  INT NULL
max_output_tokens          INT NOT NULL
created_at                 DATETIME(6) NOT NULL
updated_at                 DATETIME(6) NOT NULL

UNIQUE(model_key)
CHECK status IN (ACTIVE, DISABLED, ARCHIVED)
CHECK max_output_tokens > 0
CHECK default_max_output_tokens IS NULL OR
      (default_max_output_tokens > 0 AND default_max_output_tokens <= max_output_tokens)
```

A billable request may omit `max_completion_tokens` only if a finite `default_max_output_tokens` resolves from governed model policy. No dispatch occurs without an effective finite output ceiling.

### 7.4 `provider_model`

```text
id                    BIGINT AUTO_INCREMENT PK
provider_code         VARCHAR(100) NOT NULL
model_id              BIGINT NOT NULL
provider_model_name   VARCHAR(200) NOT NULL
status                VARCHAR(32) NOT NULL
routing_eligible      BOOLEAN NOT NULL
capabilities_json     JSON NOT NULL
created_at            DATETIME(6) NOT NULL
updated_at            DATETIME(6) NOT NULL

UNIQUE(provider_code, provider_model_name)
UNIQUE(provider_code, model_id, provider_model_name)
CHECK status IN (ACTIVE, DISABLED, RETIRED)
```

---

## 8. Pricing tables

### 8.1 `pricing_version`

```text
id                    BIGINT AUTO_INCREMENT PK
org_id                BIGINT NOT NULL
provider_account_id   BIGINT NOT NULL
provider_model_id     BIGINT NOT NULL
version               INT NOT NULL
currency              CHAR(3) NOT NULL
effective_from        DATETIME(6) NOT NULL
effective_to          DATETIME(6) NULL
status                VARCHAR(32) NOT NULL
created_at            DATETIME(6) NOT NULL
activated_at          DATETIME(6) NULL
retired_at            DATETIME(6) NULL

UNIQUE(org_id, provider_account_id, provider_model_id, version)
CHECK status IN (DRAFT, ACTIVE, RETIRED)
CHECK effective_to IS NULL OR effective_to > effective_from
CHECK currency REGEXP ^[A-Z]{3}$
```

ACTIVE interval overlap is prevented by an application transaction that locks the commercial identity/version set before activation. A Pricing Version already referenced by a Route Attempt is immutable.

### 8.2 `pricing_rate`

```text
id                    BIGINT AUTO_INCREMENT PK
pricing_version_id    BIGINT NOT NULL
org_id                BIGINT NOT NULL
dimension_code        VARCHAR(64) NOT NULL
unit_quantity         BIGINT NOT NULL
unit_price            DECIMAL(20,8) NOT NULL

UNIQUE(pricing_version_id, dimension_code)
CHECK dimension_code IN (INPUT_TOKEN, OUTPUT_TOKEN, CACHED_INPUT_TOKEN, REQUEST)
CHECK unit_quantity > 0
CHECK unit_price >= 0
```

Application validation requires `unit_quantity` to be a power of ten and <= 1,000,000,000.

---

## 9. Routing administration tables

These may be implemented in M14, but their contract is frozen now.

### 9.1 `routing_policy`

```text
id                    BIGINT AUTO_INCREMENT PK
org_id                BIGINT NOT NULL
project_id            BIGINT NULL
model_id              BIGINT NOT NULL
version               INT NOT NULL
status                VARCHAR(32) NOT NULL
created_at            DATETIME(6) NOT NULL
activated_at          DATETIME(6) NULL

UNIQUE(org_id, project_id, model_id, version)
CHECK status IN (DRAFT, ACTIVE, RETIRED)
```

At most one ACTIVE applicable policy for an exact org/project/model context is enforced transactionally.

### 9.2 `routing_policy_candidate`

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

UNIQUE(routing_policy_id, provider_account_id, provider_model_id)
CHECK priority >= 0
CHECK status IN (ACTIVE, DISABLED)
```

M11 may use one server-configured candidate without implementing generic routing-policy administration UI. The runtime attempt schema below is used from M11.

---

## 10. `gateway_request`

Gateway-owned durable business/request identity.

```text
id                         BIGINT AUTO_INCREMENT PK
org_id                     BIGINT NOT NULL
public_request_id          CHAR(40) NOT NULL
credential_id              BIGINT NOT NULL
principal_type             VARCHAR(32) NOT NULL
organization_member_id     BIGINT NULL
service_identity_id        BIGINT NULL
project_id                 BIGINT NOT NULL
financial_scope_type       VARCHAR(32) NOT NULL
financial_scope_id         BIGINT NOT NULL
logical_model_id           BIGINT NOT NULL
api_surface                VARCHAR(32) NOT NULL
idempotency_key_digest     BINARY(32) NOT NULL
request_fingerprint        BINARY(32) NOT NULL
request_hmac_version       SMALLINT UNSIGNED NOT NULL
state                      VARCHAR(32) NOT NULL
billing_period_id          BIGINT NULL
current_route_attempt_id   BIGINT NULL
current_usage_fact_id      BIGINT NULL
created_at                 DATETIME(6) NOT NULL
validated_at               DATETIME(6) NOT NULL
dispatch_intent_at         DATETIME(6) NULL
terminal_at                DATETIME(6) NULL
updated_at                 DATETIME(6) NOT NULL

UNIQUE(public_request_id)
UNIQUE(org_id, credential_id, idempotency_key_digest)
CHECK api_surface IN (CHAT_COMPLETIONS)
CHECK principal_type IN (HUMAN_MEMBER, SERVICE)
CHECK financial_scope_type IN (PROJECT, TEAM, COST_CENTER)
CHECK state IN (
  VALIDATED, RESERVED, DISPATCH_INTENT, UPSTREAM_ACTIVE,
  TRANSPORT_COMPLETED, REJECTED_BUDGET, CANCELED_PRE_DISPATCH,
  FAILED_PRE_DISPATCH, CANCELED_AFTER_DISPATCH,
  TIMED_OUT_AFTER_DISPATCH, FAILED_AFTER_DISPATCH
)
CHECK exactly one principal FK is populated
```

`billing_period_id` is required before entering `DISPATCH_INTENT`.

`current_route_attempt_id` / `current_usage_fact_id` are mutable convenience pointers owned only by Gateway. They never replace append-only lineage.

Provider account/model/pricing are intentionally absent from this table.

---

## 11. `gateway_route_attempt`

Append-only Provider-attempt history; status/timestamps are forward-only lifecycle fields.

```text
id                    BIGINT AUTO_INCREMENT PK
org_id                BIGINT NOT NULL
request_id            BIGINT NOT NULL
attempt_no            INT NOT NULL
route_decision_id     CHAR(40) NOT NULL
routing_policy_id     BIGINT NULL
provider_account_id   BIGINT NOT NULL
provider_model_id     BIGINT NOT NULL
pricing_version_id    BIGINT NOT NULL
status                VARCHAR(32) NOT NULL
safety_reason_code    VARCHAR(64) NULL
provider_request_id   VARCHAR(255) NULL
created_at            DATETIME(6) NOT NULL
dispatch_intent_at    DATETIME(6) NULL
completed_at          DATETIME(6) NULL

UNIQUE(org_id, request_id, attempt_no)
UNIQUE(org_id, route_decision_id)
CHECK attempt_no >= 1
CHECK status IN (
  PLANNED, DISPATCH_INTENT, SAFE_NO_BILLABLE_EXECUTION,
  BILLABLE_POSSIBLE, COMPLETED
)
```

A route attempt is never repointed to a new Provider/Pricing Version.

At most one attempt per request may reach `BILLABLE_POSSIBLE` or `COMPLETED` with possible billable execution. Creating a later attempt requires all prior attempts to be durably `SAFE_NO_BILLABLE_EXECUTION`.

---

## 12. `budget_reservation`

Gateway-owned, MySQL-authoritative spend hold.

```text
id                         BIGINT AUTO_INCREMENT PK
org_id                     BIGINT NOT NULL
request_id                 BIGINT NOT NULL
route_attempt_id           BIGINT NOT NULL
billing_period_id          BIGINT NOT NULL
budget_id                  BIGINT NOT NULL
financial_scope_type       VARCHAR(32) NOT NULL
financial_scope_id         BIGINT NOT NULL
currency                   CHAR(3) NOT NULL
reserved_amount            DECIMAL(20,8) NOT NULL
commitment_id              BIGINT NULL
commitment_backed_amount   DECIMAL(20,8) NOT NULL DEFAULT 0
status                     VARCHAR(32) NOT NULL
version                    BIGINT NOT NULL
expires_at                 DATETIME(6) NOT NULL
created_at                 DATETIME(6) NOT NULL
updated_at                 DATETIME(6) NOT NULL
released_at                DATETIME(6) NULL
finalized_at               DATETIME(6) NULL
```

Required constraints:

```text
UNIQUE(org_id, route_attempt_id)
reserved_amount > 0
0 <= commitment_backed_amount <= reserved_amount
status IN (ACTIVE, PENDING_HOLD, RELEASED, FINALIZED)
version >= 0
```

Migration also creates a generated nullable column conceptually:

```text
effective_slot =
  CASE WHEN status IN ('ACTIVE','PENDING_HOLD') THEN 1 ELSE NULL END
```

with:

```text
UNIQUE(org_id, request_id, effective_slot)
```

so two effective holds for one request cannot coexist. A safe failover must first durably release the old hold.

Economic availability additionally excludes a reservation when the same request already has a durable `gateway_settlement.status = SETTLED`, preventing post-commit cleanup lag from double-subtracting Actual + Reservation.

---

## 13. `gateway_usage_fact`

Append-only observation header.

```text
id                         BIGINT AUTO_INCREMENT PK
org_id                     BIGINT NOT NULL
request_id                 BIGINT NOT NULL
route_attempt_id           BIGINT NOT NULL
sequence                   INT NOT NULL
status                     VARCHAR(16) NOT NULL
supersedes_usage_fact_id   BIGINT NULL
provider_request_id        VARCHAR(255) NULL
usage_effective_at         DATETIME(6) NOT NULL
usage_effective_at_source  VARCHAR(48) NOT NULL
pricing_version_id         BIGINT NOT NULL
currency                   CHAR(3) NOT NULL
safe_provider_metadata_json JSON NULL
observed_at                DATETIME(6) NOT NULL
created_at                 DATETIME(6) NOT NULL

UNIQUE(org_id, request_id, sequence)
CHECK sequence >= 1
CHECK status IN (FINAL, INCOMPLETE, UNKNOWN)
CHECK usage_effective_at_source IN (
  PROVIDER_BILLING_TIMESTAMP,
  PROVIDER_REQUEST_TIMESTAMP,
  GATEWAY_DISPATCH_INTENT_TIMESTAMP
)
```

Migration also creates:

```text
final_slot = CASE WHEN status = 'FINAL' THEN 1 ELSE NULL END
UNIQUE(org_id, request_id, final_slot)
```

This enforces at most one realtime FINAL usage fact per request.

`safe_provider_metadata_json` is Adapter allowlist-only and has an application-enforced serialized-size limit; M11 default maximum is 8 KiB. It never contains prompt/completion/headers/secrets.

### 13.1 `gateway_usage_dimension`

Typed dimensions are chosen instead of a generic JSON financial payload.

```text
id                    BIGINT AUTO_INCREMENT PK
org_id                BIGINT NOT NULL
usage_fact_id         BIGINT NOT NULL
dimension_code        VARCHAR(64) NOT NULL
quantity              DECIMAL(30,8) NOT NULL
provenance            VARCHAR(32) NOT NULL

UNIQUE(usage_fact_id, dimension_code)
CHECK dimension_code IN (INPUT_TOKEN, OUTPUT_TOKEN, CACHED_INPUT_TOKEN, REQUEST)
CHECK quantity >= 0
CHECK provenance IN (PROVIDER_FINAL, PROVIDER_PARTIAL, GATEWAY_DETERMINISTIC)
```

A `FINAL` fact must contain every Pricing Rate dimension required by its frozen Pricing Version. That rule is application-enforced before publication.

---

## 14. `gateway_settlement`

Backend/CostOps-owned durable financial result.

```text
id                         BIGINT AUTO_INCREMENT PK
org_id                     BIGINT NOT NULL
settlement_key             VARCHAR(96) NOT NULL
request_id                 BIGINT NOT NULL
route_attempt_id           BIGINT NOT NULL
usage_fact_id              BIGINT NOT NULL
reservation_id             BIGINT NULL
billing_period_id          BIGINT NOT NULL
financial_scope_type       VARCHAR(32) NOT NULL
financial_scope_id         BIGINT NOT NULL
provider_account_id        BIGINT NOT NULL
provider_model_id          BIGINT NOT NULL
pricing_version_id         BIGINT NOT NULL
currency                   CHAR(3) NOT NULL
calculated_amount_raw      DECIMAL(38,18) NULL
posted_amount              DECIMAL(20,8) NULL
rounding_delta             DECIMAL(38,18) NULL
status                     VARCHAR(32) NOT NULL
attempt_count              INT NOT NULL DEFAULT 0
next_attempt_at            DATETIME(6) NULL
last_error_code            VARCHAR(64) NULL
ledger_posting_id          BIGINT NULL
created_at                 DATETIME(6) NOT NULL
settled_at                 DATETIME(6) NULL
reconciliation_required_at DATETIME(6) NULL
updated_at                 DATETIME(6) NOT NULL

UNIQUE(org_id, settlement_key)
UNIQUE(org_id, request_id)
UNIQUE(org_id, usage_fact_id)
CHECK status IN (PENDING, RETRYABLE_FAILED, RECONCILIATION_REQUIRED, SETTLED)
CHECK attempt_count >= 0
```

Exact keys:

```text
settlement_key = GATEWAY_REQUEST:<public_request_id>
ledger posting_key = GATEWAY_SETTLEMENT:<decimal settlement id>
```

Amounts are non-null only after deterministic calculation; `SETTLED` requires amounts + `ledger_posting_id` + `settled_at`.

---

## 15. Ledger forward extension

V1 migrations are not edited.

Forward migration performs only these semantic changes:

```text
ledger_posting.source_type CHECK
+ GATEWAY_SETTLEMENT

ledger_posting.posting_actor_type VARCHAR(16) NOT NULL
  values MEMBER | SYSTEM

ledger_posting.posted_by_member_id
  becomes nullable

existing ledger_posting rows
  backfill posting_actor_type = MEMBER

CHECK:
  MEMBER -> posted_by_member_id IS NOT NULL
  SYSTEM -> posted_by_member_id IS NULL

ledger_entry.source_gateway_settlement_id BIGINT NULL
  same-org FK -> gateway_settlement(id,org_id)
```

Existing `ledger_entry` correction behavior may carry original Provider/Expense source lineage. The revised source CHECK therefore enforces **at most one** direct primary source among:

```text
source_charge_fact_id
source_expense_claim_id
source_gateway_settlement_id
```

rather than requiring exactly one.

Normal Gateway Settlement entry has:

```text
source_gateway_settlement_id = settlement id
source_charge_fact_id = NULL
source_expense_claim_id = NULL
allocation_line_id = NULL
allocation_decision_id on posting = NULL
posting_actor_type = SYSTEM
posted_by_member_id = NULL
```

A later Correction of that entry preserves the original Gateway source lineage the same way existing correction code preserves Provider/Expense source lineage.

---

## 16. Global financial lock order

The final cross-V1/V2 lock order is frozen as:

```text
BillingPeriod
→ Budgets sorted by id
→ Commitments sorted by id
→ Gateway Reservation / Settlement source rows as applicable
→ existing V1 source/allocation rows when a V1 posting path is used
→ Ledger insertion / posting uniqueness
```

Gateway reservation and V1 Ledger posting serialize on the same Budget row.

Gateway `DISPATCH_INTENT` and Period Close serialize on the same BillingPeriod financial fence.

M13 tests must include both races.

---

# Part B — Gateway Machine API Contract

## 17. Source of truth split

Machine-readable contracts:

```text
Control Plane:
  docs/02-development/api/openapi.yaml
  server/base: /api/v1
  error: application/problem+json

Gateway Data Plane:
  docs/02-development/api/gateway-openapi.yaml
  server/base: /v1
  error: OpenAI-compatible error object
```

A path must exist in only one source of truth.

Business invariants in these M10 Detailed Design documents outrank either YAML when a contradiction is discovered; the same PR must repair the YAML.

---

## 18. M11 Chat Completions compatibility subset

Endpoint:

```text
POST /v1/chat/completions
```

Authentication:

```text
Authorization: Bearer <AI-CostOps Gateway key>
```

Required:

```text
Idempotency-Key
model
messages
```

Supported request fields in M11:

```text
model
messages
max_completion_tokens
stream
```

Message roles:

```text
developer
system
user
assistant
```

M11 message content is text string only.

Explicitly unsupported in M11 and rejected rather than ignored:

```text
tools / tool_choice
function calls
multimodal content arrays
response_format / structured output
provider-specific thinking controls
web-search/provider plugins
audio/video/image input
arbitrary unknown fields
```

This is intentionally a **documented OpenAI-compatible subset**, not a claim of full OpenAI API conformance.

### 18.1 Output ceiling

`max_completion_tokens` is optional for client compatibility.

Effective limit:

```text
client value when provided
else model_catalog.default_max_output_tokens
```

and must be:

```text
1 <= effective <= model_catalog.max_output_tokens
```

If no finite effective limit exists:

```text
reject before Provider dispatch
```

The Gateway always forwards/enforces the resolved finite limit upstream so reservation has a bounded output dimension.

### 18.2 Request body size

M11 Data Plane maximum decoded request body size:

```text
1 MiB = 1,048,576 bytes
```

Larger requests return 413 before Provider dispatch.

Request compression/content-encoding is not supported in M11.

---

## 19. M11 response compatibility

Non-streaming success uses OpenAI Chat Completion shape with at least:

```text
id
object = chat.completion
created
model = logical model key
choices[].index
choices[].message.role = assistant
choices[].message.content
choices[].finish_reason
usage.prompt_tokens
usage.completion_tokens
usage.total_tokens
```

Provider-specific reasoning fields are not required in the M11 public contract.

Streaming success:

```text
Content-Type: text/event-stream
```

and sends OpenAI-style `data: <chat.completion.chunk JSON>` events followed by:

```text
data: [DONE]
```

Gateway does not buffer the whole completion for response replay.

Every success/error after a durable request exists includes:

```text
X-AI-CostOps-Request-Id: gwr_...
X-Trace-Id: ...
```

---

## 20. Replay/recovery API

Because response content is not persisted, financial idempotency and response replay are separate.

Endpoint:

```text
GET /v1/gateway/requests/{requestId}
```

Authentication uses the same Gateway key and only exposes a request owned by that credential.

Response contains only bounded status metadata:

```text
requestId
requestState
meteringStatus NULL | FINAL | INCOMPLETE | UNKNOWN
settlementStatus NULL | PENDING | RETRYABLE_FAILED | RECONCILIATION_REQUIRED | SETTLED
createdAt
updatedAt
```

It never returns prompt/completion, Provider secret, Budget totals or Ledger details.

---

## 21. Idempotent duplicate behavior

Same credential + same Idempotency-Key + same fingerprint:

```text
original request still active/uncertain
→ 409 GATEWAY_REQUEST_IN_PROGRESS
→ original request id header
→ no second Provider dispatch

original request transport completed but response body not retained
→ 409 GATEWAY_RESPONSE_NOT_RETAINED
→ original request id header
→ no second Provider dispatch

original request ended with a durable pre-dispatch rejection
→ replay the same terminal error class
→ no new request/reservation/provider dispatch
```

Same key + different fingerprint:

```text
409 GATEWAY_IDEMPOTENCY_CONFLICT
```

A new business attempt requires a new Idempotency-Key.

---

## 22. Gateway error status/code matrix

| Condition | HTTP | `error.type` | Stable code |
|---|---:|---|---|
| malformed JSON / unsupported field / invalid value | 400 | `invalid_request_error` | `GATEWAY_REQUEST_INVALID` |
| missing/invalid Gateway key | 401 | `authentication_error` | `GATEWAY_AUTH_INVALID` |
| credential scope/model/principal forbidden | 403 | `permission_error` | `GATEWAY_FORBIDDEN` |
| request body too large | 413 | `invalid_request_error` | `GATEWAY_REQUEST_TOO_LARGE` |
| same idempotency key, different request | 409 | `invalid_request_error` | `GATEWAY_IDEMPOTENCY_CONFLICT` |
| same request is already active/uncertain | 409 | `conflict_error` | `GATEWAY_REQUEST_IN_PROGRESS` |
| same request complete but body not retained | 409 | `conflict_error` | `GATEWAY_RESPONSE_NOT_RETAINED` |
| mandatory budget unavailable/exhausted | 429 | `insufficient_quota` | `GATEWAY_BUDGET_EXHAUSTED` |
| rate/quota rejected | 429 | `rate_limit_error` | `GATEWAY_RATE_LIMITED` |
| correctness dependency unavailable before dispatch | 503 | `server_error` | `GATEWAY_DEPENDENCY_UNAVAILABLE` |
| Provider transport/server failure with no safe response | 502 | `server_error` | `GATEWAY_UPSTREAM_FAILED` |
| Provider timeout after dispatch | 504 | `server_error` | `GATEWAY_UPSTREAM_TIMEOUT` |

429/503 may include `Retry-After` when a bounded retry time is known.

For 502/504 after `DISPATCH_INTENT`, callers may retry the **same Idempotency-Key** only to learn/recover the same Gateway request; Gateway must not blindly issue a second Provider operation.

---

## 23. M11 MiMo Adapter compatibility baseline

Verified against official Xiaomi MiMo documentation on 2026-09-02:

```text
Chat Completions endpoint:
https://api.xiaomimimo.com/v1/chat/completions

supports OpenAI Chat Completions compatibility
supports stream boolean
supports max_completion_tokens
current text models include mimo-v2.5-pro / mimo-v2.5
current model documentation lists streaming support
```

Official evidence:

```text
https://mimo.mi.com/docs/en-US/api/chat/openai-api
https://mimo.mi.com/docs/en-US/quick-start/model
https://mimo.mi.com/docs/en-US/api/guidance/error-codes
```

Initial M11 Provider candidate:

```text
provider_code = MIMO
provider model = mimo-v2.5-pro
upstream auth = api-key header via Provider Adapter
```

The logical AI-CostOps model key is **not** the Provider wire model name; it maps through `provider_model`.

### 23.1 M11 retry-safety matrix

M11 deliberately does not interpret the Provider documentation's generic retry recommendation as proof of non-billable execution.

| Failure class | M11 safety | Automatic Provider retry |
|---|---|---|
| Gateway validation/serialization before `DISPATCH_INTENT` | no Provider attempt | n/a |
| any failure after committed `DISPATCH_INTENT` | `BILLABLE_POSSIBLE` unless later positive evidence proves otherwise | **NO** |
| Provider 429 response | `BILLABLE_POSSIBLE` conservative | **NO** |
| Provider 500/503 response | `BILLABLE_POSSIBLE` conservative | **NO** |
| response-header timeout | `BILLABLE_POSSIBLE` | **NO** |
| stream/client/provider disconnect | `BILLABLE_POSSIBLE` | **NO** |

M14 may certify narrower safe classes through Adapter-specific controlled evidence.

### 23.2 Streaming usage certification

The public MiMo docs prove streaming support and show usage for non-streaming responses. M10 does **not** claim from those docs alone that every streaming termination returns a final billable usage object.

Therefore M13 Provider certification must empirically prove the MiMo streaming usage behavior needed for `FINAL` metering. Until such evidence exists, missing final streaming usage becomes `INCOMPLETE/UNKNOWN`, never zero.

---

# Part C — Migration Strategy

## 24. Forward-only migration waves

Current `main` has V1 migrations through V17. M10 does not reserve hardcoded Flyway version numbers because a legitimate V1 patch may consume the next number before M11 begins.

The **semantic order is frozen**; implementation takes the next free versions on current `main`.

### Wave M11 — Edge/identity/catalog/request foundation

Create/extend:

```text
service_identity
gateway_credential
gateway_credential_model
provider_credential
provider_catalog
model_catalog
provider_model
pricing_version
pricing_rate
gateway_request
gateway_route_attempt
```

No Ledger schema change yet.

### Wave M12 — Budget Reservation

Create:

```text
budget_reservation
```

and indexes/generated uniqueness needed for effective holds.

### Wave M13 — Metering/Settlement/Ledger

Create:

```text
gateway_usage_fact
gateway_usage_dimension
gateway_settlement
```

Then forward-alter:

```text
ledger_posting source/actor contract
ledger_entry Gateway Settlement lineage
```

M13 also adds the Gateway `CloseBlockerProvider` implementation against these durable facts.

### Wave M14 — Routing administration

Create generic routing policy/candidate tables if they were not created earlier for admin readiness.

`gateway_route_attempt` already exists from M11, so no runtime-history replacement is needed.

---

## 25. Migration rules

```text
never edit V1-V17 historical migration files
one Backend/Control Plane Flyway owner
Gateway does not run production Flyway
every new enum CHECK is forward-migrated explicitly
same-org FKs wherever relationally expressible
Gateway startup schema compatibility check fails fast when required tables/columns are absent
```

No destructive backfill of V1 financial history is needed except:

```text
ledger_posting.posting_actor_type = MEMBER
```

for existing rows during the M13 forward migration.

---

# Part D — Verification Contract

## 26. Schema verification

Real MySQL 8.4 integration tests must prove:

```text
fresh migrate V1 -> latest V2 wave
V1-V17 checksums unchanged
same-org FK boundaries
gateway credential principal XOR
credential model explicit allowlist path
request idempotency uniqueness
route attempt attempt_no uniqueness
reservation one-effective-hold uniqueness
usage one-FINAL-per-request uniqueness
Settlement request/usage uniqueness
Ledger GATEWAY_SETTLEMENT source accepted
legacy V1 Ledger rows remain MEMBER actor
SYSTEM Gateway posting requires NULL member actor
Gateway DB credential cannot mutate Ledger/Actual/Settlement/Period truth
```

---

## 27. Request/idempotency concurrency matrix

Real MySQL + controllable mock upstream:

```text
100 concurrent identical credential + Idempotency-Key + body
→ one gateway_request
→ at most one effective reservation
→ one route attempt reaching DISPATCH_INTENT
→ one Provider request

same key + changed body byte
→ one winner
→ all mismatches 409 GATEWAY_IDEMPOTENCY_CONFLICT

crash after request VALIDATED
→ no Provider call
→ recoverable pre-dispatch state

crash after reservation before DISPATCH_INTENT
→ no Provider call
→ reservation safe-release recovery

crash immediately after DISPATCH_INTENT
→ no automatic Provider re-dispatch
→ possible-billable recovery path
```

---

## 28. Budget/financial concurrency matrix

Real MySQL:

```text
same Budget many concurrent reservations
→ serialized by Budget row
→ no race overspend

V1 Provider/Expense Ledger posting vs V2 reservation on same Budget
→ deterministic lock convergence
→ no phantom availability

same Commitment concurrent reservations
→ effective commitment-backed reservation <= remaining Commitment

Settlement commit vs new reservation
→ new reservation sees committed Actual
→ settled old hold not double-counted

Gateway DISPATCH_INTENT vs Period Close
→ dispatch wins => Close blocked
or
→ Close wins => no Provider dispatch

never CLOSED period + newly billable request after blocker scan
```

---

## 29. Streaming/metering matrix

Controllable mock Provider supports:

```text
normal JSON + exact usage
normal SSE + final usage
SSE usage then disconnect
SSE no final usage
disconnect before first event
disconnect mid-stream
slow header
idle timeout
hard deadline
429
500/503
malformed JSON/SSE
malformed usage
Provider request id present/absent
Provider billing timestamp present/absent
client disconnect
```

Each case asserts:

```text
request state
route attempt state/safety
usage FINAL/INCOMPLETE/UNKNOWN
no zero-by-missing-usage
reservation consequence
whether Settlement is eligible
whether a second Provider call is forbidden
```

---

## 30. Settlement failure/idempotency matrix

Real MySQL:

```text
concurrent Settlement discovery/creation
→ one gateway_settlement

concurrent duplicate processing
→ one Ledger posting
→ one Budget Actual mutation
→ at most one Commitment consume lineage
→ one financial audit

commit succeeds but worker loses result
→ retry converges to SETTLED
→ no duplicate financial mutation

deadlock/serialization rollback
→ no partial Ledger/Actual/Audit
→ bounded retry same identity

post-commit Reservation cleanup fails
→ Settlement/Ledger remain truth
→ effective availability excludes settled hold
→ later cleanup FINALIZED

posted amount > reservation
→ full actual posts
→ overrun signal

no Budget under OPTIONAL policy
→ Ledger still posts

CLOSED historical period anomaly
→ RECONCILIATION_REQUIRED
→ no period bypass
```

---

## 31. Security/privacy matrix

Tests/CI must prove:

```text
raw Gateway key absent from DB/log/audit
raw Idempotency-Key absent from DB/log/audit
request body/prompt absent from ordinary logs/audit
Provider key absent from client response/log/audit
Provider ciphertext not exposed through API
client cannot choose Provider base URL
prod rejects HTTP external Provider URL
prod rejects missing/default digest/HMAC/KEK secrets
Gateway DB user has no financial write grants
unknown Provider error body is redacted
metric labels contain no request/org/user/credential ids
```

---

## 32. API contract tests

Machine contract tests validate at least:

```text
Gateway OpenAPI parses as 3.1
POST /v1/chat/completions requires Bearer + Idempotency-Key
unknown request fields rejected
text-only message subset enforced
1 MiB body limit enforced
finite output ceiling required before dispatch
non-streaming response shape
SSE content type + [DONE]
OpenAI-compatible error envelope
request/trace correlation headers
GET request-status credential ownership
same key replay never duplicates Provider dispatch
```

The existing Control Plane OpenAPI contract tests remain unchanged except for API README/governance recognition of the second document.

---

## 33. M11 Provider certification before claiming compatibility

M11 may call real MiMo only after a sanitized certification records:

```text
date + Provider doc version/update date
model id
base endpoint
auth header type
accepted M11 fields
non-streaming response/usage fields
stream framing behavior
stream final usage behavior observed
Provider request-id behavior
429/5xx/error-body safe mapping evidence
timeout behavior under controlled proxy where possible
redaction review
```

A synthetic mock is necessary for deterministic failure tests but is not called real Provider certification.

---

## 34. AIC-092 Definition of Done

```text
[freeze] exact public key/request/idempotency formats
[freeze] route-specific Provider/Pricing truth lives on Route Attempt
[freeze] Reservation is per Route Attempt with one effective hold per request
[freeze] credential budget mode is explicit durable policy
[freeze] credential model allowlist is deny-by-default explicit-only
[freeze] typed Usage Dimensions chosen over generic financial JSON
[freeze] one FINAL Usage Fact per request enforced
[freeze] one Settlement per request/FINAL usage enforced
[freeze] first-class GATEWAY_SETTLEMENT Ledger lineage
[freeze] SYSTEM Ledger actor semantics without fake member
[freeze] Control Plane ProblemDetail and Gateway OpenAI error surfaces separated
[freeze] M11 Chat Completions supported subset exact
[freeze] billable POST requires Idempotency-Key
[freeze] response-content replay is not promised
[freeze] request status recovery endpoint exists
[freeze] M11 MiMo post-dispatch automatic retry disabled
[freeze] forward migration waves and sole Flyway owner
[freeze] concurrency/failure/security/provider-certification matrices
```

Any correctness/API/data-ownership change to this contract reopens the relevant AIC before AIC-093 final freeze.
