# M17 — V3 Runtime Contract Clarifications

> Status: **NORMATIVE DESIGN FREEZE CANDIDATE**  
> Parent spec: `2026-09-10-m17-v3-model-provider-hub-cost-intelligence-design.md`  
> Issue: #153

This document tightens runtime/migration details discovered during fresh review of the V2 implementation. Where this document is more specific than the parent spec, this document controls.

## 1. Verified V2 endpoint authority

At the frozen V2 baseline, `RoutingPolicyMapper` resolves candidate `base_url` from global `provider_catalog.base_url`. `GatewayRequestService.DispatchResult` carries that URL and `ChatCompletionController` turns it into `ProviderCallContext` before invoking the selected adapter.

Therefore V3 MUST NOT merely create a connection-profile table while leaving the old join active.

## 2. V24 endpoint-authority migration

V24 changes runtime endpoint authority to the exact ACTIVE `provider_connection_profile` selected for the Provider Account.

Required migration behavior:

```text
1. Create provider_connection_profile.
2. For every existing Provider Account that is currently structurally routable,
   backfill version 1 from its current provider_catalog defaults.
3. Preserve provider code, adapter behavior and exact existing base URL.
4. Mark the backfilled row ACTIVE.
5. Add required same-org uniqueness/FKs.
6. Update Gateway routing reads to require an ACTIVE connection profile.
7. Candidate base_url/protocol/network policy comes from that profile.
8. provider_catalog.base_url becomes legacy/default seed metadata only;
   it is no longer dispatch-time endpoint authority after V24.
```

No previously routable V2 account may silently become unroutable solely because V24 was applied. A migration test must capture eligible V2 fixtures before V24 and prove equivalent candidate resolution after V24.

## 3. Provider catalog role after V24

`provider_catalog` remains the global server-owned provider/adapter definition:

```text
provider_code
name
adapter_code
capabilities
status
legacy/default base_url metadata
```

It answers **what adapter/provider family this is**, not **where this organization connects to it**.

For V3 the global catalog seeds at least:

```text
OPENCODE_ZEN
  adapter/protocol family = OPENAI_CHAT_COMPLETIONS for V3-supported models

CUSTOM_OPENAI_COMPATIBLE
  adapter/protocol family = OPENAI_CHAT_COMPLETIONS
```

A custom organization does not create arbitrary global provider catalog rows.

## 4. Private Provider Model identity

The parent design's nullable `owner_org_id` is necessary but not sufficient. Two custom connections inside the same organization may expose the same wire model id.

V24 therefore extends `provider_model` conceptually with:

```text
owner_org_id          BIGINT NULL
provider_account_id   BIGINT NULL
```

Semantics:

```text
system-global Provider Model:
  owner_org_id = NULL
  provider_account_id = NULL

organization-private/custom Provider Model:
  owner_org_id = current org
  provider_account_id = exact organization Provider Account
```

For organization-private rows, the Provider Account's `provider_code` must match `provider_model.provider_code`.

Uniqueness must represent:

```text
GLOBAL:
  unique(provider_code, provider_model_name) within global namespace

ORG PRIVATE:
  unique(owner_org_id, provider_account_id, provider_model_name)
```

Do not rely on MySQL NULL uniqueness behavior alone for this dual namespace. Use generated/helper namespace columns or another explicit constraint strategy proven by integration tests.

Routing candidate activation verifies the pair:

```text
(provider_account_id, provider_model_id)
```

is either:

```text
same global provider family
```

or:

```text
exact same-org private provider model bound to that Provider Account.
```

This prevents a custom model from Connection A being routed through Connection B merely because model strings match.

## 5. Logical Model ownership

`model_catalog.owner_org_id` follows the same dual namespace principle:

```text
NULL      = global logical model
org id    = organization-private logical model
```

A private logical `model_key` is unique inside one organization. The external Gateway `model` string resolver uses:

