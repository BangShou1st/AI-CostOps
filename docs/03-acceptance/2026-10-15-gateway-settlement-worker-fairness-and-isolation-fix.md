# 2026-10-15 Gateway Settlement Worker 公平轮转与组织级隔离修复（G-01 / G-02）

本记录针对独立源码审查标记的两个候选缺陷 G-01（固定 100 组织可能长期饥饿）与
G-02（单组织异常可能终止整轮扫描），基于真实源码复核、先写失败测试复现、再实施最小修复并
验证。修复前基线：`codex/history-coverage-20261012@ea2249186e44e34c71a06d035c546ff248e28270`
（开始前已核对本地 HEAD、`git status`、`git fetch` 后 `origin/codex/history-coverage-20261012`
一致，`HEAD...origin` 计数为 `0  0`，工作区无已跟踪改动）。本批修复提交：
`496f93c`（生产代码与测试，本记录所在提交为其后续文档提交）。本记录随分支推送供远端独立代码
审查，不代表发布验收通过。

## 1. 结论

**G-01 确认成立并已修复**：`GatewaySettlementWorker.runOnce()` 调用的
`GatewaySettlementMapper.selectOrganizationsWithWork(now, 100)` 原为固定
`ORDER BY org_id ASC LIMIT 100`，没有跨轮次游标/轮转。当有资格被扫描的组织超过 100 且低 id
组织跨轮次持续有待处理工作时，前 100 个组织永远占据名额，后续组织**连续多轮都无法获得处理
机会**。真实 MySQL 测试复现：101 个组织、低 id 100 个持续 PENDING，连续 5 轮后最高 id 组织
从未被访问。

**G-02 确认成立并已修复**：`runOnce()` 的组织遍历只捕获 `TransientDataAccessException`；而
`GatewaySettlementDiscoveryService.discover(...)` 存在 `IllegalStateException` 等非瞬态异常
路径，`attempt()` 的捕获范围也只限于 `TransientDataAccessException`。任一组织出现非瞬态异常
会**直接终止整轮扫描**，后续健康组织本轮得不到结算。故障注入测试复现：组织 A 抛
`IllegalStateException` 后整轮抛出，组织 B 未被处理。

两个缺陷均以“先新增、在旧代码上失败”的定向测试复现（§6.1），再修复（§4）后转绿。

## 2. G-01：固定 100 个组织长期饥饿

### 2.1 根因

`GatewaySettlementWorker.runOnce()`（修复前）每轮以常量上限一次性取组织：

```java
var organizations = settlements.selectOrganizationsWithWork(now, MAX_ORGANIZATIONS_PER_POLL);
```

对应 SQL 无游标、无排序轮转：

```sql
... ) work_orgs
ORDER BY org_id ASC
LIMIT #{limit}
```

每轮都从最小的 `org_id` 开始取满 100 个。只要存在 `>100` 个有资格组织，且低 id 组织下一轮仍有
待处理工作（新发现的 FINAL 用量或到期重试），它们每轮都重新占满名额；高 id 组织被
`LIMIT 100` 持续截断，**没有任何跨界公平性**。

### 2.2 影响

- 组织数量超过 100 时，靠后组织的 Gateway Settlement 可能长期延迟（理论上的持续饥饿），
  进而延迟其 Ledger/Budget Actual/Commitment 收敛与账期关账。
- 属“哪些组织在本轮被处理”的调度公平性问题，不产生错误金额；MySQL 仍是最终财务真相。

### 2.3 修复（最小、有界、公平的轮转）

只引入一个 **keyset 游标 + 查询内环绕**，不引入任何调度框架、Redis 锁或消息队列：

- `GatewaySettlementMapper.selectOrganizationsWithWork(now, afterOrgId, limit)` 新增
  `afterOrgId` 游标参数，排序改为
  `ORDER BY (work_orgs.org_id > #{afterOrgId}) DESC, work_orgs.org_id ASC`：
  先返回严格大于游标的组织（升序），到达末尾后**在同一查询内环绕**回最小 id。因此每次轮询
  总是返回“从游标处开始的连续一页”，不会出现必须靠一次空页才能收敛的边界。
- `GatewaySettlementWorker` 新增 `private long afterOrgId;` 内存游标；**只有在整页被完整处理
  后**才把游标推进到 `organizations.getLast()`。页为空或本轮因全局依赖故障提前返回时，游标
  保持不变。

性质：
- **无永久遗漏**：游标每轮按页推进并在查询内环绕，任何组织都会在 `ceil(组织数/页大小)` 轮
  内被覆盖。
