# 02. V1 Issue Backlog

> 稳定计划编号：`AIC-001` ～ `AIC-073`。
> GitHub Issue # 由仓库创建时自动分配。

原则：

```text
一个 Issue
→ 一个清晰业务/技术变化
→ 一个主要 PR
→ 必要测试
→ Peer Review
```

不要一次性把 73 个 Issue 全建出来。建议只创建**当前 Milestone + 下一个 Milestone**，避免 Board 噪音。

---

# M0 — Repository Foundation

## AIC-001 — 建立仓库治理文件

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `chore/repository-governance`
**Depends：** 设计包通过审查

实现：

```text
.gitignore
.gitattributes
.editorconfig
CONTRIBUTING.md
PR Template
Issue Template
```

双方 GitHub 用户名确定后再加 `CODEOWNERS`。

验收：

```text
.env / target / node_modules 可正确 ignore
没有 Secret
没有真实 Provider 原始文件
```

---

## AIC-002 — Bootstrap Spring Boot 后端

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `chore/backend-bootstrap`
**Depends：** AIC-001

建立：

```text
Java 21
Spring Boot 4.1
Maven Wrapper
Spring Web / Validation / Security
Plain MyBatis
Flyway
MySQL Connector
Actuator
Testcontainers
```

只做工程骨架，不提前写业务 CRUD。

验收：

```text
Clean Checkout
→ ./mvnw test
→ ApplicationContext 启动成功
```

---

## AIC-003 — Bootstrap React 前端

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `chore/frontend-bootstrap`
**Depends：** AIC-001

建立：

```text
React 19
TypeScript
Vite
React Router
TanStack Query
Ant Design
ECharts
Axios
ESLint
Vitest
React Testing Library
```

验收：

```text
npm ci
npm run lint
npm test
npm run build
```

---

## AIC-004 — 建立 MySQL / Redis / MinIO Compose 基础

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `chore/compose-infrastructure`
**Depends：** AIC-001

创建：

```text
compose.yaml
compose.dev.yaml
.env.example
Healthcheck
Named Volume
```

首批服务：

```text
mysql
redis
minio
```

验收：全新 Volume 下全部 Healthy。

---

## AIC-005 — 建立后端共享基础约定

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/backend-shared-foundation`
**Depends：** AIC-002

只实现真正稳定的基础能力：

```text
Money / CurrencyCode
BIGINT ID → JSON String
ProblemDetail
Pagination
UTC Time Policy
Clock（确有需要时）
```

禁止形成万能 `common/service/utils`。

测试：Money、ID、Error Serialization。

---

## AIC-006 — 建立 MySQL / Flyway / Testcontainers 基线

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `test/mysql-integration-foundation`
**Depends：** AIC-002, AIC-004

建立：

```text
Flyway Baseline
MySQL Testcontainer
Redis Integration Test Container
Test Profile
```

不使用 H2 模拟 MySQL 财务事务。

验收：空 MySQL 可自动 Migration 并启动测试上下文。

---

## AIC-007 — 建立前端 App / Auth / API 基础设施

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/frontend-app-foundation`
**Depends：** AIC-003, AIC-005

实现：

```text
Router Shell
QueryClient
Axios Client
ProblemDetail Mapper
Public / Protected Route Shell
内存 Access Token Store
```

暂不接真实 Login。

---

## AIC-008 — 建立 GitHub Actions 基线

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `chore/ci-baseline`
**Depends：** AIC-002, AIC-003, AIC-006

稳定 Check 名：

```text
backend-unit
backend-integration
backend-architecture
frontend-lint
frontend-test
frontend-build
docker-build
```

验收：每个 Check 至少在真实 PR 上成功出现一次。

---

## AIC-009 — 为 main 开启 Required Status Checks

**Owner：** Dev B
**Reviewer：** Dev A
**类型：** Repository Settings Checkpoint
**Depends：** AIC-008

把稳定 Check 加入 `Protect main`。

同步 `CONTRIBUTING.md`。

验收：任一 Required Check 失败时 PR 无法 Merge。

---

