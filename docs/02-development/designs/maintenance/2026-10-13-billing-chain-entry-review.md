# 文件账单财务主链入口审查记录（2026-10-13）

基线：`codex/history-coverage-20261012@435b0bd` + 本阶段预测资格修复。范围：导入确认 → 重复费用处置 → 分摊确认 → Provider 入账 → 预算/承诺更新。每入口按"谁可操作 / 允许旧状态 / 新状态 / 幂等身份 / 锁序 / 共同提交 / 失败恢复"记录。风格偏好不计入缺陷；"无问题"条目记录的是实际依据。

## 1. 导入确认（ImportWorkflowCommandService.confirm）

- **权限**：组织级 `IMPORT_CONFIRM`；`preflightOrgScopedBatch` 保证 404 不泄露跨组织存在性。
- **状态迁移**：`READY_FOR_REVIEW + 最新 attempt SUCCEEDED 且 errorCount=0 → CONFIRMED`（`markConfirmed` 条件更新，必须恰好影响 1 行）。已 CONFIRMED 且 confirmedAttemptId=最新 attempt 时为语义重确认（幂等重放，不开新关账门）。
- **幂等**：`ImportCommandIdempotency.reserve/finalize`（org+member+operation+key 唯一），replay 返回序列化的原响应；死锁重试 3 次包裹整个事务。
- **关账门**：新确认必须 `lockAndRequireNoClosingPeriod` + `lockAndRequireOpenPeriodsForAttempt`（组织准入锁 → 按 attempt 的贡献账期升序逐一 lockOpenAt）。重放旧确认不需要新门（不改财务事实）。
- **共同提交**：batch 状态、审计、幂等 finalize、指标在同一事务。
- **失败恢复**：异常整体回滚；幂等 reserve 行随事务回滚（reserve 在同一事务内），不留半提交。审计回滚路径有 `ImportWorkflowAuditRollbackIntegrationTest`。
- **既有测试**：`ImportConfirmIntegrationTest`、`ImportWorkflowIdempotencyIntegrationTest`、`ImportWorkflowConcurrencyIntegrationTest`（cancel×worker×lease 多组竞争）、`ImportWorkflowRetryIntegrationTest`。
- **结论**：无问题。

## 2. 重复费用处置（DuplicateReviewCommandService.scan/keep/exclude）

- **权限**：组织级 `DUPLICATE_REVIEW`。
- **scan**：只创建候选（不改 charge 财务字段之外的事实）；批量 500 写入，每批 `lockAndRequireNoClosingPeriod` → 覆盖账期 lockIfCoveredAndRequireOpenAt → 按 id 升序锁两端 charge；`insertIgnoringDuplicate` 依赖唯一键去重。
- **keep**：候选 OPEN 才可处置；锁 candidate → 两端 charge（升序）；`markKeptClean` 条件更新；两端 `restoreCleanIfNoOpen`（仍有 OPEN 候选则保持 SUSPECTED）。不改 `duplicate_of_charge_id` 指针、不改金额，属候选状态处置，不需要组织级关账门；与关账的互斥由"关账 blocker 检查 OPEN 候选 + keep 锁 charge 行"覆盖。**依据**：keep 唯一的 charge_fact 写入是 review_status SUSPECTED→CLEAN 条件更新，不产生/撤销财务事实。
- **exclude**：改变外部财务事实（charge 被标 EXCLUDED、写 duplicate_of 指针），因此有完整关账门：`lockAndRequireNoClosingPeriod` → 两端覆盖账期 `lockIfCoveredAndRequireOpenAt`（CLOSED 期间拒绝，`DuplicateClosedPeriodAdmissionIntegrationTest` 已验证）→ candidate/charge 锁。inbound 引用计数防 keeper 链式；其他 OPEN 候选被 supersede；审计与幂等共同提交。
- **既有测试**：`DuplicateReviewCommandIntegrationTest`（含审计回滚开关）、`DuplicateClosedPeriodAdmissionIntegrationTest`、`DuplicateCandidateGenerationIntegrationTest`。
- **结论**：无问题。

## 3. 分摊确认（AllocationDecisionCommandService.confirm/createManualDraft/replaceLines）

- **权限**：`ALLOCATION_EDIT` / `ALLOCATION_CONFIRM`；EXPENSE_CLAIM 额外要求 `EXPENSE_REVIEW`，且权限门在幂等 reserve **之前**执行——撤权后不能靠重放缓存响应绕过（测试 `chargeConfirmReplayIsUnaffectedByExpenseReviewGate`）。
- **状态迁移**：DRAFT → CONFIRMED（`confirmDecision`）；subject 的 `assertConfirmEligible`（CHARGE_FACT：confirmed-import 血统 + CLEAN + 无 current 指针；EXPENSE_CLAIM：APPROVED）；`assertNoConfirmed` 防止二次确认。
- **幂等**：reserve/finalize + 请求哈希；manual draft 有 MANUAL 唯一约束（已有 MANUAL 草稿则 409）。
- **锁序**：source → decision → lines（注释与测试 `expenseConfirmKeepsSourceFirstLockOrder` 固定）；金额守恒：lines 和必须精确等于 subject 金额（`ALLOCATION_SUM_MISMATCH`），币种逐行匹配。
- **共同提交**：decision 状态、current 指针、审计、幂等 finalize 同事务；审计失败或指针写失败整体回滚（`auditFailureRollsBackDecisionPointerAndIdempotency`、`pointerFailureRollsBackDecisionStatusAuditAndIdempotency`）。
- **关账边界**：confirm 本身不开关账门——分摊只是归属决策，不产生账本/预算事实；真正的财务写入（Provider 入账）有自己的关账门，且关账 blocker `UNALLOCATED_CHARGES` 会阻止带未分摊 charge 的账期关闭。`replaceLines` 无幂等键（编辑草稿可重复，最终由 confirm 的幂等与唯一确认约束兜底），与"只有 MANUAL DRAFT 可编辑"的状态门一致。
- **既有测试**：`AllocationCommandIntegrationTest`（两 draft 并发确认恰好一个成功、confirm vs edit 竞争）、`AllocationRuleVersionConcurrencyIntegrationTest`。
- **结论**：无问题。

