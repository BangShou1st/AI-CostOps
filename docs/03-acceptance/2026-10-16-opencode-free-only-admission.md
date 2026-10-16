# 2026-10-16 OpenCode 免费模型准入修正（FREE_ONLY）

基线：`codex/history-coverage-20261012@68ef79dd0e0d77607938a7b1dcfb0a297843d1da`（本地 = 上批已推送 HEAD）。
上游记录：`docs/03-acceptance/2026-10-16-opencode-manifest-drift-repair.md`（下称「上批 M-2」）。
本地参考代理：`D:\OpenCode-UA-Start\opencode-zen-proxy.py`（**只读**，未修改、未移动、未删除、**未复制进仓库**）。
核查时间：**2026-10-16（Asia/Shanghai）**。

本批**只修免费准入**：新增 1 个 Backend 生产谓词 + 2 处 Backend 门槛收紧、1 个 Gateway 生产策略 + 1 处评估器接线、
5 个新增测试类（Backend 2 / Gateway 3）、3 个既有测试期望同步、本记录。
**未**改端点、协议族、凭证、网络策略、SQL 迁移、定价版本或任何财务事务；**未**引入代理服务/端口/中转架构；
**未**复制代理的匿名 `Bearer public`、会话模拟头、代理回退或占位工具注入。
未跟踪的 `.freebuff/` 原样保留；无 `reset --hard` / `git clean` / 强制推送；未建 PR、未合并 main、未部署。

## 1. 本批结论（先给结果）

1. **`OPENCODE_ZEN` 现在明确执行 FREE_ONLY 规则**。准入不再是「Chat 兼容即可」，而是
   `isFreeEligible()`：**同时**满足 ①`OPENAI_CHAT_COMPLETIONS` 协议、②官方 V1 Zen 定价行为 `Free`、
   ③当前仍由 V1 目录服务 —— 三个**相互独立**的证据事实。
2. **当前符合准入条件的免费模型恰好 5 个**（§4.1）：`big-pickle`、`longcat-2.5-preview-free`、
   `mimo-v2.6-flash-free`、`nemotron-3.5-lightning-free`、`space-bunny-free`。
3. **`PAID` 不参与推断，也不被改写**：17 条 `PAID` 条目**保持 `PAID`** 作为元数据，但**不获得**探针或路由资格。
   未按 `-free` 后缀推断免费；V2 Console 的免费行未外溢到 V1。
4. **已下线的免费 Chat 条目保留血统但失去准入**（7 条，§4.2）：分类仍为 `CHAT`+`VERIFIED_FREE`，
   但因不再被 V1 目录服务，第三个事实不成立 ⇒ fail closed。这是本批**新增的收紧**，
   与上批 M-2「保留血统」的决定完全一致（保留分类 ≠ 保留调用权）。
5. **两层独立拦截**（§6）：Backend 在探针与晋级前拦截；Gateway 在派发前用**自己 server-owned 的允许清单**
   独立复核，不信任 Backend、不读浏览器输入。任一层都能单独挡住付费模型。
6. **被拒绝的请求在 Provider HTTP I/O 之前结束**，返回清晰且安全的错误（§6.4）。
7. **未新增任何定价版本、未修改任何历史财务记录**；`VERIFIED_FREE` 仍不自动生成零价格版本（§8）。

## 2. 仓库基线与边界

| 项 | 值 |
| --- | --- |
| 起始 HEAD | `68ef79dd0e0d77607938a7b1dcfb0a297843d1da` |
| 目标分支 | `codex/history-coverage-20261012` |
| 工作区 | 仅有未跟踪 `.freebuff/`（未触碰） |
| 危险操作 | 未执行 `reset --hard` / `git clean` / 强制推送；未部署，未合并 main |
| Python 代理 | **只读比较**，未修改、未复制、未接入；未采用其匿名凭证/会话头/回退代理/占位工具 |

## 3. 官方证据（本批判定所用，全部为只读语义）

