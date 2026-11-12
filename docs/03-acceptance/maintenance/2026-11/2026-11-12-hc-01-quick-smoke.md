# 2026-11-12 HC-01 quick 冒烟验收

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-12 |
| 责任人 | AI（Codex） |
| 关联模块 | HC-01 harness、Gateway 数据面、Backend Settlement / Ledger、Docker Redis 归属 |
| 缺陷 / PR | 无新增缺陷；分支 `fix/maintenance-hc01-pricing-contract` 的首个提交 |
| 代码基线 | `dev=046c4f3`（`046c4f31c81a08efae1269b0304a30b63e0164fa`），工作区干净 |
| 变更性质 | 仅运行期验收与记录；未改业务代码、财务语义、Flyway 或默认配置 |
| 总体判定 | **PASS** |

## 1. 结论摘要

| # | 验收项 | 结论 | 关键理由 |
|---|---|---|---|
| 1 | Gateway SSRF opt-in | **PASS** | quick harness 仅在 `AICOSTOPS_GATEWAY_PROVIDER_ALLOW_PRIVATE_ENDPOINTS=true` 下访问 loopback Mock Provider；Gateway resolver 测试 13/13 证明默认/ prod 拒绝 loopback，opt-in 后可达 |
| 2 | Redis 容器 owner label 归属 | **PASS** | 真实 Docker 探针：无 label / 他人 owner / 仅 HC-03 label 均拒绝删除，本 run owner 允许删除；实跑后无 `ai-costops-hc01-redis` 残留 |
| 3 | `FINAL → Settlement → Ledger` 收敛 | **PASS** | 8 个组织 `completed = FINAL = SETTLED = ledger_posting = ledger_entry`，逐组织 1:1；`pending=0`，0 duplicate / 0 缺失 posting |
| 4 | 预算 / 用量 / 账本不变量 | **PASS** | 20 个 fixture budget 0 超支；0 duplicate FINAL、0 COMPLETED without FINAL、0 multi-attempt；9 条 UNKNOWN 用量保持未结算，未被当作零成本 |

## 2. 变更概述与影响边界

本次只运行 HC-01 `--quick` 冒烟并记录证据，没有修改产品代码、默认配置、数据库迁移或测试断言。

为保证证据对应当前 `HEAD`，先重建了 `backend/target/backend-1.1.0.jar` 与
`gateway/target/gateway-1.1.0.jar`（旧 JAR 早于 `046c4f3`，且 gateway 早于 P2-18 SSRF 提交）。
HC-01 使用独立数据库 `aicostops_hc01`、独立 Redis 容器 `ai-costops-hc01-redis`（`127.0.0.1:16379`）
和 Mock Provider（`127.0.0.1:18089`），未操作主库 `aicostops`，未终止用户的 `8080` / `5173` 进程。

## 3. 验证环境与执行命令

### 3.1 环境

```text
Windows 11 + PowerShell 7
Node v24.14.0
Java 21.0.10 (Temurin)
Docker Engine 29.8.0（Docker Desktop / WSL2）
Docker VM MemTotal = 1,995,673,600 bytes（约 1.86 GiB）
宿主总内存 15.71 GiB；运行前可用 1.33 GiB；harness 快照时可用 0.91 GiB
MySQL 8.4.11 / Redis 8.8.1 / Mock Provider :18089
```

### 3.2 执行前检查

```text
18080 / 18081 / 18082 / 18089  均 FREE
ai-costops-hc01-redis          不存在（无残留）
8080  java PID 13948           保持运行，未触碰
5173  node PID 1260            属于另一项目，保持运行，未触碰
.env                            存在；本记录不输出任何密钥
```

### 3.3 命令

```powershell
# 使 JAR 对应当前 HEAD
cd backend; .\mvnw.cmd -B -DskipTests package
cd ..\gateway; .\mvnw.cmd -B -DskipTests package

# HC-01 quick 冒烟（隔离库 + 独立 Redis）
cd ..
npm run perf:hc01 -- --quick

# 归属与 SSRF 的定向证据
node --test scripts/tests/hc01-ownership.test.mjs
node .local-dev/probes/hc01-owner/probe.mjs
cd gateway
.\mvnw.cmd -B "-Dtest=MimoChatAdapterResolverTest,OpenAiChatAdapterResolverTest,PublicOnlyResolverTest" test
```

