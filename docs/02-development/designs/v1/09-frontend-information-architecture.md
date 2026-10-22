# 09. React 前端信息架构

## 1. Stack

```text
React 19
TypeScript
Vite
React Router
TanStack Query
Ant Design
ECharts
Axios
```

这是企业 Workflow / Admin App，不是 ECharts Dashboard Demo。

## 2. Source Layout

```text
frontend/src/
├── app/
│   ├── router/
│   ├── providers/
│   └── layout/
├── api/
├── features/
│   ├── auth/
│   ├── workbench/
│   ├── evidence/
│   ├── imports/
│   ├── costs/
│   ├── allocation/
│   ├── expenses/
│   ├── budgets/
│   ├── ledger/
│   ├── reconciliation/
│   ├── periods/
│   └── admin/
├── components/
└── shared/
```

## 3. Server State

TanStack Query 管：

```text
List / Detail
Pagination
URL Filter
Mutation
Loading / Error / Retry
Cache Invalidation
```

Query Key 由 Feature 自己维护，例如：

```text
['ledger', filters]
['budget', id]
['imports', filters]
```

## 4. Client State

React Local State / Context 管：

```text
Modal
Drawer
Form
UI Preference
```

Access Token 放内存。

没有明确需求不引入 Redux。

## 5. Auth Bootstrap

应用启动：

```text
POST /auth/refresh
→ Access Token
→ GET /auth/me
→ Render
```

Session Expired：

```text
Redirect /login
```

多个同 Tab 401：

```text
Single-flight Refresh
→ Retry Original Request Once
```

Cross-tab：

```text
AUTH_REFRESH_RACE
→ Wait
→ Retry once
```

禁止无限循环。

## 6. Route

### Public

```text
/login
/register
/forgot-password
/reset-password
/invite/:token
```

### Workbench

```text
/workbench
```

展示：

```text
Current Period
Cost by Currency
Budget Variance
Unallocated Charges
Duplicate Candidates
Pending Approvals
Open Material Reconciliation
Close Status
```

### Evidence

```text
/evidence
/evidence/:id
```

Tab：

```text
Metadata
Imports
Lineage
Download
```

### Import

```text
/imports
/imports/:id
```

M2 Tab（Group 3 实现，2026-08-15）：

```text
Overview
Attempts
Issues
Raw Records
```

M2 不渲染 M3 占位 Tab（Normalized Facts / Allocation Proposal 等）。

权限门控：`/evidence/**` 需要 `EVIDENCE_READ`，`/imports/**` 需要 `IMPORT_READ`；
`PermissionRoute` 在 child page mount 之前拦截，未授权直达路由显示 403 且子页面
（及其查询）不挂载。Evidence 详情页的关联 Imports 子查询仅在 `IMPORT_READ` 时
启动；Import 详情页的 Evidence 元数据在 `IMPORT_READ` 下可见，原始文件下载仍
需要 `EVIDENCE_DOWNLOAD`。

应用落地（M2 Group 3 fix round，2026-08-15）：`/app` 与已认证 wildcard 使用
business-aware `ApplicationLanding`——`EVIDENCE_READ` → `/evidence`，否则
`IMPORT_READ` → `/imports`，否则第一条可读 settings 路由，全部缺失才渲染
authenticated 403。`/settings` 保持 M1 `SettingsRedirect` 语义。

Import detail 缓存生命周期（fix round）：仅 `GET /imports/{id}` 轮询；当
`PENDING/PROCESSING` 真实转为终态时 invalidate Attempt/Issue/RawRecord 全部
分页前缀、关联 Evidence imports（按 `detail.evidence.id`）与 Import list。
Retry/Cancel 成功后先把后端返回的 `ImportSummary` 写入 detail 缓存
（Retry→PENDING 立即恢复轮询、Cancel→CANCELED 立即停止），再做依赖失效；
409 只刷新状态，绝不自动重发 mutation。

### Cost / Allocation

```text
/costs/consumption
/costs/charges
/costs/unallocated
/costs/duplicates
/allocations/:id
/settings/allocation-rules
```

Allocation Editor 必须实时显示：

