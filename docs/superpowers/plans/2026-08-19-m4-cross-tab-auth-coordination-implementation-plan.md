# M4 Cross-Tab Authentication Coordination Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate normal multi-tab `AUTH_REFRESH_RACE` collisions and make all same-origin tabs converge on one browser-profile account without weakening backend refresh rotation.

**Architecture:** Add a small frontend-only `CrossTabAuthCoordinator`. Web Locks serialize all shared-cookie auth mutations; BroadcastChannel carries non-secret session lifecycle events. Existing per-tab access-token memory, backend rotation authority, bounded race fallback and terminal-session handling remain intact.

**Tech Stack:** React 19.2.8, TypeScript 6.0.3, Axios 1.19.0, TanStack Query 5.101.4, Vitest 4.1.10, browser Web Locks and BroadcastChannel APIs.

**Spec:** `docs/superpowers/specs/2026-08-19-m4-cross-tab-auth-coordination-design.md`

## Global Constraints

- Issue: #81.
- Base commit: `6c58ca77dcf2ad7124665958306a1a72d10581f8`.
- Branch: `fix/m4-cross-tab-auth-coordination`.
- Frontend-only production change; no backend, Flyway or Redis Lua modifications.
- No new npm dependency.
- `.zcode/` is local-only and must remain untracked and untouched.
- Refresh token remains HttpOnly; access token remains tab-memory only.
- Never persist or broadcast access token, refresh token, password or reset token.
- Keep existing `AUTH_REFRESH_RACE` one-retry/horizon policy as fallback defense.
- `AUTH_SESSION_EXPIRED` and `AUTH_REFRESH_REPLAY` remain terminal.
- Receiving a remote lifecycle event must not re-broadcast it.
- Use PowerShell for all local commands.

---

## File Structure

### Create

`frontend/src/features/auth/crossTabAuthCoordinator.ts`

Responsibility: browser-profile auth-cookie mutual exclusion and non-secret lifecycle event transport. It must expose small injectable interfaces so unit tests can simulate two tabs without real browser tabs.

`frontend/src/features/auth/crossTabAuthCoordinator.test.ts`

Responsibility: prove cross-tab serialization, failure release, event delivery, loop avoidance and no-secret event shape.

### Modify

`frontend/src/features/auth/authSession.ts`

Responsibility: preserve tab-local `refreshFlight` / `bootstrapFlight`, but allow refresh execution to be wrapped by the coordinator lock without duplicating retry layers.

`frontend/src/features/auth/authApi.ts`

Responsibility: wire the raw cookie refresh transport into coordinated refresh execution used by bootstrap and 401 recovery.

`frontend/src/features/auth/AuthSessionProvider.tsx`

Responsibility: coordinate login/logout/account replacement and subscribe to remote lifecycle events; clear token/query state before switching identity.

`frontend/src/features/auth/authEvents.ts`

Modify only if required to make local terminal-session events distinguishable from remote lifecycle events. Do not turn it into the cross-tab transport itself.

Existing auth/client/provider tests may be modified or extended only where needed to preserve current behavior and cover integration with the coordinator.

---

### Task 1: Add the coordinator contract and cross-tab serialization

**Files:**
- Create: `frontend/src/features/auth/crossTabAuthCoordinator.ts`
- Create: `frontend/src/features/auth/crossTabAuthCoordinator.test.ts`

**Interfaces:**

Produce the following conceptual public API; exact internal helpers may vary but the public behavior must remain equivalent:

```ts
export type CrossTabAuthEventType =
  | 'SESSION_REPLACED'
  | 'SESSION_CLEARED'
  | 'SESSION_INVALIDATED'
  | 'REFRESH_COMPLETED'

export interface CrossTabAuthEvent {
  version: 1
  type: CrossTabAuthEventType
  eventId: string
  sourceTabId: string
  occurredAt: number
}

export interface CrossTabAuthCoordinator {
  readonly tabId: string
  withCookieLock<T>(operation: () => Promise<T>): Promise<T>
  publish(type: CrossTabAuthEventType): void
  subscribe(listener: (event: CrossTabAuthEvent) => void): () => void
  close(): void
}

export function createCrossTabAuthCoordinator(): CrossTabAuthCoordinator
```

The testability boundary may use injected lock/channel factories, but production callers must not need browser details.

- [ ] **Step 1: Write failing serialization tests**

Create two independent coordinator instances backed by the same fake exclusive lock. Start two deferred operations concurrently. Assert the second operation does not enter until the first settles, and track `maxConcurrent === 1`.

Also write a failure-release test: first owner rejects, second waiter must still enter and finish.

- [ ] **Step 2: Run focused test and verify RED**

```powershell
Set-Location E:\AI-CostOps\frontend
npm test -- --run src/features/auth/crossTabAuthCoordinator.test.ts
```

Expected: FAIL because the coordinator module/behavior does not exist yet.

- [ ] **Step 3: Implement minimal Web Lock wrapper**

Production behavior:

```ts
const COOKIE_LOCK_NAME = 'aicostops:auth:cookie'
```