## 4. 关键断言与证据

### 4.1 SSRF opt-in — PASS

HC-01 的 Provider 是 loopback Mock（`http://127.0.0.1:18089/v1`），harness 只在以下显式开关下启动 Gateway：

```text
AICOSTOPS_GATEWAY_PROVIDER_ALLOW_PRIVATE_ENDPOINTS=true
```

smoke 请求返回 200，并在 30 秒内写入 FINAL usage：

```text
smoke request + FINAL usage check
smoke FINAL usage confirmed (1)
```

反向路径由 Gateway resolver 测试覆盖：

```text
MimoChatAdapterResolverTest   4/4
OpenAiChatAdapterResolverTest 4/4
PublicOnlyResolverTest        5/5
Results: Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
```

其中默认与 `prod` profile 均拒绝 loopback，命中服务端次数为 0；设置
`aicostops.gateway.provider.allow-private-endpoints=true` 后请求可达。结论是 opt-in
开关真实生效，而生产默认仍是 public-only。

### 4.2 Redis 容器 owner label 归属 — PASS

真实 Docker 探针使用固定容器名 `ai-costops-hc01-redis`，通过
`docker inspect --format '{{json .Config.Labels}}'` 读取实际标签，再调用 runner 复用的
`decideRedisRemoval`：

| 场景 | 容器标签 | 决策 | 容器结果 |
|---|---|---|---|
| A | 无 label（`{}`） | `{"allow":false,"reason":"UNLABELED_RESOURCE"}` | 按 ID 复核仍存在 |
| B | `aicostops.hc01.owner=foreign-owner` | `{"allow":false,"reason":"OWNER_MISMATCH"}` | 按 ID 复核仍存在 |
| C | 本探针 owner + run + resource | `{"allow":true}` | 按 `docker rm -f` 语义删除，名称与 ID 均消失 |
| D | 仅 `aicostops.hc03.*` label | `{"allow":false,"reason":"UNLABELED_RESOURCE"}` | 按 ID 复核仍存在 |

单元测试：

```text
node --test scripts/tests/hc01-ownership.test.mjs
tests 6 / pass 6 / fail 0
```

真实探针最终输出：`RESULT: PASS`，并确认无 residual fixed-name container。
HC-01 quick 实跑本身创建/使用/删除了自己的 Redis 容器，结束后同样无残留。

### 4.3 `FINAL → Settlement → Ledger` 收敛 — PASS

`load-results.json` 终态：

```text
quick = true
scenarios = 13
scenarioFailures = 0
converged = true
settlementViolations = 0
budgetViolations = 0
notes = 0
```

逐组织终态（`completedRequests / finalUsage / nonFinalUsage / settled / pending /
ledgerPostings / ledgerEntries`）：

| org | completed | FINAL | non-FINAL | SETTLED | pending | postings | entries |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 1 | 1766 | 1766 | 9 | 1766 | 0 | 1766 | 1766 |
| 2 | 55 | 55 | 0 | 55 | 0 | 55 | 55 |
| 3 | 57 | 57 | 0 | 57 | 0 | 57 | 57 |
| 4 | 55 | 55 | 0 | 55 | 0 | 55 | 55 |
| 5 | 56 | 56 | 0 | 56 | 0 | 56 | 56 |
| 6 | 55 | 55 | 0 | 55 | 0 | 55 | 55 |
| 7 | 55 | 55 | 0 | 55 | 0 | 55 | 55 |
| 8 | 54 | 54 | 0 | 54 | 0 | 54 | 54 |

汇总：2153 条 `completed`、2153 条 `FINAL`、2153 条 `SETTLED`、2153 条
`GATEWAY_SETTLEMENT` posting / entry；`settledWithoutPosting=0`、
`duplicatePostingKeys=0`、`badEntryCardinality=0`、`zeroAmountSettlements=0`。

### 4.4 预算 / 用量 / 账本不变量 — PASS

```text
fixture budgets          = 20
overspent budgets        = 0
active holds at end      = 0
pending holds at end     = 9
duplicate FINAL groups   = 0
COMPLETED without FINAL  = 0
completed with non-FINAL current = 0
multi-attempt requests   = 0
```

