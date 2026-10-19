# 2026-10-16 OpenCode Manifest 目录漂移定向调查与最小修复（M-2）

基线：`codex/history-coverage-20261012@af7fd6436e5b90297a5fb8e54c4c77ee38ed8260`（本地 = 上批已推送 HEAD）。
上游记录：`docs/03-acceptance/2026-10-16-opencode-v1v2-endpoint-review.md`（下称「上批复核」），其 §9 的 M-2 就是本批对象。
核查时间：**2026-10-16（Asia/Shanghai）**；本批全部官方抓取证据保存在
`.local-dev/reviews/2026-10-16-opencode-manifest-drift/`（`.local-dev/` 被 `.gitignore:66` 忽略，**不进 Git**；
下表给出关键文件的 SHA-256 以便独立复核 —— 但**该存档只存在于本机**，异地复核者需按 §3.1 的 URL 重新抓取）。

关键证据指纹：

| 文件 | SHA-256 |
| --- | --- |
| `v1-zen-models.json`（S1） | `4c4de9b688472681b1ac4cfa8ae70c42a56765f1e44d481e0433c4eae4d39fbf` |
| `v2-console-models.json`（S2） | `4c4de9b688472681b1ac4cfa8ae70c42a56765f1e44d481e0433c4eae4d39fbf`（与 S1 同） |
| `v1-zen-doc-20261011170423.txt`（S6） | `e4819c31fad4503da5c383d94b0b43e3d315e0e7c79b7838802ce70e13fb105d` |
| `v1-zen-doc-20260911215727.txt`（S7） | `33645350c93550bc484c6bcc0bb0e64d141b307c0e80de52ea0c19ccdcf33eff` |

本批**改动范围**：`OpenCodeModelManifest` 新增 9 条 Chat 分类 + 版本/来源元数据前移、2 个既有测试的元数据断言同步、
新增 1 个单元测试（8 项）与 1 个集成测试（7 项）、本记录。**未**改端点/协议准入/凭证/网络策略/迁移/定价版本。
未跟踪的 `.freebuff/` 原样保留；无 `reset --hard` / `git clean` / 强制推送；未建 PR、未合并 main、未部署、未发起任何可计费推理。

## 1. 本批结论（先给结果）

1. **漂移确认成立，且比上批记录更精确**：manifest 冻结于 `2026-09-11`，而官方 V1 Zen 目录与端点表其后继续演进。
   实测（2026-10-16）：V1 Zen 目录 **86** 条，manifest **70** 条（本批修复后；修复前 61 条）。
2. **修复范围严格限定在「V1 Zen 双证据」模型**：只有**同时**具备 ①官方 V1 Zen 端点表中
   `https://opencode.ai/zen/v1/chat/completions` 行与 ②官方 V1 Zen 定价表行的模型，才允许进入 Chat 兼容清单。
   满足该双证据条件的共 **9** 个，全部为**纯新增**（不改任何既有条目的协议或计价分类）。
3. **V1 Zen / V2 Console / Go 三个产品的计费边界被严格区分**：V1 与 V2 目录当前**字节级相同**（§3.2），
   但按上批复核 §5 的结论，**凭证产品不同**，故**不**得因目录同体而把 V2 Console 的免费行当作 V1 Zen 免费。
   本批因此**拒绝**把 5 个「仅 V2 Console 免费、无 V1 证据」的 id（含 `deepseek-v4.1-flash-free`）纳入清单（§5.3）。
4. **fail closed 保持不变**：修复后 live 目录中仍有 **25** 条不在清单内，一律 `AVAILABLE` + `UNKNOWN`，
   不得探针/晋级。其中 18 条其实**有**官方 V1 端点表行但属 Responses/Messages/Gemini/SystemOne 族（`UNSUPPORTED` 语义）。
   **本批不为覆盖更多模型而放宽任何协议准入**（要求 #9）。
5. **未删任何历史血统**：7 个已下线免费 Chat 条目、2 个已下线的 Messages 条目**全部保留**（§6），
   修复后清单 70 条 = 29 Chat + 41 UNSUPPORTED。**未更新任何已有 ACTIVE Pricing Version**，也**未新建**任何 Pricing Version。

## 2. 仓库基线与边界

| 项 | 值 |
| --- | --- |
| 起始 HEAD | `af7fd6436e5b90297a5fb8e54c4c77ee38ed8260`（`docs(acceptance): record OpenCode V1/V2 endpoint review...`） |
| 目标分支 | `codex/history-coverage-20261012` |
| 工作区 | 仅有未跟踪 `.freebuff/`（未触碰） |
| 危险操作 | 未执行 `reset --hard` / `git clean` / 强制推送 |

## 3. 官方资料（核查时间与保存路径）

### 3.1 抓取清单

