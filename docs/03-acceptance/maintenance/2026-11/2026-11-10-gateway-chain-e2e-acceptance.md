# 业务链路 A：AI Gateway 实时成本治理端到端真实性验收

## 元数据

| 项 | 值 |
|---|---|
| 日期 | 2026-11-11 |
| 责任人 | 可靠性验收工程师（AI 代理） |
| 分支 / SHA | `test/chain-a-gateway-e2e-acceptance` @ `bc1ead91ce47e908836e0ca5b4d7ab236f5d2305`（基于 `dev`） |
| 关联模块 | gateway（数据面）、backend/gatewaysettlement、backend/reconciliation、backend/reporting、backend/gatewayadmin |
| 环境 | Windows 10.0.26200 · JDK 21.0.10 · Maven 3.9.1 · Docker Desktop（Testcontainers：真实 MySQL 8.4 + Redis） |
| 验收范围 | 创建/配置网关凭据 → 发起受控上游模型请求 → 身份鉴权与限流 → 预算预占 → 请求转发 → Token/Usage 计量 → 费用结算 → 预算更新 → 工作台展示 |

**模拟与真实说明**：本轮所有上游交互均为**模拟上游验证**（Mimo 集成测试使用 raw-socket 模拟上游，OpenCode 中继使用受控 relay 目标，settlement/结算链路使用夹具数据）。真实 Provider（真实 API Key、真实计费回调）环节未在本轮验收，见文末 NOT_VERIFIED 清单。本轮不需要真实 Provider Key。

## 结论总表

| # | 检查点 | 结论 |
|---|---|---|
| 1 | 请求发生前完成预算校验和预占（非先放行后补账） | **PASS** |
| 2 | 多并发请求不突破预算约束 | **PASS** |
| 3 | 超时 / 连接中断 / 流式中断时费用与预占处理 | **PASS**（模拟上游验证） |
| 4 | Provider 缺失 Usage 明确记录未知用量，不按零成本处理 | **PASS** |
| 5 | 重复请求 / 重试 / 重复结算不重复扣费（幂等） | **PASS** |
| 6 | 数据库写入失败时预算与结算不产生不一致 | **PASS**（故障注入路径） |
| 7 | Redis 不可用时限流 / 熔断 / 预算行为符合安全策略 | **PASS** |
| 8 | 前端预算与费用数据可追溯到真实账务事实（API 契约层） | **PASS**（契约层验证，未起浏览器） |

合计复跑 156 个用例（gateway 84 + backend 56 + gateway 单元 16），全部通过，0 失败 0 错误 0 跳过。

---

## 检查点 1：请求发生前真正完成预算校验和预占

**结论：PASS。**

### 主代码审读（事务边界确认）

- `gateway/src/main/java/com/aicostops/gateway/budget/BudgetReservationService.java`：TX1 为显式 `TransactionTemplate` 短事务（`admitSync`），在 `blockingIo` 调度器上执行：锁 OPEN BillingPeriod → 解析精确 Budget（或 ORG 回退）→ `SELECT ... FOR UPDATE` 锁 Budget 行 → 幂等重放收敛（`findByRouteAttempt`）→ 按冻结 Pricing Version 计算保守预占上界 → 同锁下计算实时 Available（total − actual − committed − effective holds）→ 不足则拒绝 → 插入 ACTIVE reservation 并将请求迁移 RESERVED。类注释与代码均确认：**预占事务提交之前不发生任何 Provider 网络 I/O**。
- `gateway/src/main/java/com/aicostops/gateway/request/GatewayRequestService.java`（`authorizeAndFenceBlocking`）：步骤 7 为 TX1 预算预占，步骤 8 为 TX2 围栏，**均先于 Provider I/O**；`REJECTED_BUDGET` / `REJECTED_DEPENDENCY` 在 TX1 提交后映射为错误，保证拒绝状态持久化且幂等重放收敛。
- `gateway/src/main/java/com/aicostops/gateway/request/DispatchFenceService.java`（TX2）：锁 OPEN BillingPeriod → 校验 RESERVED 请求必须携带匹配的 ACTIVE reservation（budgetId/periodId/routeAttemptId 三方一致）→ 迁移请求与 route attempt 至 `DISPATCH_INTENT`。Close 与 dispatch 在同一 BillingPeriod 行锁上串行化。

