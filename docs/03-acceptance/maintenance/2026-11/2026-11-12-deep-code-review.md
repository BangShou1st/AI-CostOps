# 2026-11-12 深度代码审查（V3 维护期）

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-12 |
| 审查者 | AI（DeepSeek Harness） |
| 代码基线 | `dev` = `main` = `75f02ea`（`test(hc03): gateway instance failover, dependency outages and settlement recovery evidence (#181)`） |
| 审查范围 | `backend/src/main/java`、`gateway/src/main/java`、`frontend/src`、Flyway 迁移、`.github/workflows`、`compose*.yaml`、`.env.example`、`scripts/` |
| 文档性质 | 维护期**只读**深度审查记录；**本次未修改任何业务代码、配置、CI 或脚本** |
| 关联设计 | [2026-11-10 后端可靠性与工程质量改造设计](../../../02-development/designs/maintenance/2026-11-10-backend-reliability-refactor-design.md)（F-01~F-09 财务不变量） |
| 前序验收 | [HC-01](2026-11-11-hc-01-gateway-concurrency/2026-11-11-hc-01-gateway-concurrency.md)、[HC-02](2026-11-11-hc-02-billing-period-lock/2026-11-11-hc-02-billing-period-lock.md)、[HC-03](2026-11-11-hc-03-failure-recovery/summary.md) |

---

## 0. 结论摘要

```text
财务正确性主链路（预算预占 → DISPATCH_INTENT → 用量事实 → Settlement → Ledger → Budget Actual → 关账）
  = 本次精读未发现新的发布阻断缺陷
  = F-01 ~ F-09 不变量在代码与 DB 约束层面仍然成立

外围防线（生产配置校验 / CI 门禁 / 会话卫生）
  = 4 项 P1：生产校验器放行仓库内公开的默认 JWT 密钥与对象存储凭据、
             CI 集成测试选择可静默为空、CI 合并门禁可被“全 skipped”绕过、
             登出接口失败时本地会话不被清除
  = 22 项 P2：调度线程耦合、配置/网络边界、分析口径、可观测口径、时区与契约漂移、死代码路径等
  = 6 项 P3：潜在陷阱与死代码

本次代码变更 = NONE
人工确认项 = 仓库 branch protection / required checks 实际配置；目标部署是否使用示例默认密钥
```

判据：`P1` = **门禁或生产/会话边界失效**（“以为在保护，实际没有保护”），不代表财务事实已经错记；
`P2` = 真实缺陷但影响可控或需特定条件；`P3` = 潜在陷阱、死代码、文档/契约漂移。
所有结论均为**静态精读 + 本地可执行验证**；未调用真实 Provider、未触碰生产环境、未运行 Docker E2E。

---

## 1. 审查方法与深度

| 层 | 做法 | 本次结果 |
|---|---|---|
| 静态精读 | 逐行读取资金主链路、状态机、SQL/DDL、配置校验器与前端金额/认证层；用 `git grep` 交叉核对每个调用点 | 约 70 个文件 / 约 1.1 万行；关键结论均落到 `文件:行号` |
| 可执行验证 | `npm test`（脚本 + Backend 单元/架构 + Gateway 单元/架构 + 前端单测）、`npm run verify:links`、`npm run doctor` | 全部通过，见 §5 |
| 反向核对 | 对每条候选发现追问“是否可达、是否只是防御性代码”，据此剔除 2 条不可达结论、降级 5 条严重度 | 见 §3 各条的“前置条件/状态” |

明确未做（避免过度声明）：

```text
未运行：integration（Testcontainers）、browser E2E、HC-01/02/03 perf harness、Docker 镜像构建
未验证：GitHub Actions 本轮远程结果、branch protection 实际配置、真实 Provider 调用
未覆盖：ingestion/providers/** 全部解析器、reconciliation/iam 非资金入口、frontend 全部页面级交互
```

---

## 2. 财务不变量复核（F-01 ~ F-09）

| 编号 | 不变量 | 代码级机制（已精读） | 结论 |
|---|---|---|---|
| F-01 | 金额禁用 float/double | 全链路 `BigDecimal` + `DECIMAL(20,8)`；`GatewaySettlementCostCalculator` 不预舍入、超 `DECIMAL(38,18)` 拒绝；前端 `lib/money.ts` 用 `BigInt` 最小单位 | **PASS**（唯一例外在展示层，见 P2-12） |
| F-02 | 重试不重复入账 | `gateway_request(org,credential,idempotency_digest)`、`budget_reservation(route_attempt)`、`ledger_posting(org,posting_key)`、`gateway_settlement(org,settlement_key)` 四层唯一约束 + fingerprint HMAC 比对 | **PASS** |
| F-03 | 预算并发原子判定 | TX1 锁 Budget 行后再 `SUM(ACTIVE,PENDING_HOLD)`，READ COMMITTED 当前读；`available = total-actual-committed-holds` 与插入同一事务 | **PASS** |
| F-04 | 未知用量不得按 0 结清 | 分类器把缺失/畸形/部分维度归 `INCOMPLETE`/`UNKNOWN`；discovery 只取 `uf.status='FINAL'`；失败终结把 ACTIVE 预占转 `PENDING_HOLD` | **PASS**（分析侧口径见 P2-07） |
| F-05 | POSTED Ledger 不可变 | Java 侧无 `UPDATE/DELETE ledger_entry\|ledger_posting`；更正走 `correction_group` + REVERSAL 追加；`LedgerImmutabilityArchitectureTest` 通过 | **PASS** |
| F-06 | CLOSED 账期写保护 | 所有资金写入口经 `selectByIdForUpdate`/`lockOpenAt` 状态校验；CLOSING 拒绝新写；关账用 `version` CAS | **PASS** |
| F-07 | 重试/崩溃不重复副作用 | Dispatch fence TX2、`FOR UPDATE OF gr, ra`、恢复扫描只在“确定未派发”时释放；advisor 任务用 fencing token | **PASS** |
| F-08 | Redis 故障不改财务真相 | Gateway Redis 命令 500ms 超时 fail-closed（Provider I/O 前）；预算/账本/关账均不读 Redis | **PASS** |
| F-09 | 每次财务变动可溯源 | `request → attempt → usage_fact → settlement → ledger_posting → ledger_entry` 全链 `org_id` 约束 + FK + 审计事件 | **PASS** |

---

## 3. 发现明细

