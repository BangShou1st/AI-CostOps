# 2026-10-16 OpenCode V1 Zen / V2 Console 端点差异独立复核与最小修复

基线：`codex/history-coverage-20261012@31567ff55d3a7e73825dadd472aa0d2f20c027c4`（本地 = 上批已推送 HEAD）。
上游记录：`docs/03-acceptance/2026-10-16-opencode-zen-endpoint-compatibility-maintenance.md`（下称「上批记录」）。
本地参考代理：`D:\OpenCode-UA-Start\opencode-zen-proxy.py`（**只读**，未修改、未移动、未删除、未复制进仓库）。

本批**零生产 URL / 协议 / 认证 / 计费改动**：新增 1 个契约测试（Backend 5 项）+ 本记录 + 对上批记录的**更正说明**。
未跟踪的 `.freebuff/` 原样保留；无 `reset --hard` / `git clean` / 强制推送；未建 PR、未合并 main、未部署。

## 1. 本批结论（先给结果）

1. **V1 与 V2 是两个并存的官方产品族**，不是「官方 vs 代理专有」的关系。上批记录把 `/inference` 体系称为「代理专有」，
   **该表述不准确，本批更正**（见 §7）。V2 是 **OpenCode 官方 Console**（服务账号密钥 + 按量付费/Console 模型），
   不是第三方中转。
2. **V1 Zen 仍在服务，未失效**：`https://opencode.ai/zen/v1/*` 路由在 2026-10-16 仍指向存活处理器（§2 实测）。
   V1 文档页 `https://docs.opencode.ai/docs/zen/` 已 **302 重定向**到 `https://opencode.ai/v2/docs/console/models/`，
   这是**文档改版/合并**，**不构成** V1 端点退役证据。不得据此假定旧接口失效。
3. **`/inference/v1/chat/completions`（Python 代理实际请求的路径）与官方 V2 `/inference/openai/v1/chat/completions`
   是两个不同路径，不得等同**（要求 #5）。但**两者都在服务**：官方文档未记载的 `inference/v1` 组合同样命中存活处理器（§2）。
   因此正确结论是「**代理不是 V2 证据来源**」，而不是「代理路径不存在」。
4. **生产代码是否需要改动：不需要。** 唯一存在真实问题的位置是**测试**里的 `assertFalse(baseUrl.contains("inference"))`
   —— 它把官方 V2 主机路径段当成「非法规避」，会**误导后续维护者**并永久阻止 V2 模板落地。已修正该断言的**理由与命名**，
   行为仍为「内置模板必须留在 V1 Zen」，未放宽任何约束。
5. **判定：继续只支持 V1（现状），不在本批引入 V2 模板。** V2 与 V1 的**凭证产品不同**（§4），
   按同一 template code 原地改 base 会**静默混用凭证产品**；引入 V2 必须是**新的、独立版本化模板**（见 §6 决策与 §9 未决项）。
6. 未按 `-free` 后缀推断免费；未放宽 SSRF / DIRECT_ONLY / 凭证保护 / 协议准入 / 财务计量 / 账期事务。

## 2. 端点存活实测证据（只读，未发起任何可计费推理）

**方法**：记录型探针。用**故意无效**的密钥 `Bearer oc_sk_invalid_probe_0000` 与空/占位 body 发起请求，
并**同时**探测同前缀下的**伪造路径**作对照。只有「真实路径 ≠ 伪造路径」的差异才可作为路由存在性证据，
避免把「前缀兜底鉴权中间件」误读为「端点存在」。**没有任何请求携带真实凭证，也没有任何请求可能被计费。**

### 2.1 路由判定矩阵

