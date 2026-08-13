# M1 Organization & Authorization E2E Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver GitHub #22, #23, and #25 as one tested Organization & Authorization vertical slice: MySQL-truth authorization, IAM and organization APIs, and six permission-aware settings pages.

**Architecture:** Keep authorization context in the existing `iam` feature and organization resources in the existing `organization` feature. Application services make explicit permission/state decisions, MyBatis SQL applies organization and resource scopes before pagination, Redis caches versioned authorization contexts only, and the React application consumes a scope-free `/auth/me.permissions` projection for UX while the backend remains authoritative.

**Tech Stack:** Java 21, Spring Boot 4.1.0, MyBatis Core 3.5.19, mybatis-spring-boot-starter 4.1.0, MySQL 8.4, Redis, Testcontainers, React 19, TypeScript 6, Vite 8, React Router 7, TanStack Query 5, Ant Design 6, Axios 1, Vitest 4, Docker Compose.

## Global Constraints

- Work only on `feat/m1-organization-authorization-e2e`, based on `105a206486630f461c74e97ad91a6b71a15255b3`; implement #22/#23/#25 in one vertical integration PR.
- Follow `docs/superpowers/specs/2026-08-13-m1-organization-authorization-e2e-design.md`; do not re-open design decisions.
- Package by feature under `com.aicostops`; do not introduce global controller/service/mapper/entity layers.
- Use plain MyBatis and explicit authorization-sensitive SQL; do not add JPA, MyBatis-Plus, H2, Redux, or another framework.
- MySQL is durable identity and authorization truth. Redis is a 60-second authorization-context cache and existing authentication runtime only; Redis failure never grants access.
- Keep JWT claims minimal. Do not add permissions or project/team/cost-center IDs to JWTs.
- Use `/api/v1`, existing `ApiId` string serialization for BIGINT, existing `PageRequest(page,size)` defaults `0/50` and maximum size `200`, `PageResponse<T>`, `ProblemCode`, `DomainException`, and ProblemDetail.
- Missing permission is `403 FORBIDDEN`; nonexistent, foreign-organization, or out-of-scope resource is `404 RESOURCE_NOT_FOUND`; invalid Role/scope is `400 VALIDATION_FAILED`; natural-key conflict is `409 STATE_CONFLICT`.
- Use Testcontainers MySQL and Redis for persistence/cache behavior. Never SELECT all rows and filter authorization in Java; count and row queries use identical organization/scope/filter predicates.
- Preserve deny-by-default Spring Security. Open only completed M1 routes as authenticated; all M2 routes remain denied.
- Do not implement Organization CRUD, custom Roles/Permissions, Finance/M2 endpoints, OAuth/SAML/MFA/SCIM/LDAP, or unrelated refactors.
- All local commands below are PowerShell-compatible and run from `E:\AI-CostOps`.
- Every task follows RED → minimal GREEN → focused refactor → surrounding regression → `git diff --check` → one reviewable commit.

## Verified baseline at `1123ff03c41ab0c2f69c25c4b59558bc7df1e2b1`

- Authentication principal/filter chain exists as `AuthenticatedUser`, `SecurityVersionService.current/invalidate`, `BearerAuthenticationFilter`, `SecurityProblemWriter`, and `SecurityConfiguration`.
- IAM persistence/API exists as `IamMapper`, `AuthController`, `MeResponse`, `RedisRefreshSessionRepository.create/rotate/load/revoke/revokeAll`, `PasswordResetDelivery`, and `DevPasswordResetMailbox` with `PasswordResetDeliveryConfiguration`.
- Organization/audit/shared foundations exist as `OrganizationMapper`, `AuditService.append`, `PageRequest`, `PageResponse`, `ProblemCode`, and `ProblemDetailAdvice`.
- Frontend auth/routing/API foundations exist as `AuthSessionProvider`, `authSession`, `authApi`, `apiClient`, `ProtectedRoute`, `PublicRoute`, `AppRouter`, `toProblemDetail`, `PageResponse<T>`, and the TanStack `QueryClientProvider` in `AppProviders`.
- V2 already owns organization/IAM/master-data/audit tables and V3 owns the frozen Role/Permission seed. This plan requires no new Flyway migration; application transactions and existing natural constraints implement the approved behavior.
- Maven Surefire excludes `*IntegrationTest`; targeted unit tests use `-Dtest=... test`, and Testcontainers integration tests use Failsafe `-Dit.test=... verify`.

## Frozen shared interfaces and file map

The first four tasks establish these exact cross-task contracts:

```java
public enum ScopeType { ORG, PROJECT, TEAM, COST_CENTER }
public record ScopedPermissionGrant(String permissionCode, ScopeType scopeType, long scopeId) {}
public record AuthorizationContext(long userId, long organizationId,
        long organizationMemberId, long securityVersion,
        Set<ScopedPermissionGrant> grants) {}
public record ResourceScope(boolean organizationWide, Set<Long> resourceIds) {}

public interface AuthorizationContextCache {
    AuthorizationContext get(long userId, long securityVersion);
    void put(AuthorizationContext context);
    void evict(long userId, long securityVersion);
}

public final class AuthorizationContextService {
    public AuthorizationContext current(AuthenticatedUser user);
    public AuthorizationContext fresh(AuthenticatedUser user);
}

public final class M1AuthorizationService {
    public void requireOrg(AuthorizationContext context, String permissionCode);
    public ResourceScope requireList(AuthorizationContext context, String permissionCode, ScopeType resourceType);
    public void requireResource(AuthorizationContext context, String permissionCode,
            ScopeType resourceType, long resourceId);
}
```

Freeze Role assignment validity independently from endpoint permission applicability:

| Role | Valid scopes |
|---|---|
| `EMPLOYEE` | ORG |
| `PROJECT_OWNER` | PROJECT |
| `FINANCE_REVIEWER` | ORG, COST_CENTER |
| `FINANCE_ADMIN` | ORG |
| `SYSTEM_ADMIN` | ORG, PROJECT, TEAM, COST_CENTER |

The approved API inventory implemented by this plan is exactly:

```text
GET /users
GET /users/{id}
PATCH /users/{id}/status
GET /roles
GET /permissions
POST /role-assignments
DELETE /role-assignments/{id}
POST /invitations
GET /projects
POST /projects
PATCH /projects/{id}
GET /projects/{id}/members
POST /projects/{id}/members
DELETE /projects/{id}/members/{memberId}
GET /teams
POST /teams
PATCH /teams/{id}
GET /teams/{id}/members
POST /teams/{id}/members
DELETE /teams/{id}/members/{memberId}
GET /cost-centers
POST /cost-centers
PATCH /cost-centers/{id}
GET /provider-accounts
POST /provider-accounts
PATCH /provider-accounts/{id}
```

The frontend inventory is exactly `/settings/users`, `/settings/roles`, `/settings/projects`, `/settings/teams`, `/settings/cost-centers`, and `/settings/provider-accounts`.

IAM additions live under `backend/src/main/java/com/aicostops/iam/{api,application,domain,infrastructure}`. Organization additions use separate project/team/cost-center/provider-account controller, service, record, and mapper files under the existing `organization` feature. Frontend additions use `frontend/src/app/layout`, `frontend/src/app/router`, `frontend/src/features/settings/api`, and one directory per settings page; there is no pre-existing `frontend/src/shared` implementation to extend.

The Step 1 test names are frozen; use these exact Java methods or Vitest case titles so targeted commands and review evidence stay stable:

