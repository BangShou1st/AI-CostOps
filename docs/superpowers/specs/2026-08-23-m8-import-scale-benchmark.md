# M8 Stage 1 / PR1 — AIC-067 Import Scale Benchmark

## Baseline and environment

- Baseline commit: `02df36df87634256a659855707411941589e55f3`.
- Branch: `perf/m8-performance-hardening`.
- Host: Windows 11, Xiaomi Redmi G Pro 2024, 15.7 GB RAM, Docker reports 32
  CPUs.
- Docker Desktop: `4.82.0`; Docker server `29.6.1`, `overlayfs`, 8 GiB Docker
  memory.
- Java: `21.0.10`; Maven: `3.9.11`.
- MySQL Testcontainer: `mysql:8.4`, server `8.4.11`.
- Redis Testcontainer: `redis:8.8.1-alpine`.
- MinIO Testcontainer: `quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z`.
- Testcontainers: `2.0.5`.
- Docker storage and startup were normal for this sample: MySQL initialized in
  roughly 10–11 seconds and MinIO became healthy in roughly 1–2 seconds. No
  storage degradation was observed or used as a product conclusion.

## Harness and workload

Harness: `com.aicostops.M8ImportScaleBenchmarkIntegrationTest`.

Provider: DeepSeek, using the existing `DeepSeekProviderAdapter` and its real
`deepseek.usage-zip.v1` contract. Each synthetic ZIP contains:

- one `amount-2026-08.csv` with the required amount headers;
- one `cost-2026-08.csv` with the required cost headers;
- no real provider data; API-key values are sentinel strings used only to prove
  redaction.

The fixture is generated before timing. The timed boundaries are:

1. Upload: `ProviderImportService.create`, including upload staging/SHA,
   MySQL Evidence reservation, real MinIO PUT, and ImportBatch/Attempt creation.
2. Worker: `ImportWorkerCoordinator.pollOnce` through claim, lease fencing,
   DeepSeek inspect/parse/normalize, bounded raw persistence, canonical write,
   counters, and `READY_FOR_REVIEW` finalization.
3. Confirm: real `ImportWorkflowCommandService.confirm`, timed separately.

Scale definitions are this M8 benchmark workload, not official provider traffic:

| Scale | Rows per amount file | Rows per cost file | Input rows / raw records |
| --- | ---: | ---: | ---: |
| small | 32 | 32 | 64 |
| medium | 128 | 128 | 256 |
| large | 512 | 512 | 1,024 |

Commands:

```powershell
Set-Location "E:\AI-CostOps\backend"
.\mvnw.cmd -B failsafe:integration-test "-Dit.test=M8ImportScaleBenchmarkIntegrationTest"
.\mvnw.cmd -B "-Dm8.benchmark.scales=small,medium,large" "-Dm8.benchmark.runs=3" failsafe:integration-test "-Dit.test=M8ImportScaleBenchmarkIntegrationTest"
```

The default command runs one small warm-up and one small measured correctness
run. The reported scale sample used one warm-up plus three measured runs per
scale. Benchmark fixture construction was not included in any timed value.

## Warm-up

| Workload | Input rows | Input bytes | Upload ms | Worker ms | Confirm ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| small warm-up | 64 | 1,166 | 689 | 785 | 164 |

## Raw measured results

| Scale | Run | Input rows | Input bytes | Upload ms | Worker ms | Confirm ms | Total ms | Worker records/s | End-to-end rows/s |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| small | 1 | 64 | 1,162 | 86 | 427 | 59 | 572 | 149.883 | 111.888 |
| small | 2 | 64 | 1,162 | 65 | 427 | 92 | 584 | 149.883 | 109.589 |
| small | 3 | 64 | 1,165 | 61 | 325 | 51 | 437 | 196.923 | 146.453 |
| medium | 1 | 256 | 2,488 | 85 | 1,204 | 71 | 1,360 | 212.625 | 188.235 |
| medium | 2 | 256 | 2,487 | 70 | 1,114 | 106 | 1,290 | 229.803 | 198.450 |
| medium | 3 | 256 | 2,487 | 54 | 1,065 | 46 | 1,165 | 240.376 | 219.742 |
| large | 1 | 1,024 | 7,578 | 60 | 4,602 | 87 | 4,749 | 222.512 | 215.624 |
| large | 2 | 1,024 | 7,576 | 70 | 3,802 | 84 | 3,956 | 269.332 | 258.847 |
| large | 3 | 1,024 | 7,580 | 102 | 5,183 | 88 | 5,373 | 197.569 | 190.583 |

