# scripts/perf — 性能与并发基座（活跃）

本目录承载可重复执行、可复核的容量与并发正确性 harness。它不是业务功能，
也不改变产品语义；结果必须绑定执行 SHA、机器资源、进程数、配置与原始窗口。

当前包含：

| 目录 | 用途 |
|---|---|
| `hc01/` | HC-01 AI Gateway 单/双实例高并发基线、热点预算竞争、过载与 SSE 资源验证；HC-02 复用其 `--suite=hc02` 子集 |
| `hc02/` | HC-02 账期锁 A/B 对比：读取 baseline/optimized 的 `load-results.json`，输出 Markdown 与 `results.json` |
| `hc03/` | HC-03 真实故障注入与恢复：专属 MySQL/Redis 容器、双 Gateway 进程 kill、Backend 结算中断、真实 Close 并发；输出环境/事件/数据库不变量证据 |

## HC-01 一键入口

前置条件：

```text
Node.js ≥ 24
JDK 21
Python 3（Windows 使用 `py` launcher；可用 HC01_PYTHON 覆盖）
Docker（运行本机 MySQL 8.4 / Redis；`npm run infra:up`）
根目录 .env（MySQL/Redis/JWT/KEK/HMAC 配置）
```

```bash
npm run perf:hc01
```

等价的底层入口：

```bash
node scripts/perf/hc01/runner.mjs
node scripts/perf/hc01/runner.mjs --quick        # 快速验证 harness（1 轮、短窗口）
node scripts/perf/hc01/runner.mjs --build        # 先重新打包 backend/gateway
node scripts/perf/hc01/runner.mjs --skip-build   # 复用现有 target JAR
node scripts/perf/hc01/runner.mjs --keep-running # 跑完保留 Backend/Gateway/Mock 进程
```

runner 的行为：

1. **只重建/重置专用数据库** `aicostops_hc01`，不触碰开发库 `aicostops`；
2. 启动专用 Redis 容器 `ai-costops-hc01-redis`（`127.0.0.1:16379`，无持久化），
   不复用也不修改开发 Redis；
3. Backend 以原生 JVM 启动，执行 Flyway 并开启 Settlement Worker；
4. 创建最小权限 Gateway 数据库账号 `gw_hc01`：全库 SELECT + `LOCK TABLES`、5 张
   Gateway 所属表的 INSERT/UPDATE、M21 生产同款的 5 项 Advisor Worker 最小授权；
   无 DELETE、无 GRANT OPTION、无财务表写权限；
5. 写入合成组织/账期/预算/凭据/路由数据（原始 Gateway key 只落
   `.local-dev/hc01/seed.json`，不进入 Git）；
6. 启动受控 Mock Provider 与一个或两个**独立 JVM** Gateway；
7. 先跑一轮 `hot_fast_mixed_settlement_c50` 对照（Settlement Worker 运行中），
   再停止 Backend 并清理其数据库会话，完成 Gateway 矩阵；随后重启 Backend
   （500ms 测试轮询）把 FINAL Usage 排空到 Settlement/Ledger；
8. 执行每轮真实数据库不变量核对与最终结算收敛核对；
9. 输出 `environment.json`、`load-results.json`、`db-invariants.json` 与原始日志；
10. 停止本次创建的 Backend/Gateway/Mock 进程并删除专用 Redis 容器；
    **不停止开发用 MySQL/Redis/MinIO**。

固定端口（可用 `HC01_*` 环境变量覆盖）：Backend `18080`、Gateway `18081`/`18082`、
SSE 探针 Gateway `18083`、Mock Provider `18089`。专用数据库 `aicostops_hc01`。

## 负载矩阵（完整模式）

