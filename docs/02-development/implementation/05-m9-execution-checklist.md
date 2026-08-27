# M9 Execution Checklist

## Wave 1

- [ ] AIC-074 Audit Closure
- [ ] AIC-075 Production Configuration
- [ ] AIC-076 Application Metrics

## Wave 2

- [ ] AIC-077 Prometheus / Grafana / Alerts
- [ ] AIC-078 Browser E2E
- [ ] AIC-079 Security CI

## Wave 3

- [ ] AIC-080 Backup / Restore
- [ ] AIC-081 Scale Evidence
- [ ] AIC-082 Provider Certification

## Wave 4

- [ ] AIC-083 Final Acceptance / v1.1.0

For every item:

```text
Issue created
Branch from latest main
Failing targeted test/evidence first when applicable
Minimal implementation
Targeted PASS
Full relevant regression PASS
PR CI PASS
Evidence saved
Human acceptance if required
Squash merge
```

Do not check an item based on an agent summary alone; use repository/test/CI evidence.
