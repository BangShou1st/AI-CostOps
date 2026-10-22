# 2026-10-10 成本分析模块职责整理

依据 [职责与事务设计](../../../02-development/designs/maintenance/2026-10-10-cost-intelligence-refactor-design.md) 与
[执行提示词](../../../02-development/guides/prompts/2026-10-10-cost-intelligence-refactor-prompt.md) 实施。
范围是 intelligence 后端与 Advisor 预算风险调用点；网关执行、财务对账、预算模块未改动。

## 1. 原职责到新文件

原有 `CostIntelligenceService`（678 行）已删除，没有保留 facade 或兼容入口，只有一套业务实现。

| 原内容 | 新归属 | 说明 |
| --- | --- | --- |
| `runAnalysis`×2、`runDueAnalyses`、`analyze`、时间序列与 grain 构建、异常/预测持久化 | `CostIntelligenceAnalysisService`（283 行） | 保留可信组织入口与用户鉴权入口两条路径 |
| `buildSavings`、`compareCandidates`、`usageMap`、`rateMap`、`fingerprint` | `SavingsRecommendationGenerator`（153 行） | 在 RunService 分析回调内运行，不独立提交 |
| `summary`、`anomalies`、`forecasts`、`recommendations` | `CostIntelligenceQueryService`（85 行） | 保留组织级 COST_READ 与“昨日版本 1”查询 |
| `budgetRisk`、`latestForecastAmount`、`assessScopeBudget` | `BudgetRiskQueryService`（98 行） | 用户入口与 Advisor 内部证据入口分开 |
| `acknowledge`、`dismiss`、`markApplied`、`validateAppliedLinkage`、`transition` | `SavingsRecommendationCommandService`（115 行） | public 命令保持事务 |
| `SummaryResponse` 等 5 个响应 record | `CostIntelligenceReadModels` | 字段、顺序、类型不变；每个 record 一个 `from(...)` |
| 嵌套 `RunOutcome` | `AnalysisRunOutcome` | RunService 不再依赖原总服务的嵌套类型 |
| 嵌套 `BudgetRiskEvidence` | `BudgetRiskEvidence` | Advisor 消费的确定性风险证据契约 |
| `RUN_VERSION`、`validatedCurrency` | `CostIntelligenceValidation` | 只抽取真实复用的项；`validatedDate` 仍留在唯一调用方 |

`CostIntelligenceRunService` 保留原事务实现，只改类型引用（88 行）。`CostIntelligenceScheduler` 改注入分析服务，
仍只有一个 `@Scheduled` 与 `${aicostops.intelligence.schedule-delay-ms:3600000}`。
`CostIntelligenceController` 保留一个文件，注入查询、预算风险查询、建议命令三类服务。

**Advisor 适配范围**：`AdvisorService` 只改构造注入与预算风险分支调用（`intelligence.assessScopeBudget` →
`budgetRisks.assessScopeBudget`），共 4 行。主题授权、证据 JSON 字段、指纹与快照事务均未改动，
未新增 HTTP 路由，内部证据入口不能绕过 Advisor 主题授权。

## 2. 事务边界

| 场景 | 边界 |
| --- | --- |
| 分析 | `CostIntelligenceRunService` 的 `TransactionTemplate` 一次事务包住：身份 insert-if-missing → PENDING→RUNNING 条件更新 → 分析回调 → COMPLETED。派生结果与 COMPLETED 共同提交 |
| 分析失败 | 异常时整次分析回滚（含初始 INSERT）；随后用既有 `REQUIRES_NEW` 模板重写身份并置 FAILED。有外层事务时注册 `afterCompletion`，等外层回滚后再写失败记录 |
| 节约建议 | 在分析回调的同一事务内调用，未新增 `REQUIRES_NEW`，不允许事务内吞异常后提交部分结果 |
| 建议命令 | `@Transactional` 放在经 Spring 代理调用的 public 入口（acknowledge/dismiss/markApplied）；条件状态更新 + `AuditService.append` + 回读同事务。private `transition` 不被依赖为独立边界 |

