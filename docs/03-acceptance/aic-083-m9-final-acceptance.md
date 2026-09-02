# AIC-083 — M9 Production Foundation Final Acceptance

> M9 final closure: aggregate AIC-074–AIC-083 evidence, run final verification,
> and record the human-facing release decision for `v1.1.0`. This report is
> built from committed evidence and real runs, not from claims.

## Baseline

```text
Base main (release branch base): 70fe5ad9d732cda1cad1287e16fc3a1f1f39c0b4
Branch:                          release/v1.1.0
Release PR:                      #127
Target version:                  v1.1.0
```

## AIC-074 → AIC-082 evidence binding

| AIC | Issue | Merged PR | main merge SHA | Evidence | Result | Known limitation |
|---|---|---|---|---|---|---|
| 074 | Audit closure | #116 | `272ed84` | direct audit ports/adapters + backend suites | PASS | — |
| 075 | Production config | #117 | `868aa3b` | `ProductionConfigurationValidatorTest` + `docs/02-development/operations/01-production-configuration.md` | PASS | — |
| 076 | Metrics | #118 | `c13c1ad` | `AiCostOpsMetrics` + `AiCostOpsMetricsTest`; scrape evidence in m9-observability-evidence.md | PASS | dev core exposes health/info only; prometheus via overlay/profile by design |
| 077 | Observability | #119 | `52ab099` | `docs/03-acceptance/m9-observability-evidence.md` | PASS | alert fired + recovered (inactive), grafana provisioned |
| 078 | Browser E2E | #120 | `63e781a` | `docs/03-acceptance/m9-browser-e2e-evidence.md` | PASS | 5/5 on fresh-stack CI |
| 079 | Security CI | #121 | `b71e13a` | `docs/03-acceptance/m9-security-ci-evidence.md` | PASS | trivy 0 HIGH/CRITICAL, codeql green, accepted risks NONE |
| 080 | Backup / Restore | #122 | `382c238` | `docs/03-acceptance/m9-backup-restore-evidence.md` | PASS | full drill `M9_RESTORE_DRILL_PASS`; scripts unchanged since tested SHA `cbb84b58` |
| 081 | Scale | #123 | `e4b0c1b` | `docs/03-acceptance/m9-scale-evidence.md` | PASS | 500k reporting fixture not run on 7.6 GB local host (documented non-blocking) |
| 082 | Provider certification | #126 | `70fe5ad` | `docs/03-acceptance/m9-provider-certification-mimo.md` | PASS | `REAL_PROVIDER_CERTIFICATION_PASS` (MiMo, diff 0.000000); tested gate SHA `2f2976b` |

## Final verification (real runs)

### Backend (local, this release branch base)

```text
./mvnw.cmd -B -DexcludedGroups=architecture,integration test  → BUILD SUCCESS, 454 tests, 0 failures
./mvnw.cmd -B -Dgroups=architecture test                      → BUILD SUCCESS, 34 tests, 0 failures
./mvnw.cmd -B -Dgroups=integration verify                     → BUILD SUCCESS, 805 tests, 0 failures
```

Integration includes the M9 import/reporting benchmarks at their CI default
scales (both PASS; e.g. `M9ImportScaleBenchmarkIntegrationTest` and
`M9ReportingScaleBenchmarkIntegrationTest`).

### Frontend (local)

```text
npm run lint     → exit 0
npm test -- --run → 47 files / 432 tests passed
npm run build    → built in ~12s, exit 0
```

### Browser E2E

- Authoritative fresh-stack result (GitHub CI, identical code): **5 specs / 5
  passed / 0 failed** on main `70fe5ad` (browser-e2e check completed/success)
  and recorded in `m9-browser-e2e-evidence.md`.
- Local re-run on the shared developer stack: 3 passed / 2 failed; the two
  failures (`budget-expense-ledger`, `import-allocation`) are environmental —
  the shared dev org has accumulated objects from prior certification/e2e runs,
  so dropdown/table state races on stale rows (e.g. select-option and row-click
  timeouts). Both specs pass on fresh stacks; no product code changed in this
  release to affect them. Documented as an environment observation, not a
  release blocker.

### Compose smoke (local)

```text
.\scripts\smoke-v1.ps1 -EnvFile .env -BaseUrl http://localhost:18080/api/v1 → SMOKE_V1_PASS (exit 0)
stages: service health (all 5 healthy), dependency readiness, log classification,
auth/org/permissions, workbench read, DeepSeek import+confirm, expense submit, audit query
```