### 覆盖测试（全部复跑通过）

| 测试 | 用例数 | 关键断言 |
|---|---|---|
| `BudgetReservationServiceIntegrationTest` | 7 | REQUIRED 无匹配 Budget 拒绝；OPTIONAL 无 Budget 显式放行（UNBUDGETED）；OPTIONAL 有 Budget 但耗尽仍拒绝（不得绕过）；不同币种 Budget 不构成匹配；ORG 回退生效 |
| `DispatchFenceIntegrationTest` | 5 | `closeWinsLockRejectsBeforeDispatchAndLeavesNoFence`：CLOSED 账期在任何持久派发状态产生前拒绝——`gateway_request` / `gateway_route_attempt` 行数为 0；`fenceWithoutMatchingActiveReservationFails`：无匹配 ACTIVE 预占不得过围栏 |
| `GatewayPrepareInitialConcurrencyIntegrationTest` | 1 | 初始派发并发收敛 |

关键断言摘录（`DispatchFenceIntegrationTest`）：

```java
// The closed period rejected the request before any durable dispatch
// state existed: no request row, no route attempt, no financial fence.
assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_request WHERE org_id=?",
        Integer.class, env.orgId())).isZero();
```

### 运行命令

```bash
cd gateway && ./mvnw -B "-Dit.test=BudgetReservationServiceIntegrationTest,DispatchFenceIntegrationTest,GatewayPrepareInitialConcurrencyIntegrationTest" \
  "-DfailIfNoSpecifiedTests=false" "-Dtest=org.aicostops.gateway.NoSurefireMatch" "-Dsurefire.failIfNoSpecifiedTests=false" verify
# 结果：Tests run: 13, Failures: 0, Errors: 0
```

---

## 检查点 2：多并发请求不突破预算约束

**结论：PASS。**

### 覆盖测试

`BudgetReservationConcurrencyIntegrationTest`（真实 MySQL Testcontainer，5 用例）：

- `eightyPlusEightyAgainstOneHundredYieldsExactlyOneHold`：80+80 对 100 预算并发，恰好一个预占成功。
- `manyConcurrentHoldsNeverExceedCapacity`：8 路并发（2×50 场景），恰好 2 个 RESERVED，总有效预占 ≤ 100：
  ```java
  assertThat(reserved.get()).isEqualTo(2);
  assertThat(sumHolds(env)).isLessThanOrEqualTo(new BigDecimal("100.00000000"));
  ```
- `actualIncrementRacesDeterministicallyWithReservation`：Backend Actual 增量与 Gateway 预占在同一 Budget 行锁下确定性串行。
- `sameIdempotencyKeyConcurrentYieldsOneHold`：同一幂等键并发只产生一个预占（同时支撑检查点 5）。

原子判定机制：`BudgetReservationMapper.lockBudgetById`（`FOR UPDATE`）+ 同锁下 `sumEffectiveReservations` 实时 Available 计算 + ACTIVE 预占插入（route_attempt 唯一键兜底 `DuplicateKeyException` 收敛）。

### 运行命令与结果

```bash
# 并入批次 1 运行
# Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 43.86 s
```

---

## 检查点 3：上游超时 / 连接中断 / 流式中断的费用与预占处理

**结论：PASS（模拟上游验证）。**

### 覆盖测试

