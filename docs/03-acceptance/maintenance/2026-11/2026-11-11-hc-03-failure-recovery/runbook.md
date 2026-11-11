# HC-03 故障注入与恢复演练 Runbook

本文件是 [Issue #180](https://github.com/BangShou1st/AI-CostOps/issues/180) HC-03 的可重复执行说明。
它回答：**在什么环境、用什么命令、注入什么故障、观测什么、如何清理**。

## 1. 安全边界（先读）

- HC-03 **只操作自己创建的容器**：
  `ai-costops-hc03-mysql`（`127.0.0.1:13307`）与 `ai-costops-hc03-redis`（`127.0.0.1:16379`）。
- **绝不** `stop/kill` 共享的 `ai-costops-mysql-1` / `ai-costops-redis-1`，**绝不** 执行 `FLUSHALL`、
  全局 `KILL`、全库 `DROP`。HC-03 数据库是独立库 `aicostops_hc03`；共享开发库 `aicostops` 不会被触碰。
- Redis 仅承载限流/配额/熔断；停 Redis 不会改变任何财务事实。
- MySQL 是唯一财务真相。停 HC-03 专属 MySQL 时，任何请求都不会“假成功”；未完成 TX2 的请求不可能
  产生 Provider 调用；已提交的 `DISPATCH_INTENT` 不会被恢复逻辑当作零成本。
- 所有停止/暂停操作都在 `try/finally` 等价的恢复路径中：场景失败后 runner 会自愈依赖、重启缺失进程，
  然后才继续下一轮；脚本退出时默认删除两个专属容器（`--keep-running` 除外）。
- 受控 Mock Provider 不是真实 Provider；本演练不接入任何收费上游、不读取真实凭据。

## 2. 前置条件

| 项 | 要求 |
|---|---|
| OS | Windows 11（本机演练）；Linux 亦可（脚本跨平台） |
| Node.js | ≥ 24（`node scripts/perf/hc03/runner.mjs`） |
| JDK | 21 |
| Docker | Docker Desktop，VM 约 1.86 GiB；需要能再起一个 MySQL 8.4 + Redis 容器 |
| Python | 3（Windows 使用 `py -3`；harness 会解析真实 `python.exe`） |
| 仓库根 `.env` | `MYSQL_ROOT_PASSWORD`、`AICOSTOPS_GATEWAY_CREDENTIAL_HMAC_KEY_V1`、`AICOSTOPS_GATEWAY_REQUEST_HMAC_KEY_V1`、`AICOSTOPS_PROVIDER_KEK_V1`、`AICOSTOPS_JWT_SIGNING_KEY`、MinIO 与 Redis 配置。**不提交** |
| 端口 | `13307` / `16379` / `18080` / `18081` / `18082` / `18089` 空闲 |

> 私有运行密钥（HC-03 容器 root 密码、Redis 密码、Gateway DB 密码）首次生成后写入
> `.local-dev/hc03/run-secrets.json`（git-ignored），供 `--attach` 复用；清理目录即可轮换。

## 3. 一键入口

```bash
npm run perf:hc03
# 等价：
node scripts/perf/hc03/runner.mjs
```

常用参数：

```bash
node scripts/perf/hc03/runner.mjs --quick                 # 每场景 1 轮，快速自检
node scripts/perf/hc03/runner.mjs --scenario=F01,F03pre   # 只跑指定场景
node scripts/perf/hc03/runner.mjs --reps=3                # 显式重复次数（默认 3）
node scripts/perf/hc03/runner.mjs --build                 # 先 mvn package gateway/backend
node scripts/perf/hc03/runner.mjs --skip-build            # 复用现有 target JAR
node scripts/perf/hc03/runner.mjs --keep-running          # 保留环境，配合 --attach 迭代
node scripts/perf/hc03/runner.mjs --attach --scenario=F03pre --skip-build   # 复用已启动环境
node scripts/perf/hc03/runner.mjs --cleanup               # 只删 HC-03 容器与进程
```

退出码：`0` 表示所有已执行场景满足不变量；`1` 表示至少一个失败（日志会打印 `[HC-03-RED]` 明细）。

## 4. 环境拓扑

```text
Node harness (单进程)
  ├─ 专用 MySQL 8.4 容器  ai-costops-hc03-mysql  :13307   ← 可被真实 stop/start
  ├─ 专用 Redis 容器      ai-costops-hc03-redis  :16379   ← 可被真实 stop/start/pause
  ├─ Backend JVM          :18080  (Flyway + Settlement Worker, poll=500ms)
  ├─ Gateway JVM A        :18081  (真实进程，可被 taskkill /F)
  ├─ Gateway JVM B        :18082  (真实进程，可被 taskkill /F)
  └─ Mock Provider        :18089  (ok/delay/disconnect/http500/timeout/hold)
```

- Gateway 使用最小权限账号 `gw_hc03`：全库 SELECT + `LOCK TABLES`、5 张 Gateway 表 INSERT/UPDATE、
  Advisor Worker 最小授权；无 DELETE、无 GRANT OPTION。
- 两个 Gateway 是**独立 JVM**，共享同一 MySQL/Redis；没有外部负载均衡器，harness 明确选择端口。
- 测试窗口配置：`hard-timeout=15s`、`header-timeout=8s`、`stream-idle=8s`、
  `reservation-ttl=20s`、`recovery-interval=3s`。这些是**测试专用**短窗口（产品默认分别
  600s/60s/60s/900s/60s），避免演练等待 15 分钟。

## 5. 场景与执行顺序

| ID | 场景 | 真实注入 | 重复 |
|---|---|---|---|
| F01 | Redis 限流/配额不可达 + 黑洞 | `docker stop` / `docker pause` 专属 Redis | 3 + 3 |
| F02 | MySQL 在 TX1/TX2 前不可达 | `docker stop` 专属 MySQL，恢复后重试 | 3 |
| F03a | Gateway 在“确定未派发”窗口崩溃 | 外部会话持账期 X 锁 → 请求停在 attempt PLANNED → `taskkill /F` | 3 |
| F03b | Gateway 在 TX2 后、Provider 可能已执行时崩溃 | Mock `hold` → 请求 `UPSTREAM_ACTIVE` → `taskkill /F` | 3 |
| F04 | Gateway A 崩溃、B 继续服务 | A 上持有 SSE → kill A → 新请求发往 B → 重放旧身份 | 3 |
| F05 | 结算 Worker 崩在事务中 | Backend 停止时生成 FINAL → 锁住 reservation → 重启 Backend → 事务阻塞时 kill | 3 |
| F05db | 结算期间数据库暂断 | 同上前置 → 真实停 MySQL → 恢复 | 3 |
| F06 | Close 与 Gateway 并发 | 真实 `POST /api/v1/billing-periods/{id}/close` + 6 个并发 Gateway 请求 | 3 |

每个场景独立记录 `t_inject / t_detect / t_restore / t_healthy`（毫秒）与每一步 HTTP 状态、错误码、Provider 调用数、数据库终态。

## 6. 证据产物

| 文件 | 内容 |
|---|---|
| [`environment.json`](environment.json) | 执行 SHA/分支/dirty、JAR sha256、镜像与版本、端口、配置、资源限制与诚实性声明 |
| [`events.jsonl`](events.jsonl) | 逐事件追加日志（inject/detect/restore/verified/kill/NOT_VERIFIED），含相对时间 |
| [`db-invariants.json`](db-invariants.json) | 最终预算、请求/attempt/预留/usage/settlement/ledger、周期状态、MySQL 计数与违例 |
| [`results.json`](results.json) | 完整场景结果（每轮 checks、timeline、metrics、数据库快照） |
| [`fault-matrix.md`](fault-matrix.md) | 故障矩阵：机制、观测、实测耗时、PASS/FAIL/PARTIAL/NOT_VERIFIED |
| [`summary.md`](summary.md) | 结论、缺陷与修复、残余风险 |

原始日志不提交：`.local-dev/hc03/logs/*.log`、`.local-dev/hc03/raw/*.json`、`.local-dev/hc03/seed.json`。

## 7. 恢复与清理

```bash
# 正常退出会自动：停止 Backend/Gateway/Mock 进程 + 删除两个 HC-03 容器
node scripts/perf/hc03/runner.mjs --cleanup

# 手工确认（不应看到 ai-costops-hc03-*，也不应看到 18080/18081/18082/18089 监听）
docker ps -a --filter name=ai-costops-hc03
```

如果 runner 被强制中断：

1. `node scripts/perf/hc03/runner.mjs --cleanup`；
2. 确认没有残留 `gw_hc03`/`hc03_app` 会话（可选：`KILL` 该数据库自己的连接）；
3. 重新执行完整入口即可（每次 setup 都会重建专属容器与数据库）。

## 8. 明确不做

不引入 RabbitMQ/Kafka/Outbox、Redis 财务锁、分库分表、K8s、MySQL 主从、Redis Sentinel/Cluster、
多 Region、生产负载均衡器；不改造财务计算模型；不对可能已发送的 AI 请求做自动重试。
生产 SLA/RTO/RPO、真实 Provider、跨机高可用均为 `NOT_VERIFIED`，不得由本演练外推。