## AIC-010 — 建立 Backend / Frontend Docker Image 与完整 Compose

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `chore/docker-app-images`
**Depends：** AIC-002, AIC-003, AIC-004

实现：

```text
backend/Dockerfile
frontend/Dockerfile
Nginx Same-origin /api Proxy
Full Compose
```

验收：

```text
docker compose build
docker compose up -d
Frontend 200
Backend Health OK
```

---

# M1 — Identity & Organization

## AIC-011 — 建立 IAM / Organization / Early Audit Schema

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/iam-organization-schema`
**Depends：** AIC-006

创建：

```text
organization
app_user
user_credential
organization_member
role
permission
role_permission
role_assignment
invitation
cost_center
team
team_member
project
project_member
provider_account
audit_event
api_idempotency
```

测试：空库 Migration、Unique/FK、核心索引。

---

## AIC-012 — Seed V1 Role / Permission

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/iam-permission-seed`
**Depends：** AIC-011

按权限矩阵写 Reference Data Migration。

测试：Role/Permission Mapping 与设计一致。

---

## AIC-013 — 实现 Registration / Invitation

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/iam-registration`
**Depends：** AIC-011, AIC-012

实现：

```text
Demo Public Registration Flag
Enterprise Invitation Accept
Password Hash
Normalized Email
```

V1 可用 Dev Mail Sink，不强制真实 SMTP。

测试：重复邮箱、邀请过期、关闭公开注册。

---

## AIC-014 — 实现密码登录与 Rate Limit

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/iam-login`
**Depends：** AIC-013, Redis

实现：

```text
Credential Verify
Account Status
IP / Account Fixed-window Rate Limit
Short-lived Access JWT
Audit
```

测试：成功、错误密码、Disabled、限流、Redis 不可用时 Fail-closed。

---

## AIC-015 — 实现 Redis Refresh Session Rotation

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/iam-refresh-session`
**Depends：** AIC-014

实现：

```text
HttpOnly Refresh Cookie
Token Hash
Rotation
Previous-token Race Window
Logout
Logout-all
```

测试：过期、Rotation、Replay、多 Tab Race、Redis Restart。

---

## AIC-016 — 实现 Password Reset 与 Security Version

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/iam-password-reset`
**Depends：** AIC-015

测试：

```text
Reset Token 单次使用
TTL
旧 Session 失效
Disabled Account
```

---

## AIC-017 — 实现 Permission / Data Scope Authorization

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/iam-authorization-scope`
**Depends：** AIC-011, AIC-012

实现：

```text
ORG
PROJECT
TEAM
COST_CENTER
Permission Context
Redis Short Cache
Security Version Invalidation
```

测试：Wrong Role、Wrong Scope、Cache Invalidation、SYSTEM_ADMIN 与 Finance 分离。

---

## AIC-018 — 实现 Organization / Project / Team / CostCenter API

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/organization-master-data`
**Depends：** AIC-011, AIC-017

主数据使用 Archive / Disable，不删除历史引用。

测试：Code Unique、Membership、Scope Authorization。

---

## AIC-019 — 实现前端 Auth / Session Flow

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/frontend-auth`
**Depends：** AIC-014, AIC-015, AIC-007

实现：

```text
Login
Register
Invitation
Forgot / Reset
Bootstrap Refresh
Single-flight Refresh
Logout
```

测试：Session Expired、401 Retry、无无限 Refresh Loop。

---

## AIC-020 — 实现 Admin / Project Settings 前端

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/frontend-organization-admin`
**Depends：** AIC-017, AIC-018

页面：

```text
Users
Roles
Projects
Teams
Cost Centers
Provider Accounts
```

---

# M2 — Evidence & Import

## AIC-021 — 建立 Evidence / Import Schema

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/import-schema`
**Depends：** AIC-006, AIC-011

表：

```text
evidence
import_batch
import_attempt
raw_provider_record
import_issue
```

测试：Attempt Unique、Queue Index、FK。

---

## AIC-022 — 实现 S3-compatible Evidence Storage

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/evidence-storage`
**Depends：** AIC-004, AIC-021

实现：

