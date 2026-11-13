# 2026-11-13 运行期专项验收（HC-01 / 生产配置 / 浏览器 / 对账回收）

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-13（报告日期；实际运行证据采集于 2026-11-12 17:00–17:45 Asia/Shanghai） |
| 责任人 | AI（Codex root + 4 个并行子代理） |
| 关联模块 | Gateway HC-01 harness、Backend 生产配置/对账、Frontend 账本/Provider/定价/证据下载 |
| 缺陷来源 | 2026-11-12 剩余 P2/P3 批次 A/B/C 的运行期待验证项：P2-04、P2-02、P2-16、P2-22、P2-12、P2-15 |
| 代码基线 | `dev=6f4f7b4`、`main=104af5d`、`tag v3.1.1 -> bef5877`（`v3.1.1` 是 `main` 的祖先；`main` 额外包含发布说明提交 `104af5d`）；验收开始时工作区干净，修复后为未提交工作区改动 |
| 变更性质 | 运行期验收 + 两项缺陷最小修复与复核；无财务语义/Flyway 变更；新增本报告与证据附件 |
| 总体判定 | **PASS（修复后复核）**：2 项真实缺陷已修复并验证；HC-01 归属、UTC 展示、Edge 下载、对账回收 PASS；可选 HC-01 quick 未运行 |

## 1. 结论摘要

| # | 验收项 | 结论 | 关键理由 |
|---|---|---|---|
| 1 | HC-01 Redis 归属生命周期（P2-04） | **PASS** | 真实 Docker 下，无 label / 他人 owner / 仅 HC-03 label 均拒绝删除；本 run owner 允许删除；容器保留/删除均经 ID 复核 |
| 2 | 生产配置校验器真实解析（P2-02） | **FIXED / PASS** | 修复 `ProductionConfigurationValidator` 构造器装配后，`prod` 真实启动 8/8 PASS：1-5/8 正确 fail-fast，6/7 启动并保持运行；见 §9 |
| 3 | 账本与 Provider 连接时间（P2-16） | **PASS** | Edge 中 `/ledger` 27/27 行发布时间为 `YYYY-MM-DD HH:mm UTC`；Provider 连接列表/详情时间均带显式 UTC |
| 4 | 微小费率显示（P2-12） | **FIXED / PASS** | `Scale8DecimalSerializer` 输出 scale-8 定点串；API 返回 `0.00000001`，Edge 显示 `0.00000001 USD`；见 §9 |
| 5 | 证据下载（P2-22，Edge） | **PASS** | 证据详情页与报销详情页两处下载均为 200 B、可读、SHA-256 与证据元数据完全一致 |
| 6 | Firefox / Safari 下载 | **NOT_VERIFIED** | 本机无 Firefox/Safari 环境，未做真实性声明 |
| 7 | 对账僵尸 RUNNING 回收（P2-15） | **PASS** | 真实 `@Scheduled` 触发：31 分钟前 RUNNING → `FAILED` + `RECONCILIATION_RUN_ABANDONED`，审计存在；5 分钟前 RUNNING 未被误杀 |
| 8 | 可选 HC-01 quick 冒烟 | **NOT_RUN / NOT_VERIFIED** | 发现两项真实缺陷后按用户规则停止；且 HC-01 端口 18080/18081/18082 与本次运行期栈冲突，Docker VM 仅 1.86 GiB |

## 2. 变更概述与影响边界

本轮先完成运行期验收；用户确认“修复吧”后，对两项真实缺陷做了最小修复。修复没有修改财务语义、Flyway 或默认配置，也没有创建 PR、分支或提交；具体见 §9。

为完成运行期检查，做了以下环境动作：

- 现有项目的 MySQL / Redis / MinIO 容器是三周前创建的，未按当前 `compose.dev.yaml` 发布 6379 / 9000；执行 `npm run infra:up` 重建容器，使端口收敛到 `127.0.0.1`。三个命名数据卷保留，原库数据未丢。
- 浏览器验收使用隔离栈：Frontend `15173`、Backend `18080`、数据库 `aicostops_rv`（从 `aicostops` 复制）。
- 对账验收使用隔离栈：Backend `18082`、数据库 `aicostops_rv_recon`（从 `aicostops` 复制）。
- 生产配置验收使用 `aicostops_prodverify`，每个 case 使用随机端口；结束后已 DROP。
- 原 `8080` Backend（PID `13948`）与原 `5173` Vite（PID `1260`，属于另一个项目）全程未终止。

