# M4 Final Closure Design

## Goal

Close the remaining M4 product/UAT gaps in one final branch and one final PR by delivering GitHub issues #80, #82, and #84 without introducing unrelated M5 work.

Base: `main@27b873193b66b74b7b23f53904128e3fe0f9ce05`

Branch: `fix/m4-final-closure`

## Scope

### 1. Anonymous bootstrap warning regression (#84)

A fresh anonymous browser context is allowed to probe `POST /api/v1/auth/refresh` during application bootstrap. If that initial probe returns `401 AUTH_SESSION_EXPIRED`, the frontend must settle to anonymous silently.

Required semantics:

- initial bootstrap `AUTH_SESSION_EXPIRED` => `anonymous`;
- no `SESSION_INVALIDATED` publication for that initial anonymous bootstrap failure;
- no session-expiry warning for that initial anonymous bootstrap failure;
- runtime terminal `AUTH_SESSION_EXPIRED` / `AUTH_REFRESH_REPLAY` after an authenticated session exists remains terminal and keeps the #83 cross-tab invalidation behavior;
- no backend or Redis changes.

This is a bootstrap-context distinction, not a redesign of cross-tab auth coordination.

### 2. Role assignment dropdown polish (#80)

Only presentation behavior changes.

- Long localized role labels must be fully readable at normal desktop widths.
- The Select control and popup may be given explicit responsive width/min-width behavior.
- No role IDs, role codes, permission mappings, payloads, or backend contracts change.
- No page-level horizontal overflow may be introduced.

### 3. BillingPeriod read/list and Budget Create (#82)

#### Backend

Add the smallest read-only BillingPeriod API needed for Budget creation.

Recommended contract:

- `GET /api/v1/billing-periods`
- current-organization scoped only;
- require organization-level budget read capability (`BUDGET_READ`) before returning rows;
- response is a simple ordered list, not a new lifecycle-management surface;
- each item exposes only existing BillingPeriod read fields needed for selection/display: stable string `id`, `periodStart`, `periodEnd`, `status`, `version`;
- preserve existing `[start,end)` semantics and `OPEN | CLOSING | CLOSED` status values;
- no create/close/reopen BillingPeriod endpoints;
- no Flyway/schema changes.

The mapper already has org-scoped point reads. Extend it with an org-scoped deterministic list query, ordered by `period_start DESC, id DESC`. Application/service authorization must happen before rows are returned.

#### Frontend

Extend the existing Budget feature rather than creating a parallel workflow.

- add `budgetApi.create(...)` matching the existing `POST /api/v1/budgets` contract;
- add a small BillingPeriod API module and query key;
- add a `创建预算` action to `/budgets`, visible only when the current user has `BUDGET_MANAGE`;
- open a modal/form rather than adding a new route;
- BillingPeriod must be selected from the backend list; no raw/manual period ID field;
- creation fields remain the existing backend truth: `billingPeriodId`, `scopeType`, `scopeId`, `currency`, `totalAmount`;
- preserve money as decimal strings end-to-end; do not use `Number`, `parseFloat`, or client-side financial recomputation;
- after success, invalidate budget list queries and show the created budget from normal backend list/read data;
- permission gating is UX-only; backend `BUDGET_MANAGE` remains authoritative for create.

The frontend does not invent scope resources. Existing scope validation remains server authoritative; the form may accept the existing scope ID input because #82 only removes raw BillingPeriod IDs from product UX.

## API / Error Semantics

- BillingPeriod list is read-only and organization scoped.
- Unauthorized callers must receive the existing authorization behavior; do not leak foreign organization periods.
- Budget create errors keep the existing ProblemDetail flow and backend conflict/validation semantics.
- No optimistic financial arithmetic or optimistic Budget rows.

## Testing

### Auth

Add a regression test where initial bootstrap returns `401 AUTH_SESSION_EXPIRED` in a fresh anonymous provider:

- state becomes anonymous;
- no warning;
- no `SESSION_INVALIDATED` publish;
- existing runtime terminal-session tests continue to pass.

### Settings

Add/adjust a focused UI test proving long localized role labels render without truncation behavior imposed by the Select configuration and role value submission remains unchanged.

### Backend BillingPeriod

Cover at minimum:

- `BUDGET_READ` user lists only current-org periods;
- deterministic order;
- foreign-org rows are absent;
- unauthorized caller is rejected;
- string ID / status / time fields serialize correctly.

### Budget Create UI

Cover at minimum:

- create action hidden without `BUDGET_MANAGE`;
- BillingPeriod options come from the read API;
- no manual billingPeriodId input is rendered;
- create submits the selected period ID and exact decimal string;
- successful create invalidates/refetches Budget list;
- existing Budget/Commitment tests remain green.

## Validation Gate

Before PR creation:

- backend unit tests;
- backend integration tests;
- backend architecture tests;
- frontend focused auth/settings/budget tests;
- full frontend tests;
- frontend lint;
- frontend build;
- `git diff --check`;
- verify changed files remain within #80/#82/#84 scope;
- `.zcode/` and `start-dev.bat` remain untracked/uncommitted.

GitHub Actions CI must pass on the final Draft PR.

## Human UAT

One final M4 pass only:

1. fresh incognito/clean visit does not show the session-expiry warning;
2. role assignment long labels are fully readable and assignment still works;
3. finance admin creates a Budget entirely in the browser using a backend-provided BillingPeriod option;
4. created Budget appears with backend-provided Total / Actual / Outstanding Commitment / Available / Over-budget values and existing Commitment workflow remains usable.

## Explicit Non-goals

- BillingPeriod create/close/reopen;
- Ledger / AIC-047+;
- Budget actual posting;
- Commitment consume HTTP/UI;
- auth architecture redesign;
- backend auth/Redis changes;
- role/permission redesign;
- unrelated refactoring or dependency upgrades.
