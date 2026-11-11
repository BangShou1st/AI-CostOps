# HC-01 findings — 首要瓶颈、证据与 HC-02 建议

> 运行 HEAD：`5ca7df839fa3340fec67c325ed08de6db687440c`（Gateway/Backend 字节码来自 `bba23b5`，其后仅汇总脚本变更）；
> 完整数据见 [`load-results.json`](load-results.json)、[`environment.json`](environment.json)、
> [`db-invariants.json`](db-invariants.json)。
>
> 2026-11-11 审查修正（PR #177）：更正了 §2.6 与 §5 中“新增
> `(org_id,budget_id,status)` 索引”的错误建议（该索引自 V19 起已存在），并补充
> SSE 建流前取消订阅的 permit 泄漏修复。压测数字与 `load-results.json` 未改动；
> 修正过程见 [`2026-11-11-hc-01-review-followup.md`](2026-11-11-hc-01-review-followup.md)。

## 1. 一句话结论

在本机受控环境中，单账期 Gateway 成功吞吐在 5→200 并发下基本停留在
**14.9–17.7 rps** 量级；P95 从 c5 的 496.8 ms 增长到 c200 的
**15036.9 ms**。首要瓶颈不是 Redis、CPU 或连接池等待，而是
**`billing_period` 行级排他锁 + `gateway_request` 账期外键共享锁形成的排队**。
同账期增加预算不改善，多组织（多账期）才提升；这为 HC-02 指明了唯一优先方向。

## 2. 首要瓶颈

### 2.1 机制

1. 预算准入 TX1 以
   `SELECT ... FROM billing_period WHERE id=? AND org_id=? FOR UPDATE` 开始，
   直到 reservation 插入、request 状态更新并 commit 才释放。
2. `gateway_request` 的外键 `fk_gateway_request_period_org` 使每个并发请求在
   准入前的 INSERT 对同一账期行取共享锁；它们排在 TX1 的 X 锁之后。
3. `gateway_usage_fact`、`gateway_settlement` 等财务表同样带账期外键，
   把结算/最终化也卷入同一排队。
4. 结果是单账期的临界区被串行化；并发只增加队列长度和尾延迟。

### 2.2 SQL 证据

`SELECT STATUS FROM billing_period WHERE id = ? AND org_id = ? FOR UPDATE`：67276 次，平均 **205.56 ms**，总 13829.2 s；其次 `COMMIT` 162069 次、平均 19.98 ms。

`performance_schema.data_lock_waits` 每轮采样显示：账期行 X/S 等待者约 11–12 个，
`gateway_request` 的 X 锁最高 66 个；多组织场景降至 1–4 个。

### 2.3 热点对照

| 场景 | 轮次中位成功 RPS | P95 中位 | DB 队列峰值 | 备注 |
|---|---:|---:|---:|---|
| hot 单预算 c100 | 17.7 | 7389.9 | 89 | 同组织/同账期/同预算 |
| 同账期多预算 c100 | 18.3 | 7348.2 | 89 | 8 个预算/凭据，仍同一账期锁 |
| 多组织 c100 | 48.2 | 2618.6 | 89 | 8 个账期，等待者仅 1–4 |
| mixed settlement c50 | 12.8 | 5472.7 | 39 | Backend/Settlement 运行中 |

同账期多预算相对 hot 的比值：**103%**；多组织相对 hot：**272%**。
这排除了“某个 budget 行热点”，把根因锁定在共享账期行/外键。

### 2.4 双实例

- dual hot c50：14.4 rps / P95 5863.1 ms；single hot c50：17.2 rps / P95 4283.8 ms。
- dual hot c100：12.1 rps / P95 11406.7 ms；single hot c100：17.7 rps / P95 7389.9 ms。
- dual multi-org c100：45.5 rps；single multi-org c100：48.2 rps。
增加 Gateway 实例没有提升单账期热点，反而因双倍 DB 线程/连接加剧同一行锁排队；扩容只有在解除账期锁竞争或按账期/组织分片后才有意义。

