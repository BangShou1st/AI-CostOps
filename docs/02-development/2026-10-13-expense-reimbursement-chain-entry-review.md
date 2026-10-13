# 报销财务链入口审查记录（2026-10-13）

基线：`codex/history-coverage-20261012@0039452` + 本阶段报销链补测。范围：证据上传与绑定 → 创建/编辑 → 提交/撤回/重新提交 → 审批 → 入账 → 预算/承诺。每入口按
「谁可操作 / 允许旧状态 / 新状态 / 幂等身份 / 锁序 / 共同提交 / 失败恢复」记录。风格偏好不计入缺陷；"无问题"条目记录的是实际依据。

本批**未发现需要修复的确认缺陷**：五个写入口的权限门、乐观版本 CAS、幂等身份、关账门与冻结锁序、成功审计与财务动作同事务的设计一致且可核查；对缺少证据的场景补了定向测试（见 §7）。

## 1. 证据上传与绑定（`ExpenseEvidenceUploadService.attach`；`POST /api/v1/expenses/{id}/evidence`）

- **谁可操作**：组织级 `EVIDENCE_UPLOAD_OWN`（`requireOrg`，兼作组织准入）；权限门在任何对象存储或写入**之前**。
- **允许旧状态 / 新状态**：仅 `ExpenseClaimStatus.editableByOwner()`（DRAFT / NEEDS_INFO）可绑定；受 `expectedVersion` CAS 保护；成功时 `version+1` 并写 `evidence_id`。SUBMITTED/APPROVED/POSTED 拒绝（409）。
- **幂等身份**：无 `Idempotency-Key`（替换式 PUT 语义，与 body edit 一致）；去重身份是证据行 `(org_id, sha256)` 唯一键，对象键 `org/{org}/evidence/sha256/{xx}/{sha}`。
- **锁序**：预读（无锁，先拒绝过期版本，避免为过期请求写入对象）→ 对象存储（在费用行锁**之外**，`reserveOrReuse`/`put`/`markAvailable` 各自短事务）→ 短事务：`expense_claim` 行 `FOR UPDATE` → 重校验 owner/可编辑/version → `attachEvidence` 条件更新。
- **共同提交**：绑定（`version+evidence_id`）与 `EXPENSE_EVIDENCE_ATTACHED` 审计在同一事务；审计失败整体回滚绑定。
- **失败恢复**：数据库回滚**不撤销对象存储效果**。绑定失败（版本冲突/状态变化/审计失败）后，证据行与对象**保留**且可被后续同 sha 上传**复用**——不得删除，因为同 `(org,sha)` 证据可能被其他申请共享引用。存储失败时行被标记 `FAILED` 或保持 `STAGING`，后续同 sha 上传会修复（`reserveOrReuse` + `stat` + `markAvailable`）。**无回收机制**，属设计选择（避免误删共享证据）。
- **下载权限与跨组织**：本人须 `EXPENSE_READ_OWN` **且** 为 claimant；评审须 `EXPENSE_REVIEW`（ORG 级）。两条路径的证据查询都带 `org_id` 谓词，跨组织一律 404（不泄露存在性）。
- **结论**：无问题。新增 4 项测试（§7）。

## 2. 创建 / 编辑 / 提交 / 撤回 / 重新提交（`ExpenseClaimCommandService`；`/api/v1/expenses`）