数据库唯一约束、insert-if-missing 与状态条件更新仍是收敛权威，未引入本地 mutex。

## 3. 测试命令与结果

均在 `E:\project\AI-CostOps\backend` 执行，Testcontainers 隔离 MySQL 8.4 / Redis / MinIO。

定向回归（改动后）：

```powershell
.\mvnw.cmd -B '-Dtest=CostIntelligenceEnginesTest,M18IntelligenceRepairTest,ModuleDependencyArchitectureTest,M18OpenApiContractTest' '-Dit.test=CostIntelligenceRunIntegrationTest,ScopedBudgetRiskIntegrationTest,SavingsReadinessAndPrecisionIntegrationTest,RecommendationAppliedLinkageIntegrationTest,LedgerCostFactsIntegrationTest,*Advisor*IntegrationTest' verify
```

结果：51 项单元/架构（含架构 31、OpenAPI 契约 3）与 33 项集成全部通过。

完整后端校验（仅单元与集成测试范围）：

```powershell
.\mvnw.cmd -B test failsafe:integration-test failsafe:verify '-Daicostops.test.minio-image=cgr.dev/chainguard/minio@sha256:f74600a1a46330cdbda1ef760d17a96bd6e0f4a6f0a2c49792ca3ee7e4c6fa18'
```

结果：单元 609 项、集成 1191 项，失败 0、错误 0。该命令不含 `package` 与 `repackage`，
不构成可执行 JAR 已验证；打包与产物启动验证见下一节。

前端契约核对（未改前端产品代码）：

```powershell
npm run test -- --run src/features/intelligence src/features/advisor
```

结果：11 个文件 38 项通过。

### 完整 verify 与可执行 JAR 启动验证

审查指出替代命令未验证 Spring Boot 可执行 JAR 的打包与启动，本轮已补齐。停掉占用产物的本机后端
（`java -jar target/backend-1.1.0.jar --spring.profiles.active=local`，PID 28860，命令行确认属于本仓库）后执行：

```powershell
.\mvnw.cmd -B verify '-Daicostops.test.minio-image=cgr.dev/chainguard/minio@sha256:f74600a1a46330cdbda1ef760d17a96bd6e0f4a6f0a2c49792ca3ee7e4c6fa18'
```

结果：单元 609 项（跳过 1）、集成 1191 项，失败 0、错误 0，`BUILD SUCCESS`；
`jar:jar` 与 `spring-boot:repackage` 执行，并输出
`Replacing main artifact … adding nested dependencies in BOOT-INF/`。
产物 `target/backend-1.1.0.jar` 109,705,834 字节，manifest 为
`Main-Class: org.springframework.boot.loader.launch.JarLauncher` /
`Start-Class: com.aicostops.AiCostOpsApplication`。

产物内容与启动均已实测：

| 检查 | 结果 |
| --- | --- |
| JAR 含 9 个新拆分类 | 全部存在（Analysis/Query/BudgetRisk/Command 服务、Generator、ReadModels、AnalysisRunOutcome、BudgetRiskEvidence、Validation） |
| JAR 不含旧总服务 | `intelligence/application/CostIntelligenceService.class` 不存在 |
| JAR 内 Advisor 适配 | `AdvisorService.class` 含 `BudgetRiskQueryService` 与 `budgetRisks`，不含 `CostIntelligenceService` |
| JAR 类与编译输出一致 | 抽样 4 个类 SHA-256 与 `target/classes` 逐字节相同 |
| 启动 | `--spring.profiles.active=local` 启动成功：Tomcat 8080，`Started AiCostOpsApplication in 6.87 seconds`，Flyway 校验 27 个迁移，无异常 |
| 健康检查 | `GET /actuator/health` → 200，`status=UP` |
| 路由注册 | 启动期 `RequestMappingHandlerMapping` TRACE 日志列出 cost-intelligence 全部 8 条（5 GET + 3 POST）与 ai-advisor 的 4 个 URL、5 个方法映射（profile 同时支持 GET/PUT），与冻结契约一致 |