| Task | Required test methods/cases |
|---|---|
| 1 | `loadsAllSeededPermissionsWithExactAssignmentScope`, `rejectsInactiveOrAmbiguousMembership`, `rejectsSecurityVersionMismatch`, `keepsFinanceReviewerCostCenterGrants` |
| 2 | `cacheHitReturnsSameContext`, `cacheMissLoadsMysql`, `malformedCacheFallsBackMysql`, `redisUnavailableFallsBackMysql`, `inactiveMysqlIdentityIsDenied` |
| 3 | `roleScopeMatrixMatchesSpec`, `adminPermissionApplicabilityMatchesSpec`, `missingPermissionIsForbidden`, `wrongResourceScopeIsNotFound`, `financeGrantIsNotM1AdminAccess` |
| 4 | `authorizationChangeBumpsDurableVersion`, `afterCommitMaintainsRuntimeCaches`, `redisDownCannotKeepOldJwtValid`, `revokeAllUsesExistingSessionIndex` |
| 5 | `usersListAndDetailShareFullRepresentation`, `usersAreCurrentOrgOnly`, `usersCountMatchesRowPredicate`, `rolesAndPermissionsAreUnpaged`, `missingReadPermissionIsForbidden` |
| 6 | `roleScopeMatrixControlsAssignmentCreation`, `sensitiveMutationBypassesWarmContextCache`, `foreignTypedScopeIsNotFound`, `duplicateAssignmentIsConflict`, `roleRevokeBumpsVersionAndAudits`, `statusChangeBumpsVersionAndAudits` |
| 7 | `createsHashOnlyInvitationWithDefaultTtl`, `validatesInvitationLifetimeBounds`, `rejectsProjectOwnerOrgInvitation`, `deliveryFailureRollsBack`, `devMailboxWritesAcceptLinkWithoutLogging`, `defaultDeliveryFailsClosed` |
| 8 | `orgGrantListsCurrentOrgProjects`, `projectGrantListsOnlyExplicitProjects`, `createRequiresOrgGrant`, `wrongProjectScopeIsNotFound`, `projectCodeIsImmutableAndUnique`, `projectLifecyclePreservesRow` |
| 9 | `listsProjectMembersWithStablePageCount`, `crossOrgProjectMemberIsNotFound`, `addReactivatesExistingMembership`, `deleteDisablesWithoutDeleting`, `projectMembershipChangeBumpsVersionAndAudits` |
| 10 | `orgGrantListsCurrentOrgTeams`, `teamGrantListsOnlyExplicitTeams`, `wrongScopeIsPrivacy404`, `createRequiresOrgGrant`, `codeIsImmutableAndUniquePerOrg`, `statusTransitionsPreserveRows` |
| 11 | `teamScopeControlsMembership`, `crossOrgTeamMemberIsNotFound`, `addReactivatesTeamMembership`, `deleteDisablesTeamMembership`, `teamMembershipChangeBumpsVersionAndAudits` |
| 12 | `orgGrantListsCostCenters`, `costCenterGrantListsExplicitIds`, `createCostCenterRequiresOrg`, `wrongCostCenterScopeIsNotFound`, `costCenterCodeIsImmutable`, `costCenterLifecyclePreservesRow` |
| 13 | `providerAccountRequiresOrgGrant`, `providerAccountIsCurrentOrgOnly`, `providerNaturalKeyConflict`, `providerCodeIsImmutable`, `providerMetadataRejectsSecretKeys`, `providerLifecyclePreservesRow` |
| 14 | `implementedM1RoutesRequireAuthentication`, `unsupportedMethodsRemainDenied`, `m2RoutesRemainDenied`, `wrongRoleIsForbidden`, `wrongScopeIsPrivacyNotFound` |
| 15 | `meProjectsOnlyApplicableAdminPermissions`, `meExcludesNonM1FinancePermissions`, `mePermissionsAreSortedAndDeduplicated`, `staleMeContextIsUnauthorized`, `bootstrapRetainsPermissions` |
| 16 | `openApiContainsAllApprovedM1Routes`, `pagedContractsUseExistingPageShape`, `catalogContractsAreUnpaged`, `idsAreStrings`, `problemResponsesAreDeclared` |
| 17 | `hidesNavigationWithoutReadPermission`, `directUnauthorizedUrlRendersForbidden`, `unauthorizedRouteDoesNotMountChild`, `authorizedRouteRendersChild` |
| 18 | `usersCoversLoadingEmptyErrorAndData`, `userActionsRespectIndependentPermissions`, `userMutationInvalidatesQueries`, `rolesCatalogIsReadOnlyAndUnpaged` |
| 19 | `projectsCoverLifecycleAndMembershipStates`, `projectActionsRequireManage`, `teamsCoverLifecycleAndMembershipStates`, `teamActionsRequireManage`, `organizationMutationsInvalidateExactKeys` |
| 20 | `costCentersCoverAllQueryStates`, `costCenterActionsRequireManage`, `providerAccountsCoverAllQueryStates`, `providerFieldsMatchSchema`, `providerMutationInvalidatesQueries` |
| 21 | `forbiddenMutationRefreshesMeExactlyOnce`, `forbiddenMutationIsNotRetried`, `sessionExpiredClearsAuthAndRedirects`, `sessionExpiredDoesNotStartRefreshLoop` |
| 22 | PowerShell assertions `Assert-AdminPermissions`, `Assert-WrongRole403`, `Assert-WrongScope404`, `Assert-OldJwt401`, `Assert-AuditSecretAbsence`, `Assert-M2Denied` |
| 23 | verification gates `backend-clean-verify`, `frontend-test-lint-build`, `scope-tenant-scan`, `documentation-placeholder-scan` |

---

### Task 1: Authorization domain and MySQL context resolution

**Files:**
- Create: `backend/src/main/java/com/aicostops/iam/domain/ScopeType.java`
- Create: `backend/src/main/java/com/aicostops/iam/domain/ScopedPermissionGrant.java`
- Create: `backend/src/main/java/com/aicostops/iam/domain/AuthorizationContext.java`
- Create: `backend/src/main/java/com/aicostops/iam/infrastructure/AuthorizationIdentityRecord.java`
- Create: `backend/src/main/java/com/aicostops/iam/infrastructure/ScopedPermissionGrantRecord.java`
- Create: `backend/src/main/java/com/aicostops/iam/infrastructure/AuthorizationContextMapper.java`
- Create: `backend/src/main/java/com/aicostops/iam/application/AuthorizationContextService.java`
- Test: `backend/src/test/java/com/aicostops/iam/application/AuthorizationContextServiceIntegrationTest.java`

**Interfaces:**
- Consumes: existing `AuthenticatedUser(long userId,long securityVersion)` and V2/V3 `app_user`, `organization_member`, `organization`, `role_assignment`, `role`, `role_permission`, `permission` tables.
- Produces: `AuthorizationContextService.fresh(AuthenticatedUser)` and immutable `AuthorizationContext` with every seeded permission from every explicit assignment, preserving exact scope.

- [ ] **Step 1: Write RED MySQL tests.** Add `loadsAllSeededPermissionsWithExactAssignmentScope`, `rejectsInactiveOrAmbiguousMembership`, `rejectsSecurityVersionMismatch`, and `keepsFinanceReviewerCostCenterGrants` in `AuthorizationContextServiceIntegrationTest`; seed `FINANCE_REVIEWER + COST_CENTER:9` and assert Finance permissions are retained rather than filtered.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dit.test=AuthorizationContextServiceIntegrationTest verify; Pop-Location`. Expected: compilation failure because the context types/service do not exist.
- [ ] **Step 3: Implement minimal domain and SQL.** `AuthorizationContextMapper.findIdentity(userId)` must return one ACTIVE user/member/organization and detect more than one active membership; `findGrants(organizationMemberId)` joins assignments through seeded permissions and selects `permission.code, scope_type, scope_id` without M1 applicability filtering. `AuthorizationContextService.fresh` compares the row version to `AuthenticatedUser.securityVersion()` and throws `401 AUTH_SESSION_EXPIRED` when identity/version is invalid.
- [ ] **Step 4: Run GREEN.** Re-run the targeted Maven command. Expected: all four tests pass against MySQL 8.4 Testcontainers.
- [ ] **Step 5: Refactor only within the new boundary.** Remove duplicated row-to-grant mapping inside Task 1 files; keep MyBatis annotations/records in `iam.infrastructure`. Expected: public signatures above remain unchanged.
- [ ] **Step 6: Run surrounding regression.** Run `Push-Location backend; .\mvnw.cmd -Dtest=ModuleDependencyArchitectureTest test; Pop-Location`. Expected: architecture test passes.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 with only Task 1 files changed.
- [ ] **Step 8: Commit.** Run `git add backend/src/main/java/com/aicostops/iam backend/src/test/java/com/aicostops/iam/application/AuthorizationContextServiceIntegrationTest.java`; then `git commit -m "feat(iam): resolve authorization context from MySQL"`.

### Task 2: Redis authorization-context cache and fail-safe fallback

**Files:**
- Create: `backend/src/main/java/com/aicostops/iam/application/AuthorizationContextCache.java`
- Create: `backend/src/main/java/com/aicostops/iam/infrastructure/RedisAuthorizationContextCache.java`
- Create: `backend/src/main/java/com/aicostops/iam/infrastructure/AuthorizationContextConfiguration.java`
- Modify: `backend/src/main/java/com/aicostops/iam/application/AuthorizationContextService.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/aicostops/iam/infrastructure/RedisAuthorizationContextCacheIntegrationTest.java`
- Test: `backend/src/test/java/com/aicostops/iam/application/AuthorizationContextCacheFallbackTest.java`

**Interfaces:**
- Consumes: Task 1 `AuthorizationContext` and `AuthorizationContextMapper`.
- Produces: `AuthorizationContextCache.get/put/evict`; `AuthorizationContextService.current` cache-first and `fresh` MySQL-only paths.

- [ ] **Step 1: Write RED tests.** Cover key `aicostops:v1:iam:context:{userId}:{securityVersion}`, 60-second TTL, hit equality, malformed JSON fallback, cache miss fallback, Redis `DataAccessException` fallback, and MySQL invalid identity denial. Assert a Redis error never yields an authorized empty context.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dtest=AuthorizationContextCacheFallbackTest -Dit.test=RedisAuthorizationContextCacheIntegrationTest verify; Pop-Location`. Expected: missing cache/configuration types.
- [ ] **Step 3: Implement minimal cache.** Serialize only IDs, version, and grant triplets using the existing Jackson `ObjectMapper`; configure `aicostops.iam.context-cache-ttl: ${AICOSTOPS_IAM_CONTEXT_CACHE_TTL:60s}`; catch Redis data-access and malformed-value failures in `current`, then call the same MySQL loader used by `fresh` and best-effort repopulate.
- [ ] **Step 4: Run GREEN.** Re-run the targeted command. Expected: unit fallback and real Redis TTL/hit tests pass.
- [ ] **Step 5: Refactor only within cache code.** Centralize key construction and JSON validation in `RedisAuthorizationContextCache`; do not change Task 1 domain signatures.
- [ ] **Step 6: Run surrounding regression.** Run `Push-Location backend; .\mvnw.cmd -Dtest=SecurityVersionServiceTest test; Pop-Location`. Expected: existing authentication cache tests pass unchanged.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and no credential material in cache serialization.
- [ ] **Step 8: Commit.** Stage the listed files and commit `feat(iam): cache authorization context in Redis`.

