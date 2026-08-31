# M9 / v1.1.0 Acceptance Evidence Matrix

This matrix is the live M9 acceptance index. It starts as `NOT EXECUTED`; evidence links are filled only after real implementation/test execution.

| Stable ID | Area | Required evidence | Current state |
|---|---|---|---|
| AIC-074 | Audit closure | Provider Account + Allocation Rule direct audit assertions; backend suites | NOT EXECUTED |
| AIC-075 | Production config | prod fail-fast tests + production configuration runbook | NOT EXECUTED |
| AIC-076 | Metrics | bounded metric tests + `/actuator/prometheus` evidence | NOT EXECUTED |
| AIC-077 | Observability | Prometheus target + Grafana dashboard + alert injection/recovery | NOT EXECUTED |
| AIC-078 | Browser E2E | Playwright critical-flow report + CI run | NOT EXECUTED |
| AIC-079 | Security CI | CodeQL + Trivy final green evidence / accepted risks | NOT EXECUTED |
| AIC-080 | Recovery | MySQL + Evidence restore drill with `M9_RESTORE_DRILL_PASS` | NOT EXECUTED |
| AIC-081 | Scale | 10k / 100k / 500k import report + reporting benchmark | EXECUTED — 10k import + 10k reporting PASS; evidence in [m9-scale-evidence.md](m9-scale-evidence.md); 100k/500k opt-in pending |
| AIC-082 | Provider certification | one real-but-redacted Provider certification report | NOT EXECUTED |
| AIC-083 | Final acceptance | clean final verification + GitHub checks + human sign-off | NOT EXECUTED |

## Release blockers

The following are blocking for `v1.1.0` release acceptance:

```text
unresolved P0/P1 defect
failed production configuration guard
failed required security CI
failed MySQL restore drill
failed Evidence restore drill
missing real Provider certification
missing required M9 benchmark evidence
failed critical browser E2E
failed core backend/frontend CI
```

A performance result may be documented as resource-limited rather than falsely marked PASS, but AIC-083 must explicitly judge whether that limitation is acceptable for the intended v1.1 release claim.

## Final allowed decision values

```text
M9 = ACCEPTED / RELEASE GO
M9 = ACCEPTED WITH DOCUMENTED NON-BLOCKING LIMITATIONS
M9 = BLOCKED
```