| 探针（POST，无效密钥） | 状态码 | 判定 |
| --- | --- | --- |
| `/zen/v1/chat/completions` | **400** | 路由存活（进入模型级处理器） |
| `/inference/openai/v1/chat/completions` | **400** | 路由存活（官方 V2 Chat） |
| `/inference/v1/chat/completions` | **400** | 路由存活（**未见于官方文档**） |
| `/inference/openai/v1/responses` | 401 `Invalid credential` | 路由存活 |
| `/zen/v1/responses` | 401 `Invalid credential` | 路由存活 |
| `/inference/anthropic/v1/messages` | 401 `Invalid credential`（Anthropic 形错误体） | 路由存活 |
| `/zen/v1/messages` | 401 `Missing API key.`（Anthropic `AuthError`） | 路由存活 |
| `/zen/v1/systemone` | 401 `Invalid credential` | 路由存活 |
| **对照** `/inference/v1/foobar` | **404** | 伪造路径被拒 |
| **对照** `/zen/v1/foobar` | **404** | 伪造路径被拒 |
| **对照** `/inference/openai/v1/foobar` | **404** | 伪造路径被拒 |
| **对照** `/inference/nonexistent/v9/foo` | **404** | 伪造路径被拒 |

> 对照全部 404 而真实路径全部进入处理器，证明上表 400/401 **是路由级差异**，不是前缀兜底。
> `400`（进入 handler 后模型/字段校验失败）是最强证据：说明该路径**确实存在**。

### 2.2 目录（模型列表）路径

| GET | 结果 | 说明 |
| --- | --- | --- |
| `/zen/v1/models` | 200，7308 字节，87 个模型 | V1 目录，**存活** |
| `/inference/v1/models` | 200，**7308 字节，87 个模型（与 V1 完全同体）** | 官方 V2 目录（文档记载） |
| `/inference/openai/v1/models` | 404 | OpenAI 家族前缀**不含**目录 |
| `/inference/anthropic/v1/models` | 404 | 同上 |
| `/inference/systemone/v1/models` | 404 | 同上 |

**重要判据**：V2 目录（官方记载）与 V1 目录（上批冻结）返回**字节级相同的 87 模型清单**，
说明二者当前同源；**不能**用「目录内容不同」来推断凭证产品的差异，产品差异只能依据 §3 的官方认证文档。

> 另注：87 个 ID 中含 `test`、`claude-opus-5-5-backup` 等非产品化条目，说明该目录是**内部运营目录**，
> 不宜按 ID 全量生成生产模型清单（见 §9 未决项 M-2）。

### 2.3 文档 URL 状态

| URL | 结果 |
| --- | --- |
| `https://docs.opencode.ai/docs/zen/` | **404**（站点已重构；`docs.opencode.ai` 无 `/docs/zen`） |
| `https://opencode.ai/docs/zen/` | **302** → `https://opencode.ai/v2/docs/console/models/` |
| `https://opencode.ai/docs/{pt-br,zh-cn,ja,it,fr}/zen/` | 均 **302** → `https://opencode.ai/v2/docs/console/models/` |
| `https://opencode.ai/v2/docs/console/models/` | 200（现行权威） |
| `https://opencode.ai/v2/docs/console/api/inference/` | 200（现行权威） |
| 源码 `packages/web/src/content/docs/zen.mdx` | **404**（V1 文档源已移除） |
| 源码 `services/www/src/docs/content/console/models.mdx` | 200（V2 文档源） |

**结论**：V1 **文档**已被 V2 取代并重定向；V1 **端点**仍存活（§2.1）。二者必须分开陈述。

## 3. 官方证据（核实时间 2026-10-16，Asia/Shanghai）

