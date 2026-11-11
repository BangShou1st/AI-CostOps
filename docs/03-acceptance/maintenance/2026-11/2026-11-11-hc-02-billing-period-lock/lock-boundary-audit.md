# HC-02 账期热点锁边界审计与最小优化设计（2026-11-11）

## 1. 元数据

| 项 | 值 |
|---|---|
| 日期 | 2026-11-11 |
| 责任人 / AI | BangShou1st / Claude Code |
| 关联 Issue | [#178 HC-02｜账期热点锁竞争最小优化与并发正确性 A/B 验证](https://github.com/BangShou1st/AI-CostOps/issues/178) |
| 基线 | `main = edf416baa8e23a0b66dc00ef5734c6cf40628199`（PR #177 合并） |
| 工作分支 | `perf/hc-02-billing-period-lock` |
| 审计对象 | `BudgetReservationService` TX1、`DispatchFenceService` TX2、`GatewayRequestMapper`、`PeriodCloseService`/`BillingPeriodCloseService`、`GatewaySettlementService`、`BillingPeriodFinancialWriteFenceService`、`GatewayUsageFinalizationService`、`ReservationRecoveryService`、`SafeReservationReleaseService`、账期外键与索引 |
| 第一道门结论 | **PASS**：完成只读审计与 DB 语义探针；发现并证明“仅把 TX1 改成 `FOR SHARE` 会超扣”的隐藏依赖，最终方案为 **TX1/TX2 共享账期锁 + TX1 READ COMMITTED + 预算行 X 锁不变 + 零 DDL** |
| 实现状态 | 待第二道门（测试先行）与第三道门（A/B）完成后回填 |

## 2. 一句话结论

`billing_period` 的 X 锁是 HC-01 单账期吞吐平台化的直接原因（`FOR UPDATE` 平均等待
**205.56 ms**、67,276 次、累计 13,829 s）。它同时承担了一个**隐性正确性职责**：
在 REPEATABLE READ 下把 TX1 串行化到“第一次一致性读”之前，使非锁定的
`sumEffectiveReservations` 不会漏读并发已提交的预占。因此不能只把 `FOR UPDATE`
替换为 `FOR SHARE`；必须同时让 TX1 在预算行 X 锁之后做**当前读**（本方案用
`READ COMMITTED`），才能既放开并发又不超扣。

实现期还发现第二个同类问题：TX2 的失败者若仍用 RR 旧快照重读 request，会把并发
赢家的 `DISPATCH_INTENT` 误判为 `RESERVED` 并返回 503；TX2 同样改为
`READ_COMMITTED`，既有 20 并发同键测试从 RED 变 GREEN。

## 3. 锁图与事务边界

### 3.1 全局锁顺序

经代码与 `information_schema` 核对，账期相关写的实际顺序如下（箭头 = 先持有）：

```text
Close begin : organization X -> billing_period X -> period_close_run / audit
Close final : billing_period X -> period_close_run -> close_check
Reopen      : billing_period X -> period_close_run read -> audit
TX1 (预算)  : billing_period X(现) -> budget X -> budget_reservation insert -> gateway_request update
TX2 (派发)  : billing_period X(现) -> gateway_request update -> gateway_route_attempt update
Recovery    : billing_period X -> budget X -> budget_reservation X -> gateway_request/attempt
SafeRelease : billing_period X -> budget X -> budget_reservation X -> attempt check
Settlement  : billing_period X -> budget X -> commitment X -> reservation X -> settlement -> ledger -> actual
Resolution  : billing_period X -> budget X -> reservation X -> financial resolution
```

预算行（`budget`）X 锁是 TX1 与 Actual 变更、释放、结算之间的真实串行点；账期行
X 锁则把**所有**上述事务串成一条队列。两者都在 commit 时释放。

### 3.2 TX1（`BudgetReservationService.admitBlocking`）

1. `lockBillingPeriod` = `SELECT status FROM billing_period WHERE id=? AND org_id=? FOR UPDATE`；
2. 解析精确 scope 预算，否则 ORG 回退；`lockBudgetById` = `budget ... FOR UPDATE`；
3. 幂等检查、定价上界、`sumEffectiveReservations`（**普通 SELECT**）、插入 ACTIVE 预占、
   `markRequestReserved`（把 `gateway_request.billing_period_id` 从 NULL 写成本账期）。
4. 事务模板默认隔离级别 = `ISOLATION_DEFAULT` → MySQL 8.4 默认 **REPEATABLE READ**。

### 3.3 TX2（`DispatchFenceService.commitDispatchFence`）

`@Transactional`；先 `lockBillingPeriodShared ... FOR SHARE` 验 OPEN，再校验
reservation 仍是 ACTIVE，最后把 request/attempt 置为 `DISPATCH_INTENT` 并 commit。
Close 与 dispatch 靠同一账期行锁决出先后。

实现期发现并修复的第二个读视图问题：同一幂等身份的失败者可能在赢家提交
`DISPATCH_INTENT` 后执行 `markRequestDispatchIntent`（0 行受影响），随后用默认 RR
的旧快照重读 request，看到的是 `RESERVED` 而不是 `DISPATCH_INTENT`，于是把
“正在派发”误报成 `GATEWAY_DEPENDENCY_UNAVAILABLE` 而不是
`GATEWAY_REQUEST_IN_PROGRESS`。既有
`GatewayRequestIdempotencyIntegrationTest#concurrentSameIdentityProducesOneRequestOneAttemptOneDispatch`
能稳定复现（20 并发、期望 19 个 409）。因此 TX2 也必须使用
`Isolation.READ_COMMITTED`，让失败者的状态重读是当前读。

### 3.4 Close / Reopen

- `PeriodCloseService.beginOrResume`：先 `organization ... FOR UPDATE`，再
  `billing_period ... FOR UPDATE`，写 `CLOSING`；blockers 在事务**外**评估，随后
  `finalizeClose` 再取账期 X、写 checks、写 `CLOSED` 或回 `OPEN`。
- 关键安全属性：`CLOSING` 提交后，任何新的 TX1/TX2 都必须看到 `CLOSING` 并 fail
  closed；`begin` 的 X 请求必须等待所有已持有账期锁的财务写事务提交，且之后的
  新读者排在其后（见 §7.3 的公平性探针）。
- `reopen` 只取账期 X，回 `OPEN` 并递增 generation。

### 3.5 Settlement / Ledger / Resolution

`GatewaySettlementService.settleInTransaction` 第一把锁就是账期 X
（`BillingPeriodFinancialWriteFenceService.lockById` → `selectByIdForUpdate`），
然后预算 X、commitment X、reservation X、settlement X，再写 Ledger 与 Budget Actual。
`GatewayFinancialResolutionService` 同序。它们与 TX1/TX2 的争用是 HC-01 mixed 场景
的来源。

### 3.6 Recovery / SafeRelease

`ReservationRecoveryService.recoverOneBlocking` 与 `SafeReservationReleaseService`
都先取账期 X，再预算 X、reservation X；因此释放/回收与 TX1 的“有效预占集合”变更
天然串行。它们不是热路径，本轮不改其锁模式。

### 3.7 账期外键与共享锁

`information_schema.KEY_COLUMN_USAGE` 显示 8 张表引用 `billing_period(id, org_id)`：

```text
budget, budget_reservation, gateway_request, gateway_settlement,
ledger_posting, period_close_run, reconciliation_run, reconciliation_adjustment
```

需要更正 HC-01 报告中的两处机制描述（不影响其测量数据）：

- `gateway_request` 的 INSERT 把 `billing_period_id` 写成 **NULL**，既不检查也不锁
  账期行；真正取账期 S 锁的是 TX1 的 `markRequestReserved`、TX2 的
  `markRequestDispatchIntent` 与 `budget_reservation` 的 INSERT。
- `gateway_usage_fact`（V20）**没有** `billing_period_id` / 账期外键；usage 的
  `INSERT` 不会取账期行锁。带账期外键的是 `gateway_settlement` / `ledger_posting` 等。

因此当前 X 锁队列的主体是 TX1/TX2/Settlement/Close 自己对账期行的 X 请求，而不是
“每个请求 INSERT 都取 S 锁”。

## 4. 关键发现：X 锁的隐性正确性职责

### 4.1 探针：REPEATABLE READ + `FOR SHARE` 会超扣

在 MySQL 8.4（`hc02_probe` 临时库，非项目表）复刻 TX1 的真实顺序：
`FOR SHARE` 账期 → 普通 SELECT budget（建立 read view）→ `budget FOR UPDATE`（等待）
→ `SUM(budget_reservation)`（普通 SELECT）→ 插入 80。

```text
A: 账期 FOR SHARE -> 读 budget -> 锁 budget -> 插入 80 -> sleep 4s -> commit
B: 账期 FOR SHARE -> 读 budget（read view 固定）-> 锁 budget（等 A）-> SUM -> 插入 80
结果：B sees sum=0.00；最终 2 条 ACTIVE、合计 160.00 > 预算 100
```

原因：MySQL RR 的 read view 在事务第一次一致性读时创建。当前代码的账期 **X** 锁
使 B 在 `selectBudgetByIdentity` 之前就必须等待 A 提交，所以 B 的 read view 天然
晚于 A 的预占；一旦改成 `FOR SHARE`，B 可以在 A 提交前建立 read view，随后即使拿到
预算 X 锁，普通 `SUM` 仍看不到 A 已提交的 ACTIVE 行。

现有 `BudgetReservationConcurrencyIntegrationTest` 的 80+80 / 50+50 用例之所以能
通过，正是因为账期 X 锁把整个 TX1 串行化了；它不是“`SUM` 本身安全”的证据。

### 4.2 探针：READ COMMITTED 下同一序列正确

把两个会话都设为 `READ COMMITTED` 重跑同一序列：

```text
B sees sum=80.00（A 已提交）-> B 的 80 会被 20 的可用额度拒绝
最终 1 条 ACTIVE、合计 80.00
```

`READ COMMITTED` 让 `SUM` 成为语句级当前读；预算行 X 锁保证同一预算的 TX1 串行，
因此“锁内看到最新已提交有效预占”成立。

### 4.3 结论

最小安全方案必须同时满足：

1. 账期锁降为共享锁（TX1、TX2），让同一账期的不同预算/不同请求并发；
2. TX1 在预算行 X 锁之后的有效预占读取必须是当前读 → TX1 使用 `READ COMMITTED`；
3. 预算行 X 锁、Close 账期 X、Settlement 账期 X 保持不变；
4. 不引入任何 DDL。

## 5. 候选方案与决策

| 方案 | 内容 | 结论 |
|---|---|---|
| **A（采用）** | TX1/TX2 账期 `FOR SHARE` + TX1 `READ COMMITTED` | 最小、零 DDL；共享锁放开并发，RC 恢复“锁内当前读”；预算 X 仍串行化同预算 |
| B | TX1 `FOR SHARE` + `sumEffectiveReservations ... FOR SHARE` | **拒绝**：聚合锁定读会在 reservation 行上加 S 锁；usage finalization 已持有 request 行 X 再取 reservation X，TX1 则取 reservation S 后再取 request X，形成新的死锁环 |
| C | 预算查找改为 `FOR UPDATE`（用查找本身拿预算锁） | **拒绝**：缺失预算时产生 gap lock，改变不存在行/ORG 回退路径的锁行为；属更大 access-path 变更，收益不明确 |
| D | 专用锁行/版本行/锁分片 | 超出本轮最小范围；需 DDL 与新不变量，留作 A 被证伪后的备选 |
| E | Redis/应用层读写闸门 | 违反“Redis 不授权金额”的边界；拒绝 |
| F | 保持 X 锁、仅缩短临界区 | 不解决排队根因（205 ms 是锁等待而非计算） |

方案 A 的 TX2 同改 `FOR SHARE` 的理由：若 TX2 继续 X，它会成为新的全局串行点，
并且在 X 等待期间阻塞后续 TX1 的 S 请求，形成“每批 dispatch 一次 stop-the-world”；
TX2 只读账期状态、不修改账期行，S 与 Close 的 X 语义完全兼容（见 §7）。

## 6. 最小代码改动（计划）

1. `GatewayRequestMapper` 新增 `lockBillingPeriodShared`：
   `SELECT status FROM billing_period WHERE id=? AND org_id=? FOR SHARE`；
   保留原 `lockBillingPeriod`（X）给 Recovery/SafeRelease。
2. `BudgetReservationService`：TX1 改用 `lockBillingPeriodShared`；
   `TransactionTemplate` 显式 `ISOLATION_READ_COMMITTED`。
3. `DispatchFenceService`：TX2 改用 `lockBillingPeriodShared`，并显式
   `@Transactional(isolation = Isolation.READ_COMMITTED)`（同一幂等身份的失败者
   必须当前读到赢家的 `DISPATCH_INTENT`，返回 409 而不是误报 503）。
4. 不改预算/账本/结算/关账语义；不改任何表、索引、迁移；不引入 MQ。

## 7. 安全性论证

### 7.1 Close 与 TX1/TX2 的序列化

- Close `begin` 的账期 X 与 TX1/TX2 的 S 互斥：Close 等待所有已持有 S 的事务提交
  后才写 `CLOSING`；`CLOSING` 提交后，新 TX1/TX2 的 S 排在 X 之后，读到 `CLOSING`
  并 fail closed。
- 因此不存在“Close 检查完 blockers 后，新 DISPATCH_INTENT/财务写绕过”的窗口：
  新的 TX1/TX2 在 `CLOSING` 之后根本不能提交；已提交的预占/派发在 `CLOSING` 之前
  一定完成，并被 blockers 的普通读看到。
- Close `finalize` 的 X 同样与 S 互斥；`CLOSED` 后 TX1/TX2 读不到 OPEN。

### 7.2 TX2 的 dispatch fence

TX2 只读账期状态并更新 request/attempt；账期 FK 写入同值只取 S，不会升级为 X。
所有会把 reservation 从 ACTIVE 迁走的路径（SafeRelease、Recovery、Settlement、
Resolution）都先取账期 X，因此 TX2 持 S 期间它们不能并发修改；TX2 看到的
reservation 状态是稳定的。Close 若先拿到 X，TX2 随后读 `CLOSING` 拒绝；TX2 若先
提交，`DISPATCH_INTENT` 一定早于 `CLOSING`，blocker 可见。

TX2 使用 READ COMMITTED 是**并发失败者语义**的一部分：同一幂等身份可能同时进入
TX2，赢家把 request 置为 `DISPATCH_INTENT` 后，失败者的 `markRequestDispatchIntent`
影响 0 行；若随后仍用 RR 旧快照重读 request，会看到旧的 `RESERVED` 并抛出
`GATEWAY_DEPENDENCY_UNAVAILABLE`。RC 使重读看到已提交的 `DISPATCH_INTENT`，返回
`GATEWAY_REQUEST_IN_PROGRESS`（409）。该场景由既有 20 并发同键测试稳定复现，
改为 RC 后通过。

### 7.3 写者公平性（防止 Close 被 S 饿死）

MySQL 探针：A 持 S；B 请求 X 排队；C 随后请求 S。C **没有**插队，而是排在 B 之后
（C 的总耗时约等于 A 的剩余持锁时间）。InnoDB 的锁队列保证等待中的 X 不会被
新来的 S 无限饿死；Close 的 X 只等待**已经持有** S 的事务，其数量受 Gateway
`db-threads=12`（单实例）约束，而不是受 100/200 客户端并发约束。

### 7.4 READ COMMITTED 的边界

- 只作用于 TX1 的 `TransactionTemplate`，不影响 TX2、usage finalization、结算。
- 预算行 X 锁保证同预算 TX1 串行；所有有效预占变更路径都持预算 X（§3.1/§3.6），
  因此 RC 下的 `SUM` 不会漏读或重复读。
- 账期状态、预算行、reservation 行分别用 locking read（当前读），不受 RC 影响。
- 幂等/重复键语义不变：`findByRouteAttempt` 在 RC 下看到更新后的最新提交，
  唯一键仍兜底并发。

### 7.5 残余风险（需测试与 A/B 验证）

1. **Close 与 TX1 的组织行死锁**：Close 先取 organization X 再取账期 X；TX1 持账期 S
   后在 `budget_reservation` INSERT 取 organization S，理论上可形成环。该环在
   HC-01 的 X 方案中已存在；Close 已有 3 次 deadlock retry。需要测试证明：
   失败者只可能是单个请求（fail closed），不会产生财务不一致或绕过。
2. **Settlement 的 X 与 S 的批处理争用**：结算仍用账期 X，会等待当前 S 持有者并在
   持锁期间阻塞新准入；`mixed c50` 必须量化，若明显回退则不保留该改造。
3. **生产环境假设**：本方案依赖 MySQL InnoDB + `READ COMMITTED` 会话级隔离；
   HC-01/生产均为 MySQL 8.4。若部署使用非 InnoDB 或改变隔离级别语义，需重新评估。
4. **多实例**：每实例 12 DB 线程；双实例 S 持有者 ≤24。Close/Settlement 的等待上限
   仍受此约束，但需在 dual 场景实测。

## 8. 测试计划（测试先行）

| 层 | 用例 | 目的 |
|---|---|---|
| Gateway | `BudgetReservationSharedPeriodLockIntegrationTest#staleSnapshotAcrossSharedLockCannotOverspend` | 用可控 barrier 卡住第二笔 TX1 的预算查找，A 提交 80 后再放行；断言恰好 1 笔 RESERVED、1 笔 REJECTED_BUDGET、有效预占 ≤ 100。**未加 RC 时该用例必须 RED** |
| Gateway | 同账期不同预算并发准入（S 持有期间可完成） | 证明共享锁真的放开并发，而不是仍然 X 串行 |
| Gateway | TX2 在另一 TX1 持 S 期间可完成 | 证明 TX2 的共享锁不会成为新串行点 |
| Gateway | `CLOSING`/`CLOSED` 后 TX1/TX2 均 fail closed | 关账写栅栏 |
| Gateway | 既有 `BudgetReservationConcurrencyIntegrationTest`（80+80、50+50、8 并发、Actual 竞争、同键） | 同预算不超扣、幂等、Actual 竞争 |
| Gateway | 既有 `GatewayRequestIdempotencyIntegrationTest#concurrentSameIdentityProducesOneRequestOneAttemptOneDispatch`（20 并发同键） | TX2 失败者必须返回 409 而非 503；未加 TX2 RC 时 RED（实测 1 次 `GATEWAY_DEPENDENCY_UNAVAILABLE`） |
| Backend | Close `begin` 等待 Gateway 账期 S，提交后再 `CLOSING` | Close 与 TX1/TX2 的序列化 |
| Backend | Close blocker 在 `CLOSING` 前看到已提交的 ACTIVE reservation | 无绕过窗口 |
| Backend | 既有 `GatewaySettlementTransactionIntegrationTest`（settlement vs close） | 结算与 Close 既有语义不变 |
| Backend | 既有 `KnownPeriodCloseRaceIntegrationTest` / `UnknownPeriodCloseRaceIntegrationTest` | 组织锁/未知账期写栅栏不变 |
| 全量 | Gateway `verify` + Backend 定向集成 | 无回归 |

## 9. A/B 计划（第三道门）

复用 `scripts/perf/hc01`，固定 Mock/限流/DB/资源参数与种子，新增 `--suite=hc02`
只跑本轮需要的场景（避免重跑整张 HC-01 矩阵）：

- `hot_fast_mixed_settlement_c50`（1 轮，结算运行中）
- `hot_fast_single_c20/50/100`（各 3 轮）
- `multi_budget_single_c100`（3 轮）
- `multi_org_single_c100`（3 轮）
- `hot_fast_dual_c50/100`（各 3 轮）

优化前 = `edf416b` 基线 JAR；优化后 = 本分支 JAR；同机、同种子、同端口。
记录成功业务 RPS、P50/P95/P99、`performance_schema` 行锁等待、`gateway_db_queue`、
Hikari、死锁/超时、预算/结算/Ledger 不变量与最终收敛。429/409 不计成功吞吐。
若吞吐无明显改善或 mixed 明显回退，回退代码、保留实测报告。

## 10. 明确不做

RabbitMQ/Outbox、Kafka、Redis 财务锁/闸门、分库分表、专用锁表、锁分片、多 Region、
Kubernetes、业务新功能、全仓重构、修改历史 Flyway、重复创建
`budget_reservation(org_id,budget_id,status)` 索引。

## 11. 参考

- HC-01 基线：[`../2026-11-11-hc-01-gateway-concurrency/`](../2026-11-11-hc-01-gateway-concurrency/2026-11-11-hc-01-gateway-concurrency.md)
- HC-01 审查修正：[`../2026-11-11-hc-01-gateway-concurrency/2026-11-11-hc-01-review-followup.md`](../2026-11-11-hc-01-gateway-concurrency/2026-11-11-hc-01-review-followup.md)
