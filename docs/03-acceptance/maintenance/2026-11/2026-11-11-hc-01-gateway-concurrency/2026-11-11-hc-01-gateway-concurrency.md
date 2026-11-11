# HC-01 AI Gateway 单/双实例高并发基线验收（2026-11-11）

## 1. 元数据

| 项 | 值 |
|---|---|
| 日期 | 2026-11-11 |
| 责任人 / AI | BangShou1st / Claude Code |
| 关联 Issue | [#176 HC-01｜AI Gateway 双实例高并发基线与热点预算竞争验证](https://github.com/BangShou1st/AI-CostOps/issues/176) |
| 基线 | `main = dd53916d76711a537fd332edce75795ceb95109b` |
| 工作分支 | `perf/hc-01-gateway-load-baseline` |
| 代码/证据 SHA | `5ca7df839fa3340fec67c325ed08de6db687440c`（证据运行 HEAD；Gateway/Backend 字节码由 `bba23b5` 构建，其后仅汇总脚本变更） |
| 环境 | 见 [`environment.json`](environment.json)；本机 Windows 11 + Docker Desktop |
| 结论 | `PASS` |
| 影响边界 | 仅本机受控基线；不代表生产 QPS、跨地域 HA 或真实 Provider 容量 |

## 2. 结论摘要

- **可复现的双实例基线已建立**：真实 MySQL 8.4 / Redis 8.8、真实 Backend/Flyway、
  受控 Mock Provider、两个**独立 JVM** Gateway 共享同一数据库与 Redis。
- **发现并量化首要瓶颈**：`billing_period` 行级排他锁（预算准入 TX1 起手获取并持有到
  commit）与 `gateway_request` 对 `billing_period` 的外键共享锁形成排队；单账期吞吐在
  5→100 并发下基本平台化，同账期多预算（7 个额外预算）几乎不改善，多组织（8 个账期）
  才明显提升。
- **并发正确性通过**：预算不超扣、同键风暴只产生 1 次上游调用、预算耗尽只准入可覆盖
  的请求、断流/取消不产生 FINAL/结算、FINAL→Settlement→Ledger 唯一且最终收敛、SSE
  许可在完成/取消/异常后归零。
- **双实例不解决单账期热点**：dual hot c50/c100 为 14.4/12.1 rps，低于单实例的
  17.2/17.7；多组织 dual 45.5 rps 与单实例 48.2 同量级。共享 MySQL 才是上限，
  不能把“加 Gateway 实例”当作扩容手段。
- **发现并修复一个真实资源缺陷**：SSE 请求在取得流许可后、`invokeStream` 之前失败
  （限流/配额/路由/熔断）时，原实现不会释放许可；一次熔断窗口即可耗尽 128 个许可。
  已最小修复并新增集成回归（`StreamPermitCeilingIntegrationTest`），本基线复测通过。
- **本批次不做** RabbitMQ/Outbox、预算事务重构、账本迁移、分库分表或 Kubernetes；
  未对核心财务语义做任何修改。

## 3. 范围与非目标

本文件只覆盖 HC-00（基线冻结）与 HC-01（负载/热点/双实例/不变量）。
不包含 HC-02 性能优化、HC-03 消息驱动结算、HC-04 故障切换演练。

## 4. 环境与拓扑

```text
Node load client (single process, closed-loop concurrency)
        │  HTTP /v1/chat/completions
        ├──────────────► Gateway JVM #1 :18081 ─┐
        └──────────────► Gateway JVM #2 :18082 ─┤  real MySQL 8.4 :3307 (Docker)
                                                 ├─ real Redis 8.8 :16379 (dedicated container)
Backend JVM :18080 (Flyway + Settlement Worker) ─┘
Gateway → controlled Mock Provider :18089 (Python stdlib, deterministic modes)
```

- 专用数据库 `aicostops_hc01` 每轮重建；开发库 `aicostops` 未被修改。
- 专用 Redis 容器 `ai-costops-hc01-redis`（`127.0.0.1:16379`，无持久化），
  不复用开发 Redis。
- Gateway 数据库身份 `gw_hc01`：全库 SELECT + `LOCK TABLES`、5 张 Gateway 所属表的
  INSERT/UPDATE、M21 生产同款 Advisor Worker 最小授权；无 DELETE、无 GRANT OPTION、
  无财务表写权限。
- 资源限制（摘录，完整值见 `environment.json`）：Docker VM 约 1.86 GiB；
  MySQL `innodb_buffer_pool_size=128 MiB`，`innodb_flush_log_at_trx_commit=1`；
  Host i9-14900HX / 24C32T / 16 GiB；JDK 21.0.10；Node 24.14.0。
- Gateway/Settlement 参数：`dbThreads=12`、`dbQueueCapacity=256`、`dbPoolMax=12`、
  `maxActiveStreams=128`（SSE 探针 4）；高限流模式 capacity=500000、refill=50000/s、
  quota=100000000/day；正常限流模式（产品默认）capacity=60、refill=1/s、quota=1000/day。
- **测量隔离**：先跑一轮 `hot_fast_mixed_settlement_c50`（Settlement Worker 运行中）
  作为干扰对照；随后停止 Backend 并清理其数据库会话，完成 Gateway 矩阵；最后重启
  Backend（500ms 测试轮询）排空 FINAL→Settlement→Ledger。Gateway RPS 与 mixed 对照
  不混用。

## 5. Harness 与复现

```bash
npm run infra:up
npm run perf:hc01                 # 完整矩阵
node scripts/perf/hc01/runner.mjs --quick   # 快速验证 harness
```

Harness 复用既有 WebFlux、Redis Lua、MySQL 预算预占、Gateway Worker、Micrometer 与
M16 Mock Provider 协议；不新增运行时依赖。矩阵、产物、清理与边界见
[`scripts/perf/README.md`](../../../../../scripts/perf/README.md)。

## 6. 负载矩阵与结果

测量定义：`成功业务 RPS = 成功完成的 200 业务响应数 / 实际测量窗口`；429/409/5xx
不计入成功吞吐。每档热身 8s、测量 20s、3 轮（SSE 取消/断流/同键/预算耗尽为专项
正确性场景，不计吞吐）。

| 场景 | 实例 | 并发 | 轮次 | Provider | 成功 RPS（轮次中位） | P50 ms | P95 ms | P99 ms | 状态码 | Provider 调用 | DB 队列峰值 | Hikari 活跃峰值 | 行锁等待/轮 | 失败 |
|---|---:|---:|---:|---|---:|---:|---:|---:|---|---:|---:|---:|---:|---:|
| hot_fast_mixed_settlement_c50 | 1 | 50 | 1 | ok | 12.8（12.8–12.8） | 3707.8 | 5472.7 | 6502.0 | {"200":224} | 224 | 39 | 12 | 616 | 0 |
| hot_fast_single_c5 | 1 | 5 | 3 | ok | 14.9（14.8–15.8） | 316.2 | 496.8 | 656.2 | {"200":923} | 319/302/302 | 1 | 5 | 694/651/665 | 0 |
| hot_fast_single_c20 | 1 | 20 | 3 | ok | 17.0（16.8–17.4） | 1144.0 | 1768.8 | 2011.7 | {"200":1057} | 346/357/354 | 9 | 12 | 847/877/855 | 0 |
| hot_fast_single_c50 | 1 | 50 | 3 | ok | 17.2（16.7–18.1） | 2716.8 | 4283.8 | 4857.4 | {"200":1142} | 369/376/397 | 39 | 12 | 957/963/1036 | 0 |
| hot_fast_single_c100 | 1 | 100 | 3 | ok | 17.7（17.0–18.6） | 5327.7 | 7389.9 | 8310.9 | {"200":1258} | 434/417/407 | 89 | 12 | 1101/1097/1067 | 0 |
| hot_fast_single_c200 | 1 | 200 | 3 | ok | 16.5（16.4–18.9） | 10333.7 | 15036.9 | 16721.6 | {"200":1448} | 519/465/464 | 189 | 12 | 1375/1235/1227 | 0 |
| multi_budget_single_c100 | 1 | 100 | 3 | ok | 18.3（18.1–18.3） | 5097.5 | 7348.2 | 8375.4 | {"200":1307} | 446/431/430 | 89 | 12 | 1180/1131/1111 | 0 |
| multi_org_single_c100 | 1 | 100 | 3 | ok | 48.2（46.4–49.9） | 2005.3 | 2618.6 | 2864.5 | {"200":3107} | 1031/1007/1069 | 89 | 12 | 988/935/975 | 0 |
| slow_provider_single_c50 | 1 | 50 | 3 | delay | 15.2（13.5–16.6） | 3093.9 | 4837.2 | 5261.3 | {"200":1000} | 338/362/300 | 38 | 12 | 835/900/736 | 0 |
| sse_success_single_c8 | 1 | 8 | 3 | ok | 15.4（14.8–15.7） | 515.3 | 662.0 | 714.3 | {"200":384} | 124/128/132 | 1 | 9 | 301/296/301 | 0 |
| hot_fast_dual_c50 | 2 | 50 | 3 | ok | 14.4（14.3–16.1） | 2734.4 | 5863.1 | 6643.2 | {"200":1008} | 319/333/356 | 26 | 12 | 815/1033/899 | 0 |
| hot_fast_dual_c100 | 2 | 100 | 3 | ok | 12.1（11.5–15.7） | 6410.2 | 11406.7 | 15511.1 | {"200":1006} | 380/341/285 | 64 | 12 | 969/1068/794 | 0 |
| multi_budget_dual_c100 | 2 | 100 | 3 | ok | 14.8（13.6–16.9） | 5581.6 | 8785.7 | 11014.1 | {"200":1160} | 413/366/381 | 56 | 12 | 1072/1126/1230 | 0 |
| multi_org_dual_c100 | 2 | 100 | 3 | ok | 45.5（40.4–49.8） | 1927.8 | 4013.7 | 4782.7 | {"200":2981} | 1082/1014/885 | 84 | 12 | 1751/1691/1473 | 0 |

> P50/P95/P99 为 3 轮各自分位数的中位数；每轮样本数与窗口见 `load-results.json`。

## 7. 首要瓶颈与证据

### 7.1 单账期平台化

- 单实例热点：5/20/50/100/200 并发的成功 RPS 基本在同一量级（14.9–18.9，见上表），
  增加并发主要增加排队与 P95/P99，而不是吞吐。
- 同账期、7 个额外预算的 `multi_budget_single_c100` 与单预算热点同量级；
  说明瓶颈不是“某个 budget 行”，而是**共享的账期行**。
- 不同组织（不同账期）的 `multi_org_single_c100` 明显更高，且 `blockingObjects`
  的等待者降至 1–4 个（账期级排队消失）；其上限来自 12 个 `gateway-db` 线程/12 个连接
  与每请求多次短事务，属于第二层瓶颈。

### 7.2 SQL 与锁证据

`db-invariants.json` 中 `topSql`（真实 `performance_schema` 摘要）显示：

| digest | calls | total s | avg ms | rows examined | rows affected | no-index |
|---|---:|---:|---:|---:|---:|---:|
| SELECT STATUS FROM `billing_period` WHERE `id` = ? AND `org_id` = ? FOR UPDATE | 67276 | 13829.20 | 205.56 | 67287 | 0 | 0 |
| COMMIT | 162069 | 3238.03 | 19.98 | 0 | 0 | 0 |
| SELECT `gr` . `state` AS `request_state` , `gr` . `current_usage_fact_id` , `gr` . `dispatch_intent_at` , `ra` . `status` AS `route_status`  | 32627 | 1329.49 | 40.75 | 32627 | 0 | 0 |
| INSERT INTO `gateway_request` ( `org_id` , `public_request_id` , `credential_id` , `principal_type` , `organization_member_id` , `service_id | 32885 | 801.09 | 24.36 | 0 | 32750 | 0 |
| UPDATE `gateway_request` SET `state` = ? , `updated_at` = UTC_TIMESTAMP (?) WHERE `id` = ? AND `org_id` = ? AND `state` = ? | 32574 | 731.80 | 22.47 | 32598 | 32589 | 0 |
| INSERT INTO `gateway_settlement` ( `org_id` , `settlement_key` , `request_id` , `route_attempt_id` , `usage_fact_id` , `reservation_id` , `b | 29886 | 360.50 | 12.06 | 0 | 29886 | 0 |
| SELECT COALESCE ( SUM ( `reserved_amount` ) , ? ) FROM `budget_reservation` WHERE `org_id` = ? AND `budget_id` = ? AND STATUS IN (...) | 32696 | 167.24 | 5.12 | 133731580 | 0 | 6519 |
| SELECT `bp` . `id` , `bp` . `org_id` , `bp` . `period_start` , `bp` . `period_end` , `bp` . `status` , `bp` . `close_generation` , `bp` . `c | 29558 | 84.17 | 2.85 | 29558 | 0 | 0 |
| SELECT @@SESSION . `transaction_read_only` | 668477 | 60.17 | 0.09 | 668496 | 0 | 0 |
| SELECT ( SELECT COUNT ( * ) FROM `gateway_request` WHERE `org_id` = ? ) , ( SELECT COUNT ( * ) FROM `gateway_request` WHERE `org_id` = ? AND | 2336 | 54.23 | 23.22 | 54324063 | 0 | 2336 |
| INSERT INTO `gateway_route_attempt` ( `org_id` , `request_id` , `attempt_no` , `route_decision_id` , `routing_policy_id` , `provider_account | 32678 | 44.31 | 1.36 | 0 | 32673 | 0 |
| INSERT INTO `budget_reservation` ( `org_id` , `request_id` , `route_attempt_id` , `billing_period_id` , `budget_id` , `financial_scope_type` | 32623 | 42.51 | 1.30 | 0 | 32619 | 0 |

> 该 `performance_schema` 摘要是整轮累计（含最后的结算排空）；在线矩阵阶段的
> 锁等待与吞吐曲线见每轮 `blockingObjects`、RPS 与 P95。

`blockingObjects`（每轮采样 `performance_schema.data_lock_waits`）显示：
账期行的 X/S 等待者约 11–12 个，`gateway_request` 的 X 锁最高 66 个
（multi-budget c100）；多组织场景的等待者降至 1–4 个。

**TX1 内的次级瓶颈**：`sumEffectiveReservations`（`budget_reservation` 按
`org_id + budget_id + status` 求和）平均 5.12 ms，累计扫描 **1.337 亿行**，
其中 **6,519 次未命中索引**；它在账期/预算锁内执行，直接拉长临界区。最小候选是
为 `budget_reservation` 增加匹配 `(org_id, budget_id, status)` 的索引（只加索引、
不改财务语义），列入 HC-02 首选验证项。`COMMIT` 累计 162,069 次、平均 20 ms，
也说明每请求多次短事务是第二层成本。

机制：

1. `BudgetReservationService` 在 TX1 首先
   `SELECT ... FROM billing_period ... FOR UPDATE`，然后锁 budget、读有效预占、
   插入 reservation、更新 request，最后 commit；该 X 锁覆盖整个 TX1。
2. `gateway_request` 有外键 `fk_gateway_request_period_org (billing_period_id, org_id)
   → billing_period(id, org_id)`；每个并发请求在准入前的 `INSERT gateway_request`
   需要对该账期行取共享锁，于是排队在同一 X 锁之后。
3. `gateway_usage_fact` / `gateway_settlement` 等财务写入同样带账期外键，进一步放大排队。
4. 结果是账期行成为单点串行队列；增加并发只增加队列长度和尾延迟。

### 7.3 Settlement Worker 干扰

`hot_fast_mixed_settlement_c50` 与隔离后的热点场景相比，P50 从 2716.8 ms
升至 3707.8 ms（+36%），P95 从 4283.8 ms 升至 5472.7 ms（+28%）；DB 队列峰值
同为 39，但 mixed 吞吐 12.8 rps vs 17.2，且每轮样本更少（224 vs 369–397）。
Settlement 每条结算同样先锁同一账期行，再锁预算并写 Ledger；1s 测试轮询下
每轮最多处理 100 条，形成持续竞争。这不改变“MySQL 是最终财务真相”的设计，
但说明**在线准入与批量结算需要协调锁占用**。

### 7.4 资源侧

- Gateway 进程 CPU 低（单实例样本通常 <10% 单核），GC 暂停毫秒级；
  Hikari `pending=0`、`active=12`（池满）；`gateway_db_queued` 随并发上升。
- Redis Lua `EVALSHA` 平均约 30–50 µs/次（`INFO commandstats` 增量），不是瓶颈。
- MySQL 容器 CPU 在压测中约 50–65%，内存约 320 MiB；128 MiB buffer pool 是环境限制，
  不能把本机数字外推为生产容量。

## 8. 并发正确性证据

| 场景 | Provider 调用 | 状态分布 | 关键观测 | 失败 |
|---|---:|---|---|---:|
| idempotency_same_key_c50 | 1 | {"200":1,"409":49} | count=50 | 0 |
| idempotency_same_key_c100 | 1 | {"200":1,"409":99} | count=100 | 0 |
| budget_exhaustion_c10 | 2 | {"200":2,"429":8} | count=10 | 0 |
| sse_ceiling_probe | 4 | {"200":1,"429":1} | hold_active=4（4 个持有流释放后均 200）；after_release=0；ceiling+1=429；fresh=200；count=5 | 0 |
| sse_cancel_release | 4 | {} | hold_active=4；after_cancel=0；usage={"UNKNOWN":4}；reservations={"PENDING_HOLD":4}；settlements=0；count=4 | 0 |
| provider_disconnect_unknown | 5 | {"502":5} | usage={"UNKNOWN":5}；reservations={"PENDING_HOLD":5}；settlements=0；count=5 | 0 |

- **预算不超扣**：每轮核对 `actual + committed + effective holds ≤ total`；
  所有预算 `violations=0`。
- **同键风暴**：50/100 个相同 `Idempotency-Key` 并发只产生 1 次 Provider 调用与
  1 个请求身份；其余为 `409 GATEWAY_REQUEST_IN_PROGRESS`（不计吞吐）。
- **预算耗尽**：`REQUIRED` 小预算下并发 10 个请求，只有可覆盖的 2 个成功，
  其余 `429 GATEWAY_BUDGET_EXHAUSTED`；无超扣。
- **断流**：Mock 主动截断连接，5 个请求均为 `502 GATEWAY_UPSTREAM_FAILED`，
  usage 为 `UNKNOWN`，reservation 为 `PENDING_HOLD`，无 Settlement/Ledger。
- **SSE 上限**：独立探针 Gateway（ceiling=4）占满 4 个许可后，ceiling+1 返回
  `429 GATEWAY_RATE_LIMITED` 且无额外 Provider 调用；释放后新流 200 + `[DONE]`。
- **SSE 取消**：4 个流占满后客户端取消，`gateway_active_streams` 归零，
  usage `UNKNOWN`、reservation `PENDING_HOLD`、无 Settlement。
- **SSE 完成**：8 并发成功流（每轮 124–132 个）全部收到 `[DONE]`，流许可最终归零。
- **FINAL→Settlement→Ledger**：最终收敛后每个 FINAL 恰好 1 个 SETTLED，
  每个 SETTLED 恰好 1 条 Ledger posting、1 条 entry；无重复 posting_key、
  无 SETTLED 孤儿、无非 FINAL 结算、无非正金额结算。
- **缺陷修复**：预流失败（限流/配额/路由/熔断）曾泄漏 SSE permit；修复位于
  `ChatCompletionController`，回归见 `StreamPermitCeilingIntegrationTest`
  （强制 Redis 熔断打开，断言许可归零且无上游调用）。

## 9. 双实例与限流

| 场景 | 实例 | 并发 | 轮次中位 RPS | P95 中位 ms | 状态码（合并） | Provider 调用 | DB 队列峰值 |
|---|---:|---:|---:|---:|---|---:|---:|
| hot_fast_dual_c50 | 2 | 50 | 14.4 | 5863.1 | {"200":1008} | 319/333/356 | 26 |
| hot_fast_dual_c100 | 2 | 100 | 12.1 | 11406.7 | {"200":1006} | 380/341/285 | 64 |
| multi_budget_dual_c100 | 2 | 100 | 14.8 | 8785.7 | {"200":1160} | 413/366/381 | 56 |
| multi_org_dual_c100 | 2 | 100 | 45.5 | 4013.7 | {"200":2981} | 1082/1014/885 | 84 |
| rate_limit_normal_c20（补充验证，harness 修正后） | 1 | 20 | 6.9 | 每轮见注 | r1: 69x200 / 1319x429；r2: 69x200 / 4636x429；r3: 69x200 / 7398x429 | 69/69/69 | — |

- 双实例共享同一 MySQL/Redis；单账期热点不会因增加 Gateway 实例而提升，甚至因
  双倍 DB 线程/连接加剧同一行锁排队而略降；多组织双实例与单实例同量级，说明当前
  共享 MySQL 才是上限。不能把“加 Gateway 实例”当作扩容手段。
- 正常限流模式使用产品默认 60/1s，成功与 `429` 分开统计；未在生产或隔离环境中
  关闭任何安全保护。提高阈值只发生在隔离的 HC-01 数据库/独立进程，用于观察系统容量。
- 正常限流的**补充验证**（同一 SHA/种子/DB/Redis，harness 修正 token 子键重置后）
  三轮均为 **69×200 + 大量 429**，Provider 调用等于 200 数；429 不计入吞吐。
  主运行的 r1 同为 69×200；r2/r3 因 harness 只删了 rate-limit 基础键、未删
  `:tokens`/`:ts`，从补桶状态开始，不作为容量样本。该 harness 缺陷已修复；
  补充数据见 [`rate-limit-verification.json`](rate-limit-verification.json)。

## 10. HC-02 建议（有证据才做）

1. **优先处理账期锁/外键排队**：在不改变“MySQL 财务真相、Close 串行化、预占正确性”
   的前提下，评估把在线准入的账期排他锁改为不与 `gateway_request` 外键共享锁冲突的
   关闭围栏（专用锁行/版本行），或使用共享锁 + Close 排他升级；必须保留锁顺序与
   Close 语义，并用并发/故障回归证明。
2. **缩短 TX1 临界区**：先验证 `budget_reservation(org_id,budget_id,status)` 索引
   （只加索引，不改财务语义），把 `sumEffectiveReservations` 的扫描从亿级降到命中
   索引；再评估把可在锁外完成的定价/上界计算移出。任何改动必须保持财务不变量。
3. **协调结算与准入**：评估 Settlement Worker 的批处理/账期锁占用策略，减少与在线
   准入的排队；保留 DB Scanner 兜底与现有结算幂等。
4. **再评估资源参数**：当前 `dbThreads=12`/`dbPoolMax=12` 在无锁等待的多组织场景
   已是上限；只有在锁问题修复后用同环境 A/B 决定是否调整，禁止先调大掩盖锁竞争。
5. **生产容量复测**：在接近生产的 MySQL（buffer pool、磁盘、独立压测机）重跑同一
   harness，才能给出生产容量结论。

## 11. 未验证与边界

- `NOT_VERIFIED`：生产 QPS/SLA、真实 Provider 上限、跨可用区/多 Region、
  MySQL 主从自动切换、RabbitMQ/Outbox 结算延迟对比。
- 本机 Docker VM 1.86 GiB、MySQL 128 MiB buffer pool、压测客户端与 Gateway 同机；
  数字仅用于相对比较与瓶颈定位。
- 双实例没有外部负载均衡器；客户端按轮转分布到两个端口，不能表述为生产多活。
- 所有 SQL/锁/资源证据均来自本文件同 SHA 的真实运行；未跨旧 SHA 拼接指标。

## 12. 产物与复现

| 产物 | 内容 |
|---|---|
| [`baseline.md`](baseline.md) | HC-00 基线冻结：SHA、CI、运行时、资源、参数、命令 |
| [`environment.json`](environment.json) | SHA、主机/Docker/MySQL/Redis 版本、参数、限制 |
| [`load-results.json`](load-results.json) | 全部场景/轮次、窗口、状态、分位数、资源、锁链 |
| [`db-invariants.json`](db-invariants.json) | 最终结算快照、预算、SQL 摘要、EXPLAIN |
| [`rate-limit-verification.json`](rate-limit-verification.json) | 修正 token 子键重置后的正常限流 3 轮补充验证 |
| [`findings.md`](findings.md) | 瓶颈、证据与 HC-02 最小建议 |
| `scripts/perf/hc01/` | 一键 harness、Mock、种子、验证、汇总 |

复现：

```bash
npm run infra:up
npm run perf:hc01
node scripts/perf/hc01/summarize.mjs docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-01-gateway-concurrency/load-results.json
```
