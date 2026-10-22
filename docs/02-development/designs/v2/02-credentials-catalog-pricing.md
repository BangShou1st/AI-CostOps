# AIC-085 — Credential, Principal, Provider/Model Catalog & Pricing

> Status: **FROZEN CANDIDATE**  
> Depends on AIC-084 runtime/table ownership.

## 1. Goals

Freeze the identities and immutable commercial context needed before any Provider request can be dispatched:

```text
who is calling
which organization/project owns the request
which single financial scope pays for it
which internal Gateway credential authenticates it
which Provider account/credential may execute it
which logical model was requested
which Provider model was selected
which Pricing Version bounds and settles the request
```

This document intentionally avoids a generic policy DSL.

---

## 2. Principal is not the API key

A Gateway Credential is an authentication secret. It is not the business identity itself.

V2 Core supports exactly two principal types:

```text
HUMAN_MEMBER
SERVICE
```

### 2.1 HUMAN_MEMBER

A human principal references the existing:

```text
organization_member
```

No duplicate Gateway user table is created.

The referenced member must belong to the credential organization and be in an allowed active state when the credential is issued/used.

### 2.2 SERVICE

A service principal uses a new narrow organization-scoped entity:

```text
service_identity
```

Required logical fields:

```text
id
org_id
code
name
status = ACTIVE | DISABLED | ARCHIVED
created_at
updated_at
```

`service_identity` is not an interactive login account:

```text
no password
no refresh session
no browser login
no invitation flow
```

It exists only so non-human Gateway usage has a stable auditable identity independent of a secret credential.

Service identity administration belongs to the Control Plane.

---

## 3. Gateway Credential model

Logical durable entity:

```text
gateway_credential
```

Minimum fields:

```text
id
org_id
credential_prefix
secret_digest
secret_digest_version
principal_type
organization_member_id NULL
service_identity_id NULL
project_id
financial_scope_type
financial_scope_id
status
expires_at NULL
created_at
updated_at
revoked_at NULL
```

Principal CHECK:

```text
HUMAN_MEMBER
→ organization_member_id NOT NULL
→ service_identity_id NULL

SERVICE
→ organization_member_id NULL
→ service_identity_id NOT NULL
```

Financial scope CHECK:

```text
financial_scope_type = PROJECT | TEAM | COST_CENTER
financial_scope_id   = one same-org active target
```

The request Project is always explicit. The default financial scope is that Project. Team/Cost Center may be selected only by governed credential configuration and same-org validation.

### 3.1 Key format

Use a structured high-entropy key with a non-secret lookup prefix, conceptually:

```text
aic_<prefix>_<secret>
```

The exact printable alphabet/length is frozen in AIC-092.

Properties:

```text
prefix
= public identifier used to locate credential row

secret
= cryptographically random high-entropy value
= returned only once
= never persisted raw
```

### 3.2 Hash-only persistence

Persist a keyed digest rather than raw key material.

Preferred V2 design:

```text
HMAC-SHA-256(secret, Gateway credential pepper)
```

Store:

```text
secret_digest
secret_digest_version
```

The pepper/key is deployment secret material and is not stored in the application database.

A version field permits future digest-key rotation policy. Existing credentials may remain valid under an explicitly supported digest version until rotated/revoked.

Authentication compares digests in constant-time.

### 3.3 Raw key disclosure

Raw Gateway key is returned exactly once at creation/rotation.

Control Plane later exposes only safe metadata:

```text
credential id
prefix
principal
project
financial scope
status
expiry
created/revoked timestamps
last-used metadata
```

No “show secret again” endpoint exists.

### 3.4 Lifecycle

Credential lifecycle:

```text
ACTIVE
REVOKED
DISABLED
```

Expiry is determined by `expires_at`; an expired credential is unusable even if status remains ACTIVE.

Rotation means:

```text
create successor credential
→ return successor raw secret once
→ optionally allow explicitly bounded overlap
→ revoke predecessor
```

Do not overwrite the digest of an already-issued credential in place when rotation lineage matters.

### 3.5 Revocation behavior

Revocation blocks new requests.

An already-dispatched Provider request is not silently canceled merely because the credential is later revoked; its incurred cost must still meter/settle.

Initial V2 does not require an authorization cache. Since the request path already performs durable DB work before dispatch, M11/M12 may read credential status from MySQL directly for simple, immediate revoke semantics.

If a credential cache is introduced later:

```text
cache is not auth truth
revocation must have bounded propagation
Redis/cache outage cannot resurrect a revoked credential
```

---

## 4. Gateway Credential runtime metadata

AIC-084 keeps administrative tables single-writer under Backend. Gateway must not update `gateway_credential` merely for usage telemetry.

If last-used metadata is retained, use a separate Gateway-owned runtime projection, conceptually:

```text
gateway_credential_runtime
- credential_id
- last_used_at
- last_request_id
- updated_at
```

This table is operational metadata, not authorization truth. Failure to update it must not fail Settlement or change credential status.