| # | 资料 | URL | 结果 | 本地存档 |
| --- | --- | --- | --- | --- |
| S1 | V1 Zen 实时目录 | `https://opencode.ai/zen/v1/models` | 200，**86** id，`created` 单调 1792113771→1792113785 | `v1-zen-models.json` |
| S2 | V2 Console 实时目录 | `https://opencode.ai/inference/v1/models` | 200，**与 S1 字节级相同**（7219 B） | `v2-console-models.json` |
| S3 | V2 Console Models 权威页 | `https://opencode.ai/v2/docs/console/models/` | 200（端点表 + 定价表 + Free 列表 + Deprecated 表） | `v2-console-models.html/.txt` |
| S4 | V2 Console Inference API | `https://opencode.ai/v2/docs/console/api/inference` | 200 | `v2-console-api-inference.txt` |
| S5 | V2 Console Go 页 | `https://opencode.ai/v2/docs/console/go` | 200（订阅制 `$10/$40`，模型清单与 Console **不同**） | `v2-console-go.txt` |
| S6 | **V1 Zen 官方端点表（修订 2026-10-11T17:04:23Z）** | Wayback `20261011170423id_/https://opencode.ai/docs/zen` | 200，88 端点行 / 92 处 `zen/v1` | `v1-zen-doc-20261011170423.txt` |
| S7 | **V1 Zen 官方端点表（修订 2026-09-11，即 manifest 声称的 `SOURCE_REVISION`）** | Wayback `20260911215727id_/.../docs/zen` | 200，69 端点行 | `v1-zen-doc-20260911215727.txt` |
| S8 | V1 Zen 端点表历史快照 ×13（2025-09-19 → 2026-10-11） | Wayback CDX + `id_` 抓取 | 均 200 | `v1-zen-doc-*.txt` |
| S9 | V1 Zen 目录历史快照 ×15（2026-03-02 → 2026-10-03） | Wayback CDX `opencode.ai/zen/v1/models` | 有效 15 份（2 份存档体损坏，已剔除） | `v1-zen-catalog-ids-*.txt` |
| S10 | `models.dev/api.json`（OpenCode 官方 CLI 的模型目录来源，官方文档明示） | `https://models.dev/api.json` | 200，5.67 MB；provider `opencode` 124 模型、`opencode-go` 31 模型 | `models-dev-api.json` |

> **文档状态更正（与上批复核一致）**：`https://docs.opencode.ai/docs/zen/` **404**；
> `https://opencode.ai/docs/zen`、`/docs/zen/` 及 6 个语言前缀均 **302** → `https://opencode.ai/v2/docs/console/models/`。
> 但 **V1 Zen 官方文档源在 2026-10-11 仍以 `zen/v1` 为主体持续更新**（S6 有 88 行端点表、0 处 `/inference/`），
> 且 V1 端点实测存活（上批复核 §2.1）。**文档合并 ≠ 端点退役，也 ≠ 模型目录冻结**。

### 3.2 目录同体事实（要求 #5 的关键约束）

> 与上批复核的计数差异说明：上批复核 §2.2 在同一日测得 **87** 个模型，本批测得 **86** 个。
> 上批复核**未**把该目录原始响应落入仓库，故本批**无法**逐 id 重建这 1 条的精确差集；
> 本批已把当次原始响应完整存档（`v1-zen-models.json` / `v2-console-models.json`，SHA 可复算）以避免同类不可复现问题。
> 该差异**不影响**任何结论：两批都以**当次实测的 live 目录**为「当前可用」口径，且都只据 **V1 官方文档**做分类。
> 另注：该目录 `created` 时间戳集中在 1792113771–1792113785 的数秒窗口内，说明其为**实时编辑的内部运营目录**。

S1 与 S2 **字节级完全相同**：两者 SHA-256 均为
`4c4de9b688472681b1ac4cfa8ae70c42a56765f1e44d481e0433c4eae4d39fbf`（7219 字节，86 个 id，同一 `created` 集）。
上批复核 §2.2 已记录该事实。因此本批**明确拒绝**以下两种推断：

- ❌「目录相同 ⇒ 凭证/额度/价格相同」。V2 Console 是**服务账号密钥 `oc_sk_...`**、按量付费、$5 自动充值（S3/S4）；
  V1 Zen 是**另一套 API Key**。上批复核 §5 已判定二者**禁止混用**。
- ❌「V2 定价表说 Free ⇒ V1 Zen 也 Free」。V1 与 V2 是**两份独立价目表**，本批只用 **V1 Zen 自己的**定价表（S6）分类。

### 3.3 三产品边界（要求 #5）

| 维度 | V1 Zen（项目现用模板） | V2 Console | OpenCode Go |
| --- | --- | --- | --- |
| 推理基址 | `https://opencode.ai/zen/v1` | `https://opencode.ai/inference` | `https://opencode.ai/inference/go/v1` |
| Chat 路径 | `/zen/v1/chat/completions` | `/inference/openai/v1/chat/completions` | （经 Go 网关，S5/S10） |
| 凭证 | Zen API Key（`BEARER`） | 服务账号密钥 `oc_sk_...` | Go 订阅密钥 |
| 计费 | 按量付费（V1 价目表，S6） | 按量付费（V2 价目表，S3） | **订阅制**：Go `$10/月`、Go Plus `$40/月`（S5） |
| 目录/清单 | `/zen/v1/models`（S1） | `/inference/v1/models`（S2） | Go 模型清单与 Console **不同**（S5） |
| 本批处理 | **仅此产品参与分类** | **只作佐证，不作 V1 分类依据** | **不引入任何 Go 语义** |

