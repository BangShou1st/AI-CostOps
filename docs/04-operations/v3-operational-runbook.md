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
| Gateway | 8081 | Only in Full V3 Topology |
| MySQL | 3306 (internal) | Not host-exposed in Compose |
| Redis | 6379 (internal) | Not host-exposed in Compose |
| MinIO API | 9000 (internal) | Not host-exposed in Compose |
| MinIO Console | 9001 (internal) | Not host-exposed in Compose |
| Mock Provider | 8089 | Only in Full V3 Topology |

---

## Running Modes

### A. Daily Development (Default)

Docker runs infrastructure only (MySQL / Redis / MinIO). Application processes run natively.

```text
Docker:
  MySQL / Redis / MinIO

Native:
  Backend / Gateway / Frontend
```

**Does NOT include Gateway in Compose.** Gateway runs as native process.

See: `docs/02-development/implementation/05-bootstrap-local-development-runbook.md`

### B. Basic UI / Control-Plane Compose

Root `compose.yaml` starts 5 services: backend, frontend, mysql, redis, minio.

```text
docker compose --env-file .env up -d
```

**Does NOT include Gateway.** Not complete V3 execution topology.

Use for: UI development, control-plane testing, database work.

### C. Full V3 Operational Validation (Recommended for M21)

Complete V3 topology including Gateway + deterministic mock Provider.

```text
docker compose -f compose.yaml -f compose.v3-operational.yaml \
  -p aicostops-m21-full --env-file .env up -d --build
```

Services:
- backend (control plane)
- frontend (UI)
- mysql (financial truth)
- redis (cache)
- minio (evidence storage)
- **gateway (execution data plane)**
- **mock-provider (deterministic, zero-cost test provider)**

Gateway binds to 127.0.0.1 only. Mock Provider is internal network only.

---

## First Start (Basic Compose)

```bash
git clone https://github.com/BangShou1st/AI-CostOps.git
cd AI-CostOps
git checkout v3.0.0
cp .env.example .env
docker compose --env-file .env build
docker compose --env-file .env up -d
```

Five services start. Database migrations (V1-V27) run automatically on first backend startup.

## First Start (Full V3 Topology)

```bash
git clone https://github.com/BangShou1st/AI-CostOps.git
cd AI-CostOps
git checkout v3.0.0
cp .env.example .env

# Generate Gateway keys (required for Full V3)
# Add to .env:
#   AICOSTOPS_GATEWAY_DEV_BOOTSTRAP_ENABLED=true
#   AICOSTOPS_GATEWAY_DEV_RAW_KEY=aic_<12 Crockford-Base32>_<43 Base64URL>

docker compose -f compose.yaml -f compose.v3-operational.yaml \
  -p aicostops-m21-full --env-file .env up -d --build
```

Seven services start. Gateway health check: `http://localhost:8081/actuator/health/liveness`

## Default Login (Development Only)

When `AICOSTOPS_DEV_BOOTSTRAP_ENABLED=true` (default in `.env.example`), a dev admin account is created automatically:

```text
Email:    admin@example.test
Password: change-me-local-only
```

Open http://localhost:8080 and log in with these credentials.

**Production**: Set `AICOSTOPS_DEV_BOOTSTRAP_ENABLED=false` and use real identity.

---

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
| `GATEWAY_PORT` | `8081` | Host port for gateway (Full V3 only) |
| `AICOSTOPS_GATEWAY_DEV_RAW_KEY` | (required for Full V3) | Gateway auth key |

---

## Service URLs

| Service | URL | Notes |
|---|---|---|
| Application | http://localhost:8080 | Frontend + API proxy (Basic Compose) |
| Application | http://localhost:18080 | Frontend (Full V3 with custom port) |
| Login | http://localhost:8080/login | Dev: `admin@example.test` |
| Gateway Health | http://localhost:8081/actuator/health/liveness | Full V3 only |

---

## Health Checks

### Backend (container-side)

```bash
docker compose exec backend curl -fsS http://localhost:8080/actuator/health/liveness
```

### Gateway (container-side)

```bash
docker compose -f compose.yaml -f compose.v3-operational.yaml \
  -p aicostops-m21-full exec gateway curl -fsS http://localhost:8081/actuator/health/liveness
```

### MySQL (container-side expansion)

```bash
docker compose exec mysql sh -lc 'mysqladmin ping -h localhost -u root -p"$MYSQL_ROOT_PASSWORD" --silent'
```

### Redis (container-side expansion)

```bash
docker compose exec redis sh -lc 'redis-cli -a "$REDIS_PASSWORD" ping'
```

### MinIO (container-side)

```bash
docker compose exec minio curl -fsS http://localhost:9000/minio/health/live
```

### Frontend (host-side)

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/
```

---

## Stop / Start

```bash
# Stop (preserves data)
docker compose --env-file .env down

# Start again
docker compose --env-file .env up -d
```

For Full V3 Topology:

```bash
# Stop (preserves data)
docker compose -f compose.yaml -f compose.v3-operational.yaml \
  -p aicostops-m21-full --env-file .env down

# Start again
docker compose -f compose.yaml -f compose.v3-operational.yaml \
  -p aicostops-m21-full --env-file .env up -d
```

---

## Full Reset (DESTRUCTIVE - LOCAL/DEV ONLY)

**WARNING**: This destroys ALL data in MySQL, Redis, and MinIO.

Only use on disposable local/dev environments. Never use on production or persistent data.

```bash
docker compose --env-file .env down -v
docker compose --env-file .env up -d --build
```

---

## Backup / Restore

See `docs/02-development/operations/03-backup-restore.md`.

Key points:
- MySQL is the single source of financial truth (Ledger, Budget, Period, Attribution)
- Redis is NOT financial truth; it caches runtime state only
- MinIO stores evidence files
- Backup scripts: `scripts/ops/backup-mysql.ps1`, `scripts/ops/backup-evidence.ps1`

---

## Logs

```bash
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

```bash
FRONTEND_PORT=18080
```

### MySQL unavailable

Backend will fail-fast. Check MySQL health (container-side):

```bash
docker compose exec mysql sh -lc 'mysqladmin ping -h localhost -u root -p"$MYSQL_ROOT_PASSWORD" --silent'
```

### Migration failures

**Do NOT blindly run `docker compose down -v`**. MySQL contains financial truth.

Correct approach:
1. **STOP** the backend container
2. **INSPECT** Flyway error in backend logs: `docker compose logs backend | grep -i flyway`
3. **DO NOT** run Flyway repair as normal fix
4. **DO NOT** modify historical migrations
5. **VERIFY** you have a backup: `d
