# 2026-10-16 OpenCode 共享组件统一策略与构建测试闭环

基线：`codex/history-coverage-20261012@ae09f6023586a1bd0557fb970d45dcd1345098aa`（本批已审查 HEAD，本地 = 远端）。
上游记录：`docs/03-acceptance/2026-10-16-opencode-free-only-admission.md`（下称「上批 FREE_ONLY」）。
核查时间：**2026-10-16（Asia/Shanghai）**。

本批只解决 **共享组件一致性、OpenCode 免费凭证、Starter 构建测试** 三件事。**未**接入本地 Python 代理；
**未**改动结算 / Ledger / 预算 / 其他 Provider / 端点协议族 / SQL 迁移 / 定价版本；**未**引入 V2、付费请求或新的代理架构；
未跟踪的 `.freebuff/` 原样保留；无 `reset --hard` / `git clean` / 强制推送；未建 PR、未合并 main、未部署。

## 1. 本批结论（先给结果）

1. **项目外共享库主版本**：`E:\project\shared-libs\opencode-zen`（group `com.sharedlibs`，version `1.1.1`），
   非 Git 仓库；本批**先修主版本**，再用新增的 `scripts/sync-opencode-shared-libs.ps1` **单向**同步到
   `shared-libs/opencode-zen/`（主库→快照，永不反向），未覆盖主库中的未提交修改（同步前主库与快照逐文件 SHA-256 比对**完全一致**）。
2. **共享库免费规则改为 fail closed**：删除 `isFreeModel()` 的 `-free` 后缀兜底；修正 `isVerifiedChatModel()`，
   要求 **Chat 协议 ∧ 明确免费依据 ∧ 有效（未下线）状态** 三者同时成立。
3. **V1 免费模型准入策略集中到共享组件**：新增 `OpenCodeZenFreeAdmissionPolicy`（唯一策略版本 + 唯一允许清单 + 唯一谓词）。
   Backend 与 Gateway **各自执行校验**，保留两层独立拦截，但**不再各自定义互相冲突的免费规则**（各层仅持共享定义的别名）。
4. **修复 Gateway「凭证就绪」与「实际请求凭证选择」的冲突**：已准入的免费 Zen 模型以共享 `public` 默认值派发、
   无需存储密钥，因此不再被 DB 派生的 `credential_ready` 误判为 `CREDENTIAL_MISSING`；**其他 Provider 及未准入的 Zen 模型认证规则不变**；
   **未向数据库写入任何虚构 ACTIVE 用户凭证**（仅用共享 `public` 默认值，不落库）。
5. **`public` 的官方支持边界**：**未发现官方证据**。`Bearer public` 仅见于社区/第三方实现，官方文档未记载；
   本批**不伪装 CLI 身份、不新增会话模拟、不绕过限制**来制造成功，仅用 **Mock**（Gateway 集成测试的 Mock HTTP Server）
   验证本地逻辑，并**如实声明真实上游行为未验证**（§6、§9）。
6. **付费 / 未知 / 已下线模型保持 fail closed**；未引入 V2、未发起付费请求、未新增代理架构。
7. **共享库独立 Gradle 构建与测试通过**（Spring Boot `3.3.2` 基线）；Consuming 应用以**源码**形式在 Spring Boot `4.1.0`
   下编译同一份共享源码（由 Backend/Gateway 构建验证）。**未**把「编译共享源码成功」冒充「已发布 Starter JAR 测试成功」——
   本仓库未配置远程 Maven 发布，Starter JAR **未**被发布或测试（§7）。
8. **建立可重复的同步与验证流程**：`scripts/sync-opencode-shared-libs.ps1`（同步） +
   `npm run verify` 内置 `shared-lib tests` 步骤（`shared-libs/opencode-zen/` 存在即自动运行共享 Gradle 测试，
   **避免手动漏跑**）。
9. **先复现确认的缺陷，再做最小修复**（§5）；**未**改动无关模块。

## 2. 仓库基线与边界

