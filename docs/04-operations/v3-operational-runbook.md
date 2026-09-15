# V3 Operational Runbook

AI-CostOps v3.0.0 post-release operational reference.

## Prerequisites

| Dependency | Minimum Version | Notes |
|---|---|---|
| Git | 2.x | Clone and checkout |
| Docker | 24+ | Container runtime |
| Docker Compose | v2.x (compose v2) | `docker compose` subcommand |
| PowerShell | 7+ | Recommended shell |

Optional (daily development only):

| Dependency | Version | Notes |
|---|---|---|
| Java | 21+ | Backend native runtime |
| Node.js | 20+ | Frontend native runtime |
| Maven | via `mvnw` wrapper | Do not install globally |

---

## Running Modes

### A. Daily Development (Default)

Docker runs infrastructure only. Application processes run natively.

```text
Docker:   MySQL / Redis / MinIO
Native:   Backend / Gateway / Frontend
```

**Does NOT include Gateway in Compose.** See: `docs/02-development/implementation/05-bootstrap-local-development-runbook.md`

### B. Basic UI / Control-Plane Compose

Root `compose.yaml` starts 5 services (no Gateway).

```powershell
Copy-Item .env.example .env
docker compose --env-file .env up -d
```

**Not complete V3 execution topology.** Use for UI development only.

### C. Full V3 Operational Validation (Recommended)

Complete V3 topology including Gateway + mock Provider.

```powershell
Copy-Item .env.example .env
# Add Gateway keys to .env (see Key Generation below)

docker compose -f compose.yaml -f compose.v3-operational.yaml `
  -p aicostops-m21-final --env-file .env up -d --build

# Provision Gateway DB identity
.\scripts\m21\provision-gateway-db.ps1 -ComposeProject aicostops-m21-final

# Seed synthetic V24+ route
.\scripts\m21\seed-v3-operational.ps1 -ComposeProject aicostops-m21-final
```

---

## Key Generation

Generate Gateway cryptographic keys (add to `.env`):

```powershell
# PowerShell 7+
$credHmac = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
$requestHmac = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
$kek = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))

# Add to .env:
# AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1=$credHmac
# AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1=$requestHmac
# AICOSTOPS_PROVIDER_KEK_V1=$kek
# AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED=true
```

---

## Default Login (Development Only)

When `AICOSTOPS_DEV_BOOTSTRAP_ENABLED=true`:

```text
Email:    admin@example.test
Password: change-me-local-only
```

**Production**: Set `AICOSTOPS_DEV_BOOTSTRAP_ENABLED=false`.

---

## Health Checks (Container-Side)

### Backend

```powershell
docker compose exec backend curl -fsS http://localhost:8080/actuator/health/liveness
```

### Gateway (Full V3 only)

```powershell
docker compose -f compose.yaml -f compose.v3-operational.yaml `
  -p aicostops-m21-final exec gateway curl -fsS http://localhost:8081/actuator/health/liveness
```

### MySQL (container-side expansion)

```powershell
docker compose exec mysql sh -lc 'mysqladmin ping -h localhost -u root -p"$MYSQL_ROOT_PASSWORD" --silent'
```

### Redis (container-side expansion)

```powershell
docker compose exec redis sh -lc 'redis-cli -a "$REDIS_PASSWORD" ping'
```

### MinIO

```powershell
docker compose exec minio curl -fsS http://localhost:9000/minio/health/live
```

### Frontend

```powershell
Invoke-WebRequest -Uri http://localhost:8080/ -UseBasicParsing | Select-Object StatusCode
```

---

## Stop / Start

```powershell
# Stop (preserves data)
docker compose --env-file .env down

# Start again
docker compose --env-file .env up -d
```

For Full V3 Topology:

```powershell
docker compose -f compose.yaml -f compose.v3-operational.yaml `
  -p aicostops-m21-final --env-file .env down

docker compose -f compose.yaml -f compose.v3-operational.yaml `
  -p aicostops-m21-final --env-file .env up -d
```

---

## Full Reset (DESTRUCTIVE - LOCAL/DEV ONLY)

**WARNING**: Destroys ALL data in MySQL, Redis, and MinIO.

Only use on disposable local/dev environments. Never on production.

```powershell
docker compose --env-file .env down -v
docker compose --env-file .env up -d --build
```

---

## Backup / Restore

See `docs/02-development/operations/03-backup-restore.md`.

Key points:
- MySQL = financial truth (Ledger, Budget, Period, Attribution)
- Redis != financial truth
- MinIO = evidence storage
- Backup: `scripts/ops/backup-mysql.ps1`, `scripts/ops/backup-evidence.ps1`

---

## Logs

```powershell
# All services
docker compose --env-file .env logs

# Specific service
docker compose --env-file .env logs backend

# Follow live
docker compose --env-file .env logs -f backend
```

---

## Troubleshooting

### Port already in use

Change `FRONTEND_PORT` in `.env`:

```powershell
# In .env: FRONTEND_PORT=18080
```

### MySQL unavailable

Backend will fail-fast. Check:

```powershell
docker compose exec mysql sh -lc 'mysqladmin ping -h localhost -u root -p"$MYSQL_ROOT_PASSWORD" --silent'
```

