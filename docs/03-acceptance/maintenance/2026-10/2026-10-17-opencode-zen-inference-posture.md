# 2026-10-17 OpenCode Zen CLI 协议姿态接入（推理端点 inference/v1 + 5 工具信封 + 429 非失败）

上游：`com.sharedlibs:opencode-zen-spring-boot-starter:1.2.0`（私有 GitHub Packages；库仓库 HEAD `cb242ec`，库代码仍为 `60ea797 = v1.2.0`）。
本批由用户明确决策：**采纳共享库 1.2.0 的「CLI 协议姿态」（方案 B）**，并**把上游 429 视为正常（方案 A）**，顺序 B 先 A 后。

## 1. 结论（先给结果）

1. **端点治理版本决议**：内置 `OPENCODE_ZEN` 模板的推理基址由 legacy `https://opencode.ai/zen/v1` 改为共享 CLI 推理基址
   `https://opencode.ai/inference/v1`（= `OpenCodeZenProtocolPolicy.DEFAULT_BASE_URL`）。这是本批的**显式、版本化端点决策**，
   取代此前“内置模板必须留在 V1 Zen、不得指向 inference 家族”的治理不变量。
2. **CLI 工具信封注入请求体**：网关 `/chat/completions` 出站体新增 5 工具信封（`bash, edit, glob, grep, read`）。
   所有出站体一律走共享 `OpenCodeZenProtocolPolicy.applyRequiredChatEnvelope(body, true)`
   （`stream=true` + `stream_options.include_usage` + `tools`）。真机已验证：`stream=true` + 信封 → `200` 真实流式回复，
   而 `stream=false`（无论是否带 `tools`）→ `403 FreeTierError`，故免层只能 `stream=true`。
   **非流式调用方**（客户端 `stream:false` 与 Advisor 推理）由 `complete()` 覆写为「消费必需流式信封 + 聚合 delta」来服务，
   对外契约不变。仅 `OpenCodeZenChatAdapter` 覆写该钩子，OpenAI / MiMo / Generic 的 wire body **字节不变**。
3. **UA 单一来源**：后端 `ProviderTemplateRegistry` 与网关 `OpenCodeZenChatAdapter.SERVER_USER_AGENT` 均改为共享
   `OpenCodeZenProtocolPolicy.USER_AGENT`（`opencode/1.19.0 ai-sdk/provider-utils/4.0.23 runtime/bun/1.3.14`）；
   两份 `application.yml` 删除 `opencode.zen.user-agent` 覆盖，回落共享默认。
4. **429 视为正常（仅 OPENCODE_ZEN）**：网关 `healthSignal` 改为可覆写，Zen 适配器 `429 → ProviderHealthSignal.NONE`
   （不计入熔断）；后端 `probeStreaming` 不再把 `429` 判为 `UNSUPPORTED`（改 `UNKNOWN`）。MiMo / OpenAI / Generic 的 429 语义不变。
5. **后端探针改用同一 CLI 姿态**：`ModelProbeService` 对内置 `OPENCODE_ZEN` 改为**单次有界流式**探针
   （`stream=true` + `include_usage` + 5 工具信封），从观测到的 SSE 派生 `CHAT_COMPLETIONS` / `SSE_STREAMING` /
   `USAGE` / `STRUCTURED_JSON`。真机已验证旧探针形状（`stream=false`、无 tools）返回 `403 FreeTierError`，
   会使 Zen 探针恒为 `AUTHENTICATION_FAILED`；新形状返回 `200`。非 Zen Provider 探针路径**行为不变**。
6. **DB 迁移**：新增 `V30__m18_opencode_zen_inference_base.sql`，把 `provider_catalog` 与 **ACTIVE** 的
   `provider_connection_profile`（按 `provider_account.provider_code` 关联，因 V24 回填行 `template_code` 为 NULL）
   从 `zen/v1` 更新为 `inference/v1`。**V24 保持字节不变**（Flyway 校验和不动）。
7. **未改**：财务 / 结算 / 账本 / 预算围栏 / 路由选择 / 其他 Provider / Advisor 逻辑 / 历史验收文档；
   未删除或放宽任何既有断言（治理不变量改为**重写**）。

## 2. 仓库基线与边界

| 项 | 值 |
| --- | --- |
| 目标分支 | `codex/history-coverage-20261012` |
| 危险操作 | 未执行 `reset --hard` / `git clean` / 强制推送；未部署、未合并 main |
| 未跟踪保留 | `.freebuff/` 原样保留 |

## 3. 代码改动

