# M1 Organization & Authorization E2E — Acceptance Evidence

**Branch:** `feat/m1-organization-authorization-e2e`
**Scope:** GitHub #22 (AIC-017 authorization), #23 (AIC-018 organization APIs), #25 (AIC-020 settings frontend)
**Date:** 2026-08-13

## 1. Verification commands

```powershell
# Backend full verification (real MySQL 8.4 + Redis Testcontainers)
Push-Location backend
.\mvnw.cmd clean verify
Pop-Location

# Frontend full verification
Push-Location frontend
npm ci
npm test -- --run
npm run lint
npm run build
Pop-Location

# Acceptance smoke against the real Docker Compose environment
docker compose --env-file .env.example -f compose.yaml -f compose.dev.yaml up -d --build
.\scripts\auth-smoke.ps1
.\scripts\organization-authorization-smoke.ps1
docker compose --env-file .env.example -f compose.yaml -f compose.dev.yaml down
```

## 2. Backend test evidence

`.\mvnw.cmd clean verify` reports `BUILD SUCCESS`:

- Surefire: 143 tests, 0 failures, 0 errors (1 POSIX-only skip)
- Failsafe: 140 tests, 0 failures, 0 errors

Authorization behavior is proven by HTTP-driven integration tests against real MySQL/Redis:

- `AuthorizationContextServiceIntegrationTest`, `RedisAuthorizationContextCacheIntegrationTest` — MySQL-truth context resolution, Redis 60s cache with fail-safe fallback (Redis loss never grants access).
- `M1AuthorizationServiceTest`, `RoleScopePolicyTest`, `M1AdminPermissionPolicyTest` — four-scope model, frozen Role scope matrix, missing permission is 403, wrong resource scope is privacy-preserving 404.
- `AuthorizationInvalidationServiceIntegrationTest`, `BearerAuthenticationFilterTest` — durable `security_version` bumps; old JWTs are rejected because bearer validation reads current MySQL truth.
- `IamReadApiIntegrationTest`, `IamMutationApiIntegrationTest` — organization-scoped users/roles/permissions, user status optimistic concurrency (`expectedVersion` decimal string, stale version always 409 even for same status, matching version + real change bumps exactly once), Role assignment/revoke matrix, invalidation and audit.
- `AdminInvitationServiceIntegrationTest`, `DevInvitationMailboxTest` — hash-only invitation tokens, TTL bounds, delivery failure rollback, audit without secret.
- `ProjectApiIntegrationTest`, `ProjectMembershipApiIntegrationTest`, `TeamApiIntegrationTest`, `TeamMembershipApiIntegrationTest`, `CostCenterApiIntegrationTest`, `ProviderAccountApiIntegrationTest` — scoped SQL pagination (count/rows share predicates), code uniqueness, lifecycle preservation (ACTIVE/DISABLED/ARCHIVED, no destructive delete), cross-org privacy 404, membership reactivation and `MEMBERSHIP_CHANGED` audit.
- `M1AuthorizationApiIntegrationTest`, `SecurityConfigurationTest` — exact `HttpMethod` + path route exposure, unsupported methods denied, final `anyRequest().denyAll()` effective, M2 routes remain denied.
- `MeApiIntegrationTest` — `/auth/me.permissions` sorted, deduplicated, M1-only projection without scopes.
- `M1OpenApiContractTest` — OpenAPI parses and the operation inventory plus every per-operation response-status set match the frozen contract exactly.

### Audit assertions for API-created subjects

Audit success is proven by HTTP-driven integration tests over API-created subjects, not by the smoke script:

- `IamMutationApiIntegrationTest` reads `audit_event` rows for `USER_DISABLED`, `USER_ENABLED`, `ROLE_ASSIGNED`, `ROLE_REVOKED` and asserts actor/org/subject/metadata (`previousStatus`/`newStatus`/`targetMemberId`, `roleCode`/`scopeType`/`scopeId`) plus `assertNoSecretMetadata` (metadata never contains `password`/`token`/`secret`/`jwt`/`apikey`).
- `ProjectMembershipApiIntegrationTest` and `TeamMembershipApiIntegrationTest` assert `MEMBERSHIP_CHANGED` rows for API-created memberships.
- `AdminInvitationServiceIntegrationTest` asserts the `INVITATION_CREATED` audit row for API-created invitations and that the raw token is absent.

## 3. Frontend test evidence

`npm test -- --run` reports 16 files / 67 tests passing; `npm run lint` and `npm run build` exit 0. Coverage includes:

- Permission-aware layout and six settings routes: nav hiding without READ permission, direct unauthorized URL renders authenticated 403 with no redirect and no child mount/request (`AuthenticatedLayout.test.tsx`, `PermissionRoute.test.tsx`).
- Users/Roles: loading/empty/error/data, independent `USER_MANAGE`/`USER_INVITE`/`ROLE_ASSIGN` action gating, `securityVersion` stays a string and is sent verbatim as `expectedVersion`, 409 shows the ProblemDetail and refetches without retry, mutation cache invalidation (`UsersPage.test.tsx`, `RolesPage.test.tsx`).
- Projects/Teams/Cost centers/Provider accounts: all query states, manage-gated actions, immutable code/providerCode, lifecycle editing, member drawers with pagination and exact invalidation; `PROJECT_MEMBER_MANAGE`/`TEAM_MANAGE` work without `USER_READ` (no hidden `/users` request; manual member-ID fallback), the submission target follows the current permission mode (a stale Select selection cannot leak into manual mode after `USER_READ` is revoked), provider metadata is edited as typed JSON with normalized secret-key client validation (arrays recursed), and an explicitly cleared `externalAccountRef` is sent as `""` (`ProjectsPage.test.tsx`, `TeamsPage.test.tsx`, `CostCentersPage.test.tsx`, `ProviderAccountsPage.test.tsx`).
- Authorization/session change behavior: a 403 refetches `/auth/me` exactly once (awaited before the original forbidden error is surfaced, never replaced by a refresh failure) and the mutation is not retried; a `401 AUTH_SESSION_EXPIRED` — whether on a retried request or from the refresh itself — clears the token and the whole query cache (auth and session-bound settings data), sets the session anonymous, redirects to `/login`, and never starts a refresh loop; a shared single-flight refresh failure emits the session-expired event exactly once for concurrent waiters (`useAuthorizationMutation.test.tsx`, `AuthSessionProvider.test.tsx`, `client.test.ts`).

## 4. Smoke evidence

Prerequisite: `.\scripts\auth-smoke.ps1` passes (PASS line printed).

`.\scripts\organization-authorization-smoke.ps1` runs against the real Docker Compose stack (MySQL, Redis, backend) and prints `ORG_AUTH_SMOKE_PASS`. Flow:

1. **Bootstrap boundary** — public registration identifies the organization member; the ONLY direct SQL inserts one ORG-scoped `SYSTEM_ADMIN` `role_assignment` for that member and increments its `security_version`; the administrator then logs in again.
2. `Assert-AdminPermissions` — `/auth/me.permissions` contains every M1 admin permission (USER/ROLE/PROJECT/TEAM/COST_CENTER/PROVIDER_ACCOUNT read+manage, USER_INVITE, ROLE_ASSIGN) and the identity is unchanged.
3. `Assert-WrongRole403` — a freshly registered EMPLOYEE cannot create a project (403 `FORBIDDEN`). Scoped-role behavior proven elsewhere: the PROJECT_OWNER project list is scoped to the explicit project (step 5), and a PROJECT-scoped SYSTEM_ADMIN grant gets 404 `RESOURCE_NOT_FOUND` for out-of-scope resource access and cannot satisfy the ORG-only create.
4. Master data through HTTP only — project/team/cost-center/provider-account create, list, and update; lifecycle status transitions persist; memberships add/list/remove through real APIs.
5. `Assert-WrongScope404` — a real `POST /role-assignments` grants PROJECT_OWNER at an explicit project; the member's list returns only that project and reads/updates of another real project return 404 `RESOURCE_NOT_FOUND`.
6. User status — disable with the displayed `securityVersion` as `expectedVersion`; the disabled user's token is rejected; re-enable with the returned version.
7. `Assert-OldJwt401` — revoking a real role assignment bumps the target user's version and the pre-revoke JWT is rejected with exactly `401 AUTH_SESSION_EXPIRED`.
8. Invitation — `POST /invitations` returns PENDING and the API response never exposes a raw token; the dev invitation mailbox intentionally delivers the `acceptLink` carrying the high-entropy token (Assert-AuditSecretAbsence asserts the API response and the mailbox link, not a clean delivery channel). Raw-token absence in `audit_event.metadata_json` is proven by the HTTP-driven integration tests cited in section 2 (no direct SQL from this script).
9. `Assert-M2Denied` — `/costs/charges` and `/budgets` remain denied by the final `anyRequest().denyAll()`.

Direct SQL was used only to bootstrap the first test administrator, not to satisfy #22/#23/#25 acceptance behavior.

## 5. Issue traceability

| Issue | Evidence |
|---|---|
| #22 AIC-017 — authorization context, four scopes, version invalidation, wrong-Role 403 / wrong-scope 404, SYSTEM_ADMIN/finance separation | Backend tests in section 2; smoke `Assert-WrongRole403`, `Assert-WrongScope404`, `Assert-OldJwt401`; frontend 403/refetch behavior |
| #23 AIC-018 — organization-bound APIs, scoped SQL, code uniqueness, membership lifecycle, synchronized API docs | Backend tests in section 2; `M1OpenApiContractTest`; smoke master-data and membership steps |
| #25 AIC-020 — six permission-aware settings pages, permission gating, lifecycle UX, shared ProblemDetail handling | Frontend tests in section 3 |