## 4. Provider 入账（ProviderChargePostingService.post）

- **权限**：组织级 `LEDGER_POST`。
- **幂等身份**：`posting_key = CHARGE:<id>:ALLOCATION:<decisionId>`，唯一键兜底；`DuplicateKeyException` 后在新事务读取 winner 返回（避免 rollback-only 事务内查询）；事务内外双重 `selectPostingByKey`。
- **锁序（冻结）**：BillingPeriod（lockOpenAt）→ Budgets（按 id 升序）→ Commitments → Source(charge) → Allocation decision → Lines；重验证 pre-read 快照（charge 金额/币种/期间、allocation 行内容任一变化即 STATE_CONFLICT）。
- **混合门**：`ProviderChargeHybridPostingGuard` 在账期锁后、写入前重查；RECONCILIATION_EVIDENCE 拒绝、Gateway 重叠未处置拒绝。
- **共同提交**：posting、entries、budget.actual 增量、commitment 消费、审计同事务。`incrementActual` 条件更新（status='ACTIVE' 恰好 1 行）；actual 允许为负（迁移注释明确 credits/reversals 可使 actual < 0），total/committed 有非负 CHECK。
- **负金额**：负 allocation 行写入 CREDIT 类型账本分录且不允许消费 commitment（`negativeCreditCannotConsumeAnExplicitCommitment`）；结算表 CHECK 保证 Gateway 来源金额非负，负数只经合法的冲正/对账调整进入账本（见预测资格文档）。
- **关账竞争**：`closeWinningRaceRejectsEligibleLedgerPostWithoutFinancialMutation`、`committedPostingReplaysAfterPeriodClosesWithoutMutation`；并发重复入账 `ProviderPostingConcurrencyIntegrationTest`（两次并发只产生一次 posting+entry）；审计/预算回滚 `ProviderPostingRollbackIntegrationTest`；金额守恒 `LedgerFinancialInvariantIntegrationTest`（冲正只改冲正期间、历史分录保留）。
- **结论**：无问题。

## 5. 预算/承诺更新（LedgerBudgetService.incrementActual / CommitmentConsumeService.consume）

- **incrementActual**：`UPDATE budget SET actual_amount=actual_amount+? WHERE status='ACTIVE'`，影响行数校验；在入账事务内、预算行已按 id 升序 FOR UPDATE 锁定，无丢失更新窗口。
- **consume 锁序**：BillingPeriod → Budget → Commitment（与入账冻结锁序一致，注释引用 04-transactions §10）；lineage 幂等先行：同 ledger_entry 已消费过则零副作用重放（含 CONSUMED/RELEASED 终态）；新条目才走状态门（仅 ACTIVE/PARTIALLY_CONSUMED 可消费）；`insertUsage` 唯一键冲突后重读 winner 重放，防止双消费；`decrementCommitted`/`updateConsume` 条件更新，任何 0 行即回滚；审计共同提交。
- **消费上限**：`consumed = min(entryAmount, remaining)`，remaining 非负 CHECK；消费不足部分留在预算 actual（已由入账全额计入），承诺只覆盖被批准的额度——口径一致。
- **既有测试**：承诺消费原子性/竞争测试在 budget 模块（`BudgetCommitment*` 系列）与入账集成测试中覆盖（`postsEntriesBudgetsCommitmentUsageAndReplaysStableKey`）。
- **结论**：无问题。

## 6. 跨入口一致性

- **组织隔离**：五个入口所有查询/更新都带 `org_id` 谓词；不存在性统一 404，不区分"他组织存在"。
- **币种**：allocation 行逐行匹配 subject 币种；预算选择按 (org, period, scope, currency) 精确身份，未命中回落 ORG 同币种预算；账本分录携带行级币种，成本聚合按币种分组。
- **精度**：金额全部 DECIMAL(20,8) + BigDecimal + decimal-string 契约；比较用 compareTo 不用 equals。
- **审计失败**：所有财务命令的审计都是同事务依赖（audit 抛出即整体回滚），符合"成功审计与财务动作共同提交"的既定设计；任务尝试类记录才用独立事务。
- **中途失败/重启**：每个命令依赖 DB 事务原子性；worker 类（import worker）有 lease/条件状态机；入账在进程崩溃时随事务回滚，重启后 postingKey 幂等重放。

## 7. 本次改动

无代码修复。审查发现的设计（条件更新+幂等+关账门+锁序）在五个入口中一致且与既有测试对应；未发现为产生改动而重构的依据。已复跑的相关回归（见验证记录）：allocation、ledger posting 系列、duplicate review 系列、import workflow 系列全部通过。

**待验证风险（不属于本次范围，保持记录）**：`replaceLines` 无幂等键属设计选择（草稿可反复编辑）；CLOSING 期间的 keep 依赖 charge 行锁串行化而非显式组织门，跨期竞争窗口由关账 blocker 的 OPEN 候选检查兜底——两者均与文档化设计一致，不计为缺陷。
