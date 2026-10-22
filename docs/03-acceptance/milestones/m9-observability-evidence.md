# AIC-077 Observability Smoke Evidence

> Real run output. NO SLO / HA / capacity claims are made.

- Generated: 2026-08-28T22:26:07+08:00
- Commit SHA: 116e6851e0c9694e50cafde495dd45ed95e75a80
- Docker server version: 29.6.1
- Docker Compose version: 5.3.0
- Compose project: aicostops-obs (started/stopped only by this script)
- Prometheus image: prom/prometheus:v2.54.1
- Grafana image: grafana/grafana:11.5.2

## Checks

| Check | Result | Detail |
|---|---|---|
| backend_health | PASS | /actuator/health/liveness |
| prometheus_ready | PASS | /-/ready |
| grafana_health | PASS | /api/health |
| target_up | PASS | up{job="aicostops-backend"}=1 |
| business_series_nonempty | PASS | aicostops_login_result_total{result="INVALID_CREDENTIALS"}=1 |
| alert_firing | PASS | pending=08/28/2026 22:25:16 firing=08/28/2026 22:25:31 |
| alert_recovered | PASS | recoveredAt=08/28/2026 22:26:03 |
| grafana_dashboard_provisioned | PASS | title=AI-CostOps Operational Overview |
| cleanup | PASS | no running containers remain for project aicostops-obs |

## Alert transition (deterministic fault injection)

Signal: invalid-credential logins (metric aicostops_login_result_total{result="INVALID_CREDENTIALS"}).
Rule: sum(increase(aicostops_login_result_total{result="INVALID_CREDENTIALS"}[1m])) >= 3, for: 15s.

- pending observed at: 08/28/2026 22:25:16
- firing observed at: 08/28/2026 22:25:31
- recovered (inactive) at: 08/28/2026 22:26:03

## Core Compose runtime (without observability overlay)

- observability overlay loaded: NO (base `compose.yaml` only)
- stack start command: `docker compose --env-file .env up -d`
- core smoke command: `pwsh -File scripts/smoke-v1.ps1 -EnvFile .env`
- services healthy: mysql / redis / minio / backend / frontend
- smoke stages passed: Compose service health, Dependency readiness, Compose log
  classification, Authentication and organization scope, Workbench read, Provider
  account and DeepSeek import, Employee expense submit, Audit query
- result: PASS (exit 0, final output `SMOKE_V1_PASS`)

## Notes

- Core Compose remains usable without the observability overlay. The default/core
  Compose configuration exposes only health,info; this overlay additionally
  enables prometheus. The production profile also exposes prometheus by design,
  with production safety relying on the documented deployment boundary (the
  backend is private / not directly Internet-exposed and the frontend proxy does
  not route /actuator/*). Sensitive endpoints (env, configprops, beans,
  heapdump) remain unexposed in all cases.
- No global Docker prune and no unrelated project was stopped by this script.
- Cleanup is machine-verified: the down exit code is checked (non-zero fails the
  smoke) and docker compose -p aicostops-obs ... ps -q must be empty afterwards.
