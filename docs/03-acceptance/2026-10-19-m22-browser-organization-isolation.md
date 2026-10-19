# M22 双组织浏览器隔离补充验收（2026-10-19）

基线：`dev @ 8eb5c9b27495e5023f033787ee0651c99b51e92f`

代码验收提交：`550db8f0846e03e192ad288051d45bd27011c059`
结论：**`M22_BROWSER_ORGANIZATION_ISOLATION = PASS`**

本文件是基于最新 `dev` 新增的浏览器级组织隔离证据。历史文件 [M22 v3 production certification](m22-v3-production-certification.md) 保留原来的 `DEFERRED / NON-BLOCKING` 时间点记录，未将历史状态改写为 PASS。

## 1. 环境与身份建立

验收运行于 GitHub Actions 的 `browser-e2e` 作业。作业启动独立 Compose 项目 `aicostops-browser-e2e`，使用该项目专属 Backend、Frontend、MySQL 和其他依赖；CI 清理步骤对这个独立项目执行 `down --volumes --remove-orphans`。没有连接生产数据库或生产凭据。

产品没有公开的组织创建或组织切换 API。为保持真实身份认证和服务端授权链路，组织及身份按以下方式准备：

| 身份 | 建立方式 | 权限与用途 |
| --- | --- | --- |
| 组织 A 管理员 | 使用 Compose 合成种子管理员，通过真实登录 UI/API 登录 | A 的管理员身份 |
| 组织 B 管理员 | 先从真实注册 UI 调用 `/api/v1/auth/register`，随后只在本次隔离 MySQL 测试夹具中创建 B 组织并将该新成员迁入 B；真实登录 UI/API 登录 | B 的管理员身份，与 A 使用不同用户 |
| 组织 A 普通员工 | 从真实注册 UI/API 建立另一用户并正常登录 | 低权限反例，验证其不能操作 B 的管理员资源 |

数据库夹具仅承担产品未提供的组织初始化：使用动态返回的用户/成员 ID 创建 B 组织、迁移该新成员的组织归属、授予该成员的组织角色并为 B 建立一个开放账期。夹具检查成员归属、3 个组织角色及 1 个开放账期。所有 API 身份认证、Cookie 签发、`/auth/me`、角色权限和资源读取/写入均走真实应用链路。夹具限定在带有本次项目标签的独立 Compose MySQL 中。

组织数据使用运行时生成的资源 ID 和唯一合成标记，不依赖固定数据库 ID。每个组织均创建 Provider Account、Provider Connection 和 credential 元数据、私有 Model Catalog / Provider Model、Pricing Version、Project、Budget、Routing Policy、合成成本记录及 Ledger posting、Advisor Profile 和 explanation Job。

## 2. 浏览器上下文与会话边界

Playwright 测试分别创建 A、B 和员工三个新的 `BrowserContext`。上下文不共享 `storageState`、Cookie Jar 或 `localStorage`。A、B 由不同用户登录；测试确认登录后的 refresh Cookie 值不同，且 `/auth/me` 返回各自用户和组织。

实际结果：

- 登录与刷新页面后，A 仍是 A 管理员，B 仍是 B 管理员。
- 把 A 的 access token 放到 B 上下文请求 `/auth/me`，仍识别为 A；它不能读取 B 的 Connection（404）。Token 不会被浏览器上下文或 URL 改写成 B 身份。
- A 登出返回 204，A 的 refresh Cookie 被清除；B 的 Cookie 仍存在。随后 B 的 `/auth/refresh` 返回 200，B 的 `/auth/me` 仍是原 B 用户和组织。
- 测试最初检查 A 登出后 B Cookie 仍等于登录时的旧值；Cookie 可在会话期间正常轮换，因此改为检查 B Cookie 仍存在，并继续以 refresh 200 和 B 身份确认会话独立。登录时 A/B Cookie 值不同的断言保留。

本机 BrowserSkill 枚举到一个 Edge 扩展实例且没有活动 session；没有创建或复制浏览器登录配置。独立会话证据来自上述 CI 中的真实浏览器 `BrowserContext`。本机 Playwright 浏览器已卸载；只在隔离 GitHub runner 的既有 E2E 作业中安装 Chromium 执行 Playwright。