### Task 3: Role scope validity and M1 permission decision helpers

**Files:**
- Create: `backend/src/main/java/com/aicostops/iam/domain/RoleScopePolicy.java`
- Create: `backend/src/main/java/com/aicostops/iam/domain/M1AdminPermissionPolicy.java`
- Create: `backend/src/main/java/com/aicostops/iam/application/ResourceScope.java`
- Create: `backend/src/main/java/com/aicostops/iam/application/M1AuthorizationService.java`
- Test: `backend/src/test/java/com/aicostops/iam/domain/RoleScopePolicyTest.java`
- Test: `backend/src/test/java/com/aicostops/iam/domain/M1AdminPermissionPolicyTest.java`
- Test: `backend/src/test/java/com/aicostops/iam/application/M1AuthorizationServiceTest.java`

**Interfaces:**
- Consumes: Task 1 `AuthorizationContext` and explicit grants.
- Produces: `RoleScopePolicy.requireValid(roleCode,scopeType)`, applicability lookup, and `requireOrg/requireList/requireResource` with stable 403/404 behavior.

- [ ] **Step 1: Write RED matrix tests.** Parameterize all 20 Role×scope pairs: EMPLOYEE only ORG; PROJECT_OWNER only PROJECT; FINANCE_REVIEWER ORG/COST_CENTER; FINANCE_ADMIN only ORG; SYSTEM_ADMIN all four. Add permission applicability tests for every USER/ROLE/PROJECT/TEAM/COST_CENTER/PROVIDER_ACCOUNT permission from V3.
- [ ] **Step 2: Write RED decision tests.** Assert missing applicable permission → `403 FORBIDDEN`; matching ORG/resource grant → success; same permission but wrong resource ID/type → `404 RESOURCE_NOT_FOUND`; list scope returns ORG-wide or exact IDs; non-M1 Finance grants remain in context but are not accepted as M1 admin permissions.
- [ ] **Step 3: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dtest=RoleScopePolicyTest,M1AdminPermissionPolicyTest,M1AuthorizationServiceTest test; Pop-Location`. Expected: missing policy/service types.
- [ ] **Step 4: Implement minimal policies.** Freeze immutable maps/sets matching design §8; never derive Role validity from permission applicability. `requireList` returns `ResourceScope(true,Set.of())` for ORG or `ResourceScope(false,ids)` for typed grants.
- [ ] **Step 5: Refactor policy constants.** Keep one immutable Role matrix and one immutable M1 permission matrix; remove duplicated switch branches without changing behavior.
- [ ] **Step 6: Run surrounding regression.** Re-run targeted tests, then `Push-Location backend; .\mvnw.cmd -Dit.test=RolePermissionSeedIntegrationTest verify; Pop-Location`. Expected: policy tests and existing V3 seed contract pass.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and no non-M1 endpoint policy added.
- [ ] **Step 8: Commit.** Stage listed files and commit `feat(iam): enforce role and permission scope policies`.

### Task 4: Durable security-version invalidation support

**Files:**
- Create: `backend/src/main/java/com/aicostops/iam/application/AuthorizationInvalidationService.java`
- Modify: `backend/src/main/java/com/aicostops/iam/application/SecurityVersionService.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/BearerAuthenticationFilter.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/IamMapper.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/RedisRefreshSessionRepository.java`
- Test: `backend/src/test/java/com/aicostops/iam/application/AuthorizationInvalidationServiceIntegrationTest.java`
- Test: `backend/src/test/java/com/aicostops/iam/application/SecurityVersionServiceTest.java`
- Test: `backend/src/test/java/com/aicostops/iam/infrastructure/BearerAuthenticationFilterTest.java`
- Test: `backend/src/test/java/com/aicostops/iam/infrastructure/RedisRefreshSessionRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: existing `IamMapper`, `SecurityVersionService`, existing `RedisRefreshSessionRepository.revokeAll(long)`, Task 2 cache.
- Produces: `long AuthorizationInvalidationService.bumpInTransaction(long targetUserId,long oldVersion)` and after-commit best-effort cache/session cleanup.

- [ ] **Step 1: Write RED guarantees.** Test that mutation caller can bump exactly one target row in its MySQL transaction; after commit the new security cache value is written, old/new context keys are evicted, and existing `revokeAll(long)` is invoked. Simulate Redis down and assert a JWT with the old `sv` is rejected because bearer validation reads current MySQL truth.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dtest=SecurityVersionServiceTest,BearerAuthenticationFilterTest -Dit.test=AuthorizationInvalidationServiceIntegrationTest,RedisRefreshSessionRepositoryIntegrationTest verify; Pop-Location`. Expected: missing invalidation method and stale cache behavior failure.
- [ ] **Step 3: Implement minimal durable path.** Change `SecurityVersionService.current(userId)` to query `IamMapper.findActiveSecurityVersion` first and only then best-effort write Redis, so Redis is never sole stale-token truth. Add `put(userId,version)`; retain `invalidate`. Add `IamMapper.incrementSecurityVersionForAuthorizationChange(userId,now)` without an ACTIVE predicate and `findSecurityVersionById(userId)`; after the locked target update, call both in the same transaction to obtain the new version. Register `TransactionSynchronization.afterCommit` for cache put/context eviction/existing `revokeAll`; swallow Redis exceptions only after durable commit.
- [ ] **Step 4: Run GREEN.** Re-run targeted tests. Expected: old JWT is rejected with Redis unavailable; refresh repository behavior remains green without a new revoke API.
- [ ] **Step 5: Refactor transaction synchronization.** Keep durable increment inside the caller transaction and all Redis work in one after-commit callback; do not introduce an event framework.
- [ ] **Step 6: Run auth regression.** Run `Push-Location backend; .\mvnw.cmd -Dtest=SecurityVersionServiceTest,BearerAuthenticationFilterTest test; .\mvnw.cmd -Dit.test=RefreshAndLogoutApiIntegrationTest verify; Pop-Location`. Expected: authentication E2E behavior passes.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0; `RedisRefreshSessionRepository.revokeAll(long)` remains the only revoke-all API.
- [ ] **Step 8: Commit.** Stage listed files and commit `feat(iam): make authorization invalidation durable`.

### Task 5: IAM user, Role, and Permission reads

**Files:**
- Create: `backend/src/main/java/com/aicostops/iam/api/UserAdminController.java`
- Create: `backend/src/main/java/com/aicostops/iam/api/RoleCatalogController.java`
- Create: `backend/src/main/java/com/aicostops/iam/api/UserResponse.java`
- Create: `backend/src/main/java/com/aicostops/iam/api/RoleResponse.java`
- Create: `backend/src/main/java/com/aicostops/iam/api/PermissionResponse.java`
- Create: `backend/src/main/java/com/aicostops/iam/application/UserAdminService.java`
- Create: `backend/src/main/java/com/aicostops/iam/application/RoleCatalogService.java`
- Create: `backend/src/main/java/com/aicostops/iam/infrastructure/IamAdminMapper.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify: `backend/src/test/java/com/aicostops/shared/security/SecurityConfigurationTest.java`
- Test: `backend/src/test/java/com/aicostops/iam/api/IamReadApiIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 1–3 context/authorization services, existing `PageRequest`/`PageResponse`, `ApiId`.
- Produces: paged `GET /api/v1/users`, full-shape `GET /users/{id}`, and unpaged `GET /roles`, `GET /permissions`.

- [ ] **Step 1: Write RED API tests.** Add `usersListAndDetailShareFullRepresentation`, `usersAreCurrentOrgOnly`, `usersCountMatchesRowPredicate`, `rolesAndPermissionsAreUnpaged`, `missingReadPermissionIs403`, and cross-org user ID `404`. Assert no per-user mapper calls by implementing list SQL as one page-ID query plus one batched detail query, not one query per item.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dit.test=IamReadApiIntegrationTest verify; Pop-Location`. Expected: 403 from current denyAll or missing controllers.
- [ ] **Step 3: Implement minimal reads.** `IamAdminMapper` selects page IDs/count with identical `org_id`/filters, then fetches users, organization-member data, and assignments for all page IDs in batched queries; service assembles the frozen User representation. Catalog queries read V3 Roles/Permissions. Add authenticated matchers only for the four completed GET routes; final denyAll remains.
- [ ] **Step 4: Run GREEN.** Re-run the targeted integration test. Expected: real bearer-authenticated read/shape/pagination assertions pass while unfinished mutation routes remain denied.
- [ ] **Step 5: Refactor batched assembly.** Extract only a private assembler for page rows/assignments; verify no per-user mapper loop remains.
- [ ] **Step 6: Run surrounding regression.** Run `Push-Location backend; .\mvnw.cmd -Dtest=PageRequestTest,PageResponseTest,ApiJsonTest test; Pop-Location`. Expected: existing page and string-ID tests pass.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and only Task 5 files changed.
- [ ] **Step 8: Commit.** Stage listed files and commit `feat(iam): add organization-scoped IAM reads`.

