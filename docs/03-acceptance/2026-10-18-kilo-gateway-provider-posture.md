# Kilo Gateway Provider 接入 — 端点与姿态验收记录（2026-10-18）

## 结论

Kilo Gateway 作为项目**第二个内置模型供应商**（首个为 OpenCode Zen）接入完成。
依赖走 **JitPack 公开坐标**（免 Token），Boot 4 运行时兼容已实测。

## 决策与证据

### 1. 依赖来源：JitPack（公开，免凭据）

- 坐标：`com.github.BangShou1st.kilo-gateway:kilo-gateway-spring-boot-starter:v0.2.1`
- 仓库：`https://jitpack.io`（helper id `jitpack`）
- 解析成功，多模块出包：`kilo-gateway-spring-boot-autoconfigure` + `...-starter`。
- **环境注意**：本机全局 `settings.xml` 的 `aliyunmaven` 镜像为 `mirrorOf=*`，会把 jitpack 一并镜像掉；仓库构建走 **`mvnw`**（下载的纯净 Maven，仅合并用户级 settings，无镜像），因此解析正常。**后端/网关构建必须使用 `mvnw`，不要用系统 `mvn`。**

### 2. Boot 3.3.2 → 4.1.0 兼容（已实测）

- 库声明 `spring-boot-starter/spring-boot-autoconfigure:3.3.2`；在 `spring-boot-starter-parent:4.1.0` 下依赖树中 Spring 家族被压制为 **4.1.0 / 7.0.8**，无 3.3.x / 6.x 混入。
- 用仓库外临时工程 + `ApplicationContextRunner` 加载库的 `KiloGatewayAutoConfiguration`，在 Boot 4.1.0 下**成功装配 `KiloGatewayClient` bean**（2/2 通过）。这是运行时兼容的真判据。

### 3. 借用的库契约（单一事实来源）

反编译库 API（`javap`）确认：

- 包 `com.sharedlibs.kilogateway`；`KiloGatewayClient`（阻塞式，基于 JDK `HttpClient`）：`models()` / `freeModels()` / `ask` / `chat` / `streamChat`。
- **`FreeModelPolicy.isFreeModelId(String)`** = `id.endsWith(":free") || id.equals("kilo-auto/free")`。
- `KiloGatewayProperties`：默认 `base-url = https://ai-gateway.kilo.ai/api/v1`。

**姿态**：阻塞式 `KiloGatewayClient` **不接入**本项目数据面——数据面拥有自己的有界响应式传输（单一 DNS 解析 / public-only / 禁代理 / 有界 body），客户端既不响应式也不做 SSRF。仅借用 `FreeModelPolicy` 作为免费判定的**单一定义**，后端 manifest 与网关闭环比对同一规则，杜绝漂移；后端另 auto-configure 出一个未使用的 `KiloGatewayClient` bean（无害），网关则通过 `spring.autoconfigure.exclude` 显式关闭该自动配置。

### 4. 端点 / 鉴权 / 网络姿态（服务端拥有）

```text
template code      = KILO_GATEWAY            (BUILTIN)
baseUrl            = https://ai-gateway.kilo.ai/api/v1   （镜像库默认 base-url）
completionPath     = /chat/completions
modelsPath         = /models
protocol           = OPENAI_CHAT_COMPLETIONS （Kilo 为 OpenAI 兼容网关，全量 id 走 Chat）
authType           = BEARER
networkPolicy      = DIRECT_ONLY             （禁用 JVM/OS 代理）
userAgent          = null                    （回退中性 UA，绝不冒充 OpenCode）
lockedFields       = baseUrl/completionPath/modelsPath/userAgent/authType/networkPolicy
```

### 5. FREE_ONLY 姿态

- 内置 Kilo 模板按 **FREE_ONLY** 准入：非免费 id 只被发现、保持 AVAILABLE，**永不**探测 / 晋升 / 派发。
- 免费判定委托库的 `FreeModelPolicy`（`KiloGatewayModelManifest.isFreeEligible` + 网关 `KiloGatewayFreeOnlyAdmission`），定义一份、两处执行。
- 免费模型**匿名**调用（不发送 `Authorization`）；`ProviderExecutionContextResolver` 对已准入免费模型钉住 keyless `NONE` 凭据，付费模型仍走存储的 Bearer。
- `429` 视为正常有界结果（`ProviderHealthSignal.NONE`），不计入熔断。

## 变更清单

后端（`backend/`）：

- `pom.xml`：JitPack 仓库 + `kilo-gateway-spring-boot-starter:v0.2.1` + `<kilo-gateway.version>`。
- `db/migration/V32__m18_kilo_gateway_catalog.sql`：幂等种子 `provider_catalog`（`provider_code = adapter_code = 'KILO_GATEWAY'`，`status = 'ACTIVE'`）。
- `ProviderTemplateRegistry`：新增 `KILO_GATEWAY` 模板 + `isBuiltIn(...)` 泛化。
- `KiloGatewayModelManifest`（新增）：协议/定价分类，免费判定委托库策略。
- `ProviderConnectionService` / `ModelDiscoveryService` / `ModelProbeService`：内置模板分支泛化 + Kilo manifest 接线 + keyless 豁免。
- `ProviderTransportSupport.allowsKeylessCredential(...)`：内置免费层 keyless 单一定义。

网关（`gateway/`）：

- `pom.xml`：同上依赖；`application.yml` 关闭库自动配置。
- `provider/kilo/KiloGatewayChatAdapter`（新增，adapter code `KILO_GATEWAY`）：继承共享 OpenAI 兼容基类，无 CLI 信封，免费匿名 / 付费 Bearer，`429=NONE`。
- `routing/KiloGatewayFreeOnlyAdmission`（新增）：镜像 Zen 结构的第二道 FREE_ONLY 屏障。
- `CandidateEligibilityEvaluator` / `ProviderExecutionContextResolver`：keyless 与 FREE 屏障扩展覆盖 Kilo。

前端（`frontend/`）：

- 生产代码**无需改动**（Provider Hub 页面由 `/provider-templates` 数据驱动）。
- `providerFixture.ts` 增 Kilo 模板与连接；`ProviderHub.test.tsx` 增断言。

## 验证

- `npm run test`（后端 surefire 95 报告 / 网关 35 报告，全部 0 失败；前端 vitest **72 文件 / 547 用例通过**）。
- 新增单测：后端 `KiloGatewayEndpointContractTest`(6)、网关 `KiloGatewayChatAdapterTest`(5) + `KiloGatewayFreeOnlyAdmissionTest`(4)。
- 集成（failsafe）：`V32KiloGatewayCatalogMigrationIntegrationTest`(2)、`ProviderHubApiIntegrationTest`(13，含新增 Kilo 用例) 全通过。
- 既有 `OpenCodeV1V2EndpointGovernanceTest` 的模板普查断言由 2 更新为 3（按新事实，未放宽）。

## 已知边界 / 风险

1. Kilo 免费集由上游 `:free` 后缀策略决定，非服务端审核白名单；接受该供应商自身契约。若后续需“审核白名单”姿态，需与上游协商新证据版本。
2. 付费模型当前仅“可发现、不派发”；付费开通需另立变更（ACTIVE 定价版本 + 非 FREE_ONLY 准入）。
3. Docker/CI 走 `maven:settings` 生成容器安全 settings；若镜像策略变化需回归 JitPack 解析（见 `scripts/tasks.mjs`）。