High-cardinality request counts belong in metrics/read models, not an ever-growing credential row.

---

## 5. Credential scope is deliberately narrow

A Gateway Credential authorizes inference traffic only for its bound context.

V2 Core does not reuse browser/admin RBAC tokens on the Data Plane.

Required credential constraints:

```text
one organization
one project
one principal
one financial scope
optional allowed logical-model set
expiry/status
```

Provider credentials are never selected directly by the client credential.

No generic custom authorization DSL is added.

### 5.1 Optional model allowlist

If product policy requires model restriction, use an explicit relation:

```text
gateway_credential_model
credential_id
model_id
```

Empty relation means all enabled models allowed by the organization/project policy; if that default proves too broad in final AIC-092 threat review, invert it to explicit-only before freeze. The machine API must not accept arbitrary Provider model ids that bypass the catalog.

---

## 6. Provider Account remains the organization commercial account

Existing V1:

```text
provider_account
- org_id
- provider_code
- display_name
- external_account_ref
- status
- metadata
```

V2 retains this meaning.

A Provider Account is not itself a secret.

Gateway routing and Pricing Version always identify the Provider Account used so later Provider statement reconciliation can match the same commercial account.

---

## 7. Provider Credential model

Provider secrets never reach clients and are not stored plaintext.

V2 Docker-first baseline chooses encrypted database persistence rather than requiring an external Secret Manager.

Logical entity:

```text
provider_credential
```

Minimum fields:

```text
id
org_id
provider_account_id
credential_type
ciphertext
nonce/iv
encryption_key_version
safe_label NULL
status
created_at
rotated_at NULL
revoked_at NULL
```

### 7.1 Encryption

Use authenticated encryption, conceptually:

```text
AES-256-GCM
```

with a deployment Key Encryption Key / master secret supplied outside MySQL.

Rules:

```text
raw Provider secret never stored plaintext
raw Provider secret never returned after creation/rotation
ciphertext/nonce never written to normal logs
decryption occurs only in the Gateway/provider credential access boundary
encryption key version is explicit
```

The Control Plane needs encryption capability for credential administration; Gateway needs decryption capability for Provider calls. The shared KEK is deployment secret material and must be injected separately into both deployables.

Future external Secret Manager support may replace ciphertext with a secret reference through the same narrow port, but it is not a V2 Core dependency.

### 7.2 Provider credential rotation

Rotation creates a new credential version/row and changes the active binding; do not mutate historical request/route records to point at the new credential.

Gateway request/route facts store only safe credential identity/version ids, never secret material.

---

## 8. Provider Catalog

M10 distinguishes Provider definition from organization Provider Account.

Logical concept:

```text
provider_catalog
```

Minimum fields:

```text
provider_code
name
status
adapter_code
capabilities
```

`provider_code` remains the stable identifier already used by V1 Provider Account/import flows.

The initial implementation may seed supported Provider definitions rather than expose generic user-created Providers.

Provider capability examples are bounded enums, not free-form routing rules:

```text
CHAT_COMPLETIONS
RESPONSES
SSE_STREAMING
USAGE_IN_FINAL_CHUNK
CACHED_INPUT_USAGE
```

The exact capability set is frozen after AIC-092 Provider/client compatibility review.

---

## 9. Logical Model Catalog

Client-visible model identity is separated from Provider model ids.

Logical entity:

```text
model_catalog
```

Minimum fields:

```text
id
model_key
name
status
capabilities
```

Example semantics:

```text
model_key
= stable AI-CostOps logical model requested by client

name
= display label
```

The client does not route by a Provider account id or Provider secret.

---

## 10. Provider Model mapping

Logical relation:

```text
provider_model
```

Minimum fields:

```text
id
provider_code
model_id
provider_model_name
status
routing_eligible
capabilities
```

where:

```text
model_id
= AI-CostOps logical model id

provider_model_name
= exact Provider wire model identifier
```

This allows one logical model to have zero or more Provider implementations.

M11 may enable only one Provider mapping. M14 introduces multi-Provider routing.

Model substitution across materially different model semantics is forbidden unless the routing policy explicitly allows that logical equivalence.

---

## 11. Provider Account model availability

Routing must bind a Provider model to an organization Provider Account that is:

```text
same org
ACTIVE
has an ACTIVE Provider Credential
allowed for the logical model
routing eligible
```

If the same Provider has multiple organization accounts, routing and pricing identify the specific account.

No client request may select a secret credential id directly.

---

## 12. Pricing Version is immutable commercial context

Realtime reservation/settlement cannot query a mutable “current price” after the request has executed.

Logical entity:

```text
pricing_version
```

Minimum fields:

```text
id
org_id
provider_account_id
provider_model_id
version
currency
effective_from
effective_to NULL
status = DRAFT | ACTIVE | RETIRED
created_at
activated_at NULL
```

Used Pricing Versions are immutable.

A price change creates a new version.

### 12.1 Effective interval

