# 2026-10-15 Gateway Settlement Worker 初始组织扫描故障处理修复（G-03）

本记录针对独立源码审查标记的 G-03：`GatewaySettlementWorker.runOnce()` 的**初始**组织扫描
`selectOrganizationsWithWork(...)` 位于新增的组织级 `try/catch` **之前**，数据库不可达时该异常
未被处理。基于真实源码复核、先写失败测试复现，再实施最小修复并验证。

修复前基线：`codex/history-coverage-20261012@9a539b02c25d16950b8af16743de3f4d43ba6f00`
（开始前已核对本地 HEAD、`git status`、`git fetch` 后 `origin/codex/history-coverage-20261012`
一致，`HEAD...origin` 计数为 `0  0`，工作区无已跟踪改动）。本批修复提交：
`0b62af59018c3ca2618c6398f143e97df209ff2b`（生产代码与测试），本记录所在提交为其后续文档提交。
本记录随分支推送供远端独立代码审查，
不代表发布验收通过。

## 1. 结论

**G-03 确认成立并已修复**。修复前 `runOnce()` 的第一条查询不在任何 `try/catch` 内：数据库不可达
（`DataAccessResourceFailureException`）时异常直接穿透 `runOnce()`，**未记录 `DATABASE` 依赖指标、
未写脱敏 WARN、未“安全结束本轮”**。以 3 项 Mockito 单元测试在旧代码上复现（`Tests run: 3,
Errors: 3`，异常均从 `GatewaySettlementWorker.runOnce(GatewaySettlementWorker.java:88)` 抛出）。

修复后同组测试 3/3 通过，并在真实 MySQL 集成测试中新增 1 项恢复用例。

## 2. 根因与影响

修复前（`GatewaySettlementWorker.java`）：

```java
public int runOnce() {
    var now = Instant.now(clock);
    var organizations = settlements.selectOrganizationsWithWork(          // <-- 不在任何 try/catch 内
            now, afterOrgId, MAX_ORGANIZATIONS_PER_POLL);
    var attempted = 0;
    for (var organizationId : organizations) {
        try { ... }                                   // 组织级隔离（G-02）从这里才开始
        catch (DataAccessResourceFailureException globalFailure) { ... }
        ...
    }
```

- **影响**：数据库不可达发生在第一查询时，本轮以“未捕获异常”方式结束——没有 `dependencyError
  ("DATABASE")` 指标、没有 `SecretShapes.redact` 处理过的 WARN；对于直接调用 `runOnce()` 的调用方
  （测试、其他编排代码），异常直接外泄。`@Scheduled` 场景下 Spring 默认错误处理器会记 ERROR 并在
  下一周期重试，但这既是“另一条未脱敏的错误路径”，也不能替代本模块既定的依赖信号与安全结束语义。
- **必须避免的误判**：不能把“数据库故障”当作“本轮没有待结算工作”。若简单 `catch` 后返回空列表，
  会把故障静默降级为“无工作”，从而**丢失对依赖不可用的可见性**。正确做法是显式记录依赖故障后
  安全结束本轮，并保持游标不变，使下一轮重新处理同一页。

## 3. 修复方案（最小，不引入调度框架）

只新增一个针对初始扫描的 `try/catch`，与既有组织级/结算级处理明确区分，语义如下：

| 阶段 | 异常 | 处置 |
| --- | --- | --- |
| **初始组织扫描**（新增） | `DataAccessResourceFailureException` | 记 `dependencyError("DATABASE")` + 脱敏 WARN（“during the initial organization scan”）；`return 0`；**不推进游标** |
| 单组织发现（既有 G-02） | `DataAccessResourceFailureException` | 记 `dependencyError("DATABASE")` + 脱敏 WARN（“while scanning organization N”）；返回已尝试数；不推进游标 |
| 单组织发现（既有 G-02） | `TransientDataAccessException` | 记 `DATABASE_TRANSIENT` + WARN；继续本轮 |
| 单组织发现（既有 G-02） | 其余 `RuntimeException` | 记 `SETTLEMENT_ISOLATED_FAILURE` + WARN；继续本轮 |
| 单结算（既有 G-02） | 其余 `RuntimeException` | 既有有界持久失败路径（`SETTLEMENT_ERROR` → `RETRY_EXHAUSTED`/`RECONCILIATION_REQUIRED`） |

要点：

1. **明确区分**初始扫描失败与单组织发现/结算失败：日志文案不同（“initial organization scan” vs
   “while scanning organization N”），且初始扫描失败直接结束本轮（因为没有组织可处理）。
2. **不误判为“没有待结算工作”**：返回 0 之前先记录 `dependencyError("DATABASE")` 与 WARN；游标保持
   不变，下一轮重新读取同一页。