## 3. 验证环境与执行命令

### 3.1 环境

```text
Windows 11 + PowerShell 7
Node v24.14.0
Java 21.0.10 (Temurin)
Docker Engine 29.8.0（Docker Desktop / WSL2）
Docker VM MemTotal ≈ 1.86 GiB
```

### 3.2 基线

```text
git status --porcelain        -> 空（验收开始时；修复后见 §7）
git rev-parse --short dev     -> 6f4f7b4
git rev-parse --short main    -> 104af5d
git tag v3.1.1                -> bef5877（main 的祖先）
```

### 3.3 主要命令

| 项 | 命令（脱敏） |
|---|---|
| 基础设施 | `npm run infra:up` |
| HC-01 所有权单测 | `node --test scripts/tests/hc01-ownership.test.mjs` |
| HC-01 真实 Docker 探针 | `node .local-dev/probes/hc01-owner/probe.mjs` |
| 生产配置真实启动 | `java -jar backend/target/backend-1.1.0.jar --server.port=0`，`SPRING_PROFILES_ACTIVE=prod`，其余 prod 基线环境变量从 `.env`/临时随机值注入 |
| 生产配置 8 case | `& .local-dev/probes/prod-config/run-prod-config-verification.ps1 -PerCaseTimeoutSeconds 240` |
| 浏览器栈 Backend | `java -jar backend/target/backend-1.1.0.jar --server.port=18080`，`SPRING_DATASOURCE_URL` 指向 `aicostops_rv` |
| 浏览器栈 Frontend | `node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 15173 --strictPort`，`BACKEND_PORT=18080` |
| 浏览器 | `bsk session start --no-focus --name aicostops-runtime --json`；`bsk get-html`；`bsk download` |
| 对账栈 Backend | `java -jar backend/target/backend-1.1.0.jar --server.port=18082`，`SPRING_DATASOURCE_URL` 指向 `aicostops_rv_recon` |
| 对账调度覆盖 | `AICOSTOPS_RECONCILIATION_STALE_RUN_SWEEP_INITIAL_DELAY=10s`、`..._INTERVAL=15s`、`..._TIMEOUT=30m` |

> 所有密码、JWT/HMAC/KEK、Provider 凭据均未写入本报告；`.env` 只被读取并注入子进程。

## 4. 逐项证据

### 4.1 HC-01 Redis 归属生命周期（P2-04）— PASS

基线单测：

```text
$ node --test scripts/tests/hc01-ownership.test.mjs
# 6/6 passed
```

真实 Docker 探针使用固定容器名 `ai-costops-hc01-redis`，通过 `docker inspect --format '{{json .Config.Labels}}'` 读取标签，并调用 runner 实际复用的 `decideRedisRemoval`：

| 场景 | 容器标签 | 决策 | 决策后容器 |
|---|---|---|---|
| A | 无 label（Docker 返回 `{}`） | `{"allow":false,"reason":"UNLABELED_RESOURCE"}` | 仍存在，按容器 ID 复核 |
| B | `aicostops.hc01.owner=foreign-owner` + run/resource | `{"allow":false,"reason":"OWNER_MISMATCH"}` | 仍存在，按容器 ID 复核 |
| C | `aicostops.hc01.owner=<本次探针 owner>` + run/resource | `{"allow":true}` | 按 runner 的 `removeRedis` 语义 `docker rm -f`，固定名与 ID 均消失 |
| D | 仅 `aicostops.hc03.*` 标签 | `{"allow":false,"reason":"UNLABELED_RESOURCE"}` | 仍存在，HC-03 命名空间不被 HC-01 视为 owner |

证据：`2026-11-13-runtime-verification/hc01-ownership-evidence.txt`。

补充：首次 `npm run test:scripts` 出现一次与本项无关的 `processes.test.mjs` 瞬时失败（`Unexpected end of JSON input`），复核重跑为 `54/54 passed`；HC-01 定向测试稳定 `6/6`。该瞬时失败不作为缺陷登记。