Intervals are half-open:

```text
[effective_from, effective_to)
```

For a given:

```text
org + provider account + provider model + currency
```

ACTIVE effective intervals may not overlap.

Overlap checks are application-enforced under deterministic row locking; MySQL constraints alone are not assumed to solve interval overlap.

### 12.2 Pricing rates

Do not hide financial math in an unvalidated JSON blob.

Logical child:

```text
pricing_rate
```

Minimum fields:

```text
id
pricing_version_id
dimension_code
unit_quantity
unit_price
```

Initial normalized dimensions may include:

```text
INPUT_TOKEN
OUTPUT_TOKEN
CACHED_INPUT_TOKEN
REQUEST
```

Provider-specific dimensions are added only when the Adapter can produce a deterministic normalized quantity and tests prove the calculation.

Money uses existing project semantics:

```text
MySQL DECIMAL(20,8)
Java BigDecimal
API decimal string
```

Usage quantity may use wider exact decimal/integer precision as appropriate, but never floating point financial truth.

### 12.3 No tier/discount engine in V2 Core

V2 Core does not attempt full contract pricing complexity such as:

```text
volume tiers
committed-use discount engine
private negotiated discount DSL
automatic FX
```

If a Provider invoice differs because of discount/tier/rounding, M15 Hybrid Reconciliation records/corrects the difference.

The realtime Pricing Version must still represent the deterministic price policy the organization intentionally used at dispatch time.

---

## 13. Request pricing snapshot

Before reservation/dispatch, Gateway resolves and persists:

```text
provider_account_id
provider_model_id
pricing_version_id
pricing_currency
```

This is the request pricing snapshot.

The request cannot later silently switch to a different Pricing Version merely because an administrator activates a new version while the stream is in progress.

If M14 failover changes Provider account/model/price before any possible billable execution, Gateway must recompute/revalidate reservation with the new Pricing Version before dispatching the replacement route.

---

## 14. Currency rule

V2 Core has no implicit FX.

For a budget-controlled request:

```text
Pricing Version currency
= Reservation currency
= selected Budget currency
= realtime Settlement currency
```

If no same-currency Budget exists:

```text
budget-required policy
→ reject before Provider dispatch

explicitly unbudgeted-allowed policy
→ no Budget reservation
→ request may proceed
→ any incurred cost still posts in source pricing currency
```

No “convert to the org default currency” fallback exists.

---

## 15. Pricing and Provider statement truth

Realtime Pricing Version is the internal request-time commercial rule, not a claim that it equals the final Provider invoice.

Provider statement may later differ because of:

```text
discount
rounding
pricing change
Provider correction
late fee/credit
unknown tiering
```

M15 reconciliation compares statement truth to realtime Settlement and creates explicit difference/correction lineage.

It does not destructively rewrite the historical Pricing Version used for the request.

---

## 16. Audit requirements

Audit these administrative actions:

```text
SERVICE_IDENTITY_CREATED / UPDATED / DISABLED / ARCHIVED
GATEWAY_CREDENTIAL_CREATED / ROTATED / REVOKED / DISABLED
PROVIDER_CREDENTIAL_CREATED / ROTATED / REVOKED
MODEL_CATALOG_CHANGED
PROVIDER_MODEL_CHANGED
PRICING_VERSION_CREATED / ACTIVATED / RETIRED
```

Audit metadata contains identifiers, enum/status and safe labels only.

Never audit:

```text
raw Gateway key
Gateway digest
raw Provider key
Provider ciphertext
KEK/pepper
Prompt/Completion
Authorization header
```

---

## 17. Security validation at request time

Before reservation, Gateway validates at least:

```text
credential prefix/digest
credential ACTIVE + not expired
principal ACTIVE
same organization
project ACTIVE and bound to credential
financial scope same-org/allowed
logical model allowed
enabled Provider/model candidate exists
Provider Account ACTIVE
Provider Credential ACTIVE
Pricing Version resolvable
```

Failure occurs before durable dispatch intent and before Provider I/O.

Privacy-preserving external error responses do not reveal whether another organization has a matching id/resource.

---

## 18. AIC-085 Definition of Done

```text
[freeze] HUMAN_MEMBER and SERVICE are explicit principals
[freeze] service identity is separate from secret credential
[freeze] raw Gateway key returned once, HMAC digest only persisted
[freeze] Gateway credential binds org/project/principal/one financial scope
[freeze] Provider secret stored encrypted, not plaintext
[freeze] Provider Account remains commercial account identity
[freeze] logical model separated from Provider wire model
[freeze] Provider/model/account eligibility is explicit
[freeze] Pricing Version is immutable after use
[freeze] price rates are exact typed dimensions, not floating point
[freeze] no automatic FX
[freeze] request persists exact Provider Account/Model/Pricing Version context before dispatch
[freeze] realtime pricing does not overwrite later statement truth
```

Any later M10 document that requires a credential, model or pricing concept not representable here must reopen AIC-085 before AIC-093.
