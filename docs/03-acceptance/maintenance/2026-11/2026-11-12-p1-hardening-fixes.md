# 2026-11-12 P1 外围防线加固与 P2 口径修复（维护期）

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-12 |
| 责任人 | AI（DeepSeek Harness） |
| 关联模块 | Backend 生产配置校验、GitHub Actions 合并门禁、Frontend 认证会话与列表排序、Gateway 可观测性 |
| 缺陷来源 | [2026-11-12 深度代码审查](2026-11-12-deep-code-review.md) 的 P1-01 / P1-02 / P1-03（仓库内部分）/ P1-04 / P2-09 / P2-20 |
| 代码基线 | `dev` = `main` = `75f02ea`；修复分支均从该提交拉出，本地合并回 `dev` |
| 关联分支 | `fix/p1-01-prod-secret-guard`、`fix/p1-02-ci-integration-selection`、`fix/p1-03-merge-gate-hardening`、`fix/p1-04-logout-session-hygiene`、`fix/gateway-metrics-and-list-sort`、`docs/2026-11-12-p1-hardening-fixes` |
| 变更性质 | 既有缺陷最小修复；无新功能、无 Flyway、无 API 契约、无依赖变更、无财务语义变更 |
| 总体判定 | **PASS（仓库内 + 远程 CI/Security）**；branch protection / ruleset 仍 `NOT_VERIFIED` |

本次只处理审查报告列出的 6 项（4 个 P1 + P2-09 + P2-20），未触碰其他 P2/P3 发现。
`dev` 上的修复提交未推送到 `origin`，也没有创建 PR；远程 CI 结果不在本记录的证明范围内。

---

## 1. 变更概述（before → after）

### P1-01 生产配置校验器放行仓库内公开的默认密钥

- **位置**：`backend/src/main/java/com/aicostops/config/ProductionConfigurationValidator.java`
- **Before**：JWT 只校验长度 ≥ 32；对象存储 access-key / secret-key 只校验非空。`.env.example` 的 JWT 值恰好 64
  字符，`application.yml` 的 storage 默认值 `aicostops` / `change-me-local-only` 也非空，因此
  “cp .env.example .env 后切 prod”可以带着公开密钥启动。
- **根因**：校验器只实现了“弱值”的长度/非空门禁，没有把仓库中已经公开的示例值视为已泄露凭据；
  测试全部用 `safeProd()` 预置安全值，没有覆盖“解析结果等于示例值”的路径。
- **After**：
  - JWT 增加前缀拒绝：`local-development-only-signing-key`（大小写不敏感），覆盖 `.env.example` 与
    `application-local.yml` 的公开值及其变体；
  - storage access-key / secret-key 对称拒绝 `aicostops` 与 `change-me-local-only`，同时保留空值拒绝；
  - 错误信息只包含环境变量名与原因，不包含解析后的值；`prod` profile 之外行为不变（校验器本身只在 `prod` 生效）。
- **测试**：`ProductionConfigurationValidatorTest` 新增 3 个用例（示例 JWT 值、示例 JWT 前缀、两个 storage
  属性的两个默认值组合），并对错误信息断言 `hasMessageNotContaining(value)`。
- **修复前红灯**：`Tests run: 19, Failures: 3`（三个新用例均因“未抛异常”失败）；
  **修复后**：`Tests run: 19, Failures: 0, Errors: 0, Skipped: 0`。

### P1-02 CI 集成测试选择可静默为空

- **位置**：`.github/workflows/ci.yml`（原 229–247 行内联 `node -e` 枚举）、新增
  `scripts/ci/select-integration-tests.mjs` 与 `scripts/tests/ci-select-integration-tests.test.mjs`
- **Before**：workflow 内联遍历 `src/test/java`，枚举为空时 `-Dit.test=` 为空，且
  `-DfailIfNoSpecifiedTests=false` 关闭了“指定了但没匹配到”的保护，Maven 仍返回成功；重命名为
  `*IT.java` 的集成测试既不被 surefire 也不被 failsafe 收集。