| # | 事实 | 来源 |
| --- | --- | --- |
| E1 | V2 Console Chat Completions = `https://opencode.ai/inference/openai/v1/chat/completions` | `https://opencode.ai/v2/docs/console/api/inference`（Body 原文 curl 示例） |
| E2 | V2 四族路径：`/inference/openai/v1/chat/completions`、`/inference/openai/v1/responses`、`/inference/anthropic/v1/messages`、`/inference/google/v1beta/models/<model>:generateContent` | 同上「Endpoints」表 |
| E3 | V2 目录 = `https://opencode.ai/inference/v1/models` | `https://opencode.ai/v2/docs/console/models`（「Model list」） |
| E4 | V2 认证 = **服务账号密钥** `oc_sk_...`，`Authorization: Bearer oc_sk_...`；付费模型必须有该头，免费 Chat 模型可无头调用 | `https://opencode.ai/v2/docs/console/api`（「Service accounts」）+ E1（「Authentication」） |
| E5 | V2 Console 是**按量付费**gateway（「You are charged per request and can add credits」），$5 自动充值 $20，可按工作区/成员设月度上限 | `https://opencode.ai/v2/docs/console/models` |
| E6 | V2 密钥**绑定单一工作区**，鉴权不需 workspace 头；权限 `Inference only` / `All`；吊销后立即 401 | `https://opencode.ai/v2/docs/console/api` |
| E7 | V2 官方示例引用 **Jev** 端点 `https://opencode.ai/inference/systemone/v1/systemone` | `https://opencode.ai/v2/docs/console/models`（「Jev」） |
| E8 | V2 配置面板下发的 provider baseURL = `https://opencode.ai/inference/openai/v1` | `https://opencode.ai/v2/docs/console/api`（「Workspace config」示例 JSON） |
| E9 | V1 Zen base = `https://opencode.ai/zen/v1/`；家族 `/responses`、`/messages`、`/systemone`、目录 `/models` | V1 文档（镜像站 `https://open-code.ai/en/docs/zen`，其站点导航即 `<Provider>/docs/zen`；另 `pi-other-provider` 发布文档记 `https://opencode.ai/zen/v1/{responses,messages,chat/completions}`） |
| E10 | V1 文档 URL 现 302 至 V2（文档合并）；V1 文档源已从仓库移除 | §2.3 实测 |
| E11 | 存在第三个官方产品 **OpenCode Go**（订阅制），端点为 `https://opencode.ai/inference/go/...`，与 Console 按量付费**记账方式不同** | `https://opencode.ai/v2/docs/console/go` |
| E12 | 官方对第三方客户端要求：自称 UA（如 `my-coding-agent/1.0`）、并以 `x-opencode-session` 传稳定会话 ID（**Go 文档明示**） | 同上「Where can I use it?」 |

> 证据强度声明：E1/E2/E3/E4/E6/E7/E8/E11/E12 取自 opencode.ai 官方站点原文（本批直接抓取，HTTP 200）。
> E9 因 V1 官方页已重定向，取自**镜像站**与独立发布的 V1 端点文档，**非 opencode.ai 原文**——强度弱于 V2 证据，
> 但 V1 端点存活已由 §2.1 的**实测**独立佐证，结论不依赖 E9 的弱证据。

## 4. 三方端点差异表（V1 Zen / V2 Console / 本地 Python 代理）

| # | 维度 | V1 Zen（官方，项目现用） | V2 Console（官方，新版） | Python 代理（只读参考） | 判定 |
| --- | --- | --- | --- | --- | --- |
| 1 | 推理 base | `https://opencode.ai/zen/v1`（E9） | `https://opencode.ai/inference`（E2） | `https://opencode.ai/inference/v1`（`:32`） | 三者基址不同 |
| 2 | Chat 完整路径 | `/zen/v1/chat/completions`（E9，§2.1 存活） | `/inference/openai/v1/chat/completions`（E1，§2.1 存活） | `/inference/v1/chat/completions`（`BASE+PATHS["chat"]`，`:32`+`:44`） | **三路径互不相同** |
| 3 | 路径构造方式 | base 含 `/v1`，家族为**裸后缀** | base 不含 `/v1`，家族为 **`/openai`、`/anthropic`、`/google`、`/systemone` 前缀 + `/v1`** | base 已含 `/v1`，**直接拼 `/chat/completions`**（无家族段） | **V1 与 V2 是两种不同拼接模型** |
| 4 | 目录 | `/zen/v1/models`（§2.2） | `/inference/v1/models`（E3，§2.2） | `CATALOG_URL=https://models.opencode.ai/api.json`（`:33`，**第三方目录站**） | V2 目录**不在**家族前缀下 |
| 5 | 其他协议族 | `/zen/v1/responses`、`/zen/v1/messages`、`/zen/v1/systemone`（E9，§2.1 均存活） | `/inference/openai/v1/responses`、`/inference/anthropic/v1/messages`、`/inference/google/v1beta/...`、`/inference/systemone/v1/systemone`（E2、E7） | `/responses`、`/messages`、systemone 重封装（`:44`、`:54`） | 家族集合相近，**前缀模型不同** |
| 6 | 认证凭证 | Zen API Key（`Authorization: Bearer <key>`），项目按 `BEARER_TOKEN` 严格校验（`OpenCodeZenChatAdapter:54-60`） | **Console 服务账号密钥** `oc_sk_...`（E4、E6），绑定单一工作区 | 匿名 `Bearer public`（`:810`）/ `x-api-key: public`（`:805`） | **V1 与 V2 凭证产品不同**（见 §5） |
| 7 | 账户产品 | Zen 按量付费（Console 按量付费的前身） | Console 按量付费 gateway，$5 自动充值、月度上限（E5） | 免费模型代理（本地借用，无账户） | 产品边界不同 |
| 8 | 计费边界 | 按请求/按量，项目按 manifest 分类（`PAID`/`VERIFIED_FREE`/`UNKNOWN`） | 按请求按量，**免费 Chat 模型可无凭证调用**（E4） | 按 `-free` 后缀推断免费（`:113-124`） | 项目**禁止**按后缀推断免费（保持） |
| 9 | 第三产品 Go | — | Go 订阅端点 `/inference/go/...`，记账方式与 Console 不同（E11） | 未涉及 | 本批**不引入** Go 语义 |
| 10 | UA 与会话头 | 官方不规定；项目自有 `opencode/1.18.21`（server-owned） | 官方**要求**第三方自称 UA 并以 `x-opencode-session` 传会话 ID（E12） | `opencode/1.19.0 ai-sdk/...` + `x-opencode-client/project/session(-id)/request`（`:39-43`、`:799-803`） | **见 §9 未决项 R-1**（非本批结论） |