When `navigator.locks?.request` exists, execute the operation inside one exclusive request for that name. If unavailable, execute the operation directly; do not implement a fake localStorage mutex.

- [ ] **Step 4: Implement non-secret event transport and tests**

Use BroadcastChannel name `aicostops:auth`. The coordinator owns a random per-tab ID and event IDs. Ignore events whose `sourceTabId` equals the current tab. Validate the event envelope before delivery.

If BroadcastChannel is unavailable, storage-event signaling may be used as a notification fallback. The stored value must contain only the event envelope and must never contain auth credentials.

Add tests proving publish/subscribe delivery, self-event ignore, unsubscribe and malformed-event ignore.

- [ ] **Step 5: Add no-secret shape test**

Assert the serializable event envelope has no fields named or containing `accessToken`, `refreshToken`, `token`, `password`, `credential`, `jwt`, or `reset`.

- [ ] **Step 6: Run focused tests GREEN**

```powershell
npm test -- --run src/features/auth/crossTabAuthCoordinator.test.ts
```

Expected: all coordinator tests pass.

- [ ] **Step 7: Commit**

```powershell
git add frontend/src/features/auth/crossTabAuthCoordinator.ts frontend/src/features/auth/crossTabAuthCoordinator.test.ts
git commit -m "feat(auth): add cross-tab session coordinator"
```

---

### Task 2: Serialize refresh rotation across tabs without weakening fallback semantics

**Files:**
- Modify: `frontend/src/features/auth/authSession.ts`
- Modify: `frontend/src/features/auth/authApi.ts`
- Modify: `frontend/src/features/auth/authSession.test.ts`
- Test additional auth client tests only if required by the current wiring

**Interfaces:**

The production refresh path must have exactly these layers in this order:

```text
per-tab refreshFlight
  -> cross-tab cookie lock
     -> existing refreshWithRaceRetry
        -> raw POST /auth/refresh
```

Do not create two nested `refreshWithRaceRetry` layers.

- [ ] **Step 1: Write failing coordinated-refresh tests**

Add tests that exercise two independent simulated tabs through the coordinator lock and prove raw refresh transport maximum concurrency is one.

Preserve existing tests proving:

- same-tab concurrent bootstrap performs one refresh;
- race retries exactly once;
- delayed retry beyond horizon sends no second refresh;
- repeated race stops after two total attempts;
- terminal replay/expired errors are not race-retried.

- [ ] **Step 2: Run targeted RED tests**

```powershell
npm test -- --run src/features/auth/authSession.test.ts src/features/auth/crossTabAuthCoordinator.test.ts
```

Expected: the new coordinated-refresh behavior fails before wiring.

- [ ] **Step 3: Refactor refresh orchestration minimally**

Keep `refreshRequest()` in `authApi.ts` as the raw cookie transport. Create one coordinated refresh function used by both `authApi.refresh` and the API client's `refreshAccessToken` callback. The coordinator lock must span the full `refreshWithRaceRetry` lifecycle, including its brief wait, so another same-browser tab cannot rotate the cookie between attempt one and the allowed retry.

`bootstrapSession` must not wrap an already-coordinated refresh in a second race-retry layer.

- [ ] **Step 4: Publish refresh completion**

After a successful coordinated refresh, publish `REFRESH_COMPLETED`. Do not include access token or user details in the event.

A received `REFRESH_COMPLETED` event is informational; it must not overwrite the receiving tab's access token.

- [ ] **Step 5: Run targeted tests GREEN**

```powershell
npm test -- --run src/features/auth/authSession.test.ts src/features/auth/crossTabAuthCoordinator.test.ts
```

Expected: PASS; existing bounded-race tests remain unchanged in security meaning.

- [ ] **Step 6: Commit**

```powershell
git add frontend/src/features/auth/authApi.ts frontend/src/features/auth/authSession.ts frontend/src/features/auth/authSession.test.ts frontend/src/features/auth/crossTabAuthCoordinator.test.ts
git commit -m "fix(auth): serialize refresh rotation across tabs"
```

---

### Task 3: Enforce one browser-profile account across login/logout/session invalidation

**Files:**
- Modify: `frontend/src/features/auth/AuthSessionProvider.tsx`
- Modify: `frontend/src/features/auth/authApi.ts` if login/logout lock wrappers belong there
- Modify: `frontend/src/features/auth/authEvents.ts` only if required
- Test: add/extend provider/auth tests following current test organization

**Required behavior:**

Login and logout are cookie-mutating operations and must run under the same `aicostops:auth:cookie` lock.

- [ ] **Step 1: Write failing account-replacement test**

Simulate tab A authenticated as user A and tab B logging in as user B. Deliver `SESSION_REPLACED` to A. Assert A cancels active queries, clears full query cache, clears the old in-memory access token, performs exactly one bootstrap, and ends authenticated as user B.

The remote handler must not publish a second `SESSION_REPLACED`.

- [ ] **Step 2: Write failing cross-tab logout test**