```text
ObjectStoragePort
MinIO Adapter
Upload Limit
SHA-256
Deterministic Object Key
Authorized Download
```

测试：重复文件、MinIO Failure、无权限 Download、大文件上传时不持有长 DB Transaction。

---

## AIC-023 — 实现 DB-backed Import Worker

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/import-worker`
**Depends：** AIC-021, AIC-006

实现：

```text
FOR UPDATE SKIP LOCKED
Lease
Worker Identity
Retry / Recovery
TaskExecutor
```

测试：双 Worker Claim、Lease Expiry、Crash Recovery。

---

## AIC-024 — 实现 ProviderAdapter Registry / Schema Inspection

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/provider-adapter-framework`
**Depends：** AIC-023

统一：

```text
providerCode
parserVersion
inspect
parse
normalize
schemaFingerprint
ERROR / WARN
```

测试：Adapter Selection、Unknown Schema、Parser Version。

---

## AIC-025 — 实现 DeepSeek Sanitized Fixture / Adapter

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/provider-deepseek`
**Depends：** AIC-024

严格基于观察到的 ZIP/CSV Schema。

测试：

```text
cost.csv
amount.csv
Empty Fixture
Unknown Column
Missing Required
API Key Mask
```

---

## AIC-026 — 实现 MiMo Fixture / Adapter

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/provider-mimo`
**Depends：** AIC-024

测试：

```text
Populated Model Usage
Empty Plugin Sheet
Token Component
Total / Component 不 Double Count
```

---

## AIC-027 — 实现 Kimi Fixture / Adapter

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/provider-kimi`
**Depends：** AIC-024

测试：Bill Summary、Recharge/Promotional 字段保留、不伪造 FOCUS Credit Mapping。

---

## AIC-028 — 实现 GLM Fixture / Adapter

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/provider-glm`
**Depends：** AIC-024

测试：Billing Summary、Settlement 字段保留、不猜公式。

---

## AIC-029 — 实现 OpenAI Observed CSV + Official API JSON Fixture

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/provider-openai`
**Depends：** AIC-024

必须分开：

```text
ObservedEmptyCsvFixture
OfficialUsageApiJsonFixture
OfficialCostsApiJsonFixture
```

禁止制造“真实非空 CSV 字段”。

---

## AIC-030 — 实现 Import Review / Retry / Cancel API

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/import-workflow-api`
**Depends：** AIC-022 ～ AIC-029

实现 Import Attempt 可视化与 Retry/Cancel；最终 Confirm 在 Canonical Normalization 完成后执行。

测试：失败 Attempt 保留、Retry 新建 Attempt、Cancel 状态冲突。

---

## AIC-031 — 实现 Evidence / Import React 工作流

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/frontend-imports`
**Depends：** AIC-030

页面：

```text
Evidence List / Detail
Import List / Detail
Attempts
Issues
Raw Records
Polling
Retry / Cancel
```

---

# M3 — Canonical Cost & Attribution

## AIC-032 — 建立 Canonical Cost Schema

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/cost-schema`
**Depends：** AIC-021

表：

```text
external_document
consumption_fact
pricing_fact
charge_fact
attribution_hint
```

测试：Precision、Fact Unique、Index。

---

## AIC-033 — 实现 Canonical Normalization 与 Import Confirm

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/cost-normalization`
**Depends：** AIC-024, AIC-032

实现：

```text
Raw Record
→ Bounded Batch Normalize
→ Facts
→ READY_FOR_REVIEW
→ Confirm
```

测试：不使用 Float、Source Lineage、Total/Component 不 Double Count、Confirm 幂等。

---

## AIC-034 — 实现 Duplicate / Overlap Candidate Workflow

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/cost-duplicate-review`
**Depends：** AIC-033

状态：

```text
SUSPECTED_DUPLICATE
→ UNIQUE
or
→ EXCLUDED_DUPLICATE
```

Fingerprint 只做候选，不做万能唯一键。

---

