# V3 Operational Runbook

AI-CostOps V3 post-release operational reference. This runbook documents the maintenance-mode operational validation used on the current working branch `dev`. It does **not** describe a production deployment topology; production and data-migration checks are conditionally triggered only when explicitly needed (see "Release & Data-Migration Boundary" below).

## Prerequisites

| Dependency | Minimum Version | Notes |
|---|---|---|
| Git | 2.x | Clone and checkout |
| Docker | 24+ | Container runtime |
| Docker Compose | v2.x (compose v2) | `docker compose` subcommand |
| PowerShell | 7+ | Recommended shell |

---

## Quick Start: Self-Contained Operational Validation

### Step 1: Clone and Checkout

```powershell
git clone https://github.com/BangShou1st/AI-CostOps.git
cd AI-CostOps
git checkout dev
```

`dev` is the current working branch and where the M21 operational scripts live. The old `chore/m21-v3-operational-readiness` milestone branch has been deleted; do not recreate it.

### Step 2: Generate Runtime Environment (CSPRNG)

All secrets are generated locally using .NET `RandomNumberGenerator`. No hidden values, no old `.env`.

```powershell
pwsh -File scripts/m21/new-v3-operational-env.ps1
```

This creates `.env.m21.local` (gitignored) with:
- `AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1` (32-byte CSPRNG, Base64)
- `AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1` (32-byte CSPRNG, Base64)
- `AICOSTOPS_PROVIDER_KEK_V1` (32-byte CSPRNG, Base64)
- `AICOSTOPS_GATEWAY_DEV_RAW_KEY` (valid `aic_<12>_<43>` shape)
- `AICOSTOPS_MIMO_API_KEY` (synthetic, local-only)
- `MYSQL_GATEWAY_USER` / `MYSQL_GATEWAY_PASSWORD` (Gateway DB identity)

**Never commit `.env.m21.local`. Secrets are never printed to console.**

### Step 3: Start Full V3 Topology

```powershell
docker compose -f compose.yaml -f compose.v3-operational.yaml `
  -p aicostops-m21-final-r3 --env-file .env.m21.local up -d --build
```

Services: backend, frontend, mysql, redis, minio, gateway, mock-provider.

This is an operational validation topology, **not** a production deployment: it runs the development bootstrap, the deterministic zero-cost `mock-provider` (Python), and locally generated synthetic secrets. Use full Compose only for explicit E2E / Smoke / operational validation — never as the routine daily edit-test loop.

> `aicostops-m21-final-r3` is the scripts' default local Compose project label. Any other label works as long as the same `-ComposeProject` value is passed consistently to the later scripts.

### Step 4: Provision Gateway DB Identity

```powershell
.\scripts\m21\provision-gateway-db.ps1 -ComposeProject aicostops-m21-final-r3
```

Creates the least-privilege `gw_m21` user. The script self-imports `.env.m21.local`; no manual environment setup is required.

Grants include the frozen contract plus the MySQL 8.4 / Advisor Worker additions: database-level `LOCK TABLES` (required for locking reads on MySQL 8.4) and the Advisor Worker table grants (`advisor_inference_job`, `advisor_inference_attempt`, `advisor_explanation`, `advisor_profile`, `advisor_evidence_snapshot`). It never grants `ALL PRIVILEGES`, `GRANT OPTION`, `DELETE`, `DROP`, DDL, or `UPDATE`/`DELETE` on `budget` / `billing_period` / ledger tables.

Expected: `M21_GATEWAY_DB_PROVISION_PASS`

### Step 5: Verify Gateway DB Privileges

```powershell
.\scripts\m21\verify-v3-gateway-privileges.ps1 -ComposeProject aicostops-m21-final-r3
```

The script self-imports `.env.m21.local`; no manual `$env:MYSQL_GATEWAY_*` assignment is needed. It runs the full positive and negative privilege matrix as the real `gw_m21` account (not root): runtime SELECTs, `SELECT ... FOR UPDATE`, `LOCK TABLES` presence, Advisor Worker grants, and Gateway-owned writes, plus negative assertions that must all be denied (`budget` UPDATE, `billing_period` close, ledger INSERT, `provider_credential` UPDATE, DELETE, DDL, GRANT).

Expected: `M21_GATEWAY_PRIVILEGE_GREEN`

### Step 6: Seed Synthetic V24+ Route

```powershell
.\scripts\m21\seed-v3-operational.ps1 -ComposeProject aicostops-m21-final-r3
```

Creates the ACTIVE `provider_connection_profile` with endpoint `http://mock-provider:8089/v1`.

