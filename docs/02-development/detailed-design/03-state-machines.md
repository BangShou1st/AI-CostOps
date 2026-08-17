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
PENDING ─claim─▶ PROCESSING ─finalization 单事务─▶ READY_FOR_REVIEW ─confirm─▶ CONFIRMED（终态）
   │               │
   │               ├─▶ FAILED（worker 异常/ERROR 耗尽/SCHEMA_INCOMPATIBLE）
   │               └─▶ CANCELED（仅 PROCESSING+RUNNING）
   └──────（仅 PENDING+QUEUED 可 cancel）
FAILED / CANCELED ─retry─▶ PENDING（新 MANUAL_RETRY attempt）
```

规则：

- Cancel 仅 `(PENDING,QUEUED)` 或 `(PROCESSING,RUNNING)`；**READY_FOR_REVIEW 不可
  cancel/retry**，唯一出口 CONFIRMED。
- `CONFIRMED` 是终态：不可 cancel/retry；重复 confirm 同一 attempt（任意新 key）
  → 幂等成功（无第二条 audit）。
- `PARSED` 仅历史遗留值（M2 数据），不可 confirm。
- 成功态措辞：**M3 normal success path 由同一 finalization transaction 原子
  产生 `SUCCEEDED` Attempt + `READY_FOR_REVIEW` Batch**——不存在
  `SUCCEEDED ⇔ READY_FOR_REVIEW` 等号，兼容历史 M2 `SUCCEEDED + PARSED` 数据。
- Failed Attempt / RawRecord 保留；Retry 创建 `attempt_no + 1`；
- `CONFIRMED` 固定 `confirmed_attempt_id`；
- Parser 行为变化后不能原地重跑，使用新 Parser Version / ImportBatch。
- **Import Confirm ≠ Ledger Posting。**

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

### 3.1 DuplicateCandidate（M3 Group 2，V9）

```text
OPEN → KEPT_CLEAN
OPEN → CONFIRMED_DUPLICATE
OPEN → SUPERSEDED
```

三种右侧状态 terminal。candidate 状态与 pair identity
`(org, charge_fact_id < matched_charge_id, algorithm_version)` 绑定：同
algorithm 重扫 terminal pair 是 no-op，不重开；未来 algorithm v2 可对同一
pair 开新 candidate 而不覆盖 v1 的 reviewer history。

`charge_fact.review_status` 是 candidate 的 materialized aggregate：

```text
EXCLUDED_DUPLICATE / EXCLUDED_NONCOST   terminal，不参与聚合
otherwise:
    存在 OPEN candidate  ⇒ SUSPECTED_DUPLICATE
    不存在 OPEN candidate ⇒ CLEAN
```

每次 scan / keep / exclude 都在 candidate terminal 之后基于 DB 的 OPEN 行
reconcile，而不是假设一次 UPDATE 永远正确。

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

M3 Group 3（#49/#50）已实现 transition 事务：

```text
DRAFT → CONFIRMED   Confirm 事务：charge FOR UPDATE → decision FOR UPDATE
                   → lines FOR UPDATE → 校验 lineage/currency/exact-sum/
                   target → status CONFIRMED → pointer mutation → audit
DRAFT → SUPERSEDED  Manual override（新 MANUAL draft 创建时）与 changed
                   winning rule proposal（新 RULE draft 创建时）；lines 与
                   trace 保留，history 不消失
```

CONFIRMED 后不可改写：edit 只允许 MANUAL+DRAFT，confirm 只允许 DRAFT；
competing confirm 由 `UNIQUE(confirmed_charge_fact_id)` + charge 锁兜底，
另一 decision 保持 DRAFT（不会被隐式 SUPERSEDED）。

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