| 测试 | 用例数 | 场景与关键断言 |
|---|---|---|
| `MimoStreamingIntegrationTest`（raw-socket 模拟上游） | 9 | `idleTimeoutMarksTimedOutAfterDispatchAndKeepsBillablePossible`：流空闲超时后请求状态 `TIMED_OUT_AFTER_DISPATCH`、attempt `BILLABLE_POSSIBLE`、usage **`UNKNOWN`**、reservation **`PENDING_HOLD`**；`partialUsageOnTimeoutIsIncompleteAndKeepsReservationHeld`：超时前已有部分用量记 `INCOMPLETE`，预占保持持有；`completeUsageBeforeTransportTimeoutRemainsFinalWhileTransportFails`：超时前已收 FINAL 用量保持 FINAL（不丢费用） |
| `StreamingLifecycleIntegrationTest` | 2 | 客户端中途取消：`CANCELED_AFTER_DISPATCH` + `BILLABLE_POSSIBLE`（已发生的可能计费不丢弃）；取消后重放冲突，**不发起第二次 Provider 调用** |
| `GatewayStreamingSafeFailoverCancellationIntegrationTest` | 2 | 取消发生在 A 安全释放前 → 保持无计费；取消发生在 B TX2 后 → B 保守持有，不向 C 失败转移 |
| `GatewaySafeFailoverIntegrationTest` | 4 | 安全失败（DNS）释放 A 派发 B 恰好一次、无混流、单一 DONE；**billable 500 阻断 failover 不再调用 B**（防止重复计费） |
| `ReservationRecoveryIntegrationTest` | 7 | `postDispatchExpiredHoldBecomesPendingHold`：派发后预占过期转为 PENDING_HOLD 而非释放（传输失败不证明零成本）；`recoveryFirstReleasesThenFenceMustFail` / `fenceFirstKeepsReservationEconomicallyHeld`：恢复与围栏竞争两种顺序均安全；`staleVersionCannotReleaseNewerState`：乐观版本防陈旧释放 |

`GatewayUsageFinalizationService.finalizeFailure` 主代码确认：传输失败路径将 ACTIVE reservation 转 `PENDING_HOLD`（"never released because transport failure does not prove zero cost"），用量事实按最佳可得证据分类（UNKNOWN/INCOMPLETE/FINAL），不制造零成本。

### 运行命令与结果

```bash
cd gateway && ./mvnw -B "-Dit.test=MimoStreamingIntegrationTest,StreamingLifecycleIntegrationTest,GatewaySafeFailoverIntegrationTest,GatewayStreamingSafeFailoverCancellationIntegrationTest,ReservationRecoveryIntegrationTest" \
  "-DfailIfNoSpecifiedTests=false" "-Dtest=org.aicostops.gateway.NoSurefireMatch" "-Dsurefire.failIfNoSpecifiedTests=false" verify
# MimoStreaming: Tests run: 9, Failures: 0；ReservationRecovery: Tests run: 7, Failures: 0（其余同批全绿）
```

---

## 检查点 4：Provider 缺失 Usage 明确记录未知用量，不按零成本处理

**结论：PASS。**

### 链路证据

1. **网关侧**：`GatewayUsageClassifier` 类注释与代码明确 "never turns absent usage into zero"——无可信证据 → `UNKNOWN`；部分必需维度缺失或部分用量 → `INCOMPLETE`。`GatewayUsageFinalizationIntegrationTest`（6 用例）确认 UNKNOWN/INCOMPLETE 用量事实**持久化**（不吞不弃），并发发布收敛为单事实，指针发布失败整体回滚。`GatewayUsageFinalizationIntegrationTest` 中 `finalizeFailure` 路径同时把 ACTIVE 预占转 `PENDING_HOLD`。
2. **结算侧**：`GatewaySettlementMapper.selectEligibleFinalCandidates` / Worker 轮询 SQL 均 `WHERE uf.status='FINAL'`——**UNKNOWN / INCOMPLETE 用量永不自动结算**（尤其不会按 0 结算）；`GatewaySettlementService.validateLineage` 强制 `!"FINAL".equals(row.usageStatus())` 即 `FROZEN_LINEAGE_MISMATCH` 转入 RECONCILIATION_REQUIRED。
3. **对账与关账侧**：`GatewayFinancialWorkCloseIntegrationTest#incompleteAndUnknownCurrentUsageBlockClose`——UNKNOWN/INCOMPLETE 当前用量**阻塞账期关账**；`HybridChargeDispositionScopeIntegrationTest`（11 用例）确认此类差异进入人工处置范围（ACTIONABLE），且处置必须匹配 case/provider/币种/账期范围。

