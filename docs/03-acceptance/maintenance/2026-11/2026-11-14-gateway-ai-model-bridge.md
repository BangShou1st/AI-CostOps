# Gateway ai-model-bridge 接入验收

## 元数据

- **日期**：2026-11-14
- **责任人 / AI**：Codex
- **关联模块**：`gateway`、Provider Hub 路由的 Gateway 消费端
- **关联分支**：`refactor/provider-model-bridge`
- **依赖**：`com.github.BangShou1st.ai-model-bridge:ai-model-bridge-core:v0.2.0`

## 变更概述

将 Gateway 的 `CUSTOM_OPENAI_COMPATIBLE` wire 实现切换为项目方自维护的
`ai-model-bridge` core。目标是把 OpenAI-compatible 的请求、SSE、usage 与
finish_reason 解析从本仓库移出，同时保持 AI CostOps 的 Provider 路由上下文、
预算预占、dispatch fence、结算与账本语义不变。

变更边界：

1. 仅引入 `ai-model-bridge-core`；不引入 starter，不引入 bridge server。
2. `ProviderChatAdapter`、adapter code `CUSTOM_OPENAI_COMPATIBLE`、Provider Hub
   模板、连接档案、定价、路由、预算、结算、账本与 API 契约不变。
3. 删除旧 `OpenAiChatAdapter`、`AbstractOpenAiCompatibleAdapter`、
   `GenericOpenAiCompatibleChatAdapter`、wire DTO、SSE decoder 与 endpoint policy。
4. 新 adapter 继续支持 `BEARER`、`API_KEY_HEADER`、`NONE` 三种认证方式，
   继续透传 `completionPath` 与 `X-Client-Request-Id`（route decision id）。
5. Bridge 的同步调用在 virtual-thread executor 上运行；流式使用 bridge
   `StreamHandle` 取消，并保留 `streamIdleTimeoutMs` / `hardTimeoutMs` 与
   Flux 终止语义。路径使用 `EndpointConfig`，`ChatOptions.failOnLength=false`
   保持截断响应可结算。
6. Bridge 异常统一映射为 `ProviderExecutionException` 的
   `SAFE_NO_BILLABLE_EXECUTION` / `BILLABLE_POSSIBLE`；无法证明安全时按
   `BILLABLE_POSSIBLE` 处理，禁止把 Provider 成本丢掉。
7. 新增 `PermissiveOpenAiCompatibleAdapter`：只把 bridge 对
   “合法 finish_reason / 非空内容”的流结束校验转换为 terminal `DONE`，
   保留已观测 usage；坏帧、上游 stream error 与真实协议错误仍然失败。
8. 共享库保持通用，不为 CostOps 增加业务字段；项目特有行为（缺失
   finish_reason 的兼容、错误安全分类、idle/hard timeout、Provider metadata
   fallback、流式背压边界）全部收敛在 Gateway `provider/bridge` 内。
9. 流式事件使用 256 事件有界队列 + `Sinks` 承接；队列满时阻塞 bridge 读取
   线程，向 Provider TCP 连接施加反向压力，不再使用无界 `FluxSink`。

## 验证环境与执行命令

执行环境：Windows PowerShell，Java 21，Node 24，Docker Desktop（MySQL 8.4 /
Redis 8.8 Testcontainers）。

```powershell
cd gateway
.\mvnw.cmd -B -DskipTests compile

.\mvnw.cmd -B "-Dtest=BridgeOpenAiCompatibleChatAdapterTest,GatewayApplicationTest,GatewayOpenApiContractTest,ProviderChatAdapterRegistryTest,ProviderExecutionContextResolverTest,GatewayArchitectureTest" test

.\mvnw.cmd -B "-Dtest=BridgeOpenAiCompatibleChatAdapterTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=ChatCompletionControllerTest" "-DfailIfNoSpecifiedTests=false" verify

.\mvnw.cmd -B "-Dtest=BridgeOpenAiCompatibleChatAdapterTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Dit.test=GatewaySafeFailoverIntegrationTest,StreamingLifecycleIntegrationTest,StreamPermitCeilingIntegrationTest" "-DfailIfNoSpecifiedTests=false" verify
```

## 关键断言

- Adapter 单测 `10/10` 通过：Bearer / API key header / NONE / 非流式 usage / 流式
  Delta+Metering+Done / usage 缺失保持 absent / 429 与 401 安全分类 /
  stream idle timeout / 300 chunk 慢订阅者有界背压。
- 完整 `.\mvnw.cmd -B verify` 通过：单测 `155/155`，集成测试 `99/99`，
  `BUILD SUCCESS`。
- 旧 adapter / DTO / decoder 删除后 `mvnw compile` 成功；无 Frontend、Backend、
  Flyway 或 OpenAPI 契约变更。

## 真实 Provider 烟测（2026-11-14）

使用本地 `mykey` 中的真实密钥，通过项目自己的
`BridgeOpenAiCompatibleChatAdapter` 直连真实上游；密钥仅注入临时进程环境变量，
未写入仓库、未输出到日志。客户端经本机 `127.0.0.1:7897` 代理出站。

| Provider | Base URL | Model | 非流式 | 流式 |
|---|---|---|---|---|
| Gemini OpenAI-compatible | `https://generativelanguage.googleapis.com/v1beta/openai` | `gemini-3.1-flash-lite` | 1305 ms，`stop`，`OK`，usage 5/1/6 | 981 ms，1 Delta / 2 Metering / 1 Done |
| ModelScope | `https://api-inference.modelscope.cn/v1` | `Qwen/Qwen3.5-27B` | 3856 ms，`stop`，`OK`，usage 14/176/190 | 2609 ms，1 Delta / 1 Metering / 1 Done |

说明：

- Gemini `gemini-2.5-flash` 已对新用户下线（404），本轮改用
  `gemini-3.1-flash-lite`；`gemini-3.8-flash` 在本地代理下超时，
  `gemini-3.5-flash` 返回 503 high demand，均不是 adapter 解析错误。
- 该烟测验证的是真实上游 auth、请求、响应、SSE 与 usage 路径；
  Provider Hub 数据库路由/预算/结算链路由完整 `verify` 的 mock 集成覆盖。

## 已知边界与后续事项

1. **SSRF connect-time pinning**：bridge v0.2.0 在每次请求前解析并校验完整
   DNS 答案，但 `HttpTransport` 未把 `PinnedEndpoint` 固定到实际 socket 连接；
   旧 `PublicOnlyAddressResolverGroup` 的连接时校验已随旧 adapter 删除。
   当前仍保留 bridge DNS 预检、`DispatchEndpointGuard` shape 校验与
   `DIRECT_PUBLIC_ONLY` 策略，但 DNS rebinding 的 TOCTOU 窗口需要后续
   bridge transport 自定义 `HttpClient` / Reactor Netty resolver 才能恢复
   等价保证。
2. **依赖形态**：Gateway 重新引入 JitPack 与 Jackson 2 `2.21.7`，仅用于
   bridge core；bridge starter/server 未引入，deployable 数量不变。
3. **Provider 响应标识**：bridge v0.2.0 的 `ChatResponse` / `StreamChunk`
   未暴露上游 `id`、`created` 与 `x-request-id`；Gateway 继续使用自己的
   public request id / fallback id，Provider completion id 与
   `providerRequestId` 证据暂时为空。后续需在 bridge 响应契约中补 metadata。
4. **长时压测**：Gateway 已使用 256 事件有界队列承接 bridge 流，并覆盖
   300 chunk 慢订阅者与 permit ceiling；尚未做数十分钟级慢客户端/内存占用
   压测。

## 本轮决策

- **SSRF connect-time pinning 暂不补**：前提是上游目标地址由管理员固定配置、
  调用方不能修改目标地址，且生产环境存在出站限制。若未来开放用户自填
  `base-url`，必须先限制可访问目标，或由 Bridge 后续版本 / 受控出站代理
  在实际连接时完成目标校验；不在 CostOps 内各写一套 Transport。
- **Provider `id` / `created` 暂不补**：当前继续使用本地生成的 public ID 与
  时间，并在语义上明确它们是本地值。`x-request-id` 若出现供应商工单追踪
  需求，优先由 Bridge 暴露一个可空的上游请求 ID；`id` / `created` 等到
  确实需要代理保真时再补，均不要求业务项目解析响应 body 或 SSE。
- **不恢复旧 Adapter**：本轮保持单一 bridge-backed 实现，不为上述边界增加
  第二套 wire 代码或项目侧 Transport。

## 结论与影响边界

**结论：PASS（功能与现有回归）；四个非阻塞边界已登记，按本轮决策延后，收口。**

Gateway 的 Provider wire 实现已完成切换，系统 API、Provider Hub、预算、路由、
结算与账本不变。SSRF connect-time pinning 与 Provider 响应 metadata 属于后续
bridge 契约增强项，不应在未完成前宣称与旧实现完全等价。