> **Go 产品证据（S5）**：Go 的模型清单含 `MiMo-V2.6-Pro`、`Step 5 Preview`、`Hy4 preview`、`Hy3` 等
> **Console 目录中没有**的条目，且计费为订阅制。这从反面证明：**三个产品的可用模型集合与计费方式并不共享**，
> 因此**不能**用任一产品的清单/价目去推断另一个产品。项目当前不持有 Go 凭证，也不引入 Go 模板（沿用上批复核 §6.2 选项 C 的边界）。

## 4. 现有 V1 Zen 模板能用哪些模型（要求 #6）

项目内置模板 `OPENCODE_ZEN` 冻结为：`base=https://opencode.ai/zen/v1`、`completion=/chat/completions`、
`models=/models`、`auth=BEARER`、`network=DIRECT_ONLY`（`ProviderTemplateRegistry.java:21-32`）。
即**只有 Chat Completions 家族**可探针/晋级；Responses / Messages / Gemini 原生 / SystemOne 一律 `UNSUPPORTED`。

因此本批回答「现有 V1 模板能用哪些模型」为：**官方 V1 Zen 端点表中端点为 `/zen/v1/chat/completions` 的那一族**。

| 时点 | 官方 V1 端点表 chat 行数 | 说明 |
| --- | --- | --- |
| 2026-09-11（manifest 原始 `SOURCE_REVISION`，S7） | **20** | 与修复前 manifest 的 20 条 CHAT 完全吻合 —— 证明原分类**当时正确** |
| 2026-10-11（现行 V1 文档修订，S6） | **29** | 净增 9 条 |

**结论**：修复前 manifest 并非「错」，而是**冻结在 2026-09-11**；漂移是时间造成的，故修复必须是**增量的**，
而不是重写分类。本批新增的 9 条 chat 模型恰好把 CHAT 分类从 20 条推进到 29 条，与现行 V1 端点表**逐条对齐**。

## 5. 精确集合差异表（要求 #4：完整列出，不只给数量）

集合口径：`LIVE` = S1 实测（86）；`MANIFEST_before` = 修复前 61 条；`MANIFEST_after` = 修复后 70 条；`V1DOC` = S6（88 行）。

### 5.1 新增（Live + V1 Chat 端点行 + V1 定价行 ⇒ 本批纳入）

| # | 模型 ID | V1 端点行（S6） | V1 定价行（S6） | 本批分类 |
| --- | --- | --- | --- | --- |
| 1 | `deepseek-v4.1-flash` | `/zen/v1/chat/completions` | `$0.30 / $1.20 / cache $0.006` | `CHAT` + `PAID` |
| 2 | `glm-5.3` | `/zen/v1/chat/completions` | `$1.40 / $4.40 / cache $0.26` | `CHAT` + `PAID` |
| 3 | `glm-5.3-flash` | `/zen/v1/chat/completions` | `$0.15 / $0.50 / cache $0.03` | `CHAT` + `PAID` |
| 4 | `mistral-large-4` | `/zen/v1/chat/completions` | `50% off`：`$1.36→$0.68 / $4.18→$2.09` | `CHAT` + `PAID` |
| 5 | `qwen3.8-max` | `/zen/v1/chat/completions` | `$2.00 / $6.00 / cache $0.25 / write $2.50` | `CHAT` + `PAID` |
| 6 | `longcat-2.5-preview-free` | `/zen/v1/chat/completions` | **Free / Free / Free**（名列表述：limited time） | `CHAT` + `VERIFIED_FREE` |
| 7 | `mimo-v2.6-flash-free` | `/zen/v1/chat/completions` | **Free / Free / Free** | `CHAT` + `VERIFIED_FREE` |
| 8 | `nemotron-3.5-lightning-free` | `/zen/v1/chat/completions` | **Free / Free / Free** | `CHAT` + `VERIFIED_FREE` |
| 9 | `space-bunny-free` | `/zen/v1/chat/completions` | **Free / Free / Free** | `CHAT` + `VERIFIED_FREE` |

> 这 9 条**全部同时**具备「官方端点行」与「官方定价行」两类独立证据；
> 5 条 paid 的价格与 4 条 free 的 Free 行均取自 **V1 Zen 自己的**价目表，**不是**从 V2 或 `-free` 后缀推断。

### 5.2 保留（分类不变，逐项核对正确）

**(a) 原有 CHAT 条目仍与 V1 端点表一致（20 → 20，全部命中 `chat/completions`）**

