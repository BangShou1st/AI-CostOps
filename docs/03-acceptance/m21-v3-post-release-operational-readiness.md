# M21 — V3 Post-Release Operational Readiness

## Baseline

```text
Release:      v3.0.0
Commit:       9c55125c1b857e3ccf301875d8886131a9d1d9b0
Branch:       chore/m21-v3-operational-readiness
Issue:        #161
Date:         2026-09-15
Final HEAD:   f6606c8 (fix(m21): Gateway execution smoke)
```

## Release Closure

```text
Tag:              v3.0.0 PASS
Release:          Published PASS
Draft:            false PASS
Prerelease:       false PASS
Release Commit:   9c55125c1b857e3ccf301875d8886131a9d1d9b0 PASS
Release Notes:    Highlights PASS, Production Acceptance PASS, Deferred Acceptance PASS

Deferred:
  Literal poison proxy 127.0.0.1:7897 = DEFERRED
  Real Provider/OpenCode certification = DEFERRED
  Org Isolation Browser = DEFERRED
```

## Clean Clone

```text
Clone URL:        https://github.com/BangShou1st/AI-CostOps.git
Checkout:         v3.0.0 tag
HEAD:             9c55125c1b857e3ccf301875d8886131a9d1d9b0 PASS
```

## Basic Compose (5 services, no Gateway)

```text
Command:     docker compose --env-file .env up -d
Services:    backend, frontend, mysql, redis, minio
Gateway:     NOT included
Result:      PASS
```

## Full V3 Operational Topology (7 services)

```text
Command:     docker compose -f compose.yaml -f compose.v3-operational.yaml \
               -p aicostops-m21-full --env-file .env up -d --build
Services:    backend, frontend, mysql, redis, minio, gateway, mock-provider
Gateway:     INCLUDED
Result:      PASS
```

### Service Health (Full V3)

```text
Backend:     {"status":"UP"} PASS
Gateway:     {"status":"UP"} PASS
MySQL:       mysqld is alive PASS
Redis:       PONG PASS
MinIO:       healthy PASS
Mock:        {"status":"UP"} PASS
Frontend:    HTTP 200 PASS
```

### Gateway Execution Plane

```text
Gateway health:       /actuator/health/liveness = UP PASS
Mock Provider:        /health = UP PASS
Mock Chat Completions: /v1/chat/completions = deterministic response PASS

Real Provider:        DEFERRED
```

## Migration

```text
Flyway validated:  27 migrations PASS
Flyway applied:    27 migrations (V1-V27) PASS
V23:               M15 Hybrid Reconciliation (V2 history)
V24-V27:           V3 feature migrations (Provider Hub, Cost Intelligence, AI Advisor)
Final version:     v27 PASS
```

## First Login

```text
URL:          http://localhost:8080
Credentials:  admin@example.test / change-me-local-only
Bootstrap:    Automatic PASS
Result:       PASS
```

## Browser Control Plane Smoke

```text
Page                        URL                                    Status
--------------------------  -------------------------------------  ------
Dashboard                   /                                      PASS
Cost Intelligence Overview  /cost-intelligence/overview            PASS
Anomalies                   /intelligence/anomalies                PASS
Forecasts                   /intelligence/forecasts                PASS
Savings                     /intelligence/savings                  PASS
AI Advisor                  /advisor                               PASS
Provider Gallery            /settings/providers                    PASS
Connections                 /settings/provider-connections         PASS
Models                      /settings/provider-models              PASS
Pricing                     /settings/model-pricing                PASS
Routing                     /settings/routing-policies             PASS

White screens:     0
Console crashes:   0
Unexpected 5xx:    0
```

## Gateway Execution Plane Smoke

### Health

```text
Gateway health:       PASS ({"status":"UP"})
Mock Provider health: PASS ({"status":"UP"})
```

### Execution Smoke

```text
Request:    POST /v1/chat/completions
Model:      default-chat
Auth:       Bearer <gateway-dev-raw-key>
Idempotency: <unique>

Result:     HTTP 200 PASS
Response:   {"choices":[{"message":{"content":"Hello from M16 mock"}}],
             "usage":{"prompt_tokens":5,"completion_tokens":3,"total_tokens":8}}
Mock stats: post_chat_completions = 1 (delta +1) PASS

Full execution topology verified:
  client -> Gateway -> mock-provider -> deterministic response -> client
```

### Root Causes Found and Fixed

Three data-layer issues prevented Gateway dispatch (all fixed):

**M21-GW-001** - FIXED
```text
Title:    provider_catalog CUSTOM_OPENAI_COMPATIBLE status was DISABLED
Impact:   CandidateEligibilityEvaluator rejected adapter lookup
Fix:      UPDATE provider_catalog SET status='ACTIVE' WHERE provider_code='CUSTOM_OPENAI_COMPATIBLE'
```

**M21-GW-002** - FIXED
```text
Title:    provider_connection_profile.auth_type='NONE' skipped credential lookup
Impact:   buildProviderContext set credentialType='NONE', MIMO adapter requires 'API_KEY'
Root:     Line 595: if ("NONE".equals(profile.authType())) credentialType = "NONE"
Fix:      UPDATE provider_connection_profile SET auth_type='BEARER' WHERE id=1
Note:     auth_type must be BEARER/API_KEY_HEADER to trigger provider_credential decryption
```

**M21-GW-003** - FIXED
```text
Title:    provider_credential missing for MIMO provider account
Impact:   No decryptable credential available for MIMO adapter
Fix:      DevGatewayBootstrap creates credential when AICOSTOPS_MIMO_API_KEY is set
Verified: provider_credential row exists with credential_type='API_KEY', status='ACTIVE'
```