Services: backend / frontend / mysql / redis / minio all `healthy`.

### Observability smoke

- Current core stack verified: backend `/actuator/health/liveness` = `{"status":"UP"}`.
- The observability overlay smoke (`scripts/observability-smoke.ps1`) could not
  start on this host because the running core stack already publishes the
  overlay's fixed frontend port (`compose.observability.yaml` maps
  `127.0.0.1:18080:8080` and the core stack publishes :18080) → `docker compose
  up` port conflict. Environment observation only.
- Authoritative AIC-077 overlay evidence (unchanged scripts, merged #119):
  Prometheus target `up{job="aicostops-backend"}=1`, business series
  non-empty, alert inactive → pending → firing → inactive under deterministic
  fault injection, Grafana dashboard provisioned, cleanup verified.

### Restore drill

- Backup/restore/evidence scripts unchanged since AIC-080 tested
  implementation `cbb84b58` (`git log 382c238..HEAD -- scripts/ops/` empty).
- AIC-080 evidence `M9_RESTORE_DRILL_PASS` remains valid: source counts,
  MySQL dump sha256, restore 46 tables, evidence bucket 50/50 hashes verified,
  login works, charges/expenses/postings/entries counts exact, period
  OPEN preserved, isolated drill project removed, developer volumes untouched.
- Per AIC-083 instruction, the expensive full drill was not re-run for
  ceremony because its implementation is byte-identical.

### Security / CI authenticity

- Final release PR head `35d32750c3352131d61667ae3c7f534c8a23a6de` completed all release-gate checks successfully.
- CI run `33531734600`: **8/8 SUCCESS** — backend-unit, backend-architecture,
  backend-integration, frontend-lint, frontend-test, frontend-build,
  docker-build, browser-e2e.
- Security run `33531734465`: **3/3 SUCCESS** — trivy,
  codeql (java-kotlin), codeql (javascript-typescript).
- Browser E2E ran on the isolated fresh Compose stack and completed successfully.
- No skipped required check, no `continue-on-error`, no `|| true`, and no stale-SHA green was used for the final release decision.
- Base-main/AIC-082 merge checks also remained green before the release branch was cut; the final release decision is bound to the release-head runs above.

## Scale evaluation (AIC-081, honest record)

```text
Import:  10k PASS (412.899 rows/s) · 100k PASS (434.032 rows/s) · 500k PASS (442.888 rows/s)
          → linear scaling, no resource ceiling, no broker introduced
Reporting: 10k PASS (workbench 112 ms) · 100k PASS (workbench 731 ms)
500k reporting: NOT completed on the 7.6 GB local Docker host (fixture time);
          documented resource-limited observation, NOT a pass, NOT an invented blocker.
Stale "~155 rows/s" value: corrected to ~412.899 rows/s per worker and absent from current evidence.
```

AIC-083 judgment: the 500k-reporting host limitation is **non-blocking** for the
v1.1 claim — import 500k PASS already demonstrates 500k-scale capability through
the full ingest pipeline, and reporting scales log-linearly across 10k→100k.

## Real Provider certification (AIC-082)

```text
Provider: MiMo (mimo.usage-workbook.v1 / mimo-provider-import-v1)
Real input: YES (local, git-ignored/untracked) · Input tracked: NO
source rows 7 = records seen 7 = records valid 7; canonical charge_fact 7
source aggregate 9.151267 CNY = canonical 9.151267 → difference 0.000000
REAL_PROVIDER_CERTIFICATION_PASS (certification gate SHA 2f2976b)
Redacted evidence: docs/03-acceptance/m9-provider-certification-mimo.md
No adapter gap found; no sanitized fixture or adapter change introduced.
```

## Defects

```text
P0: 0
P1: 0
```

## Known limitations (non-blocking)

1. 500k reporting fixture not executed on the 7.6 GB local host (AIC-081).
2. Local shared-stack browser E2E 3/5 due to accumulated dev-org state;
   fresh-stack CI E2E 5/5 (authoritative).
3. Observability overlay smoke cannot start on this host while the core stack
   holds :18080 (port derivation); AIC-077 overlay evidence remains valid.

## M9 final decision

```text
M9 = ACCEPTED WITH DOCUMENTED NON-BLOCKING LIMITATIONS
```

Release delivery (tag + GitHub Release) happens only after this release PR
merges and main CI is green, per Issue #115.