## AIC-035 — 建立 Attribution Schema / Rule Version

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/attribution-schema`
**Depends：** AIC-032, AIC-018

表：

```text
allocation_rule
allocation_decision
allocation_line
```

测试：Rule Version、Target Validation。

---

## AIC-036 — 实现 Allocation Proposal / Manual Confirm

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/attribution-workflow`
**Depends：** AIC-033, AIC-035

测试：

```text
Line Sum = Source Amount
Currency Match
Target Exists
One Current Confirmed Decision
Unclean Charge 不可 Confirm
```

---

## AIC-037 — 实现 Allocation Rule

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/attribution-rules`
**Depends：** AIC-035, AIC-036

只做透明、可测试的 Deterministic Rule，不造 DSL。

测试：Priority、Version Trace、Manual Override。

---

## AIC-038 — 实现 Cost / Allocation React 工作流

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/frontend-cost-allocation`
**Depends：** AIC-034, AIC-036, AIC-037

测试：Remaining Amount、Exact Sum Guard、Duplicate Review UX。

---

# M4 — Expense & Budget

## AIC-039 — 建立 Expense / Approval Schema

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/expense-schema`
**Depends：** AIC-011, AIC-021, AIC-035

表：

```text
expense_claim
approval_case
approval_action
```

---

## AIC-040 — 实现 Expense Claim Workflow

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/expense-workflow`
**Depends：** AIC-022, AIC-039, AIC-036

实现：

```text
DRAFT
SUBMITTED
NEEDS_INFO
APPROVED / REJECTED
CANCELED
VOIDED before posting
```

测试：Owner、State Conflict、Approval Audit。

---

## AIC-041 — 建立 BillingPeriod / Budget Schema

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/budget-period-schema`
**Depends：** AIC-006, AIC-018

表：

```text
billing_period
budget
budget_commitment
budget_commitment_usage
```

---

## AIC-042 — 实现 BillingPeriod OPEN Guard

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/period-guard`
**Depends：** AIC-041

测试：

```text
OPEN 允许
CLOSING / CLOSED 拒绝普通财务写
```

---

## AIC-043 — 实现 Budget 管理与 Read Model

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/budget-management`
**Depends：** AIC-041, AIC-017

公式：

```text
available = total - actual - committed
```

测试：Precision、Scope/Currency Unique、Version Conflict。

---

## AIC-044 — 实现 Atomic Budget Commitment Activation

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/budget-commitment`
**Depends：** AIC-040 Approval Shell, AIC-042, AIC-043

必须使用 MySQL Conditional Update。

测试：

```text
Idempotency-Key
100 Concurrent Attempts
Insufficient Budget
Period Not OPEN
Approval + Counter Atomic
```

这是 V1 强制并发证据。

---

## AIC-045 — 实现 Commitment Release / Consume Primitive

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/budget-commitment-lifecycle`
**Depends：** AIC-044

测试：

```text
Partial Consume
Full Consume
Release Remainder
Remaining 不可为负
```

---

## AIC-046 — 实现 Expense / Budget React

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/frontend-expense-budget`
**Depends：** AIC-040, AIC-043, AIC-044

Budget 页面必须正确展示：

```text
Total
Actual
Outstanding Commitment
Available
Over-budget
```

---

# M5 — Immutable Ledger

## AIC-047 — 建立 Ledger / Correction Schema

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/ledger-schema`
**Depends：** AIC-032, AIC-035, AIC-041

表：

```text
ledger_posting
ledger_entry
correction_group
```

`audit_event` / `api_idempotency` 已在 M1 基础中存在。

测试：Posting Key Unique、FK、Index。

---

## AIC-048 — 实现 Provider Charge Posting Transaction

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/ledger-charge-posting`
**Depends：** AIC-036, AIC-042, AIC-045, AIC-047

强制测试：

```text
Import Confirmed
Charge CLEAN
Allocation Confirmed
Period OPEN
posting_key 幂等
Budget Actual
Commitment Consume
无匹配 Budget 时仍可入账
Over-budget Cost 仍入账
Rollback 无半状态
```

必须使用真实 MySQL Integration Test。

---

