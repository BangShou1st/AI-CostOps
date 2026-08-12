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

Tab：

```text
Overview
Attempts
Issues
Raw Records
Normalized Facts
Allocation Proposal
```

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

V1 用 Polling：

```text
GET /imports/{id}
```

Processing 时每 2-5 秒 Poll，Terminal State 停止。

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