- **重复财务入账不可能因分页引入**：分页只改变“哪些组织被访问”，Settlement 的业务唯一性、
  幂等收敛与原子财务事务完全不变（§8）。
- **无无限循环**：每轮至多一页、`LIMIT` 有界；游标推进量有界。
- **单实例 / 多实例**：游标是每个 worker 实例的内存状态，各自独立环绕；单实例最终覆盖全部
  组织（§6.2），多实例各自独立也最终覆盖全部组织（§6.2 的多实例用例），多实例只会造成
  同一组织被重复访问（幂等收敛，无双重入账）。进程重启时游标回到起点，只是从最小 id 重新
  开始环绕，不造成永久遗漏。

## 3. G-02：单组织异常终止整轮

### 3.1 根因

修复前的组织遍历只有：

```java
try {
    discovery.discover(organizationId, DEFAULT_BATCH_SIZE);
    for (var settlementId : discovery.workIds(...)) { attempt(...); }
} catch (TransientDataAccessException transientDiscoveryFailure) {
    metrics.gatewaySettlementRetry("DATABASE_TRANSIENT");
}
```

`GatewaySettlementDiscoveryService.discover(...)` 在“发现的 Settlement 不可读”时抛
`IllegalStateException`（`IllegalStateException("A discovered Gateway Settlement must be
readable")`）；`attempt()` 也只捕获 `TransientDataAccessException`，`service.settle(...)` 内部
`transactions.execute(...)` 抛出的非瞬态运行期异常同样会穿透 `attempt` 与组织循环。任一组织
的非瞬态异常都会**终止 `runOnce()`**，本轮其后的组织全部不再有机会。

### 3.2 影响

一个组织的异常（`IllegalStateException`、非瞬态 `DataAccessException` 等）会抑制整轮对其后
所有健康组织的结算，放大为“整轮沉默失败”，且没有任何日志/指标痕迹。

### 3.3 修复（区分可隔离异常与全局依赖故障，显式日志/指标/恢复路径）

在 `runOnce()` 的组织循环按异常类别分派，且在 `attempt()` 内按结算级分派：

| 类别 | 判定 | 处置 | 依据 |
| --- | --- | --- | --- |
| 全局依赖故障 | `DataAccessResourceFailureException`（无法取得连接等资源不可达） | 记 `dependencyError("DATABASE")`、WARN（脱敏）、**停止本轮且不推进游标**；下一轮调度重试同一页 | 非单组织可隔离；不空转 |
| 组织级瞬态 | `TransientDataAccessException` | 记 `gatewaySettlementRetry("DATABASE_TRANSIENT")` + WARN（脱敏），继续本轮后续组织 | 事务已回滚、discovery 无部分财务写入 |
| 组织级其它 | 其余 `RuntimeException` | 记 `gatewaySettlementRetry("SETTLEMENT_ISOLATED_FAILURE")` + WARN（脱敏），继续本轮后续组织 | 同上 |
| 结算级其它（`attempt`） | 其余 `RuntimeException` | 走**既有有界持久失败路径**：`markRetryableFailed(..., "SETTLEMENT_ERROR")`，达到
`MAX_AUTO_RETRIES` 后升级 `markReconciliationRequired("RETRY_EXHAUSTED")` | 结算事务已回滚；有界收敛，不无限重试 |

要点：

1. **不使用空 `catch (RuntimeException)`**：每个分支都有日志与低基数指标，非静默吞错。
2. **只在事务可回滚/无部分写入时才继续**：`attempt` 依赖 `GatewaySettlementService.settle`
   的 `TransactionTemplate`（异常即回滚）；`discovery.discover` 只做幂等 `insertPending`，
   不落部分财务状态。因此继续处理后续组织是安全的。
3. **后续恢复路径明确**：组织级失败下一轮调度重新读取该组织（discovery 幂等、工作集仍在）；
   结算级失败进入有界重试并在耗尽后进入人工 `RECONCILIATION_REQUIRED` 边界。
4. **不形成紧密无限重试**：轮内每组织/每结算只尝试一次；跨轮由 `@Scheduled(fixedDelayString
   ...:5s)` 节流；结算级失败有 `MAX_AUTO_RETRIES` 上限。
5. **全局依赖故障停止本轮**，游标不推进，避免对不可达数据源空转 100 次。

日志脱敏复用既有 fail-closed `SecretShapes.redact(...)`，日志只含数值组织/结算 id 与脱敏后的
异常文本，不含密钥、原始请求内容或账单明细。

## 4. 生产代码改动（最小）