### 4.2 生产配置校验器真实解析（P2-02）— FAIL

> 本节保留修复前的 FAIL 证据；修复与复核见 §9。

**结论：`prod` profile 真实启动失败，validator 根本没有执行。**

真实启动报错原文（`aicostops_prodverify`，随机端口）：

```text
Error creating bean with name 'productionConfigurationValidator' defined in URL
[jar:nested:/E:/project/AI-CostOps/backend/target/backend-1.1.0.jar/!BOOT-INF/classes/!/com/aicostops/config/ProductionConfigurationValidator.class]:
Failed to instantiate [com.aicostops.config.ProductionConfigurationValidator]:
No default constructor found

Caused by: java.lang.NoSuchMethodException:
com.aicostops.config.ProductionConfigurationValidator.<init>()
```

根因：`ProductionConfigurationValidator` 同时存在两个构造器：

```java
public ProductionConfigurationValidator(Environment environment)
ProductionConfigurationValidator(Environment environment, HostResolver hostResolver) // test seam
```

两者都没有 `@Autowired`，类也没有无参构造器。Spring 在 `prod` 上下文中走无参构造路径，直接失败。单元测试是手工 `new` 该对象，因此没有覆盖 Spring 容器装配路径。

8 个 endpoint case 全部在 bean 实例化阶段退出，未出现 `Unsafe production configuration`，也未出现 `Started AiCostOpsApplication`：

| # | `AICOSTOPS_STORAGE_ENDPOINT` | opt-in | 期望 | 退出码 | endpoint 判定 |
|---|---|---|---|---|---|
| 1 | `http://0.0.0.0:9000` | false | fail-fast | 1 | `NOT_VERIFIED`（blocked） |
| 2 | `http://127.1:9000` | false | fail-fast | 1 | `NOT_VERIFIED`（blocked） |
| 3 | `http://2130706433:9000` | false | fail-fast | 1 | `NOT_VERIFIED`（blocked） |
| 4 | `http://[::ffff:127.0.0.1]:9000` | false | fail-fast | 1 | `NOT_VERIFIED`（blocked） |
| 5 | `http://host.docker.internal:9000` | false | fail-fast（要求显式 opt-in） | 1 | `NOT_VERIFIED`（blocked） |
| 6 | `http://host.docker.internal:9000` | true | 启动并保持运行 | 1 | `NOT_VERIFIED`（blocked） |
| 7 | `https://example.com` | false | 启动并保持运行 | 1 | `NOT_VERIFIED`（blocked） |
| 8 | `http://example.com` | false | fail-fast（公网明文） | 1 | `NOT_VERIFIED`（blocked） |

Flyway 本身正常：case 1 `Successfully applied 32 migrations ... now at version v32`；case 2–8 `Successfully validated 32 migrations`。因此失败不是数据库 URL、Redis、MinIO 或 endpoint 配置导致。

真实 DNS / JVM 解析（不是注入 resolver）：

| 主机 | `Resolve-DnsName` / JDK 21.0.10 `InetAddress` | 分类 |
|---|---|---|
| `0.0.0.0` | `/0.0.0.0`，`anyLocal=true` | LOCAL |
| `127.1` | `URI.getHost()=null` | 无 host（拒绝路径） |
| `2130706433` | `/127.0.0.1`，`loopback=true` | LOCAL |
| `[::ffff:127.0.0.1]` | `/127.0.0.1`，`Inet4Address` | LOCAL |
| `host.docker.internal` | `10.61.104.23`，`siteLocal=true` | INTERNAL |
| `example.com` | `104.20.23.154`、`172.66.147.243`（另有 AAAA） | PUBLIC |
| `console.example.com` | `UnknownHostException` / NXDOMAIN | UNDETERMINED（不是本次阻断原因） |

证据：

- `2026-11-13-runtime-verification/prod-validator-constructor-error.txt`
- `2026-11-13-runtime-verification/prod-config-cases.csv`
- `2026-11-13-runtime-verification/dns-resolve-dnsname.txt`
- `2026-11-13-runtime-verification/jdk-resolution-probe.txt`

**影响：任何 `prod` profile 部署都无法启动，属于生产阻断缺陷；endpoint 的本地性/明文策略本身仍未获得运行期证明。**