### 2.5 队列与资源

- `gateway_db_active` 达到配置上限 12，`gateway_db_queued` 随并发上升
  （c100/c200 的峰值见上表）；Hikari `pending=0`、`active=12`，说明瓶颈在
  数据库行锁而非连接池获取。
- Gateway 进程 CPU 多数样本低于单核 10%，GC 暂停毫秒级；Redis
  `EVALSHA` 平均 43.5 µs/次。它们不是主瓶颈。
- MySQL 容器在压测中约 40–65% CPU、约 350 MiB 内存；128 MiB buffer pool
  是环境限制，不能外推生产。

### 2.6 TX1 内的次级成本：`sumEffectiveReservations`

- **索引已经存在**：`V19__m12_budget_reservation.sql` 定义了
  `idx_budget_reservation_org_budget(org_id, budget_id, status)`，查询恰好按这三列过滤。
  `db-invariants.json` 的末态 `EXPLAIN FORMAT=JSON` 显示
  `key=idx_budget_reservation_org_budget`、`used_key_parts=[org_id,budget_id,status]`、
  `rows_examined_per_scan=2`、`query_cost=3.44`；查询仍需按 `reserved_amount` 回表
  （`used_columns` 包含该列）。
- **累计统计**（`performance_schema`，整轮）：32,696 次调用、平均 5.115 ms、
  合计 167.24 s、`SUM_ROWS_EXAMINED=133,731,580`、`SUM_NO_INDEX_USED=6,519`。
- **累计 `NO_INDEX_USED` 与末态 EXPLAIN 不能直接合并解读为“缺索引”**：
  1. **时间点不同**：`topSql` 是整轮累计（热身 + 全部场景 + 结算排空）；
     末态 `EXPLAIN` 是排空后的一次估算。此时预算快照的 `effectiveHolds=0`
     （EXPLAIN 估算约 2 行），索引范围比负载期小几个数量级，选择索引是必然结果，
     不能代表负载期的执行计划。
  2. **语义不同**：`SUM_NO_INDEX_USED` 统计“未使用索引的**执行次数**”
     （6,519/32,696≈19.9%），不是扫描行数；`EXPLAIN` 的
     `rows_examined_per_scan` 是**估算值**，不是实测扫描行数。两者都不能单独
     证明索引存在或缺失。
  3. **数据分布不同**：负载期 `budget_reservation` 从空表增长到约 3.26 万行，
     有效预占集合与 InnoDB 持久化统计（按阈值异步刷新）持续变化；非覆盖索引需要按
     `reserved_amount` 回表，代价模型可能在“二级索引 + 回表”与“全表扫描”之间切换。
     19.9% 的 no-index 占比与“轮内计划发生变化”相符，不能推断索引不存在。
  4. **归因不完整**：133,731,580/32,696≈4,090 行/次；若把扫描全部归到 6,519 次
     no-index 执行，则约 20,515 行/次。若这些执行发生在空表/小表阶段，不可能累计出
     该量级的扫描行数，因此它们更可能发生在表已具规模的阶段。封存产物没有逐次执行
     计划，无法把 no-index 精确归因到某个时间点；HC-02 必须补 `EXPLAIN ANALYZE`
     与逐阶段计划，而不是据此新增 DDL。
- **量级判断**：该项合计 167 s，约为 `billing_period ... FOR UPDATE` 13,829 s 的
  **1.2%**。它是 TX1 临界区内的次级成本，不是首要瓶颈；现有证据不支持新增索引，
  更不支持重复创建 `(org_id, budget_id, status)` 索引。
- **HC-02 方向（本轮不执行）**：在真实热点数据规模下采集 `EXPLAIN ANALYZE`
  （实测扫描行数、回表次数、`Handler_read_*`）与负载期执行计划，确认索引是否被选中、
  有效预占集合多大；再评估覆盖索引 `(org_id, budget_id, status, reserved_amount)`
  或查询改造的 A/B 收益，以及新增列/索引对预占 INSERT/UPDATE 的写放大。
  `COMMIT` 累计 162,069 次、平均 20 ms，也说明每请求多次短事务是第二层成本。