## 3. 正向功能与跨组织读取矩阵

以下状态来自代码验收提交的 `browser-e2e` 日志中 183 条 HTTP 状态断言。每条断言的实际状态均与测试期望一致；A→B 和 B→A 的资源隔离检查均执行。

| 场景 | 实际 API / 页面 | 期望 HTTP | 实际 HTTP | 数据断言 |
| --- | --- | ---: | ---: | --- |
| 注册、登录、当前身份 | `POST /auth/register`、`POST /auth/login`、`GET /auth/me` | 200 | 200 | 返回各自身份 |
| Connection、Credential 正向创建/激活 | `POST /provider-connections`、`POST /provider-connections/{id}/credentials`、`POST /provider-connections/{id}/activate` | 201 / 201 / 200 | 201 / 201 / 200 | 仅返回脱敏 credential 元数据；响应不含合成原始密钥 |
| 模型、定价、预算 | `GET /model-catalog`、`GET /provider-models`、`POST /pricing-versions`、`POST /pricing-versions/{id}/activate`、`POST /budgets` | 200 / 200 / 201 / 200 / 201 | 200 / 200 / 201 / 200 / 201 | A、B 各自可使用本组织私有模型与资源 |
| Routing Policy | `POST /routing-policies`、`POST /routing-policies/{id}/activate` | 201 / 200 | 201 / 200 | 本组织策略及候选正常建立 |
| Advisor | `PUT /ai-advisor/profile`、`POST /ai-advisor/explanations`、`GET /ai-advisor/explanations/{id}` | 200 / 201 / 200 | 200 / 201 / 200 | 可建立并读取本组织 Profile 与 explanation Job |
| 成本和 Ledger 正向查询 | `GET /costs/charges`、`GET /ledger/postings?...projectId={ownProjectId}` | 200 / 200 | 200 / 200 | 合成导入、分摊及 Ledger posting 属于当前组织 |
| 跨组织列表与筛选 | `GET /provider-connections`、`/provider-accounts`、`/projects`、`/budgets?...scopeId={foreignProjectId}`、`/costs/charges`、`/ledger/postings?...projectId={foreignProjectId}`、`/model-catalog`、`/provider-models`、`/pricing-versions`、`/routing-policies` | 200 | 200 | 列表不含对方资源；对方项目筛选结果为空 |
| 外组织私有模型的路由选项 | `GET /routing-options?modelId={foreignModelId}` | 404 `RESOURCE_NOT_FOUND` | 404 `RESOURCE_NOT_FOUND` | 不泄露外组织私有模型的路由候选 |
| 外组织详情 | `GET /provider-connections/{id}`、`/provider-connections/{id}/credentials`、`/routing-policies/{id}`、`/budgets/{id}`、`/costs/charges/{id}`、`/ledger/postings/{id}`、`/ai-advisor/explanations/{id}` | 404 | 404 | API 使用现有隐私 404 契约，不返回外组织内容 |
| 浏览器直接输入外组织 Connection URL | `/settings/provider-connections/{foreignConnectionId}`，页面调用 Connection 详情 API | API 404 | API 404 | 页面稳定显示本组织列表；MutationObserver 和最终 DOM 均未发现外组织账户名、credential 标签或合成密钥 |

跨组织列表仍返回 200 是现有列表 API 契约；组织隔离通过结果集只含当前组织/全局可见资源验证。详情 API 按现有契约返回 404，低权限员工权限不足则返回 403，没有为了测试改变状态码。

## 4. 跨组织写入与写后不变证明

以下攻击均使用有效登录身份，并携带对方组织的真实动态资源 ID。拒绝后由资源所有者重新读取 API 状态，核对内容、状态、版本或关联记录未变。

