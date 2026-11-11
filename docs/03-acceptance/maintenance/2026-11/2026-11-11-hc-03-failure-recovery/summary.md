# HC-03 Gateway 双实例故障接管、依赖中断与财务恢复验收（2026-11-11）

## 1. 元数据

| 项 | 值 |
|---|---|
| 日期 | 2026-11-11 |
| 责任人 / AI | BangShou1st / AI 编码代理 |
| 关联 Issue / PR | [#180](https://github.com/BangShou1st/AI-CostOps/issues/180) / 本分支独立 PR（不自动合并） |
| 基线 | `main = 561b3844ecfa689e73f816580a7fb11ef1957908`（HC-02 / PR #179 合并） |
| 工作分支 | `perf/hc-03-failure-recovery`（从最新 `main` 创建；`dev` 已 fast-forward 到同一提交） |
| 执行 SHA | 见 [`environment.json`](environment.json) 的 `git.sha` / `git.dirty`（完整演练时工作区干净） |
| 环境 | Windows 11 + Docker Desktop（VM 1.86 GiB）+ JDK 21.0.10 + Node 24.14；见 [`environment.json`](environment.json) |
| 结论 | **PASS**（9 个故障子场景 × 3 次全部通过；财务不变量 0 违例；含 2 项 PARTIAL/NOT_VERIFIED 边界） |
| 影响边界 | 本机受控环境；不代表生产 SLA/RTO/RPO、跨机/跨 Region 高可用或真实 Provider 故障 |

## 2. 结论摘要

- **真实故障，而不是 Mock 异常**：Redis 停服/黑洞、MySQL 停机、Gateway `taskkill /F /T`
  （Windows 上的 SIGKILL 等价物）、Backend 结算事务中 kill、真实 HTTP Close 全部实际执行。
  所有可停服务均为本任务独占容器，共享开发 MySQL/Redis 未被停止或 FLUSHALL。
- **故障期间安全拒绝**：Redis 停服 13 ms、黑洞 604 ms 内 503；MySQL 停机 30.0 s 内 503
  （Hikari 产品默认 `connectionTimeout`），Provider 调用数 **0**，无假成功、无无限等待。
- **崩溃不造成财务重复**：F03b/F04 中“可能已派发”的请求保留 `PENDING_HOLD`、无 FINAL、
  无 settlement、同幂等身份重放 409；只有真实完成的请求产生唯一的 FINAL→Settlement→Ledger。
- **双实例只提供对新请求的部分可用性**：A 被杀后 B 在 196 ms（中位）内服务新请求；
  旧 SSE 连接断开；harness 显式选择端口，**没有**入口负载均衡/自动接管。
- **结算可恢复**：事务中 kill 后 0 posting、Budget Actual 不变；重启后唯一 SETTLED，
  `FINAL = SETTLED = postings`；真实数据库暂断期间 Backend 存活且可诊断，恢复后自动收敛。
- **Close 围栏有效**：真实 Close 返回 `BLOCKED` 且 `PENDING_GATEWAY_FINANCIAL_WORK=FAIL(itemCount=2)`，
  证明 Close blockers 看见已提交的派发/预留事实；账期回到 `OPEN`，无重复 posting、无超扣。
- **未发现需要修改财务状态机的生产缺陷**；本轮新增 `GatewayDataSourceOutageIntegrationTest`
  把 TX1/TX2 数据源故障与连接池恢复变成 CI 可回归的确定性证据。

## 3. 范围与非目标

**做**：F01 Redis 限流/配额不可达与黑洞、F02 MySQL 停机、F03 Gateway 崩溃（派发前/后窗口）、
F04 双实例部分可用、F05 结算 Worker 崩溃与数据库暂断、F06 Close × Gateway 并发；
每场景 3 次真实重复，逐次记录注入/发现/恢复/健康时间与数据库终态。

**不做**：RabbitMQ/Kafka/Outbox、Redis 财务锁、分库分表、K8s、MySQL 主从、Redis Sentinel/Cluster、
多 Region、生产 LB、核心财务模型改造、对可能已发送请求的自动重试。生产 SLA/RTO/RPO、
MySQL 单节点断电期间 API 持续可用、Gateway 流连接无缝迁移均未验证。

## 4. 环境与拓扑

```text
Node harness
  ├─ 专属 MySQL 8.4  ai-costops-hc03-mysql  :13307   （可被真实 stop/start）
  ├─ 专属 Redis 8.8  ai-costops-hc03-redis  :16379   （可被真实 stop/start/pause）
  ├─ Backend JVM     :18080  Flyway + Settlement Worker（poll 500ms）
  ├─ Gateway JVM A   :18081  （独立进程，可被 taskkill /F）
  ├─ Gateway JVM B   :18082  （独立进程，可被 taskkill /F）
  └─ Mock Provider   :18089  （ok/delay/disconnect/http500/timeout/hold）
```

- 数据库 `aicostops_hc03`，Gateway 最小权限账号 `gw_hc03`（全库 SELECT + `LOCK TABLES` +
  5 张 Gateway 表 INSERT/UPDATE + Advisor 最小授权；无 DELETE、无 GRANT OPTION）。
- 测试短窗口：`hard-timeout=15s`、`header-timeout=8s`、`stream-idle=8s`、
  `reservation-ttl=20s`、`recovery-interval=3s`；均为测试配置，产品默认为
  600s/60s/60s/900s/60s。
- 资源实测：Docker VM 1.86 GiB；HC-03 MySQL 约 400–500 MiB；两个 Gateway 与 Backend
  共享同一宿主机 CPU；无外部负载均衡器。

## 5. 执行方式与重复次数

```bash
node scripts/perf/hc03/runner.mjs --reps=3 --skip-build
```

- 场景顺序：F01(stop) → F01(pause) → F02 → F03a → F03b → F04 → F05kill → F05db → F06。
- 每个场景 3 次独立执行；F01 的 stop/pause 是两个独立注入变体。
- 每次注入都记录 `t_inject / t_detect / t_restore / t_healthy`，并等待 recovery/settlement
  扫描稳定后再做终态裁决。
- runner 在每个 rep 后自愈依赖与进程，保证一次失败不会污染后续场景；退出时删除专属容器。

## 6. 逐场景结果

| ID | 结果 | t_detect（中位） | t_restore（中位） | 新请求/收敛 | 关键证据 |
|---|---|---:|---:|---:|---|
| F01 stop | PASS 3/3 | 13 ms | 784 ms | 1 237 / 245 ms | 503、Provider 0、恢复后 200 |
| F01 pause | PASS 3/3 | 604 ms | 836 ms | 251 ms | 500 ms 命令超时封顶，无放行 |
| F02 | PASS 3/3 | 30 014 ms | 34 408 ms | 4 074 ms | 503、无派发、连接池恢复 |
| F03a | PASS 3/3 | 453 ms | 9 273 ms | 905 ms | PLANNED、无预留、FAILED_PRE_DISPATCH |
| F03b | PASS 3/3 | 489 ms | 10 849 ms | 844 ms | PENDING_HOLD、无 FINAL、重放 409 |
| F04 | PASS 3/3 | 508 ms | 8 455 ms（A 重启） | B 接管 196 ms | B 200、旧 SSE 断开、无重放 |
| F05 kill | PASS 3/3 | 460 ms | 18 261 ms | 424 ms | 0 posting → 唯一 SETTLED |
| F05 db | PASS 3/3 | 6 080 ms | 3 150 ms | 5 609 ms | Backend 存活、恢复后唯一结算 |
| F06 | PASS 3/3（专项 PARTIAL） | — | — | Close 130–1 500 ms | BLOCKED + `PENDING_GATEWAY_FINANCIAL_WORK=FAIL(2)` |

完整矩阵、逐项断言与边界见 [`fault-matrix.md`](fault-matrix.md)。

## 7. 财务一致性验收

所有场景共同验证并通过：

1. 预算 `actual + committed + effective holds ≤ total`，无超扣；
2. 同一幂等身份不重复派发（重放 409，Provider 调用数不增加）；
3. 已确认 FINAL 不重复结算，`FINAL = SETTLED = ledger postings`，无重复 `posting_key`/`source_id`；
4. UNKNOWN/INCOMPLETE/缺失 usage 不伪造 FINAL、不按零费用结算；
5. 无法确定 Provider 是否执行时不自动重试派发、不释放 PENDING_HOLD；
6. CLOSING/CLOSED 不绕过写围栏（确定性测试 + F06 真实 Close）；
7. 恢复失败可诊断：503 错误码、readiness、Backend 依赖日志、`PENDING_HOLD` 保留。

最终 [`db-invariants.json`](db-invariants.json)：`violations = []`，所有 fixture `overspent=false`，
无重复 posting / 无 settlement 绑定非 FINAL usage / 无 SETTLED 缺 posting。

## 8. 缺陷、修复与新增回归

- **生产缺陷**：本轮**未发现**需要修改财务状态机或事务语义的缺陷。所有故障下均满足既定的
  fail-closed 与财务不变量。
- **新增确定性回归**：`gateway/src/test/java/com/aicostops/gateway/request/GatewayDataSourceOutageIntegrationTest`
  ——JDBC 代理在 TX1 `INSERT budget_reservation` 或 TX2 `FOR SHARE` 处注入真实 SQLException：
  TX1 失败后无预留、请求仍 VALIDATED、attempt PLANNED；TX2 失败后请求不进入 DISPATCH_INTENT、
  预留仍唯一且 ACTIVE；清除注入后同一连接池恢复；连续 3 次 TX2 失败不释放/不重复持有。
  该测试在本地通过（3/3），并随 Gateway failsafe 集成测试运行。
- **Harness 修正（非产品缺陷）**：F03a 的预算基线改为在预热请求结算之后采集；`--attach`
  复用持久化运行密钥；子进程 detached 以保证 `--keep-running` 生效。这些只影响证据采集工具。

## 9. 判定汇总

| 判定 | 内容 |
|---|---|
| **PASS** | F01（stop/pause）、F02、F03a、F03b、F04、F05kill、F05db、F06（Close blocked 路径），各 3/3 |
| **PARTIAL** | F06“Close 先胜 → CLOSING 窗口内 TX1/TX2 fail closed”仅在确定性集成测试中证明，真实 Close 窗口 130 ms 未命中 |
| **NOT_VERIFIED** | MySQL 单节点断电期间财务 API 持续可用；Gateway 流连接无缝迁移；跨机/跨 Region 高可用；生产 SLA/RTO/RPO；真实 Provider 故障 |
| **FAIL** | 无 |

## 10. 残余风险与生产不可验证项

| ID | 风险/限制 | 影响 | 建议 |
|---|---|---|---|
| R-1 | MySQL 不可达时 Hikari 默认 `connectionTimeout=30s` 才 503 | 故障期间请求排队 30s，可能放大上游超时 | 评估将 Gateway 数据源 `connection-timeout` 收紧到 3–5s；需单独评审产品默认值 |
| R-2 | 两个 Gateway 无入口 LB；F04 是客户端显式切换端口 | 不能表述为生产自动故障切换/多活 | 生产需要入口健康检查与连接排空策略（不在本轮范围） |
| R-3 | 未证明“commit 成功但响应未返回”的微秒边界崩溃 | 该边界理论上可能重复结算 | 已由事务注入点测试覆盖回滚语义；真实进程级需内核/故障注入工具，标 NOT_VERIFIED |
| R-4 | Close `beginOrResume` 有 3 次 deadlock 重试；finalize 不重试 | 重试耗尽会显式 500，不会静默吞掉 | 已有界；本轮未真实制造死锁耗尽，建议后续补 Chaos 注入 |
| R-5 | `docker pause` 模拟 Redis 黑洞依赖 Docker 行为 | 与真实网络丢包/半开连接不完全等价 | 结论限定为“已连接但静默”的本地可控黑洞 |
| R-6 | 单机、共享 Docker VM、测试短窗口 | 时间数字不能外推生产 RTO | 仅作为工程级恢复证据 |
| R-7 | F06 的合成组织有一个未解决对账 case，`OPEN_MATERIAL_RECONCILIATION` 同时 FAIL | Close 被阻断的原因不止网关一条 | 网关 blocker 的 `itemCount=2` 是独立直接证据；生产需同时满足对账 blocker |

## 11. 交付物

| 文件 | 内容 |
|---|---|
| [`runbook.md`](runbook.md) | 安全边界、环境、命令、场景、清理 |
| [`audit.md`](audit.md) | 阶段 A：代码路径与现有测试覆盖审计、本轮新增点 |
| [`fault-matrix.md`](fault-matrix.md) | 故障矩阵、逐场景实测时间线与证据、判定汇总 |
| [`environment.json`](environment.json) | 执行 SHA/分支/JAR sha256/镜像/端口/配置/资源 |
| [`events.jsonl`](events.jsonl) | 逐事件时间线（inject/detect/restore/verified/kill） |
| [`db-invariants.json`](db-invariants.json) | 最终预算、请求、预留、usage、settlement、ledger、账期状态与违例 |
| [`results.json`](results.json) | 逐轮 checks/timeline/metrics 与数据库快照 |
| `scripts/perf/hc03/` | 可重复入口与 harness（`npm run perf:hc03`） |
| `GatewayDataSourceOutageIntegrationTest` | TX1/TX2 数据源故障与连接池恢复回归 |