### Task 6: User status and Role assignment mutations

**Files:**
- Create: `backend/src/main/java/com/aicostops/iam/api/UpdateUserStatusRequest.java`
- Create: `backend/src/main/java/com/aicostops/iam/api/CreateRoleAssignmentRequest.java`
- Create: `backend/src/main/java/com/aicostops/iam/api/RoleAssignmentController.java`
- Create: `backend/src/main/java/com/aicostops/iam/application/RoleAssignmentService.java`
- Modify: `backend/src/main/java/com/aicostops/iam/api/UserAdminController.java`
- Modify: `backend/src/main/java/com/aicostops/iam/application/UserAdminService.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/IamAdminMapper.java`
- Modify: `backend/src/main/java/com/aicostops/organization/infrastructure/OrganizationMapper.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify: `backend/src/test/java/com/aicostops/shared/security/SecurityConfigurationTest.java`
- Test: `backend/src/test/java/com/aicostops/iam/api/IamMutationApiIntegrationTest.java`

**Interfaces:**
- Consumes: `AuthorizationContextService.fresh`, `RoleScopePolicy`, Task 4 invalidation, existing `AuditService`; `OrganizationMapper.scopeResourceExists(ScopeType,long resourceId,long organizationId)` is the typed current-org check.
- Produces: `PATCH /users/{id}/status`, `POST /role-assignments`, `DELETE /role-assignments/{id}`.

- [ ] **Step 1: Write RED mutation tests.** Cover fresh MySQL actor validation despite warm cache; ACTIVE↔DISABLED; all 20 Role/scope matrix pairs; ORG `scopeId == organizationId`; foreign PROJECT/TEAM/COST_CENTER 404; invalid Role/scope 400; duplicate assignment 409; revoke physical delete; target version bump; old JWT 401; `USER_DISABLED`, `USER_ENABLED`, `ROLE_ASSIGNED`, `ROLE_REVOKED` actor/org/subject/metadata and secret absence.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dit.test=IamMutationApiIntegrationTest verify; Pop-Location`. Expected: missing mutation DTO/controller/service methods.
- [ ] **Step 3: Implement minimal transactions.** Annotate application use cases `@Transactional`; call `fresh(actor)` before and inside the transaction, lock actor/target rows, validate Role before typed resource, map invisible resources to 404, insert/delete current grant, append audit, then call `bumpInTransaction`. Same-status PATCH returns current user without bump/audit. Open only the three completed mutation routes as authenticated.
- [ ] **Step 4: Run GREEN.** Re-run targeted integration test. Expected: matrix, HTTP semantics, audit, and invalidation assertions pass.
- [ ] **Step 5: Refactor mutation validation.** Share typed Role/scope validation inside `RoleAssignmentService`; keep user status and assignment transactions separate.
- [ ] **Step 6: Run surrounding regression.** Run `Push-Location backend; .\mvnw.cmd -Dtest=AuditServiceTest,ProblemDetailAdviceTest test; Pop-Location`. Expected: audit secret guard and ProblemDetail remain green.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and no audit secret keys.
- [ ] **Step 8: Commit.** Stage listed files and commit `feat(iam): manage users and role assignments`.

### Task 7: Admin invitation creation and dev delivery

**Files:**
- Create: `backend/src/main/java/com/aicostops/iam/application/InvitationDelivery.java`
- Create: `backend/src/main/java/com/aicostops/iam/application/AdminInvitationService.java`
- Create: `backend/src/main/java/com/aicostops/iam/api/CreateInvitationRequest.java`
- Create: `backend/src/main/java/com/aicostops/iam/api/InvitationResponse.java`
- Create: `backend/src/main/java/com/aicostops/iam/api/AdminInvitationController.java`
- Create: `backend/src/main/java/com/aicostops/iam/infrastructure/DevInvitationMailbox.java`
- Create: `backend/src/main/java/com/aicostops/iam/infrastructure/InvitationDeliveryConfiguration.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/IamAdminMapper.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify: `backend/src/test/java/com/aicostops/shared/security/SecurityConfigurationTest.java`
- Test: `backend/src/test/java/com/aicostops/iam/application/AdminInvitationServiceIntegrationTest.java`
- Test: `backend/src/test/java/com/aicostops/iam/infrastructure/DevInvitationMailboxTest.java`
- Test: `backend/src/test/java/com/aicostops/iam/infrastructure/DefaultInvitationDeliveryTest.java`

**Interfaces:**
- Consumes: existing `EmailAddress`, `TokenDigest`, public `InvitationAcceptanceService`, `AuditService`, `RoleScopePolicy`, and ORG-scoped `USER_INVITE` authorization.
- Produces: `InvitationDelivery.deliver(String normalizedEmail,String invitationToken)`, dev file mailbox, fail-closed default delivery, and `POST /api/v1/invitations`.

- [ ] **Step 1: Write RED tests.** Verify 32 random bytes URL-safe token, SHA-256 hash-only DB, default 72 hours, accepted `expiresInHours` 1/168, rejected 0/169, single-use compatibility with existing acceptance, PROJECT_OWNER rejection, only ORG-valid initial Roles, `INVITATION_CREATED` metadata without token, and delivery failure rolling back invitation/audit. Default profile must throw dependency unavailable instead of no-op delivery.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dtest=DevInvitationMailboxTest,DefaultInvitationDeliveryTest -Dit.test=AdminInvitationServiceIntegrationTest verify; Pop-Location`. Expected: missing delivery/service types and current default pattern mismatch.
- [ ] **Step 3: Implement minimal creation.** Use `SecureRandom` 32 bytes and URL-without-padding encoding; insert PENDING invitation and audit in one transaction, call delivery before commit, never return/log token. Configure `aicostops.iam.invitation-default-lifetime:72h`, max `168h`, dev mailbox path and invite URL. Default bean throws `503 DEPENDENCY_TEMPORARILY_UNAVAILABLE`; open only authenticated `POST /invitations` while public token acceptance stays public.
- [ ] **Step 4: Run GREEN.** Re-run targeted tests. Expected: delivery/TTL/rollback/security assertions pass and existing acceptance consumes the generated token once.
- [ ] **Step 5: Refactor token handling.** Keep raw token lifetime inside the service/delivery call and expose no token-bearing response or log field.
- [ ] **Step 6: Run existing acceptance regression.** Run `Push-Location backend; .\mvnw.cmd -Dit.test=InvitationAcceptanceServiceIntegrationTest,RegistrationInvitationApiIntegrationTest verify; Pop-Location`. Expected: public invitation acceptance remains green.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and no SMTP dependency or token logging.
- [ ] **Step 8: Commit.** Stage listed files and commit `feat(iam): create and deliver admin invitations`.

### Task 8: Project CRUD and scoped pagination

**Files:**
- Create: `backend/src/main/java/com/aicostops/organization/domain/MasterDataStatus.java`
- Create: `backend/src/main/java/com/aicostops/organization/domain/Project.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/ProjectController.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/CreateProjectRequest.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/UpdateProjectRequest.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/ProjectResponse.java`
- Create: `backend/src/main/java/com/aicostops/organization/application/ProjectService.java`
- Create: `backend/src/main/java/com/aicostops/organization/infrastructure/ProjectMapper.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify: `backend/src/test/java/com/aicostops/shared/security/SecurityConfigurationTest.java`
- Test: `backend/src/test/java/com/aicostops/organization/api/ProjectApiIntegrationTest.java`

**Interfaces:**
- Consumes: `AuthorizationContextService.current`, `M1AuthorizationService`, `ResourceScope`, existing page and ProblemDetail primitives.
- Produces: paged GET, ORG-only POST, ORG/PROJECT-scoped PATCH for `/api/v1/projects`.

- [ ] **Step 1: Write RED tests.** Cover ORG list, PROJECT-ID list subset, identical count/row predicate, wrong Role 403, wrong scope/cross-org/nonexistent 404, ORG-only create, immutable code, duplicate org code 409, editable name/status, ACTIVE/DISABLED/ARCHIVED retained, and string IDs.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dit.test=ProjectApiIntegrationTest verify; Pop-Location`. Expected: missing project API types.
- [ ] **Step 3: Implement minimal explicit SQL.** `ProjectMapper.countAuthorized` and `findAuthorizedPage` both require `org_id`, status filter, and either ORG flag or `id IN allowedProjectIds`; never omit the scope predicate for an empty set. POST forces current `org_id` and ACTIVE. PATCH updates only name/status after scoped lookup. Open only completed project CRUD routes as authenticated.
- [ ] **Step 4: Run GREEN.** Re-run targeted integration test. Expected: CRUD, lifecycle, 403/404, uniqueness, and pagination assertions pass.
- [ ] **Step 5: Refactor SQL parameter construction.** Keep one private conversion from `ResourceScope` to mapper parameters; do not move authorization into mapper Role joins.
- [ ] **Step 6: Run architecture regression.** Run `Push-Location backend; .\mvnw.cmd -Dtest=ModuleDependencyArchitectureTest test; Pop-Location`. Expected: organization package follows module rule.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and no SELECT-all Java filtering.
- [ ] **Step 8: Commit.** Stage project files/tests and commit `feat(organization): add scoped project APIs`.