| 操作 | 实际 API | 期望 HTTP | 实际 HTTP | 拒绝后的所有者复核 |
| --- | --- | ---: | ---: | --- |
| 修改 Provider Account | `PATCH /provider-accounts/{foreignId}` | 404 | 404 | Provider Account 列表/字段仍为原值 |
| 更新、激活、修订 Provider Connection | `PUT /provider-connections/{foreignId}`；对应 `/activate`、`/revisions` | 404 | 404 | 所有者读取的内容、状态、版本不变 |
| 创建或撤销 Credential | `POST /provider-connections/{foreignId}/credentials`；`POST .../credentials/{foreignId}/revoke` | 404 | 404 | Credential 元数据及状态不变，原始密钥不返回 |
| 修改 Budget / Project | `PUT /budgets/{foreignId}`；`PATCH /projects/{foreignId}` | 404 | 404 | 所有者读取的额度、名称、状态不变 |
| 更新、激活、修订 Routing Policy | `PUT /routing-policies/{foreignId}`；对应 `/activate`、`/revisions` | 404 | 404 | 策略内容、候选、状态、版本不变 |
| 激活外组织 Pricing Version | `POST /pricing-versions/{foreignId}/activate` | 404 | 404 | 所有者的 Pricing Version 状态不变 |
| 用 A 自己的 Account 给 B 私有模型定价（反向亦测） | `POST /pricing-versions`，body 含对方 `providerModelId` | 404 | 404 | 发起方无新增 Pricing Version；模型仍只由其所有者读取 |
| 在本方策略草稿关联对方 Account/Model | `PUT /routing-policies/{ownDraftId}` | 400 | 400 | 草稿候选/版本不变 |
| Advisor Profile 引用外组织项目/模型 | `PUT /ai-advisor/profile` | 400 | 400 | Profile 仍保持写入前内容 |
| 重试外组织 Advisor Job | `POST /ai-advisor/explanations/{foreignId}/retry` | 404 | 404 | 所有者读取的 Job 状态不变 |
| 对外组织成本记录入账 | `POST /costs/charges/{foreignId}/post` | 404 | 404 | 所有者读取的 Charge 状态与 Ledger posting 不变 |
| 员工读取/激活 B Connection | `GET /provider-connections/{BId}`；`POST /provider-connections/{BId}/activate` | 403 / 403 | 403 / 403 | B 所有者复核 Connection 内容、状态、版本不变 |
| 请求体伪造 `orgId` 创建 Provider Account | `POST /provider-accounts`，分别 A→B、B→A | 201 或 400 | 201 | `orgId` 不改变服务端组织归属：资源仅出现在调用者组织列表，对方组织列表无该资源 |

上述资源没有独立 DELETE API；本次不适用删除攻击，没有为验收新增业务 API。所有能执行的跨组织写入拒绝都在 A→B、B→A 两个方向运行。请求体 `orgId` 被 API 接收但不作为授权或归属依据；归属由服务端认证上下文决定。

## 5. 缺陷、修复与回归

先复现后修复发现一个 **High** 严重度的真实组织隔离缺陷：

- A 的 `GET /model-catalog` 和 `GET /provider-models` 曾包含 B 的私有模型标识/名称。
- A 查询 B 私有模型的 `GET /routing-options?modelId=...` 曾返回可见的路由选项。
- A 使用自己的 Provider Account、携带 B 的真实私有 `providerModelId` 调用 `POST /pricing-versions` 曾返回 201 并持久化跨组织关联。

这构成跨租户私有模型元数据泄露及未授权定价关联写入。修复在服务端将私有 Model Catalog / Provider Model / Routing Policy 查询限制为全局或当前组织，并在 Pricing 创建时验证 Provider Model 组织范围；未关闭鉴权、未放宽隔离。新增 `ModelPricingApiIntegrationTest` 回归覆盖。

## 6. 执行结果与记录

