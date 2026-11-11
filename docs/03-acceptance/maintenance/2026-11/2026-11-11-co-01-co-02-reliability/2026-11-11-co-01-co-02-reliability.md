# CO-01 / CO-02 核心链路可靠性补强验收（2026-11-11）

## 1. 元数据

| 项 | 值 |
|---|---|
| 日期 | 2026-11-11 |
| 责任人 / AI | BangShou1st / Claude Code |
| 关联模块 | 账单导入 / 账本 / 关账（CO-01）；Gateway 预算 / 幂等 / 未知用量（CO-02） |
| 关联计划 | `2026-11-10-backend-reliability-refactor-design.md` §4、§5、§9 CO-P1/P2 |
| 基线 | `main = dev = 57696e2` |
| 前置证据 | [核心业务一致性验收](../2026-11-10-core-business-consistency-acceptance.md)、[链路 A](../2026-11-10-gateway-chain-e2e-acceptance.md)、[链路 B](../2026-11-10-billing-chain-e2e-acceptance.md) |
| 结论 | **PASS（附 PARTIAL）**——未发现新的财务不变量破坏；补齐两个 P1 级恢复/故障测试与一个 P2 级恢复扫描扩展 |

## 2. CO-01：账单导入 / 账本 / 关账

### 2.1 已核对（既有实现，未改财务语义）

- 上传证据：`EvidencePersistenceService` 以 `org + sha256` 复用 Evidence 身份；对象存储 PUT 在 DB 短事务之外；`STAGING -> AVAILABLE` 可修复。
- Worker：`ImportAttemptMapper.claimNextQueued` 使用 `FOR UPDATE SKIP LOCKED` + DB 时钟租约 + `leaseVersion` fencing；过期租约创建后继 attempt，不复活旧 attempt。
- 幂等：`file_hash`（Evidence 身份）、行指纹（duplicate review）、`posting_key`（Ledger）三层各司其职。
- 关账：8 项 `CloseBlockerCode` + 组织/账期行锁 + CLOSED 写栅栏；Posting 只追加，Correction 走追加更正。

### 2.2 本轮补强

| 项 | 类型 | 说明 | 验证 |
|---|---|---|---|
| 对象存储读取失败（worker 执行期） | 测试 | 新增 `ImportAttemptExecutorIntegrationTest.unreadableEvidenceObjectFailsAttemptWithoutCanonicalOrLedgerRows`：`ObjectStoragePort.open` 抛错时 attempt/batch FAILED、`raw_provider_record` 0 行，不生成虚假成本 | 定向集成测试 |
| 导入失败告警死链 | 代码 + 测试 | `ImportAttemptExecutor.fail` 在最终化成功后发 `import.completed{result=FAILED}`；失败用例断言 counter +1 | `ImportAttemptExecutorIntegrationTest` |
| 关账异常消息 | 代码 | `PeriodCloseService.validateAllBlockers` 从固定“seven”改为按 `CloseBlockerCode.values().length` 生成 | 单测/编译 |
| 跨文件重复扫描未强制 | **PARTIAL（已知操作风险）** | 重复扫描仍为显式 API（`POST /api/v1/duplicate-candidates/scan`）；关账 blocker 只统计已落库 candidate。**未在关账时自动跑 O(n²) 扫描**（避免无索引证据的阻塞查询）；操作要求：关账前必须执行扫描并处置 OPEN candidate，已写入 Runbook/本证据的限制项 | 见 §4 |

### 2.3 未纳入本轮的测试深度项

- 双线程真实并发 claim（现有为顺序 claim + 不同任务的并发用例）。
- 全新 charge 写入已 CLOSED 账期的独立用例（现有覆盖同一 posting 回放与 CLOSING 竞争）。
- 两个 `close()` 真并发、Confirm-vs-Close 服务层竞争。
- 恢复后 attempt#1 charge 不可入账的 ledger posting 级用例（现有 `posting_key` 唯一键与 `confirmed_import` gate 已实现，缺专门测试）。

以上均为测试深度项，不是已证实的生产缺陷；不改变本轮 PASS 结论。