| 分类 | 模型 ID | V1 端点行 | 备注 |
| --- | --- | --- | --- |
| `PAID` | `deepseek-v4-pro`、`deepseek-v4-flash`、`minimax-m3`、`minimax-m2.7`、`minimax-m2.5`、`glm-5.2`、`glm-5.1`、`glm-5`、`kimi-k2.5`、`kimi-k2.6`、`kimi-k2.7-code`、`kimi-k3` | 均 `/zen/v1/chat/completions` | 12 条全部保留，**未改分类** |
| `VERIFIED_FREE` | `big-pickle` | `/zen/v1/chat/completions` | 无 `-free` 后缀却 Free ⇒ 证明来源是价目表而非后缀 |
| `VERIFIED_FREE` | `mimo-v2.5-free`、`nemotron-3-ultra-free` | `/zen/v1/chat/completions` | V1 端点表与 V1 定价表**均有**该行，但**已不在 live 目录**（见 §5.4） |

**(b) `UNSUPPORTED` 条目（41 条，全部保留；本批未改任何一条）**

`gpt-5.6-sol/terra/luna`、`gpt-5.5`、`gpt-5.5-pro`、`gpt-5.4`、`gpt-5.4-pro/mini/nano`、
`gpt-5.3-codex`、`gpt-5.3-codex-spark`、`gpt-5.2`、`gpt-5.2-codex`、`gpt-5.1`、`gpt-5.1-codex(-max/-mini)`、
`gpt-5`、`gpt-5-codex`、`gpt-5-nano`（Responses 族）、`grok-4.5`、`grok-build-0.1`（Responses 族）、
`claude-fable-5`、`claude-opus-5/4-8/4-7/4-6/4-5`、`claude-sonnet-5/4-6/4-5`、`claude-haiku-4-5`（Messages 族）、
`qwen3.7-max`、`qwen3.7-plus`、`qwen3.6-plus`、`qwen3.5-plus`（Messages 族）、
`gemini-3.6-flash`、`gemini-3.5-flash`、`gemini-3.5-flash-lite`、`gemini-3.1-pro`、`gemini-3-flash`（Gemini 原生族）。
已逐条复核：**无一条**在 V1 端点表中是 `chat/completions`（即不存在「把 Chat 误标为 UNSUPPORTED」）。

### 5.3 未纳入 —— Console-only 或证据不足（保持 `UNKNOWN`，fail closed）

**这是本批最重要的「拒绝修改」清单**：它们在 live 目录中，但**没有 V1 Zen 证据**。

| # | 模型 ID | live | V1 Zen 端点行（S6） | V2 Console 状态（S3/S10） | 处理 |
| --- | --- | --- | --- | --- | --- |
| 1 | `deepseek-v4.1-flash-free` | ✅ | **无** | **列在 Free 定价表** | **拒绝纳入**：V2 免费 ≠ V1 免费 |
| 2 | `cat-jam-free` | ✅ | **无** | 列在 Free 定价表 | 拒绝纳入 |
| 3 | `muse-spark-1.2-contributor-free` | ✅ | **无** | 列在 Free 定价表 | 拒绝纳入（V1 仅有 `muse-spark-1.3-contributor-free`，属 Responses 族） |
| 4 | `nemotron-3.5-super-vl-free` | ✅ | **无** | 列在 Free 定价表 | 拒绝纳入 |
| 5 | `gemini-4-argon` | ✅ | **无** | 付费（`$4/$20` 5 折） | 拒绝纳入（族未知，需 Gemini 原生 支持） |
| 6 | `claude-opus-5-5-backup` | ✅ | **无** | 未列（内部运营条目） | 拒绝纳入 |
| 7 | `test` | ✅ | **无** | 未列（内部测试条目） | 拒绝纳入 |
| 8 | `claude-haiku-5-5`、`claude-opus-5-5`、`claude-sonnet-5-5` | ✅ | `messages` | Messages 族 | 拒绝纳入 Chat（族不符，正确 fail closed） |
| 9 | `gpt-6-astra`、`gpt-6-luna`、`gpt-6-sol`、`gpt-6.1-sol`、`grok-4.6`、`grok-4.7`、`muse-spark-1.2`、`muse-spark-1.3`、`muse-spark-1.3-contributor-free` | ✅ | `responses` | Responses 族 | 拒绝纳入 Chat |
| 10 | `gemini-3.7-flash`、`gemini-3.8-flash` | ✅ | `models/<id>` | Gemini 原生族 | 拒绝纳入 Chat |
| 11 | `qwen3.8-flash` | ✅ | `messages` | Messages 族 | 拒绝纳入 Chat |
| 12 | `jev-1.13`、`jev-1.13-free` | ✅ | `systemone` | SystemOne | 拒绝纳入 Chat |
| 13 | `claude-fable-5-1` | ✅ | `messages` | Messages 族 | 拒绝纳入 Chat |

> 第 8–13 组**其实有官方 V1 端点行**，但族**不是** Chat Completions，项目无该族实现 ⇒ 必须 `UNKNOWN`/`UNSUPPORTED`
> 并 fail closed。**本批没有**为了「多覆盖模型」而把它们塞进 Chat（要求 #9）。
> 第 1–7 组则是**连 V1 端点行都没有**的条目。