- **创建**：`EXPENSE_CREATE_OWN`；幂等 `EXPENSE_CREATE` + 请求哈希（`expenseDate/amount/currency`）。DRAFT、version=0、claimant 绑定当前成员。
- **编辑**：`EXPENSE_CREATE_OWN`（自有草稿沿用创建自有权限）；**无**幂等键；`expectedVersion` CAS；仅 DRAFT/NEEDS_INFO。
- **提交**：`EXPENSE_SUBMIT_OWN`；幂等操作恒为 `EXPENSE_SUBMIT`（重放不受中间状态影响），`reserve` 在权限门**之后**。状态迁移 DRAFT→SUBMITTED（首个 `SUBMIT` 建审批案例）或 NEEDS_INFO→SUBMITTED（`RESUBMIT` 复用案例）；前置证据必须存在且 `AVAILABLE`。`approval_action` 追加 `SUBMIT`/`RESUBMIT`；`EXPENSE_SUBMITTED` 审计与状态、案例、action、幂等 finalize 同事务。
- **撤回**：`EXPENSE_SUBMIT_OWN`；仅 SUBMITTED→CANCELED，且审批案例必须 PENDING→CANCELED 并追加 `CANCEL` action；`EXPENSE_CANCELED` 审计同事务。DRAFT/NEEDS_INFO/APPROVED 不可撤回。
- **幂等身份**：`(org_id, actor_member_id, operation, hex(SHA-256(key)))`；不同请求体复用同键 → 409。
- **锁序**：幂等 `reserve`（首写）→ `expense_claim` 行 `FOR UPDATE`（提交/撤回）→ `approval_case` 行 `FOR UPDATE`。
- **撤权下的幂等重放**：权限门在 reserve/replay 之前，撤权后同键重放返回 **403**，不会返回缓存成功（新增测试确认）。
- **失败恢复**：异常整体回滚（含 reserve 行）；`ExpenseCommandTransactions` 对死锁重试 3 次并包裹**整**事务，从不重试外部存储。
- **状态机**：由 `ExpenseClaimStatus.canTransitionTo` 冻结；`POSTED/REJECTED/CANCELED` 为终态。
- **结论**：无问题。`resubmitRequestHash`/`OPERATION_RESUBMIT` 常量为未使用的预留（提交恒用 `EXPENSE_SUBMIT`），属一致性设计，不计为缺陷。

## 3. 审批（`ExpenseReviewCommandService`；`/api/v1/expenses/{id}/{request-info|approve|reject}`）

- **谁可操作**：组织级 `EXPENSE_REVIEW`（`requireOrg`，在 `reserve` 之前；同组织无 owner 比较）。
- **动作 / 状态迁移**：`REQUEST_INFO`、`APPROVE`、`REJECT` 均自 `SUBMITTED + PENDING` 出发；同事务写 `expense_claim.status`、`approval_case.status`、`approval_action`、审计、幂等 finalize。
- **幂等身份**：`EXPENSE_REQUEST_INFO` / `EXPENSE_APPROVE` / `EXPENSE_REJECT` + 请求哈希（含 `expectedVersion`、`comment`）。
- **锁序**：`approve` 先 `closeAdmission.lockOpenAt(org, expense_date)`（关账准入，要求覆盖账期 **OPEN**）→ `expense_claim` 行 `FOR UPDATE` → `approval_case` 行 `FOR UPDATE`；`requestInfo`/`reject` 不产生财务事实，不开关账门。
- **与关账竞争**：`approve` 对账期上锁并要求 OPEN；CLOSING/CLOSED 一律拒绝且不改任何行（`ExpenseFailureAtomicityIntegrationTest` 参数化覆盖）。已提交结果重放早于关账门返回，不改财务事实。
- **过期版本 / 已变化状态**：`version` CAS + `SUBMITTED/PENDING` 状态检查，均 409。
- **权限上下文**：`approve` 使用 `authorizationContexts.fresh()`（跳过缓存取当前授权），`requestInfo`/`reject` 使用 `current()`——与 approve 的财务邻近性一致。
- **结论**：无问题。新增同键并发审批用例（只切换一次）。

## 4. 入账（`ExpensePostingService.post`；`POST /api/v1/expenses/{id}/post`）