| # | 事实 | 来源 | 指纹 |
| --- | --- | --- | --- |
| S1 | V1 Zen **实时目录** 86 个 id | `GET https://opencode.ai/zen/v1/models`（2026-10-16，HTTP 200，7219 B） | SHA-256 `6a4fc13e81fd7a7905c5934cef9781e39255a9aa93e2da406fc4f02b682e2685` |
| S2 | V1 Zen **端点表**：88 端点行 / 29 条 `chat/completions` | Wayback `20261011170423id_/https://opencode.ai/docs/zen`（官方原文存档，HTTP 200） | SHA-256 `eeeaa89f7cc5a8bea2a443d9b995d0e6670e0276eff329ee19889b8e705afe8f` |
| S3 | V1 Zen **定价表**：13 条 `Free/Free` 行 | 同 S2 | 同上 |
| S4 | V1 文档 URL 现 302 → V2 Console 文档 | §3.1 | — |

> **证据强度声明**：S1 为本批**实测**（强证据）。S2/S3 取自 **Wayback 存档的官方 `opencode.ai/docs/zen` 原文**，
> 非第三方镜像（强度高于上批复核的 E9 弱证据），但仍**非**当前在线原页 —— 因为 V1 文档已被 V2 取代并重定向（S4）。
> S1 与本批结论不矛盾：实时目录给出「当前被服务」口径，端点表/定价表给出「族与价格」口径。
>
> **S1 与上批 M-2 的一致性**：本批测得 86 个 id / 7219 B，与上批 M-2 §3.1 的 `S1`（86 id，7219 B，
> SHA-256 `4c4de9b6…`）**字节数相同**。上批存档文件未随仓库分发，故本批**不复算其 SHA**；
> 若 SHA 有差异，最可能是目录为实时编辑的内部运营目录（`created` 集中在数秒窗口），
> **不影响**本批结论 —— 本批只据**本批实测目录**判定「当前是否被服务」。

### 3.1 本批**未**扩展的部分（明确声明）

- **未**引入 V2 Console、OpenCode Go 或任何其他协议族模板；
- **未**接入本地 Python 代理，**未**新增代理服务、代理端口或中转架构；
- **未**因「免费」而引入匿名凭证或伪装绕过服务限制；
- 端点地址沿用经确认的 V1 Zen 官方直连模板 `https://opencode.ai/zen/v1` + `/chat/completions`（**未改**）：
  本批**未**发现任何证明该地址有问题的证据，故按需求**不做定向更正**。

## 4. 准入集合（逐条可复核）

判定规则（三条**同时**成立才准入）：

| 事实 | 判定口径 |
| --- | --- |
| 协议 | S2 端点表中该 id 的行为 `https://opencode.ai/zen/v1/chat/completions` |
| 价格 | S3 定价表中该 id 行为 `Free / Free` |
| 当前服务 | S1 实时目录中存在该 id |

### 4.1 准入（5 条，全部满足三条事实）

| # | 模型 ID | 协议 | 价格 | 当前服务 |
| --- | --- | --- | --- | --- |
| 1 | `big-pickle` | `chat/completions` | `Free` | ✅ |
| 2 | `longcat-2.5-preview-free` | `chat/completions` | `Free`（名列表述 limited time） | ✅ |
| 3 | `mimo-v2.6-flash-free` | `chat/completions` | `Free` | ✅ |
| 4 | `nemotron-3.5-lightning-free` | `chat/completions` | `Free` | ✅ |
| 5 | `space-bunny-free` | `chat/completions` | `Free` | ✅ |

> `big-pickle` **没有** `-free` 后缀却是唯一的准入项之一；这正是「来源是定价表而非后缀」的直接反证。

### 4.2 禁止调用 —— 已确认免费但**当前未被服务**（7 条，保留血统、失去准入）

