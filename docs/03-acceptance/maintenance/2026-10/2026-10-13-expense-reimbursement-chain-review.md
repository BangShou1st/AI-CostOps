# 2026-10-13 维护记录：报销→审批→入账链审查、补测与验收

基线：`codex/history-coverage-20261012@0039452`（此前本地提交仍未发布）。本批只新增测试与文档，
未修改生产代码。未跟踪的 `.freebuff/`、`CHANGELOG.md`、`LICENSE` 保持原样，无 reset/clean。

2026-10-14 后续复核已补强证据恢复与前端测试，并完成一次退出码 0 的完整 `npm run verify`。
独立 MySQL 诊断也更正了并发回滚的推断，见[后续复核记录](2026-10-14-expense-review-followup.md)。
下文保留本批原始验证结果与限制，不用后续成功替换历史失败。

## 1. 范围与本批结论

沿实际调用链审查报销链五个写入口：证据上传与绑定 → 创建/编辑 → 提交/撤回/重新提交 → 审批 →
入账 → 预算/承诺，并覆盖跨组织、撤权、重复请求、并发与失败恢复。逐入口记录见
[入口审查记录](../../../02-development/designs/maintenance/2026-10-13-expense-reimbursement-chain-entry-review.md)。

**本批未发现需要修复的确认缺陷。** 权限门（在幂等 reserve 之前）、`expectedVersion` 乐观 CAS、
幂等身份、关账准入（approve 与 posting）、冻结锁序，以及"成功审计与财务动作共同提交"的设计一致且可核查；
对既有套件未覆盖的显式场景补齐了定向用例并以真实 MySQL 事务验证。

## 2. 补测（新增/扩展 4 个测试类，8 个用例）

| 测试类 | 新增用例 | 场景 |
| --- | --- | --- |
| `ExpenseEvidenceBindingRecoveryIntegrationTest`（新文件，4 项） | 绑定审计失败回滚但**保留**已存证据；重复上传同文件**复用**同一证据行；并发同文件**仅绑定一次**；**跨组织**证据下载 404 |
| `ExpenseIdempotencyIntegrationTest`（+2） | 撤权后同键重放的**提交**与**审批**仍返回 403，不返回缓存成功 |
| `ExpenseReviewConcurrencyIntegrationTest`（+1） | **同键并发审批**恰好切换一次（一状态、一 action、一审计） |
| `ExpensePostingIntegrationTest`（+1） | 入账审计失败后**整体回滚**（posting/entries/预算 actual/承诺消费/状态） |

## 3. 验证范围（绑定被验证代码 = 本批工作区 HEAD，含上述改动）

### 3.1 报销链定向回归（真实 MySQL/Redis/MinIO Testcontainers）

| 验证 | 结果 |
| --- | --- |
| `*Expense*IntegrationTest`（报销链全部集成：证据、生命周期、幂等、并发、入账、分配、E2E 主链） | **85 项通过、0 failures、0 errors、0 skipped** |

### 3.2 完整 workspace verify（`npm run verify`，隔离构建目录）

| 阶段 | 实际结果 |
| --- | --- |
| 脚本单元 + 生命周期测试 | 通过 |
| 后端 surefire（单元 + 架构） | 640 项、0 failures、0 errors、**1 skipped**（`DevInvitationMailboxTest::posixSymlinkMailboxCannotChangeTargetPermissions`，POSIX 专属，Windows 跳过） |
| 后端 failsafe（集成） | **1218 项、0 failures、27 errors、0 skipped** |
| 最终结论 | `BUILD FAILURE`（后端 failsafe 阶段），总耗时 56:31 |

27 个 error = `V22LegacyRoutingBackfillIntegrationTest` 1 + `V10MigrationIntegrationTest` 10 +
`V11MigrationIntegrationTest` 16，失败直接表现为 **Docker/Testcontainers 启动与数据库连接错误**：

- V22：`ContainerLaunchException: Container startup failed for image mysql:8.4`，Docker 引擎返回 `Status 500`
  （新建容器无法启动）；
- V10/V11：`HikariPool-6 - Connection is not available, request timed out after 30009ms` /
  `Communications link failure`（共享 MySQL 容器在运行后段失联）。

本机 Docker Desktop VM 内存实测约 **1.9 GB**；多个缓存上下文、连接池与容器叠加可能造成资源压力，
但上下文数量、OOM 或容器退出根因尚缺直接证据，不能把报告中的测试类数量等同于 Spring 上下文数量。
本批改动仅涉及 `expense`/`ledger` 测试与文档，与 V10/V11/V22 迁移/路由测试无直接代码交集；这有助于定位，
但不能排除整套运行中的测试生命周期或资源管理问题。

**同一代码上的隔离重跑（确认单独运行通过）**：

