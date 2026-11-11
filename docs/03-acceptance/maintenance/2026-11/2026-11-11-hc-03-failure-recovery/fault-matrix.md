# HC-03 故障注入矩阵与实测结果（2026-11-11）

> 数据来源：[`results.json`](results.json)（逐轮 checks/metrics/timeline）、
> [`events.jsonl`](events.jsonl)（逐事件时间）、[`db-invariants.json`](db-invariants.json)。
> 完整执行：`node scripts/perf/hc03/runner.mjs --reps=3 --skip-build`，执行 SHA 见 [`environment.json`](environment.json)。
> 所有依赖均为 HC-03 独占资源；共享 MySQL/Redis 未被停止、暂停或 FLUSH。

## 1. 总览

| ID | 故障 | 真实机制 | 重复 | 结果 | 关键阈值 |
|---|---|---|---|---:|---|
| F01-stop | Redis 不可达 | `docker stop ai-costops-hc03-redis` | 3 | **PASS** | 请求 503、Provider 0、恢复后 200 |
| F01-pause | Redis 已连接但静默（黑洞） | `docker pause`（仅测试容器） | 3 | **PASS** | 500ms 命令超时内 fail closed |
| F02 | MySQL 停机（TX1/TX2 前不可达） | `docker stop ai-costops-hc03-mysql` | 3 | **PASS** | 503、无请求行/无派发、连接池恢复 |
| F03a | Gateway 在明确未派发窗口崩溃 | 外部会话持账期 X 锁 → `taskkill /F` | 3 | **PASS** | attempt PLANNED、无预留、无 Provider 调用、恢复为 FAILED_PRE_DISPATCH |
| F03b | Gateway 在 TX2 后崩溃 | Mock `hold` → `taskkill /F` | 3 | **PASS** | PENDING_HOLD、无 FINAL、无结算、同键重放 409 |
| F04 | A 崩溃、B 继续服务 | 持有 A 的 SSE → `taskkill /F` → 请求发往 B | 3 | **PASS** | B 新请求 200、旧 SSE 断开、不透明重放 409 |
| F05-kill | 结算 Worker 在事务中崩溃 | 锁 reservation → 重启 Backend → 阻塞时 `taskkill /F` | 3 | **PASS** | 0 posting/0 Actual 变化；重启后唯一 1:1 结算 |
| F05-db | 结算期间数据库暂断 | `docker stop` 专属 MySQL（Backend 存活） | 3 | **PASS** | 健康/日志可见；恢复后唯一结算 |
| F06 | 真实 Close × Gateway 并发 | 真实 HTTP Close + 6 并发请求 | 3 | **PASS（专项部分 PARTIAL）** | Close=BLOCKED、`PENDING_GATEWAY_FINANCIAL_WORK=FAIL`、账期回到 OPEN |

## 2. 实测时间线（每场景 3 次的中位数）

| 场景 | t_detect | t_restore（依赖/进程恢复） | t_healthy（新请求/结算成功） | 备注 |
|---|---:|---:|---:|---|
| F01-stop | 13 ms | 777 ms | 232 ms | Redis 拒绝连接，失败立即发生 |
| F01-pause | 567 ms | 830 ms | 213 ms | 静默 Redis 由 500 ms 命令超时封顶 |
| F02 | 30 012 ms | 34 241 ms | 4 938 ms | **30 s 来自 Hikari `connectionTimeout` 产品默认值** |
| F03a | 481 ms（kill 完成） | 10 669 ms | 786 ms | TTL=20 s + 3 s 扫描后转 FAILED_PRE_DISPATCH（观测约 20–25 s） |
| F03b | 544 ms（kill 完成） | 8 352 ms | 692 ms | TTL=20 s 后转 PENDING_HOLD（观测约 20–25 s） |
| F04 | 479 ms（kill 完成） | 7 663 ms（A 重启） | B 接管 167 ms | **167 ms 是客户端显式切到 :18082，不是入口自动切换** |
| F05-kill | 486 ms | 16 176 ms（Backend 重启） | 351 ms（结算收敛） | 事务回滚 + 重启后单次结算 |
| F05-db | 6 010 ms（探针窗口） | 3 078 ms（MySQL 启动） | 5 376 ms | readiness/日志可见，Backend 未崩溃循环 |
| F06 | — | — | Close 64–111 ms | Close 返回 BLOCKED；未观察到 CLOSING 窗口内的 503 |

> `t_detect` 是“注入后第一次观测到故障语义”的时间：F01 为失败响应到达；F02 为 503 到达；
> F03/F04/F05 为进程确认消失。`t_restore` 是从 detect 到依赖/进程重新 ready。
> `t_healthy` 是从 restore 到新请求 200（F05 为结算 SETTLED）。