> 所有条目均为**只读发现**。`【已确认】` = 本人逐行核对；`【待确认】` = 需仓库设置或运行环境证据。

### 3.1 P1（4 项）

#### P1-01 生产配置校验器放行仓库内公开的默认 JWT 密钥与对象存储凭据【已确认】

**位置**

- `backend/src/main/java/com/aicostops/config/ProductionConfigurationValidator.java:69-76`（JWT 只校验长度 ≥ 32）
- `backend/src/main/java/com/aicostops/config/ProductionConfigurationValidator.java:62-63`（对象存储 access/secret 只校验非空）
- `backend/src/main/resources/application.yml:77-79`（默认值 `http://localhost:9000` / `aicostops` / `change-me-local-only`）
- `.env.example:28`（`AICOSTOPS_JWT_SIGNING_KEY=local-development-only-signing-key-change-before-production-2026`，64 字符）
- `.env.example:19-20`（MinIO 用户名/口令即上述默认值）；`compose.yaml:16-17` 直接注入为 storage 凭据

**事实**

1. 同一校验器对 DB 口令使用 `rejectResolvedValue(..., "change-me-local-only", ...)`（`:64-65`），对 JWT 只判长度，对 storage 只判非空。
2. `.env.example` 的 JWT 密钥恰好 64 字符，**满足** `MINIMUM_JWT_KEY_LENGTH = 32`；HS256 的 key 就是这 64 字节（`JwtTokenService:25-28` 同样只拒绝 < 32 字节）。
3. 因此按文档 `cp .env.example .env` 后切 `prod`，校验器对这两类密钥**不会报错**：任何人只要有仓库读权限，即可用公开字符串离线签发 HS256 token，或以默认口令访问 Evidence 桶。JWT 的第二个门槛 `sv`（security version）也不难满足：`app_user.security_version` 的 DDL 默认值是 `0`（`V2__m1_identity_organization_schema.sql:19`），未改过密码/未触发 `logout-all` 的用户 `sv=0` 可直接猜中。

**影响**：校验器声称拦截的“dev-only/weak”值实际漏过；JWT 一项在沿用示例值的部署上等价于身份伪造（资金面可写）。属于生产边界失效，不是已发生的资金错记。

**建议最小修复（未执行，待授权）**

- JWT 校验增加对已知示例值的 denylist（`local-development-only-signing-key-*`），或强制“必须由部署注入且不得等于示例值”；
- storage access/secret 改为与 DB 口令对称的 `rejectResolvedValue`；
- `ProductionConfigurationValidatorTest` 增加“不设置环境变量、走 yml 默认解析”的用例（现有用例全部用 `MockEnvironment` 预置属性，覆盖不到该路径）。

#### P1-02 CI 的集成测试选择可静默为空，门禁仍报绿【已确认】

**位置**

- `.github/workflows/ci.yml:237`（`f.endsWith("IntegrationTest.java")` 文件名枚举）
- `.github/workflows/ci.yml:247`（`-Dit.test=$TESTS -DfailIfNoSpecifiedTests=false`）
- `backend/pom.xml:254/266`、`gateway/pom.xml:170/182`（surefire 排除、failsafe 只 include `**/*IntegrationTest.java`）

**事实**

- 分片列表由脚本遍历 `src/test/java` 按文件名后缀生成；枚举为空时 `-Dit.test=` 为空且 `failIfNoSpecifiedTests=false` 关闭了“指定了但没匹配到”的保护，Maven 报 `BUILD SUCCESS`。
- 命名为 `*IT.java` 的新集成测试既不被 surefire 的 `*Test.java` 收，也不被 failsafe 的 `**/*IntegrationTest.java` 收，**永远不会执行**，而 `backend-integration` 与 `ci-status` 均为绿。

**影响**：对以 Testcontainers 为主要正确性证据的项目，“已跑集成测试”的结论可能是 0 个用例。

**建议最小修复（未执行）**：去掉 `-DfailIfNoSpecifiedTests=false`，改 `-DfailIfNoTests=true`；枚举为空或数量少于 failsafe include 匹配数时 `exit 1`。

#### P1-03 合并门禁可被“全 skipped”绕过，且安全配置变更自证【已确认 + 待确认】

**位置**

- `.github/workflows/ci.yml:24-59`（paths-filter 只覆盖 backend/gateway/frontend/scripts/compose）
- `.github/workflows/ci.yml:565-577`（`ci-status` 只把 `failure`/`cancelled` 判失败，`skipped` 视为通过）
- `.github/codeql/codeql-config.yml:9-13`；`.github/workflows/security.yml:4`（`pull_request` 触发，PR 使用自身版本的 workflow 与 codeql 配置）
- 仓库无 `CODEOWNERS`、无 dependabot（`git ls-files` 无匹配）

**事实**

1. 只改 `.github/workflows/security.yml`、`.github/codeql/codeql-config.yml`、`deploy/**` 的 PR 不命中任何 filter → 全部作业 `skipped` → `ci-status` 通过。
2. 同一 PR 的 Security workflow 使用**被修改后的**配置（例如给 CodeQL 加 `paths-ignore: backend/src`）；job 名不变，required check 仍为“成功”。
3. `CODEOWNERS` 缺失意味着这类变更不需要安全负责人 review。

**影响**：安全门禁的配置本身不受门禁保护。是否可真正合并取决于仓库 ruleset 的 required checks 与 bypass 规则【待确认：需 `gh api repos/.../branches/main/protection` 或仓库设置截图】。

**建议最小修复（未执行）**：为 `.github/**`、`.trivyignore` 增加不受 paths-filter 影响的独立校验 job；补 `CODEOWNERS`；`ci-status` 对 `skipped` 要求“变更集确实与代码无关”的显式证明。

#### P1-04 登出失败时本地会话不被清除，且调用点丢弃异常【已确认】

**位置**

- `frontend/src/features/auth/AuthSessionProvider.tsx:205-220`（`logout` 仅在 `succeeded` 时 `clearLocalSession()`；非终态错误走 `catch` 后 `throw`）
- `frontend/src/app/layout/AuthenticatedLayout.tsx:181/218/228`、`frontend/src/features/auth/AuthPages.tsx:49`（`void auth.logout()`，无 catch、无提示）

**事实**