| 模型 ID | 分类（保留） | 失去准入的原因 |
| --- | --- | --- |
| `mimo-v2.5-free` | `CHAT`+`VERIFIED_FREE` | 不在 S1 |
| `laguna-s-2.1-free` | `CHAT`+`VERIFIED_FREE` | 不在 S1 |
| `ling-3.0-tiny-free` | `CHAT`+`VERIFIED_FREE` | 不在 S1 |
| `longcat-2.0-free` | `CHAT`+`VERIFIED_FREE` | 不在 S1 |
| `north-mini-code-free` | `CHAT`+`VERIFIED_FREE` | 不在 S1 |
| `nemotron-3-ultra-free` | `CHAT`+`VERIFIED_FREE` | 不在 S1 |
| `deepseek-v4-flash-free` | `CHAT`+`VERIFIED_FREE` | 不在 S1 |

> 严格说明：`nemotron-3-ultra-free` 与 `mimo-v2.5-free` 在 S2/S3 **仍有**端点行与定价行，
> 只是**不在 S1 实时目录**。本批对二者与其余 5 条一视同仁 —— 因为「当前是否被服务」是同一判据。

### 4.3 禁止调用 —— `PAID`（17 条，分类保留、无准入）

`deepseek-v4-pro`、`deepseek-v4-flash`、`minimax-m3`、`minimax-m2.7`、`minimax-m2.5`、`glm-5.2`、`glm-5.1`、
`glm-5`、`kimi-k2.5`、`kimi-k2.6`、`kimi-k2.7-code`、`kimi-k3`、`deepseek-v4.1-flash`、`glm-5.3`、
`glm-5.3-flash`、`mistral-large-4`、`qwen3.8-max`。

> 其中前 12 条与后 5 条**都是** `CHAT` 分类（即「协议兼容」成立）——
> 若只有协议门槛，它们会被放行。**这正是本批必须引入强于协议门槛的免费准入的原因**。

### 4.4 禁止调用 —— `UNKNOWN` / `UNSUPPORTED` / 无当前免费证据

以下全部 fail closed，既不能探针也不能晋级：

| 组别 | 条目 |
| --- | --- |
| 非 Chat 族（`UNSUPPORTED`） | Responses 族（`gpt-5.*`、`gpt-6-*`、`grok-4.5/4.6/4.7`、`grok-build-0.1`、`muse-spark-1.2/1.3`）、Messages 族（`claude-*`、`qwen3.5/3.6/3.7/3.8-flash`）、Gemini 原生族（`gemini-3.x`）、SystemOne（`jev-1.13*`） |
| V2 Console 独有免费（V1 **无**证据） | `deepseek-v4.1-flash-free`、`cat-jam-free`、`muse-spark-1.2-contributor-free`、`nemotron-3.5-super-vl-free`、`gemini-4-argon` |
| 有官方证据但当前未被服务 | `deepseek-v4-flash-vision-exp`、`exo-free`、`ling-3.0-flash-fin-free`、`ling-3.1-flash-free`、`step-5-preview-free` |
| 未分类 / 内部条目 | `future-unknown-model`、`unknown-random-free`、`test`、`claude-opus-5-5-backup`、`claude-*-5-5` |

> **后缀推断被明确拒绝**：`glm-5.3-free`、`qwen3.8-max-free`、`totally-new-model-free` 等一律 `UNKNOWN` ⇒ 无准入。

## 5. 实现（最小改动）

### 5.1 Backend

| 文件 | 改动 | 理由 |
| --- | --- | --- |
| `OpenCodeModelManifest.java` | ①新增 `FREE_ADMISSION_POLICY_VERSION = "2026-10-16-v1"`；②新增 `SERVED_FREE_CHAT_AT_REVIEW`（5 条）；③新增 `isFreeEligible(String)` = 协议 `CHAT` ∧ 价格 `VERIFIED_FREE` ∧ 在册；④类注释补 FREE_ONLY 口径 | 把「分类」与「准入」显式分离：`classify()` 保留历史元数据，`isFreeEligible()` 才是调用权 |
| `ModelProbeService.java` | 探针门槛 `isChatCompatible` → `isFreeEligible`，并改写错误文案 | 阻止付费/未知/下线模型发起**真实 Provider 探针** |
| `ModelDiscoveryService.java` | 晋级门槛 `isChatCompatible` → `isFreeEligible`，并改写错误文案 | 阻止付费/未知/下线模型被晋极为可路由 |

