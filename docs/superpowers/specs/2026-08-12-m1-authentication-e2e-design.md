# M1 Authentication E2E Design

## 1. Purpose

Deliver the first complete M1 vertical feature: a user can register or accept an invitation, authenticate with a password, receive a short-lived access JWT plus a Redis-backed rotating refresh session, recover a password, log out, and use the React application through the existing protected-route/API-client foundation.

Tracked requirements:

- AIC-011 / GitHub #16
- AIC-012 / GitHub #17
- AIC-013 / GitHub #18
- AIC-014 / GitHub #19
- AIC-015 / GitHub #20
- AIC-016 / GitHub #21
- AIC-019 / GitHub #24

Delivery is vertical: one feature branch and one integration PR may close all seven issues, while every issue keeps its individual acceptance criteria and tests.

## 2. Frozen constraints

- Java 21, Spring Boot 4.1.0, plain MyBatis (`mybatis-spring-boot-starter` 4.1.0 / MyBatis Core 3.5.19).
- MySQL 8.4 is durable identity truth.
- Redis is authentication runtime state, never durable financial or identity truth.
- Flyway is forward-only; critical MySQL behavior uses Testcontainers, never H2.
- Root package is `com.aicostops`; package by feature, not global controller/service/mapper/entity layers.
- External API base is `/api/v1`; BIGINT IDs serialize as strings; errors use the existing ProblemDetail model.
- Access tokens stay in frontend memory; long-lived refresh credentials never go to `localStorage`.
- No OAuth provider, SAML, SCIM, LDAP, MFA platform, Keycloak clone, microservices, Kafka, or new infrastructure product.

## 3. Delivery boundary

Included:

```text
full AIC-011 schema
V1 role/permission seed
public registration behind feature flag
invitation acceptance
password credentials
account status checks
login rate limits
short-lived access JWT
Redis refresh sessions + atomic rotation
cross-tab refresh race handling
replay detection
logout / logout-all
password forgot/reset
security_version invalidation
GET /auth/me
React auth/session flow
failure-path + integration tests
```

Excluded until the second M1 vertical feature:

```text
AIC-017 Permission / Data Scope Authorization
AIC-018 Organization / Project / Team / CostCenter APIs
AIC-020 Admin / Project Settings UI
```

Roles and permissions are seeded now because authentication and later authorization share the same reference model. This feature does not claim full business-resource authorization is complete.

## 4. Backend boundaries

Use focused packages:

```text
com.aicostops.iam.api
com.aicostops.iam.application
com.aicostops.iam.domain
com.aicostops.iam.infrastructure

com.aicostops.organization.domain
com.aicostops.organization.infrastructure

com.aicostops.audit.application
com.aicostops.audit.infrastructure
```

The organization package contains only the membership/organization behavior required by authentication in this feature. Do not introduce global `common/service`, generic `utils`, JPA entities, or MyBatis-Plus.

Persistence uses explicit SQL. Correctness-sensitive queries and state transitions must be visible in SQL and tests.

## 5. Database model

AIC-011 creates:

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

Frozen rules:

- IDs are `BIGINT AUTO_INCREMENT`.
- `app_user.email_normalized` is globally unique.
- `app_user.security_version` is durable invalidation state.
- password credentials are isolated in `user_credential`; plaintext passwords/reset tokens are never persisted.
- `organization_member` is unique by `(org_id,user_id)`.
- role assignments are unique by member/role/scope tuple.
- master data uses ACTIVE/ARCHIVED/DISABLED rather than deleting historical references.
- `audit_event` is append-only and never records secrets.

Empty MySQL 8.4 must migrate from zero successfully.

## 6. Role and permission seed

Seed exactly:

```text
EMPLOYEE
PROJECT_OWNER
FINANCE_REVIEWER
FINANCE_ADMIN
SYSTEM_ADMIN
```

Seed the existing permission catalog and mappings from `docs/02-development/detailed-design/06-permission-matrix.md`; do not invent names.

Invariant: `SYSTEM_ADMIN` does not automatically receive finance posting, correction, budget-management, period-close, or period-reopen powers.

## 7. Public registration and the single V1 organization

The frozen architecture says V1 exposes one active organization and public registration is demo-only. Anonymous registration must not silently create tenants.

Configuration:

```text
ALLOW_PUBLIC_REGISTRATION=false              # default
PUBLIC_REGISTRATION_ORG_SLUG=demo            # local dev/test value
```

When public registration is enabled:

1. lookup the configured ACTIVE organization by slug;
2. create `app_user` + `user_credential` + ACTIVE `organization_member` + `EMPLOYEE` role assignment in one MySQL transaction;
3. if the configured organization is absent/inactive, fail without creating partial identity data.

For local Docker development only, activate Spring profile `dev`. A `DevAuthenticationBootstrap` component under that profile inserts a deterministic organization with slug `demo` if it does not exist, using explicit SQL after Flyway has completed. It must be idempotent and must never run outside `dev`.

Production/default profile does not seed a demo organization and keeps public registration disabled.

## 8. Password and email identity