| 文件 | 改动 |
| --- | --- |
| `gatewaysettlement/infrastructure/GatewaySettlementMapper.java` | `selectOrganizationsWithWork` 新增 `afterOrgId` keyset 游标参数；排序改为游标优先 + 查询内环绕；补充契约注释。 |
| `gatewaysettlement/application/GatewaySettlementWorker.java` | 新增内存游标 `afterOrgId`（仅在整页完整处理后推进）；组织循环按“全局依赖 / 组织级瞬态 / 组织级其它”分派并记日志+指标；`attempt` 对非瞬态异常走既有有界持久失败路径（`SETTLEMENT_ERROR`），对资源不可达异常透传以便停止本轮；原 `recordTransientFailure` 泛化为 `recordBoundedFailure(org, id, errorCode)`（语义不变，仅参数化错误码）。 |
| `observability/AiCostOpsMetrics.java` | 有界理由码目录新增 `SETTLEMENT_ISOLATED_FAILURE`、`SETTLEMENT_ERROR`（保持低基数、非自由文本）。 |

未改动：预测/Advisor、Provider Adapter、Gateway API、`GatewaySettlementService` 的财务事务
契约、`GatewaySettlementDiscoveryService`、任何 Flyway 迁移、任何 API、`LICENSE`。未放宽断言、
未扩大超时、未删除测试。

## 5. 测试改动

### 5.1 新增真实 MySQL 集成测试（`GatewaySettlementWorkerTraversalIntegrationTest`，5 项）

`backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementWorkerTraversalIntegrationTest.java`
（`@SpringBootTest`，`@Tag("integration")`，MySQL 8.4 Testcontainers）。为精确控制游标与轮次，
测试手动构造 `GatewaySettlementWorker`（非 `@Scheduled` Bean），并仅 mock 下游
`GatewaySettlementService`（隔离被测的“访问了哪些组织”）；组织、请求、结算行均为真实 MySQL。
工作以真实 PENDING `gateway_settlement` 行播种（worker 自身的 discovery 分支2），使低 id 组织
跨轮次持续有待处理工作——正是饥饿的前提。

| 用例 | 断言 |
| --- | --- |
| `repeatedRoundsEventuallyReachEveryOrganizationBeyondOnePage` | 101 组织；第一轮恰好前 100 个（页边界）；4 轮内最高 id 组织被访问且全部 101 个被覆盖 |
| `rotationDoesNotDuplicateSettlementRowsWhenOrganizationsRepeat` | 跨轮覆盖全部组织，且每组织结算行数恒为 1（分页不产生重复行） |
| `independentWorkerInstancesEachTraverseEveryOrganization` | 两个独立游标 worker 交替各跑 4 轮，各自覆盖全部 101 个组织 |
| `organizationFailureIsIsolatedAndTheRestOfTheRoundStillSettles` | 组织 A 的 `discover` 抛 `IllegalStateException`：`runOnce` 不抛出、组织 B 仍被结算、A 不被计为成功、记 `SETTLEMENT_ISOLATED_FAILURE` |
| `datasourceOutageStopsTheRoundWithoutPreventingRecovery` | 组织 A 的 `discover` 抛 `DataAccessResourceFailureException`：本轮停止（无任何结算）、不记组织级隔离指标；依赖恢复后下一轮 A、B 均正常结算 |

### 5.2 新增 Mockito 单元测试（`GatewaySettlementWorkerTest`，新增 6 项）与指标目录断言

对控制流分支做精确冻结（无数据库）：

| 用例 | 冻结的契约 |
| --- | --- |
| `isolatedSettlementExceptionBecomesBoundedDurableRetry` | 非瞬态结算异常→ `markRetryableFailed(..., "SETTLEMENT_ERROR")` + 指标 |
| `exhaustedIsolatedSettlementFailuresEscalateToReconciliationRequired` | 达到 `MAX_AUTO_RETRIES` → `markReconciliationRequired("RETRY_EXHAUSTED")`，不再重试 |
| `datasourceReachabilityFailureEscapesAttemptSoTheRoundCanStop` | 资源不可达异常从 `attempt` 透传，且不写任何结算状态 |
| `runOnceIsolatesOrganizationDiscoveryFailureAndKeepsProcessing` | 组织 A discovery 失败，组织 B 仍被 `settle`，记 `SETTLEMENT_ISOLATED_FAILURE` |
| `runOnceKeepsTheCursorWhenTheDatasourceBecomesUnreachable` | 连续两轮同一页：游标不推进，两次记 `dependencyError("DATABASE")` |
| `runOnceAdvancesTheCursorOnlyAfterAFullyProcessedPage` | 游标序列 `0 → 100`（仅整页处理后推进） |

