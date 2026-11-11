# HC-03 阶段 A：现有能力与测试覆盖审计（2026-11-11）

## 1. 元数据

| 项 | 值 |
|---|---|
| 任务 | Issue [#180](https://github.com/BangShou1st/AI-CostOps/issues/180) HC-03 故障恢复专项 |
| 基线 | `main = 561b3844ecfa689e73f816580a7fb11ef1957908` |
| 分支 | `perf/hc-03-failure-recovery` |
| 审计范围 | Gateway 请求生命周期 / 限流 / 熔断 / TX1 / TX2 / 预留恢复 / Settlement Worker / PeriodClose / HC-01 harness / M16 脚本 |
| 结论 | 核心**判定逻辑**已有较强覆盖；**真实进程/容器中断与恢复**基本空白，F04 完全没有双实例演练 |

本文件只记录“已有覆盖 vs 本轮新增覆盖”，不重复 HC-01/HC-02 的性能或锁性能结论。

## 2. 关键代码路径（只读审计）

### 2.1 Gateway 请求生命周期

```text
Bearer auth (DB, 每请求)
  → 1 MiB body 上限 + OpenAI 兼容 DTO 校验
  → stream permit（仅 SSE）
  → Redis 限流 Lua → Redis 日配额 Lua        ← 失败：503 GATEWAY_DEPENDENCY_UNAVAILABLE
  → authorizeForRouting：模型/账期/请求行（幂等身份）
  → 逐候选：RouteAttemptCoordinator.plan（独立事务，先落 PLANNED）
  → TX1 BudgetReservationService.admitSync（共享账期锁 → Budget X 锁 → 预留）
  → TX2 DispatchFenceService.commitDispatchFence（共享账期锁 + 预占校验 + DISPATCH_INTENT）
  → beginUpstream（UPSTREAM_ACTIVE）→ 唯一的 Provider I/O
  → FINAL / UNKNOWN / INCOMPLETE usage；失败不重试已可能计费的调用
```

- 派发前任何失败都不会产生 Provider I/O；TX1/TX2 的任何异常都会回滚对应事务。
- `GatewayErrorResponseFilter` 把未分类异常统一映射为 503 `GATEWAY_DEPENDENCY_UNAVAILABLE`，因此 MySQL 断连不会伪装成 200。
- 同一幂等身份在 `DISPATCH_INTENT/UPSTREAM_ACTIVE/…AFTER_DISPATCH` 状态重放返回 409 `GATEWAY_REQUEST_IN_PROGRESS`，不会二次派发。

### 2.2 预占恢复

- [`ReservationRecoveryService`](../../../../../gateway/src/main/java/com/aicostops/gateway/budget/ReservationRecoveryService.java)
  三类扫描：过期 ACTIVE 预留、SAFE 已释放但请求未终止、TTL 前的派发前孤儿。
- 只有“请求 RESERVED/VALIDATED + attempt PLANNED”或 attempt 已 SAFE 才释放；其余一律 `PENDING_HOLD`（TTL 只是触发器，不是零成本证明）。
- 调度器默认 60s；本轮 drill 改为 3s + TTL 20s，仅测试配置。
- **空白**：真实进程崩溃后的扫描从未演练；`ReservationRecoveryScheduler` 在测试中从未被调度。

### 2.3 Backend Settlement

- DB worker 每次轮询按组织轮转，逐个结算；`GatewaySettlementService` 单事务：
  账期 X 锁 → Budget → Commitment → Reservation → Settlement → Ledger posting → Budget Actual → Reservation FINALIZED → SETTLED。
- 失败注入点：`BILLING_PERIOD_LOCKED / LEDGER_INSERTED / BUDGET_ACTUAL_MUTATED / AUDIT_WRITTEN / BEFORE_SETTLEMENT_SETTLED`。
- 瞬时失败按 `DATABASE_TRANSIENT`/`SETTLEMENT_ERROR` 记录，最多 3 次自动重试后 `RECONCILIATION_REQUIRED`；数据库不可达时整轮停止且游标不前进。
- **空白**：没有 JVM kill、没有真实 MySQL 停机；`RECONCILIATION_REQUIRED` 只通过 Mockito 断言，未真实跑满重试轮次。

### 2.4 Close / 账期锁

- HC-02 后 TX1/TX2 取 `FOR SHARE`，Close/Reopen/Recovery/Settlement 取 `FOR UPDATE`。
- Close begin：组织准入 X → 账期 X → `CLOSING`；随后 blocker 评估；finalize 重新锁账期。
- `PENDING_GATEWAY_FINANCIAL_WORK` 同时统计“可能已派发但财务未终结的请求”和 ACTIVE/PENDING_HOLD 预留。
- `withDeadlockRetry` 只包裹 `beginOrResume`（组织准入 → 账期顺序导致的 MySQL serialization loser），最多 3 次；finalize 不重试。
- **空白**：没有真实并发 Close + Gateway 流量的演练；没有 deadlock 重试耗尽的可见性测试。

### 2.5 M16 / HC-01 资产

- M16 `invoke-m16-failure.ps1` 已经证明“停止专用 MySQL/Redis 容器 → 失败闭锁 → 重启恢复”的模式，但它依赖旧的 `m16-*` 容器、执行 `FLUSHALL`，且与本轮共享库/最新 schema 不兼容。
- HC-01 提供了可复用的：专用数据库/Redis 容器、最小权限 Gateway 账号、受控 Mock Provider（ok/delay/disconnect/http500/timeout/hold）、双 Gateway JVM、`environment.json`/`db-invariants.json` 产物格式、预算/派发/结算核对 SQL。
- 本轮**不修改** HC-01 runner，只复用其工具函数与 Mock 协议。

## 3. 已有测试覆盖矩阵（F01–F06）

| 故障 | 已有自动化证据 | 机制 | HC-03 真实缺口 |
|---|---|---|---|
| F01 Redis 不可达 | `RedisTokenBucketRateLimiterFailClosedTest`、`RedisTokenBucketRateLimiterTimeoutTest`、`RedisTokenBucketRateLimiterIntegrationTest#redisUnavailableFailsClosed…`、`RedisDailyQuotaLimiterIntegrationTest#redisUnavailableFailsClosed…` | 单测 Mock、静默 ServerSocket、连不上的 Lettuce 端口 | 没有真实停/暂停 Redis 后**恢复**；没有 HTTP 边界“0 Provider 调用”的恢复段 |
| F02 MySQL 不可达 | `DispatchFenceIntegrationTest`（缺预留/非 PLANNED/CLOSING）、`GatewayPrepareInitialConcurrencyIntegrationTest`、`ChatCompletionControllerTest#requiredBudget…` | 真实 MySQL + JDBC 预置状态 | 没有任何 MySQL 停机；没有连接池恢复；没有 TX1/TX2 事务回滚断言 |
| F03 Gateway 崩溃 | `ReservationRecoveryIntegrationTest`（TTL 过期、PENDING_HOLD、SAFE 释放）、`GatewaySafeFailoverIntegrationTest`、`GatewayRequestIdempotencyIntegrationTest` | 真实 MySQL；崩溃=预置行 + 手工调用 recovery | 无进程 kill；无“TX2 已提交但进程消失”窗口；调度器未真实运行 |
| F04 双实例 | 无 | — | 没有第二实例被杀、B 接新请求、SSE 断开、跨实例重放证据 |
| F05 Worker 崩溃 | `GatewaySettlementTransactionIntegrationTest`（5 个注入点回滚）、`GatewaySettlementWorkerTest`（重试耗尽）、`GatewaySettlementWorkerTraversalIntegrationTest`（Mockito 断连） | 真实 MySQL + `GatewaySettlementFailureInjector` / Mockito | 无 JVM kill、无真实数据库中断、无重启后收敛断言 |
| F06 Close × 周期锁 | `DispatchFenceIntegrationTest#closeWins…`、`PeriodLockSharedConcurrencyIntegrationTest`、`GatewayPeriodSharedLockCloseIntegrationTest`、`GatewayFinancialWorkCloseIntegrationTest`、`Known/UnknownPeriodCloseRaceIntegrationTest` | 真实 MySQL、`FOR SHARE`/`FOR UPDATE`、latch 屏障 | 无真实 Close HTTP 调用 + 并发流量的演练；deadlock 重试没有耗尽测试 |

## 4. 本轮新增（不重复实现已有能力）

| 新增 | 类型 | 覆盖 |
|---|---|---|
| `scripts/perf/hc03/`（runner/lib/seed/verify/env） | 可重复故障 harness | F01–F06 的真实容器/进程故障、计时、事件与不变量产物 |
| `GatewayDataSourceOutageIntegrationTest` | Gateway 集成测试（真实 MySQL + JDBC SQL 级故障） | TX1/TX2 数据源故障：无预留/无 DISPATCH_INTENT、连接池恢复、重复失败不重复持有 |
| F06 真实 Close 演练 | harness（真实 Backend HTTP + 真实 Close 服务 + 真实流量） | `PENDING_GATEWAY_FINANCIAL_WORK=FAIL` 证明 Close blockers 看见已提交派发事实；周期返回 OPEN；无重复 posting |
| HC-03 证据目录 | 文档 | 运行说明、矩阵、environment/events/db-invariants、summary、残余风险 |

明确**不新增**：RabbitMQ/Kafka/Outbox、Redis 财务锁、分库分表、K8s、MySQL 主从、Redis Sentinel、生产故障开关、核心财务模型改造。
