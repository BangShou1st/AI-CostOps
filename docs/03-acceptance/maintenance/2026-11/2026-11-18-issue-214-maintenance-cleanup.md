# Issue #214 维护清理验收

## 元数据

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-18 |
| 责任人 | Codex |
| 关联模块 | Backend、Gateway、Frontend、CI、scripts、开发 Compose |
| 关联缺陷 | Issue #214 |
| 基线 | `cbe412b` |
| PR | [Issue #214](https://github.com/BangShou1st/AI-CostOps/issues/214) 的关联 PR；完整验证结果见该 PR 描述 |

## 变更概述

本次维护针对已确认的未使用实现、无业务入口的预览代码、重复 CI 步骤及运行时缺陷进行定向清理，不扩大到功能开发或架构重写。

- **Backend**：删除无调用的 legacy refresh/probe 方法、测试捷径 settlement 构造器与 noop facade、未使用的 reconciliation Java enum。保留默认失败注入、回滚测试接缝及 HTTP `modelNames` 字段；外部枚举契约不变。密码重置集成测试改用 FK-safe 的 `M2DatabaseCleaner`。Provider 地址校验合并等价分支，DNS 单测可离线运行。
- **Gateway**：删除配置透传桥；修复 Redis 降级时本地半开状态的租期处理。
- **Frontend**：删除无入口的 Dev connection preview 与 `ReadinessDot`。导入、证据上传、分摊规则和定价的 ACTIVE 目录共用分页，并支持中止在途请求。
- **CI**：移除 8 个非矩阵重复准入步骤，严格识别 docs-only 变更；集成测试选择器作为 Backend 两个 integration job 的输入，mock provider 变更触发 `browser-e2e`。保留 required check 名称、矩阵和 Security workflow。
- **scripts / Compose**：开发 overlay 移除 3 个与基础 Compose 重复的环境值，修改前后五个服务的解析配置哈希一致。`infra:up` 仅解析 Compose JSON stdout，失败时诊断保留 stdout 与 stderr。构建目录仅在 build/test/verify 进程以 `process.exitCode === 0` 结束时写入完成标记，并自动轮换符合生成命名格式的已标记产物，保留最近 3 个；失败、取消、dev、未标记与旧目录不参与自动清理，须确认相关进程结束后再人工处理。

范围取舍：账期财务锁、已发布 Flyway 迁移、真实 Provider 认证路径、仍受契约或运维依赖的期限与退役记录均保留。没有 API、Schema、结算、账本或其他财务语义变更；未调用真实 Provider。

## 验证环境与执行命令

以下定向回归在基线 `cbe412b` 对应工作树执行。命令按 PowerShell 记录。

| 范围 | 命令 | 结果 |
|---|---|---|
| Backend 定向单测 | `Set-Location backend; .\mvnw.cmd -B "-Dtest=CustomEndpointValidatorTest,M18TransportRegressionTest,ReconciliationMatchEngineTest" test` | 42 tests 通过 |
| Gateway 定向回归 | `Set-Location gateway; .\mvnw.cmd -B "-Dit.test=BudgetReservationConcurrencyIntegrationTest,BudgetReservationServiceIntegrationTest,ReservationRecoveryIntegrationTest,CircuitBreakerRedisIntegrationTest" verify` | 164 Surefire + 25 集成测试通过 |
| scripts 全量回归 | `npm run test:scripts`；`npm run test:scripts:integration` | 最终版 58 + 2 通过 |
| Frontend 定向回归 | `Set-Location frontend; npm test -- --run RulesPage ModelPricingPage ImportWorkflow ProviderImportUploadModal ProviderHub` | 5 个核心文件、48 tests 通过 |
| Frontend 生产构建 | `Set-Location frontend; npm run build` | 通过；产物用于独立浏览器验收 |
| BrowserSkill UI 验收 | Windows Edge；独立会话、201 条 ACTIVE 合成 HTTP 夹具；使用 `observe` / `click` / `wheel` | PASS，细节见下方；未提交任何写入 |
| CI workflow lint | `actionlint -color=false` | `.github/workflows/ci.yml`、`.github/workflows/security.yml` 无 lint 错误 |
| Compose 解析对照 | 见下方两条命令 | `backend`、`frontend`、`minio`、`mysql`、`redis` 五个服务哈希完全相同 |
| 文档链接 | `npm run verify:links` | 成功；269 Markdown 文件、556 条链接，0 个断链 |
| 完整验证与远端 CI / Security | Issue #214 关联 PR 描述 | 以 PR 验证结果为准；本记录仅覆盖已执行的定向回归与 BrowserSkill 验收 |

Compose 配置哈希对照命令（基线与修改后）：

```powershell
git show cbe412b:compose.dev.yaml | docker compose --env-file .env -f compose.yaml -f - config '--hash=*'
docker compose --env-file .env -f compose.yaml -f compose.dev.yaml config '--hash=*'
```

Compose 对照与 workflow lint 结果由根任务独立复核。完整 Maven verify 与远端 CI / Security 的最终状态记录在 Issue #214 关联 PR 描述中。

单趟 Backend `verify` 曾因本机 Docker WSL 内存约 1.86 GiB 而中断：V24、V30、V32、V22 四个临时迁移 MySQL 容器在对应测试类完成后仍占用内存，导致共享测试 MySQL `OOMKilled=true`（Exit 137）。中断前已完成 194/196 个集成测试类、1227 项断言/测试，以及 681 个单元/架构测试（含 1 项原有跳过）；这不是全量 `verify` 通过记录。

处置为给上述四个迁移测试类增加 `@AfterAll`，停止专用 MySQL 容器，并在必要时结束 Spring context；保持原测试断言、迁移文件和共享测试库生命周期不变。V10、V11 及最后改动的四个迁移类计划局部重跑，Gateway 另行完整运行；执行结果与远端 CI / Security 记录在关联 PR，不在此声称整趟 `verify` 通过。

## 关键断言与证据

- `M2DatabaseCleaner` 保证密码重置夹具按 child-before-parent 顺序全量清理，避免共享 MySQL Testcontainer 的外键数据泄漏。
- CI 的 required check 名称、测试矩阵和 Security workflow 保留；两个 Backend integration job 使用选择器输出，mock provider 变更触发 `browser-e2e`。
- Compose overlay 精简前后五个服务的解析配置哈希一致，三个重复环境值不改变运行配置。
- 构建目录轮换只针对有成功完成标记、且名称符合生成格式的最近三次产物；失败、取消、dev 和旧式未标记目录不自动删除。
- BrowserSkill 使用独立 Windows Edge 会话和 201 条合成 ACTIVE 供应商记录：导入页筛选、账单上传对话框、分摊规则创建表单和定价创建表单均跨页选中第 201 条“跨页验收供应商”；表单回显选择，上传来源类型由禁用转为可选。未提交表单，没有持久化写入。
- BrowserSkill 另确认 Provider 连接配置页按无连接状态显示空态及新建按钮。
- 本次无数据库迁移、无 API 契约调整、无真实 Provider 请求；保留账期财务锁与历史迁移。

## 结论与影响边界

**判定：已执行的定向回归与 BrowserSkill UI 验收 PASS。** 当前已知结果为 Backend 定向 42、Gateway 164 Surefire + 25 集成测试、Frontend 核心 48、scripts 58 + 2；Frontend 生产构建通过。BrowserSkill 以独立本地合成夹具验证跨页选择及表单状态，没有调用真实 Provider 或提交写入。全仓文档链接检查通过（269 个 Markdown 文件、556 条链接、0 个断链）。完整 Maven verify 与远端 CI / Security 状态见 Issue #214 关联 PR 验证结果，本报告不代替其结论。

本次变更限于已确认的维护缺陷与无效代码清理，不新增 Provider 或业务能力。真实 Provider 认证未验证，`dev`、失败、取消及历史遗留构建目录需要人工确认后清理；这属于预期限制。
