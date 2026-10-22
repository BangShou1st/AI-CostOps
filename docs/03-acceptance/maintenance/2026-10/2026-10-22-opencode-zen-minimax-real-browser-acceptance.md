# OpenCode Zen minimax 模型真实浏览器验收（2026-10-22）

基线：`main @ 69aa204`（`fix: migrate OpenCode Zen integration to public JitPack v1.4.2`）

结论：**`OPENCODE_ZEN_MINIMAX_REAL_BROWSER_ACCEPTANCE = PASS`**

本文件记录一次以 minimax 模型为目标的、**真实浏览器驱动 + 真实上游推理**的端到端验收。
所有 Provider HTTP I/O 均指向 `https://opencode.ai/inference/v1`；没有 mock、没有回放、
没有假数据。账本金额、计量用量与上游返回一一对应。

## 1. 验收环境

为不干扰本机同时运行的其他项目（ai-collab、spec-agent 已占用 8080/8081/5173），
本次验收使用一套完全隔离的本地栈：

| 组件 | 位置 | 说明 |
| --- | --- | --- |
| MySQL / Redis / MinIO | Docker Compose project `aicostops-zen-uat` | 独立 network `aicostops-zen-uat`，独立 volume |
| Backend | 宿主机 native，端口 18080 | `local` profile，Flyway 32 个迁移全部成功 |
| Gateway | 宿主机 native，端口 18081 | `local` profile |
| Frontend | Vite dev server，端口 15173 | 真实浏览器（Edge 155，BrowserSkill）访问 |
| 浏览器 | Edge + BrowserSkill session | 全部操作走真实 UI，非 API 捷径 |

环境文件为 git-ignored 的 `.env.zenuat`（随机生成的数据库口令、Redis 口令、
MinIO 口令、JWT 签名密钥与 Gateway HMAC/KEK 密钥）。Gateway 原始密钥只在创建时
展示一次，仅存在于本次验收的本地文件中，未写入任何提交物。

## 2. 控制面真实浏览器路径

全部步骤在真实浏览器中通过 UI 完成（页面与断言均来自 `bsk observe` 的可访问性树）：

1. **登录**：`/login` → 以 dev bootstrap 管理员身份登录，工作台正常渲染（账期 #1 进行中）。
2. **云账号**：创建 Provider Account `OPENCODE_ZEN`（显示名 "OpenCode Zen UAT"），状态"启用"。
3. **连接**：Provider Gallery → 新建连接（模板 `OpenCode Zen`，Provider Account ID=2），
   生成 DRAFT 连接 #1。服务端锁定的端点为 `https://opencode.ai/inference/v1`，
   `completionPath=/chat/completions`、`modelsPath=/models`、`BEARER · DIRECT_ONLY`。
4. **激活**：连接详情 → 激活此版 → 状态变为"已启用"，版本 v1。
5. **健康探测**：执行探测 → 结果 **"探针通过"**。这是对上游 `/models` 的真实请求。
6. **实时发现**：模型目录 → 实时发现 → 从上游 `/models` 实际取回并登记 **86 个模型**，
   来源标记 `LIVE_DISCOVERY`。其中 minimax 系列全部在册：
   `minimax-m2.5`、`minimax-m2.7`、`minimax-m3`、`minimax-m3.1-flash-free`。
7. **模型探针（免费 minimax）**：`minimax-m3.1-flash-free` → 探针 → **探针通过**，
   实测能力四项全 VERIFIED：
   `CHAT_COMPLETIONS=VERIFIED · SSE_STREAMING=VERIFIED · USAGE=VERIFIED · STRUCTURED_JSON=VERIFIED`。
   这是对上游 `/chat/completions` 的真实流式推理请求。
8. **模型探针（付费 minimax）**：`minimax-m3` → 探针 → 被控制面拒绝，
   提示 *"OpenCode Zen is admitted for verified-free Chat Completions models only;
   this model has no current free evidence in the controlled manifest."*，
   **未产生任何上游 I/O**。
9. **升级（Promote）**：`minimax-m3.1-flash-free` → 升级 → 成功，
   "已升级为组织模型：逻辑 #2 · 服务商模型 #2 · 定价就绪 否 · 路由就绪 否"。
   分层治理如实呈现：升级只创建组织模型，不自动产生价格与路由。
