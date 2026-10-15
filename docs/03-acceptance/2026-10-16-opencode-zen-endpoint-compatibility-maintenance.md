# 2026-10-16 OpenCode Zen 接入兼容性维护（对照本地代理）

基线：`codex/history-coverage-20261012@7a14d717f87f9916af8058bd1ad8c1672846344a`（本地 = 远端）。
本批**零生产 URL / 协议 / 模型改动**：只新增 OpenCode 端点契约测试（Gateway 3 项 + Backend 5 项）与本记录。
未跟踪的 `.freebuff/` 原样保留；无 `reset --hard` / `git clean` / 强制推送；未建 PR、未合并 main、未部署、未调用真实付费 OpenCode Provider。
本批不改动上一批 Gateway 403 修复提交（`3244dd3` / `4f2d389`）及其测试。

## 1. 本批结论（先给结果）

- **项目当前 OpenCode URL 是正确的，不替换**。项目 `base_url = https://opencode.ai/zen/v1` + `completion_path = /chat/completions`
  = `https://opencode.ai/zen/v1/chat/completions`，与官方文档逐字一致。
- **本地 Python 代理用的是另一套、代理专有的中转 base**：`https://opencode.ai/inference/v1`（见 §3.1）。它是社区第三方
  「Zen Free」代理，**不属于** OpenCode 官方协议证据，**不得**据此替换项目常量。
- 项目中**未发现需修复的请求地址/路径/重复 `/v1`/多段拼接缺陷**；`/models` 与 `/chat/completions` 均按「base 已含 `/v1`」
  组合，无重复 `/v1`。
- 项目的**协议分流与失败兜底已经正确**：只有 Chat Completions 家族可探针/晋级；Responses、Messages、Gemini 原生、
  System One（Jev）在受控清单中为 `UNSUPPORTED`，未知模型为 `UNKNOWN`，均 **fail closed**。
- 因此本批**不产生生产源码改动**，只以**契约测试**把该事实固化，避免后续被代理 URL 误替换。

## 2. Git 基线与边界

| 项 | 值 |
| --- | --- |
| 起始 HEAD | `7a14d717f87f9916af8058bd1ad8c1672846344a`（`docs(acceptance): record gateway intermittent 403 root cause and fixture fix`） |
| 目标分支 | `codex/history-coverage-20261012` |
| 远端 HEAD（开始前） | `7a14d717f87f9916af8058bd1ad8c1672846344a`（本地与远端一致） |
| 未跟踪/受保护内容 | `.freebuff/`（未触碰） |
| 危险操作 | 未执行 `reset --hard` / `git clean` / 强制推送 |

## 3. Python 参考实现与 Java 项目差异矩阵

参考文件：`D:\OpenCode-UA-Start\opencode-zen-proxy.py`（**只读**，未修改、未移动、未删除、未整体复制进仓库）。
该文件为社区「Zen Free」第三方本地代理（其头部注释明确声明 unofficial，基于 `magpie-community/plugins` 的 `zen-free`），
内含 `Bearer public` 匿名鉴权、`x-opencode-session*` 会话模拟、`systemone`/多协议重封装等**代理专有行为**。

### 3.1 差异表