### 4.3 浏览器验收（P2-16 / P2-12 / P2-22）

环境：Edge + `browser-skill`，`bsk` session `kinm`；应用 `http://localhost:15173`，Backend `18080`，隔离库 `aicostops_rv`。管理员登录成功，未打印凭据。

#### 4.3.1 P2-16 时间显示 — PASS

`/ledger`：导出表格 HTML 后逐行校验发布时间：

```text
row_count=27
all_first_column_cells_match_UTC=true
```

关键 DOM 样例：

```text
2026-11-10 18:22 UTC  对账调整
2026-11-10 18:06 UTC  供应商成本
2026-08-24 08:54 UTC  纠正
```

`/settings/provider-connections`：

```text
#2 · v2  已启用  ...  激活于 2026-09-12 12:51 UTC
#1 · v1  已退役  ...  激活于 2026-09-12 12:34 UTC
```

详情页 `/settings/provider-connections/2`：

```text
v2 · 创建 2026-09-12 12:51 UTC · 激活 2026-09-12 12:51 UTC
凭证 MiMo Dev · API Key · 创建 2026-09-02 13:57 UTC
```

截图：

- `2026-11-13-runtime-verification/ledger-utc.png`
- `2026-11-13-runtime-verification/provider-connections-utc.png`
- `2026-11-13-runtime-verification/provider-connection-detail-utc.png`

说明：任务描述中的“28 条 ledger”对应 DB `ledger_entry=28`；UI 账本列表展示的是 `ledger_posting=27` 条发布记录，因此 DOM 为 27 行。两个计数口径不同，不是缺数据。

#### 4.3.2 P2-12 微小费率显示 — FAIL

> 本节保留修复前的 FAIL 证据；修复与复核见 §9。

实际路由为 `/settings/model-pricing`（仓库中不存在 `/gateway/model-pricing` 路由）。

创建请求（`unitPrice` 为 JSON number，符合 OpenAPI 输入声明）：

```json
{"providerAccountId":32,"providerModelId":1,"currency":"USD","effectiveFrom":"2026-11-12T09:23:30.413Z","effectiveTo":null,"rates":[{"dimensionCode":"INPUT_TOKEN","unitQuantity":1000000,"unitPrice":0.00000001},{"dimensionCode":"OUTPUT_TOKEN","unitQuantity":1000000,"unitPrice":0.00000002}]}
```

创建后 `POST /api/v1/pricing-versions/2/activate` 返回 `status=ACTIVE`。数据库实际值正确：

```text
INPUT_TOKEN   unit_quantity=1000000  unit_price=0.00000001
OUTPUT_TOKEN  unit_quantity=1000000  unit_price=0.00000002
列类型：decimal(20,8)
```

但 `GET /api/v1/pricing-versions` 原始响应为：

```json
{"id":"22","dimensionCode":"INPUT_TOKEN","unitQuantity":1000000,"unitPrice":"1E-8"}
{"id":"23","dimensionCode":"OUTPUT_TOKEN","unitQuantity":1000000,"unitPrice":"2E-8"}
```

前端 `lib/money.ts` 的 `parseDecimal8` 只接受 `^-?[0-9]+\.[0-9]{8}$`，实际输出：

```text
formatMoney("1E-8", "USD")       = —
formatMoney("2E-8", "USD")       = —
formatMoney("0.00000001", "USD") = 0.00000001 USD
```

页面 DOM：

```text
账号 32 / 模型 1  2  USD  已启用  输入 —/1000000；输出 —/1000000  2026-11-12 09:23 UTC → 长期
```

根因：`PricingRateResponse.unitPrice` 上的 `@JsonSerialize(using = ToStringSerializer.class)` 调用 `BigDecimal.toString()`。JDK 的 `BigDecimal.toString()` 在 adjusted exponent < -6 时使用科学计数法：

```text
new BigDecimal("0.00000001").toString()     = 1E-8
new BigDecimal("0.00000001").toPlainString() = 0.00000001
```

因此响应内容违反 OpenAPI 的 `unitPrice` 响应 schema `^-?[0-9]+\.[0-9]{8}$`，前端按契约拒绝并显示 `—`。数据库存储、API 输入解析和普通费率（如 `30.00000000`、`0.00010000`）不受影响。