### 5.4 移除（本批**零移除**）

| 类别 | 模型 ID | 处理 |
| --- | --- | --- |
| 已下线免费 Chat（不影响既定分类，保留血统） | `deepseek-v4-flash-free`、`laguna-s-2.1-free`、`ling-3.0-tiny-free`、`longcat-2.0-free`、`north-mini-code-free`、`mimo-v2.5-free`、`nemotron-3-ultra-free` | **保留**，`CHAT`+`VERIFIED_FREE` 不变 |
| 已下线 Messages | `qwen3.7-max`、`qwen3.7-plus` | **保留**，`UNSUPPORTED`+`UNKNOWN` 不变 |

> 上批复核 M-2 列出 7 个「已不在官方目录的 CHAT 条目」，与上表一致。另需区分两种「不在 live 目录」：
> `mimo-v2.5-free`、`nemotron-3-ultra-free` **仍有** V1 端点行与 V1 定价行（S6），只是当前未在 live 目录中列出；
> 二者与其余 5 条一样，依要求 #8 **一律不删除**。

### 5.4.1 有完整 V1 双证据但**当前未被服务**的 5 条（本批有意**不新增**）

下列 5 条**同时具备**官方 V1 端点行（`chat/completions`）与官方 V1 定价行，证据强度与 §5.1 的 9 条**同级**，
但它们**当前不在 live V1 目录（S1）中**，即现有 V1 模板今天**无法寻址**到它们：

| # | 模型 ID | V1 端点行（S6） | V1 定价行（S6） | live（S1） | 本批处理 |
| --- | --- | --- | --- | --- | --- |
| 1 | `deepseek-v4-flash-vision-exp` | `/zen/v1/chat/completions` | `$0.14 / $0.28 / cache $0.028` | ❌ | 不新增 |
| 2 | `exo-free` | `/zen/v1/chat/completions` | **Free** | ❌ | 不新增 |
| 3 | `ling-3.0-flash-fin-free` | `/zen/v1/chat/completions` | **Free** | ❌ | 不新增 |
| 4 | `ling-3.1-flash-free` | `/zen/v1/chat/completions` | **Free** | ❌ | 不新增 |
| 5 | `step-5-preview-free` | `/zen/v1/chat/completions` | **Free** | ❌ | 不新增 |

> **为什么与 §5.1 区别对待（本批采用的完整判定规则，三条互斥）**：
> 1. **当前由 V1 Zen 服务 + 官方 V1 双证据 ⇒ 新增**（§5.1 的 9 条）。live 目录是「现有模板能用哪些模型」的权威口径（要求 #6）；
> 2. **已在清单中、只是不再被服务 ⇒ 绝不删除**（本节上表，要求 #8 血统保留）。这是**祖父条款**，不是新增；
> 3. **有官方证据但从未分类、且当前不被服务 ⇒ 不新增**（本表）。清单是**受控可路由准入表**，
>    为一个今天无法被观察到、也无法被寻址的 id 预先扩大准入面，不满足要求 #10 的「最小修改」；
>    一旦它重新出现在 live 目录，应按与 §5.1 相同的双证据标准纳入（已记入 §9 M-2b）。
>
> 三条规则互斥且可机械复核：`(1)` 新增 9 条全部 `live=✅`；`(2)` 保留 9 条全部 `live=❌` 但修复前已在清单；
> `(3)` 不新增 5 条全部 `live=❌` 且修复前不在清单。

### 5.5 改名

**无改名**。本批对 15 份 V1 文档快照 + 15 份 V1 目录快照做过 id 级比对，未发现同一模型在 V1 产品内更换 id；
`gpt-5.6-*`、`claude-*`、`gemini-*` 等族在快照序列中 id 稳定。故本批**不产生**任何 id 别名映射。

### 5.6 协议改变

**无协议族变更**。以 S7（2026-09-11）与 S6（2026-10-11）逐条比对：

- 族发生变化的 id：**0 条**；
- S7 中出现、S6 中消失的 id：**0 条**（V1 文档在这两个修订间**只增不减**）；
- S6 相对 S7 新增的 id：**19 条**（`claude-haiku-5-5`、`claude-opus-5-5`、`claude-sonnet-5-5`、
  `deepseek-v4.1-flash`、`exo-free`、`gpt-6-luna`、`gpt-6-sol`、`gpt-6.1-sol`、`grok-4.7`、
  `jev-1.13`、`jev-1.13-free`、`ling-3.1-flash-free`、`longcat-2.5-preview-free`、
  `mimo-v2.6-flash-free`、`mistral-large-4`、`qwen3.8-flash`、`qwen3.8-max`、`space-bunny-free`、
  `step-5-preview-free`）。

> **重要**：manifest 中**没有**任何「应为 Chat 却被标为 UNSUPPORTED」的冲突项（已用脚本全量核对，结果为空集）。
> 因此本批**只做纯新增**，不改任何既有协议分类。

