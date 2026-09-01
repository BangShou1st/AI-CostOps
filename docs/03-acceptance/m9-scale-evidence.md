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
- Tested implementation SHA: `80bd8a20cd7f2cf4638456027d5c3099df6087a4` (`fix(perf): distinguish real scale ceilings from benchmark failures`)
- Evidence SHA: this commit (docs-only follow-up after 80bd8a2).

## Environment

| Item | Value |
|---|---|
| Host | Windows 10/11 x64, Docker Desktop |
| Docker Total Memory | 7790 MB |
| MySQL | 8.4 (Testcontainers) |
| Java | 21.0.10 |
| Spring Boot | 4.1.0 |
| Default CI scale | 10k correctness sample (larger scales are opt-in properties) |

## Import benchmark — 10k / 100k / 500k (all PASS, no ceiling)

Default CI command (10k sample):

```powershell
Set-Location "E:\AI-CostOps\backend"
.\mvnw.cmd -B "-Dgroups=integration" "-Dit.test=M9ImportScaleBenchmarkIntegrationTest" verify
```

Larger scales (real measurement on this host):

```powershell
.\mvnw.cmd -B "-Dm9.benchmark.scales=100k" "-Dm9.benchmark.runs=1" "-Dit.test=M9ImportScaleBenchmarkIntegrationTest" verify
.\mvnw.cmd -B "-Dm9.benchmark.scales=500k" "-Dm9.benchmark.runs=1" "-Dit.test=M9ImportScaleBenchmarkIntegrationTest" verify
```

Results (Testcontainers MySQL 8.4, no resource ceiling hit):

```text
# 10k (139.7 s, narrow ceiling classifier active)
M9_IMPORT_BENCHMARK|warmup|scale=10k|input_rows=10000|input_bytes=64702|upload_ms=978|worker_ms=28646|confirm_ms=237
M9_IMPORT_BENCHMARK|measured|scale=t10k|run=1|input_rows=10000|input_bytes=64696|upload_ms=268|worker_ms=24219|confirm_ms=75|total_ms=24562|worker_records_per_sec=412.899|end_to_end_rows_per_sec=407.133|jvm_max_heap_mib=4024|jvm_used_heap_sample_mib=77|gc_count_delta=42|gc_time_ms_delta=44

# 100k (386.1 s, no ceiling)
M9_IMPORT_BENCHMARK|warmup|scale=10k|input_rows=10000|input_bytes=64704|upload_ms=1422|worker_ms=34810|confirm_ms=150
M9_IMPORT_BENCHMARK|measured|scale=t100k|run=1|input_rows=100000|input_bytes=634871|upload_ms=292|worker_ms=230398|confirm_ms=230|total_ms=230920|worker_records_per_sec=434.032|end_to_end_rows_per_sec=433.050|jvm_max_heap_mib=4024|jvm_used_heap_sample_mib=119|gc_count_delta=279|gc_time_ms_delta=551

# 500k (1306 s / 21.8 min, no ceiling — linear scaling 412→434→442 rows/s)
M9_IMPORT_BENCHMARK|warmup|scale=10k|input_rows=10000|input_bytes=64700|upload_ms=1094|worker_ms=31390|confirm_ms=227
M9_IMPORT_BENCHMARK|measured|scale=t500k|run=1|input_rows=500000|input_bytes=3164766|upload_ms=408|worker_ms=1128954|confirm_ms=2360|total_ms=1131722|worker_records_per_sec=442.888|end_to_end_rows_per_sec=441.805|jvm_max_heap_mib=4024|jvm_used_heap_sample_mib=225|gc_count_delta=828|gc_time_ms_delta=1438
```

All three: Tests run 1, Failures 0, Errors 0, BUILD SUCCESS. No M9_IMPORT_RESOURCE_CEILING — the narrow classifier did not suppress any AssertionError / SQL / business exception.

Correctness assertions at this scale (all verified inside the test):

```text
import_batch status=CONFIRMED, confirmed_attempt_id set
import_attempt SUCCEEDED, records_seen=records_valid=10000, error_count=0
raw_provider_record=10000, charge_fact=10000, attribution_hint=30000
import_issue=0, duplicate_candidate=0
SUM(charge_fact.amount) = 12500.00000000 (exact)
single currency; audit_event=1; no secret key material persisted
```

