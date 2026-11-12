# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 规范，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

发布基线与验收证据的权威来源见 `PROJECT_CONTEXT.md` 与 `docs/03-acceptance/`；
本文件是面向变更检索的摘要视图。

## [Unreleased]

暂无未发布变更。

## [3.1.1] - 2026-11-12

`main` 稳定标签为 `v3.1.1`；本版本汇总 2026-11-12 深度审查后的全部 32 项维护修复（4 P1 + 22 P2 + 6 P3），
包含安全加固、CI 门禁、Gateway/后端修复、前端契约与展示、OpenAPI 补齐。定价费率响应 number→string、
技术时间统一 UTC、`CACHED_INPUT_TOKEN` 定价创建 400 等契约/展示变化见下方 `Fixed` 条目。

### Added
- 剩余 P2/P3 决策项落地记录（批次 C，`docs/03-acceptance/maintenance/2026-11/2026-11-12-remaining-p2-p3-decisions.md`）：P2-01 调度隔离、P2-02 本地性解析与明文 endpoint、P2-06 dummy bcrypt、P2-07 observed 基线、P2-08 Workbench 同基、P2-12 费率字符串、P2-16 UTC 显示、P2-17 OpenAPI 补齐、P2-18 SSRF 默认开启、P2-21 账期客户端合并、P2-22 下载 revoke、P3-01 CACHED 拦截；含逐项 before/after、红/绿证据、`NOT_VERIFIED` 与影响边界
- `ControlPlaneOpenApiRouteCoverageTest`：反射扫描生产 `@RestController`，断言每条 `/api/v1` 路由存在于 `openapi.yaml` + `m18-v3-openapi.yaml` 契约并集；修复前缺失 14 条，修复后 0
- 剩余 P2/P3 后端修复记录（批次 B，`docs/03-acceptance/maintenance/2026-11/2026-11-12-remaining-p2-p3-backend.md`）：P2-10 授权前置段抽公共、P2-11 成功终结谓词放宽、P2-13 定价维度去重/版本串行化/409、P2-14 路由候选批量查询、P2-15 僵尸对账 run 收敛、P3-03/P3-04 死代码删除；含逐项 before/after、红/绿证据、`NOT_VERIFIED` 与影响边界
- 对账僵尸 run 回收：`ReconciliationRunRecoveryScheduler`（默认启动后 30s 首扫、5m 间隔、30m 超时、100 条/批，可经 `AICOSTOPS_RECONCILIATION_*` 覆盖）与 `ReconciliationRunService.failStaleRunningRuns`；测试默认关闭 sweep
- 剩余 P2/P3 低风险清理记录（批次 A，`docs/03-acceptance/maintenance/2026-11/2026-11-12-remaining-p2-p3-cleanup.md`）：P2-03 dev Compose 回环绑定、P2-04 HC-01 Redis 容器归属校验、P2-05 CodeQL 覆盖 `scripts/`、P2-19 前端依赖声明、P3-02 `percentOf` 正则、P3-05 分摊编辑器金额兜底、P3-06 死代码与幽灵权限码；含逐项 before/after、红/绿证据、`NOT_VERIFIED` 与影响边界
- 导航权限目录一致性测试：`scripts/tests/nav-permission-catalog.test.mjs`，从全部 Flyway 迁移解析 `permission` 种子，断言前端导航的每个 `readPermission` 都存在于 `/permissions` 的数据来源；HC-01 Redis 所有权测试 `scripts/tests/hc01-ownership.test.mjs`
- 维护期 P1 加固与 P2 口径修复记录（`docs/03-acceptance/maintenance/2026-11/2026-11-12-p1-hardening-fixes.md`）：P1-01 生产默认密钥拒绝、P1-02 集成测试选择 fail-closed、P1-03 `ci-status` skip 语义与 CODEOWNERS、P1-04 登出失败本地清理、P2-09 Gateway 指标口径、P2-20 数值 id 排序；含逐项 before/after、本地验证输出、`NOT_VERIFIED` 与影响边界
- `.github/CODEOWNERS`：默认仓库 owner，并对 `.github/**`、`.trivyignore`、`compose*.yaml`、`docker-compose*.yml`、`deploy/`、`.env.example` 显式指定 owner；是否强制 review 取决于仓库 ruleset（`NOT_VERIFIED`）
- CI 集成测试选择器：`scripts/ci/select-integration-tests.mjs`（纯函数枚举 + 分片，空枚举/空分片/非法参数非 0 退出）与 `scripts/tests/ci-select-integration-tests.test.mjs`（6 个用例）
- 深度代码审查记录（`docs/03-acceptance/maintenance/2026-11/2026-11-12-deep-code-review.md`）：`75f02ea` 基线只读精读约 70 个文件 / 约 1.1 万行（Gateway 请求/预占/派发/计量/结算/恢复，Backend 结算/账本/预算/关账/配置校验，前端金额与认证层，CI 与 compose），复核 F-01~F-09 财务不变量全部成立，`npm test` 1516 用例全绿、文档 0 死链；记录 4 项 P1（生产默认密钥校验缺口、CI 集成测试选择可静默为空、CI 门禁可被 skipped 绕过、登出失败不清本地会话）、22 项 P2、6 项 P3，明确本次未修改任何业务代码
- HC-03 harness 资源所有权加固（PR #181 审查）：容器带 `aicostops.hc03.owner/run/resource` label，新增 `run-owner.json` 与 v2 PID 身份记录（owner/命令 token/启动时间）；新一轮、`--attach`、`--cleanup`、stop/kill 均先校验，外部/无 label/旧式 PID 一律拒绝；新增 13 个纯函数测试；Dockerfile 增加 apt `>= 2.8.4ubuntu0.1` 构建期断言，Security 镜像改为 `--pull --no-cache`（不忽略 CVE、不降低 severity）
- HC-03 真实故障注入与恢复验收（Issue #180）：新增 `scripts/perf/hc03/` 一键入口 `npm run perf:hc03`，只操作本任务创建的 `ai-costops-hc03-mysql` / `ai-costops-hc03-redis`，对真实 Redis 停服/黑洞、真实 MySQL 停机、Gateway JVM `taskkill /F`（SIGKILL 等价）、双实例部分可用、Backend 结算事务中断与数据库暂断、真实 HTTP Close 并发进行注入，输出 `environment.json` / `events.jsonl` / `db-invariants.json` / `results.json`；新增 `GatewayDataSourceOutageIntegrationTest` 覆盖 TX1/TX2 数据源故障后的无预留/无派发栅栏与连接池恢复；证据见 `docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-03-failure-recovery/`
- HC-02 账期锁竞争审计与最小优化（Issue #178）：`billing_period` 共享锁（TX1/TX2）+ TX1/TX2 `READ COMMITTED` 当前读，usage finalization 的 `lockLineage` 改为 `FOR UPDATE OF gr, ra`（不再 X 锁共享 `pricing_version` 行），预算行 X 锁、Close 账期 X 锁、财务语义与全部表/索引不变；新增 `PeriodLockSharedConcurrencyIntegrationTest`（共享锁并发、stale-snapshot 不超扣、CLOSING 拒绝）、`GatewayPeriodSharedLockCloseIntegrationTest`（Close 等待共享锁后 CLOSING、Gateway shared reader 观察到 CLOSING）与 usage finalization 的 pricing_version 锁范围回归；审计与设计见 `docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-02-billing-period-lock/lock-boundary-audit.md`
- HC-01 harness 新增 HC-02 A/B 入口：`--suite=hc02` 只跑账期热点矩阵，`--gateway-jar` 指定基线/优化 JAR，`--variant` 与 `--code-sha` 写入产物，避免优化前后 SHA 混淆
- HC-01 Gateway 单/双实例高并发基线 harness（`scripts/perf/hc01/`）：隔离数据库/Redis、真实 Backend/Mock Provider、独立 Gateway JVM、热点/对照预算矩阵、逐轮财务不变量与结算收敛核对；一键入口 `npm run perf:hc01`
- Gateway 阻塞 DB 调度器饱和可观测性：`gateway_db_active` / `gateway_db_queued` / `gateway_db_threads_max` / `gateway_db_queue_capacity`（仅新增指标，不改变调度语义）
- HC-01 Gateway 单/双实例高并发基线验收（`docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-01-gateway-concurrency/`）：5–200 并发 ×3 轮、热点/多预算/多组织/双实例/慢上游/SSE/断流/同键/预算耗尽/正常限流，逐轮财务与 permit 不变量；首要瓶颈为 `billing_period` 行锁 + `gateway_request` 外键共享锁排队；修复预流失败 SSE permit 泄漏；补充限流验证。
- 核心业务真实性与产品一致性验收总报告（`docs/03-acceptance/maintenance/2026-11/2026-11-10-core-business-consistency-acceptance.md`）：链路 A/B 各 8 检查点 PASS、产品一致性审查与修复、前端文案改造截图验收、审计驱动死代码清理
- 业务链路 A（AI Gateway 实时成本治理）端到端真实性验收证据（`docs/03-acceptance/maintenance/2026-11/2026-11-10-gateway-chain-e2e-acceptance.md`）：8 检查点全部 PASS，定向复跑 156 个集成/单元用例（真实 MySQL 8.4 + Redis Testcontainers，模拟上游），未发现缺陷，无代码变更
- 后端核心链路与并发可靠性改造设计文档（`docs/02-development/designs/maintenance/2026-11-10-backend-reliability-refactor-design.md`）：CO-00~CO-05 六个工作包，财务不变量 F-01~F-09、异常矩阵与分批实施计划
- 根级任务编排：根 `package.json` + `scripts/tasks.mjs`（零依赖、跨平台），统一 `dev / build / test / verify / infra:*` 入口
- `scripts/dev/` 补充 `.sh` 版本（`start-infra.sh` / `stop-infra.sh` / `status.sh`），与 `.ps1` 一一对应
- `scripts/README.md`：脚本目录职责说明与里程碑产物归档/命名策略
- 根级 `LICENSE`（专有许可，内部使用）与本 `CHANGELOG.md`
- OpenCode Zen 共享库统一接入（`shared-lib 1.2.0`）与 Zen CLI 协议姿态
- Kilo Gateway 作为第二个内置 Provider 接入
- Cost Intelligence 经审计的成本历史覆盖（`cost_history_coverage` 及 revision 表，Flyway V28）与分析输入身份（`cost_intelligence_run.input_fingerprint`，Flyway V29）
- M21 运维验收补充脚本：`scripts/m21/invoke-v3-operational-financial-smoke.ps1`（财务闭环）与 `scripts/m21/invoke-v3-operational-advisor-smoke.ps1`（Advisor Worker）
- M22 双组织浏览器隔离 E2E（`frontend/e2e/m22-browser-organization-isolation.spec.ts`）与真实 MySQL 隔离夹具
- 根级 `AGENTS.md`：面向 AI 编码代理的项目上下文（结构、不变量、命令、测试约定、Git 与安全红线）
- OpenCode Zen minimax 真实浏览器验收记录（`docs/03-acceptance/maintenance/2026-10/2026-10-22-opencode-zen-minimax-real-browser-acceptance.md`）
- 文档链接自动校验工具：`scripts/dev/verify-doc-links.mjs`，并通过根 `package.json` 提供 `npm run verify:links` 命令
- 业务链路 B（外部账单归集与财务对账）端到端真实性验收记录（`docs/03-acceptance/maintenance/2026-11/2026-11-10-billing-chain-e2e-acceptance.md`）：8/8 检查点 PASS，含三层幂等键数据库核对与定向复跑测试证据
- CO-00 安全与代码基线证据包（`docs/03-acceptance/maintenance/2026-11/2026-11-11-co-00-baseline/`）：分支/SHA/依赖清单、Trivy 与 CodeQL 脱敏结果、`main @ 57696e2` 的 CI 14/14 + Security 3/3 重新验证
- CO-01/CO-02 核心链路可靠性补强验收（`2026-11-11-co-01-co-02-reliability/`）：预占前孤儿恢复、依赖不可用 503、对象不可读失败、导入失败指标与 50 并发同键
- CO-03 Redis 正确性与降级验收（`2026-11-11-co-03-redis/`）：登录限流 Lua 原子化、工作台缓存权限指纹、Gateway Redis 命令超时与职责/故障矩阵
- CO-04 SQL/Worker/性能复验（`2026-11-11-co-04-sql-worker-performance/`）：10k/100k import 各 3 轮、10k reporting 30 次 p50/p95、M8/M9 EXPLAIN ANALYZE；500k reporting 标 `NOT_VERIFIED`
- CO-05 可观测/故障注入验收（`2026-11-11-co-05-observability/`）：关联键映射、traceId/MDC、导入失败与对象存储依赖指标、§8.2 故障矩阵与交付决策