```text
Source Amount
Allocated Amount
Remaining
```

Sum 不相等时不能 Confirm。

### Expense

```text
/expenses
/expenses/new
/expenses/:id
/approvals
```

### Budget

```text
/budgets
/budgets/:id
/budget-commitments/:id
```

必须展示：

```text
Total
Actual
Outstanding Commitment
Available
Over-budget
```

禁止把 `total - actual` 错标成 Available。

### Ledger

```text
/ledger
/ledger/postings/:id
/ledger/entries/:id
```

核心页面必须展示完整 Lineage：

```text
LedgerEntry
→ Posting
→ Allocation
→ Charge / Expense
→ RawRecord
→ ImportAttempt
→ Evidence
```

这是 V1 旗舰页面之一。

### Reconciliation

```text
/reconciliation
/reconciliation/runs/:id
/reconciliation/cases/:id
```

Case 详情：

```text
External Amount
Internal Amount
Difference
Reason
Resolution
Related Posting
```

### BillingPeriod

```text
/billing-periods
/billing-periods/:id
```

显示 Close Checklist：

```text
OPEN_IMPORTS
UNRESOLVED_DUPLICATES
UNALLOCATED_CHARGES
UNPOSTED_APPROVED_EXPENSES
OPEN_MATERIAL_RECONCILIATION
PENDING_CORRECTIONS
LEDGER_INTEGRITY
```

### Admin

```text
/settings/users
/settings/roles
/settings/projects
/settings/teams
/settings/cost-centers
/settings/provider-accounts
```

## 7. Permission-aware UI

Frontend Hide/Disable 只是 UX。

安全边界永远是 Backend。

Role 改变后收到 403：

```text
Refresh /auth/me
→ 提示权限已变化
```

## 8. Money

Money 从 API 到展示前保持 Decimal String。

按 Currency 展示：

```text
CNY 100
USD 20
```

不做假 Total。

## 9. Import Progress

V1 用 Polling（M2 Group 3 固定契约）：

```text
GET /api/v1/imports/{id}
```

`PENDING` / `PROCESSING` 每 3 秒 Poll 一次；`PARSED` / `FAILED` / `CANCELED`
立即停止。只有轻量 Import detail 轮询，Issues / Raw Records 大表不轮询。
路由卸载 / logout 时随 query teardown 停止轮询。

不为了进度引入 WebSocket/SSE。

## 10. 大表

全部 Server-side：

```text
Pagination
Filter
Sort
```

禁止浏览器加载 500k Raw Record。

## 11. Error UX

统一处理 ProblemDetail：

```text
422 → Validation
409 → State/Version Conflict
429 → Retry-After
503 → Dependency Temporarily Unavailable
```

不显示 Stack Trace。

## 12. Sensitive Action

以下动作需要业务化 Confirm：

```text
Period Reopen
Ledger Correction
User Disable
Budget Total Change
```

需要 Reason 时必须输入 Reason。

## 13. ECharts

用于：

```text
Trend
Provider Distribution
Project Cost
Budget Variance
```

Chart 是辅助，不复制每张 Table。

## 14. 前端测试重点

```text
Auth Single-flight Refresh
Permission Action
Allocation Sum
Budget Available Display
Close Blocker UX
Ledger Lineage
ProblemDetail Mapping
Mutation Cache Invalidation
```

不把主要精力放在 Ant Design Snapshot。

## M5 Ledger Workflow

`/ledger` is a permission-gated read-only posting/entry list with posting and
lineage detail routes. Cost and expense detail pages expose `记账` only after
their source, confirmed allocation, review, and posting permissions are ready.
The mutation sends no client-generated money calculation: the backend resolves
the OPEN period, exact-target → ORG budget fallback, actuals, and commitment
consumption under locks.

For each positive allocation line, the UI may show one optional commitment
selector from the existing budget and commitment reads. Negative/zero lines and
lines without a visible budget have no selector. Correction is a reasoned modal
with an OPEN correction-period selector and REVERSAL_ONLY/REPLACE modes; all
mutation queries disable automatic retry and invalidate source, allocation, and
ledger lists on success.