3. **不引入新调度框架**：复用既有 `@Scheduled(fixedDelay ...)`、既有 `AiCostOpsMetrics` 依赖信号与
   既有 `SecretShapes.redact` 脱敏。
4. **保持既有约束不变**：G-01 公平轮转（游标仅在整页处理完后推进）、G-02 组织级隔离、结算级有界
   持久重试、财务事务与账期行锁/状态约束全部不动（§8）。

## 4. 生产代码改动（最小）

| 文件 | 改动 |
| --- | --- |
| `gatewaysettlement/application/GatewaySettlementWorker.java` | 仅把 `runOnce()` 的初始 `selectOrganizationsWithWork(...)` 包入 `try/catch (DataAccessResourceFailureException)`：记 `dependencyError("DATABASE")`、写脱敏 WARN（区别于组织级文案）、`return 0`、游标不变。新增 `import java.util.List;`（用于 `final List<Long> organizations`）。 |

未改动：`GatewaySettlementMapper`（G-01 游标 SQL 不变）、`GatewaySettlementDiscoveryService`、
`GatewaySettlementService`、`AiCostOpsMetrics`、任何 Flyway 迁移、任何 API、`LICENSE`。未放宽断言、
未扩大超时、未删除测试。

## 5. 测试改动

### 5.1 新增 Mockito 单元测试（`GatewaySettlementWorkerInitialScanFailureTest`，3 项）

`backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementWorkerInitialScanFailureTest.java`
（纯 Mockito，无数据库；使用既有 Logback `ListAppender` 模式捕获 WARN）：

| 用例 | 冻结的契约 |
| --- | --- |
| `initialScanDatasourceFailureIsSignalledRedactedAndEndsTheRoundSafely` | 初始扫描抛 `DataAccessResourceFailureException`（消息含合成密钥形状标记）：`runOnce` 不抛出、返回 0；记 `dependencyError("DATABASE")`；**不**记组织级 `gatewaySettlementRetry`；**不**调用单组织 `discover`；WARN 文案含 `initial organization scan` 与 `[REDACTED]`，且不含合成标记 |
| `consecutiveInitialScanFailuresKeepTheCursorAndSignalEveryRound` | 连续两轮初始扫描失败：两轮均返回 0；两次均以相同游标（`afterOrgId=0`）查询；两次均记 `dependencyError("DATABASE")` |
| `roundTraversesNormallyOnceTheInitialScanRecovers` | 首轮扫描失败（返回 0），次轮恢复：正常遍历并结算该组织（`settle(11,7)`），依赖指标记 1 次 |

### 5.2 新增真实 MySQL 集成测试（`GatewaySettlementWorkerTraversalIntegrationTest`，+1 项 = 6 项）

`initialScanDatasourceFailureStopsTheRoundSafelyAndRecovers`（MySQL 8.4 Testcontainers）：
以包装 `GatewaySettlementMapper`（首次调用抛 `DataAccessResourceFailureException`，其后委托真实
`selectOrganizationsWithWork`）驱动**生产控制流**，2 个真实 PENDING 组织：

- `runOnce()` 返回 0、无组织被访问、记 `dependencyError("DATABASE")`；
- **不误判为无工作**：每个组织的持久 PENDING 结算行数仍为 1（工作未被消费/丢弃）；
- **游标未推进**：依赖恢复后同一 `worker` 下一轮遍历并结算**同样的**两个组织。

### 5.3 既有回归（未改动）

`GatewaySettlementWorkerTest`（9 项）、`AiCostOpsMetricsTest`（6 项）、G-01/G-02 的
`GatewaySettlementWorkerTraversalIntegrationTest` 既有 5 项，以及
`GatewaySettlementTransactionIntegrationTest`、`GatewaySettlementDiscoveryIntegrationTest`、
`GatewaySettlementSchemaIntegrationTest`、`PeriodCloseCoordinatorIntegrationTest`、
`GatewayFinancialWorkCloseIntegrationTest`、`GatewayFinancialResolutionIntegrationTest`、
`M15FinancialConcurrencyIntegrationTest` 全部保留并在修复后重跑（§6）。

## 6. 验证记录

环境：Windows，JDK 21，Docker（Testcontainers MySQL 8.4）。命令、结果与退出码如实记录；未放宽
断言、未扩大超时。

### 6.1 修复前（缺陷复现，基线 `9a539b0`）

```text
命令(backend): .\mvnw.cmd -B -q "-Dtest=GatewaySettlementWorkerInitialScanFailureTest" test
结果: Tests run: 3, Failures: 0, Errors: 3, Skipped: 0
退出码: 1
```

3 项错误均呈现同一缺口：异常从
`com.aicostops.gatewaysettlement.application.GatewaySettlementWorker.runOnce(GatewaySettlementWorker.java:88)`
抛出，直接穿透 `runOnce()`。