### Changed
- HC-02 账期热点锁优化 A/B：8 个场景各 3 轮，成功 RPS +14%～+206%、P95 −22%～−70%，`billing_period` 锁等待 254.8 ms→1.96 ms、`lockLineage` 56.1 ms→0.31 ms；两轮财务不变量 0 违例、结算完全收敛。单预算仍受按设计保留的预算行 X 锁限制（227.3 ms），后续若继续优化需改预占/可用额度策略。详见 `docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-02-billing-period-lock/`
- HC-01 合并前审查修正：更正 `findings.md` / 验收报告中“为 `budget_reservation` 新增 `(org_id,budget_id,status)` 索引”的错误建议（V19 已存在 `idx_budget_reservation_org_budget`），说明累计 `NO_INDEX_USED` 与末态 `EXPLAIN` 的口径差异，HC-02 改为先在真实规模测量执行计划、回表与覆盖索引收益/写放大；证据见 `docs/03-acceptance/maintenance/2026-11/2026-11-11-hc-01-gateway-concurrency/2026-11-11-hc-01-review-followup.md`
- `CloseBlockerProviderIntegrationTest` 过时测试方法名更正（`registryContainsExactlySevenCanonicalProviders` → `registryContainsExactlyEightCanonicalProviders`，`CloseBlockerCode` 实为 8 项；仅改名，未改断言）
- `CostIntelligenceSchedulerFailureVisibilityIntegrationTest` 与共享上下文的启动后台 sweep 隔离（`@MockitoBean` 薄调度包装器，与服务端测试同款做法），消除同一 SHA 并行运行一绿一红的偶发重复 WARN 断言失败
- 根任务编排新增构建产物保留策略：`.local-dev/builds/` 只保留最近 3 次运行的目录（含本次），任务结束即清理更早的历史目录，避免单次约 200 MB 的构建树无限堆积（本地实测曾累积到 4.0 GB / 43 个目录）
- CI/CD 工作流极致加速与资源治理：
  - 并发控制与自动取消：`.github/workflows/ci.yml` 和 `security.yml` 引入 `concurrency` 机制，PR 连续推送自动取消旧运行以节省配额，`main` 分支保持独立执行保障发布审计
  - 路径变更检测与智能短路：引入 `dorny/paths-filter@v3` 进行模块级变更检测，纯文档变更跳过耗时编译与测试，各作业均正常报告 SUCCESS 以规避必需检查（Required Checks）挂起
  - 集成测试套件分片并行：将 `backend-integration` 198 个集成测试类通过 Matrix 拆分为 2 个并行 Shard；作业使用 `-Dsurefire.skip=true`，但该属性在 Surefire 3.5.6 下不生效（每个分片仍会执行 694 个单测，属已知待优化项），墙钟时间较串行 409 秒显著下降
  - 浏览器端到端测试加速：`browser-e2e` 作业新增 Playwright 浏览器二进制缓存（`~/.cache/ms-playwright`），免去每次 67 秒外网下载
  - 容器镜像构建并行化与缓存加固：`docker-build` 与 `trivy` 中 3 个容器镜像构建由串行改为后台并发构建；Dockerfile 补全 BuildKit `--mount=type=cache`（Maven/npm 缓存）；补齐 `gateway/.dockerignore`
  - 微型作业整合：`backend-unit` / `gateway-unit` 原生整合架构测试（ArchUnit），`frontend-quality` 整合 lint 与 build，减少重复虚拟机冷启动与依赖安装开销
  - CodeQL 语言环境按需加载：按 matrix 语言按需安装 JDK/Maven 与 Node/npm，消除跨语言冗余下载
  - 分支门禁对齐：`Protect main` ruleset 的 Required Checks 更新为重构后的作业名（`backend-integration (shard 0)` / `(shard 1)`、`frontend-quality (lint & build)`、`ci-status`），移除已并入其他作业的旧 context（`backend-architecture`、`frontend-lint`、`frontend-build`），修复 PR 永久 `BLOCKED`
  - `docker-build` 并行镜像构建改为逐 PID 等待并传播失败退出码（裸 `wait` 恒为 0 会吞掉构建失败、让必需检查在镜像损坏时仍通过），与 `security.yml` 同源修复对齐
