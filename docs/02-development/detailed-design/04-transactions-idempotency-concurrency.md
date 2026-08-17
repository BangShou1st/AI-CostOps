# 04. 事务、幂等与并发设计

## 1. 总原则

```text
业务不变量
+
MySQL Transaction / Constraint
+
Integration Test
```

Redis 可以优化，但不能成为财务正确性的唯一 Guard。

## 2. Evidence Upload

Object Storage 与 MySQL 无法做一个本地 ACID Transaction。

流程：

```text
1. Stream 到临时文件
2. 同时计算 SHA-256
3. 校验大小 / 类型
4. 查 (org_id, sha256)
5. 新文件写入 Deterministic Object Key
6. MySQL Transaction：
   Evidence
   ImportBatch
   ImportAttempt(QUEUED)
7. Commit
```

如果 Object 已写但 DB Rollback：

```text
可能留下 Orphan Object
```

因为 Object Key 是 Content-addressed，可安全复用；以后 Cleanup 清理长期未引用对象。

禁止一边 Streaming 大 XLSX，一边持有 DB Transaction。

## 3. ImportAttempt Claim

使用队列式：

```sql
SELECT id
FROM import_attempt
WHERE status='QUEUED'
  AND available_at <= UTC_TIMESTAMP(6)
ORDER BY available_at,id
LIMIT :n
FOR UPDATE SKIP LOCKED;
```

Claim Transaction 只负责：

```text
锁定
RUNNING
lease_owner
lease_until
```

随后立即 Commit，再开始 Parse。

`SKIP LOCKED` 只用于 Queue-like Job Claim，不作为普通一致性捷径。

## 4. Retry

```text
Require IMPORT_RETRY
Idempotency-Key: required nonblank (1..200 chars)
Lock latest ImportAttempt (FOR UPDATE)
→ Lock ImportBatch (FOR UPDATE)
→ Require Batch FAILED | CANCELED（且无活跃 QUEUED/RUNNING Attempt）
→ Insert attempt_no + 1, trigger MANUAL_RETRY, predecessor = latest, QUEUED
→ Batch → PENDING
→ Audit IMPORT_RETRIED（同事务）
→ Commit
```

不删旧 Attempt / RawRecord / Issue。锁顺序固定为 `ImportAttempt → ImportBatch`，
与 worker claim/recovery/finalization 一致，不允许反向。

## 4.1 Cancel

```text
Require IMPORT_CANCEL
Idempotency-Key: required nonblank (1..200 chars)
Lock latest ImportAttempt (FOR UPDATE)
→ Lock ImportBatch (FOR UPDATE)
→ 仅允许 (PENDING, QUEUED) 或 (PROCESSING, RUNNING)
→ Attempt → CANCELED, finished_at=now, lease_owner/until=NULL（保留 started_at/
  计数器/检测字段/lease_version）
→ Batch → CANCELED
→ Audit IMPORT_CANCELED（同事务）
→ Commit
```

Cancel 是协作式的：不中断 Java worker 线程。取消提交后，stale worker 的
lease renew / fenced persistence / finalization 全部命中零行，Batch 保持
CANCELED，永不产生 `Attempt=CANCELED + Batch=PARSED` 之类拆分结果。

## 4.2 命令幂等与审计

Retry / Cancel 都要求 `Idempotency-Key`：

- `api_idempotency.idempotency_key` 存 64 字符小写十六进制
  `SHA-256(exact UTF-8 key)` 指纹（表 collation 大小写不敏感，不能直接存原 key；
  `ABC` 与 `abc` 是不同 key）。
- `request_hash` 独立覆盖 operation + org/actor + ImportBatch id + `{}` body。
- 新 key → provisional 行（`response_status=0`）→ 命令 → 同事务 finalize 为 200 +
  response JSON；回滚则无残留。
- 同 key + 同 hash → 重放已存 200 响应，不重复状态迁移/审计。
- 同 key + 不同 hash → 409 `STATE_CONFLICT`。
- 并发同 key 收敛：`FOR UPDATE` 当前读 winner 的已提交行。
- 并发不同 key 同 Batch 可能触发 MySQL gap-lock/insert-intention 死锁，命令事务
  做有界重试（3 次）后重读 winner 状态，产生正确 409。
- 每个提交的命令恰好写一条 secret-free audit event（`IMPORT_RETRIED` /
  `IMPORT_CANCELED`，subject_type `IMPORT_BATCH`，metadata 仅 id/status）。

## 5. Import Confirm

```text
Lock ImportBatch
Load SUCCEEDED Attempt
Require READY_FOR_REVIEW
Require No Blocking ERROR
Set confirmed_attempt_id
Set CONFIRMED
Audit
Commit
```

Confirm 不直接 Posting Ledger。

## 6. Allocation Confirm（#49，已实现）

```text
Lock Source
Lock Decision
Require DRAFT
Require Source Eligible
Currency Match
SUM(lines)=Source Amount
Target Valid
No Other Current Confirmed
Set CONFIRMED
Update Source.current_allocation_decision_id
Audit
Commit
```