Use `PasswordEncoderFactories.createDelegatingPasswordEncoder()` and persist the encoded value including its algorithm prefix. Do not implement custom cryptography.

Normalize email with trim + locale-independent lowercase before lookup and uniqueness checks.

Login must not reveal whether an account exists. Unknown email and wrong password both return:

```text
401 AUTH_INVALID_CREDENTIALS
```

## 9. Access JWT and security-version validation

Default access lifetime: 15 minutes, configurable.

Claims:

```text
sub = user id as decimal string
sv  = security_version
jti
iat
exp
```

Do not put permission/project lists into the JWT.

Use HMAC SHA-256 for this modular-monolith V1, with a 256-bit-or-stronger signing secret supplied through environment/configuration. No real secret is committed; tests use an isolated test secret. `.env.example` may contain an explicitly non-production development value only if it is clearly documented as unsafe outside local development.

Current Authentication E2E endpoints that require bearer authentication (`/auth/me`, `/auth/logout`, `/auth/logout-all`) must validate the JWT `sv` claim against current account status/security version. Resolution policy:

1. read `aicostops:v1:auth:security:{userId}` when available;
2. on cache miss, read MySQL and repopulate the short cache;
3. if Redis is unavailable, safely fall back to MySQL for these authenticated auth endpoints;
4. reject a disabled account or stale `sv` claim.

This makes password reset/logout-all immediately durable through MySQL `security_version` without making Redis the truth. AIC-017 later extends the same model to business-resource authorization and sensitive actions.

## 10. Login rate limiting

Use the frozen fixed-window Redis keys:

```text
aicostops:v1:ratelimit:login:ip:{ipHash}:{windowId}
aicostops:v1:ratelimit:login:account:{accountHash}:{windowId}
```

Defaults:

```text
IP:      20 / 15 min
Account:  8 / 15 min
```

Rules:

- rate-limit before credential verification;
- hash account/IP identifiers used in keys;
- exceeded limit => `429 AUTH_RATE_LIMITED` + `Retry-After`;
- Redis unavailable during login => short timeout then `503 REDIS_UNAVAILABLE_FOR_AUTH`;
- already-valid access tokens are not invalidated because the login limiter is unavailable.

## 11. Refresh session

Cookie value:

```text
sessionId.secret
```

Secret is high entropy. Redis stores only a one-way digest.

Key:

```text
aicostops:v1:auth:refresh:{sessionId}
```

Hash fields:

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

Defaults:

```text
Access token:               15 min
Refresh session:             7 days
Previous-token race window: 10 sec
```

Cookie:

```text
HttpOnly
SameSite=Strict
Secure=true outside local dev
Path=/api/v1/auth
```

Refresh credentials never appear in response JSON or localStorage.

Because refresh is cookie-authenticated ambient authority, `/auth/refresh` and cookie-based `/auth/logout` must enforce exact same-origin `Origin` validation against configured allowed origins. Browser requests missing an acceptable Origin for these cookie-authenticated mutations are rejected. SameSite is defense-in-depth, not the sole CSRF control.

## 12. Atomic refresh rotation

Use the frozen Redis Lua compare-and-rotate operation with outcomes:

```text
ROTATED
RACE
REPLAY
EXPIRED
```

- current hash match => atomically move current to previous, set race deadline, write new current hash;
- previous hash inside race window => `409 AUTH_REFRESH_RACE`;
- previous/unknown old token outside allowed race => `401 AUTH_REFRESH_REPLAY`, revoke session, audit replay;
- missing/expired session => `401 AUTH_SESSION_EXPIRED`.

Frontend handles `AUTH_REFRESH_RACE` with one short delay and one refresh retry only. No loop.

## 13. Login, logout and logout-all

Login order:

```text
rate limit
→ MySQL user/credential lookup
→ account status
→ password verify
→ Redis refresh-session create
→ access JWT issue
→ audit
```

No access/refresh credentials are returned if required Redis session creation fails.

`POST /auth/logout` is repeat-safe: revoke current refresh session if present, clear cookie, audit, and still succeed on repetition.

`POST /auth/logout-all` performs a MySQL transaction that increments `app_user.security_version`, then best-effort removes Redis sessions and refresh/security caches. Durable invalidation is the MySQL version bump.

## 14. Password forgot/reset

Redis key:

```text
aicostops:v1:auth:reset:{tokenId}
```

Value:

```text
user_id
token_hash
```

Default TTL: 30 minutes.

`POST /auth/password/forgot` returns the same generic success-shaped response for any syntactically valid email, whether or not the account exists. It is rate limited.

A development mail sink/mock is allowed. Reset secrets must not be written to normal application logs. Automated tests capture the development sink directly to obtain reset tokens.

Reset must atomically validate-and-consume the single-use Redis token before durable password mutation. Successful reset then updates credential + increments `security_version` in one MySQL transaction, best-effort revokes refresh sessions, and audits the change. Reuse and expiry are rejected. Disabled accounts cannot reset into an active session.

## 15. Invitation acceptance

The invitation row stores token hash plus metadata, never the reusable secret.