| # | 维度 | Python 代理（文件:行） | Java 项目（文件:行） | 是否需要改 | 判定与理由 |
| --- | --- | --- | --- | --- | --- |
| 1 | 上游域名 / Base URL | `BASE = "https://opencode.ai/inference/v1"`（`opencode-zen-proxy.py:32`） | `OPENCODE_ZEN_BASE_URL = "https://opencode.ai/zen/v1"`（`ProviderTemplateRegistry.java:21`）；DB seed 同值（`V24__m18_provider_hub.sql:41`） | **否** | 官方文档端点族均为 `https://opencode.ai/zen/v1/...`，项目已与官方一致；`inference/v1` 是代理专有中转 base，非官方协议证据，**不替换** |
| 2 | HTTP Method 与完整路径 | `POST` + `PATHS["chat"]="/chat/completions"`（`:44`、`:811`）→ `/inference/v1/chat/completions` | `POST` + `targetUrl = base + completionPath`（`AbstractOpenAiCompatibleAdapter.java:152-157`）→ `/zen/v1/chat/completions` | **否** | 方法/后缀一致；差异只在 base，见 #1；Gateway 组合逻辑无重复 `/v1`（契约测试固化） |
| 3 | 按模型的端点/协议族 | 三族 + 原生：chat `/chat/completions`、responses `/responses`、anthropic `/messages`（`:44`）；`systemone`（`:54`、`:181`） | 仅 `OPENAI_CHAT_COMPLETIONS` 走 Chat 适配器；Responses/Messages/Gemini/SystemOne 在清单中 `UNSUPPORTED`（`OpenCodeModelManifest.java:56-96`） | **否**（仅补测试） | 项目能力边界是**有意**的（M17/M18 冻结）：不具备 Responses/Messages/Gemini 原生实现，故一律 fail closed，绝不按 Chat 复用 |
| 4 | Bearer / API Key 传递 | 匿名 `Authorization: Bearer public`（`:810`）；anthropic 用 `x-api-key: public`（`:805`） | 连接配置的 `BEARER` 凭证（真实密钥）；`Authorization: Bearer <secret>`（`AbstractOpenAiCompatibleAdapter.java:140-145`）；内置模板仅接受 `BEARER_TOKEN`（`OpenCodeZenChatAdapter.java:54-60`） | **否** | 代理的匿名 `public` 属于绕过/未授权语义，**禁止复制**；项目保持真实凭证 + 严格凭证类型校验 |
| 5 | User-Agent 与请求头 | `opencode/1.19.0 ai-sdk/provider-utils/4.0.23 runtime/bun/1.3.14`（chat，`:39-43`）；另发 `x-opencode-client: cli`、`x-opencode-project`、`x-opencode-session`、`x-opencode-session-id`、`x-opencode-request`（`:799-810`） | 服务端自有 UA `opencode/1.18.21`（`ProviderTemplateRegistry.java:23`、`OpenCodeZenChatAdapter.java:33`）+ `X-Client-Request-Id`（`AbstractOpenAiCompatibleAdapter.java:134-149`） | **否** | UA 是**服务端自有策略**，官方文档不规定 UA；不复制代理的 UA 版本与 `x-opencode-*` 会话头（后者属会话模拟/限制规避）；跨模块 UA 一致性已有测试锁定 |
| 6 | 请求 JSON 字段与转换 | 强制 `stream=true`（`:790-791`）、chat 加 `stream_options.include_usage=true`（`:793-796`）、注入 5 个「占位工具」（`:287-305`）、改写 `session`/`metadata` | `model` / `messages` / `max_completion_tokens` / `stream`；流式加 `stream_options.include_usage=true`（`AbstractOpenAiCompatibleAdapter.java:159-168`、`OpenAiWireDtos.java:14-19`） | **否**（已对齐 include_usage） | 占位工具与 session 注入是代理为上游 schema「补形状」的 hack，禁止复制；`include_usage` 项目已具备（契约测试固化） |
| 7 | 普通响应 JSON 解析 | `_filter_json_response` 过滤/改名工具调用（`:610-648`） | 解析 `choices[].message.content` 与 `usage`（`AbstractOpenAiCompatibleAdapter.java:199-221`） | **否** | 项目解析的是标准 Chat 字段；工具名过滤属代理侧防泄漏逻辑，不在项目 Chat 契约内 |
| 8 | SSE 流 / `[DONE]` / Usage | 自建 SSE 解析与重封装；`usage` 只在 `stream_options.include_usage` 下出现（`:357-493`） | `OpenAiSseDecoder` → `Delta`/`Metering`/`Done`（`AbstractOpenAiCompatibleAdapter.java:112-128,170-197`）；`[DONE]` 终止 | **否** | 语义等价（Usage 经 `Metering` 事件进入计量）；契约测试断言流式命中同一路径且请求 `include_usage` |
| 9 | 上游错误 / 超时 / 重试 | `HTTPError` 透传状态与体（`:850-853`）；无重试；失败回退到本地代理（`:77-86`） | `ProviderExecutionException` + 健康信号分级（`AbstractOpenAiCompatibleAdapter.java:223-234,256-295`）；`followRedirect(false)`、硬/空闲超时 | **否** | 项目更严格（分类错误、拒重定向、有界超时）；代理的「失败回退再代理」属规避，禁止复制 |
| 10 | 环境变量 / 网络策略 / 路由 | `ZEN_FALLBACK_PROXY="http://127.0.0.1:7897"` 回退代收（`:35`）；无 SSRF/DNS 防护；仅本机 `127.0.0.1:18991`（`:30-31`） | `DIRECT_ONLY` + `.noProxy()`（`AbstractOpenAiCompatibleAdapter.java:63-65`、`ProviderControlPlaneTransport.java:144`）、`PublicOnlyAddressResolverGroup`（DNS 重绑定防护）、`DispatchEndpointGuard` | **否** | 代理的回退代理/匿名中转均为**必须保留在本地**的行为；项目**禁止**放宽 SSRF/DNS/重定向/凭证保护，**禁止**引入中转架构 |

