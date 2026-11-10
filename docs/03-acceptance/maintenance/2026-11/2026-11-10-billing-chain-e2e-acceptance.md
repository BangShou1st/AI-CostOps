# 2026-11-10 业务链路 B：外部账单归集与财务对账 端到端真实性验收

## 元数据

| 项目 | 值 |
|---|---|
| 日期 | 2026-11-11（验收执行日，任务命名 2026-11-10） |
| 分支 | `test/chain-b-billing-e2e-acceptance`（基于 `dev`，独立 worktree `AI-CostOps-wt-chainB`） |
| 基线 SHA | `bc1ead91ce47e908836e0ca5b4d7ab236f5d2305` |
| 责任人 | AI 可靠性验收代理（Chain B） |
| 环境 | Windows 10 / Git Bash；JDK 21；Docker Desktop（WSL2）；Testcontainers `mysql:8.4`；Redis 容器（Ledger 测试夹具） |
| 方法 | 先盘点既有测试覆盖，再定向复跑（不跑全量 verify）；一次性 MySQL 8.4 容器应用全部 Flyway 迁移做数据库层核对；前端 API 契约层测试验证 |
| 结论 | **8/8 检查点 PASS，无 P0/P1 业务正确性缺陷** |

真实调用链（盘点结论）：

```text
上传账单文件 → EvidenceStorageService → EvidencePersistenceService.reserveOrReuse
  （uq_evidence_org_sha256 唯一键 + DuplicateKeyException→复用，@Transactional 短事务）
→ ImportBatch（uq_import_batch_identity: evidence_id+provider_account_id+source_type+parser_version）
→ ImportAttempt（DB 任务队列，QUEUED）
→ ImportLeaseService.claimNext（FOR UPDATE SKIP LOCKED 短事务抢占，租约=DB 时钟+leaseVersion fencing）
→ ImportAttemptExecutor（inspect→parse→ImportRawPersistenceService 有界分批持久化；
   每批一个短事务，先 lockAndVerifyOwnership(attemptId,workerId,leaseVersion) 再写 raw+canonical）
→ CanonicalCostWritePort（uq_charge_fact_raw_fact: raw_record_id+fact_index）
→ DuplicateFingerprint / duplicate_candidate（记录指纹去重 + duplicate review 语义）
→ 确认（ImportWorkflowCommandService.confirm，org 级锁+账期 admission）
→ 费用归属 allocation_decision/allocation_line
→ 财务入账 ProviderChargePostingService（posting_key=CHARGE:{chargeFactId}:ALLOCATION:{decisionId}，
   uq_ledger_posting_org_key；锁序 period→budgets→commitments→source→decision→lines）
→ 对账 ReconciliationRunService/ReconciliationCase（provider_account+currency 粒度）
→ 差异处理 ReconciliationAdjustmentService / GatewayFinancialResolutionService / LedgerCorrectionService
   （Correction 只追加，CorrectionGroup correction_key 幂等）
→ 关账 PeriodCloseService（8 项 CloseBlocker + BillingPeriodFinancialWriteFence 财务写栅栏）
→ 报表 /workbench、/ledger、/reconciliation（openapi.yaml 契约 + 前端 feature API 层）
```

三层幂等键语义（数据库层确认，见各检查点）：

| 层 | 语义 | 落点 |
|---|---|---|
| file_hash | 同一组织同一文件字节 = 同一 Evidence 身份 | `evidence.uq_evidence_org_sha256` |
| 记录指纹 | 同一业务记录跨文件/跨 Attempt 的去重与人工复核 | `duplicate_candidate.fingerprint CHAR(64)` + `uq_charge_fact_raw_fact` |
| posting_key | 同一 Charge+Allocation 决策只入账一次 | `ledger_posting.uq_ledger_posting_org_key` |

---

## 检查点 1：同一账单文件重复上传/重新处理不重复计费（file_hash 层幂等）

**结论：PASS**

覆盖测试（全部复跑通过）：