- **根因**：选择逻辑不可测试、没有空集合契约；门禁把“没有可运行测试”等同于“测试通过”。
- **After**：
  - 枚举/分片抽为纯函数模块，保留原 `index % shards === shard` 规则与排序；
  - 空目录、空分片、非法参数均以非 0 退出并输出到 stderr；
  - workflow 改为调用脚本，删除 `-DfailIfNoSpecifiedTests=false`，改用 `-DfailIfNoTests=true`；
  - 新增 6 个 Node 测试（嵌套枚举、空目录、空分片、奇偶分片、非法参数、CLI 输出）。
- **规则不变证据**：对当前 `backend/src/test/java` 实际枚举 **199** 个
  `*IntegrationTest.java`，shard 0 = 100、shard 1 = 99，两片并集与旧内联脚本逐项一致；
  空目录运行选择器退出码为 **1**。

### P1-03 合并门禁可被“全 skipped”绕过（仓库内部分）

- **位置**：`.github/workflows/ci.yml`（`detect-changes` 输出与 `ci-status`）、新增 `.github/CODEOWNERS`
- **Before**：`ci-status` 只把 `failure` / `cancelled` 判失败；只改 `.github/**`、`deploy/**`、
  `.trivyignore` 的 PR 不命中任何 paths-filter，全部作业 `skipped`，汇总仍绿。`docs_only` 只表示
  “命中了 docs 过滤器”，不排除同时改了非文档路径。
- **根因**：没有“变更集与代码无关”的显式证明；skip 被无条件视为通过；安全配置变更不受 CODEOWNERS 约束。
- **After**：
  - 新增 `code` 过滤器（backend/gateway/frontend/scripts/package/compose/deploy/.github/.trivyignore/
    `.env.example`/Dockerfile/.dockerignore），`docs_only` 现在要求 **docs 命中且 code 未命中**；
  - `ci-status` 逐作业比较 `detect-changes` 结果：skip 只有在“该作业自己的过滤器未命中”时才允许，
    过滤器命中却 skip 即失败；`main` push 上所有作业强制运行，skip 一律失败；
  - 非 docs-only 且没有任何已跟踪业务区域命中时显式失败，堵住 `.github`-only / `deploy`-only /
    `.trivyignore`-only 的静默通过；
  - 新增 `.github/CODEOWNERS`：默认 owner + `.github/**`、`.trivyignore`、`compose*.yaml`、
    `docker-compose*.yml`、`deploy/`、`.env.example` 显式 owner（`@BangShou1st`）。
- **不修改 required check 名称**：job 仍为 `ci-status`。
- **逻辑推演（本地 bash 真值表，直接执行从 workflow 抽取的 run 块）**：

| 变更集 / 场景 | 谁 run | 谁 skip | `ci-status` |
|---|---|---|---|
| docs-only（`docs/**`、`*.md`） | 仅 `ci-status` | 所有代码作业（过滤器未命中） | **PASS** |
| `.github/workflows/security.yml`-only | 无代码作业 | 全部 | **FAIL**（无业务区域命中且非 docs-only） |
| `deploy/**`-only | 无代码作业 | 全部 | **FAIL**（同上） |
| `backend/**`-only | backend-unit / backend-integration / docker-build / browser-e2e | gateway / frontend / scripts（过滤器未命中） | **PASS**（既有行为，不被误伤） |
| `frontend/**`-only | frontend-quality / frontend-test / docker-build / browser-e2e | backend / gateway / scripts | **PASS** |
| `scripts/**`-only | scripts-unit / scripts-lifecycle | 其余 | **PASS** |
| backend 过滤器命中但 backend-unit 被 skip | — | backend-unit | **FAIL** |
| `main` push 上任一 required job skip | — | 该作业 | **FAIL**（强制运行） |
| 任一作业 failure | — | — | **FAIL** |

> 说明：审查建议中“为 `.github/**`、`.trivyignore` 增加独立校验 job”不在本批 6 项范围内，本次未新增 job；
> 因此这类 out-of-band 变更会显式失败，直到后续维护窗口补上专用校验。这是有意的 fail-closed 结果，
> 代价是 `.github`-only PR 需要 owner 介入或与代码变更合并提交。

### P1-04 登出失败时本地会话不被清除

- **位置**：`frontend/src/features/auth/AuthSessionProvider.tsx`、`AuthenticatedLayout.tsx`、
  `AuthPages.tsx`（`AppPage` 调用点）