After local logout succeeds, initiating tab publishes `SESSION_CLEARED`. A sibling receiving it must clear token/cache/state locally and must not call backend logout.

- [ ] **Step 3: Write failing terminal invalidation propagation test**

When one tab locally classifies `AUTH_SESSION_EXPIRED` or `AUTH_REFRESH_REPLAY` as terminal, it publishes one `SESSION_INVALIDATED`. Sibling tabs become anonymous and clear all query cache. Remote handling must not re-publish.

- [ ] **Step 4: Run provider/auth tests RED**

Use the exact existing provider/auth test file names discovered in the repository plus the coordinator/authSession focused tests. At minimum:

```powershell
npm test -- --run src/features/auth
```

Expected: new lifecycle tests fail before implementation.

- [ ] **Step 5: Implement login session replacement**

Under the cookie lock:

1. perform login;
2. set initiating tab access token;
3. load `/auth/me`;
4. if replacing an authenticated identity, cancel and clear old session-bound queries before publishing the new authenticated state;
5. publish `SESSION_REPLACED` after successful state establishment.

Never broadcast credentials.

- [ ] **Step 6: Implement remote `SESSION_REPLACED` handling**

Subscriber in `AuthSessionProvider` must:

1. cancel queries;
2. clear query cache;
3. clear access token;
4. enter loading state;
5. bootstrap once against the current shared cookie;
6. become authenticated or anonymous according to the authoritative result.

Guard against provider unmount and duplicate overlapping remote replacement handling.

- [ ] **Step 7: Implement logout propagation**

Run explicit backend logout under the cookie lock. On success/finally according to the existing logout contract, clear local access/query/session state and publish `SESSION_CLEARED` exactly once. Remote recipients clear locally and do not call backend logout.

Do not change backend logout semantics for Redis failure.

- [ ] **Step 8: Bridge terminal invalidation without loops**

Keep one local terminal-session path. Local detection may publish `SESSION_INVALIDATED`; remote receipt must call a local-only clear function and never publish again.

- [ ] **Step 9: Run focused tests GREEN**

```powershell
npm test -- --run src/features/auth
```

Expected: PASS with account-switch, logout and invalidation propagation covered.

- [ ] **Step 10: Commit**

```powershell
git add frontend/src/features/auth
git commit -m "fix(auth): synchronize session lifecycle across tabs"
```

---

### Task 4: Regression gate and scope audit

**Files:**
- Modify only tests or auth implementation necessary to resolve real regressions from Tasks 1-3.
- Do not expand into #80 or #82 on this branch.

- [ ] **Step 1: Run full frontend tests**

```powershell
Set-Location E:\AI-CostOps\frontend
npm test -- --run
```

Expected: all tests PASS; no hang/timeouts; report file/test count.

- [ ] **Step 2: Run lint**

```powershell
npm run lint
```

Expected: PASS with zero lint errors.

- [ ] **Step 3: Run production build**

```powershell
npm run build
```

Expected: PASS. Existing chunk-size warning may remain; no new build error.

- [ ] **Step 4: Audit diff scope**

```powershell
Set-Location E:\AI-CostOps
git status --short
git diff --check
git diff --stat origin/main...HEAD
git diff --name-only origin/main...HEAD
```

Expected:

- `.zcode/` may remain untracked only;
- no backend/Flyway/Redis/Budget/Expense/package dependency changes;
- only auth frontend files plus the approved design/plan docs are changed;
- `git diff --check` has no output.

- [ ] **Step 5: Verify commit history**

```powershell
git log --oneline --decorate origin/main..HEAD
```

Expected: documentation commits already on the branch plus 2-4 focused implementation commits; no unrelated history.

- [ ] **Step 6: Push branch**

```powershell
git push -u origin fix/m4-cross-tab-auth-coordination
```

If the configured proxy causes the known TLS handshake failure, retry only that command with:

```powershell
git -c http.proxy= -c https.proxy= push -u origin fix/m4-cross-tab-auth-coordination
```

Do not change global Git config.

- [ ] **Step 7: Stop and report to Sol**

Do not open or merge a PR unless explicitly instructed. Return:

1. `git status --short --branch`;
2. `git log --oneline origin/main..HEAD`;
3. `git diff --stat origin/main...HEAD`;
4. `git diff --name-only origin/main...HEAD`;
5. focused auth test output summary;
6. full frontend test count and PASS result;
7. lint result;
8. build result;
9. concise explanation of the final cross-tab refresh/login/logout behavior;
10. any deviations from this plan, with reason.

---

## Sol Review Gate

Sol will review the remote diff and verify:

- no token/password material is persisted or broadcast;
- one Web Lock covers the entire refresh retry lifecycle;
- login and logout share the same cookie-mutation lock;
- remote events cannot rebroadcast indefinitely;
- identity replacement clears old query/token state before new data is exposed;
- old bounded refresh-race semantics remain intact;
- no unrelated files are touched;
- CI is green before any merge recommendation.

After code/CI review, human UAT must still prove multi-tab reload, account switch, logout propagation and no repeated refresh 409 storm. No merge without explicit user authorization.