### Key Architectural Insight

The MIMO adapter (`MimoChatAdapter`) does NOT use `PublicOnlyAddressResolverGroup`,
making it the only adapter that can reach Docker-internal mock providers. The
`GenericOpenAiCompatibleChatAdapter` uses `PublicOnlyAddressResolverGroup` which
blocks all private/link-local IPs — this is by design (DNS rebinding defense) and
cannot be bypassed in non-prod profiles.

## Persistence

### Restart Test
```text
All services: Healthy after restart PASS
Data intact:  Migration version still v27 PASS
```

### Down/Up Test
```text
All services: Healthy after up PASS
Data intact:  Migration version still v27 PASS
```

## Backup / Restore

```text
Documentation audit:    PASS
Docs location:          docs/02-development/operations/03-backup-restore.md
Destructive drill:      NOT EXECUTED / OUT OF M21 SCOPE

Key points verified:
  - MySQL = financial truth (Ledger, Budget, Period)
  - Redis != financial truth
  - MinIO = evidence storage
```

## Upgrade

```text
V2 -> V3 guidance:      PASS (in runbook)
Migration mapping:      V23=M15, V24-V27=V3 features
Schema downgrade:       NOT SUPPORTED (documented)
Backup before upgrade:  DOCUMENTED
```

## Observability

```text
Prometheus overlay:     compose.observability.yaml EXISTS (not started in M21)
Grafana dashboard:      aicostops-overview.json EXISTS
Health endpoints:       /actuator/health/liveness PASS

Note: Observability stack documented but not started during M21 validation.
```

## Troubleshooting

```text
Port collision:         DOCUMENTED (FRONTEND_PORT override)
MySQL unavailable:      DOCUMENTED (fail-fast behavior)
Missing env:            DOCUMENTED (clear error messages)
Migration failure:      DOCUMENTED (safe vs destructive guidance)
```

## Automated Regression

```text
Backend unit:           PASS (f6606c8)
Backend architecture:   PASS
Backend integration:    PASS
Gateway unit:           PASS
Gateway architecture:   PASS
Gateway integration:    PASS
Frontend lint:          PASS
Frontend build:         PASS
Frontend test:          PASS
Docker build:           PASS
Browser E2E:            PASS
Security:               PASS
```

## Hosted Exact-Head

```text
Previous HEAD (46f91ed):  15/15 PASS
Final HEAD (f6606c8):     15/15 PASS

CI Jobs (11/11 PASS):
  backend-unit, backend-architecture, backend-integration,
  gateway-unit, gateway-architecture, gateway-integration,
  frontend-lint, frontend-build, frontend-test,
  docker-build, browser-e2e

Security: 1/1 PASS
```

## Defects

### P1 Defects

**M21-OPS-001** - FIXED
```text
Title:    Root compose does not include Gateway; previous acceptance overclaimed full V3 topology
Fixed:    Added compose.v3-operational.yaml with Gateway + Mock Provider
Verified: Full V3 topology (7 services) starts and passes health checks
```

**M21-DOC-003** - FIXED
```text
Title:    Migration troubleshooting suggested destructive down -v without warning
Fixed:    Added DESTRUCTIVE/LOCAL/DISPOSABLE warnings, safe troubleshooting steps
```

### P2 Defects

**M21-DOC-004** - FIXED
```text
Title:    Health commands used host-side env expansion
Fixed:    Changed to container-side expansion (sh -lc '...')
```

**M21-DOC-005** - FIXED
```text
Title:    README release ledger omitted v2.0.0
Fixed:    Added v2.0.0 -> 7e10e6e609d186f40faecd300d159e2c49ee5fc7
```

**M21-DOC-006** - FIXED
```text
Title:    V23 incorrectly classified as V3 migration
Fixed:    V23=M15 (V2 history), V24-V27=V3 features
```

**M21-DOC-007** - FIXED
```text
Title:    Acceptance evidence missing final sections
Fixed:    Added Backup/Restore, Upgrade, Observability, Troubleshooting, Deferred
```

### P0/P1 Summary

```text
P0: 0
P1: 0 (2 fixed)
P2: 4 (all fixed)
```

## Files Changed

```text
NEW:  compose.v3-operational.yaml (Gateway + Mock Provider overlay, health check fix)
NEW:  docs/04-operations/v3-operational-runbook.md (complete operational reference)
NEW:  docs/03-acceptance/m21-v3-post-release-operational-readiness.md
NEW:  scripts/m21/seed-v3-operational.ps1 (V24+ data gap fixes)
NEW:  scripts/m21/invoke-v3-operational-smoke.ps1 (Gateway execution smoke)
NEW:  scripts/m21/provision-gateway-db.ps1 (gw_m21 least-privilege)
FIX:  README.md (V3 version, v2.0.0 ledger, milestones, login credentials)
```

## Candidate Decision

```text
Basic Compose truth documented:          PASS
Full V3 Operational Topology documented: PASS
Gateway actually starts:                 PASS
Gateway health:                          PASS
Gateway DB least-privilege (gw_m21):     PASS
V24+ synthetic seed reproducible:        PASS
Controlled execution smoke:              PASS (request -> Gateway -> mock-provider -> 200)
Mock provider invocation count +1:       PASS
Destructive reset guidance fixed:        PASS
Health commands verified:                PASS
v2.0.0 release ledger restored:          PASS
V24-V27 wording corrected:               PASS
Restart smoke:                           PASS
Formal M21 evidence complete:            PASS

P0: 0
P1: 0

M21_OPERATIONAL_READY_CANDIDATE
```

Awaiting: GPT-5.6 Sol review.