```text
credential org
+ model_key
```

and can resolve either a visible global key or a same-org private key.

If a private key collides with a global key, V3 rejects creation rather than introducing ambiguous shadowing. This keeps Gateway request parsing deterministic.

## 6. Routing candidate runtime shape

After V24 a resolved candidate contains at least:

```text
providerAccountId
providerModelId
providerModelName
providerConnectionProfileId
pricingVersionId
currency
baseUrl
completionPath
protocolCode
networkPolicy
adapterCode
credentialReady
routingEligible
verifiedCapabilities
```

`DispatchResult` and `ProviderCallContext` carry `providerConnectionProfileId` and the bounded connection fields needed by the adapter.

Provider-specific secret header construction remains inside the adapter/transport boundary. Generic orchestration never logs or serializes the secret.

## 7. Adapter boundary

V3 avoids one Java adapter class per user-created provider.

Registry shape:

```text
MIMO                         -> existing MimoChatAdapter
OPENAI                       -> existing OpenAiChatAdapter
OPENCODE_ZEN                 -> OpenCodeZenChatAdapter/transport policy
CUSTOM_OPENAI_COMPATIBLE     -> GenericOpenAiCompatibleChatAdapter
```

The generic custom adapter consumes safe `ProviderCallContext` connection settings. It may not accept arbitrary JSON transforms or arbitrary headers.

OpenCode has a distinct adapter/transport entry even though its payload is OpenAI-compatible because `DIRECT_ONLY`, provider-owned User-Agent, model manifest and diagnostics are provider-specific policy and must not drift into the generic custom adapter.

## 8. Existing V2 providers

MiMo/OpenAI behavior remains backward compatible through backfilled profiles. V24 does not force existing admins to recreate credentials, prices, logical models or routing policies.

Historical V2 Route Attempts without `provider_connection_profile_id` remain valid historical evidence. The new column is nullable for old rows but required for all post-V24 planned attempts.

## 9. Model discovery persistence rules

`provider_model_discovery` is keyed to an exact connection profile version, not only Provider Account:

```text
org_id
provider_connection_profile_id
provider_model_name
```

Reason: changing endpoint/profile creates a new observation lineage. A model seen on v1 is not silently asserted to exist on v2.

Refreshing discovery creates/updates observations for the active profile and marks previously seen-but-now-absent entries `UNAVAILABLE`; it never deletes historical observations.

## 10. Probe semantics

Connection/model probes are explicit user/admin actions in V3.0. There is no autonomous high-frequency provider health poller.

Why:

```text
Provider probes may consume quota or money.
A health loop must not create hidden spend.
```

UI Health derives from the last explicit probe plus a staleness classification:

```text
HEALTHY      = last probe PASS and not stale
DEGRADED     = last known PASS but stale or partial capability uncertainty
FAILED       = last probe failed
UNKNOWN      = never probed / profile changed since last probe
```

Staleness is display/readiness metadata, not automatic Provider I/O.

## 11. Cost Intelligence run model

V3 does not require a distributed scheduler.

Use a DB-backed deterministic run identity:

```text
cost_intelligence_run
  id
  org_id
  analysis_date
  currency
  run_version
  status = PENDING | RUNNING | COMPLETED | FAILED
  started_at
  completed_at
  failure_code NULL
```

Uniqueness:

```text
(org_id, analysis_date, currency, run_version)
```

A low-frequency Spring scheduler may enqueue/claim due daily runs, but MySQL uniqueness/claim state is the convergence authority. Multiple backend replicas must converge on one logical run.

User-facing reads do not require the scheduler to be alive: when no fresh run exists, the API may compute a bounded synchronous summary or clearly return staleness; it never fabricates fresh data.

The engine persists derived anomaly/forecast/recommendation evidence, not duplicate Ledger truth.

## 12. Advisor job state machine

`advisor_inference_job` is explicit asynchronous durable work:

```text
PENDING
  -> CLAIMED
  -> DISPATCHING
  -> RUNNING
  -> COMPLETED

PENDING/CLAIMED/DISPATCHING/RUNNING
  -> FAILED

CLAIMED with expired lease before any durable Gateway dispatch
  -> PENDING (safe reclaim)

Once linked Gateway execution is BILLABLE_POSSIBLE or later
  -> NEVER create a replacement Provider execution automatically
```

Required fields include:

```text
id
org_id
requested_by
subject_type
subject_id
evidence_fingerprint
advisor_profile_version
status
claim_token NULL
claim_expires_at NULL
gateway_request_id NULL
attempt_count
failure_code NULL
created_at
started_at NULL
completed_at NULL
```

`attempt_count` counts explicit Advisor executions/retries, not hidden transport retries.

## 13. Advisor claim/fencing

Worker claim transaction:

```text
lock one eligible PENDING/expired-safe CLAIMED job
verify no linked gateway_request_id exists
assign random claim_token
set CLAIMED + bounded lease
commit
```

After commit, worker creates/converges the governed Gateway request using a deterministic internal idempotency identity derived from immutable job/attempt identity.

The job is then linked transactionally to exactly one `gateway_request_id` for that explicit attempt.

If two workers race, DB uniqueness/idempotency must converge on one Gateway Request and one Provider dispatch lineage.

## 14. Advisor crash recovery

Recovery distinguishes two cases:

```text
A. no gateway_request linked and claim lease expired
   => safe to return to PENDING

B. gateway_request linked
   => never create a new Provider call merely because job lease expired
   => inspect/converge from existing Gateway Request / Route Attempt state
```

If existing Gateway state is financially uncertain, Advisor job remains/ends FAILED with an uncertainty-safe code; it does not redispatch.

This is the same no-blind-redispatch invariant applied to asynchronous Advisor work.

## 15. Explicit Advisor Retry

A user-authorized retry creates a new Advisor attempt identity while retaining parent lineage:

```text
advisor_inference_job_attempt
or equivalent append-only attempt child
```

Each explicit retry has its own governed Gateway request, budget admission, Provider attempt, usage, Settlement and Ledger effect.

The API never mutates an old billable attempt into a new one.

## 16. Advisor output persistence

V3 persists only bounded validated Advisor narrative needed for product history:

```text
summary
recommended_actions
warnings
fact_reference_ids
schema_version
```

Hard size limits apply per field and total document.

Before persistence:

```text
parse exact schema
reject unknown authoritative-money fields
sanitize/control characters
validate fact references exist in the envelope
```

Raw provider body, hidden reasoning, arbitrary response metadata, Authorization and upstream workload prompts/completions are not stored.

## 17. OpenCode current-protocol rule

As of the M17 design date, OpenCode Zen publishes multiple endpoint families (including `/chat/completions`, `/responses`, and `/messages`) and a live `/models` catalog. V3.0 intentionally implements only `OPENAI_CHAT_COMPLETIONS`.

The template manifest is the controlled compatibility list. A live model not classified for Chat Completions remains visible as unsupported/unknown rather than being guessed into the generic adapter.

## 18. V24 compatibility tests

Before M18 can claim the migration complete, integration tests prove:

```text
pre-V24 currently eligible MiMo/OpenAI fixture
  -> V24 backfill
  -> same provider account/model/price
  -> ACTIVE connection profile v1
  -> same effective endpoint
  -> still eligible

custom Provider Account A + model-x
custom Provider Account B + model-x
  -> both coexist in same org
  -> routing A cannot select B private provider_model

org A private model-x
org B private model-x
  -> both coexist
  -> neither leaks across org
```

## 19. Freeze declaration

M18 implementation must follow this refined runtime contract. Changing endpoint authority, private model identity, Advisor reclaim/redispatch behavior, or the no-autonomous-probe rule requires reopening M17 design rather than being treated as an implementation detail.