### 5.7 计价状态改变

**无既有条目的计价状态被改动**。逐条核对：

- 修复前 20 条 CHAT 的 `PAID`/`VERIFIED_FREE`：**全部保持原值**（其中 8 条为 `VERIFIED_FREE`，
  7 条带 `-free` 后缀、`big-pickle` 不带后缀）；
- 修复前 41 条 `UNSUPPORTED` 的 `PRICING_UNKNOWN`：**全部保持原值**；
- 新增的 9 条：**首次**分类（5 `PAID` + 4 `VERIFIED_FREE`），非「改变」。

> 计价分类**只**引自 V1 Zen 定价表（S6）原文行；`-free` 后缀、live 目录可用性、V2 Console 定价行
> **三者均未**参与推断（要求 #7）。

### 5.8 汇总计数

| 项 | 修复前 | 修复后 |
| --- | --- | --- |
| manifest 条目总数 | 61 | **70**（+9） |
| `CHAT` | 20 | **29**（+9） |
| `UNSUPPORTED` | 41 | **41**（±0） |
| `PAID` | 12 | **17**（+5） |
| `VERIFIED_FREE` | 8 | **12**（+4） |
| live 目录中未分类（fail closed） | **34** | **25**（−9：恰为本批新增的 9 条） |

## 6. 修复决策与最小改动（要求 #10）

### 6.1 先失败测试，后改生产清单

**先**新增 `OpenCodeManifestDriftTest` 并确认其**先失败**（首轮 7 项、失败 3 项）：

| 失败项 | 断言 | 实际 |
| --- | --- | --- |
| `manifestRecordsTheDriftReviewProvenance` | 期望 `2026-10-16-v1` | 实际 `2026-09-11-v2` |
| `newlyServedV1ChatModelsBecomeChatCompatible` | 期望 `OPENAI_CHAT_COMPLETIONS`（`deepseek-v4.1-flash`） | 实际 `UNKNOWN` |
| `newlyServedChatPricingFollowsTheV1ZenPricingTable` | 期望 `PAID`（`deepseek-v4.1-flash`） | 实际 `UNKNOWN` |

退出码 `1`，`Tests run: 7, Failures: 3, Errors: 0`。**随后**才修改生产清单，测试转绿（§7）。

### 6.2 生产改动（唯一生产文件）

| 文件 | 改动 | 理由 |
| --- | --- | --- |
| `OpenCodeModelManifest.java` | ①新增 9 条 `Map.entry`（5 `PAID` + 4 `VERIFIED_FREE`）；②`MANIFEST_VERSION` `2026-09-11-v2`→`2026-10-16-v1`；`VERIFIED_AT`→`2026-10-16`；`SOURCE_REVISION`→`2026-10-11`；`SOURCE`→`OpenCode Zen (V1) official endpoint and pricing tables`；③类注释补充漂移口径与三产品边界 | 使受控清单与官方 V1 **端点表 + 定价表**双证据一致 |

**未改**：任何端点 URL、协议准入、认证类型、超时、网络策略、`DIRECT_ONLY`、SSRF/重定向策略、SQL 迁移、
定价版本、`ModelProbeService` / `ModelDiscoveryService` 的分流与门槛逻辑。**未放宽任何既有断言**。

### 6.3 元数据版本前移的理由（为什么不能只加 9 条）

`MANIFEST_VERSION` 是**受控清单的版本号**，其语义是「本清单所依据的官方修订」。既有测试
（`OpenCodeProductionManifestTest`、`OpenCodeManifestIntegrationTest`）本就断言该版本与 `SOURCE_REVISION`，
说明版本前移是**设计内**行为。若只增 9 条而不前移 `SOURCE_REVISION`，清单将声称自己仍依据 2026-09-11 的文档，
却包含该文档中不存在的分类 —— 那是**不可追溯**的。故本批同步前移，并把 2 个既有测试的元数据断言一并更新
（**只改元数据断言，未改任何语义断言**）。

### 6.4 fail closed 的边界未被放宽（要求 #9）

- `classify()` 逻辑未变：未命中即 `UNKNOWN`/`UNKNOWN`；
- `ModelProbeService` 门槛未变：非 Chat 兼容即 `400`，**零 Provider I/O**；
- `ModelDiscoveryService.promote` 门槛未变：非 Chat 兼容即 `409`；
- 未新增任何「按前缀/按目录批量覆盖」的机制。

## 7. 验证与证据

### 7.1 新增/修改的测试