`logoutWithCookieLock` 在 refresh 失败或跨标签刷新竞争未决时会抛错（非 401）；此时 `succeeded=false`，`clearLocalSession()` 不执行，`status` 仍为 `authenticated`，access token 仍在内存，页面无任何提示。`void promise` 还产生 unhandled rejection。

**影响**：公共终端上用户以为已退出，实际仍可继续操作；这是可复现的会话卫生缺陷（触发条件：`/auth/logout` 或 refresh 失败）。不构成跨租户越权。

**建议最小修复（未执行）**：`logout()` 对非终态失败也执行 `clearLocalSession()`（服务端注销降级为 best-effort，保留 cookie 供下次 refresh 失效）；调用点 `.catch()` 并提示“已本地登出”。

### 3.2 P2（22 项）

| ID | 标题 | 证据 | 事实与影响 | 建议 |
|---|---|---|---|---|
| P2-01 | 单线程 `TaskScheduler` 把 Advisor 推理与预算预占回收串在一起 | `GatewayApplication.java:13`（`@EnableScheduling`）；`AdvisorInferenceWorker.java:93-104`（`tick()` 在调度线程内循环 claim + Provider 调用）；`ReservationRecoveryScheduler.java:32`；`gateway/src/main/resources/application.yml` 无 `spring.task.scheduling.pool.size` | Spring Boot 4.1.0 默认 `pool.size=1`（已从 `spring-boot-autoconfigure-4.1.0.jar` 元数据核对）；一次 Advisor 推理最长占用 60s（header timeout），排队时会连续占用 → 预占过期扫描的**触发**被推迟，ACTIVE/PENDING_HOLD 释放变慢（偏保守，不超扣，但延长关账阻塞）。本地测试日志已出现 `[scheduling-1] AdvisorInferenceWorker` 阻塞 30s 的现场 | 为 `@EnableScheduling` 配置 ≥2 线程的 `ThreadPoolTaskScheduler`（或 advisor 独立调度器）；advisor 增加批量上限与可禁用开关 |
| P2-02 | 生产“本地性”判断是子串匹配，且 compose 把对象存储写死为明文 HTTP | `ProductionConfigurationValidator.java:136-139`；`compose.yaml:15`（`AICOSTOPS_STORAGE_ENDPOINT: http://minio:9000` 无 `${}` 包装）；`compose.yaml:34` | `http://0.0.0.0:9000`、`http://127.1:9000`、`http://2130706433:9000`（十进制 IP）、`http://[::ffff:127.0.0.1]:9000` 均不含三个字面子串 → 通过 loopback 判定；同一 helper 用于 `allowed-origins`，也会误杀含 `localhost` 字样的合法域名。生产 profile 下对象存储端点始终明文 | 用 `URI` + `InetAddress` 解析后复用 `DispatchEndpointGuard.isPublicUnicast`；endpoint 改必填变量；显式要求 `https` 或文档化内网明文例外 |
| P2-03 | dev overlay 把 MySQL/Redis/MinIO 发布到所有网卡 | `compose.dev.yaml:16/20/24`（无 `127.0.0.1:` 前缀）；`scripts/tasks.mjs:127`（`infra:up` 组合 `compose.yaml + compose.dev.yaml`）；`.env.example:9/17/20` | 开发者按文档起基础设施后，同网段任何人可用示例口令直连财务库/Redis/MinIO 控制台。对照 `compose.v3-operational.yaml:53`、`compose.m16.yaml:48` 均已写 `127.0.0.1:` | 所有 dev 端口补 `127.0.0.1:` 前缀 |
| P2-04 | `hc01` 性能 harness 无归属校验地删除固定名容器 | `scripts/perf/hc01/runner.mjs:290`、`:319`（`docker rm -f ai-costops-hc01-redis`） | 并发两个 run 时后启动者删掉前者 Redis，压测数据静默失真；同名用户容器被直接销毁。同仓库 `scripts/perf/hc03/ownership.mjs:124-135` 已实现“无 owner label 一律拒绝删除”并有测试 | 复用 hc03 ownership 决策；补 harness 测试 |
| P2-05 | CodeQL 不分析 `scripts/` | `.github/codeql/codeql-config.yml:9-13` | `scripts/**`（含 `child_process`、递归删除、Docker 控制）一行都不进 SAST；这是开发者本机高权限执行的代码 | `paths` 追加 `scripts`，必要时对 `perf/**` 单独放宽 |
| P2-06 | 登录对未知邮箱短路 bcrypt，形成用户枚举计时侧信道 | `iam/application/LoginService.java:57`（`identity == null` 时短路，不再执行 `passwordEncoder.matches`） | 邮箱不存在时不执行 bcrypt（cost 10，约 50-100ms），存在时执行；同一 401 但耗时差可批量测量。限流 8 次/账号/15min + 20 次/IP/15min，跨账号喷洒仍可行 | 未知邮箱时对固定 dummy hash 执行一次 `matches` 再抛同一异常 |
| P2-07 | 成本智能把“未观察日”当零成本纳入异常基线 | `CostIntelligenceAnalysisService.java:377-385`（`completeSeries` 补 `ZERO` 且 `observed=false`）；`CostAnomalyEngine.java:70-79`（`detect` 只读 amount，从不读 `observed`）；同文件 `:252-257`（`latestDelta` 用 `history.get(size/2)`） | 缺口日与“真零消费”被压成同一 `(0,false)`：28 天 baseline 的 median/MAD 被缺口拉低 → z 分数虚高 → 虚假异常；异常行被 Advisor 冻结为证据进入 LLM 叙述。偶数样本下 `latestDelta` 取上中位数，与 `median()` 口径不一致 | `detect` 要求被评估桶 `observed=true`、baseline 只取 `observed` 桶；`latestDelta` 复用 `CostAnomalyEngine.median()` |
| P2-08 | Workbench 的 provider 口径与 project 口径不同基 | `reporting/infrastructure/WorkbenchQueryMapper.java:23-33`（`CHARGE_BASIS` 要求 import CONFIRMED + `review_status='CLEAN'`）vs `:71-94`（按项目汇总只要求 allocation CONFIRMED）vs `:96-110` | 一条已确认分摊、但随后被判 `EXCLUDED_DUPLICATE/NONCOST` 的 charge，会让“provider 合计 ≠ project 合计 + 未分摊”。金额未入账，属报表一致性缺陷 | 按项目汇总复用 `CHARGE_BASIS`，或明确文档化两者不同基与对账公式 |
| P2-09 | Gateway 可观测口径缺口：流式成功未计 `COMPLETED`；`SAFE_RELEASED_TERMINAL` 落 `UNKNOWN` | `ChatCompletionController.java:487`（仅非流式成功记录 `COMPLETED`）、`:414/:559`；`GatewayMetrics.java:39-41/44-46/201-203` | 流式是主要流量，`gateway_request_total{outcome="COMPLETED"}` 系统性偏低，成功率面板失真；`recoverSafeReleasedRequest` 的终结结果在指标里不可见 | 流式成功终结补记 `COMPLETED`；把 `SAFE_RELEASED_TERMINAL` 加入 bounded 集合 |
| P2-10 | 保留第二套仅测试使用的授权/派发路径（约 145 行） | `GatewayRequestService.java:76-259`；调用点只有 `DispatchFenceIntegrationTest`、`GatewayRequestIdempotencyIntegrationTest`、`ReservationRecoveryIntegrationTest` | 该路径自带单候选选路、`candidateOnly=false` 的终结拒绝语义，与生产 orchestrator 的多候选/SAFE failover 不同；测试通过不代表生产路径被覆盖，两条路径会随维护漂移 | 删除并让测试改走 orchestrator，或抽公共段并标注“仅测试入口” |
| P2-11 | 成功终结的 `DISPATCH_INTENT` 分支与 mapper 谓词矛盾 | `GatewayUsageFinalizationService.java:204-217` vs `GatewayRequestMapper.java:259-264`（只允许 `UPSTREAM_ACTIVE`）；`requireOne` 在 `:288-292` | 若该分支被触发，UPDATE 影响 0 行 → 异常 → 整个成功终结事务回滚（用量事实也不落库），请求悬挂在 `DISPATCH_INTENT` + ACTIVE 预占。当前不可达（`beginUpstream` 必先置 `UPSTREAM_ACTIVE`），但一旦可达会放大故障 | 删掉 `DISPATCH_INTENT` 分支，或让 mapper 同时接受该状态，保持 guard 与 SQL 一致 |
| P2-12 | 定价速率以 JSON number 出线，前端按 2 位小数展示 | `gatewayadmin/api/ControlPlaneDtos.java:109-113`（`BigDecimal unitPrice`，无 `ToStringSerializer`，全仓仅 `Money.amount` 有）；`frontend/src/features/gateway/api/gatewayTypes.ts:72-75`（`unitPrice: number`）；`ModelPricingPage.tsx:120`（`String(rate.unitPrice)` → `formatMoney`） | 常规“每 100 万 token 30 美元”显示正常；但单价 < 0.005 时被四舍五入成 `0.00`，< 1e-6 时 `String()` 产生指数记法（如 `1e-7`）→ DECIMAL_RE 不匹配 → 显示 `—`。属唯一把金额声明成 `number` 的契约点，违反“金额全程 decimal string”不变量 | DTO 加 `@JsonSerialize(using = ToStringSerializer.class)`；前端类型改 `string` 并用 8 位定点展示 |
| P2-13 | 定价创建缺少维度覆盖/重复校验，版本号 `max+1` 无串行化 | `ModelPricingService.java:98-111`；`ControlPlaneMapper.java:201-208`（普通 SELECT）；V18 `pricing_rate` 唯一键 `(pricing_version_id,dimension_code)` | 重复 `dimensionCode` → 唯一键冲突 → 500（应 400）；并发/双击创建 → 同一 `version` → 500（应 409）；只含 `INPUT_TOKEN` 的价目表可激活（准入阶段会被 `ReservationAmountCalculator` 拒绝，故暂不产生零成本结算） | 入参去重 + 维度覆盖校验；事务内先锁 organization 行；`DuplicateKeyException` → `STATE_CONFLICT` |
| P2-14 | 路由策略列表 N+1 | `routing/application/RoutingPolicyService.java:46` → `:211-216`（每行 `findCandidates`） | 页大小 200 时约 202 次往返，管理台列表在候选多时明显变慢 | 批量 `IN` 取候选后内存分组 |
| P2-15 | 对账 run 进程崩溃后永久 `RUNNING`，`CREATED` 枚举从未写入 | `reconciliation/application/ReconciliationRunService.java:87-99`、`:181-207`；`WorkbenchQueryMapper.java:127-131` | 崩溃/重启后无补偿入口，“活跃对账”永久非零，误导运维；关账不受阻（blocker 只看最新 run 状态） | 增加启动时/定时“僵尸 RUNNING 收敛”（按 started_at 超时），或统一由 blocker 忽略过期 run |
| P2-16 | 两套时区口径：同一 Instant 在不同页面相差 8 小时，且一处不标时区 | `frontend/src/lib/dateTime.ts:3-12`、`:43-51`（固定 `Asia/Shanghai`，输出无时区后缀）vs `features/intelligence/format.ts:44-52`（默认 `UTC` + `timeZoneName:'short'`）；UTC 组：`ModelPricingPage.tsx:121`、`ProviderConnectionsPage.tsx:63`、`ConnectionDetailPage.tsx:96/121/131`；上海组：`LedgerListPage.tsx:72`、`ReconciliationRunDetailPage.tsx:112-113`、`PeriodClosePage.tsx:198-199`、`CommitmentTable.tsx:63` | 同一套 ISO-8601 UTC Instant，`/provider-connections` 的 `activatedAt` 按 UTC 显示并标 UTC，`/ledger/postings` 的 `postedAt` 按东八区显示且不标时区；人工对账时同一事件出现两个相差 8 小时的时间，且上海组无法分辨本地/UTC；定价生效窗口（UTC）与入账时间（上海）比对会系统性偏移 | 收敛为单一 formatter（保留 `Asia/Shanghai` 并显式 `+08:00`，或全站 UTC 并标注）；删除重复的 `new Intl.DateTimeFormat` |
| P2-17 | 契约与文档资产漂移（枚举缺值 + 端点未收录 + 契约副本） | `ledger/domain/LedgerSourceType.java`（5 值）vs `docs/02-development/api/openapi.yaml:632`（3 值）；`/pricing-versions`、`/model-catalog`、`/provider-models`、`/routing-policies`、`/routing-options`、`/gateway-credentials`、`/service-identities` 均不在三份 OpenAPI；`docs/03-acceptance/m18-openapi.yaml` 与 `docs/02-development/api/m18-v3-openapi.yaml` 内容完全相同（384 行，SHA-256 一致） | 按契约实现的消费方会把 `GATEWAY_SETTLEMENT`/`RECONCILIATION_ADJUSTMENT` 两类真实入账当未知来源；7 组端点无契约、无契约测试、权限码无处校验；双份契约必然漂移。违反 AGENTS §7“四同步” | 补齐枚举与 paths（含 `x-permission`）；CI 增加“Controller 路由 ⊆ 契约 paths”断言；删除重复副本，契约只保留在 `02-development/api/` |
| P2-18 | OpenAI / MiMo 适配器的传输层 SSRF 守卫只在 `prod` profile 生效 | `provider/openai/OpenAiChatAdapter.java:55`、`provider/mimo/MimoChatAdapter.java:68`（`environment.acceptsProfiles(Profiles.of("prod"))`）；`compose.yaml:34`、`compose.m16.yaml:33`（默认空 profile）；对照 `provider/shared/AbstractOpenAiCompatibleAdapter.java` 无条件装 `PublicOnlyAddressResolverGroup` + `followRedirect(false)` | 连接档案在激活时做过一次公网解析（`ProviderConnectionService.java:225-227`），Gateway 侧不再做传输层复验；DNS rebinding/解析漂移在空 profile 部署下无第二道防线。内置模板 baseUrl 默认固定、自定义端点走无条件 resolver，故实际可利用性低 | resolver 与 endpoint allowlist 默认开启，仅显式开关关闭；或在 compose 显式声明 `prod` |
| P2-19 | 前端依赖声明不完整/冗余 | `frontend/package.json:12-21` 无 `dayjs`，但 `ExpenseForm.tsx:2` 值导入；`echarts@6.1.0` 声明但 `frontend/src` 与 `frontend/e2e` 零引用（所有图是手写 SVG） | `npm ci` 下今天能用只因 antd 传递依赖并 hoist；antd 升级或换严格 hoisting 即编译失败。`echarts` 白白扩大依赖面与扫描成本，并使 AGENTS/README 的技术栈描述失真 | `npm pkg set dependencies.dayjs=<已装版本>`；删除 `echarts` 并同步技术栈描述 |
| P2-20 | 列表“最新在前”用 `localeCompare` 比数字字符串 id | `GatewayCredentialsPage.tsx:72`、`ModelPricingPage.tsx:78`、`RoutingPoliciesPage.tsx:56` | `['9','10','100'].sort((a,b)=>b.localeCompare(a))` → `9,100,10`；从第 10 条起顺序错误，运维可能在密集列表里误吊销旧凭证/误启用旧版本 | 按 `Number(id)` 或 `createdAt` 排序；更好的做法是后端返回有序分页 |
| P2-21 | `/billing-periods` 有两套客户端、两套缓存键、两份类型 | `features/budgets/api/billingPeriodApi.ts:13-20`（key `['billing-period','list']`）vs `features/period-close/api/periodCloseApi.ts:16-18`（key `['period-close','periods']`） | 关账后只失效 `['period-close',...]`，预算/账本侧账期下拉不会同步；`BillingPeriodResponse` 两份类型会分叉。当前 `staleTime=0` 掩盖，一旦加缓存立刻出现“关账后仍显示 OPEN” | 删除其一，统一客户端与 keystore 类型 |
| P2-22 | 证据下载后同帧 `revokeObjectURL`【待确认】 | `features/evidence/EvidenceDetailPage.tsx:45-50`、`features/expenses/components/ExpenseEvidenceSection.tsx:55-62` | Chrome 通常已把 blob 交给下载管理器；Firefox/Safari 在导航开始前 revoke 可能得到空文件/中断。属审计链路可用性风险 | `setTimeout(revoke, 0)` 或等 anchor 移除后再 revoke；EvidenceDetailPage 也应 append 到 body |

