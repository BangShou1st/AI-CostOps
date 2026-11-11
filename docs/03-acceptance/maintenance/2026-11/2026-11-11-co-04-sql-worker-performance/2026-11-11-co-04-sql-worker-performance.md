# CO-04 SQL / Worker / 性能复验验收（2026-11-11）

## 1. 元数据

| 项 | 值 |
|---|---|
| 日期 | 2026-11-11 |
| 责任人 / AI | BangShou1st / Claude Code |
| 关联模块 | 导入 Worker、工作台聚合、预算/认领 SQL |
| 关联计划 | `docs/02-development/designs/maintenance/2026-11-10-backend-reliability-refactor-design.md` §7 / §9 CO-P4 |
| 基线 | `main = dev = 57696e2` |
| 环境 | Windows 11 / Docker Desktop（`cpus=8`, `MemTotal≈1.9 GB`）/ Java 21.0.10 / Maven Wrapper 3.9.11 / Spring Boot 4.1.0 / MySQL 8.4 Testcontainers |
| 结论 | **PASS（附 NOT_VERIFIED）**——正确性断言全部通过；10k/100k 各 3 轮有实测数据；未发现需要改 SQL/索引的证据，保留原 SQL；500k reporting 因当前 Docker 内存未跑 |

## 2. Import benchmark（真实 DB-backed worker）

命令：

```bash
cd backend
./mvnw -B verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=M9ImportScaleBenchmarkIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dm9.benchmark.scales=10k,100k -Dm9.benchmark.runs=3
```

| Scale | Run | total_ms | worker rows/s | end-to-end rows/s | GC ms |
|---|---:|---:|---:|---:|---:|
| 10k | 1 | 31,443 | 320.297 | 318.036 | 68 |
| 10k | 2 | 29,330 | 342.747 | 340.948 | 38 |
| 10k | 3 | 34,699 | 289.494 | 288.193 | 19 |
| 100k | 1 | 304,618 | 328.875 | 328.280 | 318 |
| 100k | 2 | 313,693 | 319.609 | 318.783 | 290 |
| 100k | 3 | 295,242 | 341.320 | 338.705 | 221 |

汇总（n=3，p95 不具统计意义，只报 min/median/max）：

| Scale | worker rows/s (min / median / max) | end-to-end rows/s (min / median / max) | total_ms (min / median / max) |
|---|---|---|---|
| 10k | 289.494 / 320.297 / 342.747 | 288.193 / 318.036 / 340.948 | 29,330 / 31,443 / 34,699 |
| 100k | 319.609 / 328.875 / 341.320 | 318.783 / 328.280 / 338.705 | 295,242 / 304,618 / 313,693 |

每组额定正确性断言（测试内）：`import_batch=CONFIRMED`、`import_attempt=SUCCEEDED`、raw rows / charge facts / attribution hints 数量精确、金额精确、无重复发布、无 secret 持久化。全部通过，`BUILD SUCCESS`。

## 3. Reporting benchmark（真实 WorkbenchQueryService + EXPLAIN ANALYZE）

命令（10k rows × 30 次，每次测量前 flush Redis 强制回源 SQL）：

```bash
cd backend
./mvnw -B verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=M9ReportingScaleBenchmarkIntegrationTest -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dm9.reporting.rows=10000 -Dm9.reporting.runs=30
```

30 次测量（`fixture_ms=461,290`，10k charges；每次测量前 flush Redis 强制回源）：

| 指标 | min | median | p95 | max |
|---|---:|---:|---:|---:|
| workbench_ms | 70 | 73 | 77 | 79 |

结论：10k charges 下工作台聚合稳定在 70–79 ms，p95 = 77 ms（n=30，最近秩）。

## 4. EXPLAIN ANALYZE（真实 MySQL 执行）

M8 `EXPLAIN ANALYZE`（MySQL 8.4.11，真实执行；`import_queue_claim` 的夹具没有 QUEUED attempt，rows=0，但计划仍使用队列索引）：

