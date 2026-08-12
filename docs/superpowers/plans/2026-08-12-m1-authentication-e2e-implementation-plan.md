# M1 Authentication E2E Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the complete M1 Authentication E2E vertical slice covering AIC-011/#16, AIC-012/#17, AIC-013/#18, AIC-014/#19, AIC-015/#20, AIC-016/#21, and AIC-019/#24.

**Architecture:** Keep the modular monolith and package-by-feature boundaries. MySQL is durable identity truth through explicit MyBatis SQL and transactions; Redis owns bounded runtime state for limits, refresh/reset tokens, and caches; Spring Security validates minimal HS256 JWTs; React extends the existing Axios, memory-token, QueryClient, ProtectedRoute, and single-flight foundations.

**Tech Stack:** Java 21, Spring Boot 4.1.0, plain MyBatis 4.1.0/Core 3.5.19, MySQL 8.4, Redis 8.8, Flyway, Testcontainers, React 19, TypeScript 6, Vite 8, React Router, TanStack Query, Axios, Vitest, React Testing Library, Docker Compose, Nginx same-origin proxy.

## Global Constraints

- Default and production keep `AICOSTOPS_ALLOW_PUBLIC_REGISTRATION=false`; only local development explicitly enables registration.
- Local development organization is `slug=local-dev`, `name=AI CostOps Local Development`; no default-profile organization seed exists.
- IDs remain MySQL `BIGINT AUTO_INCREMENT` and JSON decimal strings.
- Passwords use `PasswordEncoderFactories.createDelegatingPasswordEncoder()`; JWT uses Spring Security HS256 support with an environment-only secret of at least 256 bits.
- Refresh/reset secrets, plaintext passwords, full JWTs, and signing secrets are never stored in MySQL, Redis, browser storage, audit metadata, or normal logs.
- Refresh cookie is `HttpOnly`, `SameSite=Strict`, `Path=/api/v1/auth`, and secure outside local development.
- Cookie-authenticated refresh/logout enforce configured exact-origin validation.
- Every behavior change follows RED → verified failure → minimal GREEN → verified pass → refactor → verified pass.
- Flyway SQL, Lua, configuration, Docker, and OpenAPI are validated through executable integration/contract checks.
- Existing CI job names and test groups remain unchanged.
- Scope excludes AIC-017/#22 authorization/data scope, AIC-018/#23 organization CRUD, and AIC-020/#25 admin UI.

## File Structure Map

### Backend production files

- Modify `backend/pom.xml`: Redis and Spring Security JWT/resource-server dependencies; Redis Testcontainers support remains test-scoped through Testcontainers core.
- Modify `backend/src/main/resources/application.yml`: typed `aicostops.auth.*` defaults and Redis connection settings.
- Create `backend/src/main/resources/db/migration/V2__m1_identity_organization_schema.sql`: all AIC-011 tables, constraints, and indexes.
- Create `backend/src/main/resources/db/migration/V3__seed_v1_roles_permissions.sql`: exact roles, permission catalog, and mappings.
- Create `backend/src/main/resources/redis/refresh-rotate.lua`: atomic `ROTATED/RACE/REPLAY/EXPIRED` rotation.
- Delete `backend/src/main/java/com/aicostops/shared/security/M0SecurityConfiguration.java`; create `backend/src/main/java/com/aicostops/shared/security/AuthenticationSecurityConfiguration.java`: explicit public/authenticated/deny-all boundary and bearer filter wiring.
- Modify `backend/src/main/java/com/aicostops/shared/web/ProblemCode.java`: frozen authentication codes plus narrowly required registration/invitation/reset codes.
- Modify `backend/src/main/java/com/aicostops/shared/web/ProblemDetailAdvice.java`: validation/malformed/security ProblemDetail mapping and `Retry-After` support.
- Create `backend/src/main/java/com/aicostops/iam/domain/EmailAddress.java`: trim plus `Locale.ROOT` lowercase normalization.
- Create `backend/src/main/java/com/aicostops/iam/domain/AccountStatus.java`, `InvitationStatus.java`, `RefreshRotationOutcome.java`: closed domain states.
- Create `backend/src/main/java/com/aicostops/iam/application/AuthProperties.java`: validated registration, JWT, cookie, origin, rate-limit, reset, cache, and session settings.
- Create `backend/src/main/java/com/aicostops/iam/application/RegistrationService.java`, `InvitationAcceptanceService.java`, `LoginService.java`, `RefreshService.java`, `AuthenticatedUserService.java`, `LogoutService.java`, `PasswordResetService.java`: transaction/use-case boundaries.
- Create `backend/src/main/java/com/aicostops/iam/application/PasswordResetSink.java` and `backend/src/main/java/com/aicostops/iam/infrastructure/DevPasswordResetSink.java`: test/dev-only token delivery without logging.
- Create `backend/src/main/java/com/aicostops/iam/infrastructure/IamMapper.java`: explicit identity, credential, membership, role, invitation, and security-version SQL.
- Create `backend/src/main/java/com/aicostops/organization/infrastructure/OrganizationMapper.java`: configured organization lookup and dev-only idempotent insert.
- Create `backend/src/main/java/com/aicostops/audit/application/AuditService.java` and `backend/src/main/java/com/aicostops/audit/infrastructure/AuditMapper.java`: append-only sanitized events.
- Create `backend/src/main/java/com/aicostops/iam/infrastructure/RedisRateLimiter.java`, `RedisRefreshSessionRepository.java`, `RedisSecurityVersionCache.java`, `RedisPasswordResetRepository.java`: explicit Redis keys/schemas/failure policies.
- Create `backend/src/main/java/com/aicostops/iam/infrastructure/JwtTokenService.java`, `BearerAuthenticationFilter.java`, `OriginValidationFilter.java`, `PasswordConfiguration.java`: Spring Security cryptography and HTTP security adapters.
- Create `backend/src/main/java/com/aicostops/iam/infrastructure/DevAuthenticationBootstrap.java`: `dev`-profile local organization bootstrap after Flyway.
- Create `backend/src/main/java/com/aicostops/iam/api/AuthController.java`, `InvitationController.java`, and focused request/response records under `iam/api`: frozen `/api/v1` contracts.