### 3.3 P3（6 项）

| ID | 标题 | 证据 | 事实与影响 |
|---|---|---|---|
| P3-01 | `CACHED_INPUT_TOKEN` 可被 API 配置，但数据面永不产出该维度 | `ModelPricingService.java:30-31`（白名单含 CACHED）；`GatewayUsageObservation.java:62-95`（`cachedInputTokens` 恒 null）；`GatewayUsageClassifier.java:41-42` | 一旦价目表配置 CACHED 维度，该模型所有用量被判 `INCOMPLETE` → 永不 FINAL → 永不 Settlement，只能人工对账。UI 只提交 INPUT/OUTPUT，故仅 API 路径可达 |
| P3-02 | `percentOf` 比较正则笔误 | `frontend/src/features/intelligence/decimal.ts:127`（`/^0+(?=\\d)/`） | 正则里的 `\\d` 匹配字面反斜杠+d，前导零实际不会被去；当前因 `padStart(scale+1)` 无可观测影响，但未来改宽度即出错 |
| P3-03 | `EXPENSE_RESUBMIT` 幂等操作死代码 | `expense/application/ExpenseIdempotency.java:26`、`:70-78`；`ExpenseClaimCommandService.java:130-135` 固定用 `OPERATION_SUBMIT` | 目前不生效；若维护期“修正”为 RESUBMIT，同一 `Idempotency-Key` 会在两个 operation 下各生效一次，重放保护被拆分 |
| P3-04 | `InitialCandidateResult.skipped()` 死代码 | `GatewayRequestOrchestrator.java:233-238`（无调用点） | 易被误认为存在“跳过候选”的第三条路径，增加阅读成本 |
| P3-05 | 分摊编辑器在 render 期直接 `parseDecimal8`，无兜底 | `features/allocation/shared/AllocationLinesEditor.tsx:102`、`features/allocation/AllocationEditor.tsx:81-83`；同文件 `:72-74` 已有 `safeParse` | 后台若返回非 scale-8 金额，异常发生在 render → 被 `RouteErrorBoundary` 捕获 → 整页“页面加载失败”，而不是提示具体字段；同一错误金额走 `formatMoney` 则静默显示 `—`。当前契约严格，未触发 |
| P3-06 | 前端死代码与“幽灵权限码” | `features/auth/AuthPages.tsx:47-50`（`AppPage` 全仓除定义外 0 引用，自带登出按钮但不受路由守卫）；`app/layout/appNavigation.tsx:9`（`readPermission: 'WORKBENCH_SECTION'`，后端/迁移/文档均无该权限码；可见性实际由 `WORKBENCH_SECTION_PERMISSIONS` 特判） | 早期占位页残留，形成第二套未受 `ProtectedRoute`/`PermissionRoute` 约束的登出入口；假权限码一旦有人去掉特判，工作台会对所有人隐藏且无报错。建议删除 `AppPage`，`readPermission` 改为可选或直接使用真实 `hasWorkbenchAccess` 语义，并在测试中断言每个码都存在于 `/permissions` 目录 |

