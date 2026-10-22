# 给实施 AI 的提示词：成本分析模块整理

下面内容可以整体复制给能访问本地仓库的 AI。任务是实施、测试与交付，不是再生成一份方案。

---

请在 `E:\project\AI-CostOps` 实施成本分析模块的职责整理。默认中文沟通。先读适用 AGENTS.md、PROJECT_CONTEXT.md、当前源码，以及：

- `docs/02-development/2026-10-10-cost-intelligence-refactor-design.md`
- `docs/02-development/2026-10-10-directory-structure-review.md`
- `docs/03-acceptance/m18-backend-contract-freeze.md`
- `docs/03-acceptance/m18-openapi.yaml`

按以下要求完成代码、验证和文档，然后停止。这是已有项目的行为保持重构，不是新项目搭建。

## 目标和范围

目前 `backend/src/main/java/com/aicostops/intelligence/application/CostIntelligenceService.java` 约 678 行，混合计算、查询、预算风险、建议状态命令及返回类型。请按实际职责拆分，保留 `api/application/domain/infrastructure` 目录。

范围是 intelligence 后端、Advisor 的预算风险调用适配，以及受影响测试和维护说明。不要重构整个 Advisor、gateway、财务对账或预算模块。不要改数据库 schema、引入 MyBatis-Plus、升级依赖、搬全项目目录、批量迁移 SQL、新增 Maven 模块或改前端视觉。

成本分析已经在用 MyBatis，不需要从 JDBC 迁移。测试中的 JdbcTemplate 是夹具/数据库断言工具，保留其用途。

## 开始前

1. 查看 git status 和相关 diff，记录原有修改。工作区已有大量未提交和未跟踪改动，包括 CostIntelligenceRunService、任务事务测试以及报销、前端整理成果。它们是当前基线，必须保留；禁止 reset、clean、覆盖或删除。
2. 搜索 CostIntelligenceService、嵌套响应 record、RunOutcome、RUN_VERSION、assessScopeBudget 的全部调用，不依赖假设中的调用关系。
3. 读取上述两个 Mapper、现有 domain 算法、Scheduler、Controller、AdvisorService 的 BUDGET_RISK 快照分支，以及相关测试。
4. 跑目标测试基线，区分已有失败与此次回归。发现环境问题先合理排查，不能把未执行记录成通过。

## 建议实现

在 intelligence/application 内按以下分工落实，名称可根据仓库习惯微调：

- **CostIntelligenceAnalysisService**：两个 runAnalysis 入口、runDueAnalyses、异常/预测分析组织、时间序列及贡献辅助方法。
- **CostIntelligenceRunService**：复用原事务实现；只调整独立结果类型引用等必要内容。
- **SavingsRecommendationGenerator**：buildSavings、compareCandidates、使用量/费率读取与 map、指纹、建议写入。在 RunService 分析回调的同一事务里运行。
- **CostIntelligenceQueryService**：summary、anomalies、forecasts、recommendations。
- **BudgetRiskQueryService**：用户 budgetRisk 查询、内部 assessScopeBudget 证据入口，以及作用域预测查找。复用 domain BudgetRiskService。
- **SavingsRecommendationCommandService**：acknowledge、dismiss、markApplied、关联校验、条件状态更新与审计。
- **CostIntelligenceReadModels**：集中保留 5 种响应 record 的现有字段和类型；不要让 application 依赖 api 包。
- **AnalysisRunOutcome / BudgetRiskEvidence**：服务之外的独立应用结果类型，不再依赖原总服务嵌套类型。

仅为真实复用抽取小型参数/校验类和 recommendation 响应装配方法；避免复制查询与命令的同一映射，也避免大量单方法类和泛型框架。

Controller 保留一个文件，分别调用查询、预算风险查询、建议命令服务。Scheduler 改注入分析服务。Advisor 只改依赖和 assessScopeBudget 调用，保留其主题授权、证据 JSON、指纹与快照事务。当前直接依赖较小的 application 服务足够，不强行加多层 port/adapter。

