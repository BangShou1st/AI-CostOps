# 2026-11-12 剩余 P2/P3 后端修复（批次 B）

| 项目 | 内容 |
|---|---|
| 日期 | 2026-11-12 |
| 责任人 | AI（DeepSeek Harness；中心 agent + 3 个并行子代理） |
| 关联模块 | Gateway 请求授权/派发栅栏/用量终结、Backend 定价、路由策略、对账 run、报销幂等 |
| 缺陷来源 | [2026-11-12 深度代码审查](2026-11-12-deep-code-review.md) P2-10 / P2-11 / P2-13 / P2-14 / P2-15 / P3-03 / P3-04 |
| 代码基线 | `dev` = `c6b2a6e`（`main` = `39560cc`） |
| 关联分支 | `fix/b-gateway-fence`、`fix/b-pricing-routing`、`fix/b-reconciliation-expense` → 集成 `fix/remaining-b-backend` |
| 关联 PR | 本批 PR（创建后回填编号） |
| 变更性质 | 维护期既有缺陷最小修复；无新功能、无 Flyway、无 API 契约、无财务语义变更 |
| 总体判定 | **PASS（本地）**；远程 CI/Security 另行标注 |

本批只处理审查报告 §3.2 / §3.3 中剩余的 7 项后端发现，未触碰批次 A 已完成的 7 项，也未开始批次 C 的方案项。

---

## 1. 变更概述（before → after）

### P2-10 保留第二套仅测试使用的授权/派发路径

- **位置**：`gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestService.java`
- **Before**：`authorizeForRouting`（生产 orchestrator 使用）与 `authorizeAndFence`（仅 3 个集成测试使用）各自重复实现“身份校验 + 模型白名单/ACTIVE + 打开账期 + VALIDATED 请求收敛”。`authorizeAndFence` 还自带单候选选路、预算准入与 TX2，语义与 orchestrator 不同，会随维护漂移。
- **根因**：M14 orchestrator 引入后，旧的单候选派发入口没有退役，且两条路径的公共前置段是复制关系。
- **After**：
  - 抽出私有 `authorizePrelude(AuthorizeCommand)`，包含两条路径完全相同的步骤：idempotency key 校验/digest/fingerprint、credential-model allowlist、model ACTIVE、打开账期、`convergeExistingOrCreate`；
  - `authorizeForRoutingBlocking` 直接返回 prelude；`authorizeAndFenceBlocking` 先走 prelude，再做单候选选路、attempt、`admitSync`、`commitDispatchFence`，行为与错误码保持不变；
  - `authorizeAndFence` 的 Javadoc 明确标注 **test-only legacy single-candidate path**，生产必须走 `GatewayRequestOrchestrator.prepareInitial`，派发/预占语义变更必须改 orchestrator；
  - 3 个集成测试（`DispatchFenceIntegrationTest`、`GatewayRequestIdempotencyIntegrationTest`、`ReservationRecoveryIntegrationTest`）未删除、未改断言。
- **已知差异**：抽公共段后，test-only 路径的“打开账期检查”移到路由解析之前；当请求同时“无可用路由 + 无打开账期”时错误码从 `GATEWAY_FORBIDDEN` 变为 `GATEWAY_DEPENDENCY_UNAVAILABLE`。生产路径 `authorizeForRouting` 原本就是先检查账期再解析路由，因此生产语义不变；3 个定向测试均通过。

### P2-11 成功终结的 `DISPATCH_INTENT` 分支与 mapper 谓词矛盾

