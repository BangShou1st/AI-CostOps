# HC-04 热点预算优化与网关多级缓存

> 日期：2026-11-25（2026-11-26 WP7/WP8 收口）
> 状态：**STAGE_A_COMPLETE / STAGE_B_FAIL_DEFERRED**（经仓库所有者批准的维护期例外改造，停在阶段 A，见 §4.4 与 §11）
> - 阶段 A（Flyway `V34`、A1–A4、C1、R1、R2）：已完成开发、全量集成/架构回归及同机 ABAB 压测验收（PASS）
> - 阶段 B（§4 B1 热点预算拆子账户分桶）：WP7 正确性门禁 **FAIL**，保持未启用并停在阶段 A
> 证据：[HC-04 验收报告](../../../03-acceptance/maintenance/2026-11/2026-11-26-hc-04-hot-budget-cache/comparison.md) · [WP7 分桶正确性门禁](../../../03-acceptance/maintenance/2026-11/2026-11-26-hc-04-hot-budget-cache/bucket-gate.md) · [HC-02 A/B 对比](../../../03-acceptance/maintenance/2026-11/2026-11-11-hc-02-billing-period-lock/comparison.md)
> 取代：同日作废的「额度租约 + Redis 段内分配」草案（已删除，不实施）

---

## 0. 背景与已测事实

HC-02 优化后，单预算热点仍被预算行 X 锁串行化：

| 事实 | 数据 | 来源 |
|---|---|---|
| 单预算成功 RPS | c20 35.8 / c50 32.2 / c100 27.8 | comparison.md §2 |
| P95 / P99（c20） | 870 / 1053 ms | 同上 |
| `budget ... FOR UPDATE` 平均等待 | 227.30 ms（22,919 次） | comparison.md §3 |
| 锁内 `sumEffectiveReservations` | 5.36 ms/次，约 6,000 行/次 | 同上 |
| `COMMIT` | 约 20.4 ms/次，约 5 次/请求 | 同上 |
| 每请求 DB 往返 | 约 75–80 次（Gateway ~54 条 SQL + 约 21 次 `SELECT @@session.transaction_read_only`） | 2026-11-25 代码审计 |
| 配置类缓存 | **无**：凭据、模型目录（重复查 2 次）、路由策略/候选、定价费率（TX1 与计量各 1 次）、连接配置、Provider 凭据（每次 AES 解密）全部每请求查 MySQL | 同上 |
| Hikari 活跃连接 | 12/12 打满；Gateway CPU < 10%；Redis EVALSHA ≈ 40 µs | comparison.md §4 |

推论：单预算吞吐 ≈ 1 ÷ 预算行锁持有时间（约 28 ms → 约 35 rps）；c100 低于 c20 是因为约 8 个 DB 连接卡在锁等待上。多预算场景则主要受连接池与每请求往返次数限制。

## 1. 目标

1. 缩短预算行锁持有时间，并让锁等待不占用 DB 连接（§3）。
2. 去掉每请求的只读配置查询，降低 DB 往返与连接池压力（§5）。
3. 在保证财务正确性的前提下，让单预算热点突破单行锁上限（§4，可选阶段）。
4. Redis 承担缓存、失效广播、延迟队列、幂等快路径等真实职责（§5、§6）。
5. 全部改造有同脚本、同环境的 A/B 压测数据（§8）。

**不可违反**：MySQL 仍是唯一金额授权方，Redis 不独立授权任何支出；`DISPATCH_INTENT` 先持久化再调 Provider；Gateway 不写 actual/committed/Ledger/Settlement；`missing usage ≠ zero cost`。

## 2. 方案总览

| 编号 | 改造 | 方向 | 优先级 |
|---|---|---|---|
| A1 | `budget.reserved_amount` 冗余计数 + 条件更新原子扣减，替代锁内 SUM | MySQL | P0 |
| A2 | 定价查询与金额计算移到锁外；`useGeneratedKeys` 替代 `LAST_INSERT_ID` | MySQL | P0 |
| A3 | 按预算的应用内信号量，锁等待不占 DB 连接 | 并发 | P0 |
| A4 | JDBC `useLocalSessionState=true` 等会话参数，去掉多余往返 | MySQL | P0 |
| C1 | Caffeine + Redis 两级缓存 + Pub/Sub 失效 | Redis | P0 |
| R1 | 超时预占 ZSET 延迟队列唤醒 | Redis | P1 |
| R2 | 幂等快路径 `SET NX` | Redis | P1 |
| B1 | 热点预算拆子账户（分桶） | MySQL | P2，A 全部验收后再做 |

## 3. 阶段 A：缩短预算行锁

### A1 冗余计数 + 条件更新

新迁移 `V34__hc04_budget_reserved_amount.sql`：

```sql
ALTER TABLE budget ADD COLUMN reserved_amount DECIMAL(20,8) NOT NULL DEFAULT 0;
-- 回填：当前有效预占
UPDATE budget b SET reserved_amount = (
  SELECT COALESCE(SUM(br.reserved_amount),0) FROM budget_reservation br
  WHERE br.org_id=b.org_id AND br.budget_id=b.id AND br.status IN ('ACTIVE','PENDING_HOLD'));
ALTER TABLE budget ADD CONSTRAINT chk_budget_reserved_nonneg CHECK (reserved_amount >= 0);
```

准入改为「库存扣减」写法（仍在 TX1、RC、账期 FOR SHARE 之后）：

```sql
UPDATE budget
SET reserved_amount = reserved_amount + #{amount}, version = version + 1
WHERE org_id=#{orgId} AND id=#{budgetId} AND status='ACTIVE'
  AND total_amount - actual_amount - committed_amount - reserved_amount >= #{amount}
```

- 影响行数 1 → 插入 `budget_reservation(ACTIVE)`；0 → 预算不足（REJECTED_BUDGET）或状态非 ACTIVE（再读一次区分）。
- 这条 UPDATE 本身取得预算行 X 锁，取代原来的 `lockBudgetById` + `sumEffectiveReservations` 两次往返。
- `sumEffectiveReservations` 不再出现在热路径；保留给校验脚本与对账。

**必须同步维护的 5 处**（审计确认这些位置都已持有预算行锁，在同一事务内对 `reserved_amount` 做 `-amount`）：

| 动作 | 位置 |
|---|---|
| 预占 +amount | `gateway/.../budget/BudgetReservationService.java`（原 :162 插入处） |
| 释放 −amount | `gateway/.../budget/SafeReservationReleaseService.java`（:44，锁在 :30） |
| 过期回收释放 −amount | `gateway/.../budget/ReservationRecoveryService.java`（:197，锁在 :176） |
| 结算 FINALIZED −amount（同事务 actual += 实际成本） | `backend/.../gatewaysettlement/application/GatewaySettlementService.java`（:241，锁在 :136）；SQL 在 `GatewayReservationSettlementMapper.java:46` |
| 对账 FINALIZED/RELEASED −amount | `backend/.../reconciliation/application/GatewayFinancialResolutionService.java`（:397/:399，锁在 :291）；SQL 在 `GatewayReservationSettlementMapper.java:64` |

`ACTIVE → PENDING_HOLD` 两种状态都计入有效占用，`reserved_amount` 不变。

**一致性保障**：
- 每次 `-amount` 必须以「预占状态条件更新影响 1 行」为前提（version fencing 已有），保证同一预占只减一次。
- 新增校验：`budget.reserved_amount = Σ 有效预占`。写进压测 verify 脚本与一个集成测试；另加一个只读的定时巡检（发现漂移打指标与日志，不自动修）。
- ArchUnit：Gateway 只允许修改 `budget.reserved_amount`，仍禁止修改 actual/committed。