| 测试类 | 用例 | 关键断言 |
|---|---|---|
| `evidence/application/EvidenceStorageServiceIntegrationTest`（10） | `sameOrganizationAndSha256ReusesTheSameEvidenceIdentity` / `sameBytesInSameOrganizationReuseEvidenceAndSkipObjectRewrite` / `sameSha256InDifferentOrganizationsKeepsTenantIsolation` | 同组织同 sha256 复用同一 Evidence 身份且跳过对象重写；跨组织隔离 |
| `M2EvidenceImportSchemaIntegrationTest` | 约束断言 | `uq_evidence_org_sha256`、`uq_import_batch_identity` 存在 |
| `ingestion/application/ImportWorkflowIdempotencyIntegrationTest`（7） | `sameKeySameHashAfterCompletedResultReplaysStoredResponse` / `concurrentSameKeyConvergesOnOneCommittedResult` | 命令幂等键并发收敛到一个已提交结果 |
| `ingestion/application/ImportWorkflowConcurrencyIntegrationTest`（9） | cancel/finalize/retry/recovery 矩阵 | 只有可串行化的合法状态组合 |
| `ingestion/application/ImportWorkflowRetryIntegrationTest` | 重试语义 | 仅 FAILED/CANCELED 可重试，重试生成新 Attempt（MANUAL_RETRY），不改已确认外部事实 |
| `ledger/ProviderChargePostingIntegrationTest` | `postsEntriesBudgetsCommitmentUsageAndReplaysStableKey` / `committedPostingReplaysAfterPeriodClosesWithoutMutation` | `posting_key = CHARGE:{chargeFactId}:ALLOCATION:{decisionId}` 回放同一 posting，重复调用零新增行、零重复审计 |

关键断言摘录（`ProviderChargePostingIntegrationTest`）：

```java
var replay = postings.post(actor, chargeId, new PostSourceCommand(java.util.List.of()));
assertThat(replay.posting().id()).isEqualTo(first.posting().id());
assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_posting WHERE org_id=?",
        Integer.class, orgId)).isEqualTo(1);
assertThat(auditCount("LEDGER_CHARGE_POSTED")).isEqualTo(1);
```

代码防线（`EvidencePersistenceService.reserveOrReuse`）：先按 `(org_id, sha256)` 查既有身份；并发插入撞唯一键时捕获 `DuplicateKeyException` 并转为幂等复用，不产生第二个 Evidence。

运行命令：

```bash
cd backend && ./mvnw -B "-Dit.test=EvidenceStorageServiceIntegrationTest,M2EvidenceImportSchemaIntegrationTest,ImportWorkflowIdempotencyIntegrationTest,ImportWorkflowRetryIntegrationTest,ProviderChargePostingIntegrationTest,ImportWorkflowConcurrencyIntegrationTest" "-DfailIfNoSpecifiedTests=false" test-compile failsafe:integration-test failsafe:verify
```

数据库核对（一次性 MySQL 8.4 应用全部 Flyway 迁移后查询）：

```sql
mysql> SELECT CONSTRAINT_NAME, TABLE_NAME FROM information_schema.TABLE_CONSTRAINTS
       WHERE CONSTRAINT_SCHEMA='aicostops_verify'
         AND CONSTRAINT_NAME IN ('uq_evidence_org_sha256','uq_import_batch_identity',
             'uq_ledger_posting_org_key');
+------------------------+---------------+
| CONSTRAINT_NAME        | TABLE_NAME    |
+------------------------+---------------+
| uq_evidence_org_sha256 | evidence      |
| uq_import_batch_identity | import_batch |
| uq_ledger_posting_org_key | ledger_posting |
+------------------------+---------------+
```

## 检查点 2：同一业务记录跨文件导入的去重策略（记录指纹 / duplicate review）

**结论：PASS**

覆盖测试（全部复跑通过）：