| 文件 | 改动 |
| --- | --- |
| `gateway/.../provider/shared/AbstractOpenAiCompatibleAdapter.java` | 新增 `decorateWirePayload` 钩子（默认恒等）并在 `complete()`/`stream()` 落 `bodyValue` 前调用；`wireRequest` 改 `protected`；`healthSignal` 由 `private static` 改 `protected` 实例方法 |
| `gateway/.../provider/opencode/OpenCodeZenChatAdapter.java` | 覆写 `decorateWirePayload`（**一律**完整流式信封 `applyRequiredChatEnvelope(body, true)`）；覆写 `complete()` 为「流式 + 聚合」以服务非流式调用方；覆写 `healthSignal`（`429 → NONE`）；`SERVER_USER_AGENT` 改为共享 UA |
| `backend/.../providerhub/application/ProviderTemplateRegistry.java` | `OPENCODE_ZEN_BASE_URL` / `OPENCODE_ZEN_USER_AGENT` 改为共享常量 |
| `backend/.../providerhub/application/ModelProbeService.java` | ①`probeStreaming` 把 `429` 从 `UNSUPPORTED` 改为 `UNKNOWN`；②新增 `probeOpenCodeZen`（单次有界流式探针 + SSE 能力派生）与 `openCodeZenProbeBody` / `classifyOpenCodeZenStream`，仅内置 `OPENCODE_ZEN` 走该分支，非 Zen 探针路径不变；③抽出 `readBoundedStream` 供 SSE 读取复用 |
| `backend/src/main/resources/application.yml`、`gateway/src/main/resources/application.yml` | 删除 `opencode.zen.user-agent` 覆盖 |
| `backend/.../db/migration/V30__m18_opencode_zen_inference_base.sql`（新增） | `provider_catalog` + ACTIVE `provider_connection_profile` → `inference/v1` |

## 4. 测试改动

- **重写（不变量，非删除/放宽）**：`M18TransportRegressionTest`、`OpenCodeV1V2EndpointGovernanceTest`、
  `OpenCodeZenEndpointContractTest`（后端）、`ProviderHubApiIntegrationTest`、`OpenCodeManifestDriftIntegrationTest`（后端）、
  `PublicOnlyResolverTest`（网关）、网关 `OpenCodeEndpointContractTest`（mock 端点改 `inference/v1`）。
- **新增**：网关 `OpenCodeEndpointContractTest` 内新增「出站信封含 `tools`+`include_usage`+`stream:true`」、
  「非流式调用被聚合服务」、「`429` → `NONE`（非 QUALIFYING_FAILURE）」三项断言；
  后端新增 `OpenCodeZenProbePostureTest`（5 项：信封契约 + SSE 能力派生，含真实上游 SSE 形状）；
  后端新增 `V30OpenCodeZenInferenceBaseMigrationIntegrationTest`（真实 MySQL，验证 `provider_catalog` 与 ACTIVE profile 终值）。

## 5. 验证证据（定向）

### 5.1 真机联调（受控、非计费；2026-10-17）

共享库策略构造 + 真实上游 `https://opencode.ai/inference/v1`，凭据取自本机 `opencode-zen-free`（值不落盘、不打印）：

| 探针形状 | 结果 | 结论 |
| --- | --- | --- |
| `GET /models`（identity headers） | `200`，14 个模型 | `inference/v1` 同时是目录基址 |
| `stream=true` + `include_usage` + 5 工具信封 | `200` 真实流式回复 | 网关出站形状可用 |
| `stream=true` + 5 工具（无 `include_usage`） | `200` | `include_usage` 非必需 |
| **旧探针形状** `stream=false`、无 tools | **`403 FreeTierError`** | 免层必须 `stream=true`，故探针改为流式 |
| `stream=false` + tools | `403 FreeTierError` | 仅 `stream=false` 即被拒 |
| **新探针形状**（同步 `openCodeZenProbeBody`） | `200` + `[DONE]` + `usage` 帧 | 能力全部可派生 → 探针 `PASS` |

- 流尾真实形状：`finish_reason` 帧 → `choices:[]` + `usage` 帧 → `data: [DONE]`（已据此锁定 `classifyOpenCodeZenStream` 的判定）。

### 5.2 定向测试

| 范围 | 命令 | 结果 |
| --- | --- | --- |
| 后端定向 surefire | `mvnw -B test -Dtest=OpenCodeV1V2EndpointGovernanceTest,OpenCodeZenEndpointContractTest,OpenCodeManifestDriftTest,M18TransportRegressionTest,OpenCodeFreeOnlyAdmissionTest,OpenCodeProductionManifestTest` | Tests run **40**, 0F/0E |
| 后端 Zen 探针契约 surefire | `mvnw -B test -Dtest=OpenCodeZenProbePostureTest,SseLegalChunkValidationTest` | Tests run **12**, 0F/0E（信封 + SSE 能力派生） |
| 网关定向 surefire | `mvnw -B test -Dtest=OpenCodeEndpointContractTest` | Tests run **6**, 0F/0E（含信封与 429） |
| 后端 V30 迁移 failsafe | `mvnw -B test-compile failsafe:integration-test failsafe:verify -Dit.test=V30OpenCodeZenInferenceBaseMigrationIntegrationTest` | Tests run **1**, 0F/0E |
| 网关 OpenCode 集成 failsafe | `-Dit.test=OpenCodeFreeAdmissionDispatchBoundaryIntegrationTest` | Tests run **7**, 0F/0E |
| 后端全量 failsafe（首次 `npm run verify`） | `-Dit.test=...OpenCode...` 及全量 | 后端 failsafe `Tests run: 1257`，仅 `GatewayM12ReservationSchemaIntegrationTest` 迁移版本号断言失败（29→30），已修正并单类复跑通过（11/11）；OpenCode 相关集成测试均通过 |