### Migration failures

**Do NOT run `docker compose down -v` blindly.** MySQL = financial truth.

1. **STOP** backend: `docker compose stop backend`
2. **INSPECT** Flyway logs: `docker compose logs backend | Select-String -Pattern "flyway" -CaseSensitive:$false`
3. **DO NOT** use Flyway repair as normal fix
4. **DO NOT** modify historical migrations
5. **VERIFY** backup exists: `docs/02-development/operations/03-backup-restore.md`
6. **INVESTIGATE** root cause before any destructive action
7. If recovery needed, follow backup/restore runbook

**Only** on disposable local/dev with explicit data loss acceptance:

```powershell
# DESTRUCTIVE: Destroys all data
docker compose --env-file .env down -v
docker compose --env-file .env up -d
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
- **Overview**: `/cost-intelligence/overview` - Four key judgments
- **Anomalies**: `/intelligence/anomalies` - Deterministic detection
- **Forecasts**: `/intelligence/forecasts` - DAMPED_HOLT time series
- **Savings**: `/intelligence/savings` - Counterfactual recommendations

### AI Advisor
- **Advisor**: `/advisor` - Governed financial explanation
- Requires Gateway execution configuration

---

## Upgrade Notes (V2 to V3)

- V23 = M15 Hybrid Reconciliation (V2 history)
- V24-V27 = V3 feature migrations (Provider Hub, Cost Intelligence, AI Advisor)
- New env vars for Gateway (HMAC keys, KEK, rate limiting)
- Gateway is now a separate deployable (Spring WebFlux data plane)
- Frontend includes new V3 pages

**Rollback**: V3 does not support schema downgrade. Restore from backup.

---

## Gateway Execution Smoke (Full V3 Only)

Validates that the Gateway can dispatch requests through to a mock Provider.

### Prerequisites

1. Full V3 topology running (Mode C above)
2. Gateway DB provisioned (`provision-gateway-db.ps1`)
3. V24+ seed applied (`seed-v3-operational.ps1`)
4. DevGatewayBootstrap completed (creates provider_credential with `AICOSTOPS_MIMO_API_KEY`)

### Required Data State

| Table | Condition | Why |
|---|---|---|
| `provider_catalog` | `CUSTOM_OPENAI_COMPATIBLE` status = `ACTIVE` | Adapter must be enabled |
| `provider_connection_profile` | `auth_type` = `BEARER` (NOT `NONE`) | Triggers credential lookup in `buildProviderContext` |
| `provider_credential` | At least 1 ACTIVE row for the provider account | MIMO adapter requires `credentialType="API_KEY"` |
| `provider_account` | `provider_code` = `MIMO` | Maps to MIMO adapter (no `PublicOnlyAddressResolverGroup` restriction) |
| `routing_policy_candidate` | ACTIVE row linking policy → provider_account → provider_model | Candidate must exist for routing resolution |

### Key Insight: Why `auth_type` Must NOT Be `NONE`

The `ChatCompletionController.buildProviderContext()` method (line 595) checks:
```java
if (profile != null && "NONE".equals(profile.authType())) {
    credentialType = "NONE";  // Skips credential lookup entirely
    secret = null;
}
```

When `auth_type='NONE'`, the Gateway never reads `provider_credential`. The MIMO adapter
then rejects because it requires `credentialType="API_KEY"`. Setting `auth_type='BEARER'`
forces the credential lookup path.

### Run the Smoke

```powershell
# From the Gateway container, get the dev raw key:
$gwKey = docker exec aicostops-m21-final-gateway-1 printenv AICOSTOPS_GATEWAY_DEV_RAW_KEY

# Send the smoke request:
Invoke-RestMethod -Uri "http://127.0.0.1:8081/v1/chat/completions" `
  -Method POST `
  -Headers @{
    "Authorization" = "Bearer $gwKey"
    "Idempotency-Key" = "m21-smoke-$(Get-Random)"
    "Content-Type" = "application/json"
  } `
  -Body '{"model":"default-chat","messages":[{"role":"user","content":"Hello M21"}],"stream":false}'
```

### Expected Result

HTTP 200 with:
```json
{
  "choices": [{"message": {"content": "Hello from M16 mock"}}],
  "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8}
}
```

Verify mock provider received the request:
```powershell
docker exec aicostops-m21-final-mock-provider-1 python -c "import urllib.request; print(urllib.request.urlopen('http://localhost:8089/stats').read().decode())"
# Expected: {"post_chat_completions": 1, "mode": "ok", "held": 0}
```

---

## Security Reminders

- Never commit `.env` with real secrets
- Change `AICOSTOPS_JWT_SIGNING_KEY` before production
- Set `AICOSTOPS_ALLOW_PUBLIC_REGISTRATION=false` in production
- Enable `AICOSTOPS_REFRESH_COOKIE_SECURE=true` in production
- Disable `AICOSTOPS_DEV_BOOTSTRAP_ENABLED` in production
- Gateway HMAC/KEK keys must be unique per environment
- Gateway keys must never be printed or logged
- Gateway uses least-privilege DB identity (MYSQL_GATEWAY_USER, not MYSQL_USER)