### 4.1 关于要求 #5 的专门判定

- Python 代理请求 `/inference/v1/chat/completions`；官方 V2 文档记载 `/inference/openai/v1/chat/completions`。
- 二者**不是同一表达式**：代理缺 `/openai` 家族段，且 `/v1` 位置不同。
- 实测（§2.1）两者**都返回 400**，说明**都存在存活处理器**。
- 但 V2 官方文档**从未**记载代理所用的无家族前缀推理路径；该组合**不是**官方 V2 协议证据。
- 因此：**不得**用代理路径替代官方 V2 路径，**也不得**反过来宣称代理路径不存在或已失效。

## 5. 凭证与产品边界（要求 #3、#9）

- **V1 Zen 密钥与 V2 Console 服务账号密钥是不同产品、不同签发体系的凭证**：
  V2 密钥形如 `oc_sk_...`，绑定单一工作区，权限模型为 `Inference only` / `All`，吊销即 401（E4、E6）；
  `Workspace config` 中该凭证注入到 provider env `OPENCODE_CONSOLE_TOKEN`（E8）。
- **官方未声明** V1 Zen 密钥可用于 `/inference` 推理族。**故禁止混用**（要求 #9）。
- 项目当前**只**持有 V1 语义（单一 `BEARER` + `OPENCODE_ZEN` 账户 + `https://opencode.ai/zen/v1`），
  **不存在** V2 凭证的存放、账户或模板，因此**不存在已经被错误混用的现状**。
- 本批**未**新增任何凭证类型、账户映射或模板，也**未**引入代理的匿名 `Bearer public`。

## 6. 生产代码审查与决策（要求 #6、#7）

### 6.1 审查结果

| 组件 | 文件 | 结论 |
| --- | --- | --- |
| ProviderTemplateRegistry | `ProviderTemplateRegistry.java:21-32` | 内置模板 = `https://opencode.ai/zen/v1` + `/chat/completions` + `/models`；与 V1 官方一致，**无需改** |
| OpenCodeModelManifest | `OpenCodeModelManifest.java` | 协议/定价分类表，与端点族选择解耦；未知模型 fail closed。**与本次端点结论无冲突，无需改**（漂移见 §9 M-2） |
| Gateway Adapter | `OpenCodeZenChatAdapter.java:33,54-60` | `DIRECT_ONLY` + 仅接受 `BEARER_TOKEN`；`context.baseUrl() + path` 组合（`AbstractOpenAiCompatibleAdapter`）。**无需改** |
| 连接配置 | `ProviderConnectionService.java:121-123,153-167` | 内置模板字段 server-owned，浏览器不可覆盖；`rejectBuiltinOverride`。**无需改** |
| DB seed | `V24__m18_provider_hub.sql:41` | `OPENCODE_ZEN` 行 base = `zen/v1`，与模板一致。**无需改** |
| 契约测试 | `OpenCodeZenEndpointContractTest.java:44-52` | **唯一需修正项**：以 `contains("inference")` 作为「反代理」断言，把官方 V2 主机路径段污名化为非法规避。见 §6.3 |