### Backend tests

- Modify `backend/src/test/java/com/aicostops/testsupport/MySqlContainerSupport.java`: reusable MySQL properties and cleanup helpers.
- Create `backend/src/test/java/com/aicostops/testsupport/RedisContainerSupport.java`: real Redis 8.8 container properties.
- Create `backend/src/test/java/com/aicostops/M1SchemaIntegrationTest.java`, `RolePermissionSeedIntegrationTest.java`: schema/seed executable checks.
- Create tests mirroring each application class under `backend/src/test/java/com/aicostops/iam/...` and `audit/...`.
- Create `backend/src/test/java/com/aicostops/iam/infrastructure/RedisAuthenticationIntegrationTest.java`: real rate-limit, TTL, Lua rotation/race/replay/revoke/cache coverage.
- Create `backend/src/test/java/com/aicostops/iam/AuthenticationApiIntegrationTest.java`: real MySQL+Redis HTTP path and transactional failure coverage.
- Delete `backend/src/test/java/com/aicostops/shared/security/M0SecurityConfigurationTest.java`; create `backend/src/test/java/com/aicostops/shared/security/AuthenticationSecurityConfigurationTest.java`.
- Modify `backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java`: enforce feature packages without weakening shared constraints.

### Frontend

- Modify `frontend/src/api/client.ts`: credentials, refresh-route exclusion, single-flight, one retry, race retry, and auth-failure callback.
- Modify `frontend/src/app/providers/AppProviders.tsx`, `frontend/src/app/router/AppRouter.tsx`, `frontend/src/app/router/ProtectedRoute.tsx`, and `frontend/src/styles.css`: bootstrap-aware session provider and routes without replacing the shell.
- Create `frontend/src/features/auth/authApi.ts`, `authTypes.ts`, `AuthProvider.tsx`, `AuthForm.tsx`, `LoginPage.tsx`, `RegisterPage.tsx`, `ForgotPasswordPage.tsx`, `ResetPasswordPage.tsx`, `InvitationAcceptPage.tsx`, `LogoutButton.tsx`.
- Create/modify adjacent `*.test.ts(x)` files for every auth behavior and route.

### Contracts, runtime, evidence

- Modify `docs/superpowers/specs/2026-08-12-m1-authentication-e2e-design.md`: terminology-only `demo` → local development/local-dev correction.
- Modify `docs/02-development/api/openapi.yaml` and `docs/02-development/api/04-错误码幂等并发.md`: concrete authentication schemas, responses, and codes.
- Modify `.env.example`, `compose.yaml`, `compose.dev.yaml`: explicit dev profile, registration org, allowed origin, cookie security, and non-production JWT value.
- Create `docs/03-acceptance/implementation/09-m1-authentication-e2e-evidence.md`: only executed commands/results/counts/warnings/limitations.
- Create `scripts/auth-e2e-smoke.ps1`: executable clean-compose authentication acceptance flow.