### 3.2 属于「代理专有、明确不复刻」的部分（清单）

1. 代理专有 base `https://opencode.ai/inference/v1` 与 `ZEN_FALLBACK_PROXY` 回退代理（`:32`、`:35`、`:77-86`）。
2. 匿名 `Bearer public` / `x-api-key: public` 鉴权（`:805`、`:810`）。
3. 合成 `ses_…` 会话 ID 与 `x-opencode-session(-id)` / `x-opencode-project` / `x-opencode-request` 头（`:235-268`、`:799-810`）。
4. 占位工具注入（bash/edit/glob/grep/read 作为「Never call this tool」）与工具名改写告警（`:287-324`）。
5. 按 `-free` 后缀推断免费（`:113-124`）——项目**禁止**按后缀推断免费/零费用。
6. 多协议重封装（Responses/Anthropic 原生 SSE 折叠与回放，`:496-709`）——项目未实现，保持 fail closed。
7. 本机监听 `127.0.0.1:18991`（`:30-31`）与 CORS `*`（`:725`）。

> 说明：代理中若含私有 API Key/Token/Cookie、本机地址端口、账号/环境变量等，仅在本机使用；本记录与提交**均不含**任何此类内容。

## 4. OpenCode 官方文档核实

| 项 | 值 |
| --- | --- |
| 核实时间 | 2026-10-16（本地时区 Asia/Shanghai） |
| 文档 URL | `https://docs.opencode.ai/docs/zen/` |
| 关键核实结论 | 官方端点表逐条给出：Chat Completions 家族（DeepSeek V4 / MiniMax / GLM / Kimi / Big Pickle / MiMo 等）→ `https://opencode.ai/zen/v1/chat/completions`；Responses 家族（GPT-5.x / Grok / Muse Spark）→ `/zen/v1/responses`；Anthropic Messages 家族（Claude / Qwen3.x Plus·Max）→ `/zen/v1/messages`；Gemini 原生 → `/zen/v1/models/<model>`；System One（Jev）→ `/zen/v1/systemone`；模型目录 → `https://opencode.ai/zen/v1/models` |
| 与项目比对 | 项目 `base_url=https://opencode.ai/zen/v1` 与官方**同主机、同路径前缀**一致；`completion_path=/chat/completions`、`models_path=/models` 组合后与官方两条 URL 逐字一致 |
| OpenCode Go | **不适用**：本地参考代理为 Zen Free（无 Go 语义），故未查阅 `https://docs.opencode.ai/docs/go/`；项目未引入 Go 账号/路由/产品语义 |

## 5. 实际修改内容

**生产源码：无改动。** 经核对，项目 OpenCode 接入的地址、路径、协议分流、User-Agent、凭证与网络策略均已正确且比代理更严格；
`inference/v1` 仅为代理专有中转地址，依「不得为产生代码修改而盲目替换」原则**不做替换**。

**新增测试（本批唯一代码改动）：**

| 文件 | 模块 | 项数 | 覆盖点 |
| --- | --- | --- | --- |
| `gateway/src/test/java/.../provider/opencode/OpenCodeEndpointContractTest.java` | Gateway | 3 | 完整上游路径 `/zen/v1/chat/completions`（非流式 + 流式）；路径内 `/v1` 恰好一次（无重复/丢失/多段拼接）；服务端 UA；`Authorization: Bearer <secret>`；流式请求体含 `"stream":true` 与 `"include_usage":true`；流式事件含 `Delta`/`Metering`/`Done`；JVM 代理被投毒后仍零命中（DIRECT_ONLY）；`ProviderCallContext.toString()` 不泄露密钥 |
| `backend/src/test/java/.../providerhub/application/OpenCodeZenEndpointContractTest.java` | Backend | 5 | 内置模板 base/paths/protocol/network/auth 冻结值；代理专有 `inference/v1` 未被采纳；`joinBase` 组合无重复 `/v1`、无丢失、尾部斜杠/缺前导斜杠不产生多段拼接；服务端 UA 与受限字段（server-owned）；仅 Chat Completions 家族可共用 Chat，Responses/Messages/Gemini/未知一律 fail closed |