---

## 4. 已深读且判定为正确的关键路径（防“只看缺陷”）

以下路径逐行走查后**未发现需要修复的问题**，作为本次“深入读取”的证据留档：

1. **TX1 预算准入**（`BudgetReservationService:86-183`）：共享账期锁 → 预算行 X 锁 → `findByRouteAttempt` 幂等回放 → `countEffectiveHolds` 防双持 → 无 FX 的币种精确匹配 → CEILING 到 8 位 → 唯一键冲突回放；锁序与 Close/Settlement 一致。
2. **TX2 派发栅栏**（`DispatchFenceService:52-137`）：`FOR SHARE` 账期 + 状态复核 + reservation 逐字段匹配 + attempt/state 双 CAS + 并发 loser 返回 `IN_PROGRESS`（READ COMMITTED 下可见 winner 已提交状态）。
3. **SAFE failover**（`GatewayRequestOrchestrator:90-177/241-385`）：只有 `SAFE_NO_BILLABLE_EXECUTION` 才能前进；候选按冻结策略顺序、历史候选去重、N+1 前必须“无有效预占”；取消在两处检查并可终结新 current route。
4. **用量事实**（`GatewayUsageFinalizationService:77-197`）：SAFE 路由的成功/取消终结是显式 no-op（绝不制造用量）；已 FINAL 不被实时链路覆盖；无用量不落零维度；provider metadata 白名单 + 8 KiB 上限。
5. **结算原子性**（`GatewaySettlementService:143-285`）：period→budget→commitment→reservation→settlement 固定锁序；`validateImmutableIdentity`/`validatePeriod`/`validateReservation`/`validateLineage` 四重校验；超额只告警不隐藏；失败区分 retryable vs reconciliation 且都在事务外落状态。
6. **关账状态机**（`PeriodCloseService:79-283`）：org 准入锁 → 周期锁 → generation/attempt 校验；blocker 事务外评估、事务内落检查；`ERROR`→FAILED+回 OPEN，`FAIL`→BLOCKED+回 OPEN，成功才 CLOSED；死锁只重试事务边界。
7. **账本幂等**（`GatewaySettlementLedgerService:25-105`）：`GATEWAY_SETTLEMENT:<id>` posting_key 幂等，重放校验 posting/entry 全字段；SYSTEM actor 与 `posted_by_member_id NULL` 由 DB CHECK 保证；单 entry 基数校验。
8. **预占恢复**（`ReservationRecoveryService:54-219`）：TTL 只作为“可判定”触发而非零成本证明；SAFE 或 `RESERVED/VALIDATED + PLANNED` 才释放，其余转 PENDING_HOLD；每笔独立事务 + 版本 CAS。
9. **Gateway 鉴权**（`GatewayAuthenticationManager:56-100`）：prefix 解析 + HMAC 恒定时间比较 + `INTERNAL_SYSTEM` 拒绝 + principal/project/financial scope 三级 ACTIVE 校验。
10. **JWT**（`JwtTokenService:24-57` + `BearerAuthenticationFilter:36-64`）：HS256 单一算法、密钥 ≥256 bit、0 leeway、每次请求校验 `sv`。
11. **Commitment 消费**（`CommitmentConsumeService:90-187`）：`MANDATORY` 事务、period→budget→commitment 锁序、lineage 重放优先于状态门禁、`min(entry, remaining)` 与 `committed_amount` 原子递减。
12. **账本入账**（`ProviderChargePostingService:132-215`）：事务内重新加载并逐字段比对来源/分摊、allocation sum 必须等于 charge、hybrid fence 在期间锁之后执行、commitment link 目标与状态校验。
13. **关账 blocker**（`GatewayCloseBlockerMapper:18-65`）：只有“可能已计费且无 FINAL→SETTLED 闭环”的请求才阻塞；有效 `gateway_financial_resolution` 才豁免；SAFE 历史不产生假阻塞。
14. **前端金额**（`lib/money.ts`、`intelligence/decimal.ts`）：API 金额一律按 decimal string 处理，计算用 `BigInt`，展示不经过 IEEE-754；`lib/money.ts` 在超过分位精度时显示精确值，`intelligence/decimal.ts` 走定点 2 位（两者并存与丢弃精度的问题见 P2-12/P2-16）。
15. **认证协作**（`api/client.ts:31-84`）：刷新单飞、仅终态错误清 token、409 刷新竞争不误杀会话、重试只重放一次。

