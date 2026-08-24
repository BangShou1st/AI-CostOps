# 06. V1 发布验收

只有证据齐全，才允许创建 `v1.0.0`。

> 状态说明：`[x]` = 自动化证据已验证，`[ ]` = 待人工 sign-off。

## A. Repository / Build

```text
[x] main 保护仍有效
[x] Required CI 全绿
[x] 没有绕过 Review 的正式代码
[x] Clean Checkout 后端可构建
[x] Clean Checkout 前端可构建
[x] Docker Image 可构建
[x] Full Compose 可启动
[x] 空 MySQL 可从 Flyway 迁移到最新版本
```

证据：v1-release-candidate-evidence.md Section 4（437 unit + 34 architecture + 795 integration + 420 frontend tests PASS），AIC-071 Compose smoke PASS。

## B. IAM / Security

```text
[x] Registration / Invitation 按配置工作
[x] Login
[x] Disabled Account 被拒绝
[x] Login Rate Limit
[x] Refresh Rotation
[x] Cross-tab Refresh Race
[x] Replay Rejected
[x] Logout / Logout All
[x] Password Reset 使旧 Session 失效
[x] Permission + Scope Integration Tests
[x] SYSTEM_ADMIN 不自动拥有 Finance 权限
[x] Raw Evidence Download 权限正确
[x] Repo / Log 无 Secret
```

证据：Backend integration tests（795 PASS），M1 E2E authentication evidence，AIC-070 security review。

## C. Provider Import

```text
[x] DeepSeek Fixture
[x] Kimi Fixture
[x] GLM Fixture
[x] MiMo Fixture
[x] OpenAI Observed Empty CSV Fixture
[x] OpenAI Official Usage API JSON Fixture
[x] OpenAI Official Costs API JSON Fixture
[x] Unknown Column Test
[x] Missing Required Test
[x] Schema Fingerprint / Parser Version
[x] Failed Attempt 保留
[x] Retry
[x] Lease Recovery
[x] 同 Evidence 不重复创建正式业务结果
```

证据：M2 provider adapter tests，M7 E2E provider import，AIC-067 import benchmark，AIC-069 runtime failure injection。

## D. Canonical Cost / Attribution

```text
[x] Consumption / Pricing / Charge 区分
[x] 不猜 Price
[x] Total / Component 不 Double Count
[x] Duplicate Candidate Review
[x] Provider Identity 只是 Hint
[x] Allocation Exact Sum
[x] Unallocated Charge 不能 Posting
```

证据：M2-M3 implementation tests，M7 E2E expense integration。

## E. Budget

```text
[x] available = total - actual - outstanding commitment
[x] Money 使用 BigDecimal / DECIMAL
[x] 100 并发 Commitment 测试
[x] Commitment 路径无 Oversubscription
[x] Partial / Full Consume
[x] Release
[x] 已发生超预算 Cost 仍入账
[x] Over-budget 可见
```

证据：M5 implementation tests，AIC-068 financial concurrency test（100 concurrent requests, 10 ACTIVE, 90 REQUESTED）。

## F. Ledger

```text
[x] Stable posting_key
[x] Duplicate Post 幂等
[x] POSTED Entry 不可变
[x] 无 Generic Destructive Update API
[x] Provider Charge Posting
[x] Expense Posting
[x] Rollback 不留下半状态
[x] Correction 追加
[x] CLOSED 历史不静默改写
[x] Lineage 可追到 Evidence
```

证据：M5 immutable ledger implementation，AIC-068 financial concurrency test。

## G. Reconciliation / Close

```text
[x] Matched
[x] Missing Internal / External
[x] Amount Mismatch
[x] Explicit Tolerance
[x] Case Resolution
[x] 所有 Close Blocker
[x] Material Open Case 阻止 Close
[x] CLOSING 阻止普通财务写
[x] CLOSED 拒绝普通 Posting
[x] Reopen 需要权限 + Reason + Audit
[x] Close / Write Race Test
```

证据：M6 reconciliation/close implementation，AIC-068 close/post fence test，Browser UAT：7/7 close blockers PASS，Period Close PASS，CLOSED write protection 409 PASS，Reopen PASS。

## H. Frontend

```text
[x] Login / Session
[x] Evidence / Import
[x] Cost / Duplicate / Allocation
[x] Expense / Approval
[x] Budget
[x] Ledger / Lineage
[x] Reconciliation / Close
[x] Workbench
[x] Permission-aware Actions
[x] Backend 仍是授权 Truth
[x] Money / Currency 展示正确
[x] 无跨币种假 Total
```

证据：Frontend Vitest 420 tests PASS，Browser UAT 32/32 scenarios PASS。

## I. Docker / Failure

```text
[x] MySQL Health
[x] Redis Health
[x] MinIO Health
[x] Backend Health
[x] Frontend HTTP
[x] Redis Restart 不破坏财务 Truth
[x] MinIO Unavailable Path
[x] Worker Crash Recovery
[x] Compose Smoke 可复现
```

证据：AIC-071 Compose smoke PASS（fresh volume + restart persistence），AIC-069 runtime failure injection。

## J. Performance

Synthetic Dataset：

```text
64 / 256 / 1,024 rows
```

记录：

```text
Import Throughput: 111–215 rows/s E2E
Worker Throughput: 149–229 records/s
```

设计目标：

```text
500k Import 不 OOM — 未实测（Known Limitation）
```

证据：AIC-067 import benchmark。达不到的设计目标已在 Known Limitations 中诚实记录。

## K. 文档真实性

README 已区分：

```text
真实 Provider Schema
官方公开 Schema
Synthetic Data
实测 Benchmark
设计目标
```

未声称：

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
财务正确性 Bug      — 0 found
Auth Bypass          — 0 found
Secret Leak          — 0 found
Data Loss            — 0 found
Duplicate Posting    — 0 found
Budget Oversubscription — 0 found
Closed Period Mutation — 0 found (409 PERIOD_NOT_OPEN verified)
main CI 无法工作     — CI green
```

### P1 — 正常应修

```text
核心 V1 Workflow 不可用 — 0 found
要求支持的 Provider Fixture 失败 — 0 found
严重阻断 UX          — 0 found
Compose 不可靠       — Compose smoke PASS
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
