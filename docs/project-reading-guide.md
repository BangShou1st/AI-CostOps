# AI-CostOps 项目阅读指南

当前版本和运行方式从根 README 开始；这里说明如何沿一个功能理解代码。

学习 Tlias 后想读懂本项目，可以从 [报销模块源码解读与 Tlias 对照](02-development/expense-module-walkthrough.md) 开始，沿创建、上传、提交、审核和入账阅读。

## 目录与职责

| 目录 | 职责 |
| --- | --- |
| `frontend/src/features` | 按业务放页面、接口调用、展示转换和测试 |
| `backend/src/main/java/com/aicostops` | 控制台和费用业务，按业务模块组织 |
| `gateway/src/main/java/com/aicostops/gateway` | 实时模型调用、预算准入、流处理与用量事实 |
| `backend/src/main/resources/db/migration` | Flyway 数据库演进；已发布迁移不直接改写 |
| `docs/03-acceptance` | 历史验收证据和版本契约；不能当作当前测试结果 |
| `docs/04-operations` | 运维、发布和恢复入口 |

Backend 模块内部通常包含 `api`（HTTP）、`application`（业务用例与事务）、
`domain`（业务对象与规则）、`infrastructure`（SQL 和外部实现）。本模块直接使用
Mapper 是现有实现方式之一，并非所有模块都经过 Repository；不要为目录对称增加空层。

## 主要业务模块

| 模块 | 阅读时关注的职责 |
| --- | --- |
| `iam` / `organization` | 登录与权限 / 组织、项目、团队、成本中心 |
| `evidence` / `ingestion` / `cost` | 文件证据 / 导入与重试 / 标准化费用事实 |
| `attribution` / `allocation` | 归属模型和持久化 / 归属操作、规则与确认 |
| `expense` | 费用申请、证据、提交和审批 |
| `budget` / `ledger` | 预算与承诺 / 最终入账和追加修正 |
| `gatewaysettlement` / `reconciliation` | 用量事实结算 / 差异核查、调整及关账 |
| `gatewayadmin` / `providerhub` / `routing` | 网关管理 / Provider 连接档案 / 路由策略 |
| `intelligence` / `advisor` | 确定性分析 / 基于证据的 AI 解释 |

## 第一条阅读链：我的报销列表

1. `frontend/src/features/expenses/ExpensesListPage.tsx`：页面加载、失败、重试与分页。
2. `frontend/src/features/expenses/api/expenseApi.ts`：HTTP 路径和响应类型。
3. `backend/.../expense/api/ExpenseController.java`：校验分页并转换响应。
4. `backend/.../expense/application/ExpenseQueryService.java`：权限与本人范围。
5. `backend/.../expense/infrastructure/ExpenseQueryMapper.java` → `backend/src/main/resources/mapper/expense/ExpenseQueryMapper.xml`：摘要关联查询与总数；列表不读取审批历史。
6. `backend/.../expense/api/ExpenseResponses.java`：金额字符串与 ID 字符串转换。
7. `backend/src/test/java/com/aicostops/expense/ExpenseSummaryQueryIntegrationTest.java`：组织隔离、查询边界和分页错误。

接着看费用创建、编辑和提交：`ExpenseRequests` → `ExpenseClaimCommandService` → Mapper。
其中 `ExpenseClaimMapper` 负责报销主表，`ExpenseApprovalMapper` 负责审批流程与动作历史。
每次回答输入是什么、谁有权限、哪些写入共同提交、重试如何处理、失败后留下什么。

## 两条费用来源链

```text
文件证据 → 导入 → 标准化费用 → 归属 → 入账 → 对账/关账
实时请求 → Gateway → Provider → 用量事实 → Backend 结算 → 入账 → 对账/关账
```

两条链路最终都归入同一个财务账本。Provider 调用无法靠本地数据库回滚撤销；
已入账记录通过追加修正保留历史。读 Gateway 前先理解费用、预算和账本。

## 成本分析失败与事务

`CostIntelligenceAnalysisService` 组织算法，`SavingsRecommendationGenerator` 在同一事务内生成节约建议；
`CostIntelligenceRunService` 管理任务事务。查询、预算风险与建议命令分别由
`CostIntelligenceQueryService`、`BudgetRiskQueryService`、`SavingsRecommendationCommandService` 承担。
分析结果和成功状态共同提交。失败时先回滚整次分析，再以独立事务保存失败任务。
若加入调用方事务，则等待该事务回滚后记录失败，避免与尚未释放的锁冲突。
同一分析身份的已完成或已失败结果不会被覆盖。进程在分析中崩溃时未提交结果回滚，
后续调度可以重新处理；失败状态写入本身若不可用，会记录日志，不能声称已持久化成功。

真实提交边界验证见 `CostIntelligenceRunIntegrationTest`，测试没有外层自动回滚事务。

## 测试环境

集成测试使用 Testcontainers 的隔离数据库，不能指向个人或生产数据库。
MinIO 默认测试镜像保持原配置；镜像仓库不可访问时，可通过
`-Daicostops.test.minio-image=<已验证镜像@sha256:摘要>` 显式覆盖。
这只改变测试容器，不改变生产镜像；测试记录应注明实际镜像。

使用 `PageRequest` 的接口（包括报销、审核、预算和承诺列表）要求
`page >= 0`、`1 <= size <= 200`，且 `page * size <= 2147483647`。
超范围返回 `400 / VALIDATION_FAILED`，不静默截断输入。