## 3. CO-02：Gateway 预算 / 幂等 / 未知用量

### 3.1 本轮修复

| 项 | 类型 | 说明 | 验证 |
|---|---|---|---|
| 预占前孤儿请求恢复 | 代码 + 测试 | `VALIDATED/RESERVED/DISPATCH_INTENT/UPSTREAM_ACTIVE` 下，所有 attempt ∈ {`PLANNED`,`SAFE_NO_BILLABLE_EXECUTION`}、至少一个 `PLANNED`、无有效预占、年龄 ≥ `reservation-ttl` 的请求，收敛为 `FAILED_PRE_DISPATCH`；不触碰资金/Provider I/O；新鲜请求与已派发 attempt 不误伤 | `ReservationRecoveryIntegrationTest` 12/12（新增 4 例） |
| 依赖不可用错误映射 | 代码 + 测试 | 全部候选 `REJECTED_DEPENDENCY`（预算上界不可安全计算）时返回 **503 `GATEWAY_DEPENDENCY_UNAVAILABLE`**，不再落入 403；request 置 `FAILED_PRE_DISPATCH` | `GatewayPrepareInitialConcurrencyIntegrationTest` 3/3 |
| 50 并发同键 | 测试 | 同一幂等键 50 个并发 contender：1 request / 1 attempt，其余 409，无 FORBIDDEN | 同上 |
| `reservation_attempt` 指标 | 代码 | 两次 `admitSync` 后按 outcome 计数 | 编译 + Gateway 指标测试 |

### 3.2 既有证据（本轮未重跑）

- 预算预占先于 Provider I/O、MySQL 条件更新、`DISPATCH_INTENT` 先持久化：链路 A 检查点 1/2/6。
- missing usage ≠ zero：`GatewayUsageClassifier` 只产生 UNKNOWN/INCOMPLETE，结算只取 FINAL：链路 A 检查点 4。
- 重复 settle / 双回调 / 断流：链路 A 检查点 3/5。

### 3.3 已记录的限制

- 同键 50/100 并发已补 50；100 contender 需更高 CI 资源，未跑。
- 跨 Gateway 实例竞争仍为“单 JVM 多连接”；正确性由 MySQL Budget 行锁 + 唯一键保障，真实双进程/双实例标 `NOT_VERIFIED`。
- 预算刚好等于可用额、非 USD/不同 scale、UNKNOWN 结算发现、PENDING_HOLD→Settlement、超额审计 metadata 的专项用例未在本轮补齐（计划书 §5.2/§5.4 的建议深度项）。
- TX1/TX2 数据库异常故障注入未新增专用 injector；现有 fail-closed 代码路径与链路 A 的 DB 写失败注入覆盖。

## 4. 验证命令与结果

```bash
cd gateway && ./mvnw -B verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ReservationRecoveryIntegrationTest,GatewayPrepareInitialConcurrencyIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=false
# 结果：15 tests, 0 failures, 0 errors, BUILD SUCCESS

cd backend && ./mvnw -B verify -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=ImportAttemptExecutorIntegrationTest,EvidenceStorageServiceIntegrationTest \
  -Dfailsafe.failIfNoSpecifiedTests=false
# 结果：ImportAttemptExecutorIntegrationTest 20/20、EvidenceStorageServiceIntegrationTest 12/12，BUILD SUCCESS
```

补充定向结果：`ProblemDetailAdviceTest`（traceId）7/7、`WorkbenchIntegrationTest`（缓存权限指纹）7/7 均通过；Gateway 侧 `RedisTokenBucketRateLimiterTimeoutTest` 1/1。

## 5. 结论

CO-01/CO-02 的核心财务不变量没有新缺陷：预算仍由 MySQL 条件更新裁决、未知用量不按零计费、Posting 只追加、关账写栅栏覆盖全部入口。本轮把“预占前孤儿恢复”“依赖不可用 503”“对象不可读失败”“导入失败告警”从代码存在提升为有测试的行为；未实现的测试深度项和跨实例/关账强制扫描已明确标为 `PARTIAL` / `NOT_VERIFIED`，未用未经验证的结论冒充完成。