Charge 还必须：

```text
ImportBatch CONFIRMED
review_status = CLEAN
```

实现锁序（全命令一致，避免与 duplicate 工作流死锁）：

```text
idempotency reserve（事务内）
charge_fact FOR UPDATE
allocation_decision FOR UPDATE（需 supersede 的其它 DRAFT 按 id ASC FOR UPDATE）
allocation_line FOR UPDATE（replace-lines 先锁旧行再删插）
audit（secret-free）→ idempotency finalize → commit
外层 bounded deadlock retry ×3
```

Eligibility 失败（import 未 CONFIRMED / lineage 不属于 confirmed attempt /
review_status ≠ CLEAN / EXCLUDED）→ 409 `ALLOCATION_NOT_ELIGIBLE`；sum 不
精确相等 → 409 `ALLOCATION_SUM_MISMATCH`；已有 CONFIRMED → 409
`ALLOCATION_ALREADY_CONFIRMED`；非 DRAFT → 409 `DECISION_NOT_DRAFT`。审计
事件 `ALLOCATION_DECISION_CONFIRMED`（subject CHARGE_FACT），metadata 仅
decisionId/source/ruleId/lineCount/currency。

## 6.1 Duplicate Scan / Keep / Exclude（M3 Group 2）

Scan 是显式 org-level command，但不是长事务：

```text
非锁定读取 org eligible rows（confirmed-attempt lineage, CLEAN/SUSPECTED）
内存分组 + pairwise（EXACT / half-open OVERLAP，strict <，adjacent 不是 overlap）
drafts 按 ≤500 分批
每批独立短事务：
    lock / re-read endpoint charge rows（id ASC）
    endpoint 已 terminal 的 draft 直接丢弃
    insert candidate（同 (org,pair,algorithm) 已存在 → no-op）
    只对 DB 中实际存在 OPEN candidate 的 endpoint 将 CLEAN → SUSPECTED
commit，下一批
```

重扫不会复活 terminal pair 的 SUSPECTED 状态（只有 OPEN 行驱动聚合）。

Keep / Exclude 是幂等事务，锁序与重试：

```text
idempotency reserve / replay（事务内）
candidate FOR UPDATE
受影响 endpoint charges FOR UPDATE（id ASC）
guards（Exclude 还有 duplicate-chain guard：有 inbound 依赖的 charge 不可再被 exclude）
candidate terminal 之后按 DB OPEN 行 reconcile 两端 / 所有受影响端点
audit（secret-free）
idempotency finalize
commit
外层 bounded deadlock retry ×3（只有 MySQL deadlock/serialization loser 重试）
```

Exclude 的 graph cleanup：与 excluded 端相连的其它 OPEN candidate 全部
SUPERSEDED，每个受影响的非 excluded counterpart reconcile 回 CLEAN（若无
剩余 OPEN）。Idempotency-Key 语义与 Import workflow 一致：exact UTF-8 原文
SHA-256 指纹（不 trim，`"abc"` 与 `" abc"` 是不同 caller key），request hash
覆盖 operation/org/actor/candidate(/excluded charge)；same key same hash =
replay 存储的 CandidateSummary，same key different hash = 409。

#49 的 Allocation Confirm 已实现（见 §6）；#50 的 rule version 创建用
`organization FOR UPDATE` 行锁串行化同 org 的 `maxVersion + 1`（无新表），
`UNIQUE(org_id, rule_key, version)` 兜底，并发同 key 创建恰好得到连续版本号。

## 7. API 幂等

优先自然业务键：

```text
Evidence → (org_id,sha256)
Ledger → posting_key
```

没有自然键的 Create Command 要求：

```http
Idempotency-Key: <opaque uuid>
```

MySQL Transaction：

```text
Insert api_idempotency
Duplicate + Same Request Hash → Return Existing
Duplicate + Different Hash → 409
Create Resource
Store Result
Commit
```

不要把唯一 Idempotency Record 放 Redis。

M3 allocation 命令复用同一张 `api_idempotency` 表（无新 schema），operation
前缀：

```text
ALLOCATION_MANUAL_DRAFT    hash 覆盖 chargeId + 规范化 lines（scale-8 金额）
ALLOCATION_CONFIRM         hash 覆盖 decisionId
ALLOCATION_PROPOSAL        hash 覆盖 chargeId
ALLOCATION_RULE_VERSION    hash 覆盖 ruleKey + 规范化 definition
ALLOCATION_RULE_ARCHIVE    hash 覆盖 ruleId
```

PUT replace-lines 是完整替换语义，天然幂等，不强加 key。replay 响应体经
`AllocationResponseCodec` 序列化（金额一律 scale-8 字符串），同 key 回放
byte-stable。

## 8. Budget Commitment Activation

先：

```text
Lock BillingPeriod
Require OPEN
```

公式：

```text
available = total - actual - committed
```

Atomic SQL：

```sql
UPDATE budget
SET committed_amount = committed_amount + :amount,
    version = version + 1
WHERE id = :budgetId
  AND status='ACTIVE'
  AND total_amount - actual_amount - committed_amount >= :amount;
```

