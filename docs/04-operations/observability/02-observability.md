# Observability (AIC-077)

Optional Prometheus + Grafana stack layered on top of the core AI-CostOps
Compose deployment. It is **opt-in**: the core stack (`compose.yaml`) remains
fully usable on its own, and this overlay additionally enables `prometheus`
scraping. (The production profile also exposes `prometheus` by design, behind
the private-backend deployment boundary; it is not gated on this overlay.)

## What is added

| Component | Image | Host port | Purpose |
|---|---|---|---|
| Prometheus | `prom/prometheus:v2.54.1` | 9090 | scrape `/actuator/prometheus`, evaluate alert rules |
| Grafana | `grafana/grafana:11.5.2` | 3000 | dashboards over the Prometheus datasource |

Config lives under `deploy/observability/`:

- `prometheus/prometheus.yml` — scrape job `aicostops-backend` (15s interval).
- `prometheus/alerts.yml` — operational alert rules.
- `grafana/provisioning/datasources/` — Prometheus datasource (`uid: prometheus`).
- `grafana/provisioning/dashboards/` — dashboard provider.
- `grafana/dashboards/aicostops-overview.json` — operational overview dashboard.

## Starting the overlay

```powershell
# from the repository root, with .env present
docker compose -f compose.yaml -f compose.observability.yaml up -d
```

Then open:

- Prometheus: http://localhost:9090
- Grafana: http://localhost:3000 (admin / admin — local only)

## Prometheus exposure boundary

The default/core Compose configuration exposes only `health,info`. This
observability overlay additionally enables `prometheus` by appending to the
backend environment (key-by-key merge, base env preserved):

```yaml
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health,info,prometheus
```

The production profile (`application-prod.yml`) also enables `prometheus` by
design; production safety does **not** depend on the overlay being absent. It
depends on the documented deployment boundary:

- the backend is private and is not directly Internet-exposed;
- the frontend proxy / ingress does not route `/actuator/*`.

In all cases `env`, `configprops`, `beans`, `heapdump`, etc. are never exposed.
This is preferred over adding a new Spring profile or editing the default
`application.yml`.

## Metrics

Business metrics are emitted by `AiCostOpsMetrics` (AIC-076) with stable
`aicostops.*` names and bounded enum/code labels only (no user/org/request/free
text). Examples:

- `aicostops_login_result_total{result=...}`
- `aicostops_import_completed_total{provider=...,result=...}`
- `aicostops_ledger_posting_total{sourceType=...,result=...}`
- `aicostops_budget_activation_total{result=...}`
- `aicostops_reconciliation_run_total{result=...}`
- `aicostops_period_close_total{result=...}` / `aicostops_period_reopen_total{result=...}`
- `aicostops_dependency_error_total{dependency=...}`

Infrastructure signals (HTTP, JVM, GC, Hikari pool) come from Spring Boot /
Micrometer built-ins.

## Alert rules

`alerts.yml` contains:

- `AiCostOpsHigh5xxRate` — 5xx ratio > 5% for 5m (real ops; not exercised by smoke).
- `AiCostOpsImportFailureSpike` — >= 3 failed imports in 10m (real ops; not exercised by smoke).
- `AiCostOpsLoginInvalidCredentialsSpike` — >= 3 invalid-credential logins in 1m,
  `for: 15s`. This short threshold is a deterministic smoke signal only, **not** a
  production SLO/HA/capacity claim.

## Smoke test

`scripts/observability-smoke.ps1` proves the alert pipeline end-to-end against the
real stack using a bounded, deterministic signal (invalid-credential logins). It:

1. aborts if any host port it needs (18080/9090/3000) is already taken, so it
   never collides with a concurrently running normal `ai-costops` stack;
2. starts `backend/prometheus/grafana` (+ deps) under an isolated project name;
3. verifies the backend target is `UP` and a real `aicostops_*` series is non-empty;
4. seeds the invalid-credential series, waits for a scrape, then generates a bounded
   number of failures so `increase()` has a real baseline;
5. observes the chosen alert go `pending -> firing -> inactive`;
6. verifies the Grafana dashboard is provisioned;
7. stops **only** the project it started (never a global prune, never unrelated
   projects) and machine-verifies the cleanup: the `down` exit code must be 0 and
   `docker compose -p aicostops-obs ... ps -q` must be empty afterwards;
8. writes `docs/03-acceptance/m9-observability-evidence.md` with real run data,
   including the machine-verified cleanup result.

The core Compose stack is validated separately by the repository's standard
runtime smoke `scripts/smoke-v1.ps1`, run without this overlay.

```powershell
pwsh -File scripts/observability-smoke.ps1
```

No SLO/HA/capacity conclusions are drawn from the smoke.