| SQL | 外层耗时 ms | actual time ms | rows |
|---|---:|---:|---:|
| `import_queue_claim` | 21 | 1.03 | 0 |
| `budget_lookup_by_period` | 8 | 2.42–3.03 | 50 |
| `duplicate_review_page` | 4 | 1.59–1.81 | 50 |
| `workbench_provider_cost` | 15 | 3.0 | 1 |
| `workbench_unallocated_currency` | 4 | 1.16 | 1 |

M9 reporting `EXPLAIN ANALYZE`（10k charges）：

| SQL | 外层耗时 ms | actual time ms | rows |
|---|---:|---:|---:|
| `workbench_provider_cost` | 36 | 21.6 | 3 |
| `workbench_project_cost` | 33 | 29.9 | 1 |
| `workbench_unallocated_currency` | 29 | 25.7 | 0 |

原始计划行见 `results.json` 的 `explainAnalyzeLines`；完整输出在本轮本地日志 `co04-explain-2026-11-11.log` / `co04-reporting-2026-11-11.log`（不入库）。

## 5. SQL / Worker 审读结论

- 导入认领：`ImportAttemptMapper.claimNextQueued` 使用 `FOR UPDATE SKIP LOCKED`，`idx_import_attempt_queue(status,available_at,id)` 与 `idx_import_attempt_lease(status,lease_until,id)` 存在；M8 既有 EXPLAIN 与本次 ANALYZE 均命中索引。
- 工作台聚合：`WorkbenchQueryMapper` 的 provider/currency、project、unallocated 三条 SQL 在 10k charges 上均为毫秒级；`EXPLAIN ANALYZE` 原文见 §4。
- 预算读取：`budget_lookup_by_period` 使用 `idx_budget_org_period_created_id`；10k budgets 下为索引范围扫描。
- 重复候选页：`duplicate_review_page` 使用现有 `(org_id,status,candidate_type,created_at,id)` 方向索引；240 charges 夹具下为毫秒级。
- **没有证据支持改 SQL 或加索引**：本轮不修改任何 SQL/DDL，保留原查询。`HybridReconciliationMapper` 的 `BINARY provider_request_id` 关联、Advisor claim、Settlement traversal 等未在本轮独立 EXPLAIN，标 `NOT_VERIFIED`。

## 6. 边界与未验证

| 项 | 状态 | 原因 |
|---|---|---|
| 500k reporting | **NOT_VERIFIED** | 当前 Docker Desktop `MemTotal≈1.9 GB`，低于历史 M9 证据的 7790 MB；不伪造 500k PASS |
| 500k import | **NOT_VERIFIED（本轮未重跑）** | 历史 M9 证据有 442.888 rows/s 的 500k 单轮数据；本轮选择 10k/100k × 3 以控制时间 |
| 跨 Gateway 实例竞争 | **NOT_VERIFIED** | 单 JVM 多连接；DB 行锁/唯一键语义由 CO-02 测试覆盖，真实双进程未跑 |
| 生产 QPS / P95 | **NOT_VERIFIED** | 无生产流量回放；本数据只代表该主机/该容器配置 |
| BINARY 关联 / Advisor / Settlement 查询 | **NOT_VERIFIED** | 未纳入本轮 EXPLAIN ANALYZE 列表；不影响本轮正确性结论 |

## 7. 结论

- 正确性不下降：所有 benchmark 在计时区外构造夹具，计时区内走真实上传 → DB worker → confirm / 真实 WorkbenchQueryService，并在每轮 assert 行数、金额与最终状态。
- 性能：10k/100k 各 3 轮稳定在约 290–343 worker rows/s；与历史 M9 证据同量级（本机 Docker 资源更小），未出现需要数据库队列换 MQ 的证据。
- 优化：没有稳定的 SQL/索引回归证据，因此**不改 SQL/索引**；本轮只补齐复跑能力、EXPLAIN ANALYZE 证据与报告口径。