| 测试类 | 用例要点 |
|---|---|
| `cost/review/DuplicateFingerprintTest`（4，单元） | 同证据同指纹；对次序无关；金额/币种/时间窗任一不同则指纹不同；指纹为 64 位小写 hex |
| `cost/review/DuplicateCandidateGenerationIntegrationTest`（12） | 精确重复生成一个 OPEN candidate 并把双方标 `SUSPECTED_DUPLICATE`；半开窗口重叠生成 overlap candidate；相邻/空窗口不生成；负数与零金额相等也成 candidate；**未 CONFIRMED 的 batch/attempt 不参与扫描**；跨组织隔离；重扫不产生重复行；死锁重试后只统计已提交 batch |
| `cost/review/DuplicateReviewCommandIntegrationTest` | `keep` 标 `KEPT_CLEAN` 并恢复双方 `CLEAN`；存在其他 OPEN candidate 时端点保持 `SUSPECTED`；幂等键回放不产生第二次变更与第二次审计；`exclude` 标 `CONFIRMED_DUPLICATE`/`EXCLUDED_DUPLICATE` |
| `cost/review/DuplicateCandidatePersistenceIntegrationTest` | candidate 持久化与唯一键 `(org_id, charge_fact_id, matched_charge_id, algorithm_version)` |
| `M3Group2DuplicateAttributionSchemaIntegrationTest` | V9 schema 断言 |

关键断言摘录（`DuplicateReviewCommandIntegrationTest`）：

```java
assertThat(summary.candidate().status()).isEqualTo(CandidateStatus.KEPT_CLEAN);
assertThat(summary.chargeFact().reviewStatus()).isEqualTo(ReviewStatus.CLEAN);
assertThat(auditCount("DUPLICATE_CANDIDATE_KEPT_CLEAN")).isEqualTo(1);
assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isEqualTo(1);
```

语义分层说明：重新导入同一文件（如 LEASE_RECOVERY / MANUAL_RETRY 重解析）会为每个 Attempt 产生新的 raw/canonical 行，但只有 CONFIRMED 的 batch 参与 duplicate 扫描；跨 Attempt 的同业务记录由 `DuplicateFingerprint` 捕获为 candidate，未复核的 OPEN candidate 通过 `UnresolvedDuplicatesBlockerProvider` 阻止账期关账，从而不会静默双计费。

运行命令：

```bash
cd backend && ./mvnw -B "-Dtest=DuplicateFingerprintTest" "-DfailIfNoSpecifiedTests=false" test
cd backend && ./mvnw -B "-Dit.test=DuplicateCandidateGenerationIntegrationTest,DuplicateReviewCommandIntegrationTest,DuplicateCandidatePersistenceIntegrationTest,M3Group2DuplicateAttributionSchemaIntegrationTest" "-DfailIfNoSpecifiedTests=false" test-compile failsafe:integration-test failsafe:verify
```

数据库核对：

```sql
mysql> SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS
       WHERE TABLE_SCHEMA='aicostops_verify' AND COLUMN_NAME='fingerprint'
         AND TABLE_NAME='duplicate_candidate';
+--------------------+-------------+-----------+
| duplicate_candidate | fingerprint | char(64)  |
+--------------------+-------------+-----------+
```

## 检查点 3：金额、币种、精度始终符合财务约束

**结论：PASS**

覆盖测试（全部复跑通过）：

| 测试类 | 用例要点 |
|---|---|
| `attribution/AllocationDecimalTest`（5，单元） | BigDecimal 归属分摊精度，无浮点 |
| `cost/application/CanonicalCostNormalizerTest`（20，单元） | 标准化金额解析/精度 |
| `reconciliation/ReconciliationMatchEngineTest`（6，单元） | 对账匹配金额与容差 |
| `ledger/LedgerFinancialInvariantIntegrationTest`（2） | 无预算入账与超预算 actual 均被允许（成本不丢弃不变量）；Correction 只改更正账期且保留历史 entry |
| `ledger/ProviderChargePostingIntegrationTest` | allocation line 金额之和必须等于 charge 金额；所有 line 币种必须等于 charge 币种（`ProviderChargePostingService.validateRevalidatedSource` 强制） |
| `M15FinancialConcurrencyIntegrationTest`（4） | 并发财务动作金额一致性 |
| `reconciliation/HybridReconciliationEvidenceIntegrationTest`（15） | `sameRequestIdAcrossTwoCurrenciesProducesTwoExactCorrelations`——跨币种不混同 |