## AIC-049 — 实现 Expense Posting Transaction

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/ledger-expense-posting`
**Depends：** AIC-040, AIC-048

测试：APPROVED Only、幂等、Period Guard、Claim→POSTED Atomic。

---

## AIC-050 — 实现 Ledger Query / Lineage API

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/ledger-query-lineage`
**Depends：** AIC-048

必须可追：

```text
Entry
→ Posting
→ Allocation
→ Charge / Expense
→ RawRecord
→ ImportAttempt
→ Evidence
```

测试：Scope Authorization、Pagination、Lineage 完整。

---

## AIC-051 — 实现 Correction Posting

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/ledger-correction`
**Depends：** AIC-048, AIC-049

Correction 写入 OPEN Correction Period，不静默改 CLOSED 历史。

测试：Reversal/Replacement、Budget Move、Original Immutable、Idempotency。

---

## AIC-052 — 增加 Ledger Invariant / Architecture Test

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `test/ledger-invariants`
**Depends：** AIC-048 ～ AIC-051

覆盖：

```text
No Destructive Update
Provider Adapter 不依赖 Ledger
Duplicate Posting
Closed Period Rejected
```

---

## AIC-053 — 实现 Ledger React Workflow

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/frontend-ledger`
**Depends：** AIC-050, AIC-051

核心展示：Lineage + 授权后的 Correction UX。

---

# M6 — Reconciliation & Close

## AIC-054 — 建立 Reconciliation / Close Schema

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/reconciliation-close-schema`
**Depends：** AIC-047, AIC-041

表：

```text
reconciliation_run
reconciliation_case
period_close_run
period_close_check
```

---

## AIC-055 — 实现 Reconciliation Run / Matching Baseline

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/reconciliation-run`
**Depends：** AIC-050, AIC-054

V1 只做 Document/Period Financial Reconciliation，不造通用 Matching DSL。

测试：Matched、Missing Internal/External、Amount Mismatch、Tolerance。

---

## AIC-056 — 实现 Reconciliation Case Lifecycle

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/reconciliation-cases`
**Depends：** AIC-055

Resolution 必须有 Reason/Note，不自动改 Ledger。

测试：Material Case 在 Resolve 前持续阻止 Close。

---

## AIC-057 — 实现 Close Blocker Provider

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/period-close-blockers`
**Depends：** AIC-030, AIC-034, AIC-040, AIC-056

实现：

```text
OPEN_IMPORTS
UNRESOLVED_DUPLICATES
UNALLOCATED_CHARGES
UNPOSTED_APPROVED_EXPENSES
OPEN_MATERIAL_RECONCILIATION
PENDING_CORRECTIONS
LEDGER_INTEGRITY
```

每个 Blocker 都有 PASS/FAIL Test。

---

## AIC-058 — 实现 BillingPeriod Close / Reopen Coordinator

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/period-close`
**Depends：** AIC-042, AIC-054, AIC-057

测试：

```text
OPEN→CLOSING→CLOSED
Blocker → 回 OPEN / BLOCKED
CLOSING 阻止写
Reopen 权限
Generation History
Close / Write Race
```

---

## AIC-059 — 实现 Reconciliation / Close React

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/frontend-reconciliation-close`
**Depends：** AIC-056, AIC-058

---

# M7 — Workbench & End-to-End Integration

## AIC-060 — 实现 Reporting Read Model

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `feat/reporting-read-models`
**Depends：** AIC-048, AIC-043, AIC-056

查询：

```text
Cost by Provider
Cost by Project
Budget Variance
Workbench Blocker Count
```

按 Currency 分组。

测试：Scope、No Cross-currency Sum、EXPLAIN Review。

---

## AIC-061 — 增加 Redis Dashboard Cache

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `perf/reporting-cache`
**Depends：** AIC-060

Cache-aside + Short TTL + MySQL Fallback。

测试：Hit/Miss、Redis Down、Stale Bound。

---