- **Before**：`logout()` 只在 `succeeded` 时 `clearLocalSession()`；`logoutWithCookieLock` 在 5xx 或
  refresh 失败时抛非终态错误，`status` 保持 `authenticated`、access token 仍在内存；调用点
  `void auth.logout()` 丢弃异常形成 unhandled rejection。
- **根因**：把“服务端注销成功”当成“本地会话可清理”的前提；服务端注销本应是 best-effort。
- **After**：
  - 非终态失败也执行 `clearLocalSession()`（仍受当前 lifecycle 约束），httpOnly refresh cookie 保留，
    供下次 refresh 失效；
  - 401 终态路径保持原语义（`handleLocalTerminal()` + `SESSION_INVALIDATED`）；
  - refresh 单飞、跨标签协调、409 语义未改动；失败时不发布 `SESSION_CLEARED`（不清除其他标签页凭据）；
  - 调用点 catch 并提示“已本地登出；服务端会话将在下次刷新时失效。”，无 unhandled rejection。
- **测试**：provider 测试覆盖 500 logout、access 过期后 refresh 失败两条非终态路径（token 清空、
  `status=anonymous`、未发布 `SESSION_CLEARED`、错误仍传给调用方），原 401 终态参数化用例保持通过；
  layout 测试覆盖调用点提示。定向 `47 passed / 0 failed`。

### P2-09 Gateway 可观测口径

- **位置**：`gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java`、
  `observability/GatewayMetrics.java`
- **Before**：只有非流式成功记录 `COMPLETED`；流式是主要流量，`gateway_request_total{outcome="COMPLETED"}`
  系统性偏低。`ReservationRecoveryService` 产生的 `SAFE_RELEASED_TERMINAL` 不在
  `RESERVATION_RECOVERY_OUTCOMES`，被 `bounded()` 折叠成 `UNKNOWN`。
- **After**：流式成功在终端 `[DONE]` 真正下发后记录一次 `COMPLETED`（在 finalize 与 `[DONE]` 之间被客户端
  取消仍记 `CANCELED`，避免一次请求同时计 COMPLETED 与 CANCELED）；`SAFE_RELEASED_TERMINAL` 加入
  bounded 集合，不再落 `UNKNOWN`。标签集合仍是固定枚举，不引入高基数标签。
- **测试**：`GatewayMetricsTest` 新增 bounded outcome 断言；`GatewaySafeFailoverIntegrationTest` 的流式成功
  用例断言 `gateway_request_total{outcome="COMPLETED"}` 增量恰好为 1。

### P2-20 列表“最新在前”用 localeCompare 比数字字符串 id

- **位置**：`GatewayCredentialsPage.tsx`、`ModelPricingPage.tsx`、`RoutingPoliciesPage.tsx`、新增
  `frontend/src/lib/numericIdOrder.ts`
- **Before**：`b.id.localeCompare(a.id)` 按字典序比较数字字符串，`['9','10','100']` 得到 `9,100,10`。
- **After**：共享 `compareNumericIdDesc` / `sortByNumericIdDesc`，用 `BigInt` 比较数值（id 是数据库
  long 的字符串形式，避免 `Number` 精度丢失），非数字 id 保留原 locale 降序兜底；三个页面统一使用。
  未改后端分页顺序。
- **测试**：`numericIdOrder.test.ts` 覆盖 `['9','10','100'] → ['100','10','9']`、稳定性与输入不变性、
  非数字兜底。

---

## 2. 验证环境与执行命令

环境（2026-11-12）：Windows + PowerShell，Node `v24.14.0`，Java `21.0.10`，Docker Desktop 引擎就绪。