代码防线：

- 所有金额列为 `DECIMAL(20,8)`（`charge_fact.amount`、`ledger_entry.amount`、`reconciliation_case.*` 等），代码侧全部 `BigDecimal` + `compareTo`（入账校验用 `compareTo`，非 `equals`）。
- 入账时 `sum(allocation lines) == charge.amount` 且每条 line 币种 = charge 币种，否则 `ALLOCATION_SUM_MISMATCH` / `VALIDATION_FAILED` 拒绝。
- 币种列 `CHAR(3)` 且有 DB CHECK：`chk_ledger_entry_currency`、`chk_reconciliation_case_currency`、`chk_charge_*` 等 `REGEXP_LIKE(currency,'^[A-Z]{3}$')`。
- 对账 case 以 `(provider_account_id, currency)` 为唯一粒度（`uq_reconciliation_case_run_provider_currency`），跨币种不混加。

运行命令：

```bash
cd backend && ./mvnw -B "-Dtest=AllocationDecimalTest,CanonicalCostNormalizerTest,ReconciliationMatchEngineTest" "-DfailIfNoSpecifiedTests=false" test
cd backend && ./mvnw -B "-Dit.test=LedgerFinancialInvariantIntegrationTest,M15FinancialConcurrencyIntegrationTest" "-DfailIfNoSpecifiedTests=false" test-compile failsafe:integration-test failsafe:verify
```

数据库核对：

```sql
mysql> SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS
       WHERE TABLE_SCHEMA='aicostops_verify' AND DATA_TYPE='decimal'
         AND TABLE_NAME IN ('charge_fact','ledger_entry','reconciliation_case','reconciliation_adjustment');
charge_fact.amount decimal(20,8) / ledger_entry.amount decimal(20,8) /
reconciliation_case.{external,internal,difference}_amount decimal(20,8) ...

mysql> SELECT CONSTRAINT_NAME FROM information_schema.CHECK_CONSTRAINTS
       WHERE CONSTRAINT_SCHEMA='aicostops_verify' AND CONSTRAINT_NAME LIKE '%currency%';
-- chk_ledger_entry_currency / chk_reconciliation_case_currency / chk_expense_claim_currency / ...（13 项）
```

## 检查点 4：导入任务中断 / Worker 崩溃后的安全恢复

**结论：PASS**

覆盖测试（全部复跑通过）：

| 测试类 | 用例要点 |
|---|---|
| `ingestion/application/ImportLeaseServiceIntegrationTest`（12） | `oneQueuedAttemptIsClaimedExactlyOnceByTwoWorkers`（SKIP LOCKED 抢占恰好一次）；两 worker 无阻塞认领两个任务；heartbeat 用数据库时钟续租；过期 owner/version 无法心跳；租约时长来自 DB 时钟；`expiredLeaseFailsOldAttemptAndQueuesRecoverySuccessorWithLineage`——过期租约旧 Attempt 置 FAILED(WORKER_LEASE_EXPIRED) 并排队 `LEASE_RECOVERY` 后继（带 lineage），从不复活旧 Attempt |
| `ingestion/application/ImportWorkerCoordinatorIntegrationTest`（2） | claim→execute→heartbeat 编排 |
| `ingestion/application/ImportWorkflowConcurrencyIntegrationTest`（9） | `cancelFirstFencesWorkerFinalizationDeterministically` / `cancelFirstFencesRawPersistenceDeterministically` / `cancelFirstBlocksStaleClaimAndLeaseRecoveryDeterministically`——stale worker 的每个写路径都被 lease fencing 拦截（0 行更新） |
| `ingestion/application/ImportWorkflowRetryIntegrationTest` | 恢复预算耗尽 / 手工重试 |

