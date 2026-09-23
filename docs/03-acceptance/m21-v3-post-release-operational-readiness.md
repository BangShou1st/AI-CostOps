# M21 — V3 Post-Release Operational Readiness

## Baseline

```text
Release:      v3.0.0
Commit:       9c55125c1b857e3ccf301875d8886131a9d1d9b0
Branch:       chore/m21-v3-operational-readiness
Issue:        #161
PR:           #162
Date:         2026-09-15
Final HEAD:   e71985f55d5f09bce5298429dc7a8adcd84b4a61
```

## Product Baseline vs Operational Harness

```text
Product baseline:     v3.0.0 / 9c55125c1b857e3ccf301875d8886131a9d1d9b0
Operational harness:  PR #162 final HEAD (resolved externally by GitHub CI)
```

## M21 Post-Release Security Fix

### Issue

The frozen M18 security contract requires transport-level public-only DNS rebinding defense for all Gateway Provider dispatch. Investigation confirmed:

| Adapter | PublicOnlyAddressResolverGroup (prod) | Status |
|---|---|---|
| GenericOpenAiCompatibleChatAdapter | YES | FROZEN M18 |
| OpenCodeZenChatAdapter | YES | FROZEN M18 |
| MimoChatAdapter | NO → FIXED | M21 FIX |
| OpenAiChatAdapter | NO → FIXED | M21 FIX |

### Fix

- `MimoChatAdapter`: Now uses `PublicOnlyAddressResolverGroup` when `prod` profile is active
- `OpenAiChatAdapter`: Now uses `PublicOnlyAddressResolverGroup` when `prod` profile is active

Non-production profiles (dev/test) use the default JVM resolver to allow local mock validation.

### Regression Tests Added

- `MimoChatAdapterResolverTest.java`: Verifies resolver wiring via `isPublicOnlyResolverActive()` test seam
- `OpenAiChatAdapterResolverTest.java`: Verifies resolver wiring via `isPublicOnlyResolverActive()` test seam

**Mutation proof**: Removing the resolver wiring line flips the boolean flag to false, causing tests to FAIL.

## Runtime Environment Bootstrap

### Script

`scripts/m21/new-v3-operational-env.ps1`

Reads `.env.example` as base template, overrides sensitive values with .NET `RandomNumberGenerator` CSPRNG.

### Generated Values

- `AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1` (32 random bytes, Base64)
- `AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1` (32 random bytes, Base64)
- `AICOSTOPS_PROVIDER_KEK_V1` (32 random bytes, Base64)
- `AICOSTOPS_GATEWAY_DEV_RAW_KEY` (valid `aic_<12 Crockford-Base32>_<43 Base64URL>` shape, validated against parser regex)
- `AICOSTOPS_MIMO_API_KEY` (synthetic, local-only)
- `MYSQL_GATEWAY_USER` / `MYSQL_GATEWAY_PASSWORD` (Gateway DB identity)
- All root compose required vars (MYSQL_DATABASE, MYSQL_USER, MYSQL_PASSWORD, MYSQL_ROOT_PASSWORD, REDIS_PASSWORD, MINIO_ROOT_USER, MINIO_ROOT_PASSWORD, MINIO_BUCKET, AICOSTOPS_JWT_SIGNING_KEY, etc.)

All values written to `.env.m21.local` (gitignored). Never committed.

## Gateway DB Least Privilege

### Script

`scripts/m21/provision-gateway-db.ps1`

### Privilege Contract

Frozen M16 contract + V3 runtime reads derived from Gateway mappers.

**Runtime SELECTs**: billing_period, budget, ledger_posting, ledger_entry, organization, organization_member, project, team, cost_center, model_catalog, provider_account, provider_model, provider_catalog, provider_connection_profile, provider_credential, pricing_version, pricing_rate, routing_policy, routing_policy_candidate, gateway_credential, gateway_credential_model, service_identity, gateway_settlement

**Gateway-owned writes**: gateway_request, gateway_route_attempt, gateway_usage_fact, gateway_usage_dimension, budget_reservation

**Never granted**: ALL PRIVILEGES, GRANT OPTION, DELETE, DROP, DDL, control-plane UPDATE, provider_credential UPDATE, ledger mutation outside frozen contract

### Verification Matrix

`scripts/m21/verify-v3-gateway-privileges.ps1`

Positive: runtime SELECTs, SELECT ... FOR UPDATE, Gateway-owned INSERT/UPDATE (rolled back)
Negative: budget UPDATE, billing_period close, ledger INSERT, provider_credential UPDATE, DDL, DELETE, GRANT

Output: `M21_GATEWAY_PRIVILEGE_GREEN`

## Synthetic V3 Seed

### Script

`scripts/m21/seed-v3-operational.ps1`

Creates ACTIVE `provider_connection_profile` with:
- endpoint: `http://mock-provider:8089/v1`
- auth_type: `API_KEY_HEADER` (valid per V24 schema CHECK constraint)
- auth_header_name: `X-API-Key`

Hard-fails (exit 1) if required preconditions missing (provider_account, provider_credential, routing_policy, candidate, pricing).

Outputs machine-readable state to `.m21-operational-state.json` (gitignored).

## Gateway Execution Smoke

### Script

