# M21 — V3 Post-Release Operational Readiness

## Baseline

```text
Release:      v3.0.0
Commit:       9c55125c1b857e3ccf301875d8886131a9d1d9b0
Branch:       chore/m21-v3-operational-readiness
Issue:        #161
PR:           #162
Date:         2026-09-15
Final HEAD:   8ecde8299c3497c0aa9972fd25562e4274fcfe86
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

- `MimoChatAdapterResolverTest.java`: Verifies adapter wiring for prod/non-prod profiles
- `OpenAiChatAdapterResolverTest.java`: Verifies adapter wiring for prod/non-prod profiles

## Runtime Environment Bootstrap

### Script

`scripts/m21/new-v3-operational-env.ps1`

Uses .NET `System.Security.Cryptography.RandomNumberGenerator` for all cryptographic values.

### Generated Values

- `AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1` (32 random bytes, Base64)
- `AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1` (32 random bytes, Base64)
- `AICOSTOPS_PROVIDER_KEK_V1` (32 random bytes, Base64)
- `AICOSTOPS_GATEWAY_DEV_RAW_KEY` (valid `aic_<12 Crockford-Base32>_<43 Base64URL>` shape)
- `AICOSTOPS_MIMO_API_KEY` (synthetic, local-only)
- `MYSQL_GATEWAY_USER` / `MYSQL_GATEWAY_PASSWORD` (Gateway DB identity)

All values written to `.env.m21.local` (gitignored). Never committed.

## Gateway DB Least Privilege

### Script

`scripts/m21/provision-gateway-db.ps1`

### Privilege Contract

Frost M16 contract + V3 runtime reads derived from Gateway mappers:

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

Creates ACTIVE `provider_connection_profile` with endpoint `http://mock-provider:8089/v1` and `auth_type=API_KEY`.

Removes unrelated CUSTOM_OPENAI_COMPATIBLE mutation (debugging observation, not root cause).

## Gateway Execution Smoke

### Script

`scripts/m21/invoke-v3-operational-smoke.ps1`

Validates:
1. Gateway health
2. HTTP 200 from `POST /v1/chat/completions`
3. Deterministic mock response ("Hello from M16 mock")
4. Mock invocation delta exactly +1
5. `route_attempt.provider_connection_profile_id` IS NOT NULL

Output: `M21_V3_GOVERNED_SMOKE_PASS`

## Defects

### P1 Security Defect (M21 Discovery)

**M21-SEC-001** — FIXED
```text
Title:    MiMo and OpenAI adapters lacked PublicOnlyAddressResolverGroup in production
Impact:   Violates frozen M18 DNS-rebinding / SSRF contract
Fix:      Conditional resolver wiring: prod profile → PublicOnlyAddressResolverGroup
Tests:    MimoChatAdapterResolverTest, OpenAiChatAdapterResolverTest
Severity: P1 Security
```

### P2 Documentation Defects

All previous P2 defects (DOC-004 through DOC-007) addressed.

## Files Changed

```text
NEW:  scripts/m21/new-v3-operational-env.ps1 (CSPRNG environment bootstrap)
NEW:  scripts/m21/verify-v3-gateway-privileges.ps1 (complete privilege matrix)
NEW:  scripts/m21/bootstrap-v3-operational.ps1 (convenience wrapper)
FIX:  scripts/m21/seed-v3-operational.ps1 (self-contained, no hardcoded values, no CUSTOM_OPENAI_COMPATIBLE mutation)
FIX:  scripts/m21/provision-gateway-db.ps1 (container-side password handling)
FIX:  scripts/m21/invoke-v3-operational-smoke.ps1 (lineage verification)
FIX:  gateway/src/main/java/.../MimoChatAdapter.java (prod DNS rebinding fix)
FIX:  gateway/src/main/java/.../OpenAiChatAdapter.java (prod DNS rebinding fix)
NEW:  gateway/src/test/java/.../MimoChatAdapterResolverTest.java (regression test)
NEW:  gateway/src/test/java/.../OpenAiChatAdapterResolverTest.java (regression test)
FIX:  docs/04-operations/v3-operational-runbook.md (self-contained PowerShell flow)
```

## Candidate Decision

```text
Bootstrap self-contained (CSPRNG) = PASS
Gateway raw key generated reproducibly = PASS
Synthetic Provider credential generated reproducibly = PASS
Gateway DB identity separate = PASS
Complete positive/negative privilege matrix = PASS
All V3 runtime SELECTs granted = PASS
Synthetic V24+ seed = PASS
ACTIVE connection profile = PASS
Gateway request reaches controlled mock = PASS
Mock delta exactly +1 = PASS
route_attempt.profile_id NOT NULL = PASS
MIMO SSRF/security contract reconciled = PASS
Production code fix with TDD regression tests = PASS
Documentation self-contained = PASS

P0: 0
P1: 0 (1 fixed)

M21_OPERATIONAL_READY_CANDIDATE
```

Awaiting: GPT-5.6 Sol final review.