---

## 5. 验证执行记录

环境（`npm run doctor`，2026-11-12 03:08）：

```text
Node >=24: OK v24.14.0
Java 21:   OK 21.0.10 LTS
npm:       OK 11.9.0
Docker:    OK 引擎已就绪
backend Maven wrapper: OK      gateway Maven wrapper: OK      frontend 依赖: 存在
```

单元 / 架构 / 前端测试（`npm test`，全部通过）：

| 套件 | 结果 |
|---|---|
| `scripts/tests/*.test.mjs` | 41 passed / 0 failed |
| Backend（含 ArchUnit） | 696 run / 0 fail / 2 skipped |
| Gateway（含 ArchUnit） | 232 run / 0 fail / 1 skipped |
| Frontend（Vitest） | 547 passed / 72 files |
| 合计 | **1516 个用例，0 失败，3 skipped**（LIVE 探针/开发邮箱条件用例） |

架构约束同时通过：`ModuleDependencyArchitectureTest`（31）、`GatewaySettlementArchitectureTest`（2）、`LedgerImmutabilityArchitectureTest`（2）、`GatewayArchitectureTest`（7）、`GatewayNoExplicitTableLockTest`（1）。

文档链接校验：

```text
$ npm run verify:links
[verify-doc-links] Found 244 Markdown files to inspect.
[verify-doc-links] Checked 379 links.
[verify-doc-links] SUCCESS: 0 broken links found! All local document links are valid.
```

补充现场证据（本次测试运行日志，非构造）：