### A2 锁外预计算

- `findPricingRates` + `ReservationAmountCalculator.calculate` 移到 TX1 之前（定价版本冻结，结果按 pricingVersionId 走 C1 缓存）。
- `findByRouteAttempt` / `countEffectiveHolds` 在锁前做一次快路径判断；锁内依靠 `UNIQUE(org_id, route_attempt_id)` 与 `uq_budget_reservation_effective` 冲突后的收敛分支兜底（现有 :167 逻辑保留）。
- `insertActiveReservation` 改 `useGeneratedKeys`，去掉 `LAST_INSERT_ID` 往返。
- `markRequestReserved` 是否可移出 TX1：先核对 recovery 是否依赖 `RESERVED` 状态，**不确定就不动**，并在报告中写明结论。

目标：锁内只剩「条件 UPDATE budget + INSERT reservation (+ UPDATE request) + COMMIT」。

### A3 按预算信号量

- Gateway 进程内 `ConcurrentHashMap<budgetId, Semaphore>`（有界，Caffeine 管理过期），每预算许可数默认 2，可配置。
- 获取许可在 `blockingIo` 线程**拿 DB 连接之前**；超时（默认 2s）返回现有的依赖不可用错误，不放行。
- 作用：等锁的请求在应用层排队，不再占 Hikari 连接，其他预算/其他请求阶段不被饿死。
- 边界：只在单实例内生效；多实例下仍由 MySQL 行锁保证正确性，信号量只是削峰。

### A4 会话参数

- Gateway 与 Backend 的 JDBC URL 增加 `useLocalSessionState=true`（以及评估 `cachePrepStmts`/`useServerPrepStmts`），消除每请求约 21 次 `SELECT @@session.transaction_read_only`。
- 用 performance_schema digest 前后对比验证。

## 4. 阶段 B（P2）：热点预算拆子账户

**只在阶段 A 全部验收通过后开始；若 B 的正确性测试无法全部通过，停在 A 并如实报告。**

**2026-11-26 WP7 门禁未通过。** 已发生的 Provider 费用完整入账后，预算可分配额度可以为负。分桶配额下界为 0，此时无法满足 §4.1 的配额总和上界。阶段 B 停在设计审查。[真实 MySQL 反例](../../../03-acceptance/maintenance/2026-11/2026-11-26-hc-04-hot-budget-cache/bucket-gate.md)

### 4.1 模型

- 新表 `budget_bucket(id, org_id, budget_id, bucket_no, quota_amount, reserved_amount, version)`，`UNIQUE(org_id, budget_id, bucket_no)`。
- 预算增加 `bucket_count`（默认 1 = 不分桶）。只有被判定为热点的预算（配置或按锁等待指标）才拆为 N 桶（默认 8）。
- **核心不变量**（在预算行锁下维护）：
  ```text
  Σ bucket.quota_amount ≤ total − actual − committed − 非桶内有效预占
  bucket.reserved_amount ≤ bucket.quota_amount
  ```

### 4.2 流程

- 准入：`bucket_no = hash(routeAttemptId) % N`，对该桶做条件更新 `reserved + amount ≤ quota`；失败则按顺序尝试其余桶（最多 N 次）；全部失败 → 进入「再平衡」。
- 再平衡（低频）：锁预算行 → 按最新 actual/committed 重算可分配额度 → 重新划分各桶 quota（只能下调到不低于各桶已 reserved）→ 提交。
- 释放/结算/对账：对预占所属桶 `reserved −amount`；结算增加 actual 时，在同一预算行锁内**同步下调桶 quota**，保持 §4.1 不变量。
- Backend 的其它 actual/committed/total 变更（账本入账、承诺、调额度）同样在预算行锁内调用「收缩 quota」逻辑。漏掉任何一处都会破坏不变量——实现方必须列出全部写入点并逐一测试。
- `budget_reservation` 增加 `bucket_no`。

### 4.3 必测

200 并发 + 同时进行账本入账、调额度：Σ 有效占用 ≤ total − actual − committed，0 超扣；再平衡与准入并发；结算与准入并发。

### 4.4 评估结论

