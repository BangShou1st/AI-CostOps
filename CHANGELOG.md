# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 规范，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

发布基线与验收证据的权威来源见 `PROJECT_CONTEXT.md` 与 `docs/03-acceptance/`；
本文件是面向变更检索的摘要视图。

## [Unreleased]

`main` 上的稳定版本仍为 `v3.0.0`；以下条目为 `main...dev` 上尚未合并或发布的维护变更摘要。

### Added
- 根级任务编排：根 `package.json` + `scripts/tasks.mjs`（零依赖、跨平台），统一 `dev / build / test / verify / infra:*` 入口
- `scripts/dev/` 补充 `.sh` 版本（`start-infra.sh` / `stop-infra.sh` / `status.sh`），与 `.ps1` 一一对应
- `scripts/README.md`：脚本目录职责说明与里程碑产物归档/命名策略
- 根级 `LICENSE`（专有许可，内部使用）与本 `CHANGELOG.md`
- OpenCode Zen 共享库统一接入（`shared-lib 1.2.0`）与 Zen CLI 协议姿态
- Kilo Gateway 作为第二个内置 Provider 接入
- Cost Intelligence 经审计的成本历史覆盖（`cost_history_coverage` 及 revision 表，Flyway V28）与分析输入身份（`cost_intelligence_run.input_fingerprint`，Flyway V29）
- M21 运维验收补充脚本：`scripts/m21/invoke-v3-operational-financial-smoke.ps1`（财务闭环）与 `scripts/m21/invoke-v3-operational-advisor-smoke.ps1`（Advisor Worker）
- M22 双组织浏览器隔离 E2E（`frontend/e2e/m22-browser-organization-isolation.spec.ts`）与真实 MySQL 隔离夹具
- 根级 `AGENTS.md`：面向 AI 编码代理的项目上下文（结构、不变量、命令、测试约定、Git 与安全红线）
- OpenCode Zen minimax 真实浏览器验收记录（`docs/03-acceptance/maintenance/2026-10/2026-10-22-opencode-zen-minimax-real-browser-acceptance.md`）
- 文档链接自动校验工具：`scripts/dev/verify-doc-links.mjs`，并通过根 `package.json` 提供 `npm run verify:links` 命令

### Changed
- 项目文档体系系统性治理：
  - `docs/03-acceptance/` 消除 73 个根目录平铺堆积，完成分层归档（`checklists/`、`milestones/`、`maintenance/2026-10/`、`quality/`）
  - `docs/02-development/` 重构为清晰的 `api/`（统一契约收口）、`designs/`（`v1/`, `v2/`, `maintenance/`）、`guides/`（源码精读）、`implementation/`
  - `docs/04-operations/` 统一收纳生产配置、可观测性指标、灾备恢复与运维 Runbook，消除与 02 开发目录的运维重叠
  - `docs/superpowers/` 增加归档说明 README，明确为 M0~M18 历史工具计划与设计草案封存
- 测试文件与日志资产规范化隔离：
  - 规整 `.local-dev/` 目录结构为 `logs/`（本地运行/诊断日志）与 `probes/`（一次性排查探针脚本），清除根目录散落日志与临时构建 POM 残留
- `AGENTS.md` 文档与资产治理规范强化：
  - 升级第 10 节，建立硬性目录落位准则、测试/日志资产分类矩阵、维护期标准 Markdown 结构模板与 `npm run verify:links` 零死链门禁
- 前端设计体系统一：`styles.css` 建立全局设计令牌（品牌色阶、中性色、圆角、阴影）；antd `ConfigProvider` 主题令牌与之对齐；`v3-tokens.css` / `provider.css` 收敛至同一调色板
- 前端视觉现代化：侧栏胶囊式导航选中态、卡片柔和边框与分层软阴影、登录页焦点态、全局表格数字 `tabular-nums`
- 前端路由级代码分割：`AppRouter` 全部功能页改为 `React.lazy`，入口包由 1,704.89 kB（gzip 517.05 kB）降至 242.33 kB（gzip 76.56 kB）
- `dev` 分支 push 触发 GitHub CI 与 Security（保留 `main`、`pull_request` 触发与每周定时扫描，未弱化门禁）
- 内置 `OPENCODE_ZEN` 推理基址迁移到共享 CLI 推理基址 `https://opencode.ai/inference/v1`；Flyway V30/V31 对 `provider_catalog` 与 ACTIVE `provider_connection_profile` 做显式、版本化端点变更，V24 迁移保持字节不变
- OpenCode Zen 免费层准入统一为 FREE_ONLY，仅准入有证据的免费 Chat 模型
- README 重写：改为面向新读者的结构（价值主张 → 快速开始 → 架构 → 功能 → 开发 → 文档 → 贡献），移除冻结历史叙述，链接逐条校验可达
- `PROJECT_CONTEXT.md` 精简为当前状态索引，历史里程碑细节移交 `docs/` 与 `CHANGELOG.md`