Expected: `M21_SEED_PASS`

### Step 7: Run Governed Execution Smoke

```powershell
.\scripts\m21\invoke-v3-operational-smoke.ps1 -ComposeProject aicostops-m21-final-r3
```

Expected: `M21_V3_GOVERNED_SMOKE_PASS`

Verifies:
- Gateway health PASS
- HTTP 200 from `POST /v1/chat/completions`
- Deterministic mock response
- Mock invocation delta exactly +1
- `route_attempt.provider_connection_profile_id` EXACT MATCH to the seeded profile

### Step 8: Run Financial Closed-Loop Smoke

```powershell
.\scripts\m21\invoke-v3-operational-financial-smoke.ps1 -ComposeProject aicostops-m21-final-r3
```

Expected: `M21_FINANCIAL_SMOKE_PASS`

Continues past the execution smoke and waits for the Backend `GatewaySettlementWorker` to settle on the same MySQL that is the financial source of truth. Asserts:
- normal: 1 request → 1 FINAL usage fact (with pricing version) → 1 SETTLED settlement → 1 `GATEWAY_SETTLEMENT` ledger posting + 1 COST entry → Budget Actual increased by the posted amount → reservation FINALIZED
- duplicate (identical Idempotency-Key replay): HTTP 409, Provider not re-executed, no second financial mutation
- failure (mock injects HTTP 500): request is non-200 and is NOT booked as zero cost (no ledger posting, no SETTLED settlement, Budget Actual unchanged)

### Step 9: Run Advisor Worker Smoke

```powershell
.\scripts\m21\invoke-v3-operational-advisor-smoke.ps1 -ComposeProject aicostops-m21-final-r3
```

Expected: `M21_ADVISOR_WORKER_SMOKE_PASS`

Runs the real `AdvisorInferenceWorker` through the `gw_m21` DB account against the mock Provider: a success path (Job/Attempt COMPLETED, Explanation written, exactly one mock call) and an upstream HTTP 500 path (Job/Attempt FAILED, safe error code, no Explanation). No real billable Provider is called.

---

## Security Notes

### Production Code Fix (M21 Post-Release)

The MiMo and OpenAI adapters were found to lack transport-level DNS rebinding defense in production profile. Fixed in M21:

- `MimoChatAdapter`: now uses `PublicOnlyAddressResolverGroup` when `prod` profile is active
- `OpenAiChatAdapter`: now uses `PublicOnlyAddressResolverGroup` when `prod` profile is active

Non-production profiles (dev/test) use the default JVM resolver to allow local mock validation.

This preserves the frozen M18 `DIRECT_PUBLIC_ONLY` SSRF contract.

### Secret Safety

- Gateway raw key is never printed to console or logs
- Synthetic MiMo key is local-only, never a real Provider credential
- DB passwords are passed via container-side environment, never in command lines
- No secrets are committed to Git

---

## Running Modes

### A. Daily Development (Default)

Docker runs infrastructure only. Application processes run natively. This is the default mode for day-to-day development: `docker compose up` builds/runs nothing here beyond infrastructure, and the three applications run locally.

```text
Docker:   MySQL / Redis / MinIO
Native:   Backend / Gateway / Frontend
```

```powershell
npm run infra:up   # MySQL / Redis / MinIO only
npm run dev        # Backend + Gateway + Frontend, native, local profile
```

### B. Full V3 Operational Validation (Smoke / E2E / ops only)

Complete V3 topology including Gateway + mock Provider (`compose.yaml` + `compose.v3-operational.yaml`).

Use this only when explicitly required for E2E / Smoke / operational validation. It is an operational acceptance topology, not a production deployment topology, and not the routine daily edit-test loop.

---

## Restart Validation

After first smoke, restart full topology:

```powershell
docker compose -f compose.yaml -f compose.v3-operational.yaml `
  -p aicostops-m21-final-r3 --env-file .env.m21.local restart
```

Re-run the execution, financial, and Advisor smokes. Must preserve:
- Flyway schema history up to the current head (V32 on `dev`; earlier records referring to the "V27 migration" reflected the migration head at that time)
- Routing state
- Profile/credential
- Gateway DB user/grants

---

## Persistence Validation

```powershell
docker compose -f compose.yaml -f compose.v3-operational.yaml `
  -p aicostops-m21-final-r3 --env-file .env.m21.local down

docker compose -f compose.yaml -f compose.v3-operational.yaml `
  -p aicostops-m21-final-r3 --env-file .env.m21.local up -d --build
```

Re-provision Gateway DB and re-smoke. All data must persist.

---

## Release & Data-Migration Boundary (Conditional)

This project is in long-term maintenance mode. There is no active need to build a production environment, and no need to force a production upgrade merely to "finish" the project. The full Compose topology above is an operational validation harness, **not** a production-deployable configuration, and the M21 least-privilege scripts describe test infrastructure only.

The following are **conditionally triggered** checks. Perform them only if you actually decide to upgrade a real persistent database or to update the stable release — do not execute them as part of routine maintenance:

- Reconcile the target database's Flyway history against this repository's migration head before upgrading.
- Confirm the backup and evidence-storage (MinIO / S3) restore capability for the target environment.
- Review migrations `V28`–`V32`, and specifically the `V30` / `V31` OpenCode Zen ACTIVE profile endpoint changes, which performed a versioned in-place change to existing ACTIVE `provider_connection_profile` rows and carry a historical-endpoint traceability risk.
- Verify the production security configuration and least privilege; do not reuse the development bootstrap, `mock-provider`, or locally generated synthetic secrets / passwords.
- Perform the upgrade and restore drill in an isolated environment before touching any real environment.
- Only after human review may a decision be made to update `main` / the stable release.

Current boundary notes:

- `dev` is ahead of the stable `main` by a large number of maintenance commits, including Flyway migrations `V28`–`V32`.
- The existing M21 Compose configuration is NOT a directly production-deployable configuration.
- The older M9 backup/restore drill does not substitute for an up-to-date persistent-database upgrade/restore acceptance on the current version.

This runbook records these rules only; it does not perform any upgrade, migration, backup, or production deployment.

---

## Default Login (Development Only)

When `AICOSTOPS_DEV_BOOTSTRAP_ENABLED=true`, the development bootstrap creates a synthetic administrator. This applies to local / validation environments only and must never be used in production.

```text
Email:    admin@example.test
Password: change-me-local-only
```

---

## V3 Features

### Model Provider Hub
- **Gallery**: `/settings/providers` - Connection templates
- **Connections**: `/settings/provider-connections` - Versioned profiles
- **Models**: `/settings/provider-models` - Model discovery
- **Pricing**: `/settings/model-pricing` - Pricing versions
- **Routing**: `/settings/routing-policies` - Multi-provider routing

### Cost Intelligence
- **Overview**: `/cost-intelligence/overview`
- **Anomalies**: `/intelligence/anomalies`
- **Forecasts**: `/intelligence/forecasts`
- **Savings**: `/intelligence/savings`

### AI Advisor
- **Advisor**: `/advisor` - Governed financial explanation

---

## Security Reminders

The validation flow above uses development-only settings. None of the following may be carried into production:

- Never commit `.env` with real secrets; never commit `.env.m21.local`
- Change `AICOSTOPS_JWT_SIGNING_KEY` before production
- Set `AICOSTOPS_ALLOW_PUBLIC_REGISTRATION=false` in production
- Disable `AICOSTOPS_DEV_BOOTSTRAP_ENABLED` in production and use a real identity
- Do not reuse the development bootstrap administrator, the deterministic `mock-provider`, or the locally generated synthetic HMAC / KEK keys and DB passwords
- Gateway HMAC/KEK keys must be unique per environment
- Gateway keys must never be printed or logged
- Gateway uses least-privilege DB identity (`MYSQL_GATEWAY_USER`, not `MYSQL_USER`)