---

### Task 1: AIC-011 schema and AIC-012 reference data

**Interfaces:**
- Produces MySQL tables named in the design and seeded role/permission codes consumed by all later tasks.
- `role_assignment.scope_type='ORG'` and `scope_id=organization.id` identify the default registration/invitation assignment.

- [ ] **Step 1: Add failing MySQL integration assertions.** In `M1SchemaIntegrationTest`, query `information_schema.tables`, `statistics`, and constraint metadata for all 16 tables; insert duplicates/invalid foreign keys and assert MySQL rejects them. In `RolePermissionSeedIntegrationTest`, assert literal sets of five roles, all 48 documented permissions, exact role mappings, and the five forbidden finance permissions absent from `SYSTEM_ADMIN`.
- [ ] **Step 2: Verify RED.** Run `cd backend; .\mvnw.cmd -B -Dgroups=integration -Dit.test=M1SchemaIntegrationTest,RolePermissionSeedIntegrationTest verify`. Expected failure: missing `organization`/`role` tables.
- [ ] **Step 3: Add minimal migrations.** Implement `V2__m1_identity_organization_schema.sql` with explicit InnoDB/utf8mb4 definitions and `V3__seed_v1_roles_permissions.sql` with literal catalog/mapping inserts.
- [ ] **Step 4: Verify GREEN and migration replay.** Run the focused command, then `cd backend; .\mvnw.cmd -B -Dgroups=integration verify`; both must exit 0.
- [ ] **Step 5: Refactor only duplicate test metadata helpers, rerun integration tests, run `git diff --check`, and commit `feat(iam): establish M1 identity schema and reference data`.

### Task 2: Local Development Bootstrap

**Interfaces:**
- Produces `OrganizationMapper.insertLocalDevelopmentOrganizationIfMissing(String slug, String name)` and profile-scoped `DevAuthenticationBootstrap`.
- Uses `aicostops.auth.public-registration-org-slug=local-dev` only when the `dev` profile explicitly enables registration.

- [ ] **Step 1: Add failing tests.** `DevAuthenticationBootstrapIntegrationTest` starts dev and non-dev contexts, asserts the literal local organization exists only in dev, and invokes bootstrap twice to assert one row.
- [ ] **Step 2: Verify RED.** Run `cd backend; .\mvnw.cmd -B -Dgroups=integration -Dit.test=DevAuthenticationBootstrapIntegrationTest verify`. Expected failure: no `local-dev` organization.
- [ ] **Step 3: Implement minimal mapper/bootstrap/config.** Use `INSERT ... SELECT ... WHERE NOT EXISTS`, `@Profile("dev")`, `ApplicationRunner`, and typed properties. Set default registration false.
- [ ] **Step 4: Verify GREEN, then verify default context.** Run focused integration test and `cd backend; .\mvnw.cmd -B -Dtest=AiCostOpsApplicationTest test`.
- [ ] **Step 5: Apply terminology-only spec/config edits, rerun, `git diff --check`, and commit `feat(iam): add local development authentication bootstrap`.

### Task 3: Registration and Invitation Acceptance

**Interfaces:**
- `RegistrationService.register(RegisterCommand): RegisteredIdentity`.
- `InvitationAcceptanceService.accept(String rawToken, AcceptInvitationCommand): RegisteredIdentity`.
- `IamMapper` exposes explicit insert/lookups; service methods are `@Transactional`.
- API request fields are `email`, `displayName`, `password`; invitation request fields are `displayName`, `password` because invitation email is authoritative.

- [ ] **Step 1: RED email/password unit behavior.** Add `EmailAddressTest` for whitespace and Turkish-locale independence and `PasswordConfigurationTest` for delegating `{bcrypt}` encoding. Run `cd backend; .\mvnw.cmd -B -Dtest=EmailAddressTest,PasswordConfigurationTest test`; expect missing types.
- [ ] **Step 2: GREEN email/password primitives.** Add exact domain record and encoder bean, rerun focused tests.
- [ ] **Step 3: RED transactional registration.** Add integration cases for disabled flag, success, duplicate normalized email, missing/inactive configured org, and forced role-assignment failure leaving zero user/credential/member rows. Run focused test; expect missing service/route.
- [ ] **Step 4: GREEN registration.** Implement one `@Transactional` service and `POST /api/v1/auth/register`; map duplicate to conflict ProblemDetail without exposing SQL.
- [ ] **Step 5: RED invitation behavior.** Insert invitations with SHA-256 token hashes and assert expired, used, wrong token, duplicate email, inactive org, success, and forced rollback.
- [ ] **Step 6: GREEN invitation behavior.** Lock invitation row `FOR UPDATE`, compare a stable digest, create four identity rows, mark accepted in the same transaction, audit sanitized `INVITATION_ACCEPTED`, and expose `POST /api/v1/invitations/{token}/accept`.
- [ ] **Step 7: Run focused unit/integration and affected backend unit suites, `git diff --check`, and commit `feat(iam): implement registration and invitation acceptance`.