**未改**：端点 URL、协议族集合、认证类型、`DIRECT_ONLY`、超时、SSRF/重定向策略、`classify()` 的分类结果、
SQL 迁移、定价版本、事务契约。**未删除任何条目**（清单仍 70 条）。

### 5.2 Gateway（独立第二层）

| 文件 | 改动 | 理由 |
| --- | --- | --- |
| `OpenCodeFreeOnlyAdmission.java`（新增） | server-owned 允许清单（5 条）+ `admits(adapterCode, providerModelName)`，仅约束 `OPENCODE_ZEN` | 防止「历史上已晋级 / 已配 Pricing Version / 旧路由」的付费模型绕过 Backend |
| `CandidateEligibilityEvaluator.java` | 在适配器与能力检查之后、按需加 `FREE_ADMISSION_REQUIRED` 拒绝；新增该 `EligibilityReason`；构造器注入策略 | 在**任何**预算准入、派发围栏与 Provider HTTP I/O 之前拒绝 |

**为何是独立实现而非共享代码**：Backend 与 Gateway 是**两个独立可部署物**（`GatewayArchitectureTest` 禁止
Gateway 依赖 Backend 包）。允许清单**有意重复**：任一侧被误改都不会自动放宽另一侧。两侧清单相同由测试锁定（§7.4）。

### 5.3 幂等与并发不变

拒绝发生在候选评估阶段，**早于** `RouteAttemptCoordinator.plan`、预算 `admitSync` 与 `commitDispatchFence`，
因此：不产生 `gateway_route_attempt` 行、不占用预算、不写财务状态。集成测试断言路由尝试数为 **0**（§7.3）。

## 6. 两层如何拦截（需求 #2、#6）

### 6.1 请求时序（付费模型）

```
Gateway 收到请求
  └─ resolveCatalog：模型须 ACTIVE 且在凭证允许清单内
      └─ orchestrator.prepareInitial
          └─ CandidateEligibilityEvaluator.evaluate
              ├─ ...凭证/模型/适配器/能力检查（既有）
              └─ OpenCodeFreeOnlyAdmission.admits(...)  ← ★ 本批新增，独立免费准入
                  └─ false ⇒ 拒绝（FREE_ADMISSION_REQUIRED），跳过该候选
          └─ 所有候选被拒 ⇒ GATEWAY_FORBIDDEN（HTTP 403），标记请求 FAILED_PRE_DISPATCH
      ✗ 无 RouteAttempt、无预算预留、无派发围栏、**无上游 HTTP**
```

**关键性质**：即使该付费模型此前已被晋级、已有 ACTIVE Pricing Version、并挂在已 ACTIVE 的路由策略上，
Gateway 仍会在**自己的**评估中拒绝它 —— 该场景正是集成测试 §7.3 构造的形态。

### 6.2 Backend 探针/晋级（真正的网络前置）

```
ModelProbeService.probe      ─┐
ModelDiscoveryService.promote ─┴─ 若 profile.templateCode == OPENCODE_ZEN
                                 且 !manifest.isFreeEligible(model)
                                    ⇒ 400（探针）/ 409（晋级）
                                    ★ 在 execute()/fetchLiveModels 之前返回 ⇒ 零 Provider I/O
```

### 6.3 防互相绕过

| 绕过尝试 | 结果 |
| --- | --- |
| 直接调 Gateway 调用历史已晋级的付费模型 | 被 Gateway 独立准入拒绝（§7.3） |
| 先经 Backend 探针再晋级付费模型 | 探针 400、晋级 409（§7.2） |
| 手工在 DB 中把 `last_probe_status` 置为 `PASS` 后晋级 | 仍 409（准入门槛独立于探针结果） |
| 依赖 `-free` 后缀 | 两侧均为允许清单，后缀无用（§7.1、§7.2） |
| 依赖 V2 Console 免费行 | 不在允许清单，`UNKNOWN` ⇒ 拒绝 |

