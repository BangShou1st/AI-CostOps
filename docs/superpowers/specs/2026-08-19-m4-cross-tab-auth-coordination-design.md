# M4 Cross-Tab Authentication Coordination — Design

Date: 2026-08-19
Issue: #81
Base: `main@6c58ca77dcf2ad7124665958306a1a72d10581f8`

## 1. Decision

AI-CostOps uses **one current account per browser profile for one same-origin application**. Multiple tabs are allowed, but they must converge on the same authenticated identity because the refresh session is carried by one shared HttpOnly cookie.

The current mismatch is:

- access token: per-tab, in-memory only;
- refresh cookie: shared by same-origin tabs;
- `refreshFlight` / `bootstrapFlight`: per JavaScript realm only.

Therefore two tabs can concurrently present the same refresh credential and trigger `409 AUTH_REFRESH_RACE`. A tab can also temporarily retain an access token for the previous account after another tab replaces the shared refresh cookie by logging in as a different user.

## 2. Architecture

Add a frontend-only `CrossTabAuthCoordinator` with two browser primitives:

1. **Web Locks** — exclusive lock name `aicostops:auth:cookie` serializes operations that mutate or rotate the shared auth cookie: refresh, login and logout.
2. **BroadcastChannel** — channel name `aicostops:auth` propagates non-secret session lifecycle events across tabs.

No new npm dependency is allowed.

If `navigator.locks` is unavailable, fall back to current tab-local serialization plus the existing bounded `AUTH_REFRESH_RACE` retry. Do not invent a localStorage mutex that claims atomic cross-tab safety.

If `BroadcastChannel` is unavailable, use a storage-event signal fallback containing only the same non-secret event envelope. Storage is a notification fallback only, never the source of authentication truth.

## 3. Security boundaries

The following are forbidden from localStorage, sessionStorage and BroadcastChannel payloads:

- refresh credential/token;
- access token/JWT;
- password;
- password-reset token.

The refresh token remains HttpOnly. Access tokens remain in each tab's in-memory `AccessTokenStore`.

The backend remains the authority for rotation, replay detection, session expiry and security-version invalidation. Do not weaken Redis rotation/replay semantics, increase the race window as the primary fix, or hide errors without removing the concurrent stale refresh source.

## 4. Cross-tab event contract

Use a versioned discriminated event envelope with a per-tab source ID and event ID. Event payloads must contain no credentials.

Required event types:

- `SESSION_REPLACED` — successful login replaced the browser-profile session; sibling tabs must discard old access state and bootstrap against the new shared cookie.
- `SESSION_CLEARED` — successful explicit logout; sibling tabs become anonymous without issuing another logout request.
- `SESSION_INVALIDATED` — terminal `AUTH_SESSION_EXPIRED` or `AUTH_REFRESH_REPLAY`; sibling tabs clear auth state and query cache.
- `REFRESH_COMPLETED` — refresh rotation completed; informational coordination signal only. It must not carry an access token.

Receiving a remote event must never re-broadcast the same event. Use `sourceTabId` / `eventId` to prevent self-processing and loops.

## 5. Refresh flow

Single-tab single-flight remains in place.

For each refresh lifecycle:

1. tab-local callers share one `refreshFlight`;
2. that flight enters `withCookieLock`;
3. while holding the lock, execute the existing `refreshWithRaceRetry` policy;
4. on success, store the returned access token only in the current tab and publish `REFRESH_COMPLETED`;
5. release the Web Lock automatically when the promise settles.

A sibling waiting for the same Web Lock cannot submit the stale cookie concurrently. After it acquires the lock it may perform its own refresh to obtain its own per-tab access token; sequential rotations are acceptable, concurrent stale rotations are not.

The existing fallback semantics remain hard requirements:

- one brief retry only for `409 AUTH_REFRESH_RACE`;
- no retry after the retry horizon;
- repeated race terminates that lifecycle;
- `AUTH_SESSION_EXPIRED` / `AUTH_REFRESH_REPLAY` are terminal and never retried as races.

## 6. Login/account switch flow

Login mutates the browser-profile session and therefore runs under the same cookie lock.

On successful login:

1. set the current tab's access token;
2. load `/auth/me` and commit authenticated state;
3. clear session-bound query cache before exposing data for the new identity if the previous state was authenticated;
4. publish `SESSION_REPLACED`.

Sibling tabs receiving `SESSION_REPLACED`:

1. cancel active queries;
2. clear all query cache;
3. clear their old in-memory access token;
4. bootstrap once against the now-current shared refresh cookie;
5. converge to the new account.

A sibling must never continue using the old account's access token after `SESSION_REPLACED`.

## 7. Logout and invalidation

Explicit logout runs under the cookie lock. After a successful backend logout, the initiating tab clears its token/cache/state and publishes `SESSION_CLEARED`.

Sibling tabs receiving `SESSION_CLEARED` clear token/cache/state locally and must not send another logout request.

When any tab encounters terminal `AUTH_SESSION_EXPIRED` or `AUTH_REFRESH_REPLAY`, publish `SESSION_INVALIDATED` exactly once for that local terminal transition. Every tab clears its access token and full session-bound query cache. Remote handling does not re-publish.

## 8. Failure handling

- A rejected refresh/login/logout promise releases the Web Lock automatically.
- One failed owner must not deadlock later operations.
- Transport/Redis failures do not masquerade as terminal logout unless the existing terminal classification says so.
- Hidden/background-tab delay must not create an unbounded retry window.
- Broadcast failures must not expose secrets or break single-tab correctness.

## 9. File boundaries

Create:

- `frontend/src/features/auth/crossTabAuthCoordinator.ts`
- `frontend/src/features/auth/crossTabAuthCoordinator.test.ts`

Expected focused modifications:

- `frontend/src/features/auth/authApi.ts`
- `frontend/src/features/auth/authSession.ts`
- `frontend/src/features/auth/AuthSessionProvider.tsx`
- `frontend/src/features/auth/authEvents.ts` only if needed to separate local terminal events from remote lifecycle events cleanly
- focused existing auth/client tests as required

Do not modify backend, Flyway, Redis Lua, Budget, Expense, `.zcode/`, or dependency manifests for this issue.

## 10. Acceptance

Automated:

- two independent coordinator instances sharing one lock never execute cookie-mutating operations concurrently;
- owner failure releases ownership for the next waiter;
- same-tab StrictMode/bootstrap single-flight remains one request;
- login in tab B causes tab A to discard the old identity and bootstrap to the new identity;
- logout in one tab makes sibling tabs anonymous without duplicate logout calls;
- terminal invalidation propagates to sibling tabs without event loops;
- no event/storage payload contains access or refresh credentials;
- existing race retry horizon tests stay green;
- full frontend tests, lint and build pass.

Human UAT:

- two tabs can reload concurrently without repeated `/auth/refresh` 409s;
- simultaneous access-token recovery does not produce a refresh 409 storm;
- logging into a different account in one tab makes all tabs converge to that account;
- logout in one tab logs out siblings;
- repeated foreground/background tab switching does not produce recurring `AUTH_REFRESH_RACE` noise.

M4 formal close remains blocked until this UAT passes.