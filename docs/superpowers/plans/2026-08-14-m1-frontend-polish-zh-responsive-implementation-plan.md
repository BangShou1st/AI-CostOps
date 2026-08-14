# M1 Frontend Polish / Simplified-Chinese Localization / Responsive Layout — Implementation Plan

Base: `e3252a0` on `feat/m1-organization-authorization-e2e`.
Design: `2026-08-14-m1-frontend-polish-zh-responsive-design.md`.

Every implementation task follows RED -> targeted failure -> minimal GREEN
-> targeted regression -> `git diff --check` -> independent commit.

## Task 1 — Approved design docs

- Write the design spec and this plan.
- Self-review: no TODO/TBD/"以后再定", no backend contract change, no M2,
  technical values untranslated, breakpoints and sidebar behavior explicit,
  acceptance criteria explicit.
- Commit: `docs(m1): define frontend polish and localization`.

## Task 2 — Localization foundation

- Add `@ant-design/icons@6.3.2` to `frontend/package.json` dependencies
  (already installed as antd's transitive dependency; React 19 compatible).
- Wrap the app in Ant `ConfigProvider locale={zhCN}` (import
  `antd/locale/zh_CN`; verified at `e3252a0` to expose `zh-cn` Pagination/
  Modal/Table/Empty copy) in `AppProviders`.
- Create `frontend/src/features/settings/presentation.ts` with
  `statusLabel`, `roleLabel` (fail-safe for unknown roles), plus shared
  page/table copy constants.
- Localize `AuthPages.tsx` (登录/创建账号/忘记密码？/重置密码/接受邀请/退出登录
  and pending states) and common status strings (Restoring your session…
  -> 正在恢复会话…).
- Localize known frontend error UX messages (403/session expired/conflict/
  network) while `ProblemDetail.code` stays English and backend `detail`
  text is never machine-translated.
- Tests: Chinese auth labels, Chinese status/role labels, technical API
  values remain English.
- Commit: `feat(frontend): localize M1 user interface`.

## Task 3 — Responsive collapsible application shell

- Rework `AuthenticatedLayout` into `app-shell`:
  - `sidebar` (`height: 100dvh`, `position: sticky; top: 0`, flex column,
    overflow hidden) with `brand/header`, `nav-scroll-area`
    (`flex: 1; min-height: 0; overflow-y: auto`), `account-footer`
    (`margin-top: auto; flex-shrink: 0`);
  - desktop `>= 1024px`: permanent collapsible sidebar, expanded ~240px /
    collapsed ~72px, `@ant-design/icons` menu icons with Tooltips in
    collapsed state, aria-labeled collapse control, current user and
    退出登录 in the footer;
  - `localStorage` key `aicostops:settings-sidebar-collapsed` with safe
    fallback; `prefers-reduced-motion` respected for the width transition;
  - `< 1024px`: compact top header (`[菜单按钮] AI CostOps / 页面名`),
    navigation Drawer with permission-aware nav, current user and logout;
    route selection/ESC/mask close it; state not persisted.
- Add a `useMediaQuery`-style hook (matchMedia) for the breakpoint.
- Tests (semantic DOM/class/state, no jsdom pixel tests): expanded labels,
  collapse hides labels but keeps nav, collapse button aria-label,
  preference round-trip, malformed preference safe fallback, footer stays
  structural, permissions still hide nav items, desktop uses sidebar,
  mobile uses menu button/drawer, mobile renders no permanent sidebar,
  route selection closes the drawer.
- Commit: `feat(frontend): add responsive collapsible settings shell`.

## Task 4 — Settings pages localization and table/layout density

- Localize all six pages (Users/Roles/Projects/Teams/Cost Centers/
  Provider Accounts), member drawers, lifecycle modal, provider editor,
  role assignment drawer/modal, invitation modal, loading/empty/error/
  pagination/action copy through `presentation.ts`.
- Apply `statusLabel`/`roleLabel`; status cells use Ant Tag with text.
- Tables: `scroll={{ x: ... }}` with sensible per-column width/minWidth so
  390px pages never overflow and containers scroll internally.
- Desktop width/density: main content `width: 100%; min-width: 0`,
  `max-width` ~1440px, desktop padding 24–32px, compact action rows
  (Disable/Assign roles on one line).
- Regression: full Settings page suites keep passing; existing assertions
  that matched English copy are updated to the Chinese copy.
- Commit: `fix(frontend): polish settings layouts and navigation`.

## Task 5 — Known Minor fixes

- `/settings` redirect to the first permitted route by fixed priority
  (no READ -> Forbidden). Tests:
  `settingsRootRedirectsToFirstPermittedRoute`,
  `settingsRootRendersForbiddenWhenNoReadPermission`.
- Invitation selector filters out `PROJECT_OWNER` for ORG invites. Test:
  `invitationDoesNotOfferProjectOwnerForOrgInvite`.
- Narrow or remove the `"not wrapped in act"` suppression only if trivially
  safe and the full suite stays clean; otherwise record as Minor.
- Commit: `fix(frontend): polish settings layouts and navigation`
  (same commit as Task 4 if coupled) or a dedicated semantic commit.

## Task 6 — Focused UI/interaction audit

- Audit the touched scope for: sticky/fixed height, full-page horizontal
  overflow, nested overflow traps, collapsed text clipping, mobile Drawer
  duplication, z-index, offscreen modal/drawer, wrapped buttons, loading
  layout shift, stale permission/form state, double-submit, bad query
  invalidation, localized technical values, unsupported role offers, long
  email/role layout, viewport resize.
- Real bugs: RED -> minimal fix -> regression, separate semantic commit
  per bug. Minor with small fix: fix; otherwise record in the final report.

## Task 7 — Full verification and evidence

- `npm test -- --run`, `npm run lint`, `npm run build` all pass.
- Smoke: `auth-smoke.ps1` -> `AUTH_SMOKE_PASS`;
  `organization-authorization-smoke.ps1` twice ->
  `ORG_AUTH_SMOKE_PASS` twice.
- Backend regression: `mvnw.cmd clean verify` (expected unchanged;
  report Surefire/Failsafe counts).
- Scans: `rg` for TODO/TBD in the two new docs; user-visible English scan
  of `frontend/src`; `git diff --check`; `git status`.
- Generate `m1-frontend-polish-review.patch` from `e3252a0..HEAD` (not
  committed).
- Final concise report; NO push / NO PR / NO merge.
