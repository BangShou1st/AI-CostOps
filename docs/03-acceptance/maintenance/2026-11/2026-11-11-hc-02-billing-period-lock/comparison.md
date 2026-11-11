# HC-02 A/B 对比：账期共享锁、读视图修正与锁范围收敛（2026-11-11）

## 1. 被测版本与环境

| 项 | 基线 | 优化 |
|---|---|---|
| 代码 SHA | `edf416baa8e23a0b66dc00ef5734c6cf40628199`（PR #177 合并后的 main） | `2fe287e0aabc3f3e0238a937968486e7bfda34e9`（本分支） |
| Gateway JAR SHA256 | `5B430C3734F80032CFA8F98134399A99352AD6C722EF7D0C99C3DF4FF52200A2` | `BD1999526B1C8CDB007EA91E6BE4A0AC87871366F1B23B91AF41E8333E04721F` |
| Backend | 同一 JAR（HC-02 不改 Backend），Flyway 真实执行 | 同左 |
| MySQL | 8.4.11，`REPEATABLE-READ`，buffer pool 128 MiB | 同左 |
| 机器 | i9-14900HX / 32 线程 / Docker VM 1.86 GiB | 同左 |
| 种子 | HC-01 固定拓扑：hot / multiBudget(8) / multiOrg(8) | 同左 |

方法：同一 harness（`scripts/perf/hc01/runner.mjs --suite=hc02`）、同一 Mock 行为、
同一限流与线程/连接参数、同一组场景，每场景热身后 3 轮 × 20s（mixed c50 为 1 轮 × 15s）。
成功 RPS 只计 `200`，429/409 不计。每轮前清空 `performance_schema` digest，避免
上次运行的 SQL 统计混入本次证据。两轮运行的 worktree 都带有未提交的
harness/docs 变更（`dirty=true`），但被测量的 Gateway JAR 已用 SHA256 固定，且
优化 JAR 由 `2fe287e` 构建。

## 2. 结果（轮次中位）

| 场景 | 实例 | 并发 | 基线 RPS | 优化 RPS | RPS Δ | 基线 P95 ms | 优化 P95 ms | P95 Δ | 基线 P99 ms | 优化 P99 ms | 行锁等待/1k 成功（基线→优化） |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| hot_fast_mixed_settlement_c50 | 1 | 50 | 12.8 | 30.4 | **+137.4%** | 5937.5 | 2489.0 | **-58.1%** | 6593.6 | 2851.7 | 2864 → 2572 |
| hot_fast_single_c20 | 1 | 20 | 16.6（15.3–17.0） | 35.8（35.1–36.5） | **+115.3%** | 1882.4 | 869.9 | **-53.8%** | 2400.0 | 1053.0 | 2470 → 996 |
| hot_fast_single_c50 | 1 | 50 | 16.0（14.4–16.0） | 32.2（31.1–33.0） | **+101.4%** | 4734.8 | 2267.5 | **-52.1%** | 5564.3 | 2657.6 | 2625 → 998 |
| hot_fast_single_c100 | 1 | 100 | 15.8（14.8–17.7） | 27.8（27.7–30.0） | **+76.2%** | 8412.7 | 4933.7 | **-41.4%** | 9207.7 | 5633.8 | 2603 → 991 |
| multi_budget_single_c100 | 1 | 100 | 16.1（15.8–16.7） | 49.4（46.0–56.2） | **+206.3%** | 8203.3 | 2481.6 | **-69.7%** | 9689.9 | 2755.3 | 2640 → 201 |
| multi_org_single_c100 | 1 | 100 | 42.4（35.3–47.7） | 48.3（44.4–48.7） | **+14.0%** | 3383.7 | 2581.1 | **-23.7%** | 3818.5 | 2862.3 | 933 → 191 |
| hot_fast_dual_c50 | 2 | 50 | 15.2（14.5–15.6） | 24.0（21.9–24.5） | **+57.7%** | 5516.5 | 3549.0 | **-35.7%** | 7278.0 | 4154.5 | 2595 → 998 |
| hot_fast_dual_c100 | 2 | 100 | 15.8（15.5–16.2） | 22.6（21.3–23.3） | **+43.2%** | 9279.1 | 7248.4 | **-21.9%** | 10955.2 | 8490.9 | 2647 → 998 |