`POST /invitations/{token}/accept` validates status, expiry, email uniqueness, active target organization, and seeded initial role. On success, user + credential + membership + role assignment + invitation accepted state commit in one MySQL transaction.

Expired, used or conflicting invitations leave no partial identity state.

Creating/administering invitations through the full admin UI is not part of this vertical feature; acceptance is.

## 16. API contract

Keep the existing OpenAPI/interface-matrix routes under `/api/v1`:

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

Replace placeholder request/response objects in `openapi.yaml` for implemented endpoints with concrete schemas. Do not rename routes.

Use existing ProblemDetail codes when applicable:

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

Any genuinely necessary new auth code must be added to the error-contract documentation and tested in the same change.

## 17. `/auth/me` and audit

`GET /auth/me` is the frontend bootstrap truth after access-token acquisition. It returns current identity plus the organization/member/role context needed by the shell. It never trusts permission arrays from JWT because JWT contains none.

Full business-resource scope evaluation remains AIC-017.

Append-only audit events include at least:

```text
LOGIN_SUCCESS
LOGIN_FAILED
LOGOUT
PASSWORD_CHANGED
SESSION_REVOKED
refresh replay
invitation acceptance
```

Never audit password text, refresh/reset secret, full JWT, or signing secret.

## 18. Frontend flow

Extend the M0 auth/API foundation in `frontend/src/features/auth/`; do not build a second Axios/auth stack.

Public routes:

```text
/login
/register
/forgot-password
/reset-password
/invite/:token
```

Bootstrap:

```text
POST /auth/refresh
→ access token to memory
→ GET /auth/me
→ render protected app
```

Missing/expired refresh => clear auth state and route to `/login`.

401 behavior:

- single in-tab refresh promise;
- original request retries at most once;
- `AUTH_REFRESH_RACE` waits briefly and retries refresh once;
- no recursive/infinite refresh loop;
- logout clears access token and auth-related Query cache before redirect.

All UX errors go through existing ProblemDetail mapping and never expose account-existence hints or stack traces.

## 19. Security configuration

Replace M0 deny-all only with explicit auth openings.

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

Everything else stays denied until its owning feature opens it. Do not switch to a broad `anyRequest().authenticated()` that exposes unfinished APIs.

## 20. Transactions and failure policy

MySQL transactions own durable identity changes:

```text
registration
invitation acceptance
password-reset credential + security_version
logout-all security_version
```

Redis never becomes the durable commit point for these changes.

Best-effort Redis revocation is acceptable only after the durable MySQL security-version guard exists. Login/refresh never return credentials unless required Redis session writes succeed.

## 21. Test strategy

Behavior code is test-first. Generated/config artifacts use executable validation.

Backend behavior tests:

```text
email normalization
duplicate email
public registration disabled
missing/inactive registration organization
dev bootstrap only under dev profile
password encode/verify
generic invalid credentials
disabled account
JWT claims/expiry
security-version stale/disabled rejection
security-version Redis miss -> MySQL fallback
login IP/account rate limits
Redis-down login fail-closed
invalid Origin on cookie-auth mutations
refresh expiry / rotation / replay / race
logout repeat-safe
logout-all security-version bump
reset single-use / TTL / session invalidation
invitation expired / used / duplicate email
audit secret-redaction
```

MySQL Testcontainers:

```text
empty DB migration
unique/FK/core indexes
role/permission seed
transactional registration
transactional invitation acceptance
password-reset durable invalidation
```

Redis integration uses a real Redis test container for rotation Lua, expiry, race/replay, security-version cache and rate limits. Do not mock away the atomicity being tested.

Frontend tests:

```text
login/register forms
bootstrap refresh success/failure
ProtectedRoute after bootstrap
single-flight 401 refresh
refresh-race single retry
no infinite loop
logout state clearing
forgot/reset/invite flows
ProblemDetail UX
```

End-to-end acceptance from clean DB/Redis:

1. Compose builds and starts.
2. dev profile has deterministic `demo` org and public registration enabled only by local config.
3. user registers and logs in.
4. protected shell renders and `/auth/me` returns current identity.
5. refresh rotation succeeds.
6. replay of an obsolete refresh token is rejected.
7. logout prevents refresh reuse.
8. password reset invalidates prior sessions/security version.
9. all seven required GitHub checks pass.

## 22. Git / PR strategy

Implementation branch:

```text
feat/m1-authentication-e2e
```

Create it from latest protected `main` after this design is accepted. Use logical internal commits (schema, seed, registration, login, refresh, reset, frontend, docs/tests) but deliver one Authentication E2E PR.

That PR closes:

```text
#16 #17 #18 #19 #20 #21 #24
```

It must not close #22, #23 or #25.

No direct push to `main`; required checks and strict up-to-date policy remain enabled.

## 23. Success definition

Authentication E2E is complete only when this user-visible path works:

```text
register or invitation accept
→ login
→ protected shell
→ access token expiry / refresh
→ race/replay-safe rotation
→ logout
→ forgot/reset
→ old sessions invalidated
```

while preserving the frozen MySQL, Redis, API, security and repository boundaries above.