### Task 4: Login, rate limiting, access JWT, and audit

**Interfaces:**
- `RedisRateLimiter.checkLogin(String remoteIp, String normalizedEmail): RateLimitDecision` using hashed identifiers.
- `JwtTokenService.issue(long userId, long securityVersion): IssuedAccessToken`; claims are exactly `sub`, `sv`, `jti`, `iat`, `exp` plus JOSE headers.
- `LoginService.login(LoginCommand, ClientContext): LoginResult` returns access token metadata and a refresh cookie value only to the controller adapter.
- `AuditService.append(String eventType, Long actorUserId, Map<String,Object> metadata)` rejects secret-like metadata keys.

- [ ] **Step 1: RED JWT and audit unit tests.** Assert HS256, exact claims, configured expiry, short-secret startup rejection, and metadata key redaction. Run focused tests; expect missing services.
- [ ] **Step 2: GREEN JWT and audit primitives.** Use Spring `NimbusJwtEncoder`/`NimbusJwtDecoder`, configured `Clock`, explicit audit insert, and allowlisted metadata.
- [ ] **Step 3: RED real Redis limit integration.** Assert IP 20/15m and account 8/15m defaults, TTL, hashed keys without raw email/IP, `Retry-After`, and Redis-down decision. Run `RedisAuthenticationIntegrationTest`; expect missing keys/adapter.
- [ ] **Step 4: GREEN limiter.** Implement atomic `INCR` plus first-write `EXPIRE`, short Redis timeouts, and fail-closed mapping to `REDIS_UNAVAILABLE_FOR_AUTH`.
- [ ] **Step 5: RED login application/API.** Assert success ordering by observable side effects, generic unknown/wrong-password 401, disabled 403, rate-limit 429, Redis-down 503, no token when session create fails, and sanitized success/failure audit rows.
- [ ] **Step 6: GREEN login.** Implement the frozen order, create the Redis refresh session before issuing response credentials, set cookie, and expose `/api/v1/auth/login`.
- [ ] **Step 7: Run focused unit/Redis/API tests and affected suites, `git diff --check`, and commit `feat(auth): implement password login and access JWT`.

### Task 5: Bearer authentication and security-version truth

**Interfaces:**
- `AuthenticatedUserService.authenticate(String jwt): AuthenticatedIdentity` validates signature/expiry, loads Redis security cache then MySQL, safely populates cache, and falls back to MySQL on Redis errors.
- `AuthenticatedIdentity` contains user ID, member ID, organization ID/name/slug, display name, email, security version, and assigned role codes.
- `BearerAuthenticationFilter` installs a Spring `Authentication` only for a valid active, current-version identity.

- [ ] **Step 1: RED service tests.** Assert valid JWT, invalid signature, expired JWT → `AUTH_ACCESS_EXPIRED`, disabled account → `ACCOUNT_DISABLED`, stale `sv` rejection, cache hit, cache miss/MySQL populate, and Redis-down/MySQL fallback.
- [ ] **Step 2: Verify RED.** Run focused unit/integration tests; expect missing authentication service/filter.
- [ ] **Step 3: GREEN service/filter.** Implement cache value `{status,securityVersion}`, bounded TTL, explicit SQL identity lookup, and filter error forwarding to ProblemDetail.
- [ ] **Step 4: RED security boundary/API.** Assert only documented public routes are permit-all, `/auth/me|logout|logout-all` require bearer auth, and an unrelated `/api/v1/not-implemented` stays 403.
- [ ] **Step 5: GREEN boundary and `/auth/me`.** Replace M0 placeholder with explicit matchers and final `denyAll`; keep CSRF framework disabled because origin filter protects the cookie-auth mutations and access auth is bearer-based.
- [ ] **Step 6: Run focused tests plus architecture suite, `git diff --check`, and commit `feat(auth): enforce security version and authentication boundary`.

