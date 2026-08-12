# 06. V1 发布验收

只有证据齐全，才允许创建 `v1.0.0`。

## A. Repository / Build

```text
[ ] main 保护仍有效
[ ] Required CI 全绿
[ ] 没有绕过 Review 的正式代码
[ ] Clean Checkout 后端可构建
[ ] Clean Checkout 前端可构建
[ ] Docker Image 可构建
[ ] Full Compose 可启动
[ ] 空 MySQL 可从 Flyway 迁移到最新版本
```

## B. IAM / Security

```text
[ ] Registration / Invitation 按配置工作
[ ] Login
[ ] Disabled Account 被拒绝
[ ] Login Rate Limit
[ ] Refresh Rotation
[ ] Cross-tab Refresh Race
[ ] Replay Rejected
[ ] Logout / Logout All
[ ] Password Reset 使旧 Session 失效
[ ] Permission + Scope Integration Tests
[ ] SYSTEM_ADMIN 不自动拥有 Finance 权限
[ ] Raw Evidence Download 权限正确
[ ] Repo / Log 无 Secret
```

## C. Provider Import

```text
[ ] DeepSeek Fixture
[ ] Kimi Fixture
[ ] GLM Fixture
[ ] MiMo Fixture
[ ] OpenAI Observed Empty CSV Fixture
[ ] OpenAI Official Usage API JSON Fixture
[ ] OpenAI Official Costs API JSON Fixture
[ ] Unknown Column Test
[ ] Missing Required Test
[ ] Schema Fingerprint / Parser Version
[ ] Failed Attempt 保留
[ ] Retry
[ ] Lease Recovery
[ ] 同 Evidence 不重复创建正式业务结果
```

## D. Canonical Cost / Attribution

```text
[ ] Consumption / Pricing / Charge 区分
[ ] 不猜 Price
[ ] Total / Component 不 Double Count
[ ] Duplicate Candidate Review
[ ] Provider Identity 只是 Hint
[ ] Allocation Exact Sum
[ ] Unallocated Charge 不能 Posting
```

## E. Budget

```text
[ ] available = total - actual - outstanding commitment
[ ] Money 使用 BigDecimal / DECIMAL
[ ] 100 并发 Commitment 测试
[ ] Commitment 路径无 Oversubscription
[ ] Partial / Full Consume
[ ] Release
[ ] 已发生超预算 Cost 仍入账
[ ] Over-budget 可见
```

## F. Ledger

```text
[ ] Stable posting_key
[ ] Duplicate Post 幂等
[ ] POSTED Entry 不可变
[ ] 无 Generic Destructive Update API
[ ] Provider Charge Posting
[ ] Expense Posting
[ ] Rollback 不留下半状态
[ ] Correction 追加
[ ] CLOSED 历史不静默改写
[ ] Lineage 可追到 Evidence
```

## G. Reconciliation / Close

```text
[ ] Matched
[ ] Missing Internal / External
[ ] Amount Mismatch
[ ] Explicit Tolerance
[ ] Case Resolution
[ ] 所有 Close Blocker
[ ] Material Open Case 阻止 Close
[ ] CLOSING 阻止普通财务写
[ ] CLOSED 拒绝普通 Posting
[ ] Reopen 需要权限 + Reason + Audit
[ ] Close / Write Race Test
```

## H. Frontend

```text
[ ] Login / Session
[ ] Evidence / Import
[ ] Cost / Duplicate / Allocation
[ ] Expense / Approval
[ ] Budget
[ ] Ledger / Lineage
[ ] Reconciliation / Close
[ ] Workbench
[ ] Permission-aware Actions
[ ] Backend 仍是授权 Truth
[ ] Money / Currency 展示正确
[ ] 无跨币种假 Total
```

## I. Docker / Failure

```text
[ ] MySQL Health
[ ] Redis Health
[ ] MinIO Health
[ ] Backend Health
[ ] Frontend HTTP
[ ] Redis Restart 不破坏财务 Truth
[ ] MinIO Unavailable Path
[ ] Worker Crash Recovery
[ ] Compose Smoke 可复现
```

## J. Performance

Synthetic Dataset：

```text
100k
500k
1m Facts
```

记录：

```text
Import Throughput
Duration
Memory Peak
核心 Query
Index Review
```

设计目标：

```text
500k Import 不 OOM
```

达不到就修，或者在 Release Notes 诚实写实际限制。

## K. 文档真实性

README 必须区分：

```text
真实 Provider Schema
官方公开 Schema
Synthetic Data
实测 Benchmark
设计目标
```

禁止声称：

```text
真实企业生产验证
FOCUS Compliance
所有 Provider
百万级高并发
未实测性能提升
```

## L. Release Blocker

### P0 — v1.0.0 前必须修

```text
财务正确性 Bug
Auth Bypass
Secret Leak
Data Loss
Duplicate Posting
Budget Oversubscription
Closed Period Mutation
main CI 无法工作
```

### P1 — 正常应修

```text
核心 V1 Workflow 不可用
要求支持的 Provider Fixture 失败
严重阻断 UX
Compose 不可靠
```

### P2 — 可记录后延期

```text
UI Polish
可选图表
非核心 Filter
超出目标的性能优化
```

## M. Release

满足后：

```text
main
→ tag v1.0.0
→ GitHub Release
```