Measured worker rate at 10k: ~412.899 rows/s per worker (DB-backed worker,
concurrency=1, default batch size).

## Reporting benchmark — 10k / 100k (PASS, with real EXPLAIN ANALYZE)

Commands:

```powershell
# 10k (CI default)
.\mvnw.cmd -B "-Dgroups=integration" "-Dit.test=M9ReportingScaleBenchmarkIntegrationTest" verify
# 100k (real larger scale)
.\mvnw.cmd -B "-Dgroups=integration" "-Dm9.reporting.rows=100000" "-Dit.test=M9ReportingScaleBenchmarkIntegrationTest" verify
```

Results (real local runs, EXPLAIN ANALYZE verbatim from MySQL):

```text
# 10k (293.2 s)
M9_REPORTING_BENCHMARK|measured|rows=10000|fixture_ms=254664|workbench_ms=112|providers=3|projects=1|charges_sum=12500.00000000|period=OPEN
M9_REPORTING_EXPLAIN|workbench_provider_cost|ms=31|plan=-> Limit: 100 row(s)  (actual time=20.7..20.7 rows=3 loops=1)
M9_REPORTING_EXPLAIN|workbench_project_cost|ms=32|plan=-> Limit: 100 row(s)  (actual time=29..29 rows=1 loops=1)
M9_REPORTING_EXPLAIN|workbench_unallocated_currency|ms=28|plan=-> Sort: amount DESC  (actual time=25.8..25.8 rows=0 loops=1)

# 100k (7594 s / 126.6 min including fixture; workbench_ms=731)
M9_REPORTING_BENCHMARK|measured|rows=100000|fixture_ms=7524015|workbench_ms=731|providers=3|projects=1|charges_sum=125000.00000000|period=OPEN
M9_REPORTING_EXPLAIN|workbench_provider_cost|ms=252|plan=-> Limit: 100 row(s)  (actual time=213..213 rows=3 loops=1)
M9_REPORTING_EXPLAIN|workbench_project_cost|ms=413|plan=-> Limit: 100 row(s)  (actual time=409..409 rows=1 loops=1)
M9_REPORTING_EXPLAIN|workbench_unallocated_currency|ms=188|plan=-> Sort: amount DESC  (actual time=186..186 rows=0 loops=1)
```

- 10k: fixture 254s inserts 10k charges + allocations; workbench 112ms hits MySQL after Redis flush.
- 100k: fixture 7524s inserts 100k charges; workbench 731ms — log-linear scaling, no timeout.

## 500k reporting — not yet measured locally

Reporting at 500k rows (fixture inserts 500k charges + allocation lines via batched JDBC) was not completed on this developer host in this PR cycle due to the multi-hour fixture time under the 7.6 GB Docker ceiling. The 10k and 100k reporting runs above are the AIC-081 evidence; 500k reporting is tracked for AIC-083 with a larger-memory runner or fixture partitioning. The import 500k PASS above already demonstrates the system's ability to handle 500k canonical charges through the full ingest pipeline.

If a future scale cannot complete (real OOM / container kill / benchmark timeout), the import harness records a real M9_IMPORT_RESOURCE_CEILING marker with heap/GC context instead of fabricating a PASS; AssertionError / SQL / business exceptions still fail via the narrow isRecognizedResourceCeiling classifier.

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
KEEP DB-BACKED WORKER         <- chosen: 10k/100k/500k all PASS, linear scaling 412→434→442 rows/s
TUNE DB-BACKED WORKER         <- not required at current evidence; monitor if rate degrades at higher concurrency
EVALUATE TRANSACTIONAL OUTBOX <- open only if import rate becomes a P1 cost driver
EVALUATE MESSAGE BROKER       <- no evidence for RabbitMQ/Kafka; not introduced (AIC-081 is evidence only)
```

No message broker or outbox was introduced. The DB-backed worker shows linear scaling across 10k→100k→500k (worker_records_per_sec 412→434→442) with no resource ceiling hit under the narrow classifier. Reporting scales linearly as well (10k: 112 ms, 100k: 731 ms). 500k reporting fixture is tracked for AIC-083.