关键代码（fencing）：`ImportRawPersistenceService.persist` 每个有界分批事务先 `lockAndVerifyOwnership(attemptId, workerId, leaseVersion)`，未持有租约返回 `leaseLost=true`（0 插入）；`ImportAttemptExecutor` 丢失租约静默停止，后继 Attempt 重新解析（重处理幂等由检查点 2 的指纹层兜底）。`ImportLeaseService.recoverExpiredLease` 有死锁重试（3 次）与恢复预算（`maxLeaseRecoveries`，耗尽则 Batch FAILED）。

运行命令：

```bash
cd backend && ./mvnw -B "-Dit.test=ImportLeaseServiceIntegrationTest,ImportWorkerCoordinatorIntegrationTest,ImportWorkflowConcurrencyIntegrationTest,ImportWorkflowRetryIntegrationTest" "-DfailIfNoSpecifiedTests=false" test-compile failsafe:integration-test failsafe:verify
```

脱敏输出摘录（`chainB-batch1.log`）：

```text
[INFO] Tests run: 12, Failures: 0, Errors: 0 -- in com.aicostops.ingestion.application.ImportLeaseServiceIntegrationTest
[INFO] Tests run: 9, Failures: 0, Errors: 0 -- in com.aicostops.ingestion.application.ImportWorkflowConcurrencyIntegrationTest
[INFO] Tests run: 42, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 检查点 5：入账、冲正、关账的不可变性与审计语义

**结论：PASS**

覆盖测试（全部复跑通过）：

| 测试类 | 用例要点 |
|---|---|
| `ledger/LedgerImmutabilityArchitectureTest`（2，架构门禁） | ledger 包源码层无 `@Update`/`@Delete`/`UPDATE LEDGER_`/`DELETE FROM LEDGER_`/`DELETE FROM CORRECTION_GROUP`；`LedgerPostingMapper` 只允许 `@Insert`+`@Select` |
| `ledger/LedgerCorrectionIntegrationTest`（8） | `reversalOnlyKeepsHistoricalRowsAndReplaysByIdempotencyKey`：REVERSAL entry 追加（`-6.00000000`）并指向 `reversesEntryId`，历史行 posting_id/correction_group_id 不变；更正命令幂等键回放不产生第二次 posting/审计；`replaceRejectsCurrencyChangeBeforeWritingCorrectionRows`——币种变更在写任何更正行之前被拒 |
| `ledger/LedgerCorrectionIntegrityIntegrationTest`（1） | correction_group_id 来源语义（M5 canonical 更正通过，非语义来源失败） |
| `ledger/LedgerCorrectionRollbackIntegrationTest`（1） | 更正失败回滚 |
| `reconciliation/application/PeriodReopenIntegrationTest`（3） | reopen 递增 generation 且保留关账历史；OPEN 期间二次 reopen 被拒且不递增；reopen 后关账从新 generation attempt 1 开始 |
| `ledger/ProviderChargePostingIntegrationTest` | `committedPostingReplaysAfterPeriodClosesWithoutMutation`——账期 CLOSED 后回放 posting 零变更 |

关键断言摘录（`LedgerCorrectionIntegrationTest`）：

```java
assertThat(reversal.entryType().name()).isEqualTo("REVERSAL");
assertThat(reversal.reversesEntryId()).isEqualTo(targetEntryId);
assertThat(historical.get("posting_id")).isEqualTo(sourcePostingId);   // 历史行未被改动
assertThat(historical.get("correction_group_id")).isNull();
assertThat(auditCount("LEDGER_CORRECTION_POSTED")).isEqualTo(1);        // 审计恰好一次
```

审计完整性：入账/更正/关账/重开各写 `audit_event`（`LEDGER_CHARGE_POSTED`、`LEDGER_CORRECTION_POSTED`、`IMPORT_*`、`DUPLICATE_CANDIDATE_*`），各测试均断言精确次数。

运行命令：

```bash
cd backend && ./mvnw -B "-Dtest=LedgerImmutabilityArchitectureTest" "-DfailIfNoSpecifiedTests=false" test
cd backend && ./mvnw -B "-Dit.test=LedgerCorrectionIntegrationTest,LedgerCorrectionIntegrityIntegrationTest,LedgerCorrectionRollbackIntegrationTest,PeriodReopenIntegrationTest" "-DfailIfNoSpecifiedTests=false" test-compile failsafe:integration-test failsafe:verify
```

数据库核对：`chk_ledger_posting_status CHECK (status = 'POSTED')`（一次性容器实测存在），DB 层不允许出现非 POSTED 状态；不变量"POSTED 不可变、更正只追加"由架构门禁 + 应用层 append-only 写缝共同保证。

## 检查点 6：已关账期间不可被非法写入（8 项 CloseBlocker + 全写入口财务栅栏）

**结论：PASS**

覆盖测试（全部复跑通过）：

| 测试类 | 用例要点 |
|---|---|
| `reconciliation/CloseBlockerProviderIntegrationTest`（2） | registry 恰好包含 `CloseBlockerCode.values()` 全部 **8** 项（OPEN_IMPORTS / UNRESOLVED_DUPLICATES / UNALLOCATED_CHARGES / UNPOSTED_APPROVED_EXPENSES / OPEN_MATERIAL_RECONCILIATION / PENDING_CORRECTIONS / LEDGER_INTEGRITY / PENDING_GATEWAY_FINANCIAL_WORK）；构造六类脏数据后各 blocker 分别 FAIL/PASS |
| `reconciliation/UnknownPeriodCloseRaceIntegrationTest`（2） | "未知账期 admission 获胜 → close 等待组织行"；"close 获胜 → 后续未知账期 admission 被拒"——用真实线程与 DB 锁编排竞争 |
| `reconciliation/KnownPeriodCloseRaceIntegrationTest`（2） | 已知账期关账与写入竞争 |
| `cost/review/DuplicateClosedPeriodAdmissionIntegrationTest`（2） | CLOSED 期间 duplicate review 的 admission 拒绝 |
| `reconciliation/ProviderChargeHybridFenceIntegrationTest`（5） | Hybrid 栅栏：RECONCILIATION_EVIDENCE charge 不可走普通入账路径；重叠 Gateway 财务工作未 disposition 则拒绝 |
| `ledger/ProviderChargePostingIntegrationTest` | CLOSED 账期只允许幂等回放，不允许新入账 |

代码防线（不只 Controller 校验）：`BillingPeriodFinancialWriteFenceService`（`FOR UPDATE` 级账期行锁 + OPEN 状态强校验，fail-closed：0 个覆盖账期或多重重叠账期均拒绝）在以下全部财务写入口生效——`ProviderChargePostingService`/`ExpensePostingService`（经 `LedgerBudgetService.lockOpenPeriodAt`）、`BudgetCommandService`、`BudgetCommitmentCommandService`、`GatewaySettlementService`、`GatewayFinancialResolutionService`、`ReconciliationRunService`、`ReconciliationAdjustmentService`、`BudgetBackedCloseAdmissionAdapter`；关账 admission 与未知账期写入共享组织行锁，实现检查点 6 要求的竞争安全。

运行命令：

```bash
cd backend && ./mvnw -B "-Dit.test=CloseBlockerProviderIntegrationTest,UnknownPeriodCloseRaceIntegrationTest,KnownPeriodCloseRaceIntegrationTest,DuplicateClosedPeriodAdmissionIntegrationTest,ProviderChargeHybridFenceIntegrationTest,M15FinancialConcurrencyIntegrationTest" "-DfailIfNoSpecifiedTests=false" test-compile failsafe:integration-test failsafe:verify
```

脱敏输出摘录（`chainB-batch2.log`）：

```text
[INFO] Tests run: 2 -- in com.aicostops.reconciliation.CloseBlockerProviderIntegrationTest
[INFO] Tests run: 2 -- in com.aicostops.reconciliation.UnknownPeriodCloseRaceIntegrationTest
[INFO] Tests run: 5 -- in com.aicostops.reconciliation.ProviderChargeHybridFenceIntegrationTest
[INFO] Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