```text
[ scheduling-1] c.a.g.advisor.AdvisorInferenceWorker : Advisor worker tick failed
  ... HikariPool-1 - Connection is not available, request timed out after 30013ms
```

该日志直接证明 `AdvisorInferenceWorker.tick()` 运行在唯一的 `scheduling-1` 调度线程上（P2-01 的运行期证据）。

---

## 6. 未覆盖与限制

| 项 | 状态 | 说明 |
|---|---|---|
| Backend/Gateway 集成测试（Testcontainers） | `NOT_RUN` | 本轮为静态审查 + 单测；集成证据沿用 HC-03 与 CO 系列报告，未在本 SHA 重跑 |
| Browser E2E / 真实 UI 验收 | `NOT_RUN` | 本次无 UI 变更 |
| 真实 Provider 调用 | `NOT_RUN` | 不触碰外部计费 |
| GitHub Actions 远程结果 / branch protection | `NOT_VERIFIED` | 只读本地工作区；P1-03 的可合并性依赖仓库设置 |
| `ingestion/providers/**` 解析器 | `NOT_READ` | 数值/时区/币种解析是下一轮最值得投入的读取面 |
| `reconciliation` / `iam` 非资金入口 | `PARTIAL` | 只精读与资金/安全直接相关的入口与 SQL |
| `frontend` 全量页面 | `PARTIAL` | 精读 API 层、金额/小数、认证协作、定价与账本页面；其余页面未逐一走查 |

---

## 7. 后续维护建议（需显式授权后才执行）

按“真实缺陷 + 必要性”排序，本次**未做任何代码改动**：

| 优先级 | 工作项 | 触发条件 |
|---|---|---|
| 1 | P1-01 生产校验器补默认值 denylist + 默认解析路径测试 | 一次安全加固窗口；改动小、可独立回归 |
| 2 | P1-02 / P1-03 CI 门禁收紧（failIfNoTests、CODEOWNERS、skipped 语义） | 下一次 CI 维护；影响所有 PR，需人工确认 ruleset |
| 3 | P1-04 登出失败本地清理 + 提示 | 下一次前端维护 |
| 4 | P2-01 调度线程隔离（advisor 与 recovery 解耦） | 出现 Advisor 高峰或关账被延阻时 |
| 5 | P2-07 / P2-08 / P2-09 / P2-12 / P2-16 / P2-17 口径与契约修正 | 需先确认对外口径（异常判定、Workbench 对账公式、成功率面板、金额契约、时间展示） |
| 6 | P2-10 / P2-11 / P3-03 / P3-04 / P3-06 死代码清理 | 随下一次触及该模块的缺陷修复一起做 |
| 7 | P2-19 / P2-21 / P3-02 / P3-05 前端依赖与守卫 | 下一次前端依赖/质量维护 |

---

## 8. 结论与影响边界

- **判定**：本次深度审查 **PASS（无新增发布阻断缺陷）**。资金主链路的并发、幂等、状态机、不可变账本与关账栅栏在代码与 DB 约束层面均成立；本地 1516 个单测/架构/前端用例全绿，文档链接 0 死链。
- **但**：P1-01~P1-04 说明“生产配置校验”“CI 门禁”“登出会话”这三道**外围防线**存在可被配置、命名或异常路径绕过的缺口。它们不改变已经发生的财务事实，但会让运维/评审误以为防线存在。
- **影响边界**：本记录不构成任何环境的生产就绪声明；`STABLE_RELEASE` 仍为 `v3.0.0`，`main` 与 `dev` 仍指向 `75f02ea`。本次未修改代码、配置、CI 与脚本。

---

## 9. 处置记录（2026-11-12 追加）

> 本节只追加处置结果，不改写上方原始发现、证据与结论。逐项 before/after、根因、验证命令输出摘要、
> `NOT_VERIFIED` 与影响边界见 [2026-11-12 P1 外围防线加固与 P2 口径修复](2026-11-12-p1-hardening-fixes.md)。

| 发现 | 处置 | 证据摘要 |
|---|---|---|
| P1-01 生产校验器放行默认密钥 | **FIXED** | 拒绝 `local-development-only-signing-key` 前缀及 `aicostops` / `change-me-local-only` storage 凭据；新增 3 用例先红（19 run / 3 failed）后绿（19/0/0） |
| P1-02 CI 集成测试选择可静默为空 | **FIXED** | 抽出 `scripts/ci/select-integration-tests.mjs`，空枚举/空分片退出 1；199 个集成测试分片 100/99 与旧规则一致；删除 `-DfailIfNoSpecifiedTests=false` |
| P1-03 合并门禁可被“全 skipped”绕过 | **FIXED（仓库内部分）** / `NOT_VERIFIED`（仓库设置） | 新增 `.github/CODEOWNERS`；`ci-status` 对无理由 skip 判红，docs-only/backend-only/frontend-only/scripts-only PASS、`.github`-only/forced-skip/failure FAIL；branch protection 与 ruleset 未验证 |
| P1-04 登出失败不清本地会话 | **FIXED** | 500 logout 与 refresh 失败均清 token/缓存并置 anonymous，401 终态语义不变；调用点提示“已本地登出”，无 unhandled rejection |
| P2-09 Gateway 可观测口径 | **FIXED** | 流式成功 `COMPLETED` 增量 1；`SAFE_RELEASED_TERMINAL` 不再落 `UNKNOWN`；定向 `GatewaySafeFailoverIntegrationTest` 4/4 |
| P2-20 数字字符串 id 排序 | **FIXED** | 统一 `BigInt` 数值降序；`['9','10','100'] → ['100','10','9']` |

**报告更正与复核修订**：`CHANGELOG` 的“198 个集成测试类”是 `57696e2` 当时的准确计数，`75f02ea` 起为
**199** 个（shard 0 = 100、shard 1 = 99）——属测试增长，不是历史错误，本报告也未引用该数字。
本报告 P1-03 引用的 `ci.yml:565-577` 覆盖基线 `if: always()`（565）到旧循环 `fi`（577），已包含被测逻辑
（基线文件共 579 行），无须更正。P1-04 的 `AuthPages.tsx:49` 确属 P3-06 的死代码调用点，现场景已由
`AuthenticatedLayout` 的三处调用覆盖。

