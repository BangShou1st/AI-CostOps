# AIC-077 Observability Smoke Evidence

> Real run output. NO SLO / HA / capacity claims are made.

- Generated: 2026-08-28T12:41:24+08:00
- Commit SHA: c13c1adfa7f7a0749e8657badb720528652be784
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
| alert_firing | PASS | pending=08/28/2026 12:40:32 firing=08/28/2026 12:40:48 |
| alert_recovered | PASS | recoveredAt=08/28/2026 12:41:19 |
| grafana_dashboard_provisioned | PASS | title=AI-CostOps Operational Overview |

## Alert transition (deterministic fault injection)

Signal: invalid-credential logins (metric aicostops_login_result_total{result="INVALID_CREDENTIALS"}).
Rule: sum(increase(aicostops_login_result_total{result="INVALID_CREDENTIALS"}[1m])) >= 3, for: 15s.

- pending observed at: 08/28/2026 12:40:32
- firing observed at: 08/28/2026 12:40:48
- recovered (inactive) at: 08/28/2026 12:41:19

## Notes

- Core Compose remains usable without the observability overlay; prometheus is
  exposed only when compose.observability.yaml is loaded.
- No global Docker prune and no unrelated project was stopped by this script.