| 验证 | 命令/运行 | 结果 |
| --- | --- | --- |
| 完整 Compose 浏览器 E2E | GitHub CI `browser-e2e`，提交 `550db8f` | **17 tests / 17 passed**，约 1.1 分钟；状态矩阵 183 条断言全匹配 |
| 后端组织模型范围定向集成 | `cd backend; .\mvnw -B -Dtest=ModelPricingApiIntegrationTest test` | **11 tests / 0 failures / 0 errors / 0 skipped，exit 0** |
| 前端 E2E 静态检查 | `npm exec eslint -- e2e/m22-browser-organization-isolation.spec.ts e2e/support/m22-database-fixture.ts`（`frontend`） | exit 0 |
| TypeScript 检查 | `npx tsc -b --pretty false`（`frontend`） | exit 0 |
| `npm run verify`（本机） | 仓库根目录 `npm run verify` | **exit 1，未通过**：Testcontainers MySQL 被 Docker 标记 `OOMKilled=true`；`PeriodCloseCoordinatorIntegrationTest` 7 errors、`PeriodReopenIntegrationTest` 3 errors，日志含 `Communications link failure` / `java.net.ConnectException: Connection timed out`。这是本机容器/连接环境错误，不是断言通过；未删除测试或放宽断言。远端同提交的 Backend Integration `./mvnw -B verify` 作业通过。 |
| GitHub CI | [CI run 39205630409](https://github.com/BangShou1st/AI-CostOps/actions/runs/39205630409) | **14/14 jobs success**，包括 browser-e2e、Backend Integration、Docker Build |
| GitHub Security | [Security run 39205630389](https://github.com/BangShou1st/AI-CostOps/actions/runs/39205630389) | **3/3 jobs success**：CodeQL Java/Kotlin、CodeQL JavaScript/TypeScript、Trivy |

修正后的最终 E2E 运行没有失败截图或 Trace。前一轮 run `39204715980` 为 16/17；唯一失败是测试把 B 的 refresh Cookie 与更早登录时保存的旧值作相等比较，原因是合法 Cookie 轮换。该断言改为验证 B Cookie 仍存在，随后实际 `/auth/refresh` 200 和 `/auth/me` 仍为 B；新 run 17/17 通过。CI 中失败作业会附加的诊断产物未纳入仓库，也未复制 Trace 内容。

## 7. 限制与清理记录

- 产品没有公开的组织创建/切换流程，因此仅 B 组织的初始化使用本次隔离 Compose MySQL 的 SQL 测试夹具。身份认证、会话、权限和应用 API 查询均为真实链路。
- Compose 没有 Advisor Worker；测试建立并读取真实持久化 Job/Explanation，但不执行 Advisor 推理。没有调用任何真实收费 Provider；Provider/credential/成本数据均为合成测试数据。
- 本机 BrowserSkill 只有一个已连接 Edge instance，未建立第二个独立 BrowserSkill profile。测试中的独立 Cookie Jar 由 CI 中独立 `BrowserContext` 实际验证。
- 本机本轮 Compose 服务已停；确认并删除的 Docker volume 仅属于唯一项目 `aicostops-m22-01a1522b`。本机 `.local-dev/m22/e2e.env` 与此前下载的两个失败运行产物目录仍在忽略目录中。对单个 `.env` 文件及失败产物目录的删除请求被执行工具策略拦截；未换用其他方式绕过，也未将这些文件或 Trace 提交。
- 未改 `main`，未开 PR、未合并、未部署；没有修改财务账本不变量、Zen/Kilo Gateway 共享规则或 FREE_ONLY 规则；未提交 `.freebuff/`、`.trae/`、密码、Token、Cookie、Trace 或临时测试数据。

## 8. 最终判定

`M22_BROWSER_ORGANIZATION_ISOLATION = PASS`。此状态由最新 `dev` 上真实 Backend、Frontend、MySQL、独立浏览器上下文、双向读取/写入拒绝及 GitHub CI/Security 新增证据支持；历史 M22 的 `DEFERRED` 记录保持不变。停止，等待独立复审。

## 9. 独立复审整改：Provider Model 可见性边界

本节为独立复审后基于 `dev @ 86a3167fd47110aded9d434f4faad9e79b9b04b1` 新增的后续证据。前文第 1–8 节描述的是提交 `550db8f0846e03e192ad288051d45bd27011c059` 时已完成的双组织浏览器验收；本节保留该时间点记录，并补充复审发现及其整改结果。

### 9.1 P1：Routing Options 跨组织 Provider Model 泄露

**修复前真实复现。** 新增真实 MySQL 集成用例，创建全局逻辑模型 G、A/B 两个 `TESTPC` Provider Account，以及绑定 G 的 A、B 私有 Provider Model。A 的有效管理员身份请求 `GET /api/v1/routing-options?modelId=G` 得到 HTTP 200；响应包含全局模型、A 自己绑定的私有模型，同时也泄露了 B 的私有 Provider Model ID 和名称。该 RED 运行中动态 ID 分别为 G=`12`、全局映射=`13`、A 私有映射=`14`、B 私有映射=`15`；这些值仅用于还原该次运行，不被测试固定使用。

根因是 `findRouteOptions()` 只按 Provider Code、逻辑模型 ID 和 ACTIVE 状态关联 Provider Model，未限制 `owner_org_id` 与账户绑定。A 的账户因此匹配到 B 的私有映射。

**关联写入核查。** 同一真实 MySQL 集成测试通过 API 验证：A 使用全局 Provider Model 创建 Routing Policy 返回 201；使用绑定 A 自己账户的私有模型创建返回 201；提交 B 的真实私有 Provider Model ID 返回 400 `VALIDATION_FAILED`，且拒绝后 A 的 Routing Policy 与 Candidate 行数不变。修复前 `isRoutablePair()` 已阻止该跨组织写入，因此未发现第二个可利用的写入绕过。整改同时让 `findEligibleProviderModelCode()` 按调用组织和 Provider Account 过滤，并使 `isRoutablePair()` 对全局模型要求未绑定账户、对私有模型要求当前组织和正确账户，令校验边界保持一致。

| A 的操作 | 期望与实际 | 数据结果 |
| --- | --- | --- |
| `GET /api/v1/routing-options?modelId=G`，全局 Provider Model | 200，正常可见 | 保留 |
| 同一查询，绑定 A 账户的 A 私有 Provider Model | 200，正常可见 | 保留 |
| 同一查询，绑定 B 账户的 B 私有 Provider Model | 200，列表排除 B 模型 ID 与名称 | 不返回外组织内容 |
| `POST /api/v1/routing-policies` 关联全局 / A 正确绑定私有模型 | 201 / 201 | 两个合法 Draft 策略及 Candidate 创建成功 |
| 同一创建接口关联 B 私有 Provider Model | 400 `VALIDATION_FAILED` | A 的策略和 Candidate 行数不变 |

### 9.2 P2：Pricing Version Provider Model ID 存在性探针

**修复前真实复现。** 对以下四个请求使用同一组织 A 管理员身份和同一个 A 的有效 `TESTPC` Provider Account；各 Provider Model ID 均运行时创建或动态计算：

| Provider Model 情况 | 修复前实际 HTTP / code | 修复后期望与实际 HTTP / code |
| --- | --- | --- |
| B 的私有模型（RED 运行 ID `20`） | 404 `RESOURCE_NOT_FOUND` | 404 `RESOURCE_NOT_FOUND` |
| 完全不存在的模型 ID（RED 运行 ID `1021`） | 400 `VALIDATION_FAILED` | 404 `RESOURCE_NOT_FOUND` |
| 全局可见但 Provider Code 为 `OTHERPC` 的模型（RED 运行 ID `21`） | 400 `VALIDATION_FAILED` | 400 `VALIDATION_FAILED` |
| 当前组织账户绑定且兼容的私有模型（RED 运行时另用全局兼容模型 ID `19`） | 201 `DRAFT` | 201 `DRAFT`（新增 GREEN 用例改用 A 正确绑定的私有模型） |

修复前 `createPricingVersion()` 通过 `isProviderModelOwnedByAnotherOrganization()` 对外组织真实 ID 特判为 404，而完全不存在的 ID 落入一般校验并返回 400，响应可用于探测存在性。现删除该跨组织存在性查询；`selectProviderModel()` 只接受未绑定账户的全局模型，或当前组织且绑定当前请求账户的私有模型。不可见或不存在都走同一 `RESOURCE_NOT_FOUND`；已可见而 Provider Code 不兼容的模型仍走原 `VALIDATION_FAILED`。

拒绝请求之后，集成测试查询 A 组织的 `pricing_version` 和 `pricing_rate`：外组织私有、不存在、可见不兼容三种拒绝均无新增行；合法私有模型创建增加 1 条 Pricing Version 和 2 条 Rate。金额、版本递增及激活规则未修改。

### 9.3 RED → GREEN 与回归结果

新增/更新 `ModelPricingApiIntegrationTest` 的 RED 运行使用真实 Testcontainers MySQL 8.4 与 Redis：14 tests，12 passed、2 failed、0 errors、0 skipped，exit 1。两个失败分别证实 `routingOptionsExposeGlobalAndCorrectlyBoundPrivateModelsOnly` 收到 B 的私有模型 ID，以及 `pricingCreationDoesNotRevealWhetherAnInvisibleProviderModelExists` 实测状态序列为 `[404, 400, 400, 201]` 而隐私契约要求 `[404, 404, 400, 201]`。修复后同一命令 14 tests，14 passed、0 failures、0 errors、0 skipped，exit 0。

首轮修复后复跑暴露一项旧测试夹具问题：原 `incompatibleProviderModelIsRejectedAndForeignAccountIsInvisible` 将 `model_catalog.id` 误用为 `provider_model.id`，因此该输入现按正确契约返回 404。测试已改成真实可见但 Provider Code 不兼容的 `OTHERPC` Provider Model；业务实现没有为旧错误夹具保留 400。

定向命令：`cd backend; .\mvnw.cmd '-Dtest=ModelPricingApiIntegrationTest' test`。本轮保留的修复前日志仅在本地忽略目录 `.local-dev/m22-review/`，其中 Authorization 与 trace 标识已脱敏，不纳入版本控制。

原 M22 Playwright 测试 `frontend/e2e/m22-browser-organization-isolation.spec.ts` 继续覆盖双组织自己的 Pricing Version / Routing Policy 创建、外组织私有模型路由选项 404、外组织定价关联拒绝，以及 Routing Policy 候选拒绝后的状态不变。本轮没有本地安装或运行 Playwright 浏览器；后续推送 CI 的 `browser-e2e` 作业将对既有完整 Compose 场景做回归，不重复本机 183 条浏览器断言。

### 9.4 本轮结论与远端验证

最终实现与测试提交：`b5d3a5dc6549685005e22a5c163b1112b50e22e7`。本节本次远端结果文档更新在后续仅文档提交中；它不改变实现代码。根目录 `npm run verify` 已退出 **0**。其中 Backend Surefire 692 项（691 passed、1 skipped、0 failures），Failsafe 集成 1,271/1,271；Gateway Surefire/架构 227/227、Failsafe 集成 98/98；前端 Vitest 72 个文件、547/547，ESLint 与 TypeScript/Vite build 均成功。仓库脚本 unit 27/27、lifecycle 2/2。

全量 Backend Failsafe 期间，`V22LegacyRoutingBackfillIntegrationTest` 首次启动 MySQL 8.4 时 Testcontainers 收到 Docker Engine `InternalServerErrorException: Status 500`。容器启动重试成功，该迁移用例随后 1/1 通过；容器日志未见 OOMKilled，完整 Backend `mvn verify` 用时 45:08 并成功。Gateway 全量日志有一条 Advisor Worker 后台 tick 的 JDBC `Access denied` 警告，未造成用例失败；最终 Gateway `mvn verify` 用时 5:36 并成功。定向整改测试仍为 14/14 通过。本机没有运行浏览器；同一实现提交的 CI 完整 Compose `browser-e2e` 为 17/17 通过。

本次实现提交 `b5d3a5d` 对应 GitHub [CI 运行 39217437738](https://github.com/BangShou1st/AI-CostOps/actions/runs/39217437738)：**14/14 作业成功**，Backend Integration 1,271/1,271、`browser-e2e` 17/17、Docker Build 成功。对应 [Security 运行 39217437795](https://github.com/BangShou1st/AI-CostOps/actions/runs/39217437795)：**3/3 作业成功**（CodeQL Java/Kotlin、CodeQL JavaScript/TypeScript、Trivy）。

结论：**本轮模型可见性边界整改 PASS**。Routing Options 不再返回外组织私有 Provider Model；Pricing Version 对不可见模型与不存在 ID 返回相同的 404 `RESOURCE_NOT_FOUND`，可见不兼容模型仍返回既有 400，合法模型仍可创建 Pricing Version。路由候选写入越权未复现，拒绝后资源状态不变。就本轮覆盖的查询、定价创建及路由写入边界，未发现剩余跨组织问题。前文历史证据与原生产认证文档的 `DEFERRED / NON-BLOCKING` 状态均未倒改。
