# OpenCode Zen v1.5.4 升级与真实上游 HTTP 200 验收报告

## 一、元数据

- **验收日期**：2026-10-26
- **责任主体**：AI-CostOps 维护团队（Antigravity AI）
- **关联模块**：`backend`（控制面模型探针与财务结算）、`gateway`（数据面推理调用链与用量终态）、`shared-libs/opencode-zen`（v1.5.4）
- **基线版本**：AI-CostOps `v3.1.0` / 分支 `dev`
- **上游版本**：`opencode-zen:v1.5.4`（Git Commit `d17dfcbe7482785fd5ca5645c768dcc382194ebf`）
- **验收结论**：**PASS**（满足协议升级、Relay 边界与 SSRF 防护加固、财务闭环与 Fail-Closed 语义、真实上游 HTTP 200 验收）

---

## 二、背景与根因分析

### 1. 升级背景
OpenCode Zen 上游发布了 2.x 协议，并针对传输层引入了反爬虫与客户端指纹检测：非 Bun 运行时发出的直连 HTTP 请求被上游以 `429 FreeUsageLimitError` 阻断。
为解决此问题，共享库 `opencode-zen` 升级至 `v1.5.4`，引入了自动 Bun Relay 治理机制（`opencode.zen.relay-url = auto`），支持在 Windows x64 等受支持环境中自动管理或复用本机 loopback（`127.0.0.1:18992`）透明隧道。

### 2. 核心断点与修复根因
在 AI-CostOps 项目侧，升级依赖后修复了以下断点：
1. **HTTP Egress 丢弃 Relay 目标**：原 `ProviderControlPlaneTransport` 与 Gateway `AbstractOpenAiCompatibleAdapter` 自行发送 HTTP 请求，只复用了 RequestFactory 的 Header，丢失了重定向至 Relay（`http://127.0.0.1:18992/`）的目标 URI，且未设置 `x-relay-target`。已修复为自动选择 Relay 时将请求 URL 指向 Relay，并设置规范化 `x-relay-target`。
2. **SSRF 防护拦截 loopback**：AI-CostOps 原有的 `FilteringAddressResolverGroup` 与 `PublicOnlyAddressResolverGroup` 强制拒绝所有 loopback 地址。专为 OpenCode Zen 引入 `OpenCodeZenAddressResolverGroup`，仅对受信任的 Relay loopback（端口严格限定为 18992）放行，且强制要求附带经白名单校验的 `x-relay-target`。
3. **上游模型废弃（410 Gone）**：真实上游探测 `minimax-m3.1-flash-free` 时返回 `410 Gone`（`Model minimax-m3.1-flash-free has been deprecated. Use deepseek-v4.1-flash-free instead.`）。根据准入规则，`deepseek-v4.1-flash-free` 属于 `OpenCodeModelManifest` 与 `OpenCodeZenFreeAdmissionPolicy` 认证的免费 Chat 模型，平滑切换至该模型完成真实校验。
4. **SSE 复合分块反序列化缺陷**：上游返回的最后一个数据分块同时包含 `choices`（含 delta content）与 `usage`，Gateway `AbstractOpenAiCompatibleAdapter.parseChunk` 此前在 `wire.usage() != null` 时优先返回 `Metering`，导致该分块中的文本内容被丢弃，触发 `MALFORMED_PROVIDER_RESPONSE`。修复为可同时发射 `Delta` 与 `Metering` 事件。

---

## 三、Relay 信任边界与 SSRF 防护加固

针对独立复审提出的“仅凭 HTTP 400 指纹无法杜绝本地伪造服务与 Relay 转发 SSRF”的顾虑，AI-CostOps 在客户端建立了严格的四重防御防线：

1. **防线一：上游域名白名单锁定（`validateUpstreamTarget`）**
   - 协议强制要求为 `https`（禁止明文 `http`）。
   - 目标 Host 必须严格等于 `opencode.ai`（`equals("opencode.ai")`），坚决拒绝任何内网 IP（如 `127.0.0.1`、`10.0.0.0/8`、`192.168.0.0/16`）、云元数据服务（`169.254.169.254`）或其他公网域名（如 `attacker.com`）。
   - 端口必须是 443 或默认端口。
   - 严禁嵌入 UserInfo 凭据（防止 `user:pass@opencode.ai` 形式的钓鱼或混淆）。
   - 任何由用户或外部传入的端点，在设置进 `x-relay-target` 发送给本地 Relay 之前，必须无条件通过该校验，彻底切断将 Bun Relay 当作 SSRF 代理的途径。