## 6. 根目录 `npm run verify`（最终通过，exit code 0）

- 首次全量运行在后端 failsafe 暴露 1 处过期断言：`GatewayM12ReservationSchemaIntegrationTest` 断言
  `flyway_schema_history` 成功条目数为 `29`，新增 V30 后应为 `30`。已改为 `30` 并追加一条 `version = '30'` 存在性断言。
- **最终运行（含 Zen 探针流式改动与 `OpenCodeZenProbePostureTest`）exit code 0**，日志
  `.local-dev/verify-opencode-zen-posture.log`：

| 阶段 | 结果 |
| --- | --- |
| `[script tests]` | tests **27**, pass 27, fail 0 |
| `[script lifecycle tests]` | tests **2**, pass 2, fail 0 |
| `[backend]` surefire | **685** 项，0 failures / 0 errors / 1 skipped（Windows 平台） |
| `[backend]` failsafe | **1257** 项，0 failures / 0 errors → `BUILD SUCCESS` |
| `[gateway]` surefire | **214** 项，0 failures / 0 errors |
| `[gateway]` failsafe | **93** 项，0 failures / 0 errors → `BUILD SUCCESS` |
| `[frontend] test -- --run` / `lint` / `build` | 全部通过（`✓ built in 394ms`） |
| **退出码** | **0** |

- 新增的 `OpenCodeZenProbePostureTest`（5 项）、V30 迁移集成测试与网关信封/聚合/429 断言均在该次运行中通过；
  全程真实 Testcontainers（MySQL 8.4 / Redis / MinIO），未绕过 Docker 检查；未删除失败测试、未放宽断言、未延长超时。
- 计数相对上一份记录（`2026-10-16-opencode-shared-unification.md` §8）：后端 surefire `680 → 685`、
  后端 failsafe `1256 → 1257`、网关 surefire `211 → 214`、脚本单测 `26 → 27`；网关 failsafe 与前端不变。


## 7. 未验证 / 未解决项

| # | 项 | 状态 |
| --- | --- | --- |
| F-1 | 真实上游 `https://opencode.ai/inference/v1` 行为 | **已验证**（§5.1，免层 `Bearer public`）：`stream=true` + 信封 → `200`；`stream=false` → `403 FreeTierError`。未做真实计费请求 |
| F-2 | 非流式 `stream=false`（含/不含 `tools`）是否被上游接受 | **已验证被拒**：均返回 `403 FreeTierError`。故 `complete()` 已改为「消费必需流式信封 + 聚合 delta」服务非流式调用方，而非直接透传 `stream=false` |
| F-3 | 后端探针/发现对 `429` 的语义 | Zen 探针 `429` → `RATE_LIMITED`（不视为 Provider 故障）；`probeStreaming` 已改 `UNKNOWN`；`ModelDiscoveryService` 仍记 “rate limited”（保持既有分类） |
| F-4 | 连接配置的“冻结版本”语义 | V30 直接 UPDATE 现有 ACTIVE profile（而非新建 v+1 版本）。历史 RETIRED 行未改，未被重写 |
| F-5 | Zen 探针 `STRUCTURED_JSON` 的稳定性 | 真机显示推理型模型会把 `max_tokens:16` 预算耗在 reasoning 上，`delta.content` 可能为空或截断 → `STRUCTURED_JSON` 可判 `UNSUPPORTED`。`pass` 仅由 `CHAT_COMPLETIONS`/`SSE_STREAMING`/`USAGE` 驱动，故探针结论稳定；`STRUCTURED_JSON` 如实反映单次观测 |

## 8. 保留不变的安全、财务与事务约束

- 端点权威仍是冻结的 `ProviderCallContext`（`ProviderExecutionContextResolver`）；`DispatchEndpointGuard` / SSRF / 重定向 / 凭证保护未放宽。
- 未改定价版本、账本、结算、预算围栏、路由选择；未新增/修改历史财务记录。
- 未删除任何既有清单条目；未改写既有协议/计价分类。