| 项 | 值 |
| --- | --- |
| 起始 HEAD | `ae09f6023586a1bd0557fb970d45dcd1345098aa` |
| 目标分支 | `codex/history-coverage-20261012`（本地 = 远端，`0 0`） |
| 外部主库 | `E:\project\shared-libs\opencode-zen`（非 Git；同步前与快照逐文件 SHA-256 一致） |
| 仓库快照 | `shared-libs/opencode-zen/`（`com.sharedlibs:*:1.1.1`） |
| 危险操作 | 未执行 `reset --hard` / `git clean` / 强制推送；未部署、未合并 main |
| Python 代理 | **未接入**；未新增代理服务/端口/中转 |

## 3. 外部主库确认与单向同步

- **路径确认**：`E:\project\shared-libs\opencode-zen` 是 Spec Agent 的 Gradle composite-build 与
  `E:\project\spec-agent\scripts\sync-opencode-shared-libs.ps1` 共同指向的 workspace 主库；其 `build.gradle.kts` 声明
  `group = "com.sharedlibs"`、`version = "1.1.1"`。
- **同步前一致性核对**：对两棵源码树（排除 `.gradle/`、`build/`）逐文件 SHA-256 比对，**29 个文件全部一致**，
  证明同步前无主库未提交修改会被反向覆盖。
- **同步脚本**：本仓新增 `scripts/sync-opencode-shared-libs.ps1`，方向固定为主库 → 快照，
  复制两个模块目录及源码/资源布局（排除 `.gradle/` 与模块 `build/`），与 Spec Agent 既有脚本同构。
- **同步后差异**：`git status` 仅显示本批预期文件发生变化（见 §4、§5），无意外文件。
- **源码同步版本**：共享库 Maven 坐标 `com.sharedlibs:opencode-zen-*:1.1.1`（Java 21，Spring Boot 3.3.2 基线）；
  免费准入策略版本 `OpenCodeZenFreeAdmissionPolicy.POLICY_VERSION = 2026-10-16-v1`。同步为**主库 → 快照**单向，
  同步后快照内容与主库一致（§4 所列文件为主库本批改动）。

## 4. 共享组件改动（主版本 + 快照，两者内容一致）

| 文件 | 改动 | 理由 |
| --- | --- | --- |
| `OpenCodeZenFreeModelPolicy.java` | ①`isFreeModel()` 删除 `-free` 后缀兜底，改为「必须配置且可用目录 ∧ `metadata.free()` ∧ `!retired`」；②`isVerifiedChatModel()` 追加 `metadata.free()` 依据 | 后缀不是证据；执行层必须同时满足 **Chat 协议 + 明确免费依据 + 有效状态** |
| `OpenCodeZenFreeAdmissionPolicy.java`（新增） | 唯一 `POLICY_VERSION`（`2026-10-16-v1`）+ 唯一 `ADMITTED_FREE_CHAT_MODELS`（5 条）+ 唯一 `admitsFreeChatModel()` | 把 V1 免费准入集中为**单一规则定义**，供两层分别执行 |
| `OpenCodeZenModelCatalogParserTest.java` | 后缀兜底期望改为 `false`；新增 `verifiedChatModelRequiresChatFreeAndActiveTogether` | 复现并锁定 fail closed |
| `OpenCodeZenFreeAdmissionPolicyTest.java`（新增） | 3 项：准入集合、后缀/未知/付费 fail closed、策略版本锁定 | 锁定共享策略 |

**未改**：协议常量、端点 URL、`DIRECT_ONLY`、凭证类型校验、SSE、Spring 自动配置、Starter 聚合、Java 级别（21）、
Spring Boot 基线依赖（`3.3.2`）。`isChatModelForListing()`（仅目录列举，不推断免费）保持原语义。

## 5. Backend / Gateway 改动（最小修复）

### 5.1 Backend

| 文件 | 改动 | 理由 |
| --- | --- | --- |
| `OpenCodeModelManifest.java` | ①`FREE_ADMISSION_POLICY_VERSION` 改为引用共享 `POLICY_VERSION`；②删除本地 `SERVED_FREE_CHAT_AT_REVIEW`；③`isFreeEligible()` 的在册判据改用共享 `admitsFreeChatModel()` | 保留 `classify()` 的分类血统，但**免费准入唯一由共享组件定义**；探针/晋级门槛行为不变 |
| `OpenCodeFreeOnlyAdmissionTest.java` | 新增 `backendBarrierExecutesTheSharedAdmissionPolicy`（+1） | 锁定 Backend 门槛与共享策略一致 |