## 6. 测试、失败复现与回归

### 6.1 无「失败复现」的原因（如实记录）

本批**未发现可确认的旧版 URL 缺陷**：项目 base/路径与官方文档一致，组合无重复 `/v1`；因此**没有**「先写失败测试再修绿」的对象。
本批测试为**契约锁定测试**（新增后直接为绿），用于防止后续把代理的 `inference/v1` 误当作项目默认值，以及防止路径重复/丢失回归。
（若未来确证某部署绑定了 `inference/v1` 旧值，按 §8 的安全流程处理，届时再补失败复现。）

### 6.2 本批定向测试（真实执行）

| 模块 | 命令 | 结果 | 退出码 |
| --- | --- | --- | --- |
| Backend | `.\mvnw.cmd -B test "-Dtest=OpenCodeZenEndpointContractTest,OpenCodeProductionManifestTest,M18TransportRegressionTest" "-Dsurefire.failIfNoSpecifiedTests=false"` | Tests run: **19**, Failures: 0, Errors: 0 | **0** |
| Gateway | `.\mvnw.cmd -B test "-Dtest=OpenCodeEndpointContractTest,OpenCodeDirectOnlyTest,PublicOnlyResolverTest" "-Dsurefire.failIfNoSpecifiedTests=false"` | Tests run: **9**, Failures: 0, Errors: 0 | **0** |
| Backend（集成，真实 MySQL 8.4 Testcontainers） | `.\mvnw.cmd -B test-compile failsafe:integration-test failsafe:verify "-Dit.test=OpenCodeManifestIntegrationTest,V24ProviderHubMigrationIntegrationTest" "-Dfailsafe.failIfNoSpecifiedTests=false"` | Tests run: **17**, Failures: 0, Errors: 0 | **0** |

说明：Backend 集成运行期间出现 `gateway_settlement doesn't exist` 的 WARN，来自 `V24ProviderHubMigrationIntegrationTest`
将库只迁移到 V24 时无关的定时任务（Cost Intelligence）轮询，属**预期**噪声，非测试失败。

### 6.3 全量验证

| 范围 | 命令 | 结果 | 退出码 |
| --- | --- | --- | --- |
| 根目录全量 | `npm run verify`（真实 Testcontainers：MySQL 8.4 / Redis / MinIO；串行） | 全部阶段通过，见 §6.4 | **0** |

### 6.4 根目录 `npm run verify` 明细

命令（仓库根）：`npm run verify`；日志：`.local-dev/reviews/2026-10-16-opencode-zen/root-npm-verify.log`。

| 阶段 | 结果 |
| --- | --- |
| 脚本单测 | `tests 24` / `pass 24` / `fail 0` |
| 脚本生命周期集成（真实 dev/infra CLI 端到端） | `tests 2` / `pass 2` / `fail 0` |
| 后端 surefire | **659** 项，0 failures、0 errors、1 Windows 平台跳过 |
| 后端 failsafe | **1242** 项，0 failures、0 errors |
| Gateway surefire | **177** 项（174 原有 + 3 新增契约测试），0 failures、0 errors |
| Gateway failsafe | **86** 项，0 failures、0 errors |
| 前端 test | **72** 文件 / **547** 项，全部通过 |
| 前端 lint | 通过（`eslint .` 无输出） |
| 前端 production build | 通过（`vite v8.2.1`，`✓ built in 963ms`，3352 modules transformed） |
| 两项 Maven `verify` lifecycle | 均 `BUILD SUCCESS` |
| **退出码** | **0** |

全程真实 Testcontainers，未绕过 Docker 检查；未删除失败测试、未放宽断言、未延长超时。

## 7. 保留不变的安全与财务约束

- **SSRF / DNS 防护**：`PublicOnlyAddressResolverGroup`（DNS 重绑定）、`DispatchEndpointGuard`（字面 IP 策略）、
  `CustomEndpointValidator`「应用层校验 + 连接时再校验」全部保留，未放宽。
- **重定向校验**：`followRedirect(false)` + 手动逐跳同源/机密保护，未放宽。
- **`DIRECT_ONLY` / 凭证保护**：`OpenCodeZenChatAdapter` 仍 `noProxy()` 且仅接受 `BEARER_TOKEN`；未引入代理回退。
- **事务契约**：未触碰 Gateway 派发、用量事实、Settlement、Ledger、Budget、Close 任何事务契约。
- **财务正确性**：未按 `-free` 后缀推断免费/零费用；未因代理成功请求认定可计费；未改动 pricing/manifest 计费分类。

## 8. 旧数据库连接配置：是否需要迁移

- **本仓库无需迁移**：`provider_catalog.OPENCODE_ZEN` 行**首次**由 `V24__m18_provider_hub.sql:41` 写入，base 即为
  `https://opencode.ai/zen/v1`；V24 之前 `provider_catalog` 无 `OPENCODE_ZEN` 行，而 `provider_account.provider_code`
  受 FK 约束指向 `provider_catalog`，故 V24 之前**不可能存在**可回填的 OpenCode 账号 → 不存在绑定旧 URL 的 OpenCode 连接。
- **V24 回填语义（已核对）**：`provider_connection_profile` 回填用 `pc.base_url`（`:48`），对可路由账号保留**原 base**；
  MIMO 账号示例断言 `.isEqualTo("https://mimo.example.test/v1")` 已存在（`V24ProviderHubMigrationIntegrationTest:109`）。
- **若某部署确已绑定旧/异 URL 的安全处理（不得原地改 ACTIVE 行）**：
  1. 只允许通过 Provider Hub API 对 `OPENCODE_ZEN` 账号**新建 DRAFT revision**（`createRevision`）→ 编辑 → `activate`，
     由 `activateTx` 在同一事务内「先退休旧 ACTIVE、再激活新 DRAFT」，维持 `uq_connection_profile_account_active` 唯一性与不可变契约；
  2. 内置模板字段（`baseUrl`/`completionPath`/`modelsPath`/`userAgent`/`authType`/`networkPolicy`）为 server-owned，
     **禁止**浏览器/请求覆盖（`ProviderConnectionService.rejectBuiltinOverride`，`:538-544`）；
  3. 若需修正 `provider_catalog` 的**旧/默认元数据**，用**新增前向迁移**更新该行，**不得**修改已发布的 V24；
     且 V24 之后 `provider_catalog.base_url` 已非派发权威（派发读 ACTIVE profile，见 `RoutingPolicyMapper.java:41-46`）；
  4. **禁止**直接 `UPDATE provider_connection_profile SET base_url=... WHERE status='ACTIVE'`。

## 9. 未验证的协议 / 兼容性边界

- 仅 **Chat Completions** 家族在项目内可用；**Responses、Anthropic Messages、Gemini 原生、System One（Jev）均未实现**，
  在受控清单中保持 `UNSUPPORTED`，未知模型 `UNKNOWN`，一律 fail closed。
- 未验证代理专有 `inference/v1` 中转 base 的可用性/语义（本批不采用）。
- 未独立核验 UA 版本号 `opencode/1.18.21` 的「官方正确值」——官方文档不规定 UA；该值沿用项目既有 server-owned 策略，
  本批不据代理版本号改动。
- 未执行真实网络请求，未对真实 OpenCode 上游做联调。
- 未执行远程 CI。

## 10. 提交与推送

- 起始 HEAD：`7a14d717f87f9916af8058bd1ad8c1672846344a`
- 本批分支：`codex/history-coverage-20261012`，**非强制**推送；未建 PR、未合并 main、未部署。
- 本批提交内容：`OpenCodeEndpointContractTest`（Gateway 3 项）、`OpenCodeZenEndpointContractTest`（Backend 5 项）、本记录。
- 本批提交：
  - `8376a119c67d43769d7315e674c46a9db48d2373` → `test(opencode): pin OpenCode Zen endpoint contract and proxy relay boundary`
  - `1e0d38bb9f316dab7d054907ec3e97346406bf86` → `docs(acceptance): record OpenCode Zen endpoint compatibility maintenance`
  - `7dad0261020d279f53c5828cda70f9dcf20c5ac1` → `docs(acceptance): record OpenCode Zen maintenance commit SHAs and push result`
- 推送：`7a14d717f87f9916af8058bd1ad8c1672846344a..7dad0261020d279f53c5828cda70f9dcf20c5ac1  codex/history-coverage-20261012`（**非强制** fast-forward，退出码 0）。
- 本记录自身的 SHA 补记提交见仓库 `git log`（沿用本目录既有约定：验收文档自身的提交不写死在其文内）；
  未建 PR、未合并 main、未部署。