截图与原始响应：

- `2026-11-13-runtime-verification/pricing-tiny-rate.png`
- `2026-11-13-runtime-verification/pricing-get-raw.json`
- `2026-11-13-runtime-verification/pricing-create-request.json`

#### 4.3.3 P2-22 证据下载 — PASS（Edge）

两处下载入口均在 Edge 中验证：

| 入口 | 下载文件 | 大小 | SHA-256 | 可读性 |
|---|---|---|---|---|
| `/evidence/54` 详情页“下载” | `C:\Users\32962\Downloads\aicostops-expense-38-receipt.txt` | 200 B | `42744db63f5eca1b1ceb5e81459515f9738296a8612c21e322afecb4a11fb740` | 文本可读 |
| `/expenses/38` 详情页“下载凭证” | `C:\Users\32962\Downloads\evidence-38.txt` | 200 B | 同上 | 文本可读 |

文件头 16 字节：

```text
41 49 2d 43 6f 73 74 4f 70 73 20 73 79 6e 74 68  =  "AI-CostOps synth"
```

内容摘要：

```text
AI-CostOps synthetic reimbursement evidence for expense 38.
Date: 2026-10-14
Amount: 19.99 CNY
Purpose: browser acceptance of the reimbursement -> approval -> posting chain (synthetic data only).
```

SHA-256 与证据详情页显示的元数据完全一致。`bsk download` 受控副本回执均为 `byte_size=200, danger=safe, mime=text/plain`。

截图与副本：

- `2026-11-13-runtime-verification/evidence-download-success.png`
- `2026-11-13-runtime-verification/expense-download-success.png`
- `2026-11-13-runtime-verification/evidence-download-copy.txt`

Firefox / Safari：本机无环境，`NOT_VERIFIED`，不把 Edge 结果外推为跨浏览器结论。

### 4.4 对账僵尸 RUNNING 回收（P2-15）— PASS

使用隔离库 `aicostops_rv_recon`，Backend `18082`，只在**本进程**用环境变量缩短调度等待，未改 `application.yml` 默认值：

```text
AICOSTOPS_RECONCILIATION_STALE_RUN_SWEEP_INITIAL_DELAY=10s
AICOSTOPS_RECONCILIATION_STALE_RUN_SWEEP_INTERVAL=15s
AICOSTOPS_RECONCILIATION_STALE_RUN_TIMEOUT=30m
```

插入两条 RUNNING（种子 `org_id=1`、`billing_period_id=1`、`created_by_member_id=1`）：

```sql
-- stale #17: started_at = UTC_TIMESTAMP(6) - INTERVAL 31 MINUTE
-- fresh #18: started_at = UTC_TIMESTAMP(6) - INTERVAL 5 MINUTE
```

真实调度时间线：

```text
2026-11-12T17:19:57.774+08:00  Started AiCostOpsApplication in 8.076 seconds
2026-11-12T17:20:07.806+08:00  [import-heartbeat-1] ReconciliationRunRecoveryScheduler:
                               Converged 1 stale RUNNING reconciliation run(s)
```

距启动 `10.03s`，与 `initialDelay=10s` 一致；线程是 Spring 共享 `TaskScheduler`，不是手工调用。

实际结果：

```text
stale #17:  status=FAILED
            error_code=RECONCILIATION_RUN_ABANDONED
            started_at=2026-11-12 08:48:15.822758 UTC
            finished_at=2026-11-12 09:20:07.783520 UTC
fresh #18:  status=RUNNING
            finished_at=NULL
            error_code=NULL
audit #715: event_type=RECONCILIATION_RUN_FAILED
            subject_type=RECONCILIATION_RUN
            subject_id=17
            actor_user_id=1
            metadata_json={"errorCode": "RECONCILIATION_RUN_ABANDONED", "billingPeriodId": 1}
```

克隆库中原有 16 条 run 全部保持 `COMPLETED`；`#17` 的审计计数为 1，`#18` 为 0。结束后已停止 `18082` 进程并 DROP `aicostops_rv_recon`。

证据：`2026-11-13-runtime-verification/recon-scheduler.log`。