| 文件 | 类型 | 项数 | 覆盖点 |
| --- | --- | --- | --- |
| `OpenCodeManifestDriftTest` | 单元（新增） | 8 | 版本与来源可追溯；9 条新增 Chat 分类；5 paid/4 free 定价来源；Console-only / 未实现族保持 `UNKNOWN`；后缀推断被拒；V2 免费不外溢到 V1；已下线条目保留血统；**有证据但已从目录下线者不得提前准入**；非 Chat 族不被提升 |
| `OpenCodeManifestDriftIntegrationTest` | 集成（新增，真实 MySQL 8.4 Testcontainers） | 7 | 分类落库（`provider_model_discovery`）；未准入型号双门槛拒绝；新增 Chat 可晋级且**零** Pricing Version；旧型号血统保留；非 Chat 族拒绝；V1 模板未被改到 V2 |
| `OpenCodeProductionManifestTest` | 单元（**仅元数据断言**） | 8 | 版本/`verifiedAt`/`source`/`sourceRevision` |
| `OpenCodeManifestIntegrationTest` | 集成（**仅元数据断言**） | 10 | 同上 |

`OpenCodeProductionManifestTest` 的测试方法名由 `manifestVersionIsV2WithProvenance` 改为
`manifestVersionIsPinnedWithProvenance`（原名称在版本前移后已名不符实）。

### 7.2 定向测试（真实执行，退出码为证）

| 模块 | 命令 | 结果 | 退出码 |
| --- | --- | --- | --- |
| Backend（失败复现，改动前） | `.\mvnw.cmd -B test "-Dtest=OpenCodeManifestDriftTest"` | Tests run: **7**, Failures: **3** | **1** |
| Backend（改动后，含新增） | `.\mvnw.cmd -B test "-Dtest=OpenCodeManifestDriftTest,OpenCodeProductionManifestTest,OpenCodeZenEndpointContractTest,OpenCodeV1V2EndpointGovernanceTest,M18TransportRegressionTest"` | Tests run: **32**, 0F/0E | **0** |
| Backend（回归，含 ProviderHub API） | `.\mvnw.cmd -B test "-Dtest=M18TransportRegressionTest,ProviderHubApiIntegrationTest,OpenCodeZenEndpointContractTest,OpenCodeV1V2EndpointGovernanceTest,OpenCodeManifestDriftTest,OpenCodeProductionManifestTest"` | Tests run: **43**, 0F/0E | **0** |
| Backend（集成，真实 MySQL 8.4 Testcontainers） | `.\mvnw.cmd -B test-compile failsafe:integration-test failsafe:verify "-Dit.test=OpenCodeManifestDriftIntegrationTest,OpenCodeManifestIntegrationTest"` | Tests run: **17**, 0F/0E（含新增 7 项） | **0** |
| Gateway | `.\mvnw.cmd -B test "-Dtest=OpenCodeEndpointContractTest,OpenCodeDirectOnlyTest,PublicOnlyResolverTest"` | Tests run: **9**, 0F/0E | **0** |

### 7.3 旧型号兼容与血统验证（要求）

- 已下线免费 Chat 条目在修复后仍为 `CHAT`+`VERIFIED_FREE`：单元测试覆盖 5 条
  （`deepseek-v4-flash-free`、`laguna-s-2.1-free`、`ling-3.0-tiny-free`、`longcat-2.0-free`、
  `north-mini-code-free`），集成测试覆盖同样 5 条；另 `mimo-v2.5-free`、`nemotron-3-ultra-free`
  两类的分类经离线逐条核对未变（本批未改动其 `Map.entry` 行）；
- 已下线 Messages 条目 `qwen3.7-max`、`qwen3.7-plus` 仍为 `UNSUPPORTED`+`UNKNOWN`
  （单元测试的「非 Chat 族不被提升」项覆盖 `qwen3.7-max`）；
- 集成测试断言**观察行总数 == 投入数**，证明修复未删除任何 `provider_model_discovery` 行；
- `pricing_version` 计数断言为 **0**，证明本批未新建/未改动任何定价版本。

### 7.4 根目录 `npm run verify`

命令（仓库根）：`npm run verify`；日志：`.local-dev/reviews/2026-10-16-opencode-manifest-drift/root-npm-verify.log`。

**结果：全部阶段通过，退出码 `0`。**（在**包含第 8 个单元测试的最终冻结树**上执行；此前一轮因先于该测试的加入而作废，已弃用不计。）

| 阶段 | 结果 |
| --- | --- |
| 脚本单测 | `tests 24` / `pass 24` / `fail 0` |
| 脚本生命周期集成（真实 dev/infra CLI 端到端） | `tests 2` / `pass 2` / `fail 0` |
| 后端 surefire | **672** 项，0 failures、0 errors、1 skipped（Windows 平台） |
| 后端 failsafe | **1249** 项，0 failures、0 errors |
| Gateway surefire | **177** 项，0 failures、0 errors（本批未改 Gateway 代码） |
| Gateway failsafe | **86** 项，0 failures、0 errors |
| 前端 test | **72** 文件 / **547** 项，全部通过 |
| 前端 lint | 通过（`eslint .` 无输出） |
| 前端 production build | 通过（`vite v8.2.1`，`✓ built in 434ms`，3352 modules transformed） |
| 两项 Maven `verify` lifecycle | 均 `BUILD SUCCESS` |
| **退出码** | **0** |