- 项目文档体系系统性治理：
  - `docs/03-acceptance/` 消除 73 个根目录平铺堆积，完成分层归档（`checklists/`、`milestones/`、`maintenance/2026-10/`、`quality/`）
  - `docs/02-development/` 重构为清晰的 `api/`（统一契约收口）、`designs/`（`v1/`, `v2/`, `maintenance/`）、`guides/`（源码精读）、`implementation/`
  - `docs/04-operations/` 统一收纳生产配置、可观测性指标、灾备恢复与运维 Runbook，消除与 02 开发目录的运维重叠
  - `docs/superpowers/` 增加归档说明 README，明确为 M0~M18 历史工具计划与设计草案封存
- 测试文件与日志资产规范化隔离：
  - 规整 `.local-dev/` 目录结构为 `logs/`（本地运行/诊断日志）与 `probes/`（一次性排查探针脚本），清除根目录散落日志与临时构建 POM 残留
- `AGENTS.md` 文档与资产治理规范强化：
  - 升级第 10 节，建立硬性目录落位准则、测试/日志资产分类矩阵、维护期标准 Markdown 结构模板与 `npm run verify:links` 零死链门禁
- 前端设计体系统一：`styles.css` 建立全局设计令牌（品牌色阶、中性色、圆角、阴影）；antd `ConfigProvider` 主题令牌与之对齐；`v3-tokens.css` / `provider.css` 收敛至同一调色板
- 前端视觉现代化：侧栏胶囊式导航选中态、卡片柔和边框与分层软阴影、登录页焦点态、全局表格数字 `tabular-nums`
- 前端路由级代码分割：`AppRouter` 全部功能页改为 `React.lazy`，入口包由 1,704.89 kB（gzip 517.05 kB）降至 242.33 kB（gzip 76.56 kB）
- `dev` 分支 push 触发 GitHub CI 与 Security（保留 `main`、`pull_request` 触发与每周定时扫描，未弱化门禁）
- 内置 `OPENCODE_ZEN` 推理基址迁移到共享 CLI 推理基址 `https://opencode.ai/inference/v1`；Flyway V30/V31 对 `provider_catalog` 与 ACTIVE `provider_connection_profile` 做显式、版本化端点变更，V24 迁移保持字节不变
- OpenCode Zen 免费层准入统一为 FREE_ONLY，仅准入有证据的免费 Chat 模型
- OpenCode Zen 共享库升级至 `v1.5.4`，对齐 2.x CLI 协议规范（User-Agent `opencode/latest/2.1.4/cli`，40-hex project hash，规范化端点 `/inference/openai/v1/chat/completions` 与键序 `orderedChatBody`），支持基于 loopback Bun relay 的零配置透明路由与 SSRF 安全防线
- README 重写：改为面向新读者的结构（价值主张 → 快速开始 → 架构 → 功能 → 开发 → 文档 → 贡献），移除冻结历史叙述，链接逐条校验可达
- `PROJECT_CONTEXT.md` 精简为当前状态索引，历史里程碑细节移交 `docs/` 与 `CHANGELOG.md`