8 个场景全部改善；基线 0 失败、优化 0 失败。mixed、multi-budget、c20/c50 的
提升最大；multi-org 已经接近共享 MySQL + 12 DB 线程的上限，提升最小但仍正向。

## 3. 锁与 SQL 证据

`performance_schema.events_statements_summary_by_digest`（整轮累计，含结算排空）：

| 指标 | 基线 | 优化 | 说明 |
|---|---:|---:|---|
| `billing_period ... FOR UPDATE` | 27,707 次 / 7,060.5s / **254.83 ms** | — | 基线首要瓶颈 |
| `billing_period ... FOR SHARE` | — | 45,773 次 / 89.8s / **1.96 ms** | 共享锁；等待下降约 99% |
| `budget ... FOR UPDATE` | 未进 top（被账期 X 锁掩盖） | 22,919 次 / 5,209.5s / **227.30 ms** | 预算行 X 锁，按设计保留；成为新的首要等待 |
| `lockLineage`（`gateway_request ... FOR UPDATE`） | 13,835 次 / 776.2s / **56.10 ms** | 22,875 次 / 7.1s / **0.310 ms** | `FOR UPDATE OF gr, ra` 不再锁共享 `pricing_version` 行 |
| `COMMIT` | 69,195 次 / 19.74 ms | 113,899 次 / 20.39 ms | 提交/刷盘成本未变，属下一层成本 |
| `sumEffectiveReservations` | 13,850 次 / 3.45 ms / 46.1M rows | 22,902 次 / 5.36 ms / 136.4M rows | 索引仍被使用（末态 EXPLAIN）；无 DDL |

`Innodb_row_lock_waits` 按成功请求归一化后，热点单预算从约 2.6k 次/千成功降到
约 1.0k；multi-budget 从 2.6k 降到 201，说明同账期不同预算真正并行。
`data_lock_waits` 的 `blockingObjects` 采样仍显示 `billing_period` 有等待者，但该
采样把“阻塞事务持有的全部记录锁”都计入（同一事务同时持有账期 S 与预算 X 时，
等待预算 X 的会话也会被计入账期 S），因此本报告以 SQL digest 的等待时间为准，
不把 `blockingObjects` 的 waiter 数当作单一行的锁队列长度。

## 4. 数据库与资源压力

| 场景 | 基线 DB queue 峰值 | 优化 DB queue 峰值 | 基线 data_lock_waits 峰值 | 优化 data_lock_waits 峰值 |
|---|---:|---:|---:|---:|
| hot c20 | 9 | 10 | 66 | 36 |
| hot c50 | 39 | 38 | 66 | 55 |
| hot c100 | 89 | 89 | 66 | 55 |
| multi-budget c100 | 89 | 89 | 66 | 3 |
| multi-org c100 | 90 | 89 | 8 | 2 |
| dual c50/c100 | 30 / 60 | 28 / 72 | 276 / 276 | 253 / 253 |
| mixed c50 | 39 | 39 | 78 | 36 |

Hikari 活跃峰值仍为 12（池满），说明 DB 线程/连接仍是上限；`gateway_db_queued`
没有恶化。Gateway CPU 仍然低（样本 < 10% 单核），Redis EVALSHA 约 40 µs/次，
不是瓶颈。

## 5. 财务与并发不变量

- 两轮均 `converged=true`、`settlementViolations=0`、`budget.violations=0`。
- 基线：org1 FINAL **10,421 = SETTLED 10,421**；优化：org1 FINAL **19,109 = SETTLED 19,109**，
  其余 7 个组织同样全部收敛；无 multi-attempt、无 duplicate FINAL、
  `completedWithoutFinal=0`。