验证期间用 `--server.port=8099` 起过第二实例以读取启动日志，未改动任何配置文件；验证完毕已停止该实例，
并按原命令恢复本机后端（PID 52352，`--spring.profiles.active=local`，健康检查 200）。
JAR 时间戳（01:25）早于第二次 `verify`（02:11）：该次只改了测试源码、生产类未变，
`maven-jar-plugin` 判定产物最新而未重写。JAR 内容已通过逐字节比对与类文件探针确认与当前生产源码一致。

### 失败与跳过

- MinIO 默认镜像 `quay.io/minio/minio` 匿名拉取返回 401（Docker Hub 的 `minio/minio` 亦不可用），
  首次完整测试出现 417 个错误，全部来自 `MinioContainerSupport` 静态初始化，与本轮改动无关。
  改用 `MinioContainerSupport` 已支持的 `-Daicostops.test.minio-image` 覆盖为本机镜像
  `cgr.dev/chainguard/minio@sha256:f74600a1…` 后全部通过。该覆盖只影响测试容器，不改变默认或生产镜像。
  默认镜像能否稳定重复拉取应单独收尾，本次不能据此断言上游镜像永久消失。
- 单元测试有 1 项跳过：`DevInvitationMailboxTest.posixSymlinkMailboxCannotChangeTargetPermissions`
  用 `Assumptions.assumeTrue` 要求 POSIX 文件属性视图，Windows 上必然跳过。与 intelligence/Advisor 无关。
- 首轮完整 `verify` 有 1 项失败，原因是**本轮新增测试自身的缺陷**：
  `AdvisorBudgetRiskEvidenceIntegrationTest` 用本地时区 `LocalDate.now()` 插入分析任务，
  而生产代码用注入的 `Clock.systemUTC()` 推算“昨日”。在本地日期已跨天、UTC 尚未跨天的时段
  （本次为本地 01:25）两者相差一天，作用域预测查不到而返回 0。改用注入的 `Clock` 后修复，
  属测试夹具缺陷，不是业务回归。

## 4. 接口兼容与新增测试

**接口核对**：`M18OpenApiContractTest` 3 项通过，冻结 YAML 中 8 条 `/api/v1/cost-intelligence`
路径与实现完全一致。金额仍为十进制字符串（含 `toPlainString()` 的 DECIMAL(20,8) 尾随零），
ID 仍为 JSON 数字并保留 null 语义，`summary` 仍是 `openRecommendations`，`driversJson` 仍是字符串，
`robustZScore` 仍是 double。未改动请求参数、默认值、权限、状态码或 ProblemCode。
前端 `intelligenceTypes.ts` 未改，其测试通过即为消费契约未变的证据。

新增 3 个测试文件（最终共 10 项：流水线 4、命令 3、Advisor 风险证据 3），针对拆分前实际缺失的保护：

| 测试 | 新增覆盖 |
| --- | --- |
| `CostIntelligenceAnalysisPipelineIntegrationTest` | 用 spy 调用**真实**生成器，先在同一事务内真正写入异常/预测/建议，再注入失败并断言三类记录全部消失；事后完成用例证明同一夹具能正常提交三类结果；失败任务终止且不重算 |
| `SavingsRecommendationCommandAtomicityIntegrationTest` | 审计 append 失败回滚状态更新且无审计行；外组织经理不能修改本组织建议；重复与非法状态转移被拒且状态不变 |
| `AdvisorBudgetRiskEvidenceIntegrationTest` | BUDGET_RISK 快照经新入口仍保留预占（只进 immediate，不进 projected）；作用域只用自己的预测，缺失为零不套用组织预测；外组织预算在构建证据前即被拒 |