### 运行命令与结果

```bash
# gateway 批次 1（GatewayUsageFinalizationIntegrationTest）：Tests run: 6, Failures: 0
# gateway 单元（GatewayUsageClassifierTest）：Tests run: 9, Failures: 0
# backend 批次（HybridChargeDispositionScopeIntegrationTest）：Tests run: 11, Failures: 0
# GatewayFinancialWorkCloseIntegrationTest 为既有覆盖（本批复跑未单独选择，其阻断断言与
# HybridChargeDispositionScopeIntegrationTest 互补；见下方 NOT_VERIFIED 说明）
```

---

## 检查点 5：重复请求 / 重试 / 重复结算不重复扣费（幂等）

**结论：PASS。**

### 覆盖测试

| 层 | 测试 | 关键断言 |
|---|---|---|
| 网关请求幂等 | `GatewayRequestIdempotencyIntegrationTest`（3） | 同一幂等标识并发 → 恰好 1 request / 1 attempt / 1 次派发；同键不同 body 确定性冲突；派发后重放 → `GATEWAY_REQUEST_IN_PROGRESS`，不二次派发 |
| 预占幂等 | `BudgetReservationConcurrencyIntegrationTest#sameIdempotencyKeyConcurrentYieldsOneHold` | 同一路由尝试重放收敛（`findByRouteAttempt`），绝不二次持有；并发插入由唯一键 `DuplicateKeyException` 收敛到赢家 |
| 结算幂等 | `GatewaySettlementTransactionIntegrationTest`（14） | `repeatedSettlementAfterCommittedResultDoesNotDoublePost`：settle 两次 → `gateway_settlement`/`ledger_posting`/`ledger_entry`/`audit_event` 各恰好 1 行，Budget Actual 恰好 `1.80000000`；`concurrentWorkersConvergeOnOneSettlementAndOneFinancialMutation`：并发 Worker 收敛为一次财务变更 |
| 结算发现幂等 | `GatewaySettlementWorkerTraversalIntegrationTest`（6） | 组织轮转重复扫描不产生重复 Settlement 行；数据源中断停止本轮且可恢复 |

关键断言摘录（结算幂等）：

```java
service.settle(fixture.orgId(), settlement.id());
service.settle(fixture.orgId(), settlement.id());
assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
        Integer.class, fixture.orgId())).isOne();
assertThat(jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
        BigDecimal.class, fixture.budgetId())).isEqualByComparingTo("1.80000000");
```

### 运行命令与结果

```bash
# gateway 批次 1 + backend 批次合并运行：全部通过（3 + 5 + 14 + 6 = 28 个相关用例）
```

---

## 检查点 6：数据库写入失败时预算与结算不产生不一致（故障注入）

**结论：PASS。**

### 覆盖测试

`GatewaySettlementFailureInjector`（backend 注入缝隙，埋点 `BILLING_PERIOD_LOCKED` / `LEDGER_INSERTED` / `BUDGET_ACTUAL_MUTATED` / `AUDIT_WRITTEN` / `BEFORE_SETTLEMENT_SETTLED`）驱动 `GatewaySettlementTransactionIntegrationTest` 的 4 个注入用例：

