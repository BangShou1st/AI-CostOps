# HC-01 合并前审查修正：SSE 建流前取消 permit 与 HC-02 索引建议（2026-11-11）

## 1. 元数据

| 项 | 值 |
|---|---|
| 日期 | 2026-11-11 |
| 责任人 / AI | BangShou1st / Claude Code |
| 关联 PR / Issue | [#177](https://github.com/BangShou1st/AI-CostOps/pull/177) / [#176](https://github.com/BangShou1st/AI-CostOps/issues/176) |
| 审查意见 | [PR #177 issuecomment-6791703837](https://github.com/BangShou1st/AI-CostOps/pull/177#issuecomment-6791703837) |
| 修正前 HEAD | `6ddbef8dab678350b264dbf2fdfb366bf9339929` |
| 范围 | 仅 SSE permit 取消路径 + HC-02 文档更正；**不执行 HC-02**、不引入 MQ、不改预算/账本/结算/关账语义、不改数据库表 |
| 结论 | `PASS`（缺陷已复现并修复；索引建议已更正；相关回归全绿） |

本文件只记录审查后的两项修正，不重跑 HC-01 压测矩阵。压测数字与
[`load-results.json`](load-results.json) 未改动。

## 2. 任务 A：SSE 建流前取消订阅的 permit 泄漏

### 2.1 缺陷路径

`ChatCompletionController.handleRequest` 在 rate/quota/admission 之前取得 stream
permit，然后把“释放”分成两条路径：

- 外层 Mono 的 `onErrorResume`（只覆盖 **error**）；
- SSE body 的 `doFinally`（只有 body 已创建并订阅才会执行）。

审查指出的边界成立：如果客户端在 `rateLimiter.tryAcquire`、`quotaLimiter.tryAcquire`
或 `orchestrator.prepareInitial` **尚未完成**时取消订阅，Reactor 只会在外层链上发出
`doFinally(CANCEL)`，不会调用 `onErrorResume`；原实现中 stream permit 的
`releasePermit` 对 `streamPermitHeld=true` 是 no-op，因此许可永久泄漏。

### 2.2 复现（修复前，逐用例独立运行）

在 [`StreamPermitCeilingIntegrationTest`](../../../../../gateway/src/test/java/com/aicostops/gateway/web/StreamPermitCeilingIntegrationTest.java)
中加入可控异步信号：把目标 admission 阶段替换为 `Mono.never().doOnSubscribe(latch)`，
测试线程在 latch 触发后主动 `Subscription.cancel()`，断言 `activeStreams()` 归零且
`UPSTREAM_CALLS` 为 0（ceiling=2，每个用例单独启动上下文）。

| 用例 | 修复前结果 | 证据 |
|---|---|---|
| `cancelWhileRateLimitPendingReleasesPermit` | **FAIL**：`[active streams never reached 0] expected 0 but was 1`（约 22 s 超时） | 单用例 `-Dit.test=...StreamPermitCeilingIntegrationTest#cancelWhileRateLimitPendingReleasesPermit` |
| `cancelWhileQuotaPendingReleasesPermit` | **FAIL**：同上 | 单用例运行 |
| `cancelWhileAdmissionPendingReleasesPermit` | **FAIL**：同上 | 单用例运行 |

三个阶段均能稳定复现，说明不是单一阶段的偶然问题，而是外层链的所有权缺口。

### 2.3 修复设计

核心是显式化 permit 的**两段生命周期**与所有权移交，而不是让外层 `doFinally` 无条件释放。

[`ChatCompletionController.StreamPermitLease`](../../../../../gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java)
用 `AtomicInteger` 维护三态：

```text
OUTER_OWNED --invokeStream 建好 body--> BODY_OWNED --body 终止--> RELEASED
     \-- 外层 error/cancel/空完成 ------------/          （恰好一次 CAS 释放）
```

- `handOffToBody()`：`OUTER_OWNED -> BODY_OWNED`。只在 `invokeStream` 已经构建好
  `ResponseEntity<Flux<...>>` 后调用；返回 `false` 表示外层已被取消并释放，绝不启动
  一个没有 permit 覆盖的流。
- 外层 `doFinally(signal)`：`releaseFromOuter(signal)`
  - 仍在 `OUTER_OWNED`：error、cancel、或 admission 空完成都释放；
  - 已 `BODY_OWNED`：正常完成/错误都不释放（B04：外层 Mono 在响应头提交时就完成，
    上游仍在流式传输）；
  - 唯一的补漏：`CANCEL` 且 body **尚未被框架订阅**（`bodySubscribed=false`）时释放，
    因为此时响应已不可能写出，body 永远不会执行自己的 `doFinally`。
- SSE body：`doOnSubscribe` 标记已订阅；`doFinally` 调用 `releaseFromBody()`，
  正常完成/异常/客户端取消都释放。
- 所有释放路径都经过同一个 `AtomicInteger` CAS，**恰好一次**；`Semaphore` 不会被
  重复 `release()`。

明确未采用的方案：把外层 `doFinally` 改成“所有终止信号都释放流 permit”。那会在
SSE 响应头提交后立刻放掉仍被上游占用的名额，重新引入 B04 早释放缺陷。

修复后的关键行为：

| 场景 | 释放方 |
|---|---|
| 建流前限流/配额/路由/熔断错误 | 外层 `doFinally(ON_ERROR)` |
| 建流前客户端取消（三阶段） | 外层 `doFinally(CANCEL)` |
| 建流前 admission 空完成 | 外层 `doFinally(ON_COMPLETE)`（仍在 `OUTER_OWNED`） |
| 已建流、响应头已提交 | **不释放**；由 body 继续持有 |
| 流正常结束 / 上游异常 / 客户端取消 | body `doFinally` |

### 2.4 修复后验证

```powershell
cd gateway
.\mvnw.cmd -B "-Dit.test=StreamPermitCeilingIntegrationTest" "-DfailIfNoSpecifiedTests=false" failsafe:integration-test failsafe:verify
```

结果：`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`。其中：

- `heldStreamsOccupyPermitsAndOverCeilingIsRejectedWithoutDispatch`（B04：流仍占用时
  ceiling+1 必须 429，且外层完成不得提前释放）通过；
- `streamPermitIsReleasedWhenRequestFailsBeforeStreamingBody`（既有预流失败回归）通过；
- 三个新增 cancel 用例通过，且均断言取消后 `activeStreams()=0`、无上游调用。

完整 Gateway 回归（`verify`，真实 MySQL 8.4 + Redis 容器）：

```text
单元：Tests run: 232, Failures: 0, Errors: 0, Skipped: 1
集成：Tests run: 116, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS（05:05 min）
```

含 `ChatCompletionControllerTest`（16）、`StreamPermitCeilingIntegrationTest`（5）、
`GatewaySafeFailoverIntegrationTest`（4）、`MimoStreamingIntegrationTest`（9）、
`GatewayStreamingSafeFailoverCancellationIntegrationTest`（2）、
`StreamingLifecycleIntegrationTest`（2）等流式/取消相关类全绿。

## 3. 任务 B：HC-02 索引建议更正

### 3.1 事实核对

- `backend/src/main/resources/db/migration/V19__m12_budget_reservation.sql` 已定义
  `KEY idx_budget_reservation_org_budget (org_id, budget_id, status)`。
- `db-invariants.json` 的 `explains.sumEffectiveReservations` 显示
  `key=idx_budget_reservation_org_budget`、`used_key_parts=[org_id,budget_id,status]`、
  `rows_examined_per_scan=2`、`query_cost=3.44`。
- 因此“为 `budget_reservation` 新增 `(org_id,budget_id,status)` 索引”的建议会创建
  重复 DDL，是错误的；本轮**没有**新增索引、没有改表。

### 3.2 为什么累计 `NO_INDEX_USED` 与末态 EXPLAIN 看起来矛盾

| 口径 | 值 | 性质 |
|---|---|---|
| `topSql`（整轮累计） | 32,696 次、167.24 s、5.115 ms/次 | `performance_schema` 摘要 |
| `SUM_ROWS_EXAMINED` | 133,731,580 | 累计扫描行数（含所有执行） |
| `SUM_NO_INDEX_USED` | 6,519（19.9%） | 未使用索引的**执行次数** |
| 末态 `EXPLAIN` | `key=idx_budget_reservation_org_budget`、估算 2 行、cost 3.44 | 排空后的单次估算 |

解释：

1. **时间点不同**：累计统计覆盖热身、全部场景与结算排空；末态 EXPLAIN 在
   `effectiveHolds` 已收敛为 0（估算约 2 行）时执行，索引范围比负载期小几个数量级，
   选择索引是必然结果，不能代表负载期计划。
2. **语义不同**：`NO_INDEX_USED` 是执行次数，不是扫描行数；`EXPLAIN` 的
   `rows_examined_per_scan` 是估算值，不是实测。两者都不能单独证明索引缺失。
3. **数据分布与统计信息在变**：表从空表增长到约 3.26 万行，有效预占集合与 InnoDB
   持久化统计（按阈值异步刷新）持续变化；非覆盖索引需要按 `reserved_amount` 回表，
   代价模型可能在“索引 + 回表”与“全表扫描”之间切换。19.9% 的占比与轮内计划变化
   相符，而不是“索引不存在”。
4. **归因不完整**：累计扫描 133,731,580 / 32,696 ≈ 4,090 行/次；若全部归到 6,519 次
   no-index 执行则约 20,515 行/次。若这些执行发生在空表/小表阶段，不可能累计出
   该量级，因此它们更可能发生在表已具规模的阶段。封存产物没有逐次执行计划，
   无法把 no-index 精确归因到某一时刻。

### 3.3 修正后的 HC-02 方向

已在 [`findings.md`](findings.md) 与
[`2026-11-11-hc-01-gateway-concurrency.md`](2026-11-11-hc-01-gateway-concurrency.md)
更正：

- 不新增重复索引，不修改表结构；
- 先在真实热点数据规模下采集 `EXPLAIN ANALYZE`（实测扫描行数、回表次数、
  `Handler_read_*`）与负载期执行计划，确认 `idx_budget_reservation_org_budget`
  是否被选中、有效预占集合多大；
- 再评估覆盖索引 `(org_id, budget_id, status, reserved_amount)` 或查询改造的
  A/B 收益，以及新增列/索引对预占 INSERT/UPDATE 的写放大；
- 只有收益可量化且财务不变量不变时才实施；
- 该查询累计 167 s，仅为首要瓶颈 `billing_period ... FOR UPDATE`（13,829 s）的
  **约 1.2%**，优先级仍在账期锁/外键排队之后。

## 4. 边界与残余风险

- 本轮未执行 HC-02，未新增/修改任何索引或表结构，未改预算预占、账本、结算、关账
  语义，未引入 RabbitMQ/Kafka/Outbox。
- 取消回归使用 Spring `MockServerWebExchange` + 可控 `Mono.never()` 直接取消
  控制器订阅，精确覆盖 Reactor 所有权语义；真实 TCP 断连在 WebFlux 中的取消传播
  由既有 `sse_cancel_release` 压测场景与 `MimoStreamingIntegrationTest` 覆盖，
  本轮未新增真实网络断连用例。
- `StreamPermitLease` 仅覆盖进程内 permit；多实例的全局流上限不在此组件职责内
  （HC-01 已记录双实例共享 MySQL 的上限）。
- HC-02 的索引收益/写放大仍未量化（`NOT_VERIFIED`），需要下一轮在真实规模 A/B。

## 5. 复现命令

```powershell
cd gateway
.\mvnw.cmd -B "-Dit.test=StreamPermitCeilingIntegrationTest" "-DfailIfNoSpecifiedTests=false" failsafe:integration-test failsafe:verify
.\mvnw.cmd -B verify          # 单元 + 全部集成（真实 MySQL/Redis 容器）
```