9 条 `PENDING_HOLD` 来自断流 / UNKNOWN 场景，对应 9 条 non-FINAL usage，未产生
Settlement 或 Ledger；这符合 `missing usage ≠ zero cost`，不是预算泄漏。预算行
`available = total - actual - outstanding commitments` 在全部 20 个 fixture 上均未出现负数。

### 4.5 quick 场景结果

| 场景 | 并发 / 请求 | 关键结果 | failures |
|---|---|---:|---:|
| `hot_fast_mixed_settlement_c50` | 50 | 260 × 200，p95 1863 ms | 0 |
| `hot_fast_single_c5` | 5 | 118 × 200，p95 295 ms | 0 |
| `hot_fast_single_c20` | 20 | 190 × 200，p95 727 ms | 0 |
| `multi_budget_single_c100` | 20 | 285 × 200，p95 445 ms | 0 |
| `multi_org_single_c100` | 20 | 287 × 200，p95 425 ms | 0 |
| `slow_provider_single_c50` | 10 | delay 500 ms，69 × 200 | 0 |
| `idempotency_same_key_c10` | 10 | 1 × 200 + 9 × 409，Provider ops=1 | 0 |
| `budget_exhaustion_c10` | 10 | 2 × 200 + 8 × 429，Provider ops=2 | 0 |
| `sse_ceiling_probe` | 4 held + 1 over | active=4，over=429，release 后 active=0，fresh=200 | 0 |
| `sse_success_single_c8` | 8 | 128 × 200，p95 264 ms，active streams=0 | 0 |
| `sse_cancel_release` | 4 | active=4 → cancel 后 0，4 × UNKNOWN | 0 |
| `provider_disconnect_unknown` | 5 | 5 × 502，5 × UNKNOWN，FINAL=0 | 0 |
| `rate_limit_normal_c20` | 20 | 63 × 200 + 1848 × 429，p95 1118 ms | 0 |

### 4.6 耗时与资源

```text
开始 2026-11-12 19:01:21 CST（harness 日志 11:01:21 UTC）
结束 2026-11-12 19:05:47 CST（harness 日志 11:05:47 UTC）
总耗时约 4 分 26 秒
Docker VM 1.86 GiB；无 OOM、无端口冲突
```

## 5. 结论与影响边界

**判定：PASS。** HC-01 quick 在真实 Docker、真实 MySQL/Redis、真实 Backend/Gateway JVM 与
受控 Mock Provider 下完成 13 个场景，逐场景 0 失败，财务收敛与预算不变量 0 违例。

影响边界：

- 未修改产品代码、默认配置、Flyway、OpenAPI 或测试断言。
- 未操作主库 `aicostops`；运行后主库计数仍为 `ledger_entry=28`、`ledger_posting=27`、
  `pricing_version=1`，与运行前基线一致。
- 未终止用户 `8080` / `5173` 进程。
- quick 只覆盖单实例和 5–20 并发档位，不是 full matrix；双实例、200 并发、完整限流与
  长时稳定性仍以 full harness 为准。

## 6. 清理与复现

```text
ai-costops-hc01-redis            已删除，无残留
18080 / 18081 / 18082 / 18083 / 18089  已释放
共享 MySQL / Redis / MinIO       保持运行
aicostops_hc01                   保留（仅 synthetic fixture 数据）
8080 java PID 13948              保持运行
5173 node PID 1260               保持运行
git 工作区                        仅包含本验收记录与 pricing 契约改动（未提交时）
```

本地证据（被 `.gitignore` 忽略，不入库）：

```text
.local-dev/hc01/quick-evidence/load-results.json
.local-dev/hc01/quick-evidence/environment.json
.local-dev/hc01/quick-evidence/db-invariants.json
.local-dev/probes/hc01-owner/evidence.txt
```

复现命令：

```powershell
cd E:\project\AI-CostOps
npm run perf:hc01 -- --quick
node --test scripts/tests/hc01-ownership.test.mjs
```

> 本记录不包含密码、JWT/HMAC/KEK、Provider 凭据或真实 Provider 数据；
> `load-results.json` 中的 Gateway raw key 只存在于本地 `.local-dev/hc01/seed.json`。