- **位置**：`gateway/src/main/java/com/aicostops/gateway/persistence/GatewayRequestMapper.java`、`gateway/src/main/java/com/aicostops/gateway/metering/GatewayUsageFinalizationService.java`
- **Before**：成功终结的 guard 允许 `lineage.requestState()` 为 `UPSTREAM_ACTIVE` 或 `DISPATCH_INTENT`，但 `markRequestTransportCompleted` 的 SQL 只允许 `state='UPSTREAM_ACTIVE'`。一旦请求状态仍是 `DISPATCH_INTENT`，UPDATE 影响 0 行 → `requireOne` 抛错 → 整个成功终结事务回滚，已确定的用量事实也不落库。
- **根因**：guard 与 SQL 谓词在维护中漂移；`markAttemptCompleted` 已接受 `DISPATCH_INTENT`，请求侧没有同步。
- **After（选择“放宽 SQL”）**：`markRequestTransportCompleted` 的 WHERE 改为 `state IN ('DISPATCH_INTENT','UPSTREAM_ACTIVE')`，guard 不变。
- **理由**：成功终结本身带有 Provider 成功证据；`DISPATCH_INTENT` 表示 `beginUpstream` 未持久化但上游确实返回。此时应收敛为 `TRANSPORT_COMPLETED`，而不是回滚已确定的用量事实。该选择与 attempt 侧谓词一致，也避免“成功请求永远停留在 DISPATCH_INTENT + ACTIVE 预占”的放大故障。
- **测试**：`GatewayUsageFinalizationIntegrationTest` 新增 2 个用例：端到端成功终结（FINAL 用量落库、request → `TRANSPORT_COMPLETED`、attempt → `COMPLETED`）与 mapper 谓词直测。旧 SQL 下先红（`2 errors`，`Expected one row for request transport completion, got 0`），修复后 9/9 通过。

### P2-13 定价创建缺少维度去重、版本号 `max+1` 无串行化、唯一键冲突 500

- **位置**：`backend/src/main/java/com/aicostops/gatewayadmin/application/ModelPricingService.java`、`backend/src/main/java/com/aicostops/gatewayadmin/infrastructure/ControlPlaneMapper.java`
- **Before**：请求可带重复 `dimensionCode`（大小写不同也算不同），唯一键冲突直接 500；`MAX(version)+1` 与插入之间没有串行化，并发/双击创建可能同一 version → 500。
- **根因**：入参没有业务级去重；版本分配没有组织级串行化；`DuplicateKeyException` 没有映射为可重试的业务冲突。
- **After**：
  - `dimensionCode` 统一 `toUpperCase(Locale.ROOT)` 后去重，重复维度在**任何写入前**返回 400 `VALIDATION_FAILED`，detail 列出重复维度；
  - 事务内先 `SELECT id FROM organization WHERE id=? FOR UPDATE` 锁 organization 行，再取 `MAX(version)`；锁序 organization → MAX(version) → pricing_version → pricing_rate；
  - `insertPricingVersion` + `insertPricingRate` 捕获 `DuplicateKeyException` → 409 `STATE_CONFLICT`（可重试），不再 500；
  - **维度覆盖校验未实现**（按审查要求只给建议）：建议后续在 `activate` 时要求 `INPUT_TOKEN` / `OUTPUT_TOKEN` 至少覆盖，避免只含单维度价目表被激活。
- **并发语义说明**：在 MySQL 默认 REPEATABLE READ 下，事务的一致性读快照可能早于 organization 锁建立，因此并发双击仍可能读到旧 `MAX(version)` 并触发唯一键 → 409。这是**有意的可重试契约**（审查要求“并发/双击 → 409”），不是 500；不为了强制“两次都 201”而改隔离级别或扩大锁范围。
- **测试**：`ModelPricingApiIntegrationTest` 新增重复维度用例，先红（旧实现 `DuplicateKeyException` → 500），后绿（`15/15`）；新增 `ModelPricingServiceTest` 纯 Mockito 覆盖“重复维度在任何写入前拒绝”与“唯一键冲突 → 409、不写 rate、不写审计”。

### P2-14 路由策略列表 N+1

