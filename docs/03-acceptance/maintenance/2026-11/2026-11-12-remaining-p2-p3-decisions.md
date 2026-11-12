# 2026-11-12 剩余 P2/P3 决策项落地（批次 C）

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-12 |
| 责任人 | AI（DeepSeek Harness；中心 agent + 5 个并行子代理） |
| 关联模块 | Gateway 调度与 Provider 传输、Backend 生产配置/登录/成本智能/Workbench/定价/OpenAPI、Frontend 时间显示/账期缓存/证据下载 |
| 缺陷来源 | [2026-11-12 深度代码审查](2026-11-12-deep-code-review.md) P2-01 / P2-02 / P2-06 / P2-07 / P2-08 / P2-12 / P2-16 / P2-17 / P2-18 / P2-21 / P2-22 / P3-01 |
| 代码基线 | `dev` = `34fea3f`（`main` = `39560cc`） |
| 关联分支 | `fix/c-gateway-scheduling`、`fix/c-security-config`、`fix/c-analytics-login`、`fix/c-contract-pricing`、`fix/c-frontend-display` → 集成 `fix/remaining-c-decisions` |
| 关联 PR | [#188](https://github.com/BangShou1st/AI-CostOps/pull/188) |
| 变更性质 | 维护期决策项落地；含**有意的契约/展示/错误语义变化**：定价费率响应 number→string、技术时间统一 UTC、`CACHED_INPUT_TOKEN` 配置返回 400；无 Flyway、无财务语义变更 |
| 总体判定 | **PASS（本地 + 远程 CI / Security）**；§3 的环境/外部消费方项仍为 `NOT_VERIFIED` |

本批处理审查报告剩余的全部 12 项（P2-01/02/06/07/08/12/16/17/18/21/22、P3-01），每项均按 2026-11-12 确认的推荐方案执行。

---

## 1. 变更概述（before → after）

### 1.1 P2-01 调度线程隔离

- **Before**：Gateway 默认 1 个 `TaskScheduler` 线程；`AdvisorInferenceWorker.tick()` 在调度线程内无上限循环 claim + Provider 调用（header timeout 可达 60s），`ReservationRecoveryScheduler` 的预占过期扫描被推迟；worker 无禁用开关。
- **After**：`spring.task.scheduling.pool.size: 2`；Advisor 单 tick 批量上限 `aicostops.gateway.advisor-worker-batch-limit`（默认 10，`AICOSTOPS_GATEWAY_ADVISOR_WORKER_BATCH_LIMIT`）；新增 `advisor-worker-enabled`（默认 true，`AICOSTOPS_GATEWAY_ADVISOR_WORKER_ENABLED`）开关。每个 claim 立即执行，达到上限退出本轮，不留下悬挂租约。
- **根因**：Advisor 与预占回收共享唯一调度线程，且 tick 无批量边界。
- **证据**：新增批量上限用例先红（3 条候选、limit=2，旧实现 claim 4 次；`13 run / 1 failure`），后绿 `13/13`；Gateway 单元 `214 run / 0 failed / 1 skipped`。

### 1.2 P2-02 本地性校验与明文 endpoint

- **Before**：`ProductionConfigurationValidator.isLocalhost` 对整串做 `contains("localhost"|"127.0.0.1"|"[::1]")`。`http://0.0.0.0:9000`、`http://127.1:9000`、`http://2130706433:9000`、`http://[::ffff:127.0.0.1]:9000` 均可绕过；同一 helper 还会误杀 `https://localhost.example.com`。`compose.yaml` 把存储 endpoint 写死 `http://minio:9000`，生产 profile 下无明文策略。
- **After**：
  - 用 `URI` 解析 host，再用 `InetAddress` 解析全部地址：loopback / any-local / link-local → 本地；RFC1918、IPv6 ULA、CGNAT 等 → 内网；任一解析结果为本地即拒绝；IPv4-mapped IPv6 显式解包。
  - `allowed-origins` 按逗号逐项解析，拒绝空项、非 http/https、无 host、本地地址；域名中带 `localhost` 字样不再误杀。
  - 存储 endpoint：必须带 host 的 absolute http/https；`https` + 非本地通过；`http` + 内网仅当 `AICOSTOPS_STORAGE_ALLOW_PLAINTEXT_INTERNAL=true` 通过；`http` + 公网一律拒绝；本地一律拒绝；解析失败 fail-closed。
  - `compose.yaml` 对内部 MinIO 显式 `AICOSTOPS_STORAGE_ALLOW_PLAINTEXT_INTERNAL: "true"`；`application.yml` 默认 false；`.env.example` 与运维配置文档补充说明。
- **根因**：以字符串子串代替 URI/DNS 解析，且“内网明文”没有显式例外机制。
- **证据**：`ProductionConfigurationValidatorTest` 先红 `32 run / 10 failures`（9 个应拒绝未拒绝 + 1 个误杀），后绿 `32/32`。`http://127.1:9000` 被 JDK `URI.getHost()` 判为无 host，因此走“必须带 host”的拒绝路径，结果仍是拒绝（见 §3）。

### 1.3 P2-06 登录未知邮箱短路 bcrypt

- **Before**：`LoginService` 的 `identity == null || !passwordEncoder.matches(...)` 在未知邮箱时不执行 bcrypt，形成用户枚举计时侧信道。
- **After**：预置固定 cost-10 dummy BCrypt hash；未知邮箱也执行一次 `matches`，再抛同一 `AUTH_INVALID_CREDENTIALS`。审计、限流、错误码不变。
- **根因**：短路求值把“身份不存在”与“密码错误”拆成了两条耗时不同的路径。
- **证据**：新增 `unknownEmailStillRunsOneFullPasswordVerification`，临时回退旧实现时 `Wanted 2 times` 失败（未知邮箱 0 次 bcrypt）；修复后 `LoginServiceIntegrationTest` 6/6。

### 1.4 P2-07 异常基线把“未观察日”当零成本

- **Before**：`completeSeries` 把缺失日补 `(0, observed=false)`；`CostAnomalyEngine.detect` 只读 amount、从不读 `observed`；`latestDelta` 用 `history.get(size/2)`，偶数样本取上中位数，与 `median()` 口径不一致。
- **After**：被评估桶必须 `observed=true`，否则返回空；baseline 只取 `observed=true` 的桶，过滤后不足 14 个返回空；`latestDelta` 复用 `CostAnomalyEngine.median`，并同步过滤未观察日、要求被评估桶已观察。z-score / deltaPercent / materiality 阈值与异常结构不变。
- **根因**：缺口日与“真零消费”被压成同一 `(0,false)`，median/MAD 被缺口拉低，z 分数虚高。
- **证据**：新增/调整 engine 与 latestDelta 用例，临时回退旧实现 `17 run / 6 failures`；修复后 `17/17`；`CostIntelligenceAnalysisPipelineIntegrationTest` 6/6 确认正常异常仍落库。

### 1.5 P2-08 Workbench provider / project 口径不同基

- **Before**：`CHARGE_BASIS` 要求 import CONFIRMED + `review_status='CLEAN'`；按项目汇总只要求 allocation CONFIRMED。已确认分摊后被判 `EXCLUDED_DUPLICATE/NONCOST` 的 charge 会让“provider 合计 ≠ project 合计 + 未分摊”。
- **After**：按项目汇总复用 `CHARGE_BASIS`（同一基），保留 allocation CONFIRMED 作为附加条件；provider 侧与“未分摊”查询不改。
- **根因**：两个汇总各自维护了一套过滤条件。
- **证据**：新增 Workbench 集成测试（真实 MySQL）构造 CLEAN 已分摊 10.00 + CLEAN 未分摊 5.00 + 已确认分摊但 `EXCLUDED_DUPLICATE` 的 20.00；旧实现 project=30.00（含被排除的 20.00），修复后 provider=15.00、project=10.00、unallocated=5.00，恒等式成立；`WorkbenchIntegrationTest` 8/8。

### 1.6 P2-12 定价 `unitPrice` 以 JSON number 出线

- **Before**：`PricingRateResponse.unitPrice` 是 `BigDecimal` 且无 `ToStringSerializer`；前端声明 `unitPrice: number`，`String(rate.unitPrice)` 后走 2 位定点格式化。单价 <0.005 显示 `0.00`，<1e-6 产生指数记法 → 显示 `—`。
- **After**：DTO 加 `@JsonSerialize(using = ToStringSerializer.class)`，响应为 scale-8 decimal string；前端 `unitPrice: string`，改用 `lib/money.formatMoney` 精确展示；OpenAPI 响应 schema 改 `string` + `^-?[0-9]+\.[0-9]{8}$`，输入仍为 number。
- **根因**：唯一把金额声明成 `number` 的响应契约点，违反“金额全程 decimal string”。
- **证据**：集成断言先红（`Expected a string at JSON path "$.rates[0].unitPrice" but found: 30.0`），后绿 `ModelPricingApiIntegrationTest` 16/16；前端新增 `0.00000001 → 0.00000001 USD` 用例，全量前端 560 用例通过。
- **有意的展示变化**：定价页费率从 `$30.00` 变为 `30.00 USD`（`lib/money.formatMoney` 使用币种代码而非符号），以换取微小金额的精确展示。

### 1.7 P2-16 两套时区口径

- **Before**：`lib/dateTime.ts` 固定 `Asia/Shanghai` 且输出无时区后缀；`intelligence/format.ts` 默认 `UTC` + `timeZoneName:'short'`；同一 Instant 在不同页面相差 8 小时且无法分辨本地/UTC。
- **After**：唯一技术时间 formatter 统一为 **UTC + 显式 `UTC` 标注**（`YYYY-MM-DD HH:mm UTC`）；`format.ts` 复用 `formatUtcDateTime` / `formatUtcDay`，删除重复 `Intl.DateTimeFormat`；业务日期保持无时区。`formatEventDateTime` 保留为同一实现别名。
- **根因**：两套 formatter 并行存在，且上海组不带时区后缀。
- **证据**：`dateTime.test.ts` / `format.test.ts` 及受影响页面测试先红（`expected '2026-08-19 18:14' to be '2026-08-19 10:14 UTC'`），后绿；全量前端 560 用例通过。
- **边界**：`formatEventDateTime` 对无法解析的值现在返回 `—`（原返回原始字符串）；无页面明确需要本地时间展示。

### 1.8 P2-17 OpenAPI 契约补齐

- **Before**：`LedgerSourceType` 有 5 值，`openapi.yaml` 仅 3 值；`/pricing-versions`、`/model-catalog`、`/provider-models`、`/routing-policies`、`/routing-options`、`/gateway-credentials`、`/service-identities` 不在三份 OpenAPI；`docs/03-acceptance/m18-openapi.yaml` 与 `docs/02-development/api/m18-v3-openapi.yaml` SHA-256 完全相同。
- **After**：
  - `openapi.yaml` 新增 **14 条 path / 19 个 operation**（全部带真实 `x-permission`：`PROVIDER_ACCOUNT_READ` / `PROVIDER_ACCOUNT_MANAGE`）与 **21 个 schema**；`PricingDimensionInputCode` 排除 `CACHED_INPUT_TOKEN`，响应枚举保留历史兼容。
  - `LedgerSourceType` 补齐为 5 值：`PROVIDER_CHARGE, EXPENSE_CLAIM, CORRECTION, GATEWAY_SETTLEMENT, RECONCILIATION_ADJUSTMENT`。
  - 新增 `ControlPlaneOpenApiRouteCoverageTest`：反射扫描生产 `@RestController`，规范化 `/api/v1` 前缀与 `{param}`，断言每条路由存在于契约；修复前缺失 **14 条**，修复后 **0**。
  - 删除重复副本 `docs/03-acceptance/m18-openapi.yaml`；`M18OpenApiContractTest` 改读 `docs/02-development/api/m18-v3-openapi.yaml`；同步 `M1OpenApiContractTest` 的 approved operations 与 `docs/02-development/api/README.md`。
- **根因**：契约按里程碑分批维护，新增控制面端点未回填；复制副本没有单一真相源。
- **证据**：契约覆盖测试先红（14 条缺失），后绿 0；后端单元 689/0/2（含 M1/M6/M18 契约测试）；`npm run verify:links` 0 死链。
- **偏差**：覆盖测试断言的是 `openapi.yaml` + `m18-v3-openapi.yaml` 的**并集**，而不是仅 `openapi.yaml`。原因是 Provider Hub / Cost Intelligence / Advisor 的 29 条路由已由冻结的 `m18-v3-openapi.yaml` 发布，再复制进 `openapi.yaml` 正是 P2-17 要消除的漂移。

### 1.9 P2-18 SSRF 守卫默认开启

- **Before**：OpenAI / MiMo 适配器仅在 `prod` profile 启用 `PublicOnlyAddressResolverGroup` 与内置主机白名单；`compose.yaml` / `compose.m16.yaml` 默认空 profile，DNS rebinding 无第二道防线。
- **After**：两个适配器默认在**所有 profile** 安装公网解析 resolver，并启用内置 Provider 的 HTTPS/主机白名单；仅 `AICOSTOPS_GATEWAY_PROVIDER_ALLOW_PRIVATE_ENDPOINTS=true` 同时放宽两者。测试默认配置、HC-01 / HC-03 harness、`compose.m16.yaml` 的 mock/内网 Provider 显式 opt-in；`.env.example` 说明。
- **根因**：安全开关以 profile 而不是显式配置为门控，默认部署（空 profile）没有传输层复验。
- **证据**：适配器 resolver 测试在旧实现下 `8 run / 2 failures`（默认不拒绝 loopback），修复后 19 个定向测试全绿；Gateway 单元 214/0/1；`GatewaySafeFailoverIntegrationTest` 4/4（mock 上游在 opt-in 下正常）。

### 1.10 P2-21 账期双客户端合并

- **Before**：`billingPeriodApi` 缓存键 `['billing-period','list']`；`periodCloseKeys.periods()` 是 `['period-close','periods']`；两者都 GET `/billing-periods`，类型各一份；关账后只失效 period-close 键。
- **After**：以 `features/budgets/api/billingPeriodApi.ts` 的客户端与 `billingPeriodKeys.list()` 为唯一入口；删除 `periodCloseApi.listBillingPeriods`、`periodCloseKeys.periods` 与重复类型；`PeriodClosePage`、`ReconciliationPage`、`ReconciliationCaseDetailPage` 统一使用共享客户端/键，关账/重开后失效共享键。
- **根因**：两个功能各自封装了同一个端点。
- **证据**：新增测试先红（共享键下无数据、关账后只请求 1 次），后绿；全库无 `listBillingPeriods` / `periodCloseKeys.periods` / `['period-close','periods']` 残留；前端 560 用例通过。

### 1.11 P2-22 证据下载同帧 revoke

- **Before**：`ExpenseEvidenceSection` 与 `EvidenceDetailPage` 在 `anchor.click()` 后同帧 `URL.revokeObjectURL(url)`；后者未把 anchor 挂到 body。
- **After**：两处统一为创建 URL → anchor → `appendChild` → `click()` → `removeChild` → `setTimeout(revoke, 0)`；嵌套 `try/finally` 保证异常路径也移除 anchor 并调度 revoke。
- **根因**：假设 click 同帧下载管理器已接管 URL，Firefox/Safari 可能得到空文件/中断。
- **证据**：Evidence 测试先红（`anchorAttachedAtClick=false`），Expense 测试先红（revoke 在 click 同帧同步调用）；修复后两处下载测试通过，断言 anchor 已挂载/移除、revoke 延迟到下一 macrotask。

### 1.12 P3-01 `CACHED_INPUT_TOKEN` 拦截

- **Before**：`ModelPricingService` 白名单允许 `CACHED_INPUT_TOKEN`，但 Gateway 数据面 `cachedInputTokens` 恒 null；配置后该模型用量永远 `INCOMPLETE`，永不 FINAL/Settlement。
- **After**：定价创建时拒绝 `CACHED_INPUT_TOKEN`（400 `VALIDATION_FAILED`），错误信息说明数据面不产出该维度、配置后无法结算；保留枚举与历史数据可读性。
- **根因**：定价准入白名单包含了数据面尚不支持的可选维度。
- **证据**：service 单测先红（`3 run / 1 failure`，未抛 `DomainException`），集成测试先红（`expected 400 but was 201`），修复后单测 3/3、`ModelPricingApiIntegrationTest` 16/16。

---

## 2. 验证环境与执行命令

环境（2026-11-12）：Windows 11 + Git Bash / PowerShell 7，Node `v24.14.0`，Java `21.0.10`，Docker Desktop 引擎就绪。

| # | 命令 | 结果 |
|---|---|---|
| 1 | `cd backend; .\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test` | `689 run / 0 failed / 2 skipped`，BUILD SUCCESS |
| 2 | `cd backend; .\mvnw.cmd -B "-Dgroups=architecture" test` | `36 run / 0 failed / 0 skipped`，BUILD SUCCESS |
| 3 | `cd gateway; .\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test` | `214 run / 0 failed / 1 skipped`，BUILD SUCCESS |
| 4 | `cd backend; .\mvnw.cmd -B verify "-Dit.test=ModelPricingApiIntegrationTest,WorkbenchIntegrationTest,LoginServiceIntegrationTest,CostIntelligenceAnalysisPipelineIntegrationTest" "-DfailIfNoTests=true"` | `36 run / 0 failed / 0 skipped`，BUILD SUCCESS |
| 5 | `cd gateway; .\mvnw.cmd -B verify "-Dtest=GatewayApplicationTest" "-Dit.test=GatewaySafeFailoverIntegrationTest" "-DfailIfNoTests=true"` | `4 run / 0 failed / 0 skipped`，BUILD SUCCESS |
| 6 | `cd frontend; npm run lint` | 通过，0 问题 |
| 7 | `cd frontend; npm test -- --run` | `73 files / 560 tests passed` |
| 8 | `cd frontend; npm run build` | 通过（`tsc -b && vite build`，`built in 560ms`） |
| 9 | `npm run test:scripts` | `54 passed / 0 failed` |
| 10 | `docker compose --env-file .env.example -f compose.yaml -f compose.dev.yaml config --format json` | 解析成功；backend `AICOSTOPS_STORAGE_ALLOW_PLAINTEXT_INTERNAL="true"`、`AICOSTOPS_STORAGE_ENDPOINT="http://minio:9000"` |
| 11 | `npm run verify:links` | 见 §2.1 |

### 2.1 文档链接

```text
$ npm run verify:links
[verify-doc-links] Found 250 Markdown files to inspect.
[verify-doc-links] Checked 427 links.
[verify-doc-links] SUCCESS: 0 broken links found! All local document links are valid.
```

### 2.2 定向红/绿证据汇总

| 项 | 红（实现前） | 绿（实现后） |
|---|---|---|
| P2-01 Advisor 批量上限 | `13 run / 1 failure`（旧实现 claim 4 次，期望 2 次） | `13/13` |
| P2-02 本地性校验 | `32 run / 10 failures`（9 个绕过 + 1 个误杀） | `32/32` |
| P2-06 未知邮箱 bcrypt | 未知邮箱 `matches` 0 次（`Wanted 2 times`） | `LoginServiceIntegrationTest` 6/6 |
| P2-07 observed 基线 | `17 run / 6 failures` | `17/17` + pipeline 6/6 |
| P2-08 Workbench 恒等式 | project total 30.00（应 10.00） | `WorkbenchIntegrationTest` 8/8 |
| P2-12 费率字符串 | `Expected a string ... but found: 30.0` | `ModelPricingApiIntegrationTest` 16/16 |
| P2-16 UTC 显示 | `expected '2026-08-19 18:14' to be '2026-08-19 10:14 UTC'` | 前端全量 560/560 |
| P2-17 契约覆盖 | 缺失 14 条路由 | 缺失 0 |
| P2-18 默认 resolver | `8 run / 2 failures`（默认不拒绝 loopback） | 19 个定向全绿 |
| P2-21 账期共享键 | 共享键下无数据、关账后只请求 1 次 | 定向测试通过 |
| P2-22 下载 revoke | anchor 未挂载 / revoke 同帧 | 两处下载测试通过 |
| P3-01 CACHED 维度 | 单测 1 failure、集成 201 | 单测 3/3、集成 16/16 |

### 2.3 CI 首轮失败与修复（frontend-test）

PR #188 首轮 CI 的 `frontend-test` 在 73 个文件 / 560 个用例全部通过后仍判失败：Vitest 捕获到
`GatewayCredentialsPage.test.tsx` 结束后的一条未处理错误
`ReferenceError: window is not defined`（React 19 scheduler 的 `setImmediate` 任务在 jsdom teardown 后执行）。

- **根因**：吊销用例只 `await` 了 API 调用，没有 `await` mutation 失效后的列表重取与重新渲染；
  React 更新任务在测试结束后仍在飞。
- **修复**：
  - 测试把第二次 `listGatewayCredentials` 返回设置为 `REVOKED` 行，并 `await screen.findByText('已吊销')`，
    确保重取与渲染都完成；
  - 全局 `src/test/setup.ts` 在原 20ms 收敛窗口后再 flush 一次 `setImmediate`（带类型安全的环境检测），
    防止 React scheduler 任务越界。
- **证据**：目标文件连续 5 次 `3 passed`；全量 `73 files / 560 tests passed`，无 unhandled error；
  `npm run lint` 与 `npm run build` 通过。

同一轮 CI 的 `gateway-integration` 还出现一次 `GatewaySafeFailoverIntegrationTest.safeDnsFailure...`
超时：P2-18 默认启用的公网 resolver 会在连接前做真实 DNS 解析，`.invalid` 在 CI DNS 下超过
WebTestClient 默认 5s。仅该用例的客户端响应预算放宽到 20s（failover/预算/用量断言不变），
本地复跑 4/4 通过。

### 2.4 远程验证（PR #188，2026-11-12）

| 工作流 | run | 结果 |
|---|---|---|
| CI | [43172440487](https://github.com/BangShou1st/AI-CostOps/actions/runs/43172440487) | 18/18 success：`detect-changes`、`backend-unit`、`backend-integration` 两片（5m44s / 10m55s）、`gateway-unit`、`gateway-integration`（2m21s）、`frontend-quality`、`frontend-test`（2m4s）、`scripts-unit` 两平台、`scripts-lifecycle`、`docker-build`、`browser-e2e`、`ci-status` |
| Security | [43172440485](https://github.com/BangShou1st/AI-CostOps/actions/runs/43172440485) | `trivy` + `codeql (java-kotlin)` + `codeql (javascript-typescript)` 3/3 success |
| 合并 | PR [#188](https://github.com/BangShou1st/AI-CostOps/pull/188) | squash 为 `d81b48a`；仓库自动删除 head 分支 `fix/remaining-c-decisions` |

CI 共三轮：第一轮 `frontend-test` 因 React scheduler teardown 未处理错误失败；第二轮 `gateway-integration`
因真实 DNS 延迟超过 5s 客户端预算失败；两处修复后第三轮 18/18 全绿。合并后本地清理：五条子分支、
集成分支与五个临时 worktree 全部移除；本地与远程最终只保留 `dev`、`main`。

---

## 3. `NOT_VERIFIED` 与已知边界

| 项 | 状态 | 说明 |
|---|---|---|
| P2-01 真实负载下的调度交错 | `NOT_VERIFIED` | 单元测试验证批量上限；未在真实 Advisor Provider 调用下测量 recovery 触发延迟 |
| P2-02 生产 DNS/URI 实际解析 | `NOT_VERIFIED` | 测试注入 `HostResolver`；`http://127.1:9000` 由 `URI.getHost()==null` 走“必须带 host”路径拒绝，未走地址解析 |
| P2-07 业务接受度 | 决策已定 | 观测不足时“不出异常”而非“假异常”；稀疏组织可能长期无异常结果 |
| P2-12 外部消费方 | `NOT_VERIFIED` | `unitPrice` 响应 number→string；仓库内前端已同步，外部消费方未通知 |
| P2-16 视觉验收 | `NOT_VERIFIED` | 单元/组件测试覆盖 UTC 文案；未做浏览器截图验收 |
| P2-17 外部消费方 | `NOT_VERIFIED` | 契约只服务本仓库；未通知外部消费方 |
| P2-18 真实 SSRF 场景 | `NOT_VERIFIED` | 未做真实 DNS rebinding/内网 Provider 部署验证；内置主机白名单现默认开启，非官方 OpenAI/MiMo 主机需显式 opt-in |
| P2-22 真实浏览器下载 | `NOT_VERIFIED` | 仅组件测试断言 revoke 时序；未在 Firefox/Safari 实机下载 |
| 本批 PR 的远程 CI / Security | **VERIFIED（远程）** | CI 18/18、Security 3/3，见 §2.4 |

---

## 4. 影响边界

- **财务语义**：无改动；不触碰预算预占、结算、账本、关账、Flyway、金额计算。P2-07/P2-08 只改分析/报表口径；P2-12 只改费率展示契约；P3-01 只收紧定价创建准入。
- **API / 契约**：`GET /pricing-versions` 的 `unitPrice` 由 number 改为 decimal string；`POST /pricing-versions` 输入仍为 number；新增 14 条 path / 19 个 operation / 21 个 schema；`CACHED_INPUT_TOKEN` 创建返回 400。其余路径/DTO 不变。
- **展示**：技术时间统一为 `UTC` 标注；定价费率显示由 `$30.00` 改为 `30.00 USD`；账期列表两处共用同一缓存键。
- **安全 / 运维**：生产存储 endpoint 的明文内网例外需要显式 `AICOSTOPS_STORAGE_ALLOW_PLAINTEXT_INTERNAL=true`；Gateway Provider 默认公网解析 + 内置主机白名单，mock/内网部署需显式 `AICOSTOPS_GATEWAY_PROVIDER_ALLOW_PRIVATE_ENDPOINTS=true`；Advisor 新增批量/开关配置；对账僵尸 run 回收配置（批次 B）继续生效。
- **依赖 / 数据**：无新增依赖、无 Flyway。
- **CI**：新增 Controller 路由 ⊆ OpenAPI 的单元测试；契约副本删除后只保留 `02-development/api/` 单一真相源。

---

## 5. 变更文件清单

```text
.env.example
backend/src/main/java/com/aicostops/config/ProductionConfigurationValidator.java
backend/src/main/java/com/aicostops/gatewayadmin/api/ControlPlaneDtos.java
backend/src/main/java/com/aicostops/gatewayadmin/application/ModelPricingService.java
backend/src/main/java/com/aicostops/iam/application/LoginService.java
backend/src/main/java/com/aicostops/intelligence/application/CostIntelligenceAnalysisService.java
backend/src/main/java/com/aicostops/intelligence/domain/CostAnomalyEngine.java
backend/src/main/java/com/aicostops/reporting/infrastructure/WorkbenchQueryMapper.java
backend/src/main/resources/application.yml
backend/src/test/java/com/aicostops/config/ProductionConfigurationValidatorTest.java
backend/src/test/java/com/aicostops/contract/ControlPlaneOpenApiRouteCoverageTest.java（新增）
backend/src/test/java/com/aicostops/contract/M18OpenApiContractTest.java
backend/src/test/java/com/aicostops/gatewayadmin/ModelPricingApiIntegrationTest.java
backend/src/test/java/com/aicostops/gatewayadmin/application/ModelPricingServiceTest.java
backend/src/test/java/com/aicostops/iam/api/M1OpenApiContractTest.java
backend/src/test/java/com/aicostops/iam/application/LoginServiceIntegrationTest.java
backend/src/test/java/com/aicostops/intelligence/application/CostIntelligenceLatestDeltaTest.java（新增）
backend/src/test/java/com/aicostops/intelligence/domain/CostIntelligenceEnginesTest.java
backend/src/test/java/com/aicostops/reporting/WorkbenchIntegrationTest.java
compose.m16.yaml
compose.yaml
docs/02-development/api/README.md
docs/02-development/api/openapi.yaml
docs/03-acceptance/m18-openapi.yaml（删除，重复副本）
frontend/src/features/budgets/BudgetPages.test.tsx
frontend/src/features/budgets/CommitmentPages.test.tsx
frontend/src/features/evidence/EvidenceDetailPage.tsx
frontend/src/features/evidence/EvidencePages.test.tsx
frontend/src/features/expenses/ExpensePages.test.tsx
frontend/src/features/expenses/components/ExpenseEvidenceSection.tsx
frontend/src/features/gateway/GatewayCredentialsPage.test.tsx
frontend/src/features/gateway/ModelPricingGovernance.test.tsx
frontend/src/features/gateway/ModelPricingPage.tsx
frontend/src/features/gateway/api/gatewayTypes.ts
frontend/src/features/intelligence/format.test.ts
frontend/src/features/intelligence/format.ts
frontend/src/features/ledger/LedgerPages.test.tsx
frontend/src/features/period-close/PeriodClosePage.test.tsx
frontend/src/features/period-close/PeriodClosePage.tsx
frontend/src/features/period-close/api/periodCloseApi.ts
frontend/src/features/period-close/api/periodCloseKeys.ts
frontend/src/features/period-close/types.ts
frontend/src/features/reconciliation/ReconciliationCaseDetailPage.tsx
frontend/src/features/reconciliation/ReconciliationPage.tsx
frontend/src/features/reconciliation/ReconciliationPages.test.tsx
frontend/src/lib/dateTime.test.ts
frontend/src/lib/dateTime.ts
frontend/src/test/setup.ts
gateway/src/main/java/com/aicostops/gateway/advisor/AdvisorInferenceWorker.java
gateway/src/main/java/com/aicostops/gateway/provider/mimo/MimoChatAdapter.java
gateway/src/main/java/com/aicostops/gateway/provider/openai/OpenAiChatAdapter.java
gateway/src/main/resources/application.yml
gateway/src/test/java/com/aicostops/gateway/advisor/AdvisorInferenceWorkerTest.java
gateway/src/test/java/com/aicostops/gateway/provider/mimo/MimoChatAdapterResolverTest.java
gateway/src/test/java/com/aicostops/gateway/provider/mimo/MimoChatAdapterTest.java
gateway/src/test/java/com/aicostops/gateway/provider/openai/OpenAiChatAdapterResolverTest.java
gateway/src/test/java/com/aicostops/gateway/provider/openai/OpenAiChatAdapterTest.java
gateway/src/test/resources/application-test-defaults.yml
scripts/perf/hc01/runner.mjs
scripts/perf/hc03/runner.mjs
docs/03-acceptance/maintenance/2026-11/2026-11-12-remaining-p2-p3-decisions.md
docs/03-acceptance/maintenance/2026-11/2026-11-12-deep-code-review.md（追加处置记录）
docs/03-acceptance/README.md
docs/04-operations/configs/01-production-configuration.md
CHANGELOG.md
```

---

## 6. 结论

批次 C 的 12 项决策项全部落地。安全边界（生产存储 endpoint、Provider 传输层 SSRF、登录计时侧信道）按推荐方案收紧；分析/报表口径（未观察日、Workbench 同基）与业务决策一致；定价费率改为 decimal string、技术时间统一 UTC、账期客户端合并、证据下载 revoke 延迟、`CACHED_INPUT_TOKEN` 拦截均按已确认方案执行。本地后端/网关单元与架构、定向集成、前端全量测试/构建、脚本测试与文档链接全部通过；PR #188 的远程 CI 18/18 与 Security 3/3 进一步确认全量集成、浏览器 E2E 与安全扫描通过。仅 §3 列出的真实环境/外部消费方项保持 `NOT_VERIFIED`。
