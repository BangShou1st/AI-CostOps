# 账单导入批量持久化性能验收

## 元数据

- **日期**：2026-11-17
- **责任人 / AI**：Codex
- **关联模块**：`backend/ingestion`、`backend/cost`
- **关联缺陷**：500 行短事务内部仍逐行 JDBC 插入；OpenAI JSON 源流被重复打开三次

## 变更概述

导入持久化改为有界批量：

1. 500 行短事务内一次插入 `raw_provider_record`，再按
   `(import_attempt_id, record_index)` 一次取回 raw id；`import_issue` 批量插入。
2. `CanonicalCostWritePort.writeAll` 统一 normalize/validate 后，五类 canonical facts
   按表分块多行插入；每块最多 500 行，避免 MySQL prepared-statement 参数上限。
3. OpenAI 官方 JSON inspection 阶段保留分桶窗口，parse 阶段直接复用；MinIO 源流读取
   从 3 次降为 2 次。
4. sanitized normalized payload 以 `JsonNode` 直接进入 canonicalization，避免
   “序列化后立即反序列化”。

事务边界、租约 fencing、全批回滚、计数器、canonical 失败语义均未改变。

## 验证环境与执行命令

环境：Windows 11、Java 21.0.10、Docker Desktop、MySQL 8.4 / Redis 8.8.1 /
MinIO Testcontainers；单 Worker，默认 500 行批量。

```powershell
cd backend
.\mvnw.cmd -B "-Dit.test=ImportAttemptExecutorIntegrationTest,ImportCanonicalizationIntegrationTest" "-DfailIfNoSpecifiedTests=false" verify

.\mvnw.cmd -B "-Dm9.benchmark.scales=10k" "-Dm9.benchmark.runs=3" `
  failsafe:integration-test "-Dit.test=M9ImportScaleBenchmarkIntegrationTest"
```

## 关键断言与证据

历史同一主机 / 容器配置下的 10k 导入基线记录为 289–343 rows/s
（[CO-04 性能验收](2026-11-11-co-04-sql-worker-performance/2026-11-11-co-04-sql-worker-performance.md)）。
本轮 10k、三次实测：

| Run | Worker ms | Worker records/s | End-to-end rows/s |
|---|---:|---:|---:|
| 1 | 2,331 | 4,290.004 | 3,938.558 |
| 2 | 2,194 | 4,557.885 | 4,242.681 |
| 3 | 2,323 | 4,304.778 | 4,024.145 |

- Worker 中位数：4,304.778 records/s；相对历史 10k 基线区间上沿 343 rows/s
  仍约 12.5 倍，超过本轮“至少 2 倍”的验收目标。
- 实测阶段 `worker_ms` 为 2.19–2.33 秒，upload 为 75–98 ms，confirm 为 65–133 ms。
- 每次运行均通过 M9 强制正确性断言：raw/canonical/hint 计数、金额聚合、最终状态、
  幂等发布与 secret 不落库。
- `ImportAttemptExecutorIntegrationTest` 20/20、`ImportCanonicalizationIntegrationTest`
  15/15 通过。
- `OpenAiProviderAdapterTest` 38/38 通过，其中新增测试确认 OpenAI JSON parse 复用
  inspection 分桶窗口，`openStream()` 总次数为 2。

## 结论与影响边界

**结论：本地 PASS。**

该结果是在单机 Testcontainers / 单 Worker / 10k 行条件下的观测，不是生产容量承诺。
100k/500k、并发 Worker、真实 Provider 大文件和对象存储网络抖动仍属于后续容量验证范围。
