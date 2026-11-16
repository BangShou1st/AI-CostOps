# Gateway 流式竞态、账期共享锁与状态接口维护修复

## 元数据

- **日期**：2026-11-16
- **责任人 / AI**：Codex
- **关联模块**：`gateway`、`backend/budget`、CI
- **关联缺陷 / PR**：流式 `Sinks` 并发发射、Backend 账期排他锁、Status API 恒空、Gateway 测试迁移 V27 漂移、browser-e2e 未启动 Gateway
- **分支**：`fix/maintenance-streaming-lock-status`

## 变更概述

1. `BridgeOpenAiCompatibleChatAdapter` 的流式事件此前由 Provider 回调线程、
   completion 线程和 watchdog 线程并发写入 unicast `Sinks.Many`。Reactor `Sinks`
   在多线程竞争时返回 `FAIL_NON_SERIALIZED`，旧逻辑把它映射成 `StreamCanceled`，
   从而把正常流或超时误判为客户端取消。现在所有 sink 写入经过单锁串行化，
   `FAIL_NON_SERIALIZED` 会短暂退避后重放；complete/error 使用终态 CAS，
   watchdog 超时立即释放自身与上游 handle，不再重复发终态。
2. `BillingPeriodFinancialWriteFenceService` 及 `CommitmentConsumeService` 的账期
   读取改为 `FOR SHARE`。并发 Gateway 预占、Backend 结算与账本记账可以共享同一
   OPEN 账期；Close/Reopen 仍由 `FOR UPDATE` 独占锁排空在途财务事务。Expense /
   ProviderCharge 记账事务同步改为 `READ COMMITTED`，避免等待预算行锁前建立的
   REPEATABLE READ 快照漏读并发赢家已提交的 `ledger_posting`。
3. `GET /v1/gateway/requests/{requestId}` 现在 join 当前 `gateway_usage_fact` 与
   `gateway_settlement`，返回真实 `meteringStatus` / `settlementStatus`；不存在对应
   阶段时继续返回 null，隐私 404 与不暴露 prompt/secret/finance detail 的边界不变。
4. Gateway 测试容器不再手写 V1–V27 迁移清单，而是按 Flyway 版本号动态发现
   Backend 全部 `V*.sql`；新增 `GatewayMigrationCoverageTest` 防止未来再次漂移。
5. CI `browser-e2e` 使用 `compose.yaml + compose.v3-operational.yaml` 启动
   Gateway 与 mock provider，并增加 Gateway liveness 检查。

## 验证环境与执行命令

环境：Windows 11、PowerShell、Java 21.0.10、Docker Desktop、MySQL 8.4 /
Redis 8.8.1 Testcontainers。

```powershell
cd gateway
.\mvnw.cmd -B "-Dtest=BridgeOpenAiCompatibleChatAdapterTest,GatewayMigrationCoverageTest" test
.\mvnw.cmd -B "-Dit.test=GatewayRequestStatusControllerTest" "-DfailIfNoSpecifiedTests=false" verify

cd ..\backend
.\mvnw.cmd -B "-Dit.test=BillingPeriodFinancialWriteFenceIntegrationTest,GatewayPeriodSharedLockCloseIntegrationTest" "-DfailIfNoSpecifiedTests=false" verify

cd ..
docker compose -f compose.yaml -f compose.v3-operational.yaml config --quiet
```

## 关键断言与证据

- Gateway 单元：`BridgeOpenAiCompatibleChatAdapterTest` 新增
  `nonSerializedEmitIsRetriedInsteadOfBeingTreatedAsClientCancel` 与
  `streamEmitterPublishesOnlyOneTerminalSignal`；15 个定向单测全部通过。
- Gateway 集成：`GatewayRequestStatusControllerTest` 5/5 通过，成功请求返回
  `meteringStatus=FINAL`；手工 seed 的 Settlement 返回 `settlementStatus=PENDING`；
  外部 credential 与不存在 request 的隐私 404 保持通过。
- Gateway 全量 `verify`：163 个单元/架构测试 + 99 个集成测试全部通过。
- Backend：`BillingPeriodFinancialWriteFenceIntegrationTest` 6/6 通过，其中新增测试在
  两个并发事务中同时持有同一 OPEN 账期的共享锁；`GatewayPeriodSharedLockCloseIntegrationTest`
  2/2 通过，Close 仍等待共享持有者并进入 CLOSING。
- 2026-11-17 PR CI 发现共享锁下的并发回归：
  `ExpensePostingIntegrationTest.concurrentPostingReturnsOneResultAndIncrementsBudgetOnlyOnce`
  因旧快照误判费用已非 APPROVED。两个 Ledger 记账服务改为 `READ COMMITTED` 后，
  `ExpensePostingIntegrationTest` 10/10、Provider 并发/记账与账期围栏定向测试 15/15
  通过。
- Gateway 容器测试实际执行到 V33，日志包含
  `Successfully applied 33 migrations ... now at version v33`。
- Compose 模型解析通过；CI browser-e2e 的 Gateway liveness 步骤待 GitHub Actions 实际执行。

## 结论与影响边界

**结论：本地定向回归 PASS；PR #212 的 CI / Security 作为最终门禁。**

本修复不改变 Gateway API schema、预算金额计算、结算/账本写入语义或前端契约；
只修复并发射终态、账期锁粒度、状态读取和测试/部署覆盖。连接期 socket IP
pinning 仍属于 `ai-model-bridge` transport 的后续安全收口，不在本记录宣称完成。