`AiCostOpsMetricsTest.gatewaySettlementMetricsUseOnlyBoundedLabels` 增补：`SETTLEMENT_ISOLATED_FAILURE`、
`SETTLEMENT_ERROR` 不再映射为 `UNKNOWN`，`request-123` 仍为 `UNKNOWN`（低基数不变）。

### 5.3 既有回归（未改动）

`GatewaySettlementTransactionIntegrationTest`（幂等/并发/回滚/行锁/关账等待）、
`GatewaySettlementDiscoveryIntegrationTest`、`PeriodCloseCoordinatorIntegrationTest`
（其中 `settlementWorker.runOnce()` 期望 1 的到期时间用例）、`GatewayFinancialWorkCloseIntegrationTest`、
`GatewayFinancialResolutionIntegrationTest`、`M15FinancialConcurrencyIntegrationTest` 全部保留并
在修复后重跑（§6.3）。架构护栏 `GatewaySettlementArchitectureTest` /
`ModuleDependencyArchitectureTest` 仍覆盖新依赖（`SecretShapes` 属 `ingestion.application`，
不在 `ingestion.providers` 禁列内）。

## 6. 验证记录

环境：Windows，JDK 21，Docker 29.8，Testcontainers MySQL 8.4。命令、结果与退出码如实记录；
未放宽断言、未扩大超时。报告目录：见各小节。

### 6.1 修复前（缺陷复现，基线 `ea22491`）

```text
命令(backend): .\mvnw.cmd -B -q test-compile "failsafe:integration-test"
              "-Dit.test=GatewaySettlementWorkerTraversalIntegrationTest"
              "-Dfailsafe.failIfNoSpecifiedTests=false"
结果: Tests run: 5, Failures: 5, Errors: 0, Skipped: 0
退出码: 1
```

5 项失败直接呈现两个缺陷：

- G-01（3 项）：`...could not find the following element(s): [307L]`——101 组织中最高 id 组织
  从未被访问（`independentWorkerInstances…`、`repeatedRoundsEventually…`、
  `rotationDoesNotDuplicate…`）。
- G-02（2 项）：`organizationFailureIsIsolated…` 因 `java.lang.IllegalStateException:
  organization discovery exploded` 穿透 `runOnce` 而失败；`datasourceOutageStopsTheRound…`
  因 `org.springframework.dao.DataAccessResourceFailureException: database unreachable` 穿透
  而失败。

### 6.2 修复后：新增集成测试

```text
命令(backend): .\mvnw.cmd -B -q test-compile "failsafe:integration-test"
              "-Dit.test=GatewaySettlementWorkerTraversalIntegrationTest"
              "-Dfailsafe.failIfNoSpecifiedTests=false"
结果: Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 (77.0 s)
退出码: 0
```

运行日志中可见组织级隔离的实际 WARN：
`Gateway Settlement discovery failed for organization 205: java.lang.IllegalStateException:
organization discovery exploded` 与
`Gateway Settlement round stopped, datasource unavailable while scanning organization 102:
...DataAccessResourceFailureException: database unreachable`（后者继续下一轮恢复）。

### 6.3 修复后：既有相关集成回归（真实 MySQL）

```text
命令(backend): .\mvnw.cmd -B -q test-compile "failsafe:integration-test"
              "-Dit.test=GatewaySettlementTransactionIntegrationTest,GatewaySettlementDiscoveryIntegrationTest,
               GatewaySettlementSchemaIntegrationTest,PeriodCloseCoordinatorIntegrationTest,
               GatewayFinancialWorkCloseIntegrationTest,GatewayFinancialResolutionIntegrationTest,
               M15FinancialConcurrencyIntegrationTest"
              "-Dfailsafe.failIfNoSpecifiedTests=false"
结果: 全部通过（0 failures、0 errors）
退出码: 0
```

| 测试类 | 结果 |
| --- | --- |
| `GatewaySettlementTransactionIntegrationTest` | 14/14 通过 |
| `GatewaySettlementDiscoveryIntegrationTest` | 2/2 通过 |
| `GatewaySettlementSchemaIntegrationTest` | 2/2 通过 |
| `PeriodCloseCoordinatorIntegrationTest`（含 `runOnce()==1`） | 7/7 通过 |
| `GatewayFinancialWorkCloseIntegrationTest` | 20/20 通过 |
| `GatewayFinancialResolutionIntegrationTest` | 36/36 通过 |
| `M15FinancialConcurrencyIntegrationTest` | 4/4 通过 |