- 全部场景 `failures=0`，无 429/409 被计入吞吐。
- 预算不超扣、同键/派发/关账语义由定向测试覆盖（见验收报告），A/B 只跑账期矩阵，
  未关闭任何限流或保护。

## 6. 解释与边界

- 账期 X 锁移除后，单预算热点仍被**预算行 X 锁**串行化（227 ms 等待）；这是
  HC-02 明确保留的“预算判定串行点”，也是单预算 c100 只能到约 28 rps 的原因。
  继续提升需要改变预占/可用额度的计算策略，不在本轮范围。
- 同账期多预算从 ~16 提升到 ~49 rps（+206%），直接证明账期锁是此前的共享瓶颈。
- 多组织场景此前已绕过账期热点（8 个账期），优化只减少同组织内的 S/X 排队，
  因此 +14%；双实例仍受共享 MySQL 限制，提升 43–58%。
- 本机 Docker VM 1.86 GiB、MySQL buffer pool 128 MiB、压测客户端与 Gateway 同机；
  数字仅用于同环境相对比较，不是生产容量结论。
- `sumEffectiveReservations` 仍是 TX1 内的次级成本（5.36 ms），但不再有累计
  `NO_INDEX_USED` 矛盾结论；本轮未做 DDL。

## 7. 复现

```bash
# 1) 基线 worktree（main=edf416b）
git worktree add ../AI-CostOps-hc02-baseline edf416b
(cd ../AI-CostOps-hc02-baseline/gateway && ./mvnw -B -DskipTests package)
cp ../AI-CostOps-hc02-baseline/gateway/target/gateway-1.1.0.jar .local-dev/hc02/gateway-baseline.jar

# 2) 本分支构建
(cd gateway && ./mvnw -B -DskipTests package)
cp gateway/target/gateway-1.1.0.jar .local-dev/hc02/gateway-optimized.jar

# 3) A/B（各 3 轮）
node scripts/perf/hc01/runner.mjs --suite=hc02 --rounds=3 --variant=baseline \
  --code-sha=edf416baa8e23a0b66dc00ef5734c6cf40628199 \
  --gateway-jar=.local-dev/hc02/gateway-baseline.jar --skip-build \
  --output=docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-02-billing-period-lock/baseline
node scripts/perf/hc01/runner.mjs --suite=hc02 --rounds=3 --variant=optimized \
  --code-sha=2fe287e0aabc3f3e0238a937968486e7bfda34e9 \
  --gateway-jar=.local-dev/hc02/gateway-optimized.jar --skip-build \
  --output=docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-02-billing-period-lock/optimized

# 4) 对比
node scripts/perf/hc02/compare.mjs \
  docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-02-billing-period-lock/baseline/load-results.json \
  docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-02-billing-period-lock/optimized/load-results.json \
  docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-02-billing-period-lock
```

原始数据：[`baseline/load-results.json`](baseline/load-results.json)、
[`optimized/load-results.json`](optimized/load-results.json)、
[`results.json`](results.json)、[`baseline/db-invariants.json`](baseline/db-invariants.json)、
[`optimized/db-invariants.json`](optimized/db-invariants.json)。

## 8. 结论

**PASS**：8 个场景一致改善（RPS +14%～+206%，P95 −22%～−70%），
两轮财务不变量 0 违例、结算完全收敛、0 场景失败；账期锁等待从 254.8 ms 降到
1.96 ms，`lockLineage` 从 56.1 ms 降到 0.31 ms。新的首要等待是**按设计保留**的
预算行 X 锁（227.3 ms），不是本轮回归。建议保留优化；下一轮若继续追单预算吞吐，
应针对预算预占/可用额度计算策略另行设计（不在 HC-02 本轮范围）。