- **位置**：`backend/src/main/java/com/aicostops/routing/application/RoutingPolicyService.java`、`backend/src/main/java/com/aicostops/routing/infrastructure/RoutingPolicyMapper.java`
- **Before**：`list` 对当前页每条 policy 调用一次 `findCandidates`，页大小 200 时约 202 次往返。
- **After**：新增 `findCandidatesByPolicyIds`（`<script>` + `<foreach>` 的 org 域内 `IN` 查询，`ORDER BY routing_policy_id, priority, id`），`list` 一次批量取候选并按 policyId 分组构造响应；空页不执行候选查询。`get` / `requirePolicy` / `requirePolicyForUpdate` 仍走单条查询，未扩大改动。
- **测试**：新增 `RoutingPolicyServiceTest`（Mockito）断言 `findCandidatesByPolicyIds` 调用 1 次、`findCandidates` 调用 0 次，空页不查候选；新增 `RoutingPolicyApiIntegrationTest` 用真实 MySQL 验证两条策略各自返回候选。

### P2-15 对账 run 进程崩溃后永久 `RUNNING`

- **位置**：`backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationRunService.java`、新增 `ReconciliationRunRecoveryScheduler.java`、`backend/src/main/java/com/aicostops/reconciliation/infrastructure/ReconciliationMapper.java`、`backend/src/main/resources/application.yml`、`backend/src/test/resources/application-test-defaults.yml`
- **Before**：`run` 在事务里插入 `RUNNING`；进程崩溃/重启后没有补偿入口，“活跃对账”永久非零，误导运维。
- **根因**：只考虑正常完成/异常失败两条路径，没有考虑进程整体死亡；`CREATED` 枚举从未写入。
- **After（选择“启动 + 定时收敛”）**：
  - `ReconciliationMapper.selectStaleRunningRuns(cutoff, limit)` 跨组织扫描 `RUNNING` 且 `started_at < cutoff` 的 run，`LEFT JOIN organization_member` 取启动者 user id；
  - `ReconciliationRunService.failStaleRunningRuns(Duration, int)` 对每个候选独立事务：`selectRunByIdForUpdate` 后二次校验仍为 `RUNNING` 且仍早于 cutoff，`markRunFailed` CAS，错误码 `RECONCILIATION_RUN_ABANDONED`；CAS 成功后在同一事务内写 `RECONCILIATION_RUN_FAILED` 审计（actor 为启动者；member 已删除则跳过审计但仍收敛），提交后计 `FAILED` 指标；单条失败只记录并继续；
  - 新增 `ReconciliationRunRecoveryScheduler`：默认启动后 30s 首次、之后 5m 间隔，超时 30m、批量 100，均可通过 `aicostops.reconciliation.*` / `AICOSTOPS_RECONCILIATION_*` 覆盖；多实例安全由行锁 + 状态 CAS 保证；
  - 测试默认配置 `aicostops.reconciliation.stale-run-recovery-enabled: false`，避免共享 Testcontainer 被后台调度器干扰；生产默认 `true`。
- **测试**：新增 `ReconciliationStaleRunRecoveryIntegrationTest`，先红（实现前 testCompile 找不到 `failStaleRunningRuns`），后绿（1/1）：2 小时前的 run 收敛为 FAILED 且审计 actor 正确，当前 RUNNING 不受影响，第二次扫描返回 0。
- **口径说明**：未修改 `WorkbenchQueryMapper` / close blocker。崩溃后到“超时 + 下一轮扫描”之间，“活跃对账”仍会短暂非零；这是状态真实，不是误报。

### P3-03 `EXPENSE_RESUBMIT` 幂等操作死代码

- **位置**：`backend/src/main/java/com/aicostops/expense/application/ExpenseIdempotency.java`
- **Before**：`OPERATION_RESUBMIT` 常量与 `resubmitRequestHash(...)` 只有定义，`ExpenseClaimCommandService` 固定使用 `OPERATION_SUBMIT`。
- **After**：删除常量与方法；全仓 grep 除定义外 0 引用。未改其他 operation 与 hash 逻辑。