### Fixed
- Gateway 调度隔离（审查 P2-01）：`spring.task.scheduling.pool.size: 2`，Advisor 单 tick 批量上限默认 10（`AICOSTOPS_GATEWAY_ADVISOR_WORKER_BATCH_LIMIT`）并可禁用（`AICOSTOPS_GATEWAY_ADVISOR_WORKER_ENABLED`），预占回收扫描不再排在长 Provider 调用之后
- 生产配置本地性（审查 P2-02）：`isLocalhost` 改为 URI + `InetAddress` 全地址解析，覆盖 `0.0.0.0`、`127.1`、十进制 IP、IPv4-mapped IPv6 与 DNS 多 A 记录；`allowed-origins` 逐项解析且不误杀 `localhost.example.com`；存储 endpoint 公网强制 HTTPS、内网明文需 `AICOSTOPS_STORAGE_ALLOW_PLAINTEXT_INTERNAL=true`
- 登录计时侧信道（审查 P2-06）：未知邮箱也执行一次固定 cost-10 dummy BCrypt 比对，再返回同一 `AUTH_INVALID_CREDENTIALS`；审计/限流不变
- 成本异常基线（审查 P2-07）：被评估桶与 baseline 只使用 `observed=true` 的桶，观测不足返回空；`latestDelta` 复用 `median` 并过滤缺口日，避免虚假异常
- Workbench 口径（审查 P2-08）：按项目汇总复用 `CHARGE_BASIS`，与 provider / 未分摊同基；已确认分摊后被判 `EXCLUDED_DUPLICATE/NONCOST` 的 charge 不再计入项目合计
- 定价费率契约（审查 P2-12）：`PricingRateResponse.unitPrice` 改为 decimal string（`ToStringSerializer`），前端按 scale-8 精确展示；OpenAPI 响应 schema 同步
- 技术时间显示（审查 P2-16）：全站统一 `UTC` + 显式 `UTC` 标注，`intelligence/format.ts` 复用 `lib/dateTime.ts`，删除重复 formatter
- OpenAPI 契约（审查 P2-17）：新增 14 条 path / 19 个 operation / 21 个 schema（含 `x-permission`），`LedgerSourceType` 补齐 5 值，新增路由覆盖测试，删除重复的 `docs/03-acceptance/m18-openapi.yaml`
- Provider SSRF（审查 P2-18）：OpenAI / MiMo 适配器默认在所有 profile 安装 `PublicOnlyAddressResolverGroup` 并启用内置主机白名单；本地 mock / 内网 Provider 需显式 `AICOSTOPS_GATEWAY_PROVIDER_ALLOW_PRIVATE_ENDPOINTS=true`
- 账期前端客户端（审查 P2-21）：`/billing-periods` 统一使用 `billingPeriodApi` + `billingPeriodKeys.list()`，删除 period-close 的重复客户端/键/类型，关账后失效共享键
- 证据下载（审查 P2-22）：anchor 挂载 body 后 click，`setTimeout(revoke, 0)` 延迟释放 object URL；异常路径也清理 anchor
- 定价维度准入（审查 P3-01）：创建价目表拒绝 `CACHED_INPUT_TOKEN`（400），因为数据面不产出 cached-input、配置后永不结算
- Gateway 成功终结（审查 P2-11）：`markRequestTransportCompleted` 的谓词从只允许 `UPSTREAM_ACTIVE` 放宽为 `DISPATCH_INTENT` / `UPSTREAM_ACTIVE`，与成功终结 guard 及 attempt 侧 `markAttemptCompleted` 一致；避免 `beginUpstream` 未持久化但上游已返回时 0 行更新导致整个成功终结事务（含用量事实）回滚。定向 `GatewayUsageFinalizationIntegrationTest` 9/9
- 仅测试派发入口（审查 P2-10）：`GatewayRequestService` 抽出生产/测试共用的 `authorizePrelude`（身份、模型白名单、打开账期、VALIDATED 请求收敛），`authorizeAndFence` 显式标注 test-only legacy single-candidate path，生产统一走 `GatewayRequestOrchestrator.prepareInitial`
- 定价创建（审查 P2-13）：重复 `dimensionCode` 在任何写入前返回 400；事务内先锁 organization 行再分配 `MAX(version)+1`；`DuplicateKeyException` 映射为 409 `STATE_CONFLICT` 可重试，不再 500；未实现维度覆盖校验（建议在 activate 时补）
- 路由策略列表（审查 P2-14）：`list` 改为一次 `findCandidatesByPolicyIds` 批量查询并在内存分组，消除每行一次 `findCandidates` 的 N+1
- 对账 run 僵尸 `RUNNING`（审查 P2-15）：新增启动 + 定时收敛（行锁 + 状态 CAS + `RECONCILIATION_RUN_ABANDONED` + 审计 + 指标），崩溃进程遗留的活跃 run 不再永久非零；未改 Workbench/close blocker 读侧
- 死代码（审查 P3-03/P3-04）：删除 `ExpenseIdempotency.OPERATION_RESUBMIT` / `resubmitRequestHash` 与 `InitialCandidateResult.skipped()`，全仓确认 0 引用
- dev 基础设施端口暴露（审查 P2-03）：`compose.dev.yaml` 的 MySQL / Redis / MinIO 端口补 `127.0.0.1:` 前缀，与生产 / M16 overlay 对齐，同网段主机不再能直连财务库 / Redis / MinIO 控制台
- HC-01 Redis 容器归属（审查 P2-04）：`scripts/perf/hc01/runner.mjs` 不再无条件 `docker rm -f ai-costops-hc01-redis`；创建时打 `aicostops.hc01.owner/run/resource` label（owner 每次 run 唯一），删除前 `docker inspect` 校验，无 label / 他人 owner / 仅 HC-03 label 一律拒绝并给出人工命令；HC-03 所有权决策仅做向后兼容的 `labelKey`/`harness` 参数扩展
- 前端依赖声明（审查 P2-19）：显式声明 `dayjs@1.11.21`（此前仅靠 antd 传递依赖 hoist），删除零引用 `echarts@6.1.0` 及只被它使用的 `zrender` / `tslib`；同步 `AGENTS.md` / `README.md` 技术栈描述
- 分摊编辑器金额兜底（审查 P3-05）：来源金额渲染期改用 `safeParse`，非 scale-8 金额显示“金额格式异常”且不触发 `RouteErrorBoundary`，确认按钮保持 disabled；不再在 render 期抛错
- 前端死代码与幽灵权限码（审查 P3-06）：删除零引用 `AppPage` 及其专属导入；`AppNavEntry.readPermission` 改为可选，工作台条目移除不存在的 `WORKBENCH_SECTION`，可见性仍由 `hasWorkbenchAccess` 决定
- `percentOf` 正则笔误（审查 P3-02）：`/^0+(?=\\d)/` 改为 `/^0+(?=\d)/`；当前无可观测行为差异（`padStart(scale + 1)` 保证宽度）
- CI 变更检测（2026-11-12）：`dorny/paths-filter` 增加 `base: ${{ github.ref }}`，`dev` push 改为与同一分支上一提交比较，不再把“相对 `main` 的全部历史提交”计入；纯文档 push 重新走 `docs_only` 短路，`pull_request` 行为不变
- 前端测试 teardown（2026-11-12）：Vitest 全局 `afterEach` 增加 20ms 收敛窗口（fake timers 先切回真实计时器），避免 antd `useDelayState` 的 0/10ms 定时器在 jsdom 销毁后触发、以 unhandled `window is not defined` 判整文件失败（CI 43140181777 的 `frontend-test`）
- 生产配置校验（审查 P1-01）：`prod` 校验器此前只要求 JWT ≥ 32 字符、storage 非空，`.env.example` 的 64 字符公开签名密钥与 `aicostops`/`change-me-local-only` 存储凭据可通过启动；现拒绝 `local-development-only-signing-key` 前缀与两个存储默认值（对称、空值仍拒绝），错误信息只报环境变量名；新增 3 个“解析结果等于示例值”用例（先红后绿）
- CI 集成测试选择（审查 P1-02）：删除 workflow 内联枚举与 `-DfailIfNoSpecifiedTests=false`，改由 `scripts/ci/select-integration-tests.mjs` 枚举/分片并显式失败，Maven 使用 `-DfailIfNoTests=true`；当前 199 个 `*IntegrationTest.java` 的分片 100/99 与旧规则逐项一致，空目录/空分片退出码 1。2026-11-12 推送 `dev` 后远程日志确认 `Selected 100 / Selected 99`（[run 43137592677](https://github.com/BangShou1st/AI-CostOps/actions/runs/43137592677)）
- 合并门禁（审查 P1-03，仓库内部分）：`docs_only` 现在同时要求 code 过滤器未命中；`ci-status` 对“过滤器命中却 skipped”与“非 docs-only 且无任何业务区域运行”判失败，`main` push 上强制运行的作业 skip 即失败；新增 `.github/CODEOWNERS` 覆盖 `.github/**`、`.trivyignore`、`compose*.yaml`、`deploy/`、`.env.example`。branch protection / ruleset 仍 `NOT_VERIFIED`。2026-11-12 远程 CI 14/14（`ci-status` 输出 `docs_only=false`）与 Security 3/3 已通过（[CI](https://github.com/BangShou1st/AI-CostOps/actions/runs/43137592677)、[Security](https://github.com/BangShou1st/AI-CostOps/actions/runs/43137592638)）；`dorny/paths-filter` 未设 `base` 时 `dev` push 按 `main` 比较，纯文档 push 仍跑全量（成本问题，非门禁漏洞）
- 登出会话卫生（审查 P1-04）：`logout()` 在服务端注销 5xx 或 refresh 失败时也执行本地清理（保留 httpOnly cookie 供下次 refresh 失效），401 终态路径、refresh 单飞、跨标签协调与 409 语义不变；调用点 catch 并提示“已本地登出”，消除 unhandled rejection
- Gateway 可观测口径（审查 P2-09）：流式请求在终端 `[DONE]` 下发后补记 `gateway_request_total{outcome="COMPLETED"}`（此前仅非流式成功计数），`SAFE_RELEASED_TERMINAL` 加入 bounded 预占恢复 outcome，不再落 `UNKNOWN`
- 列表排序（审查 P2-20）：网关凭证、定价版本、路由策略三处“最新在前”不再用 `localeCompare` 比较数字字符串 id，改用 `BigInt` 数值降序（`['9','10','100']` → `100,10,9`），未改后端分页顺序
- Gateway SSE permit：建流前 `rateLimiter` / `quotaLimiter` / `orchestrator.prepareInitial` 未完成时客户端取消订阅只会触发 `doFinally(CANCEL)`，原实现在该路径泄漏 stream permit；新增 `StreamPermitLease` 三态所有权（外层 admission 链 → SSE body → released）显式移交并保证恰好一次释放，补三阶段取消回归，B04 流建立后外层完成不得提前释放的用例保持通过
- 工作台缓存：缓存键加入调用者 ORG 权限集合的 SHA-256 指纹，修复同组织低权限成员命中高权限成员预热视图的跨权限读泄露
- Gateway Redis 可靠性：`spring.data.redis.timeout=500ms` / `connect-timeout=1s`，Redis 已连接但慢响应/黑洞时在 Provider I/O 前有界 fail-closed；新增 blackhole 超时测试
- 可观测性：`TraceIdFilter` 写入/清理 MDC 并校验入站 `X-Trace-Id`；导入失败最终化后发 `import.completed{result=FAILED}`（修复告警死链）；对象存储 STAT/UPLOAD 失败发 `dependency.error{OBJECT_STORAGE}`；Gateway Provider 标签补齐 `KILO_GATEWAY` / `OPENCODE_ZEN` / `CUSTOM_OPENAI_COMPATIBLE`
- 关账校验异常消息从“exactly seven results”改为按 `CloseBlockerCode.values().length` 动态生成（当前 8）
- Gateway 预占前孤儿请求恢复：`VALIDATED`/`RESERVED`/`DISPATCH_INTENT`/`UPSTREAM_ACTIVE` 下，所有 attempt 为 `PLANNED`/`SAFE_NO_BILLABLE_EXECUTION` 且至少一个 `PLANNED`、无有效预算预占的请求，此前既不被过期预占扫描发现（没有预占），也不被 SAFE attempt 扫描发现；新增超过 `reservation-ttl` 的有界扫描，将其收敛为 `FAILED_PRE_DISPATCH`，不触碰资金与 Provider I/O，且年龄阈值避免误伤在途请求
- IAM 登录限流：`RedisRateLimiter` 的 `INCR` + `EXPIRE` 改为 Lua 原子脚本，消除进程崩溃在两条命令之间留下无 TTL 计数键的窗口；已有无 TTL 键在下一次登录时自愈，且不会延长进行中的固定窗口
- 对账整体调整弹窗：分配目标搜索框补 `optionFilterProp="label"`（antd 默认按 value 即数字 id 过滤，按名称搜索此前永远无结果）；`m15-hybrid-reconciliation.spec.ts` 分配目标选择改为先输入搜索词再选项，消除复用数据库上虚拟滚动导致的选项不可见（未弱化断言）
- 集成测试断言：`ImportWorkflowReadApiIntegrationTest` 的租户隔离排除检查从整个响应体的裸子串匹配收敛为 `items[*].id` 字段级排除，消除共享 Testcontainer 的 AUTO_INCREMENT 漂移导致的 ID 数值碰撞假阳性；断言意图与覆盖范围不变
- 前端镜像基线：`frontend/Dockerfile` 基础镜像由 `nginx:1.30.4-alpine` 升级为 `nginx:1.30.6-alpine`（实装 `nginx=1.30.6-r1`），消除 CVE-2026-15176 / CVE-2026-93479 / CVE-2026-97205 三项 HIGH；不新增 `.trivyignore`、不调整 Trivy 阈值
- 仓库忽略规则：新增 `.trivy-cache/`（CI 从仓库根挂载、本地扫描亦会生成的 Trivy 漏洞库缓存目录），避免 1.6 GB 缓存被误提交
- 结算 Worker：组织扫描改为带环绕的 keyset 轮询以实现跨轮公平覆盖，并按异常类型分级隔离单组织失败；处理首次组织扫描的 datasource 故障
- Cost Intelligence：预测要求可观测历史、拒绝反转主导的历史窗口、接受负 actual 并收紧预测窗口；调度分页就地重试并移除不安全的探针恢复，改用按组织与按页的 keyset 扫描
- OpenCode Zen / Kilo Gateway：关闭 M-2 清单漂移、修正版本端点并校验 SSE 完成、修复共享免费准入与 keyless 派发；修复 Kilo Gateway provider review 发现的三项缺陷
- OpenCode Zen 与 Gateway 流式聚合：修复 Gateway `AbstractOpenAiCompatibleAdapter` 在 SSE chunk 同时包含 choices 与 usage 时吞没 delta content 的流式反序列化缺陷；补齐 ModelProbe 与 Gateway 的 Relay loopback 白名单机制与指纹校验；对齐上游已废弃模型（410 Gone）并选用同系列免费模型 `deepseek-v4.1-flash-free` 达成真实上游 HTTP 200 验证
- 组织隔离安全修复：Provider 私有模型按组织作用域收敛；Routing Options 不再返回外组织私有 Provider Model；Pricing Version 对外组织不可见模型与不存在 ID 返回同一 `RESOURCE_NOT_FOUND`，关闭跨组织模型存在性探针
- M21 运维：Gateway 锁定读在 MySQL 8.4 下需数据库级 `LOCK TABLES`（不授予 `budget`/`billing_period` 的 `UPDATE`/`DELETE`）；补齐 Advisor Worker 的 `advisor_inference_job`/`advisor_inference_attempt`/`advisor_explanation`/`advisor_profile`/`advisor_evidence_snapshot` 表级最小权限
- 网关测试夹具：将 id 派生的种子固定到单连接，消除间歇 403
- 开发脚本：避免 Windows 下创建游离控制台，同时保留进程树清理
- 前端样式死代码：`.app-shell-collapsed` 选择器与真实 DOM class `settings-shell-collapsed` 不匹配，折叠侧栏的品牌居中样式此前从未生效
- 集成测试隔离：`LoginServiceIntegrationTest` / `LoginApiIntegrationTest` / `PasswordResetApiIntegrationTest` / `RefreshAndLogoutApiIntegrationTest` 补 `@AfterEach` 清理，不再把 `app_user` + `user_credential` + `audit_event` 残留泄漏给下一个测试类
- 集成测试隔离：`AuthorizationContextServiceIntegrationTest`、`ScopedBudgetRiskIntegrationTest`、`RecommendationAppliedLinkageIntegrationTest`、`SavingsRecommendationCommandAtomicityIntegrationTest` 改用 FK-safe 的 `M2DatabaseCleaner`，不再依赖"恰好排在污染源之前"的执行顺序
- 集成测试隔离：`M2DatabaseCleaner` 补齐 V28 新增的 `cost_history_coverage` / `cost_history_coverage_revision`（两者都引用 `app_user` / `organization`）
- 前端测试竞态：修复 `ImportWorkflow.test.tsx` 中的异步状态断言，避免高并发测试下的时序竞争假阴性
- 安全依赖：`follow-redirects` 升级 1.16.0 → 1.16.1，关闭 CVE-2026-111755 / CVE-2026-111756；随后 pin 到 1.16.2，关闭 CVE-2026-116689（均为 Trivy HIGH 阻断）

### Security
- CodeQL 覆盖 `scripts/`（审查 P2-05）：`.github/codeql/codeql-config.yml` 的 `paths` 追加 `scripts`，本机高权限执行的 Node 脚本（`child_process`、递归删除、Docker 控制）进入 SAST；未新增 `paths-ignore`、未改 workflow 权限
- 生产默认凭据拒绝（审查 P1-01）：JWT 签名密钥与对象存储 access/secret 不再接受仓库内公开的
  `.env.example` / `application-local.yml` 示例值，避免“有仓库读权限即可离线签发 HS256 token 或访问 Evidence 桶”；
  校验器仍只在 `prod` profile 生效，错误信息不回声任何密钥值
- M22 双组织浏览器隔离与模型可见性边界修复（跨租户私有模型元数据泄露与未授权定价关联写入）

## [3.0.0] - 2026-09-13

V3「Model Provider Hub & Cost Intelligence」稳定版，当前 Stable。项目进入 MAINTENANCE_ONLY。

- M17 V3 设计冻结（#154）
- M18 后端完成并冻结 API 契约（`docs/03-acceptance/m18-openapi.yaml`）
- M19 V3 前端体验完成
- M20 V3 生产验收完成（本标签）
- M21 发布后运维就绪验证（2026-09-15, #162）
- M22 以"已记录的非阻塞限制"关闭（2026-09-22, #164）；已知发布阻断缺陷：无

## [2.0.0] - 2026-09-10

V2「Gateway 实时计量与路由」稳定版。

- M10 V2 详细设计冻结
- M11–M15 Gateway 边界证据、身份/预算预占、计量、结算、多 Provider 路由弹性、混合对账
- M16 V2 生产验收完成（本标签，#152），含浏览器 UAT 运行手册与证据矩阵

## [1.1.0] - 2026-09-02

- M9 生产基础收尾：备份/恢复证据、可观测性证据、安全 CI 证据、规模证据、Provider 认证（MiMo）、浏览器 E2E 证据（#127）
- 标记 v1.1.0 为当前稳定版（#128）

## [1.0.1] - 2026-08-25

### Fixed
- 后端：加固 Import lease recovery 在 MySQL deadlock race 下的 bounded retry（#103）。不改变 API、Schema 或 V1 产品范围

## [1.0.0] - 2026-08-24

V1 首个稳定版：多 Provider AI 成本统一为可信、可归属、可审批、可对账、可关账的内部成本账。

- 范围：IAM/组织、证据、导入、标准成本事实、重复审查、归属/分摊、报销/审批、账期/预算/承诺、不可变账本/更正、对账、关账/重开、审计、工作台/报表
- AIC-001 ~ AIC-073 全部完成；AIC-073 最终人工验收 / 发布签署：ACCEPTED

[Unreleased]: https://github.com/BangShou1st/AI-CostOps/compare/v3.0.0...HEAD
[3.0.0]: https://github.com/BangShou1st/AI-CostOps/releases/tag/v3.0.0
[2.0.0]: https://github.com/BangShou1st/AI-CostOps/releases/tag/v2.0.0
[1.1.0]: https://github.com/BangShou1st/AI-CostOps/releases/tag/v1.1.0
[1.0.1]: https://github.com/BangShou1st/AI-CostOps/releases/tag/v1.0.1
[1.0.0]: https://github.com/BangShou1st/AI-CostOps/releases/tag/v1.0.0