### 6.4 错误信息安全性

- Backend：`MODEL_NOT_VERIFIED` + 文案「OpenCode Zen is admitted for verified-free Chat Completions models only;…」
  —— **不含**上游响应体、凭证、端点细节或模型价格。
- Gateway：`GATEWAY_FORBIDDEN`（HTTP 403）+ 「No eligible Provider route is available for the requested model」
  —— 与既有的「无可用路由」文案**一致**，因此**不泄露**某模型是否恰好存在、是否付费、是否被系统识别。
  细分原因仅进入服务端指标（`gateway_candidate_rejection_total{reason="FREE_ADMISSION_REQUIRED"}`）。

## 7. 测试证据（先失败、后通过）

### 7.1 失败复现（实施前，真实执行）

| 范围 | 命令 | 实施前结果 | 退出码 |
| --- | --- | --- | --- |
| Backend 免费准入单测 | `.\mvnw.cmd -B test "-Dtest=OpenCodeFreeOnlyAdmissionTest"` | **编译失败**：12 处 `找不到符号: 方法 isFreeEligible(String)` | **1** |
| Gateway 免费准入单测 | `.\mvnw.cmd -B test "-Dtest=OpenCodeFreeOnlyAdmissionTest"` | **编译失败**：`OpenCodeFreeOnlyAdmission` 不存在 | **1** |

> Gateway 集成测试同理在实施前无法编译（依赖生产策略类），故同样构成失败复现。

### 7.2 变异验证（证明门槛确实承重）

为避免「测试通过但其实没测到」，本批对两层门槛各做一次**定向变异**（临时插入无条件放行）并确认测试**转红**：

| 变异 | 命令 | 结果 | 退出码 |
| --- | --- | --- | --- |
| `isFreeEligible` 无条件 `true` | `.\mvnw.cmd -B test "-Dtest=OpenCodeFreeOnlyAdmissionTest"`（Backend） | Tests run **7**, Failures **5** | **1** |
| `admits` 无条件 `true` | `.\mvnw.cmd -B test "-Dtest=OpenCodeFreeOnlyAdmissionTest"`（Gateway） | Tests run **28**, Failures **26** | **1** |

变异已**全部回滚**（`git diff` 中不存在 `MUTATION-PROOF` / `if (true)`）。

### 7.3 新增测试

| 文件 | 类型 | 项数 | 覆盖点 |
| --- | --- | --- | --- |
| `OpenCodeFreeOnlyAdmissionTest`（Backend） | 单元 | 7 | 5 条准入；17 条 PAID 无准入；下线免费无准入；未知/不支持/Console-only/后缀推断 fail closed；**未把 `PAID` 改写为 `VERIFIED_FREE`**；策略版本可追溯 |
| `OpenCodeFreeOnlyAdmissionIntegrationTest`（Backend） | 集成（真实 MySQL 8.4 Testcontainers） | 6 | 准入免费模型可探针晋级且**零** Pricing Version；**PAID 即便有历史探针 PASS 也 400/409**；被拒探针不落任何探针证据（`last_probed_at` 为 NULL ⇒ 零 Provider I/O）；未知/不支持/下线全 fail closed；下线条目保留血统；自定义连接不受影响 |
| `OpenCodeFreeOnlyAdmissionTest`（Gateway） | 单元（参数化） | 28 | 准入清单稳定；付费模型无一豁免；未知/不支持/下线/Console-only/后缀/空值全 fail closed；**其他 Provider 不受影响**；策略版本与清单锁定 |
| `OpenCodeFreeAdmissionDispatchBoundaryIntegrationTest`（Gateway） | 集成（真实 MySQL 8.4 Testcontainers，Mock HTTP Server） | 5 | 见 §7.4 |
| `OpenCodeManifestDriftIntegrationTest`（同步既有） | 集成 | +1（共 8） | 新增 `newlyEvidencedPaidChatModelIsRefusedByFreeAdmission`；原「PAID 晋级成功」用例改为使用**准入免费**模型 |
| `OpenCodeManifestIntegrationTest`（同步既有） | 集成 | 10 | `realChatModelWithSimulatedProbePromotes` → `admittedFreeChatModelWithSimulatedProbePromotes`，改用 `nemotron-3.5-lightning-free` |