### Task 6: Atomic refresh rotation and origin protection

**Interfaces:**
- Cookie syntax is `sessionId.secret`; Redis stores SHA-256 digest only.
- `RedisRefreshSessionRepository.rotate(sessionId,currentHash,nextHash,nowMs,raceWindowMs): RefreshRotationOutcome` executes `refresh-rotate.lua`.
- `RefreshService.refresh(rawCookie): RefreshResult` maps `RACE`→409, `REPLAY`→revoke/audit/401, `EXPIRED`→401, `ROTATED`→new cookie/access JWT.
- `OriginValidationFilter` applies to `POST /api/v1/auth/refresh` and `POST /api/v1/auth/logout` when a refresh cookie is present.

- [ ] **Step 1: RED real Redis Lua tests.** Create sessions and assert all four literal outcomes, atomic field updates, TTL preservation, replay deletion, and revoke behavior. Run focused integration; expect script/repository missing.
- [ ] **Step 2: GREEN Lua/repository.** Implement server-side compare/rotate only; no Java GET/compare/SET sequence.
- [ ] **Step 3: RED refresh API and cookie tests.** Assert secure flags by profile, no secret in JSON, valid origin success, invalid/missing origin rejection, race 409, replay 401 plus audit, and expired 401.
- [ ] **Step 4: GREEN refresh/origin.** Implement parser, high-entropy generation via `SecureRandom`, digesting, exact configured origins, response cookie rotation, and domain-code mapping.
- [ ] **Step 5: Run focused Redis/API tests and affected suites, `git diff --check`, and commit `feat(auth): implement atomic refresh session rotation`.

### Task 7: Logout, logout-all, forgot/reset, and durable invalidation

**Interfaces:**
- `LogoutService.logout(AuthenticatedIdentity, Optional<String> refreshCookie)` is repeat-safe.
- `LogoutService.logoutAll(AuthenticatedIdentity)` increments MySQL `security_version` in a transaction before best-effort Redis cleanup.
- `PasswordResetService.forgot(String email, ClientContext)` always returns `PasswordResetAccepted` for syntactically valid email.
- `PasswordResetService.reset(String rawToken, String newPassword)` consumes Redis token once, then updates credential and version transactionally.

- [ ] **Step 1: RED logout tests.** Assert first/repeated logout both succeed and clear cookie; logout-all bumps exactly once; old JWT and refresh fail; simulated Redis delete failure does not roll back MySQL bump.
- [ ] **Step 2: GREEN logout.** Implement session delete, cookie expiration, sanitized audits, transactional version update, cache eviction, and best-effort session index cleanup.
- [ ] **Step 3: RED forgot/reset Redis tests.** Assert generic known/unknown response, hashed reset storage, 30-minute TTL, forgot rate limit, single-use atomic consume, expiry, replay, disabled account, and sink capture without logs.
- [ ] **Step 4: GREEN forgot/reset runtime.** Implement token `tokenId.secret`, Redis-side consume, dev/test sink, credential/version MySQL transaction, best-effort refresh revocation, and `PASSWORD_CHANGED`/`SESSION_REVOKED` audits.
- [ ] **Step 5: RED/Green invalidation API tests.** Prove old JWT and refresh are unusable after reset, then run all auth integration tests.
- [ ] **Step 6: Run affected backend suites, scan audit metadata assertions for forbidden secret keys, `git diff --check`, and commit `feat(auth): implement logout and password reset`.

### Task 8: Concrete OpenAPI and backend acceptance coverage

**Interfaces:**
- Concrete schemas: `RegisterRequest/Response`, `LoginRequest/Response`, `RefreshResponse`, `PasswordForgotRequest/Response`, `PasswordResetRequest`, `InvitationAcceptRequest/Response`, `MeResponse`, and `SuccessResponse`.
- Access/refresh response IDs are strings; refresh credential appears only in `Set-Cookie`.

- [ ] **Step 1: Add a failing OpenAPI parser/contract test** that loads YAML, resolves the nine auth operations, rejects placeholder `{type: object}` bodies, checks security overrides, string IDs, documented auth status responses, and absence of refresh-token fields.
- [ ] **Step 2: Verify RED.** Run `cd backend; .\mvnw.cmd -B -Dtest=OpenApiAuthenticationContractTest test`; expect placeholder-schema assertions to fail.
- [ ] **Step 3: GREEN contract/documentation.** Replace auth placeholders, add 429/503 responses where applicable, update error-code documentation, and align Java DTO names/fields.
- [ ] **Step 4: Expand `AuthenticationApiIntegrationTest` into the clean MySQL/Redis server-side acceptance path: bootstrap, register, login, me, rotate, replay reject, logout, forgot/reset, old-version reject.
- [ ] **Step 5: Run backend unit/integration/architecture groups, `git diff --check`, and commit `test(auth): complete backend Authentication E2E contracts`.