### 4.5 可选 HC-01 quick 冒烟 — NOT_RUN / NOT_VERIFIED

`npm run perf:hc01 -- --quick` 本次**未执行**，原因：

1. 已发现两项真实运行期缺陷，按用户“发现真实缺陷时停下写报告，等我确认”的规则停止继续动作。
2. HC-01 默认使用 `18080/18081/18082`，与本次浏览器栈和对账栈端口冲突。
3. Docker VM 仅 1.86 GiB；同时启动 Backend + Gateway + Mock Provider + 负载会产生不必要的资源竞争。

因此本次不声明 SSRF opt-in、Redis 归属、`FINAL → Settlement → Ledger` 收敛在 quick harness 中的运行期结论；HC-01 归属本身已由 §4.1 的独立 Docker 探针覆盖。

## 5. 真实缺陷登记（已修复，复核见 §9）

### DEF-RT-01 — `prod` profile 无法启动（生产配置校验器装配失败）

| 项目 | 内容 |
|---|---|
| 严重度 | **阻断**（建议按 P1 处理） |
| 位置 | `backend/src/main/java/com/aicostops/config/ProductionConfigurationValidator.java` |
| 现象 | `--spring.profiles.active=prod` 启动时，`productionConfigurationValidator` bean 创建失败：`No default constructor found` / `NoSuchMethodException: <init>()` |
| 根因 | 类有 `(Environment)` 与 `(Environment, HostResolver)` 两个构造器，均未标 `@Autowired`，也没有无参构造器；Spring 无法选择构造器 |
| 影响 | 所有 prod profile 部署无法启动；endpoint 本地性/明文策略、allowed-origins、存储凭据等校验都未获得运行期证明 |
| 证据 | `2026-11-13-runtime-verification/prod-validator-constructor-error.txt`、`prod-config-cases.csv` |
| 状态 | **FIXED / PASS（见 §9）；修复变更未提交** |

实际修复（见 §9）：在 `Environment` 构造器上显式 `@Autowired`，并新增 Spring 构造器选择回归测试；真实 `prod` profile 8/8 复核通过。

### DEF-RT-02 — 微小定价费率响应为科学计数法，页面显示 `—`

| 项目 | 内容 |
|---|---|
| 严重度 | **高**（建议按 P2 处理；展示与契约双重问题） |
| 位置 | `backend/src/main/java/com/aicostops/gatewayadmin/api/ControlPlaneDtos.java` 的 `PricingRateResponse.unitPrice` 序列化 |
| 现象 | DB 存 `0.00000001`，`GET /api/v1/pricing-versions` 返回 `"1E-8"`；前端 `formatMoney` 返回 `—`，页面显示 `输入 —/1000000；输出 —/1000000` |
| 根因 | `@JsonSerialize(using = ToStringSerializer.class)` 使用 `BigDecimal.toString()`；adjusted exponent < -6 时输出科学计数法，违反 OpenAPI 的 scale-8 定点串 schema |
| 影响 | 小于 `0.000001` 的费率无法在 UI 精确展示；外部消费方按 OpenAPI 正则解析会失败；数据库与普通费率不受影响，无财务写入/结算语义变化 |
| 证据 | `pricing-tiny-rate.png`、`pricing-get-raw.json`、`pricing-create-request.json` |
| 状态 | **FIXED / PASS（见 §9）；修复变更未提交** |

实际修复（见 §9）：新增 `Scale8DecimalSerializer`（`setScale(8, RoundingMode.UNNECESSARY).toPlainString()`），增加 serializer 与真实 API 集成测试；Edge 页面复核通过。`frontend` 的 `PricingRateInput.unitPrice` 仍以 decimal string 发送，而 OpenAPI 输入声明为 number；该输入契约差异不影响本次缺陷，建议后续单独清理。

> 本报告不修改 2026-11-12 的历史记录。历史记录中 P2-02/P2-12/P2-16/P2-22 的 `NOT_VERIFIED` 项由本报告补充：P2-16/P2-22 在 Edge 下 PASS；P2-02 与 P2-12 为 FAIL，后续修复记录应引用本报告，而不是改写历史结论。

## 6. 影响边界

