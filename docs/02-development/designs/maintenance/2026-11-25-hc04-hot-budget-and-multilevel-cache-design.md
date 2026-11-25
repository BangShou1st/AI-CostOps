# HC-04 热点预算优化与网关多级缓存

> 日期：2026-11-25
> 状态：APPROVED FOR IMPLEMENTATION（维护期例外改造，见 §11）
> 证据：[HC-02 A/B 对比](../../../03-acceptance/maintenance/2026-11/2026-11-11-hc-02-billing-period-lock/comparison.md)
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

## 10. 面试追问卡（实现后补答案与 file:line）

1. 热点账户问题是什么？有哪些解法（分桶、缓冲、合并记账）？你为什么先做 A 再做 B？
2. 条件更新 `WHERE 余额 >= ?` 为什么能防超扣？和 SELECT ... FOR UPDATE 再 UPDATE 比好在哪？
3. 冗余计数和明细怎么保证一致？漏减/重复减怎么防？
4. 锁内为什么不能做重操作？你移出了哪些？为什么 COMMIT 是大头？
5. 为什么不用 Redis 扣预算？（结算、调额度直接改 MySQL → 跨库竞态；AOF 丢数据）
6. 信号量放在拿连接之前解决了什么？多实例下还有用吗？
7. 两级缓存为什么要两级？一致性怎么保证？Pub/Sub 丢消息怎么办？凭据吊销最长多久生效？
8. 缓存穿透、击穿、雪崩分别怎么处理的？
9. 为什么账期状态和预算不缓存？
10. ZSET 延迟队列怎么保证多实例不重复？为什么保留 DB 扫描？
11. `innodb_flush_log_at_trx_commit` 0/1/2 的区别？为什么生产必须是 1？
12. 绝对 RPS 不高的原因是什么？（同步记账 + 单行热点 + 本机刷盘），为什么不能像秒杀那样异步落库？

## 11. 维护期例外

项目处于 `MAINTENANCE_ONLY`。本改造由 HC-02 实测瓶颈触发，经仓库所有者批准作为维护期例外实施；MySQL 仍是唯一金额授权方。