**远程复核（2026-11-12 推送 `dev`）**：[CI 43137592677](https://github.com/BangShou1st/AI-CostOps/actions/runs/43137592677)
14/14 success，日志显示 shard 0 = 100、shard 1 = 99，`ci-status` 输出 `All required checks passed (docs_only=false)`；
[Security 43137592638](https://github.com/BangShou1st/AI-CostOps/actions/runs/43137592638) trivy + CodeQL 3/3。
P1-02 / P1-03 的远程证据由此补齐；branch protection 仍待仓库设置确认。

**未处置**：本批仅处理上述 6 项；其余 20 项 P2 与 6 项 P3 保持原状，等待后续独立维护窗口。

---

## 10. 处置记录（第二批：批次 A 低风险清理，2026-11-12 追加）

> 本节只追加处置结果，不改写上方原始发现、证据与结论。逐项 before/after、红/绿输出、`NOT_VERIFIED`
> 与影响边界见 [2026-11-12 剩余 P2/P3 低风险清理（批次 A）](2026-11-12-remaining-p2-p3-cleanup.md)。

| 发现 | 处置 | 证据摘要 |
|---|---|---|
| P2-03 dev overlay 暴露 MySQL/Redis/MinIO | **FIXED** | 四个端口补 `127.0.0.1:`；`docker compose config` 解析为 `127.0.0.1:3307/6379/9000/9001` |
| P2-04 hc01 无归属删除固定名 Redis | **FIXED**（逻辑）/ `NOT_VERIFIED`（真实容器生命周期） | 复用 HC-03 决策并加 hc01 label；无 label / 他人 owner / 仅 HC-03 label 均拒绝；新增 6 个纯函数用例，脚本套件 54/54 |
| P2-05 CodeQL 不分析 `scripts/` | **FIXED** | `paths` 追加 `scripts`；PR #184 的 `codeql (javascript-typescript)` success（2m8s），未产生新告警 |
| P2-19 前端依赖声明不完整/冗余 | **FIXED** | 声明 `dayjs@1.11.21`、删除 `echarts`；干净 `npm ci` 296 包成功，`echarts`/`tslib` 不存在 |
| P3-02 `percentOf` 正则笔误 | **FIXED** | `/^0+(?=\\d)/` → `/^0+(?=\d)/`；当前无可观测行为差异，无法提供黑盒红/绿 |
| P3-05 分摊编辑器 render 期解析无兜底 | **FIXED** | 旧实现 `1 failed / 18 passed`（render 抛 `Not an exact scale-8 money string`），新实现 `19 passed`；非法金额显示“金额格式异常” |
| P3-06 `AppPage` 死代码与 `WORKBENCH_SECTION` | **FIXED** | 删除 `AppPage`；`readPermission` 改可选、工作台走真实谓词；新增导航权限目录一致性测试（先红：`WORKBENCH_SECTION` 不在 Flyway 种子） |

**报告更正**：本批未发现审查报告与代码的事实冲突，无需更正。补充两点行号/上下文说明：
P3-06 的 `AuthPages.tsx:47-50` 在 P1-04 修复后位移到 48-52，属正常行漂移；`useAuth` 同时被
`LoginPage` 使用，因此删除 `AppPage` 时保留该导入（审查报告未声称相反）。

**未处置**：本批只做上述 7 项。P2-01/02/06/07/08/10/11/12/13/14/15/16/17/18/21/22 与
P3-01/03/04 保持原状，留待批次 B/C。

---

## 11. 处置记录（第三批：批次 B 后端修复，2026-11-12 追加）

> 本节只追加处置结果，不改写上方原始发现、证据与结论。逐项 before/after、红/绿输出、`NOT_VERIFIED`
> 与影响边界见 [2026-11-12 剩余 P2/P3 后端修复（批次 B）](2026-11-12-remaining-p2-p3-backend.md)。

| 发现 | 处置 | 证据摘要 |
|---|---|---|
| P2-10 仅测试的授权/派发路径 | **FIXED** | 抽出 `authorizePrelude`（身份/模型/账期/请求收敛）供两条路径共用；`authorizeAndFence` Javadoc 标注 test-only；3 个定向集成测试 5+3+12 全过。test-only 路径“无路由 + 无账期”同时出现时错误码由 403 变 503（生产路径不变） |
| P2-11 成功终结 `DISPATCH_INTENT` 矛盾 | **FIXED** | 选择“放宽 SQL”：`markRequestTransportCompleted` 接受 `DISPATCH_INTENT` / `UPSTREAM_ACTIVE`，与 guard 及 attempt 侧一致；旧 SQL 下新用例 `2 errors`（`got 0`），修复后 9/9 |
| P2-13 定价创建维度/版本/409 | **FIXED** | 重复维度任何写入前 400（旧实现 500 → 新 15/15）；organization 行锁后分配版本；`DuplicateKeyException` → 409；维度覆盖校验按审查要求只给建议（activate 时校验 INPUT/OUTPUT）。并发双击仍可能得到 409（可重试契约），未做并发压测 |
| P2-14 路由策略列表 N+1 | **FIXED** | 新增 `findCandidatesByPolicyIds` 一次批量查询 + 内存分组；Mockito 断言批量查询 1 次、单条查询 0 次；真实 MySQL 列表集成测试 1/1 |
| P2-15 僵尸 `RUNNING` 对账 run | **FIXED** | 选择“启动 + 定时收敛”：行锁 + 状态 CAS + `RECONCILIATION_RUN_ABANDONED` + 审计 + 指标；默认 30s 首扫 / 5m 间隔 / 30m 超时；测试默认关闭。新增集成测试先红（方法不存在）后绿 1/1 |
| P3-03 `EXPENSE_RESUBMIT` 死代码 | **FIXED** | 删除常量与 `resubmitRequestHash`；全仓 0 引用 |
| P3-04 `InitialCandidateResult.skipped()` | **FIXED** | 删除方法；`NextCandidateResult.skipped()` 保留 |

**报告更正**：本批未发现审查报告与代码的事实冲突，无需更正。两项处置选择均在审查建议范围内：
P2-10 采用“抽公共段 + 显式标注仅测试入口”；P2-11 采用“放宽 SQL 谓词”，理由为成功证据存在时
`DISPATCH_INTENT` 应能收敛为 `TRANSPORT_COMPLETED`，与 attempt 侧谓词一致。

**未处置**：批次 B 只做上述 7 项。P2-01/02/06/07/08/12/16/17/18/21/22 与 P3-01 留待批次 C
（需要先拍板的方案项）。