### 5.2 Gateway

| 文件 | 改动 | 理由 |
| --- | --- | --- |
| `OpenCodeFreeOnlyAdmission.java` | `POLICY_VERSION` / `ADMITTED_FREE_CHAT_MODELS` 改为共享别名（`Set` 同一引用）；`admits()` 仍执行本地复核 | 保留**独立第二层**，但不再复制一份可能与 Backend 冲突的免费规则 |
| `CandidateEligibilityEvaluator.java` | `credential_ready` 判据：对「OpenCode 适配器 ∧ 已准入免费模型」跳过缺失凭证拒绝（`keylessFreeZen`） | 对齐 Gateway 凭证就绪与真实请求凭证选择（同模型由 `ProviderExecutionContextResolver` 固定为 keyless `public`）；其他 Provider 与未准入 Zen 模型规则不变 |
| `OpenCodeFreeOnlyAdmissionTest.java` | 新增 `gatewayBarrierExecutesTheSharedAdmissionPolicy`（+1，断言 `Set` 为同一引用） | 锁定 Gateway 门槛与共享策略同源 |
| `CandidateEligibilityEvaluatorTest.java` | 新增 4 项：准入免费 Zen 无凭证可准入 / 其他 Provider 无凭证仍 CREDENTIAL_MISSING / 付费 Zen 无凭证仍拒 | 直接复现并锁定 keyless 冲突修复 |
| `ProviderExecutionContextResolverTest.java` | 新增 2 项：准入免费 Zen 解析为 keyless 且不解密 / 未准入 Zen 仍解密存储密钥 | 证明实际请求凭证选择未被放宽 |
| `OpenCodeFreeAdmissionDispatchBoundaryIntegrationTest.java` | 新增 2 项（真实 MySQL）：准入免费 Zen **无存储凭证**仍进入派发规划；付费 Zen 无凭证仍 403 且零上游 | 端到端复现 §4 冲突在真实 MySQL 上的修复 |

**未改**：其他 Provider 适配器、路由选择、预算/围栏、派发、`credential_ready` 的 SQL 语义（仅评估器层面按适配器/模型范围豁免）。

## 6. `public` 凭证的官方支持边界（需求 #5）

- **核查结论**：**没有官方证据**表明 V1 Zen 官方支持匿名 `Authorization: Bearer public`。Web 检索命中的均为社区 / 第三方实现
  （如 FWH_docs、JAgent 等把 `public` 作为「无 API Key」写法），官方文档未记载该凭证；上批记录亦将其归类为社区代理专有行为。
- **本批做法**：仅使用**共享组件定义**的 `public` 默认值（`OpenCodeZenProtocolPolicy.FREE_TIER_CREDENTIAL`）用于已准入免费模型；
  **不伪装 CLI 身份、不新增会话模拟、不绕过限制**，**未新增**任何制造成功的机制。
- **验证方式**：仅用 **Mock**（Gateway 集成测试的本地 Mock HTTP Server）验证本地逻辑；**真实上游行为未验证**（§9 F-1）。

## 7. 构建与测试证据

### 7.1 共享库独立 Gradle 构建/测试（Spring Boot 3.3.2 基线）

命令（`shared-libs/opencode-zen/`）：`.\gradlew.bat test --console=plain`。

| 阶段 | 结果 |
| --- | --- |
| `compileJava` / `jar` | `BUILD SUCCESSFUL` |
| 测试类 | 6 个测试类 / **14** 项，0 failures / 0 errors |
| 修复前复现 | `OpenCodeZenModelCatalogParserTest` **2 项失败**（后缀兜底返回 `true`；付费 Chat 模型被 `isVerifiedChatModel` 误判为 `true`） |
| 修复后 | 全部通过（退出码 **0**） |

### 7.2 Consuming 应用（源码形式，Spring Boot 4.1.0）

共享 `autoconfigure` 源码经 `pom.xml` 的 `build-helper` 以相对路径加入 Backend/Gateway 源码集，
在 Spring Boot **4.1.0** 下编译同一份共享源码（§7.3 的定向与 §8 的全量构建即为其证据）。
**这不是** Starter JAR 测试；本仓库未配置远程 Maven 发布，无已发布 Starter JAR 被解析或测试（§9 F-6）。