1. **原 §4.1 不变量在透支结算时无解**：
   - 反例：预算 `total = 1.00`、预占 `1.00` 后，上游实际消费 `actual = 1.80` 按「已发生的 Provider Cost 不因预算不足而丢弃」完整入账；结算将预占转为 `FINALIZED`（`reserved = 0.00`）后，可用余额变为 `total − actual − committed = 1.00 − 1.80 − 0.00 = -0.80`。此时即便将所有分桶 `reserved_amount` 与 `quota_amount` 全部清零，非负分桶配额（`bucket.quota_amount ≥ 0`）无论如何也无法满足 `Σ bucket.quota_amount ≤ -0.80`（详见 [`bucket-gate.md`](../../../03-acceptance/maintenance/2026-11/2026-11-26-hc-04-hot-budget-cache/bucket-gate.md) 与真实 MySQL 回归用例 [`GatewaySettlementTransactionIntegrationTest#settledProviderCostsCanMakeTheBudgetHeadroomNegative`](../../../../backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementTransactionIntegrationTest.java#L90-L108)）。
2. **修正方向是「剩余可分配额度」模型**：
   - 将分桶配额语义从「含在途预占的总额度上限」重构为「剩余可分配新准入额度」`free_quota_i ≥ 0`，并维护新不变量：
     ```text
     Σ free_quota_i ≤ max(0, headroom − Σ reserved_i)
     其中 headroom = total − actual − committed − 非桶内有效预占
     ```
   - 发生透支时，只在预算行锁内将各桶 `free_quota_i` 归零以冻结新准入，同时**保留在途 `ACTIVE` / `PENDING_HOLD` 预占记录与计数**不误删，也不阻断已发生费用完整入账。
3. **暂缓原因（停在阶段 A）**：
   - 阶段 A 落地后，最新 WP6 对齐 ABAB 实测单预算 `c20 / c50 / c100 / c200` 预算锁语句均等已从基线 `145.40–191.31 ms` 降至 **`17.74–19.05 ms`**（降幅 **87%–91%**，`data_lock_waits` 峰值从 `36–45` 降至 `1`），单预算吞吐提升 `+17.3% ～ +29.6%`。
   - 系统瓶颈已转为同步事务刷盘（`COMMIT fsync`：S8 将 `innodb_flush_log_at_trx_commit` 临时改为 `2` 后 `c50` 立即从 `43.82` 升至 `89.80 RPS`）；分桶在生产必须维持 `innodb_flush_log_at_trx_commit = 1` 下收益有限。
   - 同时分桶需新增 `V35` 表并侵入全部财务写入路径（预算、结算、账本过账与更正、承诺、对账调账），跨模块变更面与回归风险高，不符合当前 `MAINTENANCE_ONLY` 维护期边界，因此阶段 B 暂缓、停在阶段 A。

## 5. C1：网关两级缓存

### 5.1 缓存对象

| 数据 | Key | 本地 TTL | Redis TTL | 失效 |
|---|---|---|---|---|
| Gateway 凭据（按凭据摘要） | `cfg:cred:{digest}` | 30s | 5min | Pub/Sub + TTL |
| 身份/项目/财务范围状态 | `cfg:principal:{credId}` | 10s | 1min | Pub/Sub + TTL |
| 模型目录（可见模型） | `cfg:model:{orgId}` | 60s | 10min | Pub/Sub + TTL |
| 路由策略与候选 | `cfg:route:{orgId}:{modelId}` | 30s | 5min | Pub/Sub + TTL |
| 定价费率（按版本） | `cfg:pricing:{pricingVersionId}` | 永久（容量上限） | 永久 | 版本不可变，无需失效 |
| Provider 连接配置 | `cfg:conn:{connId}` | 60s | 10min | Pub/Sub + TTL |
| Provider 凭据 | **只做本地缓存，不进 Redis** | 5min | — | Pub/Sub |

- Provider 凭据解密后的明文只在进程内存，不写 Redis、不打日志。
- **账期 OPEN 状态、预算、预占不缓存**：它们参与财务判定，必须读 MySQL。
- 同一请求内重复查询（模型目录查 2 次、定价查 2 次）改为请求内复用。

### 5.2 读路径

```text
Caffeine 命中 → 返回
→ Redis 命中 → 回填 Caffeine → 返回
→ 单飞（Caffeine AsyncLoadingCache 或按 key 的 in-flight Future）回源 MySQL
   → 写 Redis（TTL 加 ±10% 随机抖动防雪崩）→ 回填 Caffeine
→ MySQL 查无 → 写空值标记（本地 10s / Redis 60s）防穿透
Redis 故障 → 跳过 Redis 直接回源 MySQL（缓存不参与财务判定，可以 fail-open）
```

### 5.3 失效

- Backend 在配置写事务 **AFTER_COMMIT** 后：先 `DEL` Redis key，再 `PUBLISH aicostops:v2:cfg-invalidate {type, key}`。
- 各 Gateway 实例订阅该频道，收到后失效本地 Caffeine。
- Pub/Sub 不保证送达：本地 TTL 是最终兜底；订阅断线重连时清空全部本地配置缓存。
- 凭据吊销/禁用属于安全敏感变更：本地 TTL 不超过 30s，并在设计报告中写明「吊销最长生效延迟」。

### 5.4 代码位置（实现方需逐一确认）

`gateway/.../auth/GatewayAuthenticationManager.java:65,105,118,127`、`web/ChatCompletionController.java:247,252`、`request/GatewayRequestService.java:119,124`、`routing/RoutingPolicyMapper.java:31-78`、`provider/ProviderExecutionContextResolver.java:29`、`provider/ProviderCredentialDecryptor.java:44`、`metering/GatewayUsageFinalizationService.java:108`；Backend 变更入口：`gatewayadmin/infrastructure/GatewayAdminMapper.java`、`ControlPlaneMapper.java`、`providerhub/infrastructure/ProviderCredentialAdminMapper.java`、`routing/infrastructure/RoutingPolicyMapper.java`。

## 6. Redis 小项

### R1 超时预占延迟队列

- 预占创建后 `ZADD aicostops:v2:gateway:reservation-expiry {expires_at_ms} {reservationId}`；终态时 `ZREM`。
- 每 1s 用 Lua 原子认领：`ZRANGEBYSCORE key -inf now LIMIT 0 n` + `ZREM`，交给现有 `ReservationRecoveryService`（version fencing 不变），多实例下每项只被一个实例处理。
- 现有 60s DB 扫描保留为兜底（Redis 丢数据时仍能回收）。

### R2 幂等快路径

- 入口先 `SET aicostops:v2:gateway:idem:{credentialId}:{hmacDigest} {requestId} NX PX 86400000`。
- 已存在 → 直接走现有重放逻辑；不存在 → 继续现有流程。
- 权威仍是 MySQL `gateway_request` 唯一约束；Redis 丢失只会退化为查库，不会造成重复请求。

## 7. 测试（真实 MySQL 8.4 + Redis 7，Testcontainers）

1. A1：200 并发准入同一预算，0 超扣；结束后 `reserved_amount = Σ 有效预占`。
2. A1：准入与 Backend 结算（actual += x、reserved −= y）并发，0 超扣、计数一致。
3. A1：释放、过期回收、对账释放各自与准入并发，计数一致；同一预占重复释放只减一次。
4. A1：迁移回填正确（含 ACTIVE / PENDING_HOLD / RELEASED / FINALIZED 混合数据）。
5. A3：信号量超时返回错误而不是放行；许可在异常路径上不泄漏。
6. C1：命中/未命中/空值/单飞（100 并发同 key 只回源 1 次）。
7. C1：Backend 改配置后，Gateway 在 Pub/Sub 正常时秒级失效、在 Pub/Sub 丢失时 TTL 内失效。
8. C1：Redis 故障时请求仍成功（回源 MySQL）。
9. R1：到期预占 ≤ 2s 回收；双实例下每项只处理一次；Redis 清空后 DB 扫描兜底。
10. R2：100 并发同键，MySQL 插入尝试次数下降，结果与现状一致（1×200 / 99×409）。
11. B1（若实施）：§4.3 全部。
12. ArchUnit 与全量回归测试通过。

## 8. 压测

### 8.1 环境

- 只在本机压测：Windows + Docker Desktop，与 HC-02 相同（i9-14900HX，记录 Docker VM 内存与 MySQL buffer pool），baseline 与新版同机同脚本。
- 压测前先测一次单次 COMMIT 延迟（脚本循环 1000 次单行 INSERT autocommit，取 P50/P99），写进报告，作为解释绝对 RPS 的环境依据。
- 报告注明「本机 Docker 受控环境，数字用于同环境相对比较，不是生产容量结论」。

### 8.2 方法

- 改造前打 tag `hc04-baseline`；复用 `scripts/perf/hc01/runner.mjs`，新增 `--suite=hc04`。
- 每场景热身后 3 轮 × 20s，取中位数；成功只计 200。
- 每轮前清空 performance_schema digest。

### 8.3 场景与指标

| 场景 | 说明 | 指标 |
|---|---|---|
| S1 hot_single c20/c50/c100/c200 | 单预算热点 | 成功 RPS、P50/P95/P99、`budget` 锁等待次数与平均等待、Hikari 活跃/等待 |
| S2 multi_budget c100 | 8 预算 | 同上 |
| S3 multi_org c100 | 8 组织 | 同上 |
| S4 每请求 DB 往返 | 由 digest 总次数 ÷ 成功请求数 | 往返次数（含 `transaction_read_only`） |
| S5 正确性 | 压测结束校验 | 0 超扣、`reserved_amount` 与明细一致、结算全部收敛 |
| S6 Redis 故障 | 压测中 `docker pause redis` 30s | 成功率、错误码分布、0 超扣 |
| S7 到期回收延迟 | R1 前后 | 回收延迟 P50/P99 |
| S8 刷盘对照实验 | `innodb_flush_log_at_trx_commit=2` 只跑 S1 c50 | 仅作实验记录，量化刷盘对热点吞吐的影响，**不作为生产配置** |
| S9 导入补测 | 当前代码 10k/100k/500k | worker 吞吐、端到端吞吐、**堆峰值** |

产出目录：`docs/03-acceptance/maintenance/2026-11/<日期>-hc-04-hot-budget-cache/`（README、comparison.md、原始 json）。数字只写实测值；未达预期如实记录并分析原因。

## 9. 交付物

1. V34 迁移、A1–A4、C1、R1、R2 实现与测试；B1 视 §4 条件。
2. HC-04 压测报告（本机环境）。
3. 文档：本设计状态更新；AIC-087 顶部注明「预占准入由 HC-04 改为冗余计数条件更新，正确性证明见 HC-04」；`AGENTS.md` 记录维护期例外与新不变量（`budget.reserved_amount = Σ 有效预占`）；`PROJECT_CONTEXT.md`、`designs/README.md` 索引。
4. 面试追问卡（§10）补全答案与代码位置。

## 10. 面试追问卡

### 卡片 1：热点账户问题是什么？有哪些解法（分桶、缓冲、合并记账）？你为什么先做 A 再做 B？

- **核心答案**：
  - **问题本质**：高并发请求集中命中同一行 `budget` 记录时，MySQL InnoDB 行 X 锁将并发准入彻底串行化，导致大量线程排队阻塞、连接池打满与尾延迟飙升。WP6 基线实测单预算 `c20/c50/c100/c200` 预算锁语句均等为 `145.40–191.31 ms`、`data_lock_waits` 峰值 `36–45`，Hikari 12 连接占满，单预算成功 RPS 仅 `32.98–37.19`（`c200` 退化到 `32.98`）。
  - **常见解法**：① **缩短持锁期 + 削峰（阶段 A）**：用冗余计数条件更新替代锁内 `SUM`、锁外预计算定价/金额与幂等快查、连接池外信号量削峰、JDBC 会话参数减 RTT；② **分桶 / 拆子账户（阶段 B）**：将单个热点预算拆成 N 个子桶行分散行锁竞争；③ **缓冲 / 合并记账（异步批量落库或先扣缓存）**：在本系统中因违背「MySQL 是唯一金额授权真相、外部计费调用前必须落库」被否决。
  - **为什么先做 A 再做 B，且最终停在阶段 A**：先做 A 风险最低、不改表拓扑就能验证单行锁压到物理极限后的真实收益；阶段 A 验收后再评估 B，结果 **WP7 门禁 FAIL 且决定暂缓 B**：
    1. **原 §4.1 不变量在透支结算时无解**：预算 `total=1.00`、预占 `1.00` 后，上游实际消费 `actual=1.80` 按「已发生的 Provider Cost 不因预算不足而丢弃」完整入账，结算后可用余额为 `total − actual − committed = -0.80`；非负分桶配额（`quota_amount ≥ 0`）无论如何无法满足 `Σ bucket.quota_amount ≤ -0.80`。
    2. **修正方向为「剩余可分配额度」模型**：定义 `free_quota_i ≥ 0`、`Σ free_quota_i ≤ max(0, headroom − Σ reserved_i)`（`headroom = total − actual − committed − 非桶内有效预占`）；透支时只在预算行锁内把各桶 `free_quota_i` 归零冻结新准入，保留在途 `ACTIVE/PENDING_HOLD` 预占不误删。
    3. **暂缓原因**：阶段 A 后 WP6 对齐 ABAB 实测单预算 `c20/c50/c100/c200` 预算锁语句均等已从 `145.40–191.31 ms` 降到 **`17.74–19.05 ms`**（降幅 87%–91%，`data_lock_waits` 峰值从 `36–45` 降到 `1`），系统瓶颈已转为同步事务刷盘（`COMMIT fsync`）；分桶在 `innodb_flush_log_at_trx_commit=1` 下收益有限，且需新增 `V35` 表并侵入全部财务写入路径，不符合 `MAINTENANCE_ONLY` 维护期边界。
- **代码位置与证据**：
  - 条件原子扣减与锁外预计算：`gateway/src/main/java/com/aicostops/gateway/persistence/BudgetReservationMapper.java:L55-L66`、`gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservationService.java:L122-L216`
  - 结算全额入账导致负 headroom：`backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewaySettlementService.java:L209-L254`
  - WP7 真实 MySQL 透支反例：`backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementTransactionIntegrationTest.java:L90-L108`、`docs/03-acceptance/maintenance/2026-11/2026-11-26-hc-04-hot-budget-cache/bucket-gate.md:L1-L29`
  - WP6 实测对比：`docs/03-acceptance/maintenance/2026-11/2026-11-26-hc-04-hot-budget-cache/comparison.md:L93-L120`

### 卡片 2：条件更新 `WHERE 余额 >= ?` 为什么能防超扣？和 SELECT ... FOR UPDATE 再 UPDATE 比好在哪？

- **核心答案**：
  - **防超扣原理**：`UPDATE budget SET reserved_amount = reserved_amount + #{amount} ... WHERE ... AND total_amount - actual_amount - committed_amount - reserved_amount >= #{amount}` 执行时，InnoDB 会在目标 `budget` 行上获取 **排他 X 锁（Record Lock）** 并以**当前读（Current Read）**校验最新的 `status='ACTIVE'` 与剩余可用余额。高并发事务在行锁上串行化，后续事务必须等待前驱提交/回滚后重新读取已提交值再做判断；余额不足直接返回影响行数 `0`（配合 TX1 `ISOLATION_READ_COMMITTED` 后置 `selectBudgetById` 区分“非 ACTIVE”或“余额不足”），V34 亦增加 `chk_budget_reserved_nonneg` 兜底。
  - **相比 `SELECT ... FOR UPDATE` + 锁内 `SUM` + `UPDATE` 的优势**：① **消除 O(N) 扫描**：去掉基线锁内 `sumEffectiveReservations`（约 6,000 行扫描、~5.36 ms/次），变为 O(1) 单行计数运算；② **压缩锁内 RTT**：把「加锁读预算 + 聚合预占 + 判定写」合并为 1 条条件 `UPDATE`，大幅缩短持锁窗口（WP6 预算锁均等由 `145.40–191.31 ms` 降至 `17.74–19.05 ms`）；③ **消灭 Java 层 check-then-act 间隙**，条件判定下推至 MySQL 原子执行。
- **代码位置与证据**：
  - V34 表结构与非负约束：`backend/src/main/resources/db/migration/V34__hc04_budget_reserved_amount.sql:L3-L13`
  - 条件扣减与旧锁/SUM 对比：`gateway/src/main/java/com/aicostops/gateway/persistence/BudgetReservationMapper.java:L46-L66`、`gateway/src/main/java/com/aicostops/gateway/persistence/BudgetReservationMapper.java:L99-L106`
  - TX1 隔离级别与 0 行分类：`gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservationService.java:L95-L100`、`gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservationService.java:L203-L216`
  - 200 并发零超扣回归：`gateway/src/test/java/com/aicostops/gateway/budget/BudgetReservationConcurrencyIntegrationTest.java:L230-L273`

### 卡片 3：冗余计数和明细怎么保证一致？漏减/重复减怎么防？

- **核心答案**：
  - **5 处全生命周期同事务维护点**（均遵循 `billing_period -> budget -> budget_reservation` 锁序且在同一本地事务完成）：
    1. **准入加计数（`+amount`）**：Gateway 条件 `reserveBudgetAmount` 成功后插 `ACTIVE` 预占；若并发同 attempt 触发 `DuplicateKeyException`，同事务内立即 `releaseReservedBudgetAmount` 回退并收敛至 winner。
    2. **SAFE 释放（`-amount`）**：Gateway Failover 或派发前安全失败时，持锁后先 CAS `releaseActiveReservation` 再 `releaseReservedBudgetAmount`。
    3. **过期回收释放（`-amount`）**：确定未派发时先 CAS `releaseActiveReservation` 再 `releaseReservedBudgetAmount`；若可能已派发则转为 `PENDING_HOLD`，**不减计数**。
    4. **结算终结（`-amount`）**：Backend 记账增 `actual_amount` 后，CAS `finalizeForSettlement` 再调用 `decrementReserved`。
    5. **对账终结（`-amount`）**：Backend 对账裁定 `STATEMENT_ADJUSTMENT_POSTED` / `NO_CHARGE_CONFIRMED` 后，先 CAS `finalizeForSettlement` / `releaseForReconciliation` 再调用 `decrementReserved`。
  - **防重减与防漏减**：每次扣减前必先执行预占行的 `version` + 合法态（`ACTIVE` 或 `ACTIVE/PENDING_HOLD`）乐观 CAS 更新，只有 `affectedRows == 1` 才扣减 `reserved_amount`（保证同一预占至多扣减一次）；扣减 SQL 自带 `WHERE reserved_amount >= #{amount}`，返回不为 1 立即抛异常整事务回滚防漏减/错减。
  - **对账巡检与越权防护**：V34 迁移回填由专项测试验证；Gateway 新增 `BudgetReservedDriftCheckScheduler` 每 60s 只读校验 `budget.reserved_amount <> SUM(ACTIVE+PENDING_HOLD)`，发现漂移仅打告警日志和指标、**绝不自动修数**；ArchUnit 与 M21 数据库列级授权禁止 Gateway 写 `actual/committed/total`。
- **代码位置与证据**：
  - ① 准入与冲突回退：`gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservationService.java:L205-L240`
  - ② SAFE 释放：`gateway/src/main/java/com/aicostops/gateway/budget/SafeReservationReleaseService.java:L27-L57`
  - ③ 到期回收：`gateway/src/main/java/com/aicostops/gateway/budget/ReservationRecoveryService.java:L205-L252`
  - ④ 结算终结：`backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewaySettlementService.java:L240-L254`、`backend/src/main/java/com/aicostops/budget/application/LedgerBudgetService.java:L90-L98`、`backend/src/main/java/com/aicostops/budget/infrastructure/BudgetMapper.java:L143-L154`
  - ⑤ 对账终结：`backend/src/main/java/com/aicostops/reconciliation/application/GatewayFinancialResolutionService.java:L405-L421`、`backend/src/main/java/com/aicostops/gatewaysettlement/infrastructure/GatewayReservationSettlementMapper.java:L45-L74`
  - 只读漂移巡检：`gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservedDriftCheckScheduler.java:L31-L36`、`gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservedDriftCheckService.java:L34-L57`、`gateway/src/main/java/com/aicostops/gateway/persistence/BudgetReservationMapper.java:L81-L97`
  - 列级权限与 ArchUnit：`gateway/src/test/java/com/aicostops/gateway/architecture/GatewayArchitectureTest.java:L117-L163`、`scripts/m21/provision-gateway-db.ps1:L94`
  - 一致性集成测试：`backend/src/test/java/com/aicostops/budget/V34BudgetReservedAmountMigrationIntegrationTest.java:L74-L93`、`gateway/src/test/java/com/aicostops/gateway/budget/BudgetReservedDriftCheckIntegrationTest.java:L56-L112`、`gateway/src/test/java/com/aicostops/gateway/budget/BudgetReservationConcurrencyIntegrationTest.java:L275-L354`

### 卡片 4：锁内为什么不能做重操作？你移出了哪些？为什么 COMMIT 是大头？

- **核心答案**：
  - **为什么锁内不能做重操作**：`budget` 行 X 锁是单预算全部并发流量的独占瓶颈，锁内任何多余计算、缓存未命中回源或多次 SQL 往返都会被并发度线性放大为所有排队线程的等待耗时，同时占死 Hikari 连接。
  - **HC-04 移出锁外的操作**：
    1. 入口幂等快查与收敛 `findOrFenceExistingHold` 前移到开事务与拿信号量之前；
    2. 定价费率读取 `configCache.getPricingRates` 与保守上限计算 `ReservationAmountCalculator.calculate` 全部移出 TX1 到无锁阶段；
    3. 预定位预算 ID（`resolveBudget`）短暂只读后释放连接，再抢信号量；
    4. `insertActiveReservation` 改 `@Options(useGeneratedKeys = true)` 消除 `SELECT LAST_INSERT_ID()` 往返；热路径消除 `lockBudgetById` 与 `sumEffectiveReservations`；
    5. Redis ZSET 延迟入队 `delayQueue.enqueue` 放在 `transactions.execute` 提交返回之后；
    6. **刻意保留项**：`markRequestReserved` 仍留在 TX1 内，因为关账阻塞器 `GatewayCloseBlockerMapper.countUnresolvedReservations` 依赖 `gateway_request.billing_period_id` 与状态机围栏，不可为了提速破坏关账安全。
  - **为什么 `COMMIT` 成为大头**：锁内 SQL 被压减到只剩「`FOR SHARE` 账期 → `selectBudget` → 条件 `UPDATE budget` → `INSERT reservation` → `UPDATE request` → `COMMIT`」后，纯 SQL 计算仅亚毫秒；在 `innodb_flush_log_at_trx_commit=1` + `sync_binlog=1` 下，每次 `COMMIT` 都必须执行 redo log + binlog 的 `fsync` 落盘（本机 Docker/WSL2 实测单次 `autocommit INSERT` P50 为 `7.20–8.78 ms`、P99 `15.16–21.00 ms`），这部分 `fsync` 等待全部包裹在行 X 锁释放前，因而优化后预算锁语句均等稳定在 `17.74–19.05 ms`（单请求全链路约 `3.84` 次 `COMMIT`）。
- **代码位置与证据**：
  - 锁外预计算、信号量前置与提交后入队：`gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservationService.java:L122-L172`
  - 保留 `markRequestReserved` 在 TX1 内的设计依据：`gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservationService.java:L34-L38`、`gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservationService.java:L247-L251`
  - `useGeneratedKeys` 与纯计算逻辑：`gateway/src/main/java/com/aicostops/gateway/persistence/BudgetReservationMapper.java:L191-L203`、`gateway/src/main/java/com/aicostops/gateway/budget/ReservationAmountCalculator.java:L45-L104`
  - COMMIT 基准与 S4 统计：`scripts/perf/hc01/hc04.mjs:L172-L200`、`docs/03-acceptance/maintenance/2026-11/2026-11-26-hc-04-hot-budget-cache/comparison.md:L75-L83`、`docs/03-acceptance/maintenance/2026-11/2026-11-26-hc-04-hot-budget-cache/comparison.md:L142-L149`

### 卡片 5：为什么不用 Redis 扣预算？（结算、调额度直接改 MySQL → 跨库竞态；AOF 丢数据）

- **核心答案**：
  - **跨库双写竞态无解**：控制面 Backend 的结算增加 `actual_amount`、账单/报销记账（`ProviderChargePostingService` / `ExpensePostingService`）、人工调整总额（`BudgetCommandService.update`）、承诺冻结/消费及关账排空都在 MySQL 事务中直接修改 `budget` 与 `billing_period`。Redis Lua 脚本只能对 Redis 内部键保证原子性，无法参与 MySQL 事务锁排序与快照隔离；两边同时改同一笔可用额度必然发生时序交叉覆盖、负漂移或超卖。
  - **持久化不满足金融级 ACID-D**：即使生产 Compose 开启 Redis `--appendonly yes`（默认 `everysec`），异常掉电仍存在最多 1 秒的写入丢失窗口；丢失已扣减的预占记录意味着崩溃恢复后会把已消耗预算重新放出，而将 Redis 作为权威状态也会违背「MySQL 是唯一身份与财务真相」的根本架构边界。
  - **Redis 的正交职责**：只承担限流、配额、熔断（运行态护栏，fail-closed）以及可重建的 L2 配置缓存、ZSET 延迟唤醒和幂等首写 hint（C1/R1/R2）。
- **代码位置与证据**：
  - MySQL 真相架构禁令：`docs/02-development/designs/v2/04-budget-redis-atomicity.md:L28-L46`
  - Backend 直接改写 MySQL `budget`：`backend/src/main/java/com/aicostops/budget/application/BudgetCommandService.java:L83-L106`、`backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewaySettlementService.java:L209-L254`、`backend/src/main/java/com/aicostops/ledger/application/ProviderChargePostingService.java:L207-L209`
  - Redis AOF 配置与限流非资金边界：`compose.yaml:L104-L111`、`gateway/src/main/java/com/aicostops/gateway/ratelimit/RedisTokenBucketRateLimiter.java:L43-L74`

### 卡片 6：信号量放在拿连接之前解决了什么？多实例下还有用吗？

- **核心答案**：
  - **放在拿 Hikari 连接之前解决了什么**：Gateway 默认仅配 `12` 个 Hikari 连接与 `12` 个 `gateway-db` 工作线程。若所有冲向热点预算的线程都先开事务拿连接再在 MySQL 等行锁，12 个连接会迅速被同一个热点预算打满（WP6 基线即出现 Hikari `12/12` 饱和），导致**同实例上的其他不相关预算、其他组织请求甚至其他 DB 阶段全部连接饥饿**。HC-04 在 `resolveBudget` 短查询归还连接后、`transactions.execute` **之前**调用 `budgetSemaphore.acquire(budgetId)`（默认每预算许可 `permits = 2`、超时 `2000 ms`，超限/中断直接 fail-closed 抛 503），把排队隔离在应用内存信号量上，保留空闲 DB 连接服务其他预算；`Permit` 绑定原 `Semaphore` 实例且幂等释放，异常绝不泄漏。
  - **多实例下是否仍有价值**：**有用，职责是「实例级连接池保护与数据库防雪崩削峰」，而非全局互斥锁**。K 个 Gateway 实例对同一预算最多同时放入 `K × 2` 个连接进 MySQL（WP6 双实例压测下最多 4 个并发竞锁，使 `data_lock_waits` 峰值从基线 `36–45` 骤降到 `1`），依然能保护连接池并抑制 InnoDB 锁队列风暴；全局金额排他仍 100% 由 MySQL 行锁保证。
- **代码位置与证据**：
  - 信号量前置与释放：`gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservationService.java:L154-L167`
  - 有界 Caffeine 信号量与 fail-closed `Permit`：`gateway/src/main/java/com/aicostops/gateway/budget/BudgetAdmissionSemaphore.java:L30-L107`、`gateway/src/main/java/com/aicostops/gateway/budget/BudgetAdmissionSemaphore.java:L142-L161`
  - 线程池与信号量默认配置：`gateway/src/main/java/com/aicostops/gateway/config/GatewayProperties.java:L25-L40`、`gateway/src/main/resources/application.yml:L31`、`gateway/src/main/resources/application.yml:L90-L105`
  - 不饿死其他预算及零泄漏验证：`gateway/src/test/java/com/aicostops/gateway/budget/BudgetAdmissionSemaphoreIntegrationTest.java:L100-L230`

### 卡片 7：两级缓存为什么要两级？一致性怎么保证？Pub/Sub 丢消息怎么办？凭据吊销最长多久生效？

- **核心答案**：
  - **为什么需要两级**：① **L1（Caffeine 堆内）** 提供纳秒级本地读取（消除热路径重复 DB 查询，WP6 配合 A4 共削减 `-32.04 次/请求` MySQL 往返），同时是 **解密后 Provider 明文凭据唯一允许的驻留位置**（`providerCredLocalL1` 严禁写入 Redis 防止密钥外泄，读取返回 `byte[]` 防御性拷贝）；② **L2（Redis 共享）** 跨实例共享冷启动与回源结果，保护 MySQL。
  - **一致性保障**：① Backend 所有配置写入点（凭证/主体/项目/团队/成本中心/模型/路由/定价/连接档案/Provider 凭据）通过 `GatewayConfigInvalidationPublisher` 在写事务 **`AFTER_COMMIT`** 后先 `DEL` Redis L2 再 `PUBLISH aicostops:v2:cfg-invalidate`（未提交不误删，Redis 故障不回滚 DB）；② Gateway `GatewayConfigInvalidationSubscriber` 订阅频道精准驱逐对应 L1 槽位并推进 `epoch`；③ 回源加载携带 `startSlotEpoch` / `startGlobalEpoch` 版本戳校验，若并发期间收到失效通知则放弃写回 L1/L2，防止脏回填。
  - **Pub/Sub 丢消息兜底**：`GatewayConfigInvalidationSubscriber` 挂载 Lettuce `RedisConnectionStateListener` 与 Reactor `SubscriptionListener`，一旦检测到 Pub/Sub 断连或重连（`onRedisDisconnected` / `onRedisConnected` / `onChannelSubscribed` 二次订阅），立刻调用 `configCache.invalidateAll()` **清空全部本地 L1 缓存**；所有 L1 槽位均采用 `expireAfterWrite` 有限 TTL 兜底。
  - **凭据吊销最长生效延迟**：Pub/Sub 正常时为毫秒/秒级生效；若极端情况下 Pub/Sub 消息丢失，**Gateway 凭据（`cfg:cred:{digest}`）本地 `CRED_LOCAL_TTL = 30s`** 决定其最长生效延迟不超过 **30 秒**（停用主体/项目/财务范围 `PRINCIPAL_LOCAL_TTL = 10s`，吊销/轮换上游 Provider 凭据 `PROVIDER_CRED_LOCAL_TTL = 5min`）。
- **代码位置与证据**：
  - TTL 常量、epoch 校验与纯本地 Provider 凭据缓存：`gateway/src/main/java/com/aicostops/gateway/cache/GatewayConfigCache.java:L63-L76`、`gateway/src/main/java/com/aicostops/gateway/cache/GatewayConfigCache.java:L345-L391`、`gateway/src/main/java/com/aicostops/gateway/cache/GatewayConfigCache.java:L501-L547`
  - 解密入口本地缓存调用：`gateway/src/main/java/com/aicostops/gateway/provider/ProviderCredentialDecryptor.java:L60-L69`
  - Backend `AFTER_COMMIT` 删除并广播：`backend/src/main/java/com/aicostops/shared/cache/GatewayConfigInvalidationPublisher.java:L75-L240`、`backend/src/main/java/com/aicostops/gatewayadmin/application/GatewayCredentialService.java:L140-L157`
  - Gateway 订阅与断线全量清空：`gateway/src/main/java/com/aicostops/gateway/cache/GatewayConfigInvalidationSubscriber.java:L60-L96`、`gateway/src/main/java/com/aicostops/gateway/cache/GatewayConfigInvalidationSubscriber.java:L134-L194`
  - Pub/Sub 失效与 30s 兜底验证：`gateway/src/test/java/com/aicostops/gateway/cache/GatewayConfigCacheIntegrationTest.java:L435-L509`、`backend/src/test/java/com/aicostops/gatewayadmin/GatewayConfigInvalidationIntegrationTest.java:L135-L256`

### 卡片 8：缓存穿透、击穿、雪崩分别怎么处理的？

- **核心答案**：
  - **防穿透**：MySQL 查无记录时写入空值哨兵 `CacheEntry.nullSentinel()`（Redis 存储固定字符串 `"\"__NULL__\""`），使用独立短 TTL：**本地 `NULL_LOCAL_TTL = 10s`、Redis `NULL_REDIS_TTL = 60s`**；定价版本则采用 `pricingNegativeL1`（10s），且只有 `ACTIVE`/`RETIRED` 且费率非空的定价允许永久缓存，`DRAFT` 或空费率不长期缓存。
  - **防击穿（Single-Flight 单飞）**：L1 未命中后，通过 `inFlight`（`ConcurrentHashMap<String, CompletableFuture<?>>`）执行 `putIfAbsent(flightKey, created)`，同 key 并发回源只放行 1 个领导者穿透查 L2/MySQL，其余等待 `awaitInFlight(existing)`；领导者在查 L2 前还做一次 L1 `recheck`，`finally` 保证清理占位（测试已证 100 并发同 key 仅查库 1 次）。
  - **防雪崩**：① 写入 Redis 时通过 `jitterTtl` 对基准 TTL 施加 **`±10%`（`[0.9, 1.1)`）随机抖动**错开过期波峰；② L1 与 L2 过期时长天然解耦；③ Redis 读取/写入异常时捕获 `RuntimeException` 并 **`fail-open` 直接回源 MySQL**，确保 Redis 抖动或重启不会引爆业务雪崩（WP6 S6 暂停 Redis 30s 实测 0 个 502 且吞吐提升 +46.1%）。
- **代码位置与证据**：
  - 空值哨兵与 TTL 定义：`gateway/src/main/java/com/aicostops/gateway/cache/GatewayConfigCache.java:L74-L77`、`gateway/src/main/java/com/aicostops/gateway/cache/GatewayConfigCache.java:L310-L336`、`gateway/src/main/java/com/aicostops/gateway/cache/GatewayConfigCache.java:L745-L768`
  - 单飞 `inFlight` 合并加载：`gateway/src/main/java/com/aicostops/gateway/cache/GatewayConfigCache.java:L85`、`gateway/src/main/java/com/aicostops/gateway/cache/GatewayConfigCache.java:L507-L560`
  - `±10%` 抖动与 Redis `fail-open`：`gateway/src/main/java/com/aicostops/gateway/cache/GatewayConfigCache.java:L562-L610`
  - 100 并发单飞、穿透及断网 fail-open 集成测试：`gateway/src/test/java/com/aicostops/gateway/cache/GatewayConfigCacheIntegrationTest.java:L254-L339`、`gateway/src/test/java/com/aicostops/gateway/cache/GatewayConfigCacheIntegrationTest.java:L511-L597`

### 卡片 9：为什么账期状态和预算不缓存？

- **核心答案**：
  - **财务状态与行锁围栏不可分离**：`GatewayConfigCache` 明确声明 **`billing_period`、`budget`、`budget_reservation` 永不缓存**。
  - **账期状态不缓存的原因**：Gateway TX1/TX2 对账期读的本质不是取一个静态枚举，而是执行 `SELECT status FROM billing_period ... FOR SHARE` 获取 **共享行锁围栏**；Backend 关账（`BillingPeriodCloseService`）执行 `SELECT ... FOR UPDATE` 抢占排他 X 锁并切换 `CLOSING`（等待所有在途 `FOR SHARE` 持有者释放后再用 `CloseBlocker` 核对无未决派发与预占才关账）。若网关绕开 MySQL `FOR SHARE` 而读缓存里的 `OPEN`，就可能在关账事务检查间隙放行新的付费派发，破坏关账写保护。
  - **预算与预占不缓存的原因**：`budget` 余额（`total - actual - committed - reserved`）受 Backend 结算/过账/调额/承诺与多台 Gateway 并发预占/释放高频更新，任何缓存副本都是不可串行化的陈旧快照；而 `budget_reservation`（`ACTIVE`/`PENDING_HOLD`）直接参与 TX2 `DispatchFenceService` 派发前终极核验（无有效预占严禁对外调用），缓存失真将引发超扣或无预占越权派发。
- **代码位置与证据**：
  - 严禁缓存声明：`gateway/src/main/java/com/aicostops/gateway/cache/GatewayConfigCache.java:L53-L58`
  - 账期共享锁 vs 关账排他锁：`gateway/src/main/java/com/aicostops/gateway/persistence/GatewayRequestMapper.java:L74-L77`、`backend/src/main/java/com/aicostops/budget/infrastructure/BillingPeriodMapper.java:L68-L77`、`backend/src/main/java/com/aicostops/budget/application/BillingPeriodCloseService.java:L28-L73`
  - TX2 实时核验 MySQL 有效预占：`gateway/src/main/java/com/aicostops/gateway/request/DispatchFenceService.java:L54-L89`

### 卡片 10：ZSET 延迟队列怎么保证多实例不重复？为什么保留 DB 扫描？

- **核心答案**：
  - **延迟队列机制**：TX1 提交产生到期时间后，向 Redis ZSET `aicostops:v2:gateway:reservation-expiry` 写入 `{score=expires_at_ms, member=reservationId}`；正常安全释放或失败转 `PENDING_HOLD` 时调用 `ZREM` 清理。
  - **三层机制防止多实例重复处理**：
    1. **Redis Lua 原子认领（`CLAIM`）**：`ReservationRecoveryScheduler.recoverQueued` 每 `1000 ms`（`AtomicBoolean queuePollRunning` 防单机重入）执行 `CLAIM` Lua 脚本，在 Redis 单线程内原子完成 `ZRANGEBYSCORE -inf now LIMIT 0 n` + 逐个 `ZREM`，同一到期项同一轮最多被 1 个实例摘走。
    2. **MySQL 权威时钟复核**：认领后查 `findQueuedActiveHold`，以数据库 `TIMESTAMPDIFF(MICROSECOND, UTC_TIMESTAMP(6), expires_at)` 判断真实剩余微秒数；若因 JVM 时钟偏快被提前唤醒（`remainingMicros > 0`），按剩余时间重新入队而不误回收。
    3. **MySQL 严格锁序 + `version` 乐观 Fencing**：真正收敛 `recoverOneBlocking` 按 `BillingPeriod -> Budget -> Reservation` 顺序加锁，并要求 `status='ACTIVE'` 且 `version = hold.version` 才释放并扣减 `reserved_amount`（已入终态或版本变更则直接 `SKIPPED`）。
  - **保留 60s DB 扫描的必要性**：① Redis 延迟队列仅作加速唤醒 hint：TX1 提交后进程崩溃、Redis 驱逐/重启/网络中断、或某实例 Lua `ZREM` 后尚未来得及写 DB 就宕机，均会导致延迟消息丢失，必须靠 `recoverExpiredBlocking`（60s）兜底扫库回收；② DB 扫描还同时回收无 Redis 队列项的两类异常请求——全部 attempt 已 SAFE 但主请求未终态收敛（`recoverSafeReleasedRequests`）及预占前崩溃孤儿（`recoverStalePreDispatchRequests`）。WP6 S7 实测到期回收 P50 从 `29,985.41 ms` 降至 **`885.29 ms`**（-97.0%），P99 从 `31,456.17 ms` 降至 **`978.09 ms`**（-96.9%）。
- **代码位置与证据**：
  - ZSET Lua `CLAIM`：`gateway/src/main/java/com/aicostops/gateway/budget/ReservationDelayQueue.java:L17-L70`
  - 1s 队列调度与 60s DB 扫描：`gateway/src/main/java/com/aicostops/gateway/budget/ReservationRecoveryScheduler.java:L30-L50`
  - 数据库时钟校准、三路扫描与 `version` fencing：`gateway/src/main/java/com/aicostops/gateway/budget/ReservationRecoveryService.java:L63-L102`、`gateway/src/main/java/com/aicostops/gateway/budget/ReservationRecoveryService.java:L205-L252`、`gateway/src/main/java/com/aicostops/gateway/persistence/BudgetReservationMapper.java:L148-L168`
  - 并发抢占与断 Redis 兜底测试：`gateway/src/test/java/com/aicostops/gateway/budget/ReservationRecoveryIntegrationTest.java:L111-L212`

### 卡片 11：`innodb_flush_log_at_trx_commit` 0/1/2 的区别？为什么生产必须是 1？

- **核心答案**：
  - **三档策略核心区别**：
    - `0`：事务提交时不触发日志写和刷盘，由 InnoDB 后台每秒写 OS page cache 并 `fsync`；MySQL 进程崩溃或主机掉电都会丢失约 1 秒已提交数据。
    - `2`：事务提交时同步 `write()` 进 OS page cache，但每秒由后台异步 `fsync`；MySQL 进程重启不丢，但**宿主机断电或内核异常仍会丢失尚未 `fsync` 的近期事务**。
    - `1`（默认值）：每次 `COMMIT` 都同步将 redo log `write()` 并 `fsync()` 到物理磁盘（配合 `sync_binlog=1`），严格保障 ACID 中的持久性（Durability）。
  - **HC-04 S8 刷盘敏感性实测对照**：在单预算 `c50` 下临时改为 `innodb_flush_log_at_trx_commit=2`，新版成功吞吐立即从 `43.82 RPS` 跃升至 **`89.80 RPS`**（同配置相比基线 `47.44 RPS` 提升 **+89.3%**），预算锁语句均等从 `18.28 ms` 降为 **`7.54 ms`**，P50 延迟从 `1107.05 ms` 降至 `546.63 ms`。直接证明阶段 A 后行锁持锁窗口的最大物理开销是同步 `fsync`。
  - **生产为什么必须是 `1`**：AI CostOps 属于实时资金风控与计费账务系统。一旦 TX2 `DISPATCH_INTENT` 或终态 `gateway_usage_fact` 返回提交成功，Gateway 就已向第三方付费 Provider 发出不可撤回的计费调用或已返还响应；若因 `0`/`2` 在主机掉电时丢失提交事务，会出现“外部钱已花、本地请求/预占/账本记录却凭空消失”的致命丢账与对账黑洞。
- **代码位置与证据**：
  - S8 实验脚本与开跑前强制检查 `= 1`：`scripts/perf/hc01/runner.mjs:L1266-L1273`、`scripts/perf/hc01/runner.mjs:L1494-L1496`、`scripts/perf/hc04/abab.mjs:L359-L360`
  - S8 实测对比数据：`docs/03-acceptance/maintenance/2026-11/2026-11-26-hc-04-hot-budget-cache/comparison.md:L25`、`docs/03-acceptance/maintenance/2026-11/2026-11-26-hc-04-hot-budget-cache/comparison.md:L102`、`docs/03-acceptance/maintenance/2026-11/2026-11-26-hc-04-hot-budget-cache/comparison.md:L169`
  - 不可丢失的同步落盘点：`gateway/src/main/java/com/aicostops/gateway/request/DispatchFenceService.java:L53-L139`、`gateway/src/main/java/com/aicostops/gateway/metering/GatewayUsageFinalizationService.java:L81-L171`

### 卡片 12：绝对 RPS 不高的原因是什么？（同步记账 + 单行热点 + 本机刷盘），为什么不能像秒杀那样异步落库？

- **核心答案**：
  - **绝对 RPS 受限的三重物理与环境根因**（WP6 实测单预算 `42.02–43.82 RPS`、8 预算/8 组织 `~66.9 RPS`）：
    1. **多阶段同步持久化**：每笔请求严格经历建单、路由规划、TX1 预算条件扣减、TX2 `DISPATCH_INTENT` 围栏、终态 `FINAL` 用量写入，WP6 S4 实测每请求平均 **`3.84` 次 `COMMIT`**；
    2. **单行热点物理串行**：同一预算的 TX1 在 InnoDB 行 X 锁上必然串行排队，且持有期间覆盖一次完整的双 `1` `COMMIT fsync`（单次优化后锁均值 `17.74–19.05 ms` 决定了单行理论上限约 `52–56 RPS`）；
    3. **本机受控虚拟磁盘环境边界**：单台笔记本同机混部客户端、双 Gateway JVM、Backend 与 Mock Provider，MySQL 运行在 **1.86 GiB Docker VM / 128 MiB InnoDB Buffer Pool** 与 Windows WSL2 虚拟盘上，单次 `autocommit INSERT` 本底 `P50 = 7.20–8.78 ms`、`P99 = 15.16–21.00 ms`。该数据仅用于同机 A/B 对照，不代表生产独立 NVMe 硬件容量。
  - **为什么不能像电商秒杀那样 Redis 扣库存 + MQ 异步落库**：
    1. **外部成本副作用不可逆**：秒杀只是先占位再异步发订单，极端异常可退单；而 Gateway 一旦跨越边界向付费 Provider 发送请求，**真实的 token 算力账单即刻发生且不可撤回**。因此必须遵循「`DISPATCH_INTENT` 先同步持久化到 MySQL，再执行可能计费的 Provider I/O」。
    2. **多写入口并发与关账财务真相一致性**：控制面还要处理外部账单导入过账（`ProviderChargePostingService`）、人工调整（`BudgetCommandService`）与账期关账排空（`BillingPeriodFinancialWriteFence`）。异步队列落库会造成在途扣费在关账时刻仍滞留队列、损坏「`MySQL = 唯一最终财务 Truth`」与「已发生费用绝不丢弃」的不变量。
- **代码位置与证据**：
  - 环境边界、COMMIT 延迟与吞吐数据：`docs/03-acceptance/maintenance/2026-11/2026-11-26-hc-04-hot-budget-cache/comparison.md:L20-L32`、`docs/03-acceptance/maintenance/2026-11/2026-11-26-hc-04-hot-budget-cache/comparison.md:L75-L83`、`docs/03-acceptance/maintenance/2026-11/2026-11-26-hc-04-hot-budget-cache/comparison.md:L142-L149`
  - 必须先持久化 `DISPATCH_INTENT` 再派发上游：`gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestOrchestrator.java:L160-L184`、`gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java:L234-L246`、`gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java:L503-L526`
  - 多写入口并发写同一预算与账期围栏：`backend/src/main/java/com/aicostops/ledger/application/ProviderChargePostingService.java:L190-L215`、`backend/src/main/java/com/aicostops/budget/application/BudgetCommandService.java:L83-L106`

## 11. 维护期例外

项目处于 `MAINTENANCE_ONLY`。本改造由 HC-02 实测瓶颈触发，经仓库所有者批准作为维护期例外实施；MySQL 仍是唯一金额授权方。
