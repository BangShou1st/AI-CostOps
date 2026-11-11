# HC-02 账期热点锁竞争最小优化与并发正确性验收（2026-11-11）

## 1. 元数据

| 项 | 值 |
|---|---|
| 日期 | 2026-11-11 |
| 责任人 / AI | BangShou1st / Claude Code |
| 关联 Issue / PR | [#178](https://github.com/BangShou1st/AI-CostOps/issues/178) / 本分支独立 PR |
| 基线 | `main = edf416baa8e23a0b66dc00ef5734c6cf40628199`（HC-01 / PR #177 合并） |
| 工作分支 | `perf/hc-02-billing-period-lock` |
| 结论 | **PASS**（吞吐一致改善、财务不变量 0 违例；生产容量 `NOT_VERIFIED`） |
| 影响边界 | 本机受控环境；不改变预算/账本/结算/关账语义；零 DDL、零 MQ |

## 2. 变更概述（根因 → 最小修复）

HC-01 定位到 `billing_period` 行 X 锁：`FOR UPDATE` 平均等待 **205.56 ms**、
67,276 次、累计 13,829 s。TX1（预算准入）与 TX2（派发栅栏）都只需要**读**账期
状态，却用了排他锁，导致同账期所有请求串行。本轮改动：

1. **TX1/TX2 改用共享账期锁**：新增
   [`lockBillingPeriodShared`](../../../../../gateway/src/main/java/com/aicostops/gateway/persistence/GatewayRequestMapper.java)
   （`FOR SHARE`），
   [`BudgetReservationService`](../../../../../gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservationService.java)
   与 [DispatchFenceService](../../../../../gateway/src/main/java/com/aicostops/gateway/request/DispatchFenceService.java)
   使用它；Close/Reopen/Recovery/Settlement 仍持账期 X 锁。
2. **TX1/TX2 使用 `READ COMMITTED`**：共享锁不再把 TX1 串行化到第一次一致性读
   之前，非锁定的 `sumEffectiveReservations` 会漏读并发已提交预占。TX1 在预算行
   X 锁之后必须是当前读；TX2 的失败者必须当前读到赢家的 `DISPATCH_INTENT`。
3. **`lockLineage` 锁定范围收敛**：
   [`FOR UPDATE OF gr, ra`](../../../../../gateway/src/main/java/com/aicostops/gateway/persistence/GatewayUsageMapper.java)，
   不再 X 锁 org 内共享、且 currency/rates 冻结的 `pricing_version` 行。

预算行 X 锁、Close 账期 X 锁、全部表/索引、财务语义均未改动。

## 3. 测试先行与缺陷复现证据

| 复现/回归 | 修复前 | 修复后 |
|---|---|---|
| 共享锁放开不同预算：持有账期锁时另一预算准入 | **Timeout**（TX1 仍被 X 串行） | PASS |
| 共享锁放开 TX2：持有账期锁时执行 dispatch fence | **Timeout** | PASS |
| 共享锁 + **无 RC** 的 stale snapshot：2×80 对 100 | 第二笔 **RESERVED**（有效预占 160 > 100，超扣） | 第二笔 **REJECTED_BUDGET**，有效预占 ≤ 100 |
| TX2 **无 RC** 的 20 并发同键 | 1 次 `GATEWAY_DEPENDENCY_UNAVAILABLE`（应为 409） | 19×`GATEWAY_REQUEST_IN_PROGRESS` + 1 成功 |
| `lockLineage` 持 `pricing_version` X 锁时 finalize | **阻塞/超时** | PASS（0.310 ms avg，见 A/B） |
| CLOSING 后 TX1/TX2 | — | 两者均 fail closed |
| Close 等待 Gateway 共享账期锁 | — | Close 等待、共享锁提交后才 `CLOSING`；之后的 Gateway shared reader 观察到 `CLOSING` |

## 4. 验证环境与执行命令

环境同 HC-01：Windows 11 + Docker Desktop（VM 1.86 GiB）、MySQL 8.4.11
（`REPEATABLE-READ`、128 MiB buffer pool）、真实 Backend/Flyway、受控 Mock Provider、
JDK 21、Node 24。

```powershell
# 单元 + 全部 Gateway 集成（真实 Testcontainers MySQL 8.4 + Redis）
cd gateway
.\mvnw.cmd -B verify

# HC-02 定向并发回归
.\mvnw.cmd -B "-Dit.test=PeriodLockSharedConcurrencyIntegrationTest" "-DfailIfNoSpecifiedTests=false" failsafe:integration-test failsafe:verify
.\mvnw.cmd -B "-Dit.test=GatewayUsageFinalizationIntegrationTest" "-DfailIfNoSpecifiedTests=false" failsafe:integration-test failsafe:verify

# Backend Close / Settlement / 关账回归
cd ../backend
.\mvnw.cmd -B "-Dit.test=GatewayPeriodSharedLockCloseIntegrationTest,KnownPeriodCloseRaceIntegrationTest,UnknownPeriodCloseRaceIntegrationTest,GatewayFinancialWorkCloseIntegrationTest,GatewaySettlementTransactionIntegrationTest,CloseBlockerProviderIntegrationTest" "-DfailIfNoSpecifiedTests=false" failsafe:integration-test failsafe:verify
```

结果：

```text
Gateway：单元 232（1 skipped）+ 集成 121，0 失败，BUILD SUCCESS
  含 PeriodLockSharedConcurrencyIntegrationTest 4/4、GatewayUsageFinalizationIntegrationTest 7/7、
  GatewayRequestIdempotencyIntegrationTest 3/3、BudgetReservationConcurrencyIntegrationTest 5/5、
  StreamPermitCeilingIntegrationTest 5/5
Backend：42/42（含新增 GatewayPeriodSharedLockCloseIntegrationTest 2/2）
```

## 5. A/B 结果摘要

同环境 3 轮 × 8 场景（详见 [`comparison.md`](comparison.md) 与原始
[`baseline/load-results.json`](baseline/load-results.json)、
[`optimized/load-results.json`](optimized/load-results.json)）：

| 场景 | 基线 RPS | 优化 RPS | Δ | 基线 P95 | 优化 P95 | Δ |
|---|---:|---:|---:|---:|---:|---:|
| mixed settlement c50 | 12.8 | 30.4 | +137.4% | 5937.5 | 2489.0 | -58.1% |
| hot c20 | 16.6 | 35.8 | +115.3% | 1882.4 | 869.9 | -53.8% |
| hot c50 | 16.0 | 32.2 | +101.4% | 4734.8 | 2267.5 | -52.1% |
| hot c100 | 15.8 | 27.8 | +76.2% | 8412.7 | 4933.7 | -41.4% |
| multi-budget c100 | 16.1 | 49.4 | +206.3% | 8203.3 | 2481.6 | -69.7% |
| multi-org c100 | 42.4 | 48.3 | +14.0% | 3383.7 | 2581.1 | -23.7% |
| dual c50 | 15.2 | 24.0 | +57.7% | 5516.5 | 3549.0 | -35.7% |
| dual c100 | 15.8 | 22.6 | +43.2% | 9279.1 | 7248.4 | -21.9% |

SQL digest：

- `billing_period ... FOR UPDATE` 254.83 ms / 27,707 次 → `FOR SHARE` **1.96 ms** / 45,773 次；
- `lockLineage` 56.10 ms / 13,835 次 → **0.310 ms** / 22,875 次；
- 新的首要等待是按设计保留的 `budget ... FOR UPDATE` **227.30 ms** / 22,919 次。

财务一致性：两轮均 `converged=true`、`settlementViolations=0`、`budget.violations=0`；
基线 FINAL=SETTLED 10,421，优化 19,109；无 multi-attempt、无重复 FINAL、
`completedWithoutFinal=0`；全部场景 `failures=0`。

## 6. 结论与边界

- **PASS**：8/8 场景一致改善（RPS +14%～+206%，P95 −22%～−70%），账期锁等待
  下降约 99%，无财务不变量违例、无失败场景；优化保留。
- 单预算 c100 仍被**预算行 X 锁**限制在约 28 rps；这是本轮刻意保留的串行点。
  继续提升需要改变预占/可用额度计算策略，属后续独立设计，不在 HC-02 范围。
- **NOT_VERIFIED**：生产容量/SLA、真实网络环境、跨机器/多实例 LB、长期运行稳定性；
  本机受控数字不能外推为生产 QPS。
- 未引入 RabbitMQ/Outbox、Kafka、分库分表、专用锁表、Redis 财务锁；未改任何
  Flyway 历史迁移，本轮零 DDL。

## 7. 交付物

| 产物 | 内容 |
|---|---|
| [`lock-boundary-audit.md`](lock-boundary-audit.md) | 锁图、事务边界、RR/RC 探针、候选方案与安全论证、残余风险 |
| [`comparison.md`](comparison.md) | A/B 方法、结果、SQL/锁/资源证据、解释与复现命令 |
| [`baseline/`](baseline/) / [`optimized/`](optimized/) | 两轮 `environment.json`、`load-results.json`、`db-invariants.json` |
| [`results.json`](results.json) | 机器可读的 A/B 汇总（SHA、JAR、场景、Δ） |