- **财务语义**：无改动；不触碰预算预占、结算、账本、关账、金额计算、Flyway。
- **代码/配置**：修复 `ProductionConfigurationValidator` 的构造器装配与 `PricingRateResponse.unitPrice` 的序列化；新增 `Scale8DecimalSerializer` 与回归测试；未改默认 `application.yml`、未改 Flyway/OpenAPI/前端。
- **生产部署**：在 DEF-RT-01 修复前，`prod` profile 无法启动；这是本次最严重的运行期结论。
- **定价展示**：小于 `0.000001` 的费率响应违反 scale-8 契约并显示 `—`；普通费率与历史费率不受影响。
- **浏览器**：Edge 下 UTC 与两份下载入口 PASS；Firefox/Safari `NOT_VERIFIED`。
- **HC-01 quick**：未运行；SSRF opt-in、quick harness 的 `FINAL → Settlement → Ledger` 收敛不在本轮结论内。
- **原始数据/进程**：`aicostops` 原库计数未变（`ledger_entry=28`、`ledger_posting=27`、`provider_connection_profile=2`、`evidence=59`、`expense_claim=38`、`pricing_version=1`、`reconciliation_run=16`、`audit_event=713`）；原 `8080` 与 `5173` 进程未终止。

## 7. 清理与复现

清理结果：

```text
临时数据库：aicostops_rv、aicostops_rv_recon、aicostops_prodverify 均已 DROP
剩余数据库：aicostops、aicostops_hc01（既有）
临时容器：ai-costops-hc01-redis 无残留
端口：15173 / 18080 / 18082 已释放；8080 / 5173 仍为原进程
bsk：session list -> []
git：后端源码/测试与文档改动，未提交；无 .env/日志/构建产物入库
```

证据目录：`docs/03-acceptance/maintenance/2026-11/2026-11-13-runtime-verification/`。

原始日志与探针保存在 `.local-dev/logs/` 与 `.local-dev/probes/`（已被 `.gitignore` 忽略）；本报告只沉淀精简后的断言、命令与关键输出，不提交 raw log。

## 8. 结论

本轮运行期验收的最终判定为 **PASS（修复后复核）**：

- **FIXED / PASS**：`prod` profile 构造器装配失败；微小定价费率科学计数法/`—` 显示。
- **PASS**：HC-01 Redis 归属生命周期；`/ledger` 与 Provider 连接 UTC；Edge 下两处证据下载；对账僵尸 RUNNING 回收。
- **NOT_VERIFIED**：Firefox/Safari 下载；可选 HC-01 quick。

修复前的 FAIL 证据保留在 §4 / §5；修复内容、回归测试与修复后复核见 §9。修复变更当前只在工作区，未提交、未创建 PR。

## 9. 修复复核（用户确认“修复吧”后）

### 9.1 DEF-RT-01 — `prod` profile 构造器装配

修复：

- 在 `ProductionConfigurationValidator(Environment)` 上显式加 `@Autowired`，让 Spring 在存在 test-seam 构造器时仍选择生产构造器。
- 新增回归测试 `springSelectsTheEnvironmentConstructorForProductionInjection`，断言 `AutowiredAnnotationBeanPostProcessor` 只选中 `(Environment)` 构造器。

验证：

```text
.\mvnw.cmd -B "-Dtest=ProductionConfigurationValidatorTest,PricingRateResponseSerializationTest" test
-> Tests run: 35, Failures: 0, Errors: 0, Skipped: 0

.\mvnw.cmd -B -DskipTests package
-> BUILD SUCCESS（重新生成 backend-1.1.0.jar）

& .local-dev/probes/prod-config/run-prod-config-verification.ps1 -PerCaseTimeoutSeconds 240
-> OVERALL PASS (8/8 cases)
```

8/8 case 结果：

| # | endpoint | 结果 | 说明 |
|---|---|---|---|
| 1 | `http://0.0.0.0:9000` | PASS（fail-fast） | validator 拒绝 loopback，`wiring=False` |
| 2 | `http://127.1:9000` | PASS（fail-fast） | `URI.getHost()==null`，拒绝无 host |
| 3 | `http://2130706433:9000` | PASS（fail-fast） | JDK 解析到 `127.0.0.1`，拒绝 loopback |
| 4 | `http://[::ffff:127.0.0.1]:9000` | PASS（fail-fast） | IPv4-mapped IPv6 被识别为 loopback |
| 5 | `http://host.docker.internal:9000`，未 opt-in | PASS（fail-fast） | 内网明文要求显式 opt-in |
| 6 | 同上，`allow-plaintext-internal=true` | PASS（started） | `Started AiCostOpsApplication`，保持运行至主动停止 |
| 7 | `https://example.com` | PASS（started） | 公网 HTTPS 通过并启动 |
| 8 | `http://example.com` | PASS（fail-fast） | 公网明文被拒绝 |

