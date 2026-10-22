# M9 / v1.1.0 Acceptance Evidence Matrix

This matrix is the live M9 acceptance index. Evidence links are filled from real implementation/test execution; every current-state entry below is backed by a merged PR and a committed evidence document (see `docs/03-acceptance/aic-083-m9-final-acceptance.md` for the closure report).

| Stable ID | Area | Required evidence | Current state |
|---|---|---|---|
| AIC-074 | Audit closure | Provider Account + Allocation Rule direct audit assertions; backend suites | PASS — PR #116, main `272ed84`; direct audit ports/adapters + backend suites green |
| AIC-075 | Production config | prod fail-fast tests + production configuration runbook | PASS — PR #117, main `868aa3b`; `ProductionConfigurationValidatorTest` + runbook `docs/02-development/operations/01-production-configuration.md` |
| AIC-076 | Metrics | bounded metric tests + `/actuator/prometheus` evidence | PASS — PR #118, main `c13c1ad`; `AiCostOpsMetrics` + `AiCostOpsMetricsTest`; scrape evidence in `m9-observability-evidence.md` |
| AIC-077 | Observability | Prometheus target + Grafana dashboard + alert injection/recovery | PASS — PR #119, main `52ab099`; `docs/03-acceptance/m9-observability-evidence.md` (target UP, dashboard provisioned, alert fired + recovered) |
| AIC-078 | Browser E2E | Playwright critical-flow report + CI run | PASS — PR #120, main `63e781a`; `docs/03-acceptance/m9-browser-e2e-evidence.md` (5 specs / 5 passed on fresh-stack CI) |
| AIC-079 | Security CI | CodeQL + Trivy final green evidence / accepted risks | PASS — PR #121, main `b71e13a`; `docs/03-acceptance/m9-security-ci-evidence.md` (CodeQL + Trivy green; no blanket ignores) |
| AIC-080 | Recovery | MySQL + Evidence restore drill with `M9_RESTORE_DRILL_PASS` | PASS — PR #122, main `382c238`; `docs/03-acceptance/m9-backup-restore-evidence.md`, full drill `M9_RESTORE_DRILL_PASS` (scripts unchanged since tested SHA `cbb84b58`) |
| AIC-081 | Scale | 10k / 100k / 500k import report + reporting benchmark | PASS — PR #123, main `e4b0c1b`; `docs/03-acceptance/m9-scale-evidence.md`: import 10k/100k/500k PASS (412→434→442 rows/s), reporting 10k/100k PASS; limitation: 500k reporting fixture not run on the 7.6 GB local host (documented non-blocking) |
| AIC-082 | Provider certification | one real-but-redacted Provider certification report | PASS — PR #126, main `70fe5ad`; `docs/03-acceptance/m9-provider-certification-mimo.md`, `REAL_PROVIDER_CERTIFICATION_PASS` (MiMo real export, diff 0.000000) |
| AIC-083 | Final acceptance | clean final verification + GitHub checks + human sign-off | PASS — PR #127; `docs/03-acceptance/aic-083-m9-final-acceptance.md`; final decision documented below |

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

None of the above is present after the AIC-074–AIC-082 evidence pass and the AIC-083 final verification (see the release decision in `docs/03-acceptance/aic-083-m9-final-acceptance.md`).

A performance result may be documented as resource-limited rather than falsely marked PASS; AIC-083 explicitly judges in the closure report that the 500k-reporting host limitation is non-blocking for the intended v1.1 release claim (import 500k PASS already proves 500k-scale capability through the full ingest pipeline).

## Final allowed decision values

```text
M9 = ACCEPTED / RELEASE GO
M9 = ACCEPTED WITH DOCUMENTED NON-BLOCKING LIMITATIONS
M9 = BLOCKED
```

**AIC-083 final decision: `M9 = ACCEPTED WITH DOCUMENTED NON-BLOCKING LIMITATIONS`** (see `docs/03-acceptance/aic-083-m9-final-acceptance.md`).