### 7.4 Gateway 派发边界集成测试（需求 #2/#3/#4/#5 的直接证据）

构造一个**看起来完全合法**的付费路由：ACTIVE 路由策略 + ACTIVE 候选 + ACTIVE Pricing Version + ACTIVE 凭证 +
chat-capable `provider_model` + 已记录探针 PASS（即「本轮之前就已晋级的付费模型」的形态），
并以 `Mock HTTP Server` 作为上游（**不调用真实付费 API**）：

| 用例 | 断言 |
| --- | --- |
| 付费模型普通调用 | HTTP **403**；上游命中数 **0**；`gateway_route_attempt` 数 **0** |
| 付费模型**流式**调用 | HTTP **403**；上游命中数 **0**；路由尝试数 **0** |
| 下线/未知/Console-only（5 个 id，逐一） | HTTP **403**；上游命中数 **0**；路由尝试数 **0** |
| **对照：准入免费模型** | **非** 403 **且**路由尝试数 ≥ 1（证明拒绝确实来自免费准入，而非「夹具本就不可派发」） |
| 对照：非 OpenCode 路由（MiMo） | 非 403（证明其他 Provider 行为未变） |

> 对照用例是本批最重要的**反证设计**：若夹具本身不可派发，则「付费被 403」毫无意义。
> 对照证明同一夹具下准入模型会推进到派发规划，因此差异**只能**归因于免费准入。

### 7.5 定向回归（改动后，真实执行）

| 模块 | 命令 | 结果 | 退出码 |
| --- | --- | --- | --- |
| Backend surefire | `.\mvnw.cmd -B test "-Dtest=OpenCode*,M18TransportRegressionTest,ProviderHubApiIntegrationTest,AuthenticatedConnectionProbeIntegrationTest,StreamingCapabilityProbeIntegrationTest,ProviderCrossOriginSecretIntegrationTest,SseFractionalIndexIntegrationTest,AuthenticatedLiveDiscoveryIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false"` | Tests run **95**, 0F/0E | **0** |
| Backend failsafe | `.\mvnw.cmd -B test-compile failsafe:integration-test failsafe:verify "-Dit.test=OpenCodeFreeOnlyAdmissionIntegrationTest,OpenCodeManifestDriftIntegrationTest,OpenCodeManifestIntegrationTest"` | Tests run **24**, 0F/0E | **0** |
| Backend 单测（清单/治理） | `.\mvnw.cmd -B test "-Dtest=OpenCodeFreeOnlyAdmissionTest,OpenCodeProductionManifestTest,OpenCodeManifestDriftTest,OpenCodeZenEndpointContractTest,OpenCodeV1V2EndpointGovernanceTest,M18TransportRegressionTest"` | Tests run **39**, 0F/0E | **0** |
| Gateway surefire | `.\mvnw.cmd -B test "-Dtest=OpenCode*,CandidateEligibilityEvaluatorTest,DeterministicRouteSelectorTest,PublicOnlyResolverTest,ProviderChatAdapterRegistryTest,ProviderExecutionContextResolverTest,GatewayArchitectureTest"` | Tests run **58**, 0F/0E | **0** |
| Gateway failsafe | `.\mvnw.cmd -B test-compile failsafe:integration-test failsafe:verify "-Dit.test=OpenCodeFreeAdmissionDispatchBoundaryIntegrationTest"` | Tests run **5**, 0F/0E | **0** |

### 7.6 根目录 `npm run verify`

命令（仓库根）：`npm run verify`；日志：`.local-dev/reviews/2026-10-16-opencode-free-only/root-npm-verify.log`。

<!-- VERIFY-RESULT -->

