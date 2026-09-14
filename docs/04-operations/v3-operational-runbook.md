# V3 Operational Runbook

AI-CostOps v3.0.0 post-release operational reference.

## Prerequisites

| Dependency | Minimum Version | Notes |
|---|---|---|
| Git | 2.x | Clone and checkout |
| Docker | 24+ | Container runtime |
| Docker Compose | v2.x (compose v2) | `docker compose` subcommand |

Optional (daily development only, not needed for Compose Quick Start):

| Dependency | Version | Notes |
|---|---|---|
| Java | 21+ | Backend native runtime |
| Node.js | 20+ | Frontend native runtime |
| Maven | via `mvnw` wrapper | Do not install globally |

Required ports (defaults):

| Service | Port | Notes |
|---|---|---|
| Frontend (Nginx) | 8080 | Configurable via `FRONTEND_PORT` |
| MySQL | 3306 (internal) | Not host-exposed in Compose |
| Redis | 6379 (internal) | Not host-exposed in Compose |
| MinIO API | 9000 (internal) | Not host-exposed in Compose |
| MinIO Console | 9001 (internal) | Not host-exposed in Compose |

## First Start

```bash
git clone https://github.com/BangShou1st/AI-CostOps.git
cd AI-CostOps
git checkout v3.0.0
cp .env.example .env
docker compose --env-file .env build
docker compose --env-file .env up -d
```

All five services (backend, frontend, mysql, redis, minio) will start. Database migrations (V1-V27) run automatically on first backend startup.

## Default Login (Development Only)

When `AICOSTOPS_DEV_BOOTSTRAP_ENABLED=true` (default in `.env.example`), a dev admin account is created automatically:

```text
Email:    admin@example.test
Password: change-me-local-only
```

Open http://localhost:8080 and log in with these credentials.

**Production**: Set `AICOSTOPS_DEV_BOOTSTRAP_ENABLED=false` and use real identity.

## Environment Variables

Copy `.env.example` to `.env` and customize. Key variables:

| Variable | Default | Purpose |
|---|---|---|
| `MYSQL_DATABASE` | `aicostops` | Database name |
| `MYSQL_USER` | `aicostops` | Database user |
| `MYSQL_PASSWORD` | `change-me-local-only` | Database password |
| `MYSQL_ROOT_PASSWORD` | `change-me-local-root-only` | Root password |
| `REDIS_PASSWORD` | `change-me-local-only` | Redis auth |
| `MINIO_ROOT_USER` | `aicostops` | MinIO admin user |
| `MINIO_ROOT_PASSWORD` | `change-me-local-only` | MinIO admin password |
| `AICOSTOPS_JWT_SIGNING_KEY` | (dev placeholder) | JWT signing key |
| `AICOSTOPS_DEV_BOOTSTRAP_ENABLED` | `true` | Creates dev admin account |
| `AICOSTOPS_DEV_BOOTSTRAP_EMAIL` | `admin@example.test` | Dev admin email |
| `AICOSTOPS_DEV_BOOTSTRAP_PASSWORD` | `change-me-local-only` | Dev admin password |
| `FRONTEND_PORT` | `8080` | Host port for frontend |

## Service URLs

| Service | URL | Notes |
|---|---|---|
| Application | http://localhost:8080 | Frontend + API proxy |
| Login | http://localhost:8080/login | Dev: `admin@example.test` |

## Health Checks

```bash
# Backend
docker compose exec backend curl -fsS http://localhost:8080/actuator/health/liveness

# MySQL
docker compose exec mysql mysqladmin ping -h localhost -u root -p$MYSQL_ROOT_PASSWORD

# Redis
docker compose exec redis redis-cli -a $REDIS_PASSWORD ping

# MinIO
docker compose exec minio curl -fsS http://localhost:9000/minio/health/live

# Frontend
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/
```

## Stop / Start

```bash
# Stop (preserves data)
docker compose --env-file .env down

# Start again
docker compose --env-file .env up -d
```

## Full Reset (destroys data)

```bash
docker compose --env-file .env down -v
docker compose --env-file .env up -d --build
```

## Backup / Restore

See `docs/02-development/operations/03-backup-restore.md`.

Key points:
- MySQL is the single source of financial truth (Ledger, Budget, Period, Attribution)
- Redis is NOT financial truth; it caches runtime state only
- MinIO stores evidence files
- Backup scripts: `scripts/ops/backup-mysql.ps1`, `scripts/ops/backup-evidence.ps1`

## Logs

```bash
# All services
docker compose --env-file .env logs

# Specific service
docker compose --env-file .env logs backend

# Follow live
docker compose --env-file .env logs -f backend
```

## Troubleshooting

### Port already in use

Change `FRONTEND_PORT` in `.env`:

```bash
FRONTEND_PORT=18080
```

### MySQL unavailable

Backend will fail-fast. Check MySQL health:

```bash
docker compose exec mysql mysqladmin ping -u root -p$MYSQL_ROOT_PASSWORD
```

### Migration failures

If schema is corrupt, destroy and re-migrate:

```bash
docker compose --env-file .env down -v
docker compose --env-file .env up -d
```

## V3 Features

### Model Provider Hub
- **Gallery**: `/settings/providers` - Connection templates (OpenCode Zen, Custom OpenAI-Compatible)
- **Connections**: `/settings/provider-connections` - Versioned connection profiles
- **Models**: `/settings/provider-models` - Model discovery and promotion
- **Pricing**: `/settings/model-pricing` - Pricing version management
- **Routing**: `/settings/routing-policies` - Multi-provider routing strategies

### Cost Intelligence
- **Overview**: `/cost-intelligence/overview` - Four key judgments dashboard
- **Anomalies**: `/intelligence/anomalies` - Deterministic anomaly detection
- **Forecasts**: `/intelligence/forecasts` - DAMPED_HOLT time series
- **Savings**: `/intelligence/savings` - Counterfactual savings recommendations

### AI Advisor
- **Advisor**: `/advisor` - Governed explanation of financial facts
- Requires Gateway execution configuration for real provider calls

## Upgrade Notes (V2 to V3)

V3 is a major release:
- New database migrations (V23-V27) add Provider Hub, Cost Intelligence, AI Advisor tables
- New environment variables for Gateway (HMAC keys, KEK, rate limiting)
- Gateway is now a separate deployable (Spring WebFlux data plane)
- Frontend includes new V3 pages

**Rollback**: V3 does not support schema downgrade. Restore from backup taken before V3 migration.

## Security Reminders

- Never commit `.env` with real secrets
- Change `AICOSTOPS_JWT_SIGNING_KEY` before production
- Set `AICOSTOPS_ALLOW_PUBLIC_REGISTRATION=false` in production
- Enable `AICOSTOPS_REFRESH_COOKIE_SECURE=true` in production
- Disable `AICOSTOPS_DEV_BOOTSTRAP_ENABLED` in production