## 3. 逐场景证据

### F01 Redis 停服 / 黑洞

- 注入前：预热请求 200，Provider 调用 1 次。
- 停服后：连续 2 个请求均 **HTTP 503 `GATEWAY_DEPENDENCY_UNAVAILABLE`**；Provider 调用 **0**；
  被拒绝的幂等身份 **没有生成 `gateway_request` 行**（限流在准入前）。
- 黑洞（`docker pause`）：失败延迟稳定在 ~567 ms（中位），符合 `spring.data.redis.timeout=500ms` +
  `connect-timeout=1s` 的上界；**没有**因本地熔断降级而放行。
- 恢复后（同一 Redis 容器）：新幂等身份 200，Provider **恰好 1 次**，FINAL→Settlement→Ledger
  各恰好 1 条；预算未超扣。
- 6/6 轮 PASS；无 `FLUSHALL`、无共享 Redis 操作。

### F02 MySQL 停机（TX1/TX2 前）

- 停机后：A/B 两个实例的请求均 **503**，Provider **0**；失败请求若已创建请求行，均无
  `DISPATCH_INTENT`、无 usage、无 settlement（`failedRequestDispatchEvidence` 检查）。
- 失败延迟中位数 **30.0 s**：来自 Hikari 默认 `connectionTimeout=30s`。这是当前产品默认行为，
  属“有界但偏慢”的 fail-closed；未在本轮修改（见残余风险 R-1）。
- 恢复后：同一连接池无需重启即可重新取连接；新请求 200，Provider 1 次，FINAL/结算各 1；
  `/actuator/health/readiness` 在停机期间非 200。
- 3/3 轮 PASS。

### F03a Gateway 在确定未派发窗口被 SIGKILL

- 外部 MySQL 会话先对 `billing_period` 行持 X 锁（`FOR UPDATE NOWAIT` 复验返回 3572）。
- 请求在 `gateway_request` 中为 `VALIDATED`，route attempt 持久化为 `PLANNED`，
  `budget_reservation=0`，`data_lock_waits=1`（真实 TX1 阻塞）时执行 `taskkill /F /T`。
- kill 后：客户端连接重置（无伪造 200）；attempt 仍 `PLANNED`、`dispatch_intent_at NULL`；
  Provider **0**；预算 Actual/Committed/holds 与注入前一致。
- 恢复调度（Gateway B 共享 DB）：TTL 到期后请求转 `FAILED_PRE_DISPATCH`，无 usage、无 settlement；
  **没有**因 TTL 直接判零成本或补派发。
- 重启 Gateway A 后新请求 200；只有真实请求结算（1:1），被 kill 的请求永不结算。
- 3/3 轮 PASS。

### F03b Gateway 在 TX2 后 / Provider 可能已执行时被 SIGKILL

- Mock Provider `hold` 已收到请求（`held=1`，`post_chat_completions=1`）后 kill。
- kill 后：请求 `UPSTREAM_ACTIVE`（或 `DISPATCH_INTENT`），**无 usage 行**（没有伪造 UNKNOWN/FINAL），
  预留为 `ACTIVE`。
- TTL 到期后：预留转 `PENDING_HOLD`，**没有释放**；无 FINAL、无 settlement。
- 同幂等身份重放到健康实例：**409 `GATEWAY_REQUEST_IN_PROGRESS`**，Provider 调用仍为 1。
- 重启 A 后新身份 200；只有新请求产生 FINAL/Settlement/Ledger（各 1）。
- 3/3 轮 PASS。

### F04 Gateway A 崩溃、B 继续服务

- A 上存在被 Provider hold 的 SSE（`UPSTREAM_ACTIVE` + ACTIVE 预留）时 kill A。
- A 的 SSE 客户端连接断开（无 `[DONE]` 伪造完成）；B 在 **167 ms（中位）** 内成功处理新请求。
- 用 A 的旧幂等身份向 B 重放：**409**，Provider 调用不增加（无透明重放）。
- A 的预留最终为 `PENDING_HOLD`；B 的新请求正常 FINAL→SETTLED；无重复 FINAL、无预算超扣。
- 重启 A 后新请求 200。
- **边界**：没有入口负载均衡器；B 的接管是 harness 显式选择端口，不能表述为自动故障切换。
- 3/3 轮 PASS。

### F05 结算 Worker 崩溃 / 数据库暂断

- 前置：Backend 停止时生成 FINAL usage，外部会话锁住该请求的 reservation 行，再启动 Backend；
  结算事务取得账期 + 预算锁后阻塞在 reservation 行（`data_lock_waits>0`）时 `taskkill /F`。