### P3-04 `InitialCandidateResult.skipped()` 死代码

- **位置**：`gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestOrchestrator.java`
- **Before**：`InitialCandidateResult.skipped(...)` 无调用点，容易被误认为存在第三条“跳过候选”路径。
- **After**：删除该方法；`NextCandidateResult.skipped()` 是另一个类，3 处调用点保留。

---

## 2. 验证环境与执行命令

环境（2026-11-12）：Windows 11 + Git Bash / PowerShell 7，Node `v24.14.0`，Java `21.0.10`，Docker Desktop 引擎就绪。

| # | 命令 | 结果 |
|---|---|---|
| 1 | `cd backend; .\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test` | `667 run / 0 failed / 2 skipped`，BUILD SUCCESS |
| 2 | `cd backend; .\mvnw.cmd -B "-Dgroups=architecture" test` | `36 run / 0 failed / 0 skipped`，BUILD SUCCESS |
| 3 | `cd gateway; .\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test` | `211 run / 0 failed / 1 skipped`，BUILD SUCCESS（含 `GatewayArchitectureTest` 7） |
| 4 | `cd backend; .\mvnw.cmd -B verify "-Dit.test=ModelPricingApiIntegrationTest,RoutingPolicyApiIntegrationTest,ReconciliationStaleRunRecoveryIntegrationTest" "-DfailIfNoTests=true"` | `17 run / 0 failed / 0 skipped`（定价 15、路由 1、对账回收 1），BUILD SUCCESS |
| 5 | `cd gateway; .\mvnw.cmd -B verify "-Dtest=GatewayApplicationTest" "-Dit.test=GatewayUsageFinalizationIntegrationTest,DispatchFenceIntegrationTest,GatewayRequestIdempotencyIntegrationTest,ReservationRecoveryIntegrationTest" "-DfailIfNoTests=true"` | `29 run / 0 failed / 0 skipped`（用量终结 9、派发栅栏 5、幂等 3、预占恢复 12），BUILD SUCCESS |
| 6 | `npm run test:scripts` | `54 passed / 0 failed` |
| 7 | `npm run verify:links` | 见 §2.2 |

### 2.1 已知测试抖动（非本次改动引入）

第一次跑 gateway 全量 `verify` 时，`GatewayApplicationTest.prometheusMetricsAreExposed` 在 5s blocking read 上超时（`233 run / 1 error`）。该用例是 P1 记录过的“与重负载并行时的既有超时抖动”；单独复跑 `-Dtest=GatewayApplicationTest` 得到 `4 run / 0 failed / 0 skipped`，随后只带该类 + 4 个定向 IT 复跑得到 `29/29`。未修改断言或超时阈值。

### 2.2 文档链接

```text
$ npm run verify:links
[verify-doc-links] Found 249 Markdown files to inspect.
[verify-doc-links] Checked 416 links.
[verify-doc-links] SUCCESS: 0 broken links found! All local document links are valid.
```

---

## 3. `NOT_VERIFIED` 与已知边界

| 项 | 状态 | 说明 |
|---|---|---|
| P2-10 test-only 路径的错误码优先级 | 已记录差异 | “无路由 + 无打开账期”同时出现时，test-only 路径返回 503 而非 403；生产路径不变，3 个定向测试通过 |
| P2-13 并发双击的真实隔离级别行为 | `NOT_VERIFIED`（未做并发压测） | 设计契约是“锁 + 唯一键兜底 → 冲突时 409 可重试”，未断言两次都 201；未改隔离级别 |
| P2-13 维度覆盖校验 | `NOT_IMPLEMENTED`（按审查要求） | 建议后续在 `activate` 校验 INPUT/OUTPUT 覆盖 |
| P2-14 SQL 往返次数的运行时计数 | `NOT_VERIFIED` | 用 Mockito 断言“批量查询 1 次、单条查询 0 次”证明调用面；未接入 SQL 计数探针 |
| P2-15 scheduler 的真实定时触发 | `NOT_VERIFIED` | 集成测试直接调用 `failStaleRunningRuns`；测试默认关闭 scheduler，避免污染共享 Testcontainer。`@Scheduled` 间隔/超时默认值属静态配置 |
| 本批 PR 的远程 CI / Security | `NOT_VERIFIED`（本地阶段） | 以 PR 页面实际结果为准 |
| 前端测试 | `NOT_RUN` | 本批无前端改动；CI 的 `frontend-test` 会覆盖 |