**结果：全部阶段通过，退出码 `0`（一次通过，无重跑）。**

| 阶段 | 结果 |
| --- | --- |
| 脚本单测 | `tests 24` / `pass 24` / `fail 0` |
| 脚本生命周期集成（真实 dev/infra CLI 端到端） | `tests 2` / `pass 2` / `fail 0` |
| 后端 surefire | **679** 项，0 failures、0 errors、1 skipped（Windows 平台） |
| 后端 failsafe | **1256** 项，0 failures、0 errors |
| Gateway surefire | **205** 项，0 failures、0 errors |
| Gateway failsafe | **91** 项，0 failures、0 errors |
| 前端 test | **72** 文件 / **547** 项，全部通过 |
| 前端 lint | 通过（无输出） |
| 前端 production build | 通过（`✓ built in 698ms`） |
| 两项 Maven `verify` lifecycle | 均 `BUILD SUCCESS` |
| **退出码** | **0** |

计数对照（证明本批新增测试确实进入全量结果，且未删改既有测试）：

| 范围 | 上一批（上批 M-2 §7.4） | 本批 | 差 | 对应新增 |
| --- | --- | --- | --- | --- |
| Backend surefire | 672 | **679** | **+7** | `OpenCodeFreeOnlyAdmissionTest`（7 项） |
| Backend failsafe | 1249 | **1256** | **+7** | `OpenCodeFreeOnlyAdmissionIntegrationTest`（6 项）+ `OpenCodeManifestDriftIntegrationTest` 新增 1 项 |
| Gateway surefire | 177 | **205** | **+28** | `OpenCodeFreeOnlyAdmissionTest`（28 项） |
| Gateway failsafe | 86 | **91** | **+5** | `OpenCodeFreeAdmissionDispatchBoundaryIntegrationTest`（5 项） |

> 四个差值**逐一可解释**，无任何既有测试被删除或跳过；前端 547 项与上一批一致（本批未改前端）。
> 全程真实 Testcontainers（MySQL 8.4 / Redis / MinIO），未绕过 Docker 检查；
> 未删除失败测试、未放宽断言、未延长超时；未发起任何可计费推理。
> 本批 `npm run verify` **一次通过**，未出现上批复核记录的环境超时抖动。

## 8. 保留不变的安全、财务与事务约束

- **端点与协议准入**：`https://opencode.ai/zen/v1` + `/chat/completions` **未改**；Responses / Messages /
  Gemini 原生 / SystemOne 仍 fail closed；未引入 V2 Console / Go / 其他协议族。
- **代理隔离**：未接入本地 Python 代理；未新增代理服务/端口/中转；未复制匿名凭证、会话模拟头、代理回退、占位工具。
- **SSRF / DNS / 重定向**：`PublicOnlyAddressResolverGroup`、`DispatchEndpointGuard`、`CustomEndpointValidator`、
  `followRedirect(false)` + 逐跳同源校验 **未放宽**；Gateway 免费准入**不**替代这些既有防线。
- **凭证保护**：`OpenCodeZenChatAdapter` 仍 `noProxy()` 且仅接受 `BEARER_TOKEN`。
- **财务正确性**：未按 `-free` 后缀推断免费；未用 V2 定价推断 V1；**未改动/新建任何 Pricing Version**；
  未修改任何历史财务记录（Ledger / Settlement / Usage / Reservation / Close）；`VERIFIED_FREE` 仍**不**自动生成零价格版本。
- **数据库**：**未新增/修改任何 SQL 迁移**（本批无 `V30`）。
- **事务契约**：拒绝发生在派发/预算/围栏之前，未触碰任何事务契约；未新增重试。
- **清单血统**：**未删除任何** `OpenCodeModelManifest` 条目（仍 70 条 = 29 Chat + 41 UNSUPPORTED）；
  未改写任何既有条目的协议或计价分类。

## 9. 是否仍存在意外收费路径（需求 #5 的诚实回答）

