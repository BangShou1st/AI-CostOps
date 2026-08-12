# M1 Authentication E2E Design

## 1. Purpose

Deliver the first complete M1 vertical feature: a user can register or accept an invitation, authenticate with a password, receive a short-lived access JWT plus a Redis-backed rotating refresh session, recover a password, log out, and use the React application through the existing protected-route/API-client foundation.

This design implements the frozen requirements tracked by:

- AIC-011 / GitHub #16
- AIC-012 / GitHub #17
- AIC-013 / GitHub #18
- AIC-014 / GitHub #19
- AIC-015 / GitHub #20
- AIC-016 / GitHub #21
- AIC-019 / GitHub #24

Delivery is intentionally vertical: one feature branch and one integration PR may close all seven issues, but every issue keeps its own acceptance criteria and tests.

## 2. Frozen project constraints

- Java 21, Spring Boot 4.1.0, plain MyBatis (`mybatis-spring-boot-starter` 4.1.0 / MyBatis Core 3.5.19).
- MySQL 8.4 is the durable identity truth.
- Redis is authentication runtime state, never durable financial or identity truth.
- Flyway is forward-only; critical MySQL behavior is tested with Testcontainers, not H2.
- Root package is `com.aicostops`; package by feature, not global controller/service/mapper/entity layers.
- External API base is `/api/v1`; BIGINT identifiers serialize as strings; errors use the existing ProblemDetail model.
- Access tokens stay in frontend memory. Long-lived refresh credentials are never stored in `localStorage`.
- No OAuth provider, SAML, SCIM, LDAP, MFA platform, Keycloak clone, microservices, Kafka, or new infrastructure product in this feature.

## 3. Delivery boundary

### Included

- full AIC-011 schema, including IAM, organization/master-data tables, early `audit_event`, and `api_idempotency`
- V1 role/permission reference seed
- public registration behind a feature flag
- invitation acceptance
- password credential storage and verification
- account status checks
- fixed-window login rate limits
- short-lived access JWT
- Redis refresh sessions and atomic rotation
- cross-tab race handling and replay detection
- logout and logout-all
- password-forgot/reset with single-use reset token
- security-version invalidation
- `/auth/me`
- React login/register/invite/forgot/reset/session-bootstrap/logout flow
- end-to-end and failure-path tests

### Excluded

The following stay in the second M1 vertical feature (`feat/m1-organization-authorization-e2e`):

- permission/data-scope enforcement for business resources (AIC-017)
- Organization/Project/Team/CostCenter management APIs (AIC-018)
- Admin/Project Settings UI (AIC-020)

Roles and permissions are seeded now because authentication and later authorization share the same reference model, but this feature does not pretend that full business authorization is complete.

## 4. Backend feature boundaries

Use focused feature packages under `com.aicostops`:

```text
iam/
  api/
  application/
  domain/
  infrastructure/

organization/
  domain/              # only types needed by membership/registration in this feature
  infrastructure/      # minimal lookup/persistence needed by auth

audit/
  application/
  infrastructure/
```

Do not introduce a global `common/service`, `utils`, JPA entities, or MyBatis-Plus.

Persistence mappers remain explicit SQL. Correctness-sensitive queries and state transitions must be visible in SQL/tests.

## 5. Database model

AIC-011 creates the frozen M1 tables:

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

Key frozen rules:

- IDs are `BIGINT AUTO_INCREMENT`.
- `app_user.email_normalized` is globally unique.
- `app_user.security_version` is durable invalidation state.
- credentials are separated into `user_credential`; plaintext passwords/reset tokens are never stored.
- `organization_member` is unique by `(org_id,user_id)`.
- role assignments are unique by member/role/scope tuple.
- master data uses ACTIVE/ARCHIVED/DISABLED semantics rather than deleting historical references.
- `audit_event` is append-only and never records passwords, refresh tokens, reset tokens, JWT signing secrets, or full API keys.

Flyway migrations must be reviewable and forward-only. Empty MySQL 8.4 must migrate successfully from zero.

## 6. Role and permission seed

Seed exactly the frozen V1 roles:

```text
EMPLOYEE
PROJECT_OWNER
FINANCE_REVIEWER
FINANCE_ADMIN
SYSTEM_ADMIN
```

Seed the existing permission catalog from `docs/02-development/detailed-design/06-permission-matrix.md` without inventing new permission names.