---

## 4. 影响边界

- **财务语义**：无改动；不触碰预算预占、结算、账本、关账、Flyway、金额计算。P2-11 只放宽“成功证据存在时的传输完成状态转移”，不放宽任何资金写入或结算条件。
- **API / 契约**：无路径/DTO 变更；P2-13 把重复维度从 500 改为 400、并发冲突从 500 改为 409，属于错误语义修正，前端按 `ProblemDetail` 展示。
- **依赖**：无新增。
- **性能**：路由策略列表由 N+1 降到 1 次批量查询；定价创建多一次 organization 行锁。
- **运维**：对账 run 多一个后台扫描（默认 30s 首扫、5m 间隔、30m 超时、100 条/批）；测试配置默认关闭。
- **Gateway**：生产派发路径不变；test-only 单候选入口保留但已显式标注。

---

## 5. 变更文件清单

```text
backend/src/main/java/com/aicostops/expense/application/ExpenseIdempotency.java
backend/src/main/java/com/aicostops/gatewayadmin/application/ModelPricingService.java
backend/src/main/java/com/aicostops/gatewayadmin/infrastructure/ControlPlaneMapper.java
backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationRunService.java
backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationRunRecoveryScheduler.java（新增）
backend/src/main/java/com/aicostops/reconciliation/infrastructure/ReconciliationMapper.java
backend/src/main/java/com/aicostops/routing/application/RoutingPolicyService.java
backend/src/main/java/com/aicostops/routing/infrastructure/RoutingPolicyMapper.java
backend/src/main/resources/application.yml
backend/src/test/java/com/aicostops/gatewayadmin/ModelPricingApiIntegrationTest.java
backend/src/test/java/com/aicostops/gatewayadmin/application/ModelPricingServiceTest.java（新增）
backend/src/test/java/com/aicostops/reconciliation/ReconciliationStaleRunRecoveryIntegrationTest.java（新增）
backend/src/test/java/com/aicostops/routing/RoutingPolicyApiIntegrationTest.java（新增）
backend/src/test/java/com/aicostops/routing/application/RoutingPolicyServiceTest.java（新增）
backend/src/test/resources/application-test-defaults.yml
gateway/src/main/java/com/aicostops/gateway/persistence/GatewayRequestMapper.java
gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestOrchestrator.java
gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestService.java
gateway/src/test/java/com/aicostops/gateway/metering/GatewayUsageFinalizationIntegrationTest.java
docs/03-acceptance/maintenance/2026-11/2026-11-12-remaining-p2-p3-backend.md
docs/03-acceptance/maintenance/2026-11/2026-11-12-deep-code-review.md（追加处置记录）
docs/03-acceptance/README.md
CHANGELOG.md
```

---

## 6. 结论

批次 B 的 7 项后端发现全部按“测试先行、最小修复”完成。Gateway 成功终结的 guard/SQL 已一致；定价创建对重复维度返回 400、对并发唯一键冲突返回 409；路由策略列表消除 N+1；对账僵尸 RUNNING 具备启动 + 定时收敛与审计；两处确认无引用的死代码已删除。本地后端/网关单元、架构、定向集成测试全部通过；远程 CI/Security 与并发隔离级别行为按 §3 标注为待验证。