## 3. Settlement 干扰

`mixed` 对照在相同热点下把 P50 从 2716.8 ms 推到 3707.8 ms（+36%）、
P95 从 4283.8 ms 推到 5472.7 ms（+28%）；DB 队列峰值同为 39，但 mixed 吞吐
12.8 rps vs 17.2，样本 224 vs 369–397。Settlement 每条结算同样先锁同一账期行，
再锁预算并写 Ledger；在线准入与批量结算共享同一行锁。报告把 Gateway 矩阵与
mixed 对照分开，不把 mixed 的低 RPS 当作 Gateway 容量。

## 4. 并发正确性

- 预算：每轮 `actual + committed + effective holds ≤ total`，违例 0；
  最终预算未超扣。
- 同键：50/100 并发只产生 1 次上游调用、1 个请求身份；其余 409。
- 预算耗尽：可覆盖的 2 个请求成功，其余 429；无超扣。
- 断流/取消：usage `UNKNOWN`、reservation `PENDING_HOLD`、无 Settlement；
  SSE 许可归零。
- FINAL→Settlement→Ledger：最终收敛后 1:1:1，无重复 posting_key、无孤儿、
  无非 FINAL 结算、无非正金额结算。
- 缺陷 1：SSE 请求在取得许可后、`invokeStream` 之前失败时泄漏许可；已修复并由
  `StreamPermitCeilingIntegrationTest` 覆盖（强制 Redis 熔断打开，断言许可归零、
  无上游调用）。
- 缺陷 2（审查补充）：取得许可后、`rateLimiter` / `quotaLimiter` /
  `orchestrator.prepareInitial` 尚未完成时客户端取消订阅，只会触发
  `doFinally(CANCEL)` 而不会触发 `onErrorResume`，原实现同样泄漏许可。已用可控
  `Mono.never()` + 主动 cancel 在三个阶段分别复现（`activeStreams` 停留 1），
  修复后归零且无上游调用；见
  [`2026-11-11-hc-01-review-followup.md`](2026-11-11-hc-01-review-followup.md)。

## 5. HC-02 建议（按优先级）

1. **打破账期锁与账期外键共享锁的排队**：优先评估“关闭围栏不落在被
   `gateway_request` 外键引用的行上”（专用锁行/版本行），或 OPEN 期间用共享锁、
   Close 时排他升级；必须保留 Close 串行化、预算不变量与锁顺序，并用并发 +
   故障回归证明。禁止用 Redis 计数替代 MySQL 财务裁决。
2. **缩短 TX1 临界区（先测量，不先加索引）**：`budget_reservation`
   `(org_id,budget_id,status)` 索引**已存在**（V19
   `idx_budget_reservation_org_budget`），本轮不新增任何重复 DDL。HC-02 先在真实
   热点数据规模下用 `EXPLAIN ANALYZE` / 负载期执行计划核对索引选择、实际扫描行数、
   `reserved_amount` 回表成本与有效预占集合大小；再评估覆盖索引
   `(org_id,budget_id,status,reserved_amount)` 或查询改造的 A/B 收益与写放大，
   只有收益可量化且财务不变量不变时才实施。
3. **协调结算与准入**：评估 Settlement Worker 的账期锁占用/批处理节奏，
   减少与在线准入的排队；保留 DB Scanner 兜底与结算幂等。
4. **再调资源**：当前 12 DB 线程/12 连接在多组织无锁场景也接近上限；
   只有在锁问题修复后用同环境 A/B 决定是否调大，禁止先调大掩盖锁竞争。
5. **生产复测**：在接近生产的 MySQL（buffer pool、磁盘、独立压测机）重跑同一
   harness，才能给出生产容量/SLA 结论。

## 6. 明确不做

Kafka、Redis Stream、分库分表、Redis 财务锁、多 Region HA、Kubernetes、
重写 Ledger/导入 Worker、RabbitMQ/Outbox（HC-03 另评）、关闭准入/限流保护。
