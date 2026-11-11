# HC-01 findings — 首要瓶颈、证据与 HC-02 建议

> 运行 HEAD：`5ca7df839fa3340fec67c325ed08de6db687440c`（Gateway/Backend 字节码来自 `bba23b5`，其后仅汇总脚本变更）；
> 完整数据见 [`load-results.json`](load-results.json)、[`environment.json`](environment.json)、
> [`db-invariants.json`](db-invariants.json)。

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

### 2.6 TX1 内的次级瓶颈

- `sumEffectiveReservations`（`budget_reservation` 按 `org_id + budget_id + status`
  求和）平均 5.12 ms，累计扫描 **1.337 亿行**，其中 **6,519 次未命中索引**；
  它在账期/预算锁内执行，直接拉长临界区。
- `COMMIT` 累计 162,069 次、平均 20 ms；每请求多次短事务是第二层成本。
- 最小候选（HC-02）：为 `budget_reservation` 增加匹配
  `(org_id, budget_id, status)` 的索引；只加索引，不改财务语义。

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
- 缺陷：SSE 请求在取得许可后、`invokeStream` 之前失败时泄漏许可；已修复并由
  `StreamPermitCeilingIntegrationTest` 覆盖（强制 Redis 熔断打开，断言许可归零、
  无上游调用）。

## 5. HC-02 建议（按优先级）

1. **打破账期锁与账期外键共享锁的排队**：优先评估“关闭围栏不落在被
   `gateway_request` 外键引用的行上”（专用锁行/版本行），或 OPEN 期间用共享锁、
   Close 时排他升级；必须保留 Close 串行化、预算不变量与锁顺序，并用并发 +
   故障回归证明。禁止用 Redis 计数替代 MySQL 财务裁决。
2. **缩短 TX1 临界区**：先验证 `budget_reservation(org_id,budget_id,status)` 索引
   （只加索引，不改财务语义），把 `sumEffectiveReservations` 的扫描从亿级降到命中
   索引；再评估定价/上界计算移到锁外。任何改动必须先通过财务不变量。
3. **协调结算与准入**：评估 Settlement Worker 的账期锁占用/批处理节奏，
   减少与在线准入的排队；保留 DB Scanner 兜底与结算幂等。
4. **再调资源**：当前 12 DB 线程/12 连接在多组织无锁场景也接近上限；
   只有在锁问题修复后用同环境 A/B 决定是否调大，禁止先调大掩盖锁竞争。
5. **生产复测**：在接近生产的 MySQL（buffer pool、磁盘、独立压测机）重跑同一
   harness，才能给出生产容量/SLA 结论。

## 6. 明确不做

Kafka、Redis Stream、分库分表、Redis 财务锁、多 Region HA、Kubernetes、
重写 Ledger/导入 Worker、RabbitMQ/Outbox（HC-03 另评）、关闭准入/限流保护。