`scripts/m21/invoke-v3-operational-smoke.ps1`

Validates:
1. Gateway health
2. HTTP 200 from `POST /v1/chat/completions`
3. Deterministic mock response ("Hello from M16 mock")
4. Mock invocation delta exactly +1
5. **EXACT profile equality**: locates gateway_request by idempotency key digest, verifies route_attempt.provider_connection_profile_id == seeded profile id

Output: `M21_V3_GOVERNED_SMOKE_PASS`

## Defects

### P1 Security Defect (M21 Discovery)

**M21-SEC-001** — FIXED
```text
Title:    MiMo and OpenAI adapters lacked PublicOnlyAddressResolverGroup in production
Impact:   Violates frozen M18 DNS-rebinding / SSRF contract
Fix:      Conditional resolver wiring: prod profile → PublicOnlyAddressResolverGroup
Tests:    MimoChatAdapterResolverTest, OpenAiChatAdapterResolverTest
Mutation: Removing resolver line flips boolean flag, tests FAIL
Severity: P1 Security
```

### P2 Operational Defects (This Reseal)

**M21-OPS-002** — FIXED
```text
Title:    .env.m21.local missing root compose required variables
Impact:   Compose config fails with unresolved vars
Fix:      Env bootstrap reads .env.example as base template, overrides sensitive values
```

**M21-OPS-003** — FIXED
```text
Title:    seed-v3-operational.ps1 used illegal auth_type='API_KEY'
Impact:   INSERT fails V24 schema CHECK constraint
Fix:      Changed to auth_type='API_KEY_HEADER' with auth_header_name='X-API-Key'
```

**M21-OPS-004** — FIXED
```text
Title:    provision-gateway-db.ps1 used Set-StrictMode -Latest (invalid)
Impact:   Script fails under strict mode
Fix:      Changed to Set-StrictMode -Version Latest
```

**M21-OPS-005** — FIXED
```text
Title:    smoke script only checked profile_id IS NOT NULL, not exact equality
Impact:   Does not prove seeded profile was used for dispatch
Fix:      Smoke locates request by idempotency digest, asserts exact profile match
```

**M21-OPS-006** — FIXED
```text
Title:    SSRF regression tests only checked assertNotNull/adapterCode
Impact:   Tests pass even if resolver wiring removed
Fix:      Tests verify isPublicOnlyResolverActive() boolean flag
```

## Files Changed

```text
NEW:  scripts/m21/new-v3-operational-env.ps1 (CSPRNG env bootstrap from .env.example template)
NEW:  scripts/m21/verify-v3-gateway-privileges.ps1 (complete privilege matrix)
NEW:  scripts/m21/bootstrap-v3-operational.ps1 (convenience wrapper)
FIX:  scripts/m21/seed-v3-operational.ps1 (API_KEY_HEADER, hard-fail, state output)
FIX:  scripts/m21/provision-gateway-db.ps1 (StrictMode fix, container-side password)
FIX:  scripts/m21/invoke-v3-operational-smoke.ps1 (exact profile equality via idempotency)
FIX:  gateway/src/main/java/.../MimoChatAdapter.java (prod DNS rebinding fix + test seam)
FIX:  gateway/src/main/java/.../OpenAiChatAdapter.java (prod DNS rebinding fix + test seam)
NEW:  gateway/src/test/java/.../MimoChatAdapterResolverTest.java (regression test)
NEW:  gateway/src/test/java/.../OpenAiChatAdapterResolverTest.java (regression test)
FIX:  docs/04-operations/v3-operational-runbook.md (self-contained flow, no hardcoded passwords)
FIX:  .gitignore (add .m21-operational-state.json)
```

## Candidate Decision (historical review snapshot)

```text
Bootstrap self-contained (CSPRNG, complete env) = PASS
Gateway raw key generated reproducibly (regex validated) = PASS
Synthetic Provider credential generated reproducibly = PASS
Gateway DB identity separate = PASS
Complete positive/negative privilege matrix = PASS
All V3 runtime SELECTs granted = PASS
Synthetic V24+ seed (API_KEY_HEADER, schema-valid) = PASS
ACTIVE connection profile = PASS
Gateway request reaches controlled mock = PASS
Mock delta exactly +1 = PASS
route_attempt.profile_id EXACT MATCH = PASS
MIMO SSRF/security contract reconciled = PASS
Production code fix with TDD regression tests = PASS
Documentation self-contained = PASS

P0: 0
P1: 0 (1 fixed)
P2: 0 (5 fixed)

M21_OPERATIONAL_READY_CANDIDATE (historical review snapshot)
```

The candidate label and pending-review note above describe the review snapshot at that time. M21 operational readiness was subsequently completed as part of V3 final closure.

## Final Closure

```text
M21 = COMPLETE
Operational readiness = COMPLETE
```

After V3 final stabilization, these provider execution invariants are confirmed:

```text
Provider execution uses the exact frozen provider_connection_profile_id.
auth_type=NONE performs zero credential lookup/decryption.
```

M22's separately documented real-provider / OpenCode credential and independent browser-context organization-isolation gates remain `DEFERRED / NON-BLOCKING`; this M21 closure does not mark those gates PASS. See [M22 V3 Production Certification](m22-v3-production-certification.md).