### 6.4 修复后：后端单元/架构测试（Mockito，无数据库）

```text
命令(backend): .\mvnw.cmd -B -q "-Dtest=GatewaySettlementWorkerTest,AiCostOpsMetricsTest" test
结果: GatewaySettlementWorkerTest 9/9、AiCostOpsMetricsTest 6/6，0 failures、0 errors
退出码: 0
```

### 6.5 后端完整 `verify`

```text
命令(backend): .\mvnw.cmd -B verify
结果: BUILD SUCCESS
退出码: 0
总耗时: 32:16 min
日志: .local-dev/reviews/2026-10-15-gateway-settlement-worker-fix/verify-1.log
```

| 阶段 | 结果（本运行实测汇总） |
| --- | --- |
| 后端 surefire（单元 + 架构，含本批新增 6 项单测与架构护栏） | 651 项，0 failures，0 errors，1 Windows 平台跳过 |
| 后端 failsafe（全量集成，含本批新增 5 项回归与全部既有 Gateway/财务/关账套件） | 1241 项，0 failures，0 errors，0 skipped |

covers 架构护栏（`GatewaySettlementArchitectureTest` / `ModuleDependencyArchitectureTest`）与
既有 `GatewaySettlementWorkerTraversalIntegrationTest` 新增用例；本批未触达 Gateway 与前端模块，
未执行根目录全链 `npm run verify`（见 §9）。

## 7. 证据分类

- **真实 MySQL（Testcontainers）**：§6.1（修复前复现）、§6.2（新增 5 项）、§6.3（既有回归）。
- **仅 Mockito 单元（无数据库）**：§5.2 / §6.4，冻结控制流分支与指标目录。
- **架构护栏**：`GatewaySettlementArchitectureTest` / `ModuleDependencyArchitectureTest`
  由完整 `verify` 的 surefire 覆盖（§6.5）。
- **尚未充分验证**：见 §9。

## 8. 财务正确性约束核对

保留不变：MySQL 为最终财务真相；Settlement 业务唯一性（`uq_gateway_settlement_org_key` /
`org_request` / `org_usage`）与幂等收敛；Settlement、Ledger、Budget Actual、Commitment、
Reservation 的原子财务事务；普通重试不双重入账；`FINAL` / `INCOMPLETE` / `UNKNOWN` 语义；
未知费用不视为零；`RECONCILIATION_REQUIRED` 的人工边界；BillingPeriod/Close 行锁与状态约束；
POSTED 账本不可变、修正仅追加；每组织严格隔离；日志不泄露密钥/原始请求/敏感账单。

本次改动都是**调度层**改动：不改变任何金额、状态机、幂等键或事务边界；分页只改变“哪些组织
被访问”，结算级失败仍进入既有有界重试/人工边界。

## 9. 未验证的风险与边界

- **真实生产级数据库故障模式未验证**：测试注入的是应用层异常（`IllegalStateException` /
  `DataAccessResourceFailureException`）。真实 MySQL 连接池耗尽、主从切换、长事务超时等场景
  未验证；设计立场是“组织级隔离继续、资源不可达停止本轮、下一轮调度重试”，这些场景退化为
  “本轮部分组织延迟到下轮”，不产生金额错误。
- **多实例页级竞争未做定向验证**：游标为每实例内存状态，各自独立环绕并最终覆盖全部组织
  （§6.2 已验证两个独立实例各自覆盖）。两个实例同时处理同一组织时由既有业务唯一性/事务收敛
  保证无双重入账；未新增双实例页级竞争的定向用例。
- **游标为内存状态**：进程重启后游标从 0（最小 id）重新开始环绕。不造成永久遗漏，但重启后
  第一页可能重复访问已处理组织（幂等）。
- **`DataAccessResourceFailureException` 作为“全局故障”判据**：属本批选定的语义边界；若未来
  出现其他“全局但非资源不可达”的异常类型，需要重新评估归类。
- **未执行根目录全链 `npm run verify`**：本批仅改动 backend（一个生产文件族 + 其测试 + 一个
  可观测性目录常量），Gateway 与前端零改动；backend 完整 `verify` 已全绿（§6.5）。Gateway /
  前端最近一次全绿记录属更早基线，不能套用到当前分支。
- 交接记录中的 Gateway 一次 403 间歇失败、共享 Redis 污染假设、真实 Provider/OpenCode 联调、
  独立浏览器组织隔离仍为未解决/DEFERRED，本批未触碰。