### Task 9: Frontend authentication session flow

**Interfaces:**
- `AuthProvider` exposes `{status:'loading'|'anonymous'|'authenticated', user, login, logout}`.
- `authApi` calls existing Axios client; refresh requests use `withCredentials=true`; refresh token has no TypeScript field.
- `ProtectedRoute` waits during bootstrap, renders authenticated content, otherwise redirects to `/login` with no refresh loop.

- [ ] **Step 1: RED API-client tests.** Add cases for credentials, excluding login/refresh 401 recursion, clearing state on refresh failure, one original retry, and `AUTH_REFRESH_RACE` delay plus exactly one refresh retry.
- [ ] **Step 2: GREEN API-client changes.** Extend existing client rather than creating a second Axios stack; make timer injection testable.
- [ ] **Step 3: RED bootstrap/logout provider tests.** Assert refresh→memory token→me→protected render, expired refresh→clear→login, concurrent 401 single-flight, and logout clears token plus auth Query cache then navigates.
- [ ] **Step 4: GREEN provider/routes.** Add session provider and route wiring for `/login`, `/register`, `/forgot-password`, `/reset-password`, `/invite/:token`, and `/app`.
- [ ] **Step 5: RED form tests.** For each page assert required validation, submitting state, success transition, and sanitized ProblemDetail rendering; forgot always uses generic copy.
- [ ] **Step 6: GREEN forms.** Implement small accessible enterprise forms with shared `AuthForm`; do not restructure the unrelated app shell.
- [ ] **Step 7: Run `npm test -- --run`, `npm run lint`, and `npm run build`; run `git diff --check`; commit `feat(frontend): implement authentication session flow`.

### Task 10: Compose acceptance and implementation evidence

**Interfaces:**
- Compose dev starts backend with `dev` profile, `local-dev` organization, registration enabled, allowed origin matching `http://localhost:${FRONTEND_PORT}`, and an explicitly unsafe local-only 32+ byte JWT secret.
- `scripts/auth-e2e-smoke.ps1` exits nonzero on any failed status/code assertion and never prints secrets.

- [ ] **Step 1: Add executable smoke script.** Use a temporary `WebRequestSession`, preserve the first refresh cookie for replay, query only public/auth endpoints, obtain reset token through the dev-only test sink endpoint guarded by dev profile, and assert every acceptance transition.
- [ ] **Step 2: Validate static runtime configuration.** Run `docker compose --env-file .env.example config --quiet`; fix only M1 auth configuration wiring.
- [ ] **Step 3: Run full fresh backend verification.** Execute unit, architecture, and integration commands from the repository requirements and record actual test counts/exit codes.
- [ ] **Step 4: Run full fresh frontend verification.** Execute `npm ci`, lint, tests, and build and record counts/exit codes.
- [ ] **Step 5: Run Docker verification.** Build, start, inspect health for MySQL/Redis/MinIO/backend/frontend, and assert frontend HTTP 200.
- [ ] **Step 6: Run `scripts/auth-e2e-smoke.ps1` against clean MySQL/Redis and record each verified transition.
- [ ] **Step 7: Write `09-m1-authentication-e2e-evidence.md` only from captured outputs, including `Not yet verified on real GitHub PR CI.`
- [ ] **Step 8: Run `git diff --check`, `git status`, inspect `git log --oneline --decorate -15`, and commit `docs(auth): record Authentication E2E contracts and evidence`.

## Plan Self-Review

- Coverage is explicit for #16 schema, #17 seed, #18 registration/invitation, #19 login/JWT/rate limit, #20 refresh/logout, #21 reset/security version, and #24 frontend.
- #22 authorization/data scope, #23 organization CRUD, and #25 admin UI have no implementation task.
- Names are consistent across tasks: `local-dev`, `aicostops.auth.*`, `/api/v1`, `sessionId.secret`, `security_version`, and the four Lua outcomes.
- Every behavior task specifies a failing test, RED command, minimal implementation, GREEN command, refactor/verification, and commit.
- Migrations, Lua, OpenAPI, Docker, and configuration have executable validation even where mechanical test-first is not required.
- No placeholder requirement remains in this plan.