10. **定价版本**：模型与定价 → 创建定价版本（账号 2 / 模型 2，单位 1000000，
    INPUT_TOKEN 与 OUTPUT_TOKEN 均为 `0.00000000`）→ 启用版本 → 状态"已启用"。
    只有 ACTIVE 零价格版本才是真正的零价格。
11. **路由策略**：创建组织默认策略（模型 2 / 账号 2 / 服务商模型 2 / 优先级 0）→
    启用版本 → 状态"已启用"。候选就绪状态显示"凭证缺失 定价就绪"：
    凭证缺失是 DB 派生的展示标志，Gateway 的 `CandidateEligibilityEvaluator`
    对 keyless 免费内置模型显式豁免该门槛，实际派发不受影响（见 §4）。
12. **网关凭证**：创建绑定模型 #1 与 #2 的网关凭证（前缀 `dnzx862zwa40`），
    原始密钥仅展示一次。

## 3. Gateway 真实推理

通过 Gateway 数据面 `POST http://127.0.0.1:18081/v1/chat/completions` 发起真实请求：

### 3.1 非流式

```json
{"id":"cmpl_gwr_46b8b2f7-334e-4b75-b78c-dead9367d1b4","object":"chat.completion",
 "model":"minimax-m3.1-flash-free",
 "choices":[{"index":0,"message":{"content":"PONG","role":"assistant"},"finish_reason":"stop"}],
 "usage":{"total_tokens":859,"completion_tokens":4,"prompt_tokens":855}}
```

HTTP 200。模型回答 `PONG`，usage 来自上游真实计量事件。

### 3.2 流式

上游返回真实 SSE 增量，Gateway 逐块透传，末尾统一收口：

```text
data: {...,"delta":{"content":"ST","finish_reason":null}}
data: {...,"delta":{"content":"REAM_OK","finish_reason":null}}
data: [DONE]
```

Content-Type 为 `text/event-stream;charset=UTF-8`，响应头带
`X-AI-CostOps-Request-Id` 与 `X-Trace-Id`。

## 4. 免费准入与付费边界

除控制面门槛外，Gateway 侧另有一道**独立**的 FREE_ONLY 屏障
（`OpenCodeFreeOnlyAdmission`），在候选评估阶段、预算与派发闸门之前生效。
为实测该屏障，在隔离验收库中构造了一个"付费 minimax 已具备全部前置条件"的场景：
逻辑模型、ACTIVE 服务商模型、ACTIVE 定价版本（$30/$60 费率）、
ACTIVE 组织默认路由策略与候选、ACTIVE 网关凭证模型绑定，全部就位。

请求付费 `minimax-m3` 的结果：

```json
{"error":{"message":"No eligible Provider route is available for the requested model",
 "type":"permission_error","code":"GATEWAY_FORBIDDEN"}}
```

HTTP 403。数据库侧确认该请求状态为 `FAILED_PRE_DISPATCH`，
**没有创建 route_attempt、没有 Provider HTTP I/O、没有 usage fact、没有结算、没有过账**。
验证后该夹具已从验收库删除。

其他边界同样符合预期：

| 场景 | 结果 |
| --- | --- |
| 相同 Idempotency-Key 重放 | HTTP 409 `GATEWAY_RESPONSE_NOT_RETAINED`，不重复计费 |
| 请求不在凭证范围内的模型 | HTTP 400 `GATEWAY_REQUEST_INVALID`（Unknown model） |
| 非法网关密钥 | HTTP 401 `GATEWAY_AUTH_INVALID` |
| 缺失 Idempotency-Key | HTTP 400 `GATEWAY_REQUEST_INVALID` |

## 5. 计量、结算与账本闭环

每次成功请求在 MySQL 中形成完整血缘：

```text
gateway_request        state=TRANSPORT_COMPLETED
  └─ gateway_route_attempt   status=COMPLETED, route_reason_code=INITIAL_PRIMARY
       └─ gateway_usage_fact status=FINAL
            ├─ gateway_usage_dimension  INPUT_TOKEN=855, OUTPUT_TOKEN=4
            │                            provenance=PROVIDER_FINAL
            └─ gateway_settlement        status=SETTLED, posted_amount=0.00000000
                 └─ ledger_posting        status=POSTED, source=GATEWAY_SETTLEMENT
                      └─ ledger_entry      entry_type=COST, amount=0.00000000 USD
```