`affected rows = 0` 后重新读取，判断是：

```text
Budget Missing
Period/Status Invalid
Insufficient Available
Concurrent Consumption
```

同一 Transaction：

```text
Budget Counter
Commitment REQUESTED→ACTIVE
ApprovalCase→APPROVED
Audit
```

## 9. Provider Charge Posting

Stable Key：

```text
CHARGE:{chargeFactId}:ALLOCATION:{allocationDecisionId}
```

事务按稳定顺序：

```text
读取 Source / Allocation 得到 Period/Scope
→ Lock BillingPeriod
→ Require OPEN
→ Lock Budgets by id
→ Lock Commitments by id
→ Lock Charge / Allocation
→ Revalidate
→ Check Import Confirmed / Charge CLEAN
→ Check/Return Existing posting_key
→ Insert LedgerPosting
→ Insert LedgerEntry
→ Update Budget actual
→ Consume Commitment if linked
→ Audit
→ Commit
```

### 没有 Budget 怎么办

没有匹配 Budget：

```text
Ledger 仍正常入账
```

Budget 是治理工具，不是 Ledger 前置配置。

### 超预算怎么办

真实 Cost 已经发生：

```text
available before = 20
new actual = 30
```

仍然 Posting。

结果：

```text
available = -10
overBudget = true
```

## 10. Commitment Consume

```text
consumed = min(entry_amount, remaining_amount)
```

同事务：

```text
commitment.remaining -= consumed
budget.committed -= consumed
budget.actual += full entry_amount
insert budget_commitment_usage
```

Entry 超过 Remaining 的部分作为 Uncommitted Actual，也必须保留。

## 11. Expense Posting

前置：

```text
Expense APPROVED
Allocation CONFIRMED
Period OPEN
```

Key：

```text
EXPENSE:{expenseClaimId}
```

Posting 与 Claim `APPROVED→POSTED` 同事务。

## 12. Correction

禁止：

```sql
UPDATE ledger_entry ...
```

Correction 通过：

```text
Historical Target
→ Choose OPEN Correction Period
→ Lock Correction Period
→ Lock Affected Budgets
→ Insert CorrectionGroup
→ New LedgerPosting
→ Reversal / Replacement Entries
→ Update Budget Actual
→ Audit
→ Commit
```

CLOSED 历史 Period 不被静默改写。

## 13. Period Close

启动：

```text
Lock Period
Require OPEN
Set CLOSING
generation++
Insert CloseRun
Audit
Commit
```

Check 在长事务外执行。

Finalize：

```text
Lock Period
Require CLOSING
Require Current Run No FAIL
Recheck Critical Blockers
Set CLOSED
Audit
Commit
```

因为 CLOSING 已阻止普通写入，Check 不会和新 Posting Race。

## 14. Reopen

```text
Lock CLOSED Period
Require PERIOD_REOPEN
Require Reason
Set OPEN
Audit
Commit
```

旧 Close Run 保留。

## 15. Reconciliation Resolve

只更新 Case 状态 / Reason / Note。

如果要改金额：

```text
另起 Correction Command
```

## 16. Refresh Session Race

Cookie：

```text
sessionId.secret
```

Redis 存：

```text
current_hash
previous_hash
previous_valid_until
security_version
```

Lua 原子结果：

```text
CURRENT_MATCH → ROTATE
PREVIOUS_MATCH within race window → AUTH_REFRESH_RACE
old/unrecognized → AUTH_REFRESH_REPLAY
```

Same-tab 前端用 Single-flight Refresh。

Cross-tab 收到 Race 后短暂等待并 Retry once。

## 17. Lock Order

多资源事务按固定顺序：

```text
BillingPeriod
→ Budget sorted by id
→ Commitment sorted by id
→ Source
```

Charge 相关工作流（duplicate review + allocation）统一 `charge id ASC` 先锁
charge，再锁候选/decision（按 id ASC）/lines，与 duplicate Keep/Exclude 的
endpoint 锁序一致，避免跨工作流死锁：

```text
idempotency reserve
→ affected charge_fact FOR UPDATE（sorted id）
→ duplicate_candidate / allocation_decision FOR UPDATE
→ allocation_line FOR UPDATE
→ organization FOR UPDATE（仅 rule version 创建，串行化 maxVersion+1）
```

Deadlock 只允许 Bound Retry + Jitter，不无限重试。

## 18. Isolation

先使用 InnoDB 默认 Isolation。

正确性主要依赖：

```text
Explicit Lock
Unique Constraint
Conditional Update
```

而不是“默认隔离级别应该够”。

## 19. Failure Matrix

| Failure | 预期 |
|---|---|
| Redis Down during Ledger | 财务状态不损坏 |
| MySQL Rollback | 无半 Ledger/Budget |
| MinIO Down | Evidence 不确认 |
| Worker Crash | Lease Recovery |
| Duplicate Post | 返回同一 Posting |
| Budget Race | 只有合法 Commitment Active |
| Close Race | CLOSING 阻止普通写 |