### Task 9: Project membership lifecycle

**Files:**
- Create: `backend/src/main/java/com/aicostops/organization/domain/ProjectMember.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/AddProjectMemberRequest.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/ProjectMemberResponse.java`
- Create: `backend/src/main/java/com/aicostops/organization/application/ProjectMembershipService.java`
- Create: `backend/src/main/java/com/aicostops/organization/infrastructure/ProjectMemberMapper.java`
- Modify: `backend/src/main/java/com/aicostops/organization/api/ProjectController.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify: `backend/src/test/java/com/aicostops/shared/security/SecurityConfigurationTest.java`
- Test: `backend/src/test/java/com/aicostops/organization/api/ProjectMembershipApiIntegrationTest.java`

**Interfaces:**
- Consumes: project scoped authorization, Task 4 invalidation, existing `AuditService`.
- Produces: paged GET, POST add/reactivate, DELETE semantic transition at `/projects/{id}/members`; `{memberId}` is the project-member row ID.

- [ ] **Step 1: Write RED tests.** Cover paged list with identical count/rows; PROJECT_READ or PROJECT_MEMBER_MANAGE reads; manage-only writes; ACTIVE parent/target member; cross-org member 404; duplicate add reactivates one row; DELETE changes status without row deletion; repeated delete 409; target user version bump/old JWT rejection; `MEMBERSHIP_CHANGED` actor/org/subject/metadata.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dit.test=ProjectMembershipApiIntegrationTest verify; Pop-Location`. Expected: missing membership API/service/mapper.
- [ ] **Step 3: Implement minimal transaction and SQL.** Lock scoped project, organization member, and natural membership row; insert or transition to ACTIVE; DELETE transitions ACTIVE to DISABLED; append audit and bump target version only for effective changes. No physical project-member delete statement is allowed. Open the completed project-member routes as authenticated.
- [ ] **Step 4: Run GREEN.** Re-run targeted integration test. Expected: lifecycle, cross-org, audit, and invalidation pass.
- [ ] **Step 5: Refactor lifecycle mapping.** Keep state transition SQL in `ProjectMemberMapper` and remove duplicated audit metadata construction in the service.
- [ ] **Step 6: Run project regression.** Run `Push-Location backend; .\mvnw.cmd -Dit.test=ProjectApiIntegrationTest,ProjectMembershipApiIntegrationTest verify; Pop-Location`. Expected: both suites pass.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0; no physical project-member delete SQL exists.
- [ ] **Step 8: Commit.** Stage listed files and commit `feat(organization): manage project memberships`.

### Task 10: Team CRUD and scoped pagination

**Files:**
- Create: `backend/src/main/java/com/aicostops/organization/domain/Team.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/TeamController.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/CreateTeamRequest.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/UpdateTeamRequest.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/TeamResponse.java`
- Create: `backend/src/main/java/com/aicostops/organization/application/TeamService.java`
- Create: `backend/src/main/java/com/aicostops/organization/infrastructure/TeamMapper.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify: `backend/src/test/java/com/aicostops/shared/security/SecurityConfigurationTest.java`
- Test: `backend/src/test/java/com/aicostops/organization/api/TeamApiIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 1–3 authorization and Task 8 `MasterDataStatus` pattern.
- Produces: paged GET, ORG-only POST, ORG/TEAM-scoped PATCH `/api/v1/teams`.

- [ ] **Step 1: Write RED tests.** Name tests `orgGrantListsCurrentOrgTeams`, `teamGrantListsOnlyExplicitTeams`, `wrongScopeIsPrivacy404`, `createRequiresOrgGrant`, `codeIsImmutableAndUniquePerOrg`, and `statusTransitionsPreserveRows`.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dit.test=TeamApiIntegrationTest verify; Pop-Location`. Expected: missing team API types.
- [ ] **Step 3: Implement minimal team SQL/service/controller.** Mirror the frozen authorization contract, not project code by copy-pasted Role logic; `TeamMapper` receives computed `ResourceScope`, count/rows share predicates, and completed team CRUD routes become authenticated.
- [ ] **Step 4: Run GREEN.** Re-run targeted integration test. Expected: all team behavior passes on MySQL.
- [ ] **Step 5: Refactor only mapper parameter code.** Keep team DTO/service/mapper focused and reuse domain status without copying project authorization logic.
- [ ] **Step 6: Run scope-policy regression.** Run `Push-Location backend; .\mvnw.cmd -Dtest=M1AuthorizationServiceTest test; Pop-Location`. Expected: TEAM applicability remains exact.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and no implicit Team→Project rule.
- [ ] **Step 8: Commit.** Stage team files/tests and commit `feat(organization): add scoped team APIs`.

### Task 11: Team membership lifecycle

**Files:**
- Create: `backend/src/main/java/com/aicostops/organization/domain/TeamMember.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/AddTeamMemberRequest.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/TeamMemberResponse.java`
- Create: `backend/src/main/java/com/aicostops/organization/application/TeamMembershipService.java`
- Create: `backend/src/main/java/com/aicostops/organization/infrastructure/TeamMemberMapper.java`
- Modify: `backend/src/main/java/com/aicostops/organization/api/TeamController.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify: `backend/src/test/java/com/aicostops/shared/security/SecurityConfigurationTest.java`
- Test: `backend/src/test/java/com/aicostops/organization/api/TeamMembershipApiIntegrationTest.java`

**Interfaces:**
- Consumes: TEAM_READ/TEAM_MANAGE decisions, Task 4 invalidation, `AuditService`.
- Produces: paged GET, POST add/reactivate, DELETE semantic lifecycle under `/teams/{id}/members`.

- [ ] **Step 1: Write RED tests.** Cover TEAM-scoped read/manage, cross-org target 404, inactive parent/target conflict, natural uniqueness/reactivation, semantic DELETE, no row deletion, target security-version bump, old JWT rejection, and complete `MEMBERSHIP_CHANGED` audit.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dit.test=TeamMembershipApiIntegrationTest verify; Pop-Location`. Expected: missing team membership types.
- [ ] **Step 3: Implement minimal lifecycle.** Use transaction/locking and status transitions identical in semantics to Task 9 but through independent team mapper SQL; never infer project access from team membership. Open completed team-member routes as authenticated.
- [ ] **Step 4: Run GREEN.** Re-run targeted suite. Expected: all team membership assertions pass.
- [ ] **Step 5: Refactor lifecycle mapping.** Keep team-specific SQL independent and share only stable audit metadata helpers already introduced.
- [ ] **Step 6: Run cross-feature regression.** Run `Push-Location backend; .\mvnw.cmd -Dit.test=ProjectMembershipApiIntegrationTest,TeamMembershipApiIntegrationTest verify; Pop-Location`. Expected: both lifecycle contracts pass.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0; no physical team-member delete SQL exists.
- [ ] **Step 8: Commit.** Stage listed files and commit `feat(organization): manage team memberships`.

### Task 12: Cost-center CRUD and scoped pagination

**Files:**
- Create: `backend/src/main/java/com/aicostops/organization/domain/CostCenter.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/CostCenterController.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/CreateCostCenterRequest.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/UpdateCostCenterRequest.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/CostCenterResponse.java`
- Create: `backend/src/main/java/com/aicostops/organization/application/CostCenterService.java`
- Create: `backend/src/main/java/com/aicostops/organization/infrastructure/CostCenterMapper.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify: `backend/src/test/java/com/aicostops/shared/security/SecurityConfigurationTest.java`
- Test: `backend/src/test/java/com/aicostops/organization/api/CostCenterApiIntegrationTest.java`

**Interfaces:**
- Consumes: COST_CENTER_READ/MANAGE applicability and shared master-data status.
- Produces: paged GET, ORG-only POST, ORG/COST_CENTER-scoped PATCH `/api/v1/cost-centers`.