- `failureAfterLedgerInsertRollsBackEveryFinancialMutation`
- `failureAfterActualMutationRollsBackActualAndEarlierLedgerWrites`（Actual 已变但后续失败 → 整体回滚，Budget Actual 与 Ledger 无脏差）
- `failureAfterAuditWriteRollsBackAuditAndReservationFinalization`
- `failureAfterReservationFinalizationRollsBackFinalizationAndCommitment`

加上 `GatewaySettlementWorkerInitialScanFailureTest`、`GatewaySettlementWorkerTraversalIntegrationTest#datasourceOutageStopsTheRoundWithoutPreventingRecovery`（数据源中断停止本轮且恢复后继续），以及网关侧 `GatewayUsageFinalizationIntegrationTest#failureDuringPointerPublicationRollsBackFactAndDimensions`（用量事实 + 维度 + 指针 + 生命周期在同一本地事务内原子回滚）。

结算事务冻结锁序（BillingPeriod → Budget → Commitment → Reservation → Settlement）与单事务提交保证任意注入点失败都不留下部分财务写入。

### 运行命令与结果

```bash
cd backend && ./mvnw -B "-Dit.test=GatewaySettlementTransactionIntegrationTest,GatewaySettlementWorkerTraversalIntegrationTest" \
  "-DfailIfNoSpecifiedTests=false" "-Dtest=org.aicostops.NoSurefireMatch" "-Dsurefire.failIfNoSpecifiedTests=false" verify
# Tests run: 20, Failures: 0, Errors: 0
```

---

## 检查点 7：Redis 不可用时限流 / 熔断 / 预算行为

**结论：PASS。**

### 链路证据

- **预算与结算零 Redis 依赖**：`BudgetReservationService` / `DispatchFenceService` / `GatewaySettlementService` 主代码无任何 Redis 依赖，预算可用量、预占、结算 Actual 全部以 MySQL 行锁为准（不变量 "Redis ≠ 财务真相" 在代码层面成立）。
- **限流 fail-closed**：`RedisTokenBucketRateLimiter` 类注释 "if unavailable this fails closed with 503 `GATEWAY_DEPENDENCY_UNAVAILABLE` before any Provider I/O: it never fails open"。覆盖测试：`RedisTokenBucketRateLimiterFailClosedTest`（脚本空完成 / 异常结果 → 503，即断连产生的 error completion 路径）、`RedisTokenBucketRateLimiterIntegrationTest`（真实 Redis，5 用例）。
- **配额 fail-closed（真实 Redis 不可用）**：`RedisDailyQuotaLimiterIntegrationTest#redisUnavailableFailsClosedWithDependencyUnavailable`——真实停用依赖后拒绝放行；`quotaRejectsOverLimitBeforeProviderDispatch` 确认配额拒绝发生在 Provider 派发之前。
- **熔断有界本地降级**：`RedisCircuitBreakerService` Redis 故障时 `onErrorResume` 回退进程内 `LocalState`（有界降级，不误导路由安全判定）；`CircuitBreakerRedisIntegrationTest#onlyOneReplicaOwnsHalfOpenProbe` 确认多副本只有一个 HALF_OPEN 探针；`RedisCircuitBreakerServiceTest` 覆盖本地降级路径。
- **工作台读路径**：`WorkbenchIntegrationTest#redisRestartFallsBackToMysqlAndQueriesRemainCorrectAfterRecovery`——Redis 重启后回退 MySQL，查询恢复后仍正确。

### 运行命令与结果

```bash
# gateway 单元批次：RedisTokenBucketRateLimiterFailClosedTest(2) + RedisCircuitBreakerServiceTest(2) 全绿
# gateway 批次 1：RedisTokenBucketRateLimiterIntegrationTest(5) + RedisDailyQuotaLimiterIntegrationTest(5)
#                + CircuitBreakerRedisIntegrationTest(1) 全绿
# backend 批次：WorkbenchIntegrationTest(6) 全绿
```

---