### 7.3 定向测试（改动后，真实执行）

| 模块 | 命令 | 结果 | 退出码 |
| --- | --- | --- | --- |
| 共享库 Gradle | `gradlew.bat test` | Tests **14**, 0F/0E | **0** |
| Backend surefire | `mvnw.cmd -B test "-Dtest=OpenCodeFreeOnlyAdmissionTest,OpenCodeProductionManifestTest,OpenCodeManifestDriftTest,OpenCodeZenEndpointContractTest,OpenCodeV1V2EndpointGovernanceTest,M18TransportRegressionTest"` | Tests run **40**, 0F/0E | **0** |
| Backend failsafe | `mvnw.cmd -B test-compile failsafe:integration-test failsafe:verify "-Dit.test=OpenCodeFreeOnlyAdmissionIntegrationTest,OpenCodeManifestDriftIntegrationTest,OpenCodeManifestIntegrationTest"` | Tests run **24**, 0F/0E | **0** |
| Gateway surefire | `mvnw.cmd -B test "-Dtest=CandidateEligibilityEvaluatorTest,ProviderExecutionContextResolverTest,OpenCodeFreeOnlyAdmissionTest,GatewayArchitectureTest"` | Tests run **47**, 0F/0E | **0** |
| Gateway failsafe | `mvnw.cmd -B test-compile failsafe:integration-test failsafe:verify "-Dit.test=OpenCodeFreeAdmissionDispatchBoundaryIntegrationTest"` | Tests run **7**, 0F/0E | **0** |
| 根脚本单测 | `node --test scripts/tests/*.test.mjs` | Tests **26**, 0F | **0** |

### 7.4 先失败后通过的复现记录

| # | 缺陷 | 复现（修复前） | 修复后 |
| --- | --- | --- | --- |
| R-1 | `isFreeModel()` 用 `-free` 后缀兜底 | `gradle test`：`OpenCodeZenModelCatalogParserTest` 期望 `false` 实得 `true` | 通过 |
| R-2 | `isVerifiedChatModel()` 未要求免费依据 | `gradle test`：付费 Chat 模型被判为可执行（期望 `false` 实得 `true`） | 通过 |
| R-3 | Gateway `credential_ready` 与 keyless `public` 选择冲突 | `CandidateEligibilityEvaluatorTest.admittedFreeOpenCodeZenModelIsEligibleWithoutAStoredCredential`：期望 `true` 实得 `false` | 通过 |
| R-4 | `ae09f60` 引入的 `<directory>` 触发 `isolatedPom` 守卫，阻断根 verify | `npm run verify`：`[task failed] backend POM 构建目录需要显式审查，未执行构建。` | 见 §8（守卫精确化后通过） |

> R-3 的集成对照（§5.2 边界测试）在真实 MySQL 上给出同一结论：准入免费 Zen 无凭证仍进入派发规划、付费 Zen 无凭证仍 403。

## 8. 根目录 `npm run verify`

命令（仓库根）：`npm run verify`；日志：`.local-dev/reviews/2026-10-16-opencode-shared-unification.log`。

<!-- VERIFY-RESULT -->

**结果：全部阶段通过，退出码 `0`。**

| 阶段 | 结果 |
| --- | --- |
| 脚本单测 | `tests 26` / `pass 26` / `fail 0` |
| 脚本生命周期集成（真实 dev/infra CLI 端到端） | `tests 2` / `pass 2` / `fail 0` |
| **共享库 Gradle 测试**（`[shared-lib tests]`，本批新增的 verify 步骤） | 6 类 / **14** 项，`BUILD SUCCESSFUL` |
| 后端 surefire | **680** 项，0 failures、0 errors、1 skipped（Windows 平台） |
| 后端 failsafe | **1256** 项，0 failures、0 errors |
| Gateway surefire | **211** 项，0 failures、0 errors |
| Gateway failsafe | **93** 项，0 failures、0 errors |
| 前端 test | **72** 文件 / **547** 项，全部通过 |
| 前端 lint | 通过（无输出） |
| 前端 production build | 通过（`✓ built in 389ms`） |
| 两项 Maven `verify` lifecycle | 均 `BUILD SUCCESS` |
| **退出码** | **0** |

