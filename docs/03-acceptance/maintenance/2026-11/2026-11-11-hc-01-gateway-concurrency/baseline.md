# HC-00 基线冻结（2026-11-11）

## 1. 执行基线

| 项 | 值 |
|---|---|
| 仓库 | `BangShou1st/AI-CostOps` |
| 基线分支 | `main` |
| 基线 SHA | `dd53916d76711a537fd332edce75795ceb95109b`（PR #175 CO-00..CO-05） |
| 工作分支 | `perf/hc-01-gateway-load-baseline`（从 `main` 建立） |
| 基线托管 CI | `CI` 与 `Security` 在 `dd53916` 均为 success（2026-11-11T05:02Z 记录） |
| 本轮运行 HEAD | 见 [`environment.json`](environment.json) 的 `git.sha` / `git.dirty` |
| Flyway | 最新 `V32__m18_kilo_gateway_catalog.sql`；专用库每次重建并应用 32 个迁移 |

## 2. 运行时与资源

| 项 | 值 |
|---|---|
| Host | Intel i9-14900HX，24C/32T，16 GiB |
| Docker | 29.8.0；VM 内存约 1.995 GB |
| MySQL | 8.4.11（Docker）；`innodb_buffer_pool_size=128 MiB`、`innodb_flush_log_at_trx_commit=1`、`sync_binlog=1`、`max_connections=151` |
| Redis | 8.8.1（独立 `ai-costops-hc01-redis`，`127.0.0.1:16379`，无持久化） |
| JDK | 21.0.10 |
| Node | 24.14.0（压测客户端同为该进程，单机） |
| Python | 3.13（受控 Mock Provider，标准库） |
| 进程拓扑 | 单/双独立 Gateway JVM + 单 Backend JVM（原生）；MySQL/Redis 为 Docker |
| 负载客户端 | 关闭环固定并发；成功 RPS = 200 完成数 / 实际窗口 |

## 3. 被测参数（Gateway）

| 参数 | 值 |
|---|---|
| `dbThreads` | 12 |
| `dbQueueCapacity` | 256（每线程；Reactor bounded-elastic） |
| `dbPoolMax` / Hikari | 12 |
| `maxActiveStreams` | 128（SSE 上限探针使用 4） |
| 高限流模式（隔离测试） | capacity=500000、refill=50000/s、quota=100000000/day |
| 正常限流模式（产品默认） | capacity=60、refill=1/s、quota=1000/day |
| Provider | 受控 Mock（ok/delay/slow-stream/disconnect/http500/timeout/hold） |
| Backend Settlement | 混合对照 1s 轮询；Gateway 矩阵期间停止；排空 500ms 轮询（测试设置，产品默认 5s） |

## 4. 复用与不重复

- 复用现有 WebFlux、Redis Lua Token Bucket、MySQL 预算预占、DISPATCH_INTENT、
  FINAL Usage、Settlement Worker、Micrometer/Prometheus 与 M16 Mock Provider 协议。
- 不重跑/不改写 CO-00..CO-05 的历史结论；旧 10k/100k Import rows/s 与 20 并发
  验证不作为 Gateway QPS 证据。
- 不引入 RabbitMQ/Outbox、Kafka、分库分表、Redis 财务锁、Kubernetes。

## 5. 执行命令

```bash
npm run infra:up
npm run perf:hc01
node scripts/perf/hc01/runner.mjs --quick   # harness 快速自检
node scripts/perf/hc01/summarize.mjs <load-results.json>
```

## 6. 边界

- 本机受控基线，不是生产容量承诺；Docker VM/MySQL buffer pool/单机客户端是限制。
- 双实例无外部负载均衡器，客户端按连接轮转。
- 真实 Provider、跨 Region HA、MySQL 主从切换、生产 SLA 均为 `NOT_VERIFIED`。
