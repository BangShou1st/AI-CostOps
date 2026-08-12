# 03. 状态机设计

所有状态变化必须经过 Application Command：

```text
Current State Check
+ Authorization
+ Transaction
+ Audit（需要时）
```

## 1. ImportBatch

```text
UPLOADED
→ PROCESSING
→ READY_FOR_REVIEW
→ CONFIRMED

PROCESSING
→ FAILED
→ Retry → PROCESSING

READY_FOR_REVIEW / FAILED
→ CANCELED
```

规则：

- Failed Attempt / RawRecord 保留；
- Retry 创建 `attempt_no + 1`；
- `CONFIRMED` 固定 `confirmed_attempt_id`；
- Parser 行为变化后不能原地重跑，使用新 Parser Version / ImportBatch。

## 2. ImportAttempt

```text
QUEUED
→ RUNNING
→ SUCCEEDED / FAILED
```

如果：

```text
RUNNING + lease_until < now
```

可 Recovery / Requeue。

## 3. Charge Review

```text
CLEAN
→ SUSPECTED_DUPLICATE
→ CLEAN
or
→ EXCLUDED_DUPLICATE

CLEAN
→ EXCLUDED_NONCOST
```

`SUSPECTED_DUPLICATE` 不能 Posting。

## 4. AllocationDecision

```text
DRAFT
→ CONFIRMED

DRAFT
→ SUPERSEDED

CONFIRMED
→ SUPERSEDED
```

最后一种只允许在尚未 Ledger Posting 前。

已经入账后要改归属：

```text
Correction
```

## 5. ExpenseClaim

```text
DRAFT
→ SUBMITTED
→ NEEDS_INFO
→ SUBMITTED

SUBMITTED
→ APPROVED / REJECTED / CANCELED

APPROVED
→ POSTED
or
→ VOIDED（Posting 前）
```

`POSTED` 为终态。

## 6. ApprovalCase

```text
PENDING
→ NEEDS_INFO
→ PENDING

PENDING
→ APPROVED / REJECTED / CANCELED
```

所有 Action Append-only。

## 7. BudgetCommitment

```text
REQUESTED
→ ACTIVE
→ PARTIALLY_CONSUMED
→ CONSUMED

ACTIVE / PARTIALLY_CONSUMED
→ RELEASED

REQUESTED
→ REJECTED / CANCELED
```

关键规则：

> 人工点 Approve 与 Budget Atomic Activation 必须在同一财务事务中成功。

如果并发导致 Available 已不足：

```text
409 BUDGET_INSUFFICIENT
```

不能出现 ApprovalCase 已 APPROVED、Commitment 却没占预算的半状态。

## 8. ReconciliationRun

```text
CREATED
→ RUNNING
→ COMPLETED / FAILED
```

失败后重新执行创建新 Run，保留历史。

## 9. ReconciliationCase

```text
OPEN
→ INVESTIGATING
→ RESOLVED
```

也允许 `INVESTIGATING → OPEN`。

Resolve 需要：

```text
reason_code
resolution_note
actor
time
```

Resolve 不等于自动改 Ledger。

## 10. BillingPeriod

```text
OPEN
→ CLOSING
→ CLOSED

CLOSING
→ OPEN（Blocked / Cancel / Failure）

CLOSED
→ OPEN（Privileged Reopen）
```

### OPEN

正常 Posting / Commitment。

### CLOSING

阻止新的普通财务写入，保证 Close Check 不和新账并发。

### CLOSED

普通写入拒绝。

Late Data：

```text
Correction 到 Open Period
或
Privileged Reopen
```

由业务策略决定。

### Reopen

要求：

```text
PERIOD_REOPEN
Reason
Audit
close_generation++
```

旧 Close Run 不删除。

## 11. CorrectionGroup

```text
DRAFT
→ POSTED
or
→ CANCELED
```

POSTED Correction 也不可变。

## 12. 重复 Command 怎么返回

语义幂等：

```text
同一个 Import 已经 Confirmed
```

可以返回当前结果。

语义冲突：

```text
Approve REJECTED Claim
Post CLOSED Period
Confirm SUPERSEDED Allocation
```

返回：

```text
409 STATE_CONFLICT
```

并带 Current State。