### 6.2 决策矩阵（要求 #7）

| 选项 | 评价 |
| --- | --- |
| **A. 继续只支持 V1** | ✅ **本批选择**。V1 端点存活（§2.1），项目现状与之逐字一致，且无 V2 服务账号凭证可用；零风险、零凭证混用 |
| B. 原地把 `OPENCODE_ZEN.baseUrl` 改为 `/inference` | ❌ **禁止**。会**静默混用** V1 密钥与 V2 服务账号产品（§5）；且会改变可选模型族与计费边界，属破坏性变更 |
| C. 新增独立版本化 V2 模板（如 `OPENCODE_CONSOLE_V2`） | ⏸ **本批不做**，但**这是引入 V2 的唯一正确路径**。它需要新的凭证类型/账户映射/协议准入/契约测试，且**必须有真实 V2 服务账号密钥**才能验收；无凭证时只能做无凭证（仅 free chat / 401 路径）的半验收，收益不足（§9 M-1） |
| D. 经验证兼容迁移 | ❌ 不适用。V1 未失效，**不存在**需要迁移的失效旧接口；无证据支持迁移 |

### 6.3 本批唯一代码改动（最小、留痕）

| 文件 | 改动 | 理由 |
| --- | --- | --- |
| `backend/.../OpenCodeV1V2EndpointGovernanceTest.java` | **新增**，5 项 | 冻结「V1↔V2 互不别名、无模板指向 V2 族、V2 需独立 template code、OpenCode 凭证保持真实 `BEARER`」四项治理不变量 |
| `backend/.../OpenCodeZenEndpointContractTest.java` | **仅改注释与断言消息**，`assertFalse(baseUrl.contains("inference"))` → `assertFalse(baseUrl.contains("/inference"))` 并改正措辞 | 原措辞把官方 V2 体系称为「unofficial proxy-only relay」，与 §3 官方证据冲突，会误导后续维护者并永久阻止 V2 模板落地。**断言强度未变**（内置模板仍必须在 V1 主机） |
| `gateway/.../OpenCodeEndpointContractTest.java` | **仅改类注释** | 同上，同步更正「官方 V2 = `/inference/openai/v1`」的表述 |

**未改**：任何生产 URL、协议准入、认证类型、超时、网络策略、模型清单、定价分类、SQL 迁移。

## 7. 对上批记录的更正说明（不重写历史）

上批记录原文**全部保留**，**未改写、未删除**任何原有句子；仅在下列位置**追加**「⚠️ 更正」标注（可逐条 `git diff` 核对）。
另在文首插入一条**更正声明**，指向本记录。

| 上批记录位置 | 原表述（摘） | 本批追加的更正 |
| --- | --- | --- |
| 文首（新插入） | — | 更正声明：`/inference` 非代理专有；并声明**被推翻的仅此一句**，其余结论成立 |
| §1（行 12-13） | Python 代理用的是「另一套、**代理专有**的中转 base `https://opencode.ai/inference/v1`」，「不属于 OpenCode 官方协议证据」 | ⚠️ 追加：`/inference` **主机路径族本身是官方 V2 Console 体系**；代理专有的是**匿名凭证**与**无家族前缀拼接方式**；V1 端点实测仍存活 |
| §3.1 #1 | 「`inference/v1` 是代理专有中转 base，非官方协议证据」 | ⚠️ 追加：官方另有 V2 Console 体系 `https://opencode.ai/inference`（家族前缀 `/openai`、`/anthropic`、`/google`、`/systemone`）；**不替换的结论不变** |
| §3.2 第 1 条 | 「代理专有 base `https://opencode.ai/inference/v1`」 | ⚠️ 追加：`inference` 族为官方 |
| §4 文档条目 | 文档 URL `https://docs.opencode.ai/docs/zen/` | ⚠️ 追加：该 URL 现 **404**；`https://opencode.ai/docs/zen/` 现 **302** 至 `/v2/docs/console/models/`；**文档合并 ≠ 端点退役**；并补入 V2 端点族与 `oc_sk_...` 凭证 |
| §9 | 「未验证**代理专有** `inference/v1` 中转 base 的可用性/语义」 | ⚠️ 追加实测结论：该组合**存在存活处理器**（400 vs 伪造路径 404），但**未记载于官方文档** |