## 检查点 7：对账差异可定位到真实来源记录（溯源链路）

**结论：PASS**

覆盖测试（全部复跑通过）：

| 测试类 | 用例要点 |
|---|---|
| `ledger/LedgerLineageApiIntegrationTest`（3） | Ledger lineage API 返回完整溯源链：posting → allocationDecision → chargeFact → rawProviderRecordId → importAttemptId → importBatchId → providerEvidenceId |
| `reconciliation/ReconciliationAdjustmentIntegrationTest`（12） | case 级 adjustment 金额必须 = 当前外部-内部差值；line 之和必须等于金额；陈旧 run basis 先于任何财务变更被拒；OPEN case 账期只接受同账期入账；CLOSED case 账期只接受显式更正账期 |
| `reconciliation/ReconciliationTruthIntegrationTest`（3） | 外部真相（来自 CONFIRMED attempt 的 review 态 + 半开账期）与内部真相（含 Correction/Settlement/Adjustment entry）逐笔对齐 |
| `reconciliation/HybridReconciliationEvidenceIntegrationTest`（15） | case 携带 `provider_request_id` 级证据（`aggregateCaseCarriesBoundedScopeEvidence`）；精确关联要求同 Provider 账户、同大小写 requestId、认证 profile；金额/时间邻近绝不自动绑定 |
| `M15ReconciliationApiIntegrationTest`（19） | case → evidence → disposition → resolution 全 API 面 |
| `reconciliation/ReconciliationAdjustmentRollbackIntegrationTest`（1） | adjustment 失败回滚 |