- kill 后：0 ledger posting、Budget Actual 未变化、settlement 未 SETTLED；MySQL 回滚了未提交事务。
- 重启 Backend：结算收敛为唯一 SETTLED，1 posting / 1 entry / 唯一 posting_key；
  `FINAL = SETTLED = postings`；预算 Actual 只增加一次。
- 数据库暂断：同一前置下真实 `docker stop` MySQL，Backend 存活、readiness 非 200、日志出现
  DATABASE/Hikari 依赖信号；MySQL 恢复后无需重启 Backend，结算自动收敛且无重复 posting。
- 3/3 + 3/3 轮 PASS。
- **未能证明**：进程在“commit 成功但响应未返回”的微秒级边界崩溃；该边界由
  `GatewaySettlementTransactionIntegrationTest` 的 5 个事务注入点覆盖（见审计）。

### F06 真实 Close × Gateway 并发

- 通过 DevAuthenticationBootstrap 在**已存在的专用 F06 组织**上获得 `PERIOD_CLOSE`，用真实
  `POST /api/v1/billing-periods/{periodId}/close` 触发 Close。
- Close 前存在一个 `UPSTREAM_ACTIVE` + ACTIVE 预留的已派发请求（Mock hold）。
- Close 返回 **200 / runStatus=BLOCKED / periodStatus=OPEN**，blocker 列表含
  **`PENDING_GATEWAY_FINANCIAL_WORK = FAIL, itemCount=2`**（请求 + 预留），证明 Close blockers
  看见了已提交的派发事实；`OPEN_MATERIAL_RECONCILIATION` 同时因未完成的合成对账 case 为 FAIL
  （如实记录，不作为网关证据）。
- 并发 6 个 Gateway 请求：**6/6 200**，0 个 503；没有观察到 CLOSING 窗口（Close 仅 64–111 ms）。
  Provider 调用 = 6 + 1（held）；账期最终回到 OPEN；全部请求 FINAL→SETTLED 1:1；
  Gateway 无重复 attempt、无预算超扣。
- 3/3 轮 PASS。
- **PARTIAL**：Close 先胜（CLOSING）时的 TX1/TX2 fail-closed 由确定性集成测试覆盖
  （`PeriodLockSharedConcurrencyIntegrationTest#closingPeriodRejectsAdmissionAndDispatch`、
  `DispatchFenceIntegrationTest#closeWinsLockRejectsBeforeDispatch…`），本轮真实 Close 窗口过短，
  未在真实流量中命中。

## 4. 财务不变量验收（全部场景共同）

| 不变量 | 结果 | 证据 |
|---|---|---|
| 预算/hold 不超扣 | PASS | `db-invariants.json` 所有 fixture `overspent=false`；`budget.violations=[]` |
| 同一幂等请求不重复派发 | PASS | F03b/F04 重放 409；Provider 调用数不增加；`multiAttempt=0` |
| 已确认 FINAL 不重复结算 | PASS | `FINAL = SETTLED = postings`；无重复 `posting_key`/`source_id` |
| Settlement/Ledger 无重复 posting | PASS | `ledger.duplicatePostingKeys=0`、`duplicateSources=0`、`badEntryCardinality=0` |
| UNKNOWN/INCOMPLETE 不伪造 FINAL | PASS | F03b 无 usage 伪造；F05 只结算 FINAL；超时/取消路径为 UNKNOWN（历史用例 + 本轮观察） |
| 不确定 Provider 是否执行时不自动重试 | PASS | F03b 重放 409；恢复不补派发；Provider 调用 0/1 的逐轮断言 |
| 不错误释放 PENDING_HOLD | PASS | F03b/F04 预留 `PENDING_HOLD`，无 `RELEASED` |
| CLOSING/CLOSED 不绕过围栏 | PASS（确定性测试）/ PARTIAL（真实窗口） | 现有集成测试 + F06 真实 Close |
| 恢复失败/人工对账可诊断 | PASS | 503 错误码、readiness、Backend 日志依赖信号、`PENDING_HOLD` 保留 |

## 5. 判定汇总

| 判定 | 对象 |
|---|---|
| **PASS** | F01-stop (3/3)、F01-pause (3/3)、F02 (3/3)、F03a (3/3)、F03b (3/3)、F04 (3/3)、F05-kill (3/3)、F05-db (3/3)、F06 Close-blocked 路径 (3/3) |
| **PARTIAL** | F06“Close 先胜 → CLOSING 窗口内 TX1/TX2 fail closed”只在确定性集成测试中证明；真实 Close 窗口 130 ms 未命中 |
| **NOT_VERIFIED** | MySQL 单节点断电期间财务 API 持续可用；Gateway 流连接无缝迁移；跨机/跨 Region 高可用；生产 SLA/RTO/RPO；真实 Provider 故障 |
| **FAIL** | 无（最终执行 0 财务违例） |