计数对照（证明本批新增测试确实进入全量结果，且未删改既有测试）：

| 范围 | 上一批（上批复核 §8.5） | 本批 | 差 |
| --- | --- | --- | --- |
| Backend surefire | 664 | **672** | **+8** = 新增 `OpenCodeManifestDriftTest`（8 项） |
| Backend failsafe | 1242 | **1249** | **+7** = 新增 `OpenCodeManifestDriftIntegrationTest`（7 项） |
| Gateway surefire | 177 | **177** | ±0（未改 Gateway） |
| Gateway failsafe | 86 | **86** | ±0 |

> 本批 `npm run verify` **一次通过**，未出现上一批记录的 Frontend 超时抖动或脚本控制台窗口断言失败。
> 全程真实 Testcontainers（MySQL 8.4 / Redis / MinIO），未绕过 Docker 检查；未删除失败测试、未放宽断言、未延长超时。
> `OpenCodeManifestDriftTest` 与 `OpenCodeManifestDriftIntegrationTest` 均可在日志中按类名检索到实际执行记录。

## 8. 保留不变的安全与财务约束

- **SSRF / DNS 防护**：`PublicOnlyAddressResolverGroup`、`DispatchEndpointGuard`、`CustomEndpointValidator` 未放宽；
- **重定向校验**：`followRedirect(false)` + 逐跳同源校验，未放宽；
- **`DIRECT_ONLY` / 凭证保护**：`OpenCodeZenChatAdapter` 仍 `noProxy()` 且仅接受 `BEARER_TOKEN`；未引入匿名凭证或回退代理；
- **协议准入**：Responses / Messages / Gemini 原生 / SystemOne 仍 fail closed；未知模型仍 `UNKNOWN`；
- **财务正确性**：未按 `-free` 后缀推断免费；未用 V2 定价推断 V1；未改动/新建定价版本；`VERIFIED_FREE` 仍不自动创建价格；
- **事务契约**：未触碰派发、用量事实、Settlement、Ledger、Budget、Close 任何事务契约；
- **数据库**：未新增/修改任何 SQL 迁移；未改动任何已有 ACTIVE 行。

## 9. 未解决 / 未验证项

| # | 项 | 状态 |
| --- | --- | --- |
| M-2a | `LIVE = 86` 与 `V1DOC = 88` 的差集 | **未决**。差异即 §5.4.1 的 5 条「文档有、live 无」，加 `mimo-v2.5-free`/`nemotron-3-ultra-free` 等已在清单者；已逐条记载 |
| M-2b | `deepseek-v4-flash-vision-exp`、`exo-free`、`ling-3.0-flash-fin-free`、`ling-3.1-flash-free`、`step-5-preview-free` | **未决**。V1 端点表与定价表**均有**其行，但当前不在 live 目录 ⇒ 依 §5.4.1 规则 3 **不提前准入**；一旦重回 live 目录，应按 §5.1 同一双证据标准纳入 |
| M-2c | `gemini-3.7-flash`/`3.8-flash`、`claude-*-5-5`、`gpt-6-*`、`grok-4.6/4.7`、`qwen3.8-flash`、`jev-1.13` | **未决（有意不纳入）**。属 Gemini 原生 / Messages / Responses / SystemOne 族，项目**无该族实现**；纳入需先实现对应协议族 |
| M-2d | V1 目录中的 `test`、`claude-opus-5-5-backup` | **未决**。内部运营条目，不应产品化 |
| M-1 | 是否引入 V2 Console 模板 | **未决**（沿用上批复核 §9 M-1） |
| R-1 | `x-opencode-session` 会话头 | **未决**（沿用上批复核 §9 R-1） |
| R-5 | V1 Zen **官方**文档当前入口 | V1 文档 URL 已 302 至 V2。本批 V1 证据取自 **Wayback 存档的官方 `opencode.ai/docs/zen` 原文**（S6/S7/S8），非第三方镜像；强度高于上批复核的 E9 弱证据，但仍**非**当前在线原页 |
| R-6 | 真实付费推理 / 真实 V1 联调 / 远程 CI | **未执行**（默认禁止付费请求） |

## 10. 提交与推送

- 起始 HEAD：`af7fd6436e5b90297a5fb8e54c4c77ee38ed8260`
- 目标分支：`codex/history-coverage-20261012`，**非强制**推送（fast-forward）；未建 PR、未合并 main、未部署。
- 本批内容：`OpenCodeModelManifest`（9 条新增 + 元数据前移 + 注释）、2 个既有测试元数据断言、
  1 个新增单元测试（8 项）、1 个新增集成测试（7 项）、本记录、上批记录与 M18 冻结文档的**追加**更正标注。
- 未跟踪的 `.freebuff/` 原样保留，未纳入任何提交。

推送结果与最终 SHA 见同目录 `2026-10-16-opencode-manifest-drift-push.md`。

<!-- COMMITS -->

（沿用本目录既有约定：验收文档自身的提交不写死在其文内。）