Important invariant: `SYSTEM_ADMIN` does not automatically receive finance posting, correction, budget-management, period-close, or period-reopen powers.

AIC-012 tests must prove the seed rows and mappings match the frozen permission matrix.

## 7. Public-registration organization rule

The frozen design states that V1 exposes one active organization while public registration is a demo feature. To avoid silently creating organizations from anonymous requests, use this explicit rule:

- `ALLOW_PUBLIC_REGISTRATION=false` remains the enterprise-safe default.
- when public registration is enabled, the server requires a configured `PUBLIC_REGISTRATION_ORG_SLUG` identifying an existing ACTIVE organization.
- registration creates an `app_user`, `user_credential`, ACTIVE `organization_member`, and default `EMPLOYEE` role assignment in that configured organization.
- if the configured organization is missing/inactive, registration fails as a server configuration/dependency error; it never auto-creates an organization.
- local dev/test may provide a deterministic development bootstrap organization through a dev-only bootstrap mechanism; production Flyway must not silently seed a demo tenant.

This resolves a gap in the high-level design while preserving the single-active-organization V1 boundary.

## 8. Password handling

Use Spring Security's delegating password encoder (`PasswordEncoderFactories.createDelegatingPasswordEncoder()`), persisting the encoded value including its algorithm prefix. Do not implement custom password crypto.

Login error responses must not reveal whether an email exists. Wrong email and wrong password both map to `401 AUTH_INVALID_CREDENTIALS`.

Email identity is normalized consistently before lookup and uniqueness checks (trim + locale-independent lowercase). The raw submitted email is not a second identity key.

## 9. Access JWT

Default access lifetime: 15 minutes, configurable.

JWT contains only the minimum frozen claims:

```text
sub = user id as decimal string
sv  = security_version
jti
iat
exp
```

Do not embed large role/permission/project lists into JWTs.

For V1 modular-monolith deployment, use an HMAC SHA-256 signing key supplied only through configuration/environment. No real signing secret is committed. Tests use an isolated test secret.

Authentication of bearer requests validates signature/expiry and maps disabled/invalid sessions to the existing ProblemDetail contract. Full business permission/data-scope evaluation is AIC-017, not this feature.

## 10. Login rate limiting

Use the frozen Redis fixed-window policy:

```text
aicostops:v1:ratelimit:login:ip:{ipHash}:{windowId}
aicostops:v1:ratelimit:login:account:{accountHash}:{windowId}
```

Default configurable limits:

```text
IP:      20 / 15 min
Account:  8 / 15 min
```

Behavior:

1. rate-limit check occurs before credential verification;
2. account identifier in Redis keys is hashed, not raw email;
3. exceeded limit returns `429 AUTH_RATE_LIMITED` and a useful `Retry-After`;
4. Redis unavailable during login fails closed with `503 REDIS_UNAVAILABLE_FOR_AUTH` after a short timeout;
5. a Redis outage for login rate limiting does not invalidate an already-valid access token.

## 11. Refresh-session transport and Redis schema

Refresh cookie value:

```text
sessionId.secret
```

The random secret is high entropy. Redis stores only its one-way digest, never the reusable secret.

Redis key:

```text
aicostops:v1:auth:refresh:{sessionId}
```

Hash fields follow the frozen Redis design:

```text
user_id
org_member_id
security_version
current_token_hash
previous_token_hash
previous_valid_until_ms
created_at_ms
last_rotated_at_ms
absolute_expires_at_ms
device_label
```

Defaults, all configurable:

```text
Access token:               15 min
Refresh session:             7 days
Previous-token race window: 10 sec
```

Cookie policy:

- HttpOnly
- SameSite=Strict
- Secure=true outside local development
- Path restricted to `/api/v1/auth`
- no refresh token in response JSON or browser localStorage

Because refresh is cookie-authenticated ambient authority, `/auth/refresh` and cookie-based logout must also enforce same-origin request validation using the configured allowed Origin. SameSite is defense-in-depth, not the only server-side check.

## 12. Atomic refresh rotation

Use the frozen small Redis Lua script for compare-and-rotate. The script returns only these domain outcomes:

```text
ROTATED
RACE
REPLAY
EXPIRED
```

Rules:

- current hash match => move current to previous, set short race deadline, write new current hash atomically;
- previous hash match inside race window => `409 AUTH_REFRESH_RACE`;
- previous/unknown old token outside the permitted race => `401 AUTH_REFRESH_REPLAY`, revoke the session, audit the replay;
- expired/missing session => `401 AUTH_SESSION_EXPIRED`.

Frontend behavior for `AUTH_REFRESH_RACE`: short wait, then retry refresh once. Never loop indefinitely.

## 13. Logout and logout-all

`POST /auth/logout` is repeat-safe:

- delete/revoke the current Redis refresh session when present;
- clear the refresh cookie;
- audit logout;
- repeated logout remains successful and does not leak session existence.

`POST /auth/logout-all`:

- durable transaction increments MySQL `app_user.security_version`;
- best-effort removes known Redis refresh sessions;
- old access JWTs become invalid according to the security-version policy;
- new sessions use the new version;
- audit the revocation.

MySQL `security_version`, not Redis deletion, is the durable invalidation signal.

## 14. Password forgot/reset

Redis key:

```text
aicostops:v1:auth:reset:{tokenId}
```

Value contains only:

```text
user_id
token_hash
```

Default TTL: 30 minutes.

`POST /auth/password/forgot` always returns a generic success-shaped response for syntactically valid requests, regardless of whether the account exists, preventing account enumeration. It is rate limited.

A development mail sink/mock is allowed, but reset secrets must not be written to normal application logs. Automated tests may capture the dev sink directly to obtain the token.

Successful `POST /auth/password/reset`:

1. atomically consumes the single-use reset key;
2. updates the password credential;
3. increments `security_version`;
4. revokes refresh sessions best-effort;
5. audits `PASSWORD_CHANGED` / session revocation;
6. old reset token cannot be reused.

Disabled accounts cannot reset into an active session.

## 15. Invitation acceptance

The `invitation` row holds only token hash and invitation metadata. Acceptance uses the existing `/invitations/{token}/accept` contract and is single-use.

Acceptance validates:

- invitation status
- expiry
- normalized email uniqueness
- target organization is active
- initial role code is an allowed seeded role

On success it creates/links the user, credential, organization membership and initial role assignment in one transaction, then marks the invitation accepted. Duplicate/expired/used invitations must not leave partial identity state.

Creating/administering invitations through the full admin UI is not required by this vertical slice; acceptance behavior is.

## 16. `/auth/me`

`GET /auth/me` returns the authenticated identity and enough current organization/session context for the frontend shell. It must source durable status from MySQL (with only safe short-lived caching where already designed) and must not trust permission lists embedded in JWT because none are embedded.

The response is the frontend bootstrap truth after access-token acquisition. Full scoped authorization computation is completed in AIC-017; this feature may return seeded role/permission context needed by current auth UX without claiming business-resource authorization is complete.

## 17. API contract

Keep the existing OpenAPI/interface-matrix endpoint names under server base `/api/v1`:

```text
POST /auth/register
POST /auth/login
POST /auth/refresh
POST /auth/logout
POST /auth/logout-all
POST /auth/password/forgot
POST /auth/password/reset
GET  /auth/me
POST /invitations/{token}/accept
```

This feature must replace placeholder object schemas in `openapi.yaml` with concrete request/response schemas for the endpoints it implements. Do not invent alternate route names.

Use the existing ProblemDetail codes where applicable:

```text
AUTH_INVALID_CREDENTIALS
AUTH_ACCESS_EXPIRED
AUTH_SESSION_EXPIRED
AUTH_REFRESH_REPLAY
ACCOUNT_DISABLED
AUTH_REFRESH_RACE
AUTH_RATE_LIMITED
REDIS_UNAVAILABLE_FOR_AUTH
```

New auth-specific codes may be added only when an existing code cannot describe a real contract state; they must be added to the error-contract documentation and tests at the same time.

## 18. Audit

At minimum write append-only audit events for:

```text
LOGIN_SUCCESS
LOGIN_FAILED
LOGOUT
PASSWORD_CHANGED
SESSION_REVOKED
```

Also audit refresh replay and invitation acceptance with non-secret metadata.

Never audit password text, refresh secret/token, reset token, JWT signing secret, or full bearer JWT.

## 19. Frontend flow

Keep the existing M0 Axios client, memory access-token store, QueryClient and ProtectedRoute foundation. Extend `frontend/src/features/auth/` rather than creating a second auth stack.

Public routes:

```text
/login
/register
/forgot-password
/reset-password
/invite/:token
```

Application bootstrap:

```text
POST /auth/refresh
→ store access token in memory
→ GET /auth/me
→ render protected app
```

If refresh is expired/missing:

```text
clear auth state
→ /login
```

401 handling:

- one in-tab single-flight refresh promise;
- original request retries at most once;
- `AUTH_REFRESH_RACE` waits briefly and retries refresh once;
- no recursive/infinite refresh loops;
- logout clears token/query auth state before redirecting.

Frontend errors use the existing ProblemDetail mapper. Do not show stack traces or account-existence hints.

## 20. Security configuration

Replace the M0 deny-all placeholder with explicit M1 auth boundaries:

Public:

```text
/actuator/health/**
/api/v1/auth/register
/api/v1/auth/login
/api/v1/auth/refresh
/api/v1/auth/password/forgot
/api/v1/auth/password/reset
/api/v1/invitations/*/accept
```

Authenticated:

```text
/api/v1/auth/logout
/api/v1/auth/logout-all
/api/v1/auth/me
```

Everything else remains denied by default until its owning feature explicitly opens it. Do not broadly change to `anyRequest().authenticated()` and accidentally expose unfinished M1+/business endpoints.

## 21. Transactions and failure behavior

MySQL transactions own durable identity changes:

- registration user + credential + membership + default role
- invitation acceptance
- password reset credential + security-version increment
- logout-all security-version increment

Redis operations cannot be the durable commit point for those MySQL changes.

Where an operation needs both MySQL and Redis, define safe ordering so Redis failure cannot create a false durable identity state. Best-effort Redis revocation is acceptable only where MySQL security-version increment is already the durable guard.

Login/refresh creation failures must not return credentials to the client unless the required Redis session write succeeded.

## 22. Test strategy

Behavior code is test-first. Generated/config artifacts use executable validation.

### Backend unit/behavior tests

- email normalization
- duplicate email
- public registration disabled
- missing/inactive configured registration organization
- password encode/verify
- generic invalid-credential response
- disabled account
- JWT claims/expiry/security version
- login IP/account rate limits
- Redis-down login fail-closed
- refresh expiry
- refresh rotation
- replay revocation
- cross-tab race
- logout repeat-safe
- logout-all security-version bump
- reset single-use/TTL/session invalidation
- invitation expired/used/duplicate email
- audit secret-redaction invariants

### MySQL Testcontainers integration

- empty-database migration
- unique/FK/core indexes
- role/permission seed
- transactional registration
- transactional invitation acceptance
- password-reset durable invalidation

### Redis integration

Use a real Redis test container for rotation Lua, expiry, race/replay and rate-limit behavior. Do not mock away the atomicity being tested.

### Frontend tests

- login/register forms
- bootstrap refresh success/failure
- ProtectedRoute after bootstrap
- single-flight 401 refresh
- refresh-race one-time retry
- no infinite retry loop
- logout clears auth state
- forgot/reset/invite flows
- ProblemDetail display

### End-to-end acceptance

From a clean checkout / clean DB / empty Redis:

1. Compose builds and starts.
2. public-registration demo configuration can create a user in the configured dev organization.
3. user logs in and reaches a protected shell.
4. `/auth/me` returns current identity.
5. refresh rotation returns a new access token/session secret.
6. old refresh replay is rejected.
7. logout prevents refresh reuse.
8. password reset invalidates prior sessions.
9. all existing GitHub required checks remain green.

## 23. Git and PR strategy

Implementation branch:

```text
feat/m1-authentication-e2e
```

The branch starts from the latest protected `main` after this design is accepted.

Use logical commits inside the branch (schema, seed, registration, login, refresh, reset, frontend, docs/tests), but deliver one Authentication E2E PR. The PR body should close:

```text
#16 #17 #18 #19 #20 #21 #24
```

It must not close AIC-017/#22, AIC-018/#23 or AIC-020/#25.

No direct push to `main`. Required checks and strict up-to-date policy stay enabled.

## 24. Success definition

Authentication E2E is complete only when the repository can demonstrate this user-visible path:

```text
register or invitation accept
→ login
→ access protected shell
→ access token expires / refresh succeeds
→ refresh rotation is race/replay safe
→ logout works
→ forgot/reset works and invalidates old sessions
```

and the implementation preserves the frozen MySQL/Redis/security boundaries above.