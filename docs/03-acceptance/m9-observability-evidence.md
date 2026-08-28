# AIC-077 Observability Smoke Evidence

> Real run output. NO SLO / HA / capacity claims are made.

- Generated: 2026-08-28T13:36:35+08:00
- Commit SHA: c5d7e507b89e29c3d725648a13dd3581f47d4bf6
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
| alert_firing | PASS | pending=08/28/2026 13:35:46 firing=08/28/2026 13:36:02 |
| alert_recovered | PASS | recoveredAt=08/28/2026 13:36:34 |
| grafana_dashboard_provisioned | PASS | title=AI-CostOps Operational Overview |

## Alert transition (deterministic fault injection)

Signal: invalid-credential logins (metric aicostops_login_result_total{result="INVALID_CREDENTIALS"}).
Rule: sum(increase(aicostops_login_result_total{result="INVALID_CREDENTIALS"}[1m])) >= 3, for: 15s.

- pending observed at: 08/28/2026 13:35:46
- firing observed at: 08/28/2026 13:36:02
- recovered (inactive) at: 08/28/2026 13:36:34

## Notes

- Core Compose remains usable without the observability overlay. The default/core
  Compose configuration exposes only health,info; this overlay additionally
  enables prometheus. The production profile also exposes prometheus by design,
  with production safety relying on the documented deployment boundary (the
  backend is private / not directly Internet-exposed and the frontend proxy does
  not route /actuator/*). Sensitive endpoints (env, configprops, beans,
  heapdump) remain unexposed in all cases.
- No global Docker prune and no unrelated project was stopped by this script.
