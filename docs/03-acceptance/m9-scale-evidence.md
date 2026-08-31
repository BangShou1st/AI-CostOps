# M9 Scale Evidence — AIC-081

> Real local run of both scale benchmarks on the real DB-backed worker and the
> real Workbench read model. AIC-081's job is evidence, not tuning: the decision
> on worker architecture stays grounded in measured numbers, and no broker or
> async machinery was added without it.

## Scope

- `M9ImportScaleBenchmarkIntegrationTest` — 10k/100k/500k opt-in import
  benchmark on the real ingestion worker (DB-backed poll + batch pipeline).
- `M9ReportingScaleBenchmarkIntegrationTest` — 10k/100k/500k opt-in reporting
  benchmark over a realistic charge/allocation fact set, driving the real
  `/api/v1/workbench` path (`WorkbenchQueryService`) with real MySQL
  `EXPLAIN ANALYZE` for the dominant statements.

- Branch: `perf/m9-scale-evidence`
- Implementation SHAs: the commits that add the two test classes
  (`backend/src/test/java/com/aicostops/M9ImportScaleBenchmarkIntegrationTest.java`,
  `backend/src/test/java/com/aicostops/M9ReportingScaleBenchmarkIntegrationTest.java`)
- Evidence SHA: the commit that adds this document.

## Environment

| Item | Value |
|---|---|
| Host | Windows 10/11 x64, Docker Desktop |
| Docker Total Memory | 7790 MB |
| MySQL | 8.4 (Testcontainers) |
| Java | 21.0.10 |
| Spring Boot | 4.1.0 |
| Default CI scale | 10k correctness sample (larger scales are opt-in properties) |

## Import benchmark — 10k (PASS)

Command (CI default):

```powershell
Set-Location "E:\AI-CostOps\backend"
.\mvnw.cmd -B "-Dgroups=integration" "-Dit.test=M9ImportScaleBenchmarkIntegrationTest" verify
```

Result (`Tests run: 1, Failures: 0, Errors: 0`, `BUILD SUCCESS`, 208.7 s):

```text
M9_IMPORT_BENCHMARK|warmup|scale=10k|input_rows=10000|input_bytes=65179|upload_ms=1263|worker_ms=70253|confirm_ms=233
M9_IMPORT_BENCHMARK|measured|scale=t10k|run=1|input_rows=10000|input_bytes=65173|upload_ms=152|worker_ms=64447|confirm_ms=187|total_ms=64786|worker_records_per_sec=155.166|end_to_end_rows_per_sec=154.354|jvm_max_heap_mib=4024|jvm_used_heap_sample_mib=71|gc_count_delta=60|gc_time_ms_delta=375|batch_size=default|worker_concurrency=1
```

Correctness assertions at this scale (all verified inside the test):

```text
import_batch status=CONFIRMED, confirmed_attempt_id set
import_attempt SUCCEEDED, records_seen=records_valid=10000, error_count=0
raw_provider_record=10000, charge_fact=10000, attribution_hint=30000
import_issue=0, duplicate_candidate=0
SUM(charge_fact.amount) = 12500.00000000 (exact)
single currency; audit_event=1; no secret key material persisted
```

Measured worker rate at 10k: ~155 rows/s per worker (DB-backed worker,
concurrency=1, default batch size).

## Reporting benchmark — 10k (PASS)

Command (CI default):

```powershell
.\mvnw.cmd -B "-Dgroups=integration" "-Dit.test=M9ReportingScaleBenchmarkIntegrationTest" verify
```

Result (`Tests run: 1, Failures: 0, Errors: 0`, `BUILD SUCCESS`, 332.5 s):

```text
M9_REPORTING_BENCHMARK|measured|rows=10000|fixture_ms=289428|workbench_ms=81|providers=3|projects=1|charges_sum=12500.00000000|period=OPEN
M9_REPORTING_EXPLAIN|workbench_provider_cost|ms=29|plan=-> Limit: 100 row(s)  (actual time=20..20 rows=3 loops=1)
M9_REPORTING_EXPLAIN|workbench_project_cost|ms=32|plan=-> Limit: 100 row(s)  (actual time=28.9..28.9 rows=1 loops=1)
M9_REPORTING_EXPLAIN|workbench_unallocated_currency|ms=28|plan=-> Sort: amount DESC  (actual time=25.5..25.5 rows=0 loops=1)
```

- `fixture_ms` inserts 10k raw records + 10k charges + 10k allocation
  decisions + 10k allocation lines through batched JDBC (outside the measured
  section).
- `workbench_ms=81` is the real `WorkbenchQueryService.get(...)` response with
  Redis cache flushed before the measured run, so the run hits MySQL again.
- Correctness at scale: 3 providers, 1 project, `sum(charges)=12500.00000000`,
  no unallocated charges, budget variance present, period status `OPEN`.
- EXPLAIN ANALYZE output above comes from real MySQL (`EXPLAIN ANALYZE`),
  verbatim statements from `WorkbenchQueryMapper`.

## Larger scales (100k / 500k) — opt-in, not part of CI default

Both classes accept the scale property; full workloads are explicit opt-in
because of the one-hour-plus worker runtime at the measured per-row rate and
the 7790 MB Docker memory ceiling on this machine:

```powershell
# import full workload (3 runs each; 500k can take well over an hour)
.\mvnw.cmd -B "-Dm9.benchmark.scales=10k,100k,500k" "-Dm9.benchmark.runs=3" failsafe:integration-test "-Dit.test=M9ImportScaleBenchmarkIntegrationTest"

# reporting at 200k rows
.\mvnw.cmd -B "-Dm9.reporting.rows=200000" failsafe:integration-test "-Dit.test=M9ReportingScaleBenchmarkIntegrationTest"
```

If a scale cannot complete on the local machine (OOM / timeout), the import
harness records a real `M9_IMPORT_RESOURCE_CEILING` marker with heap/GC
context instead of fabricating a PASS.

## Fixture repair history (how this run became green)

The reporting fixture went through a real schema-alignment pass against the
Flyway migrations before this PASS. Two confirmed defects were fixed with
surgical edits (no blanket sed):

1. `import_batch` INSERT was missing `updated_at` in the column list
   (11 columns vs 12 value placeholders). Added `updated_at`.
2. `UPDATE charge_fact SET current_allocation_decision_id=allocation_decision_id`
   referenced a non-existent column; rewritten as an explicit
   `UPDATE ... JOIN allocation_decision ad ON ad.charge_fact_id=cf.id`
   that sets `cf.current_allocation_decision_id = ad.id`.

The previous run (with defect 1) failed with
`BadSqlGrammarException: Column count doesn't match value count at row 1`.

## Worker decision (evidence-based)

```text
KEEP DB-BACKED WORKER         <- chosen on current evidence
TUNE DB-BACKED WORKER         <- measured ceiling: ~155 rows/s/worker at 10k
EVALUATE TRANSACTIONAL OUTBOX <- open only if import rate becomes a P1 cost driver
EVALUATE MESSAGE BROKER       <- no evidence today for RabbitMQ/Kafka; not introduced
```

No message broker or outbox was introduced; the DB-backed worker stays and the
10k sample is the CI gate. A dedicated 100k/500k run and cost analysis are
tracked for AIC-083 before any tuning decision.