溯源链路说明：对账 case 粒度为 `(reconciliation_run, provider_account_id, currency)`（DB CHECK 强制三种 case 类型与行数一致性）；case 的差异证据通过 `/reconciliation-runs/{runId}/evidence` 与 `/reconciliation-cases/{caseId}/evidence` 下钻到 `provider_request_id`/charge 级；charge 级再到 raw record 的链路由 Ledger lineage API 提供（`LedgerLineageResponse` 含 `rawProviderRecordId / importAttemptId / importBatchId / providerEvidenceId`），前端 `ledger/api/ledgerApi.ts` 消费该契约。

运行命令：

```bash
cd backend && ./mvnw -B "-Dit.test=LedgerLineageApiIntegrationTest,ReconciliationAdjustmentIntegrationTest,ReconciliationTruthIntegrationTest,HybridReconciliationEvidenceIntegrationTest,M15ReconciliationApiIntegrationTest,ReconciliationAdjustmentRollbackIntegrationTest" "-DfailIfNoSpecifiedTests=false" test-compile failsafe:integration-test failsafe:verify
```

## 检查点 8：前端报表与 MySQL 权威数据一致（API 契约层）

**结论：PASS**

验证方式（不起浏览器）：

| 层 | 测试 | 结果 |
|---|---|---|
| 契约 ↔ Controller | `ledger/M5OpenApiContractTest`、`reconciliation/M6OpenApiContractTest`、`reporting/M7OpenApiContractTest`、`contract/M18OpenApiContractTest` | 全部 PASS |
| 报表聚合 ↔ MySQL | `reporting/WorkbenchIntegrationTest`（6，真实 MySQL） | PASS |
| 规模基准下报表一致性 | `M9ReportingScaleBenchmarkIntegrationTest`（1，真实 MySQL 规模数据） | PASS（381s） |
| 前端 API 客户端 | `npm test -- --run`（ledger/reconciliation/period-close/imports/duplicates/workbench/costs/api 共 16 个测试文件） | 148/148 PASS |

前端端点与 `docs/02-development/api/openapi.yaml` 逐条比对：前端实际调用的 `/evidence`、`/evidence/{id}/imports`、`/imports[/{id}][/confirm|/retry|/cancel|/attempts/...]`、`/ledger/postings|entries|corrections`、`/reconciliation-runs|cases[.../evidence|adjustments|...]`、`/billing-periods[/{id}/close-readiness|close-runs|close|reopen]`、`/workbench` 全部存在于契约中，无孤儿端点。前端金额字段为 string 类型透传（`amount: string`），不在 UI 层做浮点运算。

运行命令：

```bash
cd backend && ./mvnw -B "-Dtest=M5OpenApiContractTest,M6OpenApiContractTest,M7OpenApiContractTest,M18OpenApiContractTest" "-DfailIfNoSpecifiedTests=false" test
cd backend && ./mvnw -B "-Dit.test=WorkbenchIntegrationTest,M9ReportingScaleBenchmarkIntegrationTest" "-DfailIfNoSpecifiedTests=false" test-compile failsafe:integration-test failsafe:verify
cd frontend && npm test -- --run src/features/ledger src/features/reconciliation src/features/period-close src/features/imports src/features/duplicates src/features/workbench src/features/costs src/api
```

脱敏输出摘录：

```text
# backend contract/unit（chainB-unit.log）
[INFO] Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
# frontend
Test Files  16 passed (16)
     Tests  148 passed (148)
```

---

## 汇总运行结果

| 批次 | 范围 | 结果 |
|---|---|---|
| 批次 1 | 幂等/租约/并发/去重（5 类） | 42 tests, 0 failures |
| 批次 2 | 金额/Correction/关账竞争/Hybrid 栅栏（11 类） | 30 tests, 0 failures |
| 批次 3 | 对账溯源/重开/报表/规模基准（9 类） | 63 tests, 0 failures |
| 批次 4 | evidence 复用/schema/retry/posting 回放（8 类） | 91 tests, 0 failures |
| 单元+架构+契约（10 类） | DECIMAL/指纹/匹配引擎/不可变门禁/OpenAPI | 51 tests, 0 failures |
| 前端 feature/API 测试 | 16 文件 | 148 tests, 0 failures |

## 发现的问题与修复

| # | 级别 | 问题 | 处理 |
|---|---|---|---|
| 1 | 非缺陷（文档准确性） | `CloseBlockerProviderIntegrationTest.registryContainsExactlySevenCanonicalProviders` 方法名写 "Seven"，实际 `CloseBlockerCode` 为 8 项（断言本身基于 `values()`，行为正确，仅名称过时误导验收读者） | 重命名为 `registryContainsExactlyEightCanonicalProviders` 并复跑通过（未改任何断言/预期） |

无重复入账、CLOSED 账期可写、冲改历史流水等 P0/P1 业务正确性缺陷。

## NOT_VERIFIED / BLOCKED 清单

| 项 | 原因 |
|---|---|
| 真实浏览器端到端点击（检查点 8 的 UI 层） | 按任务要求仅做 API 契约层验证，不起浏览器 |
| 真实 Provider 原始导出重放 | 仓库测试夹具约定仅允许 `REAL_SCHEMA_SANITIZED` / `OFFICIAL_SCHEMA_SYNTHETIC` / `SYNTHETIC_ENTERPRISE`，本次沿用既有 sanitized 夹具，未引入新真实数据 |
| 全量 `./mvnw verify`（含全部 170+ 测试类） | 按方法要求定向选择；全量回归由 CI 承担 |
| MinIO 真实对象存储路径（`MinioObjectStorageAdapterIntegrationTest`） | evidence 复用语义已由 `EvidenceStorageServiceIntegrationTest` 覆盖，存储适配器不属链路 B 验收目标 |

## 结论

业务链路 B 的 8 个检查点全部 **PASS**：三层幂等键（file_hash / 记录指纹 / posting_key）语义清晰且各有唯一键与测试兜底；金额全程 DECIMAL/BigDecimal 且跨币种不混加；Worker 租约（SKIP LOCKED + DB 时钟 + leaseVersion fencing + 恢复后继）在崩溃、取消、重试竞争下均只产生合法状态；POSTED Ledger 只追加（架构门禁 + DB CHECK + Correction 幂等）；CLOSED 账期写保护由 8 项 CloseBlocker 与组织/账期行锁级财务栅栏覆盖全部写入口并经真实竞争测试；对账差异可经 case → evidence → Ledger lineage API 一路下钻到原始 Provider 记录；前端与契约层一致（148 前端测试 + 4 个 OpenAPI 契约测试 + Workbench/M9 规模基准对账 MySQL 权威数据）。验收期间未发现违反不变量的缺陷；唯一发现为一处过时的测试方法名，已更名并复跑确认。