## AIC-062 — 实现 Workbench / Reporting React

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/frontend-workbench`
**Depends：** AIC-060, AIC-061

Chart 是辅助，优先展示：

```text
Unallocated
Duplicate
Pending Approval
Open Reconciliation
Budget Overrun
Close Status
```

---

## AIC-063 — Provider Statement 主链路 E2E

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `test/e2e-provider-ledger`
**Depends：** AIC-033, AIC-036, AIC-048, AIC-056, AIC-058

流程：

```text
Login
→ Upload Synthetic Provider Fixture
→ Import
→ Confirm
→ Allocate
→ Post
→ Reconcile
→ Close
```

---

## AIC-064 — Employee Expense 主链路 E2E

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `test/e2e-expense-ledger`
**Depends：** AIC-040, AIC-049, AIC-058

流程：

```text
Employee Login
→ Expense Evidence
→ Submit
→ Finance Approve
→ Allocate
→ Post
→ Close
```

---

## AIC-065 — 实现 Audit 查询与 Sensitive Action 验证

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `feat/audit-review`
**Depends：** AIC-048, AIC-058

确保权限文档列出的关键行为都可审计。

---

# M8 — Hardening & V1 Release

## AIC-066 — 执行 Schema / Query Index Review

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `perf/mysql-index-review`
**Depends：** M7 Functional Complete

收集：

```text
Ledger Period/Project
Reconciliation
Import Claim
Duplicate Review
Budget Lookup
Workbench
```

的 EXPLAIN。

只根据证据改索引。

---

## AIC-067 — 执行 Import Scale Benchmark

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `perf/import-benchmark`
**Depends：** AIC-033

数据量：

```text
100k
500k
1m Facts
```

记录：

```text
Throughput
Duration
Memory Peak
DB Batch Behavior
```

禁止提前写性能结论。

---

## AIC-068 — 执行 Financial Concurrency / Failure Suite

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `test/financial-concurrency-failures`
**Depends：** AIC-048, AIC-051, AIC-058

必须覆盖：

```text
100 Concurrent Commitments
Duplicate Ledger Post
Transaction Rollback
Period Close/Post Race
Bounded Deadlock Retry
```

---

## AIC-069 — 执行 Redis / MinIO / Import Failure Suite

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `test/runtime-failure-injection`
**Depends：** AIC-015, AIC-022, AIC-023, AIC-061

覆盖：

```text
Redis Restart
Redis Login Failure Policy
MinIO Unavailable
Worker Crash
Lease Recovery
Dashboard Fallback
```

---

## AIC-070 — Security / Secret Leak Review

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `test/security-review`
**Depends：** Functional V1

检查：

```text
Raw Evidence Authorization
Data Scope
Secret Logging
Refresh Replay
Rate Limit
VITE_* Safety
.gitignore
Git History Secret
```

如果 Secret 真 Push 过，必须 Rotate，不是只补 `.gitignore`。

---

## AIC-071 — 建立稳定 Docker Compose Smoke

**Owner：** Dev A
**Reviewer：** Dev B
**Branch：** `test/compose-smoke`
**Depends：** AIC-010, AIC-063, AIC-064

Smoke：

```text
Compose Up
Health
Flyway Head
Register/Login
Fixture Import
Ledger Post
Ledger Query
```

稳定后再考虑设为 Required Check。

---

## AIC-072 — 完成 V1 文档与工程证据报告

**Owner：** Dev B
**Reviewer：** Dev A
**Branch：** `docs/v1-release`
**Depends：** AIC-066 ～ AIC-071

更新：

```text
README Quick Start
Architecture Link
实际 Provider Support
实际测试数量
Benchmark
Known Limitations
Contributors
```

所有数字必须来源于真实执行。

---

## AIC-073 — V1 Release Acceptance / Tag

**Owner：** Dev A
**Reviewer：** Dev B
**类型：** Release Checkpoint
**Depends：** 所有 P0/P1 Blocker 处理完成

按 `06-v1-release-acceptance.md` 验收。

通过：

```text
tag v1.0.0
→ GitHub Release
```

证据不完整就不打 Tag。

---

# 任务数量

```text
M0  10
M1  10
M2  11
M3   7
M4   8
M5   7
M6   6
M7   6
M8   8
────────
共 73 个工作项
```

73 个工作项不是 73 个“大功能”，而是刻意拆成可 Review 的 Issue / PR 粒度。