全部调用迁移后删除 CostIntelligenceService 及临时兼容入口，更新测试注入与版本常量引用。不要为了避免修改测试而保留旧总服务。确有实际使用理由需要 facade 时，在交付说明中提供依据，保证只有一套业务实现。

## 必须保持的边界

### 分析事务

- 分析身份仍为 org/date/currency/version，version 保持 1；使用原数据库唯一约束、insert-if-missing 和状态条件更新。
- 派生结果与 COMPLETED 共同提交；异常全部回滚，再持久化 FAILED。已 COMPLETED/FAILED 的重复调用不重新计算。
- 原 RunService 的 TransactionTemplate 默认参与外层事务；有外层事务时要等回滚 afterCompletion 才用 REQUIRES_NEW 写失败记录。保留原异常、suppressed 异常及失败记录日志。
- 禁止用新的 REQUIRES_NEW 拆开分析、建议写入；禁止在事务内吞异常后提交部分结果；禁止用本地 mutex 代替数据库收敛。
- Scheduler 仍仅有一个 @Scheduled，保持 `${aicostops.intelligence.schedule-delay-ms:3600000}` 及原枚举规则，单组织/币种失败不阻断剩余批次。

### 日期、算法和财务

- 保持 Clock 注入。历史 `[analysisDate - 29 天, analysisDate)`，observedThrough = analysisDate - 1 天，completeSeries 整个窗口逐日补零，月份剩余天数保持。
- 查询仍查昨日、版本 1；无任务 summary 为 STALE，列表为空。不改“最近成功任务”策略，也不提高币种/日期校验要求。
- 保留账本 POSTED 与来源链路 SQL、反冲/替换、对账调整、直接费用及 Advisor 自消费排除。没有 provider/model 来源时不能推断归属。不混合币种。
- 异常粒度只限 ORGANIZATION/PROJECT/PROVIDER/LOGICAL_MODEL；预测包含 ORGANIZATION/PROJECT/TEAM/COST_CENTER，保持 scopeKey 和算法阈值。
- 节约建议保持历史成本最高来源候选及同一使用量向量回放；无自有历史用量的候选仍可推荐；缺费率与零费率不同。
- 数量/价格/金额保持 BigDecimal 和舍入/scale；不能截断为 long 或 double。
- 保留至少 5% 和严格超过币种物质性金额的筛选，以及严格更便宜的检查；保留 readiness 与 routingChangeRequired。
- SHA-256 指纹的原输入、map 顺序、金额文本均不变。

### 风险与建议操作

- immediate = actual + committed + active reservations；projected = actual + committed + forecast future usage。预占不再次进入 projected，不外推。
- PROJECT/TEAM/COST_CENTER 只用自己的预测，缺失为零，绝不套用组织预测。
- 用户查询组织级 COST_READ；预算风险组织级 BUDGET_READ；三个建议命令组织级 BUDGET_MANAGE，先鉴权再查改本组织数据。
- OPEN→ACKNOWLEDGED，OPEN/ACKNOWLEDGED→DISMISSED，ACKNOWLEDGED→APPLIED；保持状态条件更新和原错误语义。
- APPLIED 必须验证本组织 ACTIVE policy、相同逻辑模型和推荐 account/model 的 ACTIVE candidate；不自动创建、激活或切换真实路由。
- 建议状态和 AuditService.append 同事务，审计失败不能留下成功状态。@Transactional 放在经 Spring 代理调用的 public 命令入口，不依赖 private/self-invocation。
- Advisor 内部证据入口不增加 HTTP 路由，不绕过主题授权。BUDGET_RISK 的 scope/actual/committed/reservations/forecastFuture/immediate/projected/total/risk JSON 与冻结语义不变。

### HTTP 契约

8 个接口路径、请求参数/默认值、权限、错误状态码和 ProblemCode 不变。保留现有金额字符串、ID 数字及 null 语义，不改为统一字符串 ID。summary 是 openRecommendations；driversJson 保持字符串；robustZScore 保持 double。不要把各命令已有不同的查找/冲突语义顺手统一。