- **谁可操作**：组织级 `EXPENSE_POST` + `LEDGER_POST`（均 `requireOrg`）。
- **前置 / 状态迁移**：`load` 预读 → 需 `current_allocation_decision_id`；`postingKey = EXPENSE:{id}`；已存在则直接返回。APPROVED→POSTED（`markPosted` 条件更新，必须恰好 1 行）；`lockAndRequireApproved` 校验状态与 decision 指针一致。
- **幂等身份**：`posting_key` 唯一键；事务内外双读；`DuplicateKeyException` 后在新事务读取 winner 返回。
- **锁序（冻结）**：BillingPeriod(`lockOpenAt`) → Budgets（按 id 升序）→ Commitments → source(expense `FOR UPDATE`) → allocation decision → lines；重校验 pre-read 快照（日期/金额/币种/version/账期覆盖、allocation 行逐项内容）。
- **共同提交**：posting、ledger entries、`budget.actual` 增量、commitment 消费、`LEDGER_EXPENSE_POSTED` 审计、expense POSTED 状态在同一事务。
- **关账竞争**：首入账要求 OPEN 账期；已提交结果在关账后重放不产生任何变更（`committedPostingReplaysAfterPeriodCloses`）。
- **中途失败 / 恢复**：审计失败或循环中途异常整体回滚，无半提交（新增测试确认 posting/entries/预算/承诺/状态全无残留）；进程崩溃随事务回滚，重启后 `postingKey` 幂等重放。
- **金额 / 币种 / 精度**：allocation 行币种必须逐行等于报销币种；行金额和必须**精确**等于报销金额；负行写 CREDIT 且不得消费承诺；DECIMAL(20,8)。
- **结论**：无问题。

## 5. 预算 / 承诺（`LedgerBudgetService.incrementActual` / `CommitmentConsumeService.consume`）

沿用文件账单链结论（见 `2026-10-13-billing-chain-entry-review.md` §5）：`incrementActual` 在入账事务内、预算行已按 id 升序 `FOR UPDATE` 锁定，`status='ACTIVE'` 条件更新恰好 1 行；`consume` 以 `ledger_entry` lineage 幂等（已消费则零副作用重放），状态门仅 ACTIVE/PARTIALLY_CONSUMED，`insertUsage` 唯一键冲突后重读 winner，条件更新 0 行即回滚，审计同事务。报销入账复用同一实现，无独立分支。
- **结论**：无问题。

## 6. 跨入口一致性

- **组织隔离**：所有查询/更新都带 `org_id` 谓词；不存在性统一 404，不区分"他组织存在"。
- **币种**：allocation 行逐行匹配报销币种；预算按 `(org, period, scope, currency)` 精确身份。
- **精度**：DECIMAL(20,8) + BigDecimal + decimal-string 契约，比较用 `compareTo`。
- **审计**：所有财务命令的成功审计都是同事务依赖（audit 抛出即整体回滚）。
- **状态/权限**：OWN 权限组（创建/读取/提交/证据上传）只在测试支撑中显式声明；评审与入账需 ORG 级，系统管理员不因 ORG 范围自动获得（`M1AdminPermissionPolicy` 冻结）。

## 7. 本次改动

**无生产代码修改**（未发现确认缺陷）。仅新增/扩展测试以固定上述不变量的执行证据：

| 测试 | 新增用例 | 验证点 |
| --- | --- | --- |
| `ExpenseEvidenceBindingRecoveryIntegrationTest`（新） | 4 | 绑定失败保留证据且可复用；重复上传同文件复用同一证据行；并发同文件仅绑定一次；跨组织下载 404 |
| `ExpenseIdempotencyIntegrationTest`（扩展） | 2 | 撤权后同键重放仍 403（提交与审批） |
| `ExpenseReviewConcurrencyIntegrationTest`（扩展） | 1 | 同键并发审批恰好切换一次 |
| `ExpensePostingIntegrationTest`（扩展） | 1 | 审计失败后入账整体回滚（posting/entries/预算/承诺/状态） |

## 8. 待验证风险（记录，非缺陷）

- **同键并发时 winner 回滚**：若先到达的同键请求因业务原因（如关账 409）回滚，后到达者会收到 `DuplicateKeyException` 而非同一业务错误。这是 expense / import / duplicate-review 各模块**一致的既有幂等实现选择**（`insertProvisional` 冲突后 `findByNaturalKeyForUpdate` 返回 null 即重抛），超出本链范围，未修改；如后续统一处理需跨模块评估。
- **孤儿证据无回收**：绑定失败后保留的证据行不回收，属"不得删除共享去重证据"的设计选择。