`provenance=PROVIDER_FINAL` 证明用量取自上游计量事件而非本地推算。
金额为 `0.00000000` 因为该模型使用 ACTIVE 零费率定价版本——金融真相由定价版本决定，
而不是由 `VERIFIED_FREE` 分类决定。

真实浏览器中「账本」页面可见 4 笔"网关结算"记录，各 1 分录、0.00000000 USD；
分录详情显示发布键 `GATEWAY_SETTLEMENT:<id>`、状态"已入账"、账期 1、
目标 `PROJECT · 1`，血缘可回溯到 `source_gateway_settlement_id`。

## 6. 范围与限制

- 本次验收只证明 `minimax-m3.1-flash-free` 这一免费模型在当时可达且可用。
  §6.1 的补充探测显示，准入名单内的模型之间可用性并不一致。
- 付费 minimax 模型（`minimax-m2.5` / `minimax-m2.7` / `minimax-m3`）在本次验收中
  **未被证明可用**：它们被 FREE_ONLY 策略在控制面与 Gateway 两侧一致拒绝。
  要使用它们需要真实付费凭据与解除免费限定的策略修订，不在本次范围内。
- 上游 OpenCode Zen 为免费额度服务，存在 429 限流可能；本次 5 次真实推理请求
  全部取得 HTTP 200，但这不构成对未来请求可用性的承诺。
- 未创建生产账号、生产价格、生产路由或任何部署；全部变更只存在于本地隔离栈。

### 6.1 免费准入名单的实际可用性（补充探测）

为区分"项目缺陷"与"上游免费额度耗尽"，对准入名单内的 9 个模型在同一次会话内
用项目自身的 `ModelProbeService` 路径（共享 CLI 信封 + DIRECT_ONLY 传输）逐一探测：

| 模型 | 结果 | 说明 |
| --- | --- | --- |
| `minimax-m3.1-flash-free` | **PASS** | 四项能力全 VERIFIED |
| `nemotron-3.5-lightning-free` | **PASS** | CHAT/SSE/USAGE VERIFIED；STRUCTURED_JSON UNSUPPORTED（模型不产出结构化 JSON，属模型行为差异） |
| `mimo-v2.6-flash-free` | FAIL `RATE_LIMITED` | 上游 429 |
| `big-pickle` | FAIL `RATE_LIMITED` | 上游 429 |
| `glm-5.3-flash-free` | FAIL `RATE_LIMITED` | 上游 429 |
| `beam-free` | FAIL `AUTHENTICATION_FAILED` | 上游 403 |
| `deepseek-v4.1-flash-free` | 未单独探测 | — |
| `longcat-2.5-preview-free` | 未单独探测 | — |
| `nemotron-3.5-super-vl-free` | 未单独探测 | — |

所有失败均发生在上游响应层，错误码由项目正确分类并落库到
`provider_model_discovery.last_probe_error_code`；项目侧的协议实现没有报错。
这说明 **准入名单是"允许发起免费调用"的资格，不是"此刻一定可用"的承诺**：
免费额度服务的实时可用性由上游决定，且同一组织短时间内的多次免费调用会互相挤占额度。

`beam-free` 的 `AUTHENTICATION_FAILED` 值得单独留意：它在 2026-10-22 的上游
`/models` 目录中仍然在册，但免费层已不再接受调用。这与 2026-10-21 记录的
`space-bunny-free` 被摘出准入名单属于同一类漂移（目录在册 ≠ 免费层可用），
是共享准入策略需要按上游复核周期滚动更新的信号。本次验收不修改策略。

## 7. 证据位置

```text
.local-dev/zenuat/evidence/01-model-catalog.png   模型目录（探针通过 / VERIFIED_FREE）
.local-dev/zenuat/evidence/02-ledger.png          账本（4 笔网关结算）
.local-dev/zenuat/evidence/03-pricing.png         模型与定价（ACTIVE 零费率版本）
.local-dev/zenuat/backend.log                    Backend 运行日志（含 32 个 Flyway 迁移）
.local-dev/zenuat/gateway.log                    Gateway 运行日志
```

`.local-dev/` 已被 Git 忽略，证据不进入版本库。