## Summary

| Scale | Worker median / range ms | Worker throughput median records/s | Total median / range ms | End-to-end throughput median rows/s |
| --- | ---: | ---: | ---: | ---: |
| small | 427 / 325–427 | 149.883 | 572 / 437–584 | 111.888 |
| medium | 1,114 / 1,065–1,204 | 229.803 | 1,290 / 1,165–1,360 | 198.450 |
| large | 4,602 / 3,802–5,183 | 222.512 | 4,749 / 3,956–5,373 | 215.624 |

These are observed values on the environment above. They are not TPS or a
production capacity claim.

## Correctness evidence

Every measured run asserted:

- ImportBatch final state `CONFIRMED` and `confirmed_attempt_id` equal to the
  processed attempt.
- ImportAttempt final state `SUCCEEDED`, `records_seen = records_valid` and
  `error_count = 0`.
- Raw record counts: 64 / 256 / 1,024 for small / medium / large.
- Canonical charge counts: 32 / 128 / 512, all `USD`.
- Attribution hint counts: 96 / 384 / 1,536.
- External document, consumption, pricing and import-issue counts: zero for
  this DeepSeek fixture, as required by the current normalizer contract.
- Charge amount aggregate: `1.25000000 USD × cost-file row count`.
- No duplicate candidate publication.
- One confirm audit event per run.
- Secret checks found zero persisted sentinel API-key values in raw or
  normalized payloads.

Failed or errored imports were not included as performance samples. The first
attempt to run the harness intentionally exposed a missing OPEN covering period
and was discarded; the harness was corrected to create that real prerequisite,
after which all reported runs passed.

## Bottleneck findings

Observed:

- Worker time dominates the measured end-to-end path: large worker time was
  3,802–5,183 ms while upload was 60–102 ms and confirm was 84–88 ms.
- The bounded worker pipeline completed all rows with the existing batch size,
  lease fencing, raw persistence, canonical writes, counters and finalization.
- Throughput varied across repeated runs; no single run was used as a claim.

Inferred from the measurements and existing code:

- The dominant cost is inside the worker's per-record/bounded-batch database
  persistence and canonical write path, not ZIP upload or confirm. This is an
  inference from phase timing; the harness did not collect statement-level
  query counts.

Not observed:

- No correctness failure, duplicate publication, amount/currency drift,
  secret persistence, lease error, or unbounded materialization in these runs.
- No EXPLAIN evidence showed a missing import-queue or raw-lineage join index.

Not tested:

- JVM heap/GC/CPU profiles, per-query counts, concurrent worker throughput,
  larger-than-1,024-row payloads, real provider exports, cloud object-storage
  latency, or replication behavior.

## Optimization decision

- Production import Java: unchanged. The benchmark established a repeatable
  baseline but did not prove a safe import semantic or transaction optimization.
- Schema/index: only AIC-066 V17 budget lookup index was added; it is unrelated
  to the Import worker hot path and has before/after EXPLAIN evidence in the
  companion AIC-066 document.
- Test/benchmark harness: added the real-container, synthetic DeepSeek,
  correctness-asserting benchmark and the MySQL EXPLAIN harness.

## Limitations and follow-ups

The scale names and thresholds are M8 workload choices, not provider-volume
acceptance thresholds. If future work needs to reduce worker time, isolate
statement counts and batch persistence latency first, then make a minimal
change with before/after correctness and throughput evidence. Such work is not
started in AIC-067 and must not silently expand into AIC-068+ scope.