2. **防线二：本地 Relay 端口白名单锁定（18992 独占）**
   - Gateway 在底层 Netty 地址解析器 `OpenCodeZenAddressResolverGroup` 中严格限定：Loopback 地址（`127.0.0.1` / `localhost`）**仅且仅允许**连接 `port == 18992`。
   - 任何试图将 baseUrl 指向本地 MySQL（3306）、Redis（6379）、MinIO（9000）、Backend（8080）等任意其它端口的请求，直接在 DNS 解析阶段抛出 `UnknownHostException` 阻断。
   - Backend 在 `ProviderControlPlaneTransport.validateDestination` 中同理强制：Loopback 请求的目标端口必须为 18992。

3. **防线三：Relay 存活指纹校验（`isTrustedRelay`）**
   - 发起请求前向 `http://127.0.0.1:18992/` 发起探针，必须取得 HTTP 400 且响应体明确包含 `"x-relay-target header required"`，确认为官方 Bun Relay。
   - 若本地 18992 端口未启动、超时或返回 200（如非 Relay 本地服务），探针判定失败并安全回退直连（Direct Fallback），绝不盲目发送流量。

4. **防线四：凭据隔离**
   - OpenCode Zen 属于官方 `FREE_ONLY` 免密通道，系统发送给 Relay 的只有匿名协议请求头（`x-opencode-request-id`、`x-opencode-session-id` 等），**绝对不向 Relay 转发任何系统内部凭据、数据库密码或私有 API Key**。

---

## 四、真实 Gateway 财务闭环与异常防护证据

财务系统核心不变量：
```text
Gateway Request → FINAL Usage → Settlement → Ledger → Budget Actual
missing usage ≠ zero cost
POSTED Ledger 不可变
已发生的 Provider Cost 不因预算不足而丢弃
失败请求绝不产生虚假用量与结算
```

### 1. 成功调用链路（200 + SSE + Usage）
- **数据面（Gateway）**：
  上游返回真实 HTTP 200、有效 SSE 分块与 Token Usage（如 `prompt_tokens=5133, completion_tokens=3, totalTokens=5136`）。
  `GatewayUsageFinalizationService.finalizeSuccess` 在单个数据库事务中将用量标记为 `FINAL`，原子性写入 `gateway_usage_fact` 与维度明细（`INPUT_TOKEN`、`OUTPUT_TOKEN`），成本为 0。
- **控制面（Backend）**：
  `GatewaySettlementDiscoveryService` 扫描已完成的 `gateway_request`。
  `GatewaySettlementTransactionService.settle` 执行结算事务：
  - 写入 `gateway_settlement`（状态 `SETTLED`，`posted_amount=0.00000000`）；
  - 写入不可变 `ledger_posting`（状态 `POSTED`，`source_type='GATEWAY_SETTLEMENT'`）；
  - 写入复式记账明细 `ledger_entry`（金额 `0.00000000`）；
  - 累加 `budget.actual_amount`（增量 0）；
  - 预算预占（`budget_reservation`）从 `ACTIVE` 原子转为 `FINALIZED`；
  - 记录审计事件 `GATEWAY_SETTLEMENT_POSTED`。
  上述逻辑在真实 MySQL 8.4 集成测试 `GatewaySettlementTransactionIntegrationTest`（14/14 PASS）中得到完整验证。

### 2. 异常场景防护（HTTP 410、429、不完整 SSE 流）
- **HTTP 410（模型废弃）**：
  Gateway 收到 410 判定为 `PROVIDER_UNAVAILABLE`，路由尝试状态转为 `FAILED`，用量终态为 `SAFE_NO_BILLABLE_EXECUTION`。`finalizeFailure` 明确保证：不写入 `gateway_usage_fact`，不生成 `gateway_settlement`。预算预占安全释放（`RELEASED`）。Backend 结算完全忽略，**绝不写入 Ledger，Budget Actual 增量保持为 0**。
- **HTTP 429（上游频控）**：
  Gateway 收到 429 判定为 `RATE_LIMITED`（不计入熔断故障，但属于失败），向调用方返回 429。用量标记为 `SAFE_NO_BILLABLE_EXECUTION`，**绝不生成 FINAL 用量，绝不触发结算与入账**。
- **不完整 SSE（缺失 `[DONE]` 或截断流）**：
  `ZenStreamAggregation` 检测到未接收到 `[DONE]` 或没有有效文本，抛出 `MALFORMED_PROVIDER_RESPONSE`。用量判定为非法，**绝不按照 0 费用结算，绝不入账**。

---

## 五、验证环境与执行命令

### 1. 验证环境
- **操作系统**：Windows 11 x64
- **Java**：JDK 21.0.10
- **Node.js**：v24.14.0
- **宿主机 Relay 状态**：`127.0.0.1:18992`（`bun.exe bun-relay.mjs`，PID 4212）
- **测试模型**：`deepseek-v4.1-flash-free`（官方认证免费模型）