| # | 命令 | 结果 |
|---|---|---|
| 1 | `cd backend; .\mvnw.cmd -B "-Dtest=ProductionConfigurationValidatorTest" test` | 修复前 `19 run / 3 failed`；修复后 `19 run / 0 failed` |
| 2 | `cd backend; .\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test` | `663 run / 0 failed / 2 skipped`，BUILD SUCCESS |
| 3 | `cd backend; .\mvnw.cmd -B "-Dgroups=architecture" test` | `36 run / 0 failed / 0 skipped`（ModuleDependency 31、GatewaySettlement 2、LedgerImmutability 2、M6Reconciliation 1），BUILD SUCCESS |
| 4 | `cd gateway; .\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test` | 单独重跑 `211 run / 0 failed / 1 skipped`，BUILD SUCCESS（含 GatewayArchitectureTest 7） |
| 5 | `cd gateway; .\mvnw.cmd -B verify "-Dsurefire.skip=true" "-Dit.test=GatewaySafeFailoverIntegrationTest" "-DfailIfNoTests=true"` | `4 run / 0 failed`（流式 COMPLETED 断言通过）；同次运行 unit/arch `233 run / 0 failed / 1 skipped` |
| 6 | `cd frontend; npm run lint` | 通过，无输出 |
| 7 | `cd frontend; npm test -- --run` | `73 files / 553 tests passed`（干净重跑） |
| 8 | `cd frontend; npm run build` | 通过（`tsc -b` + Vite，`built in 1.08s`） |
| 9 | `npm run test:scripts` | `47 passed / 0 failed` |
| 10 | `npm run verify:links` | `246 Markdown files / 387 links / 0 broken` |

负载相关的既有测试抖动（非本次改动引入，均已单独复跑通过）：
- 第一次跑完整前端套件时与 backend Maven 测试并发，`ReconciliationPages.test.tsx` 两个用例
  5s 超时；该文件单独运行 `27 passed`，随后无并发重跑整套 `553 passed`。
- 第一次跑 gateway 单测时与 backend ArchUnit（约 100s）并发，`GatewayApplicationTest.
  prometheusMetricsAreExposed` 在 5s blocking read 上超时；单独重跑 `211/0/1` 通过。
  两次抖动都发生在与本次改动无关的既有超时用例上，未修改断言或超时阈值。

补充的可执行证据：

- **集成测试选择**（真实目录）：
  ```text
  shard0=100 shard1=99 total=199
  matchesLegacy=True
  emptyExit=1   # 空目录：no *IntegrationTest.java files found
  ```
- **ci-status 真值表**：9 个场景全部符合 §1 P1-03 表格（docs-only/backend/frontend/scripts PASS；
  `.github`-only、filtered-but-skipped、main-forced-skip、job-failure、docs+deploy FAIL）。
- **P1-04 定向**：`AuthSessionProvider.test.tsx` + `AuthenticatedLayout.test.tsx` → `47 passed / 0 failed`。

### 2.1 远程验证（`dev` 推送 `c4cf3bf` 触发，2026-11-12）