- [ ] **Step 1: Write RED tests.** Cover org/scoped lists and totals, wrong Role 403, wrong/cross-org scope 404, ORG-only creation, immutable and org-unique code, editable name/status, and preserved archived/disabled rows.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dit.test=CostCenterApiIntegrationTest verify; Pop-Location`. Expected: missing endpoint.
- [ ] **Step 3: Implement minimal explicit SQL.** Pass computed scope to count/page/update lookup; do not use `organization_member.default_cost_center_id` as implicit authorization. Open completed cost-center routes as authenticated.
- [ ] **Step 4: Run GREEN.** Re-run targeted suite. Expected: cost-center behavior passes.
- [ ] **Step 5: Refactor scope parameter mapping.** Keep explicit COST_CENTER IDs and ORG flag in one mapper parameter object; do not infer authorization from default cost center.
- [ ] **Step 6: Run scope regression.** Run `Push-Location backend; .\mvnw.cmd -Dtest=M1AdminPermissionPolicyTest test; Pop-Location`. Expected: COST_CENTER applicability remains exact.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and no hard-delete endpoint.
- [ ] **Step 8: Commit.** Stage listed files and commit `feat(organization): add scoped cost center APIs`.

### Task 13: Provider-account CRUD

**Files:**
- Create: `backend/src/main/java/com/aicostops/organization/domain/ProviderAccount.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/ProviderAccountController.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/CreateProviderAccountRequest.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/UpdateProviderAccountRequest.java`
- Create: `backend/src/main/java/com/aicostops/organization/api/ProviderAccountResponse.java`
- Create: `backend/src/main/java/com/aicostops/organization/application/ProviderAccountService.java`
- Create: `backend/src/main/java/com/aicostops/organization/infrastructure/ProviderAccountMapper.java`
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify: `backend/src/test/java/com/aicostops/shared/security/SecurityConfigurationTest.java`
- Test: `backend/src/test/java/com/aicostops/organization/api/ProviderAccountApiIntegrationTest.java`

**Interfaces:**
- Consumes: ORG-only PROVIDER_ACCOUNT_READ/MANAGE and existing provider-account schema.
- Produces: paged GET, POST, PATCH `/api/v1/provider-accounts` without a fabricated generic code.

- [ ] **Step 1: Write RED tests.** Cover ORG-only read/manage, cross-org 404, `(org_id,provider_code,display_name)` conflict 409, immutable `providerCode`, editable `displayName`, `externalAccountRef`, status and non-secret metadata, rejection of metadata keys containing password/token/secret/apikey fragments, and no hard delete.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dit.test=ProviderAccountApiIntegrationTest verify; Pop-Location`. Expected: missing provider API types.
- [ ] **Step 3: Implement minimal provider contract.** Use existing columns only; validate metadata keys before JSON serialization; apply current-org predicate to count/rows/detail/update; open only completed provider-account routes as authenticated.
- [ ] **Step 4: Run GREEN.** Re-run targeted suite. Expected: schema-aligned CRUD and secret-safety pass.
- [ ] **Step 5: Refactor metadata validation.** Keep secret-key validation in one domain/service helper and preserve the existing schema field names.
- [ ] **Step 6: Run ProblemDetail regression.** Run `Push-Location backend; .\mvnw.cmd -Dtest=ProblemDetailAdviceTest test; Pop-Location`. Expected: validation/conflict mapping remains green.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and no generic provider-account code or credential field.
- [ ] **Step 8: Commit.** Stage listed files and commit `feat(organization): add provider account APIs`.

### Task 14: Security route exposure and API authorization regression

**Files:**
- Modify: `backend/src/main/java/com/aicostops/iam/infrastructure/SecurityConfiguration.java`
- Modify: `backend/src/test/java/com/aicostops/shared/security/SecurityConfigurationTest.java`
- Create: `backend/src/test/java/com/aicostops/iam/api/M1AuthorizationApiIntegrationTest.java`

**Interfaces:**
- Consumes: completed routes from Tasks 5–13; existing bearer filter and application authorization.
- Produces: authenticated transport access for only M1 IAM/organization routes while application services enforce permission/scope.

- [ ] **Step 1: Write RED security tests.** Assert anonymous implemented M1 route → 401; authenticated implemented routes reach controllers; unsupported methods such as `POST /users`, `DELETE /projects/{id}`, and all `/api/v1/evidence`, `/costs/charges`, `/budgets`, `/ledger` routes remain denied; EMPLOYEE project create → 403; scoped wrong-resource PATCH → privacy 404.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dtest=SecurityConfigurationTest -Dit.test=M1AuthorizationApiIntegrationTest verify; Pop-Location`. Expected: at least one unsupported method reaches MVC/404 through an earlier broad path matcher instead of the required deny-by-default response.
- [ ] **Step 3: Implement minimal matcher list.** Replace broad path matchers with `HttpMethod`-specific authenticated matchers for exactly the implemented methods in Tasks 5–13; keep public invitation accept ordered first and final `anyRequest().denyAll()`.
- [ ] **Step 4: Run GREEN.** Re-run targeted command. Expected: route exposure and backend 403/404 tests pass.
- [ ] **Step 5: Refactor matcher declarations.** Group only exact completed M1 patterns while preserving public invitation-accept ordering and final denyAll.
- [ ] **Step 6: Run full security regression.** Run `Push-Location backend; .\mvnw.cmd -Dtest=SecurityConfigurationTest,BearerAuthenticationFilterTest test; Pop-Location`. Expected: auth endpoints and deny-by-default remain green.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and no M2 matcher opened.
- [ ] **Step 8: Commit.** Stage the three files and commit `feat(security): expose authorized M1 admin routes`.

### Task 15: `/auth/me` M1 permission projection

**Files:**
- Modify: `backend/src/main/java/com/aicostops/iam/api/MeResponse.java`
- Modify: `backend/src/main/java/com/aicostops/iam/api/AuthController.java`
- Create: `backend/src/main/java/com/aicostops/iam/application/MeService.java`
- Modify: `frontend/src/features/auth/authSession.ts`
- Test: `backend/src/test/java/com/aicostops/iam/api/MeApiIntegrationTest.java`
- Test: `frontend/src/features/auth/authSession.test.ts`

**Interfaces:**
- Consumes: complete backend `AuthorizationContext`, section 8.2 applicability, existing identity fields.
- Produces: `MeResponse(...,List<String> permissions)` and frontend `AuthUser.permissions: string[]` containing only sorted/deduplicated M1 admin permissions with at least one applicable explicit scope.

- [ ] **Step 1: Write RED tests.** Backend cases: ORG SYSTEM_ADMIN projection; PROJECT SYSTEM_ADMIN excludes ORG-only USER/ROLE permissions; non-M1 Finance permissions excluded; union deduplicated/sorted; stale version 401. Frontend type/bootstrap test must retain permissions from `/auth/me`.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dit.test=MeApiIntegrationTest verify; Pop-Location`; then `Push-Location frontend; npm test -- --run src/features/auth/authSession.test.ts; Pop-Location`. Expected: missing permissions field/projection.
- [ ] **Step 3: Implement minimal projection.** `MeService.me(AuthenticatedUser)` uses `AuthorizationContextService.current`, filters only policy-known M1 admin codes whose explicit grant scope is applicable, sorts codes, and maps existing identity fields. Do not return scopes.
- [ ] **Step 4: Run GREEN.** Re-run both targeted commands. Expected: backend and TypeScript tests pass.
- [ ] **Step 5: Refactor projection.** Keep sorting/deduplication in `MeService` and preserve `MeResponse` field order/identity mapping.
- [ ] **Step 6: Run auth regression.** Run `Push-Location backend; .\mvnw.cmd -Dit.test=LoginApiIntegrationTest,RefreshAndLogoutApiIntegrationTest verify; Pop-Location`. Expected: existing auth flows remain green.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and no scope IDs in response/JWT.
- [ ] **Step 8: Commit.** Stage listed files and commit `feat(iam): expose M1 permissions from auth me`.

### Task 16: OpenAPI, API matrix, and error-contract synchronization

**Files:**
- Modify: `docs/02-development/api/02-接口矩阵.md`
- Modify: `docs/02-development/api/04-错误码幂等并发.md`
- Modify: `docs/02-development/api/openapi.yaml`
- Test: `backend/src/test/java/com/aicostops/iam/api/M1OpenApiContractTest.java`

**Interfaces:**
- Consumes: exact DTOs/routes/status codes from Tasks 5–15.
- Produces: synchronized concrete OpenAPI schemas and matrix for all M1 endpoints; no endpoint outside approved scope.

- [ ] **Step 1: Write RED contract test.** Use UTF-8 text/section assertions against `openapi.yaml` without adding a YAML dependency; assert seven paged routes use page/size and PageResponse fields, Role/Permission arrays are unpaged, all added GET/PATCH/member routes exist, IDs are strings, `/auth/me.permissions` exists, and 400/401/403/404/409/503 responses reference Problem.
- [ ] **Step 2: Run RED.** Run `Push-Location backend; .\mvnw.cmd -Dtest=M1OpenApiContractTest test; Pop-Location`. Expected: missing routes/concrete schemas.
- [ ] **Step 3: Update documents minimally.** Replace generic objects for implemented endpoints; add User/Role/Permission/assignment/invitation/project/team/member/cost-center/provider schemas. Remove the matrix's unsupported `expectedVersion` requirement from user status unless implemented; document status-state behavior, privacy 404, invalid Role/scope 400, conflict 409, delivery 503.
- [ ] **Step 4: Run GREEN.** Re-run contract test. Expected: all route/schema assertions pass.
- [ ] **Step 5: Refactor schema reuse.** Deduplicate only shared Id/Page/Problem references; keep endpoint-specific request/response schemas concrete.
- [ ] **Step 6: Run cross-document regression.** Run `rg -n "/projects/\{id\}/members|/teams/\{id\}/members|permissions:" docs/02-development/api`; then rerun `Push-Location backend; .\mvnw.cmd -Dtest=M1OpenApiContractTest test; Pop-Location`. Expected: matrix/OpenAPI routes exist and contract test passes.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 with no M2 route additions.
- [ ] **Step 8: Commit.** Stage the three docs and contract test; commit `docs(api): define M1 organization authorization contracts`.

### Task 17: Authenticated layout, permission helper, and route guard