## 验证

复用并迁移下面现有测试：

- CostIntelligenceRunIntegrationTest
- ScopedBudgetRiskIntegrationTest
- SavingsReadinessAndPrecisionIntegrationTest
- RecommendationAppliedLinkageIntegrationTest
- LedgerCostFactsIntegrationTest
- CostIntelligenceEnginesTest、M18IntelligenceRepairTest
- ModuleDependencyArchitectureTest
- AdvisorEvidenceSnapshotIntegrationTest、AdvisorScopeBudgetExecutionIntegrationTest
- 其他 Advisor 集成测试，以及 intelligence/advisor 前端测试

重点核对实际覆盖，缺失才补：建议审计失败回滚、跨组织拒绝、重复/非法状态命令、拆分后完整分析流程失败不残留派生数据、Advisor BUDGET_RISK 快照保留预占与作用域预测。事务测试用真实数据库，不能只 mock Mapper；不要用测试方法外层 @Transactional 遮蔽真实提交/回滚。

PowerShell 中可先执行以下命令。后端集成测试由 Failsafe 在 verify 阶段运行，单独 mvnw test 不足以验证这些边界。新测试要加入目标列表，最后完整 verify 会兜底。

```powershell
Set-Location -LiteralPath 'E:\project\AI-CostOps\backend'
.\mvnw.cmd -B '-Dtest=CostIntelligenceEnginesTest,M18IntelligenceRepairTest,ModuleDependencyArchitectureTest' '-Dit.test=CostIntelligenceRunIntegrationTest,ScopedBudgetRiskIntegrationTest,SavingsReadinessAndPrecisionIntegrationTest,RecommendationAppliedLinkageIntegrationTest,LedgerCostFactsIntegrationTest,*Advisor*IntegrationTest' verify

# 改动完成后运行一次完整后端校验
.\mvnw.cmd -B verify

Set-Location -LiteralPath 'E:\project\AI-CostOps\frontend'
npm run test -- --run src/features/intelligence src/features/advisor
```

遵循项目现有测试配置。Docker 未启动时后台启动 Docker Desktop 主程序 `E:\DevTools\DockerDesktop\Docker Desktop.exe`（Start-Process 使用 -WindowStyle Hidden），轮询 docker info 就绪；不能用 net start/sc start Windows 服务启动引擎。每次等待不超过 60 秒，持续工作时给简短进度。

执行 verify 前注意 Windows 下正在运行的后端 JAR 是否占用打包文件。先查进程与命令行归属，避免停止用户其他 Java 进程；若因占用无法完成验证，明确记录原因，不把跳过当成通过。

若确需改动前端，再运行 lint、build 和相关测试。任何浏览器交互/页面验收必须使用 browser-skill 的 bsk：先读其 SKILL.md，后台启动会话；每次操作前 observe 并使用新 ref；验收未完成不能 stop。不要采用 Playwright、CDP 或其他浏览器自动化。纯后端结构整理不要求额外创造浏览器/UI 改动。

最终检查 diff 和遗留引用。git diff --check 是格式检查，不是业务测试；不要把旧总服务的残留调用、丢失 @Transactional、组织条件或字段类型当成可接受遗留。

## 交付与停止条件

新增 `docs/03-acceptance/2026-10-10-cost-intelligence-refactor.md`（若日期已变化，采用实际日期），包含：

1. 原职责到新文件的对应关系，以及 Advisor 适配范围。
2. 分析、失败记录、建议命令与审计的事务边界。
3. 具体测试命令、结果、失败/跳过及原因；禁止引用旧通过记录代替本次验证。
4. 接口兼容核对和有意义的新测试，区分已验证与未验证。
5. 原有改动保留情况、剩余风险及下一步建议。

更新 docs/README.md 导航。完成后用中文简短汇报文件、改进效果和验证结论，并建议下一步先审查此次 diff 与证据。

到此停止。不自动重构其他模块，不提交/推送 Git、不发布、不继续下一批；遇到真正阻塞要报告已完成部分和具体障碍。