| 工作流 | run | 结果 |
|---|---|---|
| CI | [43137592677](https://github.com/BangShou1st/AI-CostOps/actions/runs/43137592677) | 14/14 success；`backend-integration` 日志 `Selected 100 integration tests for shard 0` / `Selected 99 integration tests for shard 1`；`ci-status` 日志 `All required checks passed (docs_only=false)` |
| Security | [43137592638](https://github.com/BangShou1st/AI-CostOps/actions/runs/43137592638) | `trivy` + `codeql (java-kotlin)` + `codeql (javascript-typescript)` 3/3 success |

本次推送是 `75f02ea..c4cf3bf` 的 fast-forward（含 5 个代码修复），`detect-changes` 正确判定为非 docs-only，
因此完整执行了 backend/gateway 集成、`docker-build` 与 `browser-e2e`；这同时补上了此前
“修改后的 `ci.yml` 在 GitHub Actions 上的真实运行结果”这条 `NOT_VERIFIED`。

第二次推送 `7744c17` 只改 3 个文档文件，但 `detect-changes` 仍检测到 **26** 个文件：`dorny/paths-filter`
未配置 `base` 时默认以仓库默认分支（`main`）为基准，`dev` 领先 `main` 的全部提交都会计入；因此
[CI 43138867306](https://github.com/BangShou1st/AI-CostOps/actions/runs/43138867306) 仍跑完整套件
（14/14 success），[Security 43138867994](https://github.com/BangShou1st/AI-CostOps/actions/runs/43138867994) 3/3。
结论：`docs_only=true` 的短路只在 PR（base..head）和 `main` push（同分支上一提交）上生效，`dev` push 不会短路。
这是实现细节造成的成本浪费，**不是门禁漏洞**；后续可在 `detect-changes` 显式设置
`base: ${{ github.ref }}`（PR 事件会忽略该参数）让 dev push 按“上一提交”比较。

---

## 3. 逐项结论

| ID | 结论 | 关键证据 |
|---|---|---|
| P1-01 | **FIXED** | 3 个新用例先红后绿；19/19；错误信息不含值 |
| P1-02 | **FIXED** | 空目录退出 1；199 个测试分片与旧规则逐项一致；`-DfailIfNoTests=true` |
| P1-03 | **FIXED（仓库内部分）** | CODEOWNERS + ci-status 真值表；branch protection 另行标注 `NOT_VERIFIED` |
| P1-04 | **FIXED** | 500 / refresh 失败均清本地会话；401 语义不变；调用点提示 |
| P2-09 | **FIXED** | 流式 COMPLETED 增量 1；`SAFE_RELEASED_TERMINAL` 不再 UNKNOWN |
| P2-20 | **FIXED** | `['9','10','100'] → ['100','10','9']`；三页统一 |

---

## 4. `NOT_VERIFIED` 与已知边界

| 项 | 状态 | 说明 |
|---|---|---|
| Branch protection / ruleset 的 required checks、bypass 规则、“Require review from Code Owners” | `NOT_VERIFIED` | 仓库外设置，未调用 GitHub API，也没有仓库管理员凭据；CODEOWNERS 本身不强制 review |
| 修改后的 `ci.yml` 在 GitHub Actions 上的真实运行结果 | **VERIFIED**（2026-11-12） | [CI 43137592677](https://github.com/BangShou1st/AI-CostOps/actions/runs/43137592677) 14/14、[Security 43137592638](https://github.com/BangShou1st/AI-CostOps/actions/runs/43137592638) 3/3；shard 100/99 与 `ci-status (docs_only=false)` 均来自运行日志。`docs_only=true` 在远程 `dev` push 不生效（`base` 默认 `main`，见 §2.1），PR 路径待真实 PR 验证 |
| 全量 backend/gateway 集成测试（Testcontainers） | **VERIFIED（远程）**（2026-11-12） | 同一次 CI 运行中 `backend-integration` 两片、`gateway-integration`、`browser-e2e` 全部 success；本地仍只做定向回归 |
| CODEOWNERS owner 账号有效性 | `NOT_VERIFIED` | 按仓库 URL 使用 `@BangShou1st`；账号变更时需替换 |
| 生产部署是否仍在使用示例密钥 | `NOT_VERIFIED` | 校验器现在会 fail-fast；实际环境需重新注入并轮换后重启验证 |
| 其他 P2 / P3 审查发现 | `NOT_IN_SCOPE` | 本批只做 6 项，未处理 P2-01/02/03/… 与 P3-01~06 |

已知取舍：

1. `.github`-only / `deploy`-only / `.trivyignore`-only PR 现在会被 `ci-status` 显式判红，直到后续补专用
   校验 job；这是 P1-03 的预期 fail-closed 行为，不是回归。**复核补充**：同一规则也覆盖
   `compose*.yaml`、`docker-compose*.yml`、`Dockerfile*`、`.dockerignore`、`.env.example`，以及任何
   未被现有过滤器覆盖的根文件（例如 `LICENSE`、`.gitignore`、`.editorconfig`、`.gitattributes`）。
   这些变更本身不需要跑测试，但现在必须与业务代码同 PR 或由 owner 处理；建议后续增加 `meta` 过滤器
   与一个轻量校验 job，把 fail-closed 从“一律拒绝”收敛为“有针对性校验”。
2. P1-01 只拒绝“仓库内已公开的示例值”，不判断密钥熵；运维仍必须通过密钥管理系统生成随机值。
3. P1-04 的服务端注销是 best-effort：失败时本地已登出，但服务端 refresh cookie 可能仍存活到下一次
   refresh 或过期；这是“公共终端优先本地清理”的明确取舍。
4. P2-09 的 `COMPLETED` 以 `[DONE]` 下发为界；DB finalize 成功但客户端在 `[DONE]` 前断开的请求仍记
   `CANCELED`，口径与“HTTP 成功完成”一致。

---

## 5. 影响边界

- **财务语义**：无改动；不触碰预算预占、结算、账本、关账、Flyway、金额计算。
- **API / 契约**：无改动；未新增/修改 OpenAPI、DTO 或错误码。
- **依赖**：无新增或升级。
- **dev / local**：生产校验器仍只在 `prod` 生效；本地默认值路径不变。
- **生产**：使用 `.env.example` 默认 JWT/storage 凭据的部署会在启动时失败（预期安全行为），需先轮换。
- **CI**：集成测试选择、skip 语义与 CODEOWNERS 影响所有 PR；required check 名称未变。
- **前端**：登出失败时的 UX 与列表顺序变化；refresh 单飞、跨标签、409 逻辑未动。
- **可观测性**：`gateway_request_total{outcome="COMPLETED"}` 对流式请求上升；
  `gateway_reservation_recovery_total{outcome="SAFE_RELEASED_TERMINAL"}` 可见，`UNKNOWN` 相应下降。

---

## 6. 变更文件清单

```text
backend/src/main/java/com/aicostops/config/ProductionConfigurationValidator.java
backend/src/test/java/com/aicostops/config/ProductionConfigurationValidatorTest.java
.github/workflows/ci.yml
.github/CODEOWNERS
scripts/README.md
scripts/ci/select-integration-tests.mjs
scripts/tests/ci-select-integration-tests.test.mjs
frontend/src/features/auth/AuthSessionProvider.tsx
frontend/src/features/auth/AuthPages.tsx
frontend/src/features/auth/AuthSessionProvider.test.tsx
frontend/src/app/layout/AuthenticatedLayout.tsx
frontend/src/app/layout/AuthenticatedLayout.test.tsx
frontend/src/lib/numericIdOrder.ts
frontend/src/lib/numericIdOrder.test.ts
frontend/src/features/gateway/GatewayCredentialsPage.tsx
frontend/src/features/gateway/ModelPricingPage.tsx
frontend/src/features/settings/routingPolicies/RoutingPoliciesPage.tsx
gateway/src/main/java/com/aicostops/gateway/observability/GatewayMetrics.java
gateway/src/main/java/com/aicostops/gateway/web/ChatCompletionController.java
gateway/src/test/java/com/aicostops/gateway/observability/GatewayMetricsTest.java
gateway/src/test/java/com/aicostops/gateway/request/GatewaySafeFailoverIntegrationTest.java
```

---

## 7. 审查报告需更正（2026-11-12 复核后修订）

> 本节最初由修复会话写入；随后的独立复核发现其中两条“更正”本身不准确，已在此修订。
> 保留修订痕迹是为了让后来者知道哪一版结论被推翻，而不是静默改写。

- `CHANGELOG` 中“198 个集成测试类”是 `57696e2`（该条目引入时）的**准确计数**；`75f02ea` 起为
  **199** 个（shard 0 = 100、shard 1 = 99）。这是测试随维护增长，不是历史记录错误；
  审查报告本身也从未引用 198。
- 审查报告 §3.1 P1-03 引用的 `ci.yml:565-577` 覆盖基线 `if: always()`（565）到旧循环的 `fi`（577），
  已包含被测逻辑；基线 `ci.yml` 共 579 行，`ci-status` 作业从 551 行开始、`run` 块为 571–579 行。
  原引用无须更正（此前“571–599 行”的说法越过了文件末尾）。
- 审查报告 P1-04 提到的 `AuthPages.tsx:49` 调用点属于审查 P3-06 已标注的 `AppPage` 死代码；本次仍按
  “所有调用点”要求补了 catch，未删除该页面（死代码清理不在本批范围）。这一条成立。

---

## 8. 发布同步（2026-11-12）

- `dev → main` 发布 PR [#183](https://github.com/BangShou1st/AI-CostOps/pull/183)（19 个提交）经全部检查项
  通过后 squash 为 `39560cc`；`main` 由此包含本次 P1/P2 修复与审查/验收文档。
- 仓库开启了“合并后自动删除 head 分支”，而本次 PR 的 head 是常驻维护线 `dev`，因此 `origin/dev` 被
  GitHub 删除。已用本地 `dev`（`b885938`：`66acf4d` 与 `39560cc` 的 merge）恢复远程分支。
- **后续约定**：`dev → main` 发布应使用 `release/<date>` 临时分支作为 PR head，合并后只删除临时分支，
  不再让 `dev` 承担 PR head。
- 同步后拓扑：`main`（`39560cc`）是 `dev` 的祖先，无分叉；`dev` 仅多一个回到 dev 的 merge 提交与本节记录。