补充：首次重跑时 case 6/7 因验收探针使用 `.env` 的本地默认 DB 密码 `change-me-local-only` 而被 validator 正确拒绝；改用 root 强密码后 8/8 通过。另修正探针在 Windows 下读取被重定向占用日志的 `FileShare` 问题；两项都是探针/基线问题，不是产品缺陷。

证据：`prod-config-cases-after-fix.csv`、`prod-config-after-fix.json`。

### 9.2 DEF-RT-02 — 微小定价费率序列化

修复：

- 新增 `Scale8DecimalSerializer`：对 `BigDecimal` 执行 `setScale(8, RoundingMode.UNNECESSARY).toPlainString()`，不再走 `BigDecimal.toString()`。
- `PricingRateResponse.unitPrice` 改用该 serializer；正常值、8 位小数和负值都保持 scale-8 定点串，无法精确表示时 fail-closed。

验证：

```text
.\mvnw.cmd -B "-Dtest=ProductionConfigurationValidatorTest,PricingRateResponseSerializationTest" test
-> ProductionConfigurationValidatorTest 33/33; PricingRateResponseSerializationTest 2/2

.\mvnw.cmd -B "-Dit.test=ModelPricingApiIntegrationTest" "-DfailIfNoTests=true" verify
-> surefire: 728 run / 0 failed / 2 skipped
-> failsafe: ModelPricingApiIntegrationTest 17/17
```

真实 API（固定 JAR，`GET /api/v1/pricing-versions`）：

```text
INPUT_TOKEN   unitPrice=[0.00000001]
OUTPUT_TOKEN  unitPrice=[0.00000002]
```

Edge `/settings/model-pricing` DOM 与截图：

```text
账号 32 / 模型 1 2 USD 已启用
输入 0.00000001 USD/1000000；输出 0.00000002 USD/1000000
```

证据：`pricing-tiny-rate-fixed.png`、`pricing-fixed-request.json`、`pricing-fixed-get.json`、`pricing-fixed-browser.md`。

### 9.3 修复后总体验证

```text
Backend 单元 + 架构（surefire）: 728 run / 0 failed / 2 skipped
ModelPricingApiIntegrationTest: 17 run / 0 failed / 0 skipped
ProductionConfigurationValidatorTest: 33 run / 0 failed / 0 skipped
PricingRateResponseSerializationTest: 2 run / 0 failed / 0 skipped
prod profile 真实启动: 8/8 PASS
Edge 微小费率: 0.00000001 USD 精确显示
npm run verify:links: 0 broken links
```

### 9.4 修复边界

- 未改财务语义、Flyway、账本/结算逻辑或默认配置。
- 未改 OpenAPI 响应 schema；修复使实现重新符合既有 `unitPrice` scale-8 正则。
- 前端 `PricingRateInput.unitPrice` 仍以 decimal string 发送，而 OpenAPI 输入声明为 number；服务端同时接受两者。该输入契约差异不影响本次缺陷，建议后续单独做契约清理。
- 修复变更未提交；当前为 `dev` 工作区改动。

## 10. 后续状态补记（2026-11-13）

本记录保留验收当时的“未提交工作区”状态，不改写原始证据。后续处置：

- 修复已由 PR #192 合入 `dev`、再由 PR #193 同步到 `main`（commit `d824c7f`），并随 `v3.1.2` 发布；
- `v3.1.1` 标签不变，仍不包含该修复；生产部署应使用 `v3.1.2` 或更新版本；
- P2-02 / P2-12 的历史 `NOT_VERIFIED` 由本记录 §9 的复核结果关闭；
  Firefox / Safari 与可选 HC-01 quick 仍保持 `NOT_VERIFIED`。
