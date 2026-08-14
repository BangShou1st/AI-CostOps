# M1 Frontend Polish / Simplified-Chinese Localization / Responsive Layout — Design

## 1. Purpose and scope

This round delivers the final M1 frontend polish on top of the approved
Organization & Authorization E2E (`e3252a0`). It is a presentation-only
round. It does not change the backend, the API contract, the permission
model, the authorization behavior, or the acceptance smoke semantics. It
does not begin M2.

Deliverables:

- default Simplified-Chinese UI for all user-visible surfaces;
- a viewport-bound collapsible sidebar on desktop (>= 1024px);
- a compact top header plus navigation Drawer below 1024px;
- responsive Settings tables with in-container horizontal scrolling;
- desktop width/density polish for the six Settings pages;
- three known Minor fixes (`/settings` first-permitted route, invitation
  `PROJECT_OWNER` filter, optional act-warning cleanup);
- focused UI/interaction bug audit within the touched frontend scope.

## 2. Frozen constraints

### 2.1 Stack (unchanged)

React 19, TypeScript 6, Vite 8, React Router, TanStack Query,
Ant Design 6.6.0, Axios, Vitest / Testing Library.

Forbidden:

- react-i18next, Redux, Zustand, a second UI framework, a CSS-framework
  replacement, a second mobile Card UI for tables;
- backend product redesign, API schema change, Organization CRUD, M2;
- unrelated refactor.

### 2.2 Localization boundary

The default UI is Simplified Chinese. There is no language switch and no
i18n framework. Ant Design receives its official `zh_CN` locale through
`ConfigProvider` so its built-in Pagination/Modal/Table/Empty/Select
copy renders Chinese.

The following values are technical values. They are presented verbatim in
English in every payload and remain English in the UI where they identify
an API value:

```text
ACTIVE  DISABLED  ARCHIVED
EMPLOYEE  PROJECT_OWNER  SYSTEM_ADMIN  FINANCE_ADMIN  FINANCE_REVIEWER
permission codes  provider codes  ProblemDetail.code  API payload
```

Chinese labels are presentation only. A Chinese label is never sent to the
backend as an API value. Unknown Role codes fail safe to their raw
code/name and never crash.

### 2.3 Sidebar behavior

Desktop (>= 1024px) shows a permanent sidebar bound to the viewport:

```text
height: 100dvh
top: 0
```

The sidebar height is never determined by main content height. Structure:

```text
app-shell
  sidebar
    brand/header
    nav-scroll-area (flex: 1; min-height: 0; overflow-y: auto)
    account-footer (margin-top: auto; flex-shrink: 0)
  main
```

Main content scrolls independently; the sidebar stays on screen regardless
of list length.

### 2.4 Collapsible sidebar

Desktop only. Frozen widths: expanded ~240px, collapsed ~72px (tuned to
the current visual density, not hard-coded outside this band).

- Expanded shows brand, Chinese menu labels with icons, collapse control,
  current user and 退出登录.
- Collapsed shows a compact brand mark, icons only (never squeezed menu
  text), a per-icon Tooltip with the Chinese menu name, a compact user
  presentation and a logout icon with Tooltip.
- Collapse control: keyboard focusable, `aria-label="收起侧边栏"` /
  `aria-label="展开侧边栏"`, visible focus state.
- Preference persists under `localStorage` key
  `aicostops:settings-sidebar-collapsed`; malformed or unavailable storage
  falls back safely to expanded. State never enters Backend, JWT or Query
  Cache.

### 2.5 Responsive breakpoints

| Viewport | Navigation |
|---|---|
| >= 1024px | permanent collapsible sidebar |
| < 1024px | compact top header with menu button + navigation Drawer |

Below 1024px the permanent sidebar does not render. The Drawer contains
brand, permission-aware nav, current user and logout. Selecting a route
closes the Drawer; ESC and mask click close it. The mobile Drawer open
state is not persisted. Sidebar and Drawer never render simultaneously.

### 2.6 Icons

`@ant-design/icons` 6.3.2 is already installed as a transitive dependency
of antd 6.6.0 and is React 19 compatible; it is promoted to an explicit
`dependencies` entry. No second icon framework, FontAwesome or icon CDN.

Menu icon semantics:

| Menu | Icon |
|---|---|
| 用户管理 | user |
| 角色与权限 | security/key/shield |
| 项目管理 | folder/project |
| 团队管理 | team |
| 成本中心 | account/book |
| 云账号 | cloud |
| 退出 | logout |

Icons follow Ant token colors and sizes; no ornamental multi-color icons.