| 验证 | 结果 |
| --- | --- |
| `V10MigrationIntegrationTest` + `V11MigrationIntegrationTest` + `V22LegacyRoutingBackfillIntegrationTest` | **27 项、0 failures、0 errors、0 skipped** |

因后端 failsafe 失败，`verify` 未继续执行 gateway 与前端阶段；以下为单独补跑的结果。

### 3.3 Gateway 与前端（补跑）

| 验证 | 结果 |
| --- | --- |
| gateway `mvnw verify`：surefire 174 + failsafe 84 | **258 项、0 failures、0 errors、0 skipped**（日志里的 `reclaimOrphans` JDBC 报错来自后台 `@Scheduled` 任务，未计为测试失败） |
| frontend `npm run test`（vitest） | **547 项：546 通过、1 失败**——`ExpensePages.test.tsx` 的「403 forbidden」用例以默认 5s 超时（隔离单跑仍超时；用 20s 预算诊断 7.5s 通过）。该用例存在分摊目标 mock 不完整及重复 DOM 查询，但耗时根因未由这些结果证明；测试 QueryClient 已配置 `retry: false`，原先“React Query 重试导致延迟”的归因不成立。**该文件本批未改动**；没有修改超时或断言。2026-10-14 后续复核另补测试依赖和查询方式，结果单独记录。 |
| frontend `npm run lint` | 通过 |
| frontend `npm run build`（tsc + vite） | 通过 |

### 3.4 三端启动与浏览器验收（browser-skill / bsk）

三端本机启动：Frontend 5173（200）、Backend 8080（就绪）、Gateway 8081（就绪）。bsk 会话 `iylr`，
`--no-focus` 后台启动；使用 **合成数据**，登录本地 dev 引导管理员（EMPLOYEE + FINANCE_ADMIN + SYSTEM_ADMIN）。

| 步骤 | 结果 |
| --- | --- |
| 新建报销 ID 38（2026-10-14，19.99 CNY，DRAFT v0） | 成功 |
| 无凭证时「提交」按钮 | 禁用（前置证据门生效） |
| 上传合成凭证 | 成功，v1；出现「下载凭证/替换凭证」，「提交」转可用 |
| 提交 | SUBMITTED，v2，审核状态 PENDING，审批历史含「提交」 |
| 报销审核队列 | 出现 ID 38（已提交，v2） |
| 批准 | APPROVED，v3，审批历史 +「批准」，出现手动分摊区 |
| 手动分摊 | 行金额自动格式化为 19.99000000（精确分配），目标 PROJECT:1 |
| 创建分摊草稿 → 确认分摊 | decision 37 = CONFIRMED；发布就绪 ✓；出现「记账」按钮 |
| 记账 | POSTED，v4；「记账」按钮消失 |

网络日志各步均为 200（`POST /expenses/38/approve`、`POST /expenses/38/allocation-decisions/manual`、
`POST /allocation-decisions/37/confirm`、`POST /expenses/38/post`）。账本仅新增一条 19.99 CNY（1 分录）的
报销发布记录，**无重复入账**。

## 4. 验证限制与遗留

- **Docker/Testcontainers 环境**：完整 verify 的 27 个错误直接表现为容器启动失败/失联（Docker VM 约 1.9 GB 内存），
  同代码隔离重跑通过；具体根因及整套运行稳定性仍需验证。未降低断言、未放宽超时、未跳过测试。
- **前端单元 1 项超时**：`ExpensePages.test.tsx` 的 403 用例（本批未改）；后续先补齐 mock 并减少重复 DOM 查询，
  保持原断言和默认超时，验证稳定性，避免仅凭提高预算认定问题关闭。
- **浏览器验收为单组织、单身份**：同一管理员驱动员工与财务动作，不构成独立身份环境的组织隔离证据。
  真实 Provider/OpenCode 联调与独立浏览器组织隔离验收维持 **DEFERRED**。
- **合成数据**：报销 38 已 POSTED（账本不可变），保留在 dev 库中，**不做删除**（删除已入账事实会破坏账本不可变性）。
  本次启动的本机三端进程已停止，临时凭证文件已删除；未清理任何数据卷或既有配置。
- 未推送、未创建 PR、未合并、未部署；远程 CI 未执行。
- 后续独立 MySQL 组件诊断已验证双请求回滚后重新预占、提交后重放；完整 API 与多请求边界仍待补测，
  孤儿证据暂不回收属于当前保留策略，后续回收需要引用检查与保留规则。详见入口审查 §8。

## 5. 建议下一步

1. 在本机 Docker 资源允许时，重跑一次完整 `npm run verify` 以取得一次"全绿"记录；或先为 Testcontainers
   增加串行/内存约束以避免后段容器失联。
2. 按需修复 `ExpensePages.test.tsx` 403 用例对机器速度的敏感（补 mock，而非放宽断言）。
3. 继续第三批（任务恢复与权限缓存失效）或独立浏览器组织隔离验收，均需单独授权。