计数对照（相对上一份 `npm run verify` 记录 `2026-10-16-opencode-free-only-admission.md` §7.6；
期间 `ae09f60` 只改了生产代码、未新增 Backend/Gateway 测试，故差值可逐一归因于本批）：

| 范围 | 上批 | 本批 | 差 | 对应新增 |
| --- | --- | --- | --- | --- |
| 脚本单测 | 24 | **26** | **+2** | `tasks.test.mjs` 共享步骤（+1）、`workspace.test.mjs` 守卫精确化（+1） |
| 后端 surefire | 679 | **680** | **+1** | `OpenCodeFreeOnlyAdmissionTest.backendBarrierExecutesTheSharedAdmissionPolicy` |
| 后端 failsafe | 1256 | **1256** | **0** | — |
| Gateway surefire | 205 | **211** | **+6** | `CandidateEligibilityEvaluatorTest`（+3）、`ProviderExecutionContextResolverTest`（+2）、`OpenCodeFreeOnlyAdmissionTest`（+1） |
| Gateway failsafe | 91 | **93** | **+2** | `OpenCodeFreeAdmissionDispatchBoundaryIntegrationTest` keyless 两项 |
| 共享库 Gradle | 10 | **14** | **+4** | 新增 `OpenCodeZenFreeAdmissionPolicyTest`（3）+ 解析器测试新增 1 项 |
| 前端 | 547 | **547** | **0** | 本批未改前端 |

> 全程真实 Testcontainers（MySQL 8.4 / Redis / MinIO），未绕过 Docker 检查；未删除失败测试、未放宽断言、未延长超时；
> 未发起任何可计费推理。本批 `npm run verify` **一次通过**（`shared-lib tests` 步骤已并入根流程，避免手动漏跑共享测试）。

## 9. 未解决 / 未验证项

| # | 项 | 状态 |
| --- | --- | --- |
| F-1 | `public` 在真实 V1 Zen 的行为 | **未验证（有意）**。无官方证据且默认禁止付费/真实请求；仅 Mock 验证本地逻辑 |
| F-2 | 已发布 Starter JAR | **未测试**。无远程 Maven 发布；Starter 源码保留在快照中 |
| F-3 | 实时目录 id 集合稳定性 | **未决**（沿用上批）。下线条目须按三事实规则重新准入后再前移策略版本 |
| F-4 | Backend / Gateway 免费规则一致性 | **已由共享定义 + 两侧测试锁定**（同一 `POLICY_VERSION`、同一 `Set` 引用） |
| F-5 | 真实付费推理 / 真实 V1 联调 / 远程 CI | **未执行** |

## 10. 保留不变的安全、财务与事务约束

- **端点与协议**：`https://opencode.ai/zen/v1` + `/chat/completions` **未改**；Responses / Messages / Gemini / SystemOne 仍 fail closed。
- **代理隔离**：未接入本地 Python 代理；未新增代理服务/端口/中转；未复制匿名凭证语义、会话模拟头、回退代理、占位工具。
- **SSRF / DNS / 重定向 / 凭证保护**：`PublicOnlyAddressResolverGroup`、`DispatchEndpointGuard`、`CustomEndpointValidator`、
  `followRedirect(false)`、`OpenCodeZenChatAdapter` 的 `noProxy()` 与仅接受 `BEARER_TOKEN` **均未放宽**。
- **财务正确性**：未按 `-free` 后缀推断免费；**未改动/新建任何 Pricing Version**；未修改任何历史财务记录；`VERIFIED_FREE` 仍不自动生成零价格版本。
- **数据库**：**未新增/修改任何 SQL 迁移**；**未写入任何虚构 ACTIVE 用户凭证**。
- **事务契约**：拒绝仍发生在派发/预算/围栏之前；未新增重试。
- **清单血统**：未删除任何条目；未改写既有条目的协议或计价分类。

## 11. 提交与推送

起始 HEAD：`ae09f6023586a1bd0557fb970d45dcd1345098aa`；目标分支 `codex/history-coverage-20261012`，**非强制**推送；
未建 PR、未合并 main、未部署。最终提交 SHA 与推送结果见同目录推送记录。

<!-- COMMITS -->