### 2.7 Mobile table strategy (approved Option A)

Keep `Table`. Narrow viewports scroll horizontally inside the table
container via `scroll.x` and column `minWidth`/width. No second Card UI.
All core tables are covered: Users, Roles, Projects, Teams, Cost Centers,
Provider Accounts, Project Members, Team Members.

Requirements: the 390px page itself never overflows horizontally; the
table container scrolls internally; action buttons stay reachable;
email/code never wrap character-by-character meaninglessly; Role/status
stay readable.

### 2.8 Desktop width/density

Main content: `width: 100%; min-width: 0`, comfortable `max-width`
(~1440px) and desktop padding (24–32px). Six Settings pages get
consistent spacing, compact action rows, and Status via Ant Tag (Tag plus
text; color alone never carries status meaning). No full-site re-color;
keep the deep-blue sidebar and blue primary.

## 3. Known Minor fixes

### 3.1 `/settings` default route

`/settings` redirects to the first permitted route by the fixed priority:

```text
USER_READ -> /settings/users
ROLE_READ -> /settings/roles
PROJECT_READ -> /settings/projects
TEAM_READ -> /settings/teams
COST_CENTER_READ -> /settings/cost-centers
PROVIDER_ACCOUNT_READ -> /settings/provider-accounts
```

No settings READ permission -> authenticated Forbidden page (no login
redirect). Tests: `settingsRootRedirectsToFirstPermittedRoute`,
`settingsRootRendersForbiddenWhenNoReadPermission`.

### 3.2 Invitation `PROJECT_OWNER`

The ORG generic invitation initial-role selector never offers
`PROJECT_OWNER` (backend RoleScopePolicy rejects `PROJECT_OWNER + ORG`).
The Roles catalog page still displays `PROJECT_OWNER`. Test:
`invitationDoesNotOfferProjectOwnerForOrgInvite`. No backend change.

### 3.3 act-warning suppression

Narrow or remove the `"not wrapped in act"` console suppression only if a
small change keeps the full suite clean; otherwise record as Minor and do
not block the round.

## 4. Presentation mapping

A small `presentation.ts` module (settings feature) holds:

```text
statusLabel:   ACTIVE -> 启用 | DISABLED -> 已停用 | ARCHIVED -> 已归档
roleLabel:     EMPLOYEE -> 员工（EMPLOYEE）
               PROJECT_OWNER -> 项目负责人（PROJECT_OWNER）
               SYSTEM_ADMIN -> 系统管理员（SYSTEM_ADMIN）
               FINANCE_ADMIN -> 财务管理员（FINANCE_ADMIN）
               FINANCE_REVIEWER -> 财务复核员（FINANCE_REVIEWER）
unknown role  -> raw code/name (fail safe)
```

No translation engine is introduced.

## 5. Authorization invariants (hard gate)

UI polish must not regress any authorization behavior:

- no READ permission -> nav item hidden; direct unauthorized URL ->
  authenticated 403; child not mounted; hidden API not requested;
- 403 mutation -> `/auth/me` refresh exactly once, awaited, original
  forbidden surfaced, no mutation retry;
- `AUTH_SESSION_EXPIRED` -> token cleared, session-bound query cache
  cleared, anonymous, login page, no refresh loop;
- project/team member management without `USER_READ` keeps the manual
  `organizationMemberId` fallback and never restores a hidden `/users`
  request;
- Chinese labels are never sent as API values. Tests prove at least one
  case: UI shows 系统管理员（SYSTEM_ADMIN） and submits `SYSTEM_ADMIN`.

## 6. Acceptance criteria

1. All user-visible auth, navigation, Settings page, modal, drawer,
   table, loading, empty, error and status copy renders Simplified
   Chinese; technical codes remain English.
2. Desktop sidebar is viewport-bound: identical footer/sign-out position
   for 10 and 500 user rows; collapse to icon rail and back persists
   across reload; aria-label and focus state exist; `prefers-reduced-
   motion` respected.
3. Below 1024px no permanent sidebar renders; top menu button opens the
   nav Drawer; route selection, ESC and mask close it.
4. Tables scroll horizontally inside their container at 390px; the page
   body does not overflow horizontally; action buttons remain reachable.
5. `/settings` redirects to the first permitted route or renders the
   authenticated Forbidden page.
6. ORG invitation role selector never offers `PROJECT_OWNER`.
7. Full frontend test suite, lint and build pass; auth smoke and two
   consecutive organization smoke runs pass; backend `clean verify`
   passes unchanged.