**未被推翻**的部分（仍然成立）：内置模板 URL 正确、`joinBase` 无重复 `/v1`、协议分流 fail closed、
`DIRECT_ONLY`、`Bearer <secret>`、不复制代理的匿名凭证 / 会话头 / 占位工具 / `-free` 推断 / 回退代理 / 中转架构。

## 8. 测试证据

### 8.1 定向测试（真实执行）

| 模块 | 命令 | 结果 | 退出码 |
| --- | --- | --- | --- |
| Backend（基线，改动前） | `.\mvnw.cmd -B test "-Dtest=OpenCodeZenEndpointContractTest,OpenCodeProductionManifestTest,M18TransportRegressionTest" "-Dsurefire.failIfNoSpecifiedTests=false"` | Tests run: **19**, 0F/0E | **0** |
| Backend（含新增） | `.\mvnw.cmd -B test "-Dtest=OpenCodeV1V2EndpointGovernanceTest,OpenCodeZenEndpointContractTest,OpenCodeProductionManifestTest,M18TransportRegressionTest" "-Dsurefire.failIfNoSpecifiedTests=false"` | Tests run: **24**, 0F/0E（含新增 5 项） | **0** |
| Backend（集成，真实 MySQL 8.4 Testcontainers） | `.\mvnw.cmd -B test-compile failsafe:integration-test failsafe:verify "-Dit.test=OpenCodeManifestIntegrationTest,V24ProviderHubMigrationIntegrationTest" "-Dfailsafe.failIfNoSpecifiedTests=false"` | Tests run: **17**, 0F/0E | **0** |
| Gateway | `.\mvnw.cmd -B test "-Dtest=OpenCodeEndpointContractTest,OpenCodeDirectOnlyTest,PublicOnlyResolverTest" "-Dsurefire.failIfNoSpecifiedTests=false"` | Tests run: **9**, 0F/0E | **0** |

### 8.2 V1 旧契约仍正确（要求）

`OpenCodeZenEndpointContractTest` 5 项 + `OpenCodeProductionManifestTest` 8 项 + `M18TransportRegressionTest` 6 项
在改动后**仍全部通过**，证明 V1 地址、路径组合、UA、凭证类型与协议准入未被本批放宽。

### 8.3 V2 支持验证情况（如实说明）

本批**未新增 V2 支持**，故**无** V2 普通/流式/Usage/fail-closed 的产品级验收。
理由是缺少可用的 V2 服务账号密钥，且无凭证时只能覆盖「免费 Chat 模型」与「401 路径」，无法验证付费模型的
Usage 计量与 fail-closed —— 半验收不足以支撑生产模板（§6.2 选项 C）。V2 的**无凭证**可用性已由 §2 探针覆盖。

### 8.4 全量验证

| 范围 | 命令 | 结果 | 退出码 |
| --- | --- | --- | --- |
| 根目录全量 | `npm run verify`（真实 Testcontainers：MySQL 8.4 / Redis / MinIO；串行） | 3 次运行：Maven 与脚本阶段全绿；Frontend/脚本阶段出现**环境超时抖动** | **1**（详见 §8.5；分阶段独立复跑全为 0） |

### 8.5 根目录 `npm run verify` 明细

命令（仓库根）：`npm run verify`；日志：`.local-dev/reviews/2026-10-16-opencode-v1v2/root-npm-verify.log`。

本批 `npm run verify` **执行了 3 次**，如实记录如下（**未删除/跳过任何测试、未放宽断言、未延长超时**）。