### 2. 核心验证命令
```powershell
# 1. Gateway 离线契约、Relay 边界、SSRF 白名单与本地端口隔离测试（16 项全部通过）
cd gateway
.\mvnw.cmd -B "-Dtest=OpenCodeZenRelayIntegrationTest,OpenCodeEndpointContractTest" test

# 2. Backend 离线契约、Relay 模拟与请求头测试（6 项全部通过）
cd ..\backend
.\mvnw.cmd -B "-Dtest=OpenCodeZenRelayProbeIntegrationTest,OpenCodeZenTransportHeadersTest" test

# 3. Gateway 真实 MySQL + Redis 用量终态集成测试（6 项全部通过）
cd ..\gateway
.\mvnw.cmd -B "-Dtest=GatewayUsageFinalizationIntegrationTest" test

# 4. Backend 真实 MySQL 财务结算闭环集成测试（14 项全部通过）
cd ..\backend
.\mvnw.cmd -B "-Dtest=GatewaySettlementTransactionIntegrationTest" test

# 5. 跨平台任务脚本（27 项全部通过）
cd ..
npm run test:scripts

# 6. 全库 Markdown 引用死链校验（255 项全部有效）
npm run verify:links
```

---

## 六、关键断言与真实证据

### 1. Phase B：真实 Backend 模型探针验收证据（HTTP 200）
```text
PROBE STATUS: 200 
BODY: 
data: {"id":"52a22736bca3237d969c825b50c9a16a","object":"chat.completion.chunk","created":1792988113,"model":"deepseek-v4.1-flash-free","choices":[{"index":0,"finish_reason":null,"delta":{"role":"assistant"}}]}

data: {"id":"52a22736bca3237d969c825b50c9a16a","object":"chat.completion.chunk","created":1792988113,"model":"deepseek-v4.1-flash-free","choices":[{"index":0,"finish_reason":"stop","delta":{"role":"assistant","content":"{\"ok\":true}"}}],"usage":{"prompt_tokens":5136,"completion_tokens":6,"total_tokens":5142,"cost":0,"opencode":{"billing_source":"free","list_cost":0}}}

data: [DONE]

LIVE PROBE RESULT: pass=true, error=null, capabilities={CHAT_COMPLETIONS=VERIFIED, SSE_STREAMING=VERIFIED, USAGE=VERIFIED, STRUCTURED_JSON=VERIFIED}
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.326 s
[INFO] BUILD SUCCESS
```

### 2. Phase C：真实 Gateway 聚合推理验收证据（HTTP 200）
```text
LIVE GATEWAY COMPLETION: 
  id=b49b39e756cc13923bbe1ae3fa0b84da, 
  content=PONG, 
  usage=ProviderUsage[promptTokens=5133, completionTokens=3, totalTokens=5136]
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.468 s
[INFO] BUILD SUCCESS
```

### 3. 上游 410 废弃检测证据（Fail-Closed 验证）
```text
PROBE STATUS: 410 
BODY: {"type":"error","error":{"type":"ModelDeprecated","message":"Model minimax-m3.1-flash-free has been deprecated. Use deepseek-v4.1-flash-free instead."},"metadata":{"model":"minimax-m3.1-flash-free","replacement":"deepseek-v4.1-flash-free"}}
LIVE PROBE RESULT: pass=false, error=PROVIDER_UNAVAILABLE
```

### 4. 上游 429 频控检测证据（Fail-Closed 验证）
```text
LIVE PROBE RESULT: pass=false, error=RATE_LIMITED, capabilities={CHAT_COMPLETIONS=UNKNOWN, SSE_STREAMING=UNKNOWN, USAGE=UNKNOWN, STRUCTURED_JSON=UNKNOWN}
com.aicostops.gateway.provider.ProviderExecutionException: Provider request failed with HTTP 429
```
系统准确将 429 归类为 `RATE_LIMITED` / `NONE` 信号，不计入 Provider 熔断，但坚决判定 `pass=false`，绝不伪造成功。

### 5. 本地非 Relay 端口 SSRF 拦截证据
```java
// Resolving 127.0.0.1 on port 3306 (MySQL) must fail with UnknownHostException
filtering.resolveAll(new InetSocketAddress("127.0.0.1", 3306), ...) -> UnknownHostException: Provider target is neither public unicast nor trusted relay: 127.0.0.1:3306
```

---

## 七、评估结论

```text
OPENCODE_ZEN_LIBRARY = v1.5.4
RELAY_MODE = auto
ZERO_CONFIG_LOCAL_DEV = PASS
UPSTREAM_HTTP_STATUS = 200 (Verified Live)
RELAY_SSRF_CONTAINMENT = PASS (Port 18992 Lock + opencode.ai Host Whitelist)
BACKEND_PROBE_VERIFIED = PASS
GATEWAY_INFERENCE_VERIFIED = PASS
FINANCIAL_INVARIANTS = PASS (No false posting on 410/429/truncated streams)
OVERALL_EVALUATION = PASS
```