| 潜在路径 | 本批后的状态 |
| --- | --- |
| 直接调 Gateway 调用付费 OpenCode 模型 | **已封堵**（Gateway 独立准入，§7.3） |
| 调用历史上已晋级、已有 Pricing Version 的付费模型 | **已封堵**（同一准入在评估阶段生效，早于预算与围栏） |
| 经 Backend 探针/晋级「洗白」付费模型 | **已封堵**（探针 400、晋级 409，§7.2） |
| 下线的免费模型（`mimo-v2.5-free` 等 7 条）被调用 | **已封堵**（第三个事实「当前被服务」不成立） |
| 按 `-free` 后缀命名的新模型被调用 | **已封堵**（允许清单，非后缀） |
| V2 Console 免费模型被当作 V1 免费调用 | **已封堵**（V1 模板不寻址 V2；清单拒绝） |
| **自定义** `CUSTOM_OPENAI_COMPATIBLE` 连接指向付费上游 | **未变更（既有设计边界）**。自定义连接的协议与端点由创建者显式声明，manifest 不介入；其成本由 Pricing Version + 预算门控。**这不是本批引入，也未被本批放宽**。 |
| 其他 Provider（MIMO 等）付费模型 | **未变更**。测试断言其行为不受本批影响。 |
| 真实付费 API 调用 | **本批零执行**（§7 全部使用 Mock HTTP Server / Testcontainers / 真实 MySQL，未发起任何可计费推理） |

**结论**：**在 `OPENCODE_ZEN` 直连范围内，本批未发现残留的意外收费路径**。
自定义连接与第三方 Provider 的付费调用属于既有设计范围，不因本批放宽，也不在本批的修正范围内。

**未验证项（如实声明）**：本批**未**对真实 OpenCode 上游发起任何请求，因此
「上述 5 条准入模型在真实 V1 Zen 上确实零计费」**未经真实调用验证** —— 依据是官方 V1 定价表的 `Free` 行。
若官方定价表变更，须按 §4 规则重新判定并前移 `FREE_ADMISSION_POLICY_VERSION`。

## 10. 未解决 / 未验证项

| # | 项 | 状态 |
| --- | --- | --- |
| F-1 | 准入模型在真实 V1 Zen 的**零计费**未经真实调用验证 | **未验证（有意为之）**。依据为官方定价表 `Free` 行；不发起可计费请求是本项目的既定约束 |
| F-2 | 实时目录 id 集合的长期稳定性 | **未决**。目录为实时编辑的内部运营目录；下线条目须在重新上架后按 §4 三事实规则重新准入 |
| F-3 | Backend 与 Gateway 允许清单的一致性 | **已由测试锁定**（两侧均断言同一 5 条集合）。因两模块独立部署，清单为有意重复；未来新增准入模型须**同时**更新两侧并前移两侧策略版本 |
| F-4 | V1 官方文档入口 | 当前 302 → V2（沿用上批 R-5）。本批 V1 证据取自 Wayback 官方原文存档 |
| F-5 | `x-opencode-session` 会话头等官方要求 | **未决**（沿用上批复核 §9 R-1，本批不动） |
| F-6 | 真实付费推理 / 真实 V1 联调 / 远程 CI | **未执行**（默认禁止付费请求） |

## 11. 提交与推送

- 起始 HEAD：`68ef79dd0e0d77607938a7b1dcfb0a297843d1da`
- 目标分支：`codex/history-coverage-20261012`，**非强制**推送（fast-forward）；未建 PR、未合并 main、未部署。
- 本批内容：Backend `OpenCodeModelManifest`/`ModelProbeService`/`ModelDiscoveryService`；
  Gateway `OpenCodeFreeOnlyAdmission`（新增）/`CandidateEligibilityEvaluator`；
  5 个新增测试类；3 个既有测试期望同步；本记录。
- 未跟踪的 `.freebuff/` 原样保留，未纳入任何提交。

推送结果与最终 SHA 见同目录 `2026-10-16-opencode-free-only-push.md`。

<!-- COMMITS -->

（沿用本目录既有约定：验收文档自身的提交不写死在其文内。）