**Files:**
- Create: `frontend/src/features/settings/api/settingsTypes.ts`
- Create: `frontend/src/features/settings/permissions.ts`
- Create: `frontend/src/app/layout/AuthenticatedLayout.tsx`
- Create: `frontend/src/app/layout/AuthenticatedLayout.test.tsx`
- Create: `frontend/src/app/router/PermissionRoute.tsx`
- Create: `frontend/src/app/router/PermissionRoute.test.tsx`
- Create: `frontend/src/app/router/ForbiddenPage.tsx`
- Modify: `frontend/src/app/router/AppRouter.tsx`
- Modify: `frontend/src/styles.css`

**Interfaces:**
- Consumes: Task 15 `AuthUser.permissions`, existing `useAuth`, `ProtectedRoute`, React Router, Ant Design.
- Produces: `hasPermission(permissions,code)`, authenticated layout/navigation, and `<PermissionRoute permission="...">` behavior.

- [ ] **Step 1: Write RED component tests.** Assert navigation items hide without READ permission; direct unauthorized settings URL renders authenticated 403; guard does not mount a child probe (proving no hidden query); authorized child renders; no redirect occurs; logout remains available.
- [ ] **Step 2: Run RED.** Run `Push-Location frontend; npm test -- --run src/app/layout/AuthenticatedLayout.test.tsx src/app/router/PermissionRoute.test.tsx; Pop-Location`. Expected: missing components/helper.
- [ ] **Step 3: Implement minimal layout/guard.** Define the six route/READ-permission nav entries once, use Ant Design `Layout/Menu`, render `Outlet`, and make guard return `ForbiddenPage` before child construction when permission is absent.
- [ ] **Step 4: Run GREEN.** Re-run targeted tests. Expected: hide/403/no-request assertions pass.
- [ ] **Step 5: Refactor route metadata.** Keep the six paths/labels/READ permissions in one typed navigation constant; do not add a state framework.
- [ ] **Step 6: Run router regression.** Run `Push-Location frontend; npm test -- --run src/app/router/ProtectedRoute.test.tsx src/app/App.test.tsx; Pop-Location`. Expected: public/protected routing remains green.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and guard tests prove child queries never mount without READ.
- [ ] **Step 8: Commit.** Stage listed files and commit `feat(frontend): add permission-aware settings layout`.

### Task 18: Users and Roles settings

**Files:**
- Create: `frontend/src/features/settings/api/settingsApi.ts`
- Create: `frontend/src/features/settings/api/settingsKeys.ts`
- Create: `frontend/src/features/settings/users/UsersPage.tsx`
- Create: `frontend/src/features/settings/users/UsersPage.test.tsx`
- Create: `frontend/src/features/settings/users/UserActions.tsx`
- Create: `frontend/src/features/settings/roles/RolesPage.tsx`
- Create: `frontend/src/features/settings/roles/RolesPage.test.tsx`
- Modify: `frontend/src/app/router/AppRouter.tsx`

**Interfaces:**
- Consumes: `apiClient`, `PageResponse<T>`, `ProblemDetail`, Task 17 permissions/guard.
- Produces: typed IAM client methods and Users/Roles pages with full User model, invitation, disable/enable, Role assign/revoke UX.

- [ ] **Step 1: Write RED UI tests.** Users: loading, empty, ProblemDetail, page data, USER_READ without USER_MANAGE hides status action, USER_INVITE and ROLE_ASSIGN independently gate actions, mutation pending, successful mutation invalidates `settingsKeys.users` and auth-me. Roles: loading, empty, error, unpaged catalog and read-only scope display.
- [ ] **Step 2: Run RED.** Run `Push-Location frontend; npm test -- --run src/features/settings/users/UsersPage.test.tsx src/features/settings/roles/RolesPage.test.tsx; Pop-Location`. Expected: missing API/pages.
- [ ] **Step 3: Implement minimal typed client/UI.** `settingsApi.listUsers({page,size})`, `getUser`, `updateUserStatus`, `listRoles`, `listPermissions`, `create/revokeRoleAssignment`, `createInvitation`; use focused Ant tables/forms/drawers and `toProblemDetail`, not a monolithic page file.
- [ ] **Step 4: Run GREEN.** Re-run targeted tests. Expected: all state/permission/cache assertions pass.
- [ ] **Step 5: Refactor feature files.** Keep user actions outside `UsersPage` and catalog rendering outside API code; preserve frozen types.
- [ ] **Step 6: Run lint/type regression.** Run `Push-Location frontend; npm run lint; npm run build; Pop-Location`. Expected: ESLint and TypeScript/Vite build pass.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and no UserSummary/UserDetail split.
- [ ] **Step 8: Commit.** Stage listed files and commit `feat(frontend): add users and roles settings`.

### Task 19: Projects and Teams settings

**Files:**
- Create: `frontend/src/features/settings/projects/ProjectsPage.tsx`
- Create: `frontend/src/features/settings/projects/ProjectsPage.test.tsx`
- Create: `frontend/src/features/settings/projects/ProjectMembersDrawer.tsx`
- Create: `frontend/src/features/settings/teams/TeamsPage.tsx`
- Create: `frontend/src/features/settings/teams/TeamsPage.test.tsx`
- Create: `frontend/src/features/settings/teams/TeamMembersDrawer.tsx`
- Modify: `frontend/src/features/settings/api/settingsTypes.ts`
- Modify: `frontend/src/features/settings/api/settingsApi.ts`
- Modify: `frontend/src/features/settings/api/settingsKeys.ts`
- Modify: `frontend/src/app/router/AppRouter.tsx`

**Interfaces:**
- Consumes: paged project/team/member backend contracts and PROJECT/TEAM permissions.
- Produces: lifecycle and membership management pages without browser scope reconstruction.

- [ ] **Step 1: Write RED tests.** For each feature cover loading/empty/ProblemDetail, scoped page data, READ without MANAGE hiding create/edit/member writes, ACTIVE/DISABLED/ARCHIVED display, immutable code in edit form, member pagination, mutation pending, and exact list/detail/member query invalidation.
- [ ] **Step 2: Run RED.** Run `Push-Location frontend; npm test -- --run src/features/settings/projects/ProjectsPage.test.tsx src/features/settings/teams/TeamsPage.test.tsx; Pop-Location`. Expected: missing pages/client methods.
- [ ] **Step 3: Implement minimal pages.** Add typed list/create/update/member methods; use separate member drawers; render only backend-returned rows and never infer Team→Project access.
- [ ] **Step 4: Run GREEN.** Re-run targeted tests. Expected: both features pass all states and permission gates.
- [ ] **Step 5: Refactor shared form fields only.** Share stable lifecycle form primitives without merging the project/team pages or member drawers.
- [ ] **Step 6: Run frontend regression.** Run `Push-Location frontend; npm run lint; npm run build; Pop-Location`. Expected: clean lint/build.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and no browser scope inference.
- [ ] **Step 8: Commit.** Stage listed files and commit `feat(frontend): add project and team settings`.

### Task 20: Cost-center and Provider Account settings

**Files:**
- Create: `frontend/src/features/settings/costCenters/CostCentersPage.tsx`
- Create: `frontend/src/features/settings/costCenters/CostCentersPage.test.tsx`
- Create: `frontend/src/features/settings/providerAccounts/ProviderAccountsPage.tsx`
- Create: `frontend/src/features/settings/providerAccounts/ProviderAccountsPage.test.tsx`
- Modify: `frontend/src/features/settings/api/settingsTypes.ts`
- Modify: `frontend/src/features/settings/api/settingsApi.ts`
- Modify: `frontend/src/features/settings/api/settingsKeys.ts`
- Modify: `frontend/src/app/router/AppRouter.tsx`

**Interfaces:**
- Consumes: paged cost-center/provider contracts and corresponding READ/MANAGE permissions.
- Produces: remaining two settings pages and complete six-page IA.

- [ ] **Step 1: Write RED tests.** Cover loading/empty/ProblemDetail, paged records, action visibility, create/edit pending states, immutable cost-center code/providerCode, editable provider fields, lifecycle display, metadata secret-key client validation, and mutation cache invalidation.
- [ ] **Step 2: Run RED.** Run `Push-Location frontend; npm test -- --run src/features/settings/costCenters/CostCentersPage.test.tsx src/features/settings/providerAccounts/ProviderAccountsPage.test.tsx; Pop-Location`. Expected: missing pages/client methods.
- [ ] **Step 3: Implement minimal pages.** Use Ant Design tables/modals/forms and existing API/problem helpers; do not add credential fields or a generic provider account code.
- [ ] **Step 4: Run GREEN.** Re-run targeted tests. Expected: both pages pass all states/actions.
- [ ] **Step 5: Refactor lifecycle controls.** Share only stable status labels/options; keep provider-specific fields isolated from cost-center code/name fields.
- [ ] **Step 6: Run six-route regression.** Run `Push-Location frontend; npm test -- --run src/app/layout/AuthenticatedLayout.test.tsx src/app/router/PermissionRoute.test.tsx; npm run build; Pop-Location`. Expected: all six routes compile and guard correctly.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and no provider credential inputs.
- [ ] **Step 8: Commit.** Stage listed files and commit `feat(frontend): add cost and provider settings`.

### Task 21: Frontend authorization/session-change behavior