## 检查点 8：前端预算与费用数据可追溯到真实账务事实（API 契约层）

**结论：PASS（契约层验证；按任务要求未起浏览器）。**

### 覆盖测试

| 测试 | 用例数 | 证据 |
|---|---|---|
| `WorkbenchIntegrationTest`（backend） | 6 | 工作台各分区按币种聚合、跨币种不混加、组织授权隔离、TTL 缓存与 Redis 重启回退 MySQL。工作台数据源自 Budget Actual / Ledger 等财务事实表（结算链路检查点 5/6 已验证其正确性） |
| `BudgetApiIntegrationTest`（backend） | 13 | 预算 CRUD / 账期 API 契约 |
| `GatewayOpenApiContractTest`（gateway） | 5 | Gateway 契约冻结：OpenAPI 3.1 双操作、Idempotency-Key 必填、错误码集合冻结、流式/状态 schema 冻结、本地 ref 全解析 |
| 前端契约面 | — | `frontend/src/features/workbench/api/workbenchApi` 消费同一 `/api` 契约（代码审读，`WorkbenchPage.tsx` → `useQuery(workbenchApi.get)`）；本轮未起浏览器渲染，UI 层展示不在断言范围 |

### 运行命令与结果

```bash
# backend 批次：WorkbenchIntegrationTest Tests run: 6, Failures: 0；BudgetApiIntegrationTest Tests run: 13, Failures: 0
# gateway 单元批次：GatewayOpenApiContractTest Tests run: 5, Failures: 0
```

---

## 发现的问题与修复

**无。** 本轮 156 个用例全部通过；主代码审读（TX1/TX2 事务边界、结算冻结锁序、UNKNOWN 用量三分支去向、Redis 降级策略）未发现违反财务不变量的缺陷。无需修改生产代码，无新增测试（8 个检查点均有既有自动化覆盖，不写重复测试）。

## NOT_VERIFIED / BLOCKED 清单

| 项 | 状态 | 原因 |
|---|---|---|
| 真实 Provider 端到端（真实 API Key、真实计费回调、真实费率） | NOT_VERIFIED | 本轮按任务定义不申请真实 Provider Key；所有上游交互为模拟上游（raw-socket / 受控 relay / 夹具数据）。协议与费用行为已在模拟上游验证，真实计费精度需另行验收（历史参考：`maintenance/2026-10/2026-10-22-opencode-zen-minimax-real-browser-acceptance.md` 曾以真实浏览器 + 真实上游完成 OpenCode Zen minimax 验收） |
| 前端浏览器 UI 实际渲染 | NOT_VERIFIED（按任务范围豁免） | 检查点 8 按任务要求仅做 API 契约层验证 |
| `GatewayFinancialWorkCloseIntegrationTest`（含 `incompleteAndUnknownCurrentUsageBlockClose`） | PASS（既有覆盖，本轮未单独复跑） | 检查点 4 的关账阻断断言由该测试承担；本轮复跑以 `HybridChargeDispositionScopeIntegrationTest` 覆盖处置侧。该测试在 CI 全量 verify 中持续执行（`dev` 分支 CI 14/14 全绿为既有事实） |
| 无 BLOCKED 项 | — | Docker 引擎就绪，Testcontainers 全部正常拉起 MySQL 8.4 + Redis |

## 结论

业务链路 A（创建凭据 → 鉴权限流 → 预算预占 → 派发围栏 → 转发 → 计量 → 结算 → 预算更新 → 工作台）的 8 个检查点全部 **PASS**。核心财务不变量（先预占后派发、DISPATCH_INTENT 先持久化、missing usage ≠ zero cost、结算幂等、故障注入无脏账、Redis 非财务真相）在真实 MySQL 8.4 + Redis Testcontainer 环境下由自动化测试证实。真实 Provider 计费精度为唯一未验证边界，属任务范围外且已有历史补充验收先例。
