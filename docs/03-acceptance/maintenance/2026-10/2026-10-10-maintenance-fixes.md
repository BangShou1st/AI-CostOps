# 2026-10-10 维护修复记录

本文保留当时的测试环境和阶段结果。默认 MinIO 镜像的后续修复见 [2026-10-11 镜像收尾](2026-10-11-minio-test-image.md)；模块提交见 [三个模块联合审查](2026-10-11-cross-module-review.md)，前端收尾见 [路由、对账与样式复核](2026-10-11-frontend-review.md)。下文“默认测试镜像未改变”仅指本文当时阶段。

## 修复范围

| 问题 | 修改 | 验证 |
| --- | --- | --- |
| 分析失败状态随原事务回滚而消失 | `CostIntelligenceRunService` 管理分析事务；回滚完成后独立保存失败状态；算法服务移除自注入 | 真实数据库测试覆盖成功提交、失败回滚、外层事务回滚和重复调用 |
| 报销分页没有上限，偏移可能溢出；部分接口静默截断参数 | `PageRequest` 返回 `400 / VALIDATION_FAILED`；报销、审核、预算和承诺接口使用统一规则 | HTTP 错误契约和偏移边界测试 |
| 列表逐行加载详情及审批历史，再丢弃历史 | Mapper 直接返回摘要，以同组织的关联查询取得审批状态和可入账标记 | 组织及本人范围、金额精度、关联状态、无逐行详情查询测试 |
| 列表请求失败时显示空表，无法明确重试 | 我的报销、审核列表显示错误提示与重试按钮 | 前端组件测试和 browser-skill 浏览器验证 |
| 文档导航停留在早期版本，阅读路径不清楚 | 更新当前 V3 入口，新增项目阅读指南 | 文档入口文件存在性检查 |

分页限制：`page >= 0`、`1 <= size <= 200`、`page * size <= 2147483647`。
原来预算和承诺接口的截断行为改为拒绝非法参数，调用方需传入有效分页。

## 验证环境与复现

后端使用 Testcontainers 创建的隔离 MySQL 8.4、Redis 和 MinIO 容器。
原 MinIO 测试镜像仓库拉取返回 401；本轮通过新加的测试参数显式使用本机已有镜像：

```text
cgr.dev/chainguard/minio@sha256:f74600a1a46330cdbda1ef760d17a96bd6e0f4a6f0a2c49792ca3ee7e4c6fa18
```

默认测试镜像和生产镜像均未改变。本轮结果不代表默认仓库拉取已恢复。

在 `backend` 目录执行以下命令。第二条运行全部常规测试与补充摘要集成测试：

```powershell
.\mvnw.cmd -B '-Daicostops.test.minio-image=cgr.dev/chainguard/minio@sha256:f74600a1a46330cdbda1ef760d17a96bd6e0f4a6f0a2c49792ca3ee7e4c6fa18' '-Dtest=PageRequestTest,ProblemDetailAdviceTest' '-Dit.test=CostIntelligenceRunIntegrationTest,Expense*IntegrationTest,SavingsReadinessAndPrecisionIntegrationTest,ScopedBudgetRiskIntegrationTest,LedgerCostFactsIntegrationTest,RecommendationAppliedLinkageIntegrationTest,BudgetApiIntegrationTest,CommitmentApiIntegrationTest' verify
.\mvnw.cmd -B '-Daicostops.test.minio-image=cgr.dev/chainguard/minio@sha256:f74600a1a46330cdbda1ef760d17a96bd6e0f4a6f0a2c49792ca3ee7e4c6fa18' '-Dit.test=ExpenseSummaryQueryIntegrationTest' verify
```

在 `frontend` 目录执行：

```powershell
npm test -- --run src/features/expenses/ExpenseLists.test.tsx src/features/expenses/ExpensePages.test.tsx src/features/expenses/api/expenseApi.test.ts
npm run lint
npm run build
```

浏览器使用 browser-skill 的任务会话，在本地 Vite 页面验证两个列表：
实际列表请求因后端未启动返回 502，页面显示失败提示；点击重试时由一次性规则返回
空分页成功响应，页面恢复表格。认证响应也由任务会话提供。
这是前端错误恢复验收，真实后端权限、SQL 和事务通过上述集成测试验证；
本轮未进行运行中完整系统或真实 Provider 调用验收。

## 本轮结果

- 后端首批：8 项分页及错误契约测试、95 项集成回归全部通过。
- 后端最终验证：常规测试 609 项，608 项通过、1 项跳过，无失败；其中 36 项架构检查通过。
  跳过项为 POSIX 符号链接权限测试，在 Windows 上按既有平台条件跳过。
- 补充摘要集成测试 3 项全部通过，包含审批状态及可入账标记的关联变化。
- 前端相关测试 27 项全部通过，lint 和生产构建通过。
- 两个列表的浏览器失败提示及重试恢复流程通过；范围见前述受控响应说明。
- `git diff --check` 通过。

## 后续结构改进

本轮先处理已确认的行为问题，只将分析任务生命周期提取为独立职责。
后续可按业务用例逐步拆解过大的应用服务，整理历史文档与当前契约的入口，
并统一跨模块名称与阅读路径。每次拆分保留 API 契约并用现有集成测试验证。