### Fixed
- 结算 Worker：组织扫描改为带环绕的 keyset 轮询以实现跨轮公平覆盖，并按异常类型分级隔离单组织失败；处理首次组织扫描的 datasource 故障
- Cost Intelligence：预测要求可观测历史、拒绝反转主导的历史窗口、接受负 actual 并收紧预测窗口；调度分页就地重试并移除不安全的探针恢复，改用按组织与按页的 keyset 扫描
- OpenCode Zen / Kilo Gateway：关闭 M-2 清单漂移、修正版本端点并校验 SSE 完成、修复共享免费准入与 keyless 派发；修复 Kilo Gateway provider review 发现的三项缺陷
- 组织隔离安全修复：Provider 私有模型按组织作用域收敛；Routing Options 不再返回外组织私有 Provider Model；Pricing Version 对外组织不可见模型与不存在 ID 返回同一 `RESOURCE_NOT_FOUND`，关闭跨组织模型存在性探针
- M21 运维：Gateway 锁定读在 MySQL 8.4 下需数据库级 `LOCK TABLES`（不授予 `budget`/`billing_period` 的 `UPDATE`/`DELETE`）；补齐 Advisor Worker 的 `advisor_inference_job`/`advisor_inference_attempt`/`advisor_explanation`/`advisor_profile`/`advisor_evidence_snapshot` 表级最小权限
- 网关测试夹具：将 id 派生的种子固定到单连接，消除间歇 403
- 开发脚本：避免 Windows 下创建游离控制台，同时保留进程树清理
- 前端样式死代码：`.app-shell-collapsed` 选择器与真实 DOM class `settings-shell-collapsed` 不匹配，折叠侧栏的品牌居中样式此前从未生效
- 集成测试隔离：`LoginServiceIntegrationTest` / `LoginApiIntegrationTest` / `PasswordResetApiIntegrationTest` / `RefreshAndLogoutApiIntegrationTest` 补 `@AfterEach` 清理，不再把 `app_user` + `user_credential` + `audit_event` 残留泄漏给下一个测试类
- 集成测试隔离：`AuthorizationContextServiceIntegrationTest`、`ScopedBudgetRiskIntegrationTest`、`RecommendationAppliedLinkageIntegrationTest`、`SavingsRecommendationCommandAtomicityIntegrationTest` 改用 FK-safe 的 `M2DatabaseCleaner`，不再依赖"恰好排在污染源之前"的执行顺序
- 集成测试隔离：`M2DatabaseCleaner` 补齐 V28 新增的 `cost_history_coverage` / `cost_history_coverage_revision`（两者都引用 `app_user` / `organization`）
- 前端测试竞态：修复 `ImportWorkflow.test.tsx` 中的异步状态断言，避免高并发测试下的时序竞争假阴性
- 安全依赖：`follow-redirects` 升级 1.16.0 → 1.16.1，关闭 CVE-2026-111755 / CVE-2026-111756（Trivy HIGH 阻断）

### Security
- M22 双组织浏览器隔离与模型可见性边界修复（跨租户私有模型元数据泄露与未授权定价关联写入）

## [3.0.0] - 2026-09-13

V3「Model Provider Hub & Cost Intelligence」稳定版，当前 Stable。项目进入 MAINTENANCE_ONLY。

- M17 V3 设计冻结（#154）
- M18 后端完成并冻结 API 契约（`docs/03-acceptance/m18-openapi.yaml`）
- M19 V3 前端体验完成
- M20 V3 生产验收完成（本标签）
- M21 发布后运维就绪验证（2026-09-15, #162）
- M22 以"已记录的非阻塞限制"关闭（2026-09-22, #164）；已知发布阻断缺陷：无

## [2.0.0] - 2026-09-10

V2「Gateway 实时计量与路由」稳定版。

- M10 V2 详细设计冻结
- M11–M15 Gateway 边界证据、身份/预算预占、计量、结算、多 Provider 路由弹性、混合对账
- M16 V2 生产验收完成（本标签，#152），含浏览器 UAT 运行手册与证据矩阵

## [1.1.0] - 2026-09-02

- M9 生产基础收尾：备份/恢复证据、可观测性证据、安全 CI 证据、规模证据、Provider 认证（MiMo）、浏览器 E2E 证据（#127）
- 标记 v1.1.0 为当前稳定版（#128）

## [1.0.1] - 2026-08-25

### Fixed
- 后端：加固 Import lease recovery 在 MySQL deadlock race 下的 bounded retry（#103）。不改变 API、Schema 或 V1 产品范围

## [1.0.0] - 2026-08-24

V1 首个稳定版：多 Provider AI 成本统一为可信、可归属、可审批、可对账、可关账的内部成本账。

- 范围：IAM/组织、证据、导入、标准成本事实、重复审查、归属/分摊、报销/审批、账期/预算/承诺、不可变账本/更正、对账、关账/重开、审计、工作台/报表
- AIC-001 ~ AIC-073 全部完成；AIC-073 最终人工验收 / 发布签署：ACCEPTED

[Unreleased]: https://github.com/BangShou1st/AI-CostOps/compare/v3.0.0...HEAD
[3.0.0]: https://github.com/BangShou1st/AI-CostOps/releases/tag/v3.0.0
[2.0.0]: https://github.com/BangShou1st/AI-CostOps/releases/tag/v2.0.0
[1.1.0]: https://github.com/BangShou1st/AI-CostOps/releases/tag/v1.1.0
[1.0.1]: https://github.com/BangShou1st/AI-CostOps/releases/tag/v1.0.1
[1.0.0]: https://github.com/BangShou1st/AI-CostOps/releases/tag/v1.0.0