| 组 | 场景 | 并发/请求 | 轮次 | 说明 |
|---|---|---|---|---|
| 单实例热点 | `hot_fast_single_c5/20/50/100/200` | 5/20/50/100/200 | 热身后 3 轮 × 20s | 同组织+同账期+同预算+同凭据 |
| 同账期不同预算 | `multi_budget_single_c100` | 100 | 3 | 8 个预算/凭据共享一个账期 |
| 不同组织 | `multi_org_single_c100` | 100 | 3 | 8 个组织，各自账期/预算 |
| 慢上游 | `slow_provider_single_c50` | 50 | 3 | Mock 固定 500ms 延迟 |
| 幂等风暴 | `idempotency_same_key_c50/100` | 50/100 同键 | 各 1 | 只验证正确性，不计吞吐 |
| 预算耗尽 | `budget_exhaustion_c10` | 10 并发 | 1 | 小预算，验证不超扣 |
| SSE 上限 | `sse_ceiling_probe` | 上限 4 + 1 | 1 | 独立探针 Gateway，验证 429 且不派发上游 |
| SSE 取消 | `sse_cancel_release` | 4 | 1 | 取消后 permit 归零，UNKNOWN/PENDING_HOLD |
| 上游断流 | `provider_disconnect_unknown` | 5 | 1 | 不产生 FINAL、不按 0 结算 |
| SSE 成功 | `sse_success_single_c8` | 8 | 1 | 完成流带 `[DONE]`，permit 归零 |
| 双实例 | `hot_fast_dual_c50/100`、`multi_*_dual_c100` | 50/100 | 3 | 两个独立 JVM，共享 MySQL/Redis |
| 正常限流 | `rate_limit_normal_c20` | 20 | 3 × 10s | 产品默认 capacity=60/refill=1，区分 429 与成功 RPS |

吞吐一律按“成功业务完成数 / 实测窗口”计算；429、409、5xx 与客户端取消不冒充成功。
每档记录 P50/P95/P99、状态与错误码分布、Provider 调用数、逐秒完成数、Gateway
Micrometer（请求/预占/用量/限流/依赖、`gateway_db_active`、`gateway_db_queued`、
Hikari、GC、CPU、活跃 SSE）、MySQL 行锁/事务/线程压力、Redis Lua 调用耗时。

每轮结束核对：预算 `actual + committed + effective holds ≤ total`、无重复 FINAL、
COMPLETED 必有 FINAL、上游调用数等于成功数（幂等/取消/断流场景按各自期望）。
全部场景结束后等待 Settlement 收敛，核对 FINAL → Settlement → Ledger 的唯一性与
`UNKNOWN/INCOMPLETE` 不入账。

## HC-03 故障注入与恢复

```bash
npm run perf:hc03
node scripts/perf/hc03/runner.mjs --quick                 # 每场景 1 轮自检
node scripts/perf/hc03/runner.mjs --scenario=F01,F03post  # 指定场景
node scripts/perf/hc03/runner.mjs --reps=3 --build        # 重新打包后完整演练
node scripts/perf/hc03/runner.mjs --keep-running          # 保留环境
node scripts/perf/hc03/runner.mjs --attach --scenario=F03post --skip-build
node scripts/perf/hc03/runner.mjs --cleanup               # 只清理 HC-03 容器/进程
```

与 HC-01 的区别（也是本轮的全部价值）：

1. **独占依赖**：`ai-costops-hc03-mysql`（:13307）与 `ai-costops-hc03-redis`（:16379）由
   HC-03 创建，可被真实 `docker stop/start/pause`；共享 `ai-costops-mysql-1`/`ai-costops-redis-1`
   从不被停止、暂停或 `FLUSHALL`。
2. **真实进程故障**：Gateway A/B 与 Backend 是独立 JVM，用 `taskkill /F /T`（Windows 上的
   SIGKILL 等价物）终止；不是 Mock 异常。
3. **确定性崩溃窗口**：外部 MySQL 会话持有账期/预留行 X 锁，把请求精确停在“attempt 已 PLANNED、
   TX1 未提交”或“结算事务已持锁”的窗口，再杀进程。
