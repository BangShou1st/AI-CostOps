# 02. Milestone 验收证据矩阵

| Milestone | 必须证明 | 主要证据 |
|---|---|---|
| M0 | 两个人可独立 Bootstrap；CI Gate 生效 | Clean Checkout Build、Compose Health、Required Check |
| M1 | Auth / Scope / Session 正确 | Login/Refresh/Replay/Rate-limit/Scope Integration Test |
| M2 | Evidence / Import 可恢复、可追溯 | MinIO Test、Worker Claim/Lease Recovery、Provider Fixture |
| M3 | Canonical Fact 与 Allocation 正确 | Normalize Test、Duplicate Review、Exact Sum Test |
| M4 | Expense / Budget 正确 | State Test、100 Concurrent Commitment Test |
| M5 | Ledger 不重复、不半写、可纠错 | Posting Idempotency、Rollback、Correction、Lineage |
| M6 | Reconciliation / Close 正确 | Mismatch Case、Blocker Test、Close/Write Race |
| M7 | 两条业务主链路完整 | Provider E2E、Expense E2E、Workbench |
| M8 | 失败与规模证据完整 | 100k/500k/1m、Redis/MinIO Failure、Compose Smoke、Security Review |

## M0

证据：

```text
Backend Build
Frontend Build
Flyway Clean DB
MySQL/Redis/MinIO Health
Docker Build
两个人独立 Clone/Run
Required Status Check 真能 Block Merge
```

## M1

证据：

```text
Wrong Password
Disabled User
Rate Limit
Refresh Rotation
Cross-tab Race
Replay
Logout All
Password Reset
Wrong Scope
Permission Cache Invalidation
```

## M2

五类 Provider Evidence 必须区分真实程度。

证据：

```text
DeepSeek Sanitized
Kimi Sanitized
GLM Sanitized
MiMo Sanitized
OpenAI Observed Empty CSV
OpenAI Official Usage/Costs JSON Synthetic
```

并测试：

```text
Schema Drift
Unknown Column
Missing Required
Lease Recovery
Retry History
```

## M3

```text
Consumption != Pricing != Charge
No Invented Price
No Total/Component Double Count
Duplicate Candidate != Auto Duplicate
Allocation Exact Sum
```

## M4

Budget 强制：

```text
available
= total
- actual
- outstanding commitments
```

以及：

```text
100 Concurrent Commitment Attempts
→ 不 Oversubscribe
```

已发生 Cost 超预算仍 Posting。

## M5

必须证明：

```text
Duplicate Request 不重复 Posting
MySQL Rollback 不留下半 Ledger/Budget
Original LedgerEntry 不可改
Correction 追加
Lineage → Evidence
```

## M6

必须证明：

```text
OPEN→CLOSING
后普通 Financial Write 被拒绝

Blocker 全部 PASS
才 CLOSED
```

Reopen 必须 Permission + Reason + Audit。

## M7

Provider 主链路：

```text
Upload
→ Import
→ Confirm
→ Allocate
→ Post
→ Reconcile
→ Close
```

Expense 主链路：

```text
Claim
→ Evidence
→ Submit
→ Approve
→ Allocate
→ Post
→ Close
```

## M8

真实执行：

```text
100k
500k
1m
```

报告：

```text
Duration
Throughput
Memory
Core Query
EXPLAIN
Known Limitations
```

不把“设计目标”写成“实测性能”。
