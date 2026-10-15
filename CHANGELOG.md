# Changelog

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 规范，
版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

发布基线与验收证据的权威来源见 `PROJECT_CONTEXT.md` 与 `docs/03-acceptance/`；
本文件是面向变更检索的摘要视图。

## [Unreleased]

### Added
- 根级任务编排：根 `package.json` + `scripts/tasks.mjs`（零依赖、跨平台），统一 `dev / build / test / verify / infra:*` 入口
- `scripts/dev/` 补充 `.sh` 版本（`start-infra.sh` / `stop-infra.sh` / `status.sh`），与 `.ps1` 一一对应
- `scripts/README.md`：脚本目录职责说明与里程碑产物归档/命名策略
- 根级 `LICENSE`（专有许可，内部使用）与本 `CHANGELOG.md`

### Changed
- 前端设计体系统一：`styles.css` 建立全局设计令牌（品牌色阶、中性色、圆角、阴影）；antd `ConfigProvider` 主题令牌与之对齐；`v3-tokens.css` / `provider.css` 收敛至同一调色板
- 前端视觉现代化：侧栏胶囊式导航选中态、卡片柔和边框与分层软阴影、登录页焦点态、全局表格数字 `tabular-nums`
- 前端路由级代码分割：`AppRouter` 全部功能页改为 `React.lazy`，入口包由 1,704.89 kB（gzip 517.05 kB）降至 242.33 kB（gzip 76.56 kB）

### Fixed
- 前端样式死代码：`.app-shell-collapsed` 选择器与真实 DOM class `settings-shell-collapsed` 不匹配，折叠侧栏的品牌居中样式此前从未生效

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

[Unreleased]: https://github.com/aicostops/ai-costops/compare/v3.0.0...HEAD
[3.0.0]: https://github.com/aicostops/ai-costops/releases/tag/v3.0.0
[2.0.0]: https://github.com/aicostops/ai-costops/releases/tag/v2.0.0
[1.1.0]: https://github.com/aicostops/ai-costops/releases/tag/v1.1.0
[1.0.1]: https://github.com/aicostops/ai-costops/releases/tag/v1.0.1
[1.0.0]: https://github.com/aicostops/ai-costops/releases/tag/v1.0.0