4. **派生证据**：`environment.json`（SHA/JAR/镜像/配置）、`events.jsonl`（逐事件与 t_inject/
   t_detect/t_restore/t_healthy）、`db-invariants.json`（财务不变量）、`results.json`（逐轮明细）。
5. **真实 Close 演练**：F06 通过 DevAuthenticationBootstrap 在**已存在的专用组织**上获得
   `PERIOD_CLOSE`，用真实 HTTP 调用 Close，同时并发 6 个 Gateway 请求，验证
   `PENDING_GATEWAY_FINANCIAL_WORK` blocker 看见已提交派发事实。
6. **不修改 HC-01**：只复用其 `lib.mjs` 的 HTTP/进程/统计工具和 Mock Provider 协议；
   `npm run perf:hc01` 行为不变。

安全规则见 [`docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-03-failure-recovery/runbook.md`](../../docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-03-failure-recovery/runbook.md)。

## HC-02 账期热点 A/B 用法

同一 harness 支持只跑 HC-02 需要的账期热点子集，并可指定预构建的 Gateway JAR，
用于优化前后 A/B：

```bash
node scripts/perf/hc01/runner.mjs \
  --suite=hc02 \
  --rounds=3 \
  --variant=baseline \
  --code-sha=<被测量版本的 git sha> \
  --gateway-jar=.local-dev/hc02/gateway-baseline.jar \
  --skip-build \
  --output=docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-02-billing-period-lock/baseline
```

`--suite=hc02` 只运行 `hot_fast_mixed_settlement_c50`（1 轮）、
`hot_fast_single_c20/50/100`、`multi_budget_single_c100`、`multi_org_single_c100`、
`hot_fast_dual_c50/100`；仍然执行逐轮财务不变量、结算收敛和
`environment.json` / `load-results.json` / `db-invariants.json` 产物。
`--gateway-jar` 指向任意提交构建出的 Gateway 可执行 JAR；Backend 仍使用当前
工作树构建的 JAR（HC-02 不改 Backend）。`--variant` 与 `--code-sha` 会写入
`configuration`，避免优化前后 SHA 混淆。每次运行会重建专用库并清空
`performance_schema.events_statements_summary_by_digest`，避免把上一次运行的
SQL digest 混入本次 A/B 证据。

比较两份结果并生成 `results.json` 与 Markdown 表：

```bash
node scripts/perf/hc02/compare.mjs \
  docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-02-billing-period-lock/baseline/load-results.json \
  docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-02-billing-period-lock/optimized/load-results.json \
  docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-02-billing-period-lock
```

## 产物

| 路径 | 是否入库 | 内容 |
|---|---|---|
| `docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-01-gateway-concurrency/` | 是 | 环境、聚合负载结果、数据库不变量、报告与瓶颈结论 |
| `.local-dev/hc01/seed.json` | 否 | 本轮合成凭据与 ID（含测试用原始 key） |
| `.local-dev/hc01/load-results-<run>.json` | 否 | 与入库结果相同的原始聚合副本 |
| `.local-dev/hc01/logs/` | 否 | Backend / Gateway / Mock / runner 日志 |
| `.local-dev/hc01/pids/` | 否 | 本次进程 PID，用于清理 |

## 边界与诚实性

- 这是**本机受控基线**，不是生产容量承诺；Docker VM 内存、MySQL buffer pool、
  同机压测客户端与 Gateway 都会限制结论。
- Gateway 矩阵与异步 Settlement Worker **分开测量**；`mixed` 对照只用于量化
  两者共享账期锁时的尾延迟影响，不能把它的 RPS 与隔离矩阵相加或混用。
- 双实例没有外部负载均衡器；客户端按连接轮转分布，不能把结果表述为生产多活。
- “提高限流阈值”只发生在隔离的 HC-01 数据库/独立进程，用于观察系统容量；
  正常限流阶段使用产品默认值，**没有关闭保护**。
- 未跑到的级别、未采集到的指标必须写 `NOT_VERIFIED`；资源不足写 `BLOCKED`，
  不得以 429 或超时冒充吞吐。