### 6.2 修复后：新增/既有单元测试（Mockito，无数据库）

```text
命令(backend): .\mvnw.cmd -B -q "-Dtest=GatewaySettlementWorkerTest,GatewaySettlementWorkerInitialScanFailureTest,AiCostOpsMetricsTest" test
结果: InitialScanFailure 3/3、Worker 9/9、Metrics 6/6，0 failures、0 errors
退出码: 0
```

运行日志可见新增的脱敏 WARN：
`Gateway Settlement round stopped, datasource unavailable during the initial organization scan:
org.springframework.dao.DataAccessResourceFailureException: connect failed: api_key=[REDACTED]`
（合成标记 `sk-synthetic-g03-marker-...` 未出现）。

### 6.3 修复后：真实 MySQL 集成测试

```text
命令(backend): .\mvnw.cmd -B -q test-compile "failsafe:integration-test"
              "-Dit.test=GatewaySettlementWorkerTraversalIntegrationTest"
              "-Dfailsafe.failIfNoSpecifiedTests=false"
结果: Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 (84.4 s)
退出码: 0
```

### 6.4 修复后：既有相关集成回归（真实 MySQL）

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

### 6.5 后端完整 `verify`

```text
命令(backend): .\mvnw.cmd -B verify
结果: BUILD SUCCESS
退出码: 0
总耗时: 36:24 min
日志: .local-dev/reviews/2026-10-15-gateway-settlement-initial-scan-fix/verify-1.log
```

| 阶段 | 结果（本运行实测汇总） |
| --- | --- |
| 后端 surefire（单元 + 架构，含本批新增 3 项单测与架构护栏） | 654 项，0 failures，0 errors，1 Windows 平台跳过 |
| 后端 failsafe（全量集成，含本批新增 1 项回归与全部既有 Gateway/财务/关账套件） | 1242 项，0 failures，0 errors，0 skipped |

本批仅改动 backend（一个生产文件 + 其测试），Gateway 与前端零改动，未执行根目录全链
`npm run verify`（见 §9）。

## 7. 证据分类

- **真实 MySQL（Testcontainers）**：§6.3（新增 G-03 集成用例及既有 G-01/G-02 用例）、§6.4（既有回归）。
- **仅 Mockito 单元（无数据库）**：§5.1 / §6.1（修复前复现）/ §6.2，冻结控制流分支、指标与脱敏 WARN。
- **架构护栏**：`GatewaySettlementArchitectureTest` / `ModuleDependencyArchitectureTest` 由完整
  `verify` 的 surefire 覆盖（§6.5）。本批未新增生产依赖（仅新增 `java.util.List` 与既有
  `SecretShapes`/`AiCostOpsMetrics` 使用）。
- **尚未充分验证**：见 §9。

## 8. 财务正确性 / 既有约束核对

保留不变：MySQL 为最终财务真相；Settlement 业务唯一性与幂等收敛；Settlement、Ledger、Budget Actual、
Commitment、Reservation 的原子财务事务；普通重试不双重入账；`FINAL`/`INCOMPLETE`/`UNKNOWN` 语义；
未知费用不视为零；`RECONCILIATION_REQUIRED` 人工边界；BillingPeriod 与 Close 的行锁与状态约束；
POSTED 账本不可变、修正仅追加；每组织严格隔离；日志不泄露密钥/原始请求/敏感账单。

本批改动仅触及 `runOnce()` 初始扫描的异常处理：不改变 G-01 游标 SQL/推进规则、不改变 G-02 组织级
隔离与结算级有界重试、不改变任何金额、状态机、幂等键或事务边界。

## 9. 未验证的风险与边界

- **仅覆盖 `DataAccessResourceFailureException`**：本批按任务范围处理该类（含
  `CannotGetJdbcConnectionException` 等子类）。若初始扫描抛出其它 `RuntimeException`（如非瞬态
  非资源类异常），仍会外泄；是否扩展属后续决策，本批未扩大范围。
- **真实生产级依赖故障模式未验证**：测试注入的是应用层异常；真实连接池耗尽/主从切换/长事务超时
  未验证。设计立场是“记依赖信号 + 安全结束本轮 + 下一轮调度重试”，退化为本轮延迟，不产生金额错误。
- **WARN 断言在单元层（Logback ListAppender）**：集成层断言的是可观测行为（指标、工作不丢失、
  游标不变与恢复遍历），未在集成层再断言日志文本。
- 未执行根目录全链 `npm run verify`（本批仅改动 backend；Gateway 与前端零改动），以后端完整
  `verify` 为准（§6.5）。
- 交接记录中的 Gateway 一次 403 间歇失败、共享 Redis 污染假设、真实 Provider/OpenCode 联调、
  独立浏览器组织隔离仍为未解决/DEFERRED，本批未触碰。