**第 1 次**：脚本阶段通过；Backend surefire `664`、Backend failsafe `1242`、Gateway surefire `177`、Gateway failsafe `86` 全部 0F/0E，
两次 Maven `BUILD SUCCESS`；**Frontend 阶段失败**（`ExpensePages.test.tsx` 1 项 `Test timed out in 10000ms`）→ 总退出码 `1`。

**第 2 次**：**脚本阶段失败**（`processes.test.mjs` 的 `Windows background batch keeps owned console windows hidden`
断言 `2 !== 0`，即"后台批处理弹出了自有控制台窗口"）→ 提前终止，退出码 `1`。

**第 3 次**：脚本 `24/24`、生命周期 `2/2`、Backend surefire `664`、Backend failsafe `1242`、Gateway surefire `177`、
Gateway failsafe `86` 全部 0F/0E，两次 Maven `BUILD SUCCESS`；**Frontend 失败 3 项**（均为 `Test timed out`：
`ExpensePages.test.tsx`、`ImportWorkflow.test.tsx`、`ReconciliationPages.test.tsx`）→ 退出码 `1`。

### 对上述失败的定性（已独立复现，非本批缺陷）

| 失败项 | 定性证据 |
| --- | --- |
| 3 项 Frontend `Test timed out` | 单独运行 **`npx vitest run` → `Test Files 72 passed (72)` / `Tests 547 passed (547)`，退出码 0**。失败文件（expenses / imports / reconciliation）**本批零改动**（`git status` 显示本批只动 backend/gateway 测试与文档，`frontend/` 改动数 = 0）。失败形态均为**超时**（该仓库为 AntD + React Query 重型渲染，测试自带 5s/10s 预算），与机器负载相关 |
| `processes.test.mjs` 控制台窗口断言 | 单独运行 **`node --test scripts/tests/processes.test.mjs` → `pass 9` / `fail 0`**，退出码 0 |
| `ImportAttemptExecutorIntegrationTest` 曾挂起 35min+ | jstack 显示阻塞在 `tearDown` 的 MySQL `readFully`；原因是 **Docker 引擎 API 降级（HTTP 500）**。重启 Docker Desktop 后，同一测试在本批第 3 次运行中 **正常完成**（Backend failsafe 全绿） |

**根因**：本机同时运行 Trae CN / Edge 等重负载进程，叠加并行 verify 与探针轮询，导致**超时型**抖动。
三项均已在**同一代码树**上单独复跑通过，故不构成本批回归。

### 分阶段独立复跑结果（同一代码树，退出码为证）

| 阶段 | 命令 | 结果 | 退出码 |
| --- | --- | --- | --- |
| 脚本单测 | `node --test scripts/tests/*.test.mjs` | `tests 24` / `pass 24` / `fail 0` | **0** |
| Frontend test | `npx vitest run` | **72 文件 / 547 项全部通过** | **0** |
| Frontend lint | `npx eslint .` | 无输出 | **0** |
| Frontend production build | `npm run build` | `✓ built in 384ms` | **0** |
| Backend surefire（verify 内） | Maven `verify` | **664** 项，0F/0E，1 skipped（Windows 平台） | **0** |
| Backend failsafe（verify 内） | Maven `verify` | **1242** 项，0F/0E | **0** |
| Gateway surefire（verify 内） | Maven `verify` | **177** 项，0F/0E | **0** |
| Gateway failsafe（verify 内） | Maven `verify` | **86** 项，0F/0E | **0** |

> 说明：Backend surefire 由上一批的 659 项增至 **664** 项，正是本批新增的 `OpenCodeV1V2EndpointGovernanceTest`（5 项）。
> Gateway 177 项与上一批一致（本批只改注释，未改断言）。

全程真实 Testcontainers（MySQL 8.4 / Redis / MinIO），未绕过 Docker 检查；未删除失败测试、未放宽断言、未延长超时。
**总退出码如实记录为 `1`（3 次运行均因环境超时抖动而非本批缺陷）**；分阶段独立复跑全部 `0`，见上表。

## 9. 未解决 / 未验证项