回滚测试的有效性经过变异验证：去掉回调中的 `callRealMethod()` 后，
“建议记录已写入”的前置断言立即失败（报出 `savings_recommendation` 计数为零），
说明零行断言不再空过——这正是审查指出的原缺陷。夹具用真实 POSTED 账本日序列（19 天平缓 + 末位尖峰）
与两个已定价候选，因此异常与建议记录是真实产生的，而非依赖空组织补零。

除 `SavingsRecommendationGenerator`（用于在真实写入后注入失败）与 `AuditService`（用于注入审计失败）
两个 seam 外，测试全部跑真实数据库，未用 mock 替代 Mapper；审计失败与流水线失败两项测试均无测试方法外层
`@Transactional`，走真实提交/回滚。

## 5. 原有改动保留与剩余风险

**保留情况**：本轮未执行 `git reset`、`git clean`、覆盖或删除任何既有改动。
工作区原有的 `CostIntelligenceRunService`、`CostIntelligenceRunIntegrationTest`、
报销与前端整理成果、`application.yml`、`docs/02-development/api/openapi.yaml` 等 100 余项改动全部保持原状。
其中 `CostIntelligenceRunService` 与 `CostIntelligenceRunIntegrationTest` 属原有未跟踪的新文件，
本轮只在 RunService 中改了两处类型引用（`RunOutcome` → `AnalysisRunOutcome`），其事务实现与测试断言未动。

本轮触碰的已跟踪文件共 6 个：Controller、Scheduler、`AdvisorService`、`SavingsReadinessAndPrecisionIntegrationTest`、
删除的原总服务、`docs/README.md`（其中 `docs/README.md` 在本轮开始前已有未提交改动，本轮只追加了验收记录链接）。
另有 9 个新增源文件、3 个新增测试文件，以及本轮验收记录与
`docs/project-reading-guide.md`（该文件此前为未跟踪状态，本轮只更新其中成本分析一段）。

**受审查反馈后的收尾**（本次追加，不改业务代码）：

- 重写 `CostIntelligenceAnalysisPipelineIntegrationTest`：原先用 `@MockitoBean` 整体替换生成器，
  异常与建议记录从未写入，两条零行断言空过；现改为 spy + `callRealMethod()` 真实写入后注入失败，
  并加事务内前置断言与事后完成用例，另经变异验证确认断言有效。
- 修复 `AdvisorBudgetRiskEvidenceIntegrationTest` 的时区夹具缺陷（改用注入的 `Clock`）。
- 完成完整 `verify`（含 `package`/`repackage`）并实测可执行 JAR 启动、健康检查与路由注册；
  验证后按原命令恢复本机后端。

- MinIO 默认测试镜像问题已于 2026-10-11 独立收尾：两类测试容器共用固定公开镜像摘要，
  无覆盖参数的完整 verify 通过。见 [默认镜像收尾记录](2026-10-11-minio-test-image.md)。

**剩余风险**：

- `CostIntelligenceAnalysisService` 283 行仍偏大：异常与预测流程共用一条阅读路径是有意保留的，
  未按方法继续切分。若后续要压缩，应连同时间序列辅助方法一起迁移，而不是拆出单方法类。
- 未做浏览器/UI 验收。本轮无前端产品代码改动，接口正确性由契约测试与前端测试覆盖；
  前端 38 项通过来自实施轮记录，本轮未重新执行。
- 新增的两处测试夹具依赖 `SET FOREIGN_KEY_CHECKS=0` 来省略部分 Gateway 父行，
  这依赖测试内显式事务固定连接；后续若移除该事务包装，夹具会失效。
- Advisor 内部证据构建职责（`buildServerEnvelope` 中的分支逻辑）仍未整理，属下一批次。

## 6. 下一步建议

先审查本次 diff 与上述测试证据，再决定是否继续。Advisor 证据构建职责整理可复用本轮测试；
网关执行与财务对账涉及各自的锁顺序与终结状态，留到有专项事务/并发验证的一轮单独处理。