**Files:**
- Create: `frontend/src/features/auth/authEvents.ts`
- Create: `frontend/src/features/settings/useAuthorizationMutation.ts`
- Create: `frontend/src/features/settings/useAuthorizationMutation.test.tsx`
- Modify: `frontend/src/api/client.ts`
- Modify: `frontend/src/api/client.test.ts`
- Modify: `frontend/src/features/auth/AuthSessionProvider.tsx`
- Modify: `frontend/src/features/auth/authSession.ts`
- Modify: `frontend/src/features/auth/authSession.test.ts`
- Modify: settings mutation components from Tasks 18–20

**Interfaces:**
- Consumes: existing single-flight refresh client, QueryClient, `AUTH_SESSION_EXPIRED`, `toProblemDetail`.
- Produces: `AuthContextValue.refreshMe():Promise<AuthUser>`, session-expired event subscription, and `useAuthorizationMutation` that refetches `/auth/me` exactly once after 403 without retrying mutation.

- [ ] **Step 1: Write RED tests.** Assert 403 calls `refreshMe` once and returns original error; mutation function executes once; permission UI updates. Assert post-refresh `401 AUTH_SESSION_EXPIRED` clears token/query cache, sets anonymous, navigates `/login`, publishes permissions-changed message, and never enters refresh recursion.
- [ ] **Step 2: Run RED.** Run `Push-Location frontend; npm test -- --run src/api/client.test.ts src/features/auth/authSession.test.ts src/features/settings/useAuthorizationMutation.test.tsx; Pop-Location`. Expected: missing event/refreshMe/mutation hook.
- [ ] **Step 3: Implement minimal behavior.** Add a small typed in-memory auth event publisher (not Redux); client emits only after one retry returns AUTH_SESSION_EXPIRED; provider subscribes and performs the fixed cleanup; hook catches 403, awaits one `refreshMe`, then surfaces the original ProblemDetail.
- [ ] **Step 4: Run GREEN.** Re-run targeted tests. Expected: exact-once/no-loop assertions pass.
- [ ] **Step 5: Refactor event cleanup.** Keep one subscribe/unsubscribe implementation and one authorization-mutation hook; do not generalize into a global store.
- [ ] **Step 6: Run full frontend regression.** Run `Push-Location frontend; npm test -- --run; npm run lint; npm run build; Pop-Location`. Expected: all Vitest, lint, and build checks pass.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and no mutation retry/refresh loop.
- [ ] **Step 8: Commit.** Stage listed files plus adjusted settings components; commit `feat(frontend): handle authorization changes safely`.

### Task 22: Organization & Authorization smoke and acceptance evidence

**Files:**
- Create: `scripts/organization-authorization-smoke.ps1`
- Create: `docs/03-acceptance/implementation/10-m1-organization-authorization-e2e-evidence.md`
- Modify: `compose.dev.yaml`
- Test: `scripts/organization-authorization-smoke.ps1`

**Interfaces:**
- Consumes: Docker Compose services, existing `scripts/auth-smoke.ps1` conventions, dev invitation mailbox, all completed APIs/UI build.
- Produces: repeatable PowerShell vertical smoke and evidence mapping for #22/#23/#25.

- [ ] **Step 1: Write RED smoke assertions.** Script must bootstrap/login admin fixture through explicit dev data setup, assert `/auth/me.permissions`, create/read/update each master record, manage project/team members, verify EMPLOYEE create 403, scoped wrong-resource 404, revoke Role then old JWT 401, inspect audit rows without secrets, and confirm M2 URL remains denied.
- [ ] **Step 2: Run RED.** Run `docker compose --env-file .env.example -f compose.yaml -f compose.dev.yaml up -d --build`; then `.\scripts\organization-authorization-smoke.ps1`. Expected: first missing/unimplemented assertion fails before completed implementation.
- [ ] **Step 3: Finalize deterministic dev setup and evidence.** Reuse compose MySQL/Redis/backend/frontend and existing public registration: register a unique EMPLOYEE, use `docker compose exec -T mysql mysql ...` to insert one ORG-scoped SYSTEM_ADMIN assignment for that member and increment its `security_version`, then log in again as the admin fixture. Add only required dev invitation-mailbox mounts, not new services. Evidence maps exact commands/tests to #22/#23/#25.
- [ ] **Step 4: Run GREEN.** Rebuild compose and run `.\scripts\auth-smoke.ps1` followed by `.\scripts\organization-authorization-smoke.ps1`. Expected: existing Authentication smoke and new vertical smoke both exit 0.
- [ ] **Step 5: Refactor smoke helpers.** Reuse local `Assert-True`/ProblemDetail parsing functions inside the new script; keep `auth-smoke.ps1` behavior unchanged.
- [ ] **Step 6: Run regression and clean runtime.** Run `.\scripts\auth-smoke.ps1`, then `.\scripts\organization-authorization-smoke.ps1`, then `docker compose --env-file .env.example -f compose.yaml -f compose.dev.yaml down`. Expected: both scripts exit 0 and containers stop without repository-file deletion.
- [ ] **Step 7: Check the diff.** Run `git diff --check`. Expected: exit 0 and evidence contains no invitation/JWT/password secret.
- [ ] **Step 8: Commit.** Stage script/evidence/compose.dev.yaml and commit `test(m1): add organization authorization acceptance smoke`.

### Task 23: Full verification and final documentation consistency

**Files:**
- Modify only if verification exposes a concrete mismatch: `docs/02-development/api/02-接口矩阵.md`, `docs/02-development/api/04-错误码幂等并发.md`, `docs/02-development/api/openapi.yaml`, `docs/03-acceptance/implementation/10-m1-organization-authorization-e2e-evidence.md`
- Test: all backend, frontend, Compose, and scan commands below.

**Interfaces:**
- Consumes: Tasks 1–22.
- Produces: one verified branch ready for the single vertical integration PR; no PR is created by this task.

- [ ] **Step 1: Run all backend unit and integration tests.** Run `Push-Location backend; .\mvnw.cmd clean verify; Pop-Location`. Expected: Surefire and Failsafe report zero failures/errors; real MySQL/Redis container suites pass.
- [ ] **Step 2: Run all frontend checks.** Run `Push-Location frontend; npm ci; npm test -- --run; npm run lint; npm run build; Pop-Location`. Expected: Vitest, ESLint, TypeScript, and Vite all exit 0.
- [ ] **Step 3: Run contract/security scans.** Run `rg -n "selectAll|findAll.*filter|\.filter\(.*organization|anyRequest\(\)\.authenticated" backend/src/main`; expected: no SELECT-all-then-filter authorization path and final security remains denyAll. Run `rg -n "/evidence|/imports|/costs|/budgets|/ledger" backend/src/main/java/com/aicostops/iam backend/src/main/java/com/aicostops/organization`; expected: no M2 endpoint implementation.
- [ ] **Step 4: Run scope/tenant scans.** Run `rg -n "organizationId|org_id|ResourceScope" backend/src/main/java/com/aicostops/iam backend/src/main/java/com/aicostops/organization`; inspect every write/list path and confirm organization comes from `AuthorizationContext`, count/rows share predicates, and no request DTO accepts client organization authority.
- [ ] **Step 5: Refactor only verified duplication.** Remove duplicated documentation statements only when the executable contract remains identical; make no production refactor in this task.
- [ ] **Step 6: Run documentation and repository regression.** Run `rg -n "T[B]D|T[O]DO|l[a]ter decide|s[i]milar to previous task" docs/02-development/api docs/03-acceptance/implementation/10-m1-organization-authorization-e2e-evidence.md`; expected: zero placeholders. Run `git status --short`; expected: only verified documentation corrections.
- [ ] **Step 7: Check the final diff.** Run `git diff --check`. Expected: exit 0; inspect `git diff --name-only` and reject any production change introduced by Task 23.
- [ ] **Step 8: Commit verified evidence corrections.** If Steps 1–7 produced documentation corrections, stage only the listed docs and commit `docs(m1): finalize organization authorization evidence`; if no files changed, record that no final commit is needed. Do not amend, push, or create a PR.

## Coverage index

| Requirement | Tasks |
|---|---|
| Design §§1–5 boundaries/current baseline | 1, 4, 14, 23 |
| Design §§6–10 authorization/context/scope/SYSTEM_ADMIN separation | 1–3, 6, 14, 15 |
| Design §11 invalidation | 4, 6, 9, 11 |
| Design §§12–13 IAM and invitations | 5–7 |
| Design §§14–16 master data/transactions/HTTP | 8–14 |
| Design §§17–18 API docs and `/auth/me` | 15–16 |
| Design §§19–20 frontend/audit | 17–21 plus audit assertions in 6, 7, 9, 11 |
| Design §§21–23 acceptance/invariants/completion | 22–23 |
| GitHub #22 AIC-017 | 1–4, 6, 14–16, 21–23 |
| GitHub #23 AIC-018 | 8–14, 16, 19–20, 22–23 |
| GitHub #25 AIC-020 | 15, 17–21, 22–23 |

## Plan execution boundary

Execute tasks in numeric order. Each task is a separate review gate and commit on the same `feat/m1-organization-authorization-e2e` branch; do not split #22/#23/#25 into separate PRs. Stop before push/PR creation and use the branch-finishing workflow only after Task 23 has fresh successful evidence.