| # | 项 | 状态 |
| --- | --- | --- |
| M-1 | **是否引入 V2 Console 模板** | **未决**。仅 V1 版本化模板支持 V2（§6.2 选项 C），需真实 `oc_sk_...` 服务账号密钥才能完整验收；建议作为**独立任务**（含新凭证类型、账户映射、协议准入、契约测试） |
| M-2 | **Manifest 与官方模型目录漂移** | **✅ 已在本批之后的 M-2 专项批次修复**（2026-10-16）。实测（2026-10-16）：官方目录 87 个模型；manifest 共 61 条（CHAT 20 / UNSUPPORTED 41）。官方新增而 manifest 未收录 **35** 个（含 `deepseek-v4.1-flash`、`glm-5.3`、`grok-4.6/4.7`、`qwen3.8-*`、`mistral-large-4` 等），这些**一律 fail closed 为 UNKNOWN**（安全，但不能探针/晋级）；manifest 中 CHAT 条目有 **7** 个已不在官方目录（`mimo-v2.5-free`、`laguna-s-2.1-free`、`ling-3.0-tiny-free`、`longcat-2.0-free`、`north-mini-code-free`、`nemotron-3-ultra-free`、`deepseek-v4-flash-free`）。本批**不改**：批量刷新会同时改动**定价分类**（财务语义）与协议准入，且需逐模型官方依据，属独立变更 <br>⚠️ **后续进展**：已由 `docs/03-acceptance/2026-10-16-opencode-manifest-drift-repair.md` 定向修复。该批次采用「官方 V1 Zen 端点行 + 官方 V1 定价行」**双证据**规则，仅新增 **9** 条（5 `PAID` + 4 `VERIFIED_FREE`），清单前移至 `2026-10-16-v1`（70 条 = 29 Chat + 41 UNSUPPORTED）；**未删任何条目**、未改任何既有分类、未改动定价版本。V2 Console 独有（仅其免费）的 5 条（含 `deepseek-v4.1-flash-free`）被**明确拒绝**纳入。
| R-1 | **官方要求第三方客户端传 `x-opencode-session`**（E12，Go 文档明示） | **未决**。项目当前**不发送**该头；是否补发涉及「会话模拟」与隐私边界，需独立评估（本批不动） |
| R-2 | `inference/v1`（无家族前缀）的**官方定位** | **未决**。实测存活但官方未记载；可能是兼容别名或过渡路由，**证据不足以下结论** |
| R-3 | V1 官方文档原文 | 当前**不可得**（404/302）。本批 V1 端点的官方性依据为镜像站 + 独立发布文档（E9，弱证据）+ §2.1 实测（强证据） |
| R-4 | 真实网络付费调用 / 真实 V2 联调 / 远程 CI | **未执行**（默认禁止付费请求） |

## 10. 保留不变的安全与财务约束

- **SSRF / DNS 防护**：`PublicOnlyAddressResolverGroup`、`DispatchEndpointGuard`、`CustomEndpointValidator` 未放宽。
- **重定向校验**：`followRedirect(false)` + 逐跳同源校验，未放宽。
- **`DIRECT_ONLY` / 凭证保护**：`OpenCodeZenChatAdapter` 仍 `noProxy()` 且仅接受 `BEARER_TOKEN`；**未**引入代理回退或匿名凭证。
- **协议准入**：Responses / Messages / Gemini 原生 / SystemOne 仍 fail closed；未知模型仍 `UNKNOWN`。
- **财务正确性**：未按 `-free` 后缀推断免费；未改动 pricing/manifest 计费分类；未因探针响应改动计量。
- **事务契约**：未触碰派发、用量事实、Settlement、Ledger、Budget、Close 任何事务契约。

## 11. 提交与推送

- 起始 HEAD：`31567ff55d3a7e73825dadd472aa0d2f20c027c4`
- 目标分支：`codex/history-coverage-20261012`，**非强制**推送；未建 PR、未合并 main、未部署。
- 本批内容：新增 `OpenCodeV1V2EndpointGovernanceTest`（5 项）、两处测试注释/断言措辞更正、上批记录更正标注、本记录。

<!-- COMMITS -->

（沿用本目录既有约定：验收文档自身的提交不写死在其文内。）
