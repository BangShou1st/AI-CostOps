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
Lock ImportBatch
→ Require FAILED
→ Insert attempt_no + 1
→ QUEUED
→ Commit
```

不删旧 Attempt / RawRecord / Issue。

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

## 6. Allocation Confirm

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
