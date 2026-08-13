# M1 Organization & Authorization E2E Design

## 1. Purpose and delivery target

Deliver the second M1 vertical feature on top of the completed Authentication E2E baseline:

- AIC-017 / GitHub #22 — permission and data-scope authorization;
- AIC-018 / GitHub #23 — organization master-data and membership APIs;
- AIC-020 / GitHub #25 — admin and project-settings frontend.

The three issues are one vertical delivery from `feat/m1-organization-authorization-e2e`. This design does not reopen Authentication E2E and does not begin M2.

The fixed base is:

```text
main
105a206 feat(auth): deliver M1 Authentication E2E (#27)
```

Success means an authenticated administrator can manage users, role assignments, invitations, projects, teams, cost centers, provider accounts and project/team memberships through the React application, while every backend decision combines identity, permission, explicit data scope and resource state.

## 2. Frozen technical and repository constraints

- Java 21, Spring Boot 4.1.0, MyBatis Core 3.5.19 and `mybatis-spring-boot-starter` 4.1.0.
- MySQL 8.4 is durable authorization truth. Redis is only a short-lived runtime cache.
- React 19, TypeScript 6, Vite 8, React Router, TanStack Query, Ant Design and the existing Axios client.
- Modular monolith, root package `com.aicostops`, package by feature; each implemented feature uses its `api`, `application`, `domain` and `infrastructure` boundaries.
- Plain MyBatis and explicit SQL for authorization-sensitive reads and writes. No JPA, MyBatis-Plus or new infrastructure framework.
- External API base `/api/v1`; BIGINT values use the existing `ApiId` JSON string representation.
- Errors use the existing RFC 9457-style ProblemDetail response, `ProblemCode`, trace ID and shared frontend mapping.
- MySQL and Redis integration behavior is verified with Testcontainers; H2 is not used.
- Backend authorization is deny-by-default. The browser is never a security boundary.

Existing baseline components are extended rather than replaced: `AuthenticatedUser`, `SecurityVersionService`, `BearerAuthenticationFilter`, `IamMapper`, `OrganizationMapper`, `AuditService`, `SecurityConfiguration`, the `/auth/me` flow, `AuthSessionProvider`, `apiClient`, `ProtectedRoute` and `toProblemDetail`.

## 3. Scope

### 3.1 Included

```text
single active-organization boundary
permission and explicit data-scope evaluation
short Redis authorization-context cache with MySQL fallback
security_version invalidation after authorization changes
IAM admin APIs
admin invitation creation and delivery boundary
project/team/cost-center/provider-account APIs
project and team membership APIs
authenticated application layout
six settings pages
permission-aware actions and route access
audit events required by this feature
backend, frontend and Testcontainers acceptance coverage
API matrix and OpenAPI synchronization during implementation
```

### 3.2 Excluded

```text
Organization CRUD or Organization Settings
custom Role creation
custom Permission creation
OAuth, SAML, MFA, SCIM or LDAP
Evidence, Import, Cost, Allocation, Expense, Budget, Ledger,
Reconciliation or Period Close implementation
M2 work
CI job-name changes
GitHub ruleset changes
```

The existing V1 Role and Permission catalog remains exactly the catalog seeded by `V3__seed_v1_roles_permissions.sql`. This design does not redesign it.

## 4. Current baseline facts

The implementation must preserve these facts from `main@105a206`:

- `AuthenticatedUser` contains `userId` and the JWT `securityVersion`.
- JWT claims remain minimal: identity and security-version data, not permissions or resource IDs.
- `BearerAuthenticationFilter` rejects a stale or disabled identity with `401 AUTH_SESSION_EXPIRED` through `SecurityVersionService`.
- `SecurityVersionService` uses the existing `aicostops:v1:auth:security:{userId}` short cache and falls back to `IamMapper.findActiveSecurityVersion` when Redis is unavailable.
- `/auth/me` currently resolves the active identity through `IamMapper.findAuthenticatedIdentity` and returns user, organization and organization-member IDs.
- V2 already contains `organization`, `app_user`, `organization_member`, Role/Permission tables, four-scope `role_assignment`, invitations, projects, teams, cost centers, provider accounts, project/team membership and `audit_event`.
- `role_assignment` already has natural uniqueness on `(org_member_id, role_id, scope_type, scope_id)`.
- project, team and cost-center codes are unique within an organization. Historical master data and memberships already have ACTIVE/ARCHIVED/DISABLED states.
- `provider_account` currently identifies a record by its BIGINT ID and enforces uniqueness on `(org_id, provider_code, display_name)`; it has no generic `code` column. This feature does not invent one.
- Spring Security currently opens only completed auth routes and denies every unfinished route. Each API delivered by this feature must be opened as authenticated while application services continue to enforce permission and scope.
- the frontend has one in-memory access-token store, one refresh-capable Axios client, one auth session provider and a protected `/app` route. The settings UI extends that stack.

## 5. V1 organization boundary

M1 does not expose Organization CRUD. The application exposes the single ACTIVE organization associated with the authenticated user’s active membership.

The active organization is both:

- the tenant and security boundary; and
- the current business-organization context.

Every user, Role, project, team, cost center, provider account and membership operation derives `organizationId` from the authenticated identity. Clients never choose the organization by request body, query parameter or trusted header.

Authorization-context resolution requires exactly one ACTIVE `organization_member` joined to one ACTIVE organization for the authenticated user. The current authentication baseline selects the first active membership; this feature preserves the V1 single-organization assumption and treats zero or ambiguous active memberships as an invalid session. The invalid result is `401 AUTH_SESSION_EXPIRED`, not a cross-tenant lookup.

All resource SQL includes the derived organization ID. A resource with the requested ID in another organization is indistinguishable from a nonexistent resource.

## 6. Authorization architecture

The fixed architecture is:

```text
BearerAuthenticationFilter
  -> AuthenticatedUser
  -> application service requests AuthorizationContext
  -> explicit permission check
  -> scoped SQL query or scoped mutation lookup
  -> resource-state rule
  -> response or ProblemDetail
```

One real authorization decision is:

```text
Authenticated Identity
+ Permission
+ Data Scope
+ Resource State Rule
```

Responsibilities are separated as follows:

- Spring Security authenticates requests and keeps unimplemented routes denied.
- The authorization-context application boundary resolves effective grants from MySQL and the short cache.
- Application services name the required permission and state transition explicitly.
- Feature mappers receive a precomputed organization and allowed scope criteria; they do not duplicate Role/Permission joins for every query.
- Explicit SQL returns only authorized rows. Java never loads all organization rows and filters afterward.
- Controllers translate request/response DTOs and do not make authorization decisions.

Primary authorization logic must not be hidden across large `@PreAuthorize` SpEL expressions. Mapper code must not independently reinterpret Roles. Frontend checks only improve UX.

## 7. Authorization context

### 7.1 Resolution source

The new `AuthorizationContextService` resolves from MySQL truth:

```text
AuthenticatedUser
  -> ACTIVE app_user and exact security_version
  -> ACTIVE organization_member
  -> ACTIVE organization
  -> role_assignment
  -> role
  -> role_permission
  -> permission
  -> AuthorizationContext
```

The context contains at least:

```text
userId
organizationId
organizationMemberId
securityVersion
permission grants
scoped grants
```

The context representation loads every permission attached to every explicit `role_assignment` through the seeded `role_permission` rows and preserves that assignment's exact scope type and scope ID. It does not discard a grant because its permission is not consumed by an M1 admin endpoint. Duplicate permission-and-scope grants collapse into sets. Role codes are retained for admin display and diagnostics, but services authorize permissions, never Role names.

For example, `FINANCE_REVIEWER` at `COST_CENTER:9` contributes all seeded FINANCE_REVIEWER permissions as explicit COST_CENTER:9 grants. M1 exposes no Finance endpoints, so those grants do not create M2 API access. A future M2 design will define how Evidence, Import, Cost and other Finance endpoints consume them.

Inactive users, organizations or memberships produce no context. A context whose `securityVersion` differs from the authenticated JWT is rejected as `401 AUTH_SESSION_EXPIRED`.

### 7.2 Redis cache

Cache key:

```text
aicostops:v1:iam:context:{userId}:{securityVersion}
```

Default TTL: 60 seconds.

The serialized value contains only the resolved context above; it contains no credential, JWT, invitation token, refresh token or password material.

Read policy:

1. validate the authenticated security version through the existing authentication guard;
2. attempt to read the exact versioned context key;
3. on hit, validate key identity/version and use the context;
4. on miss, malformed value or Redis data-access failure, resolve from MySQL;
5. best-effort write the MySQL result with the 60-second TTL;
6. if MySQL cannot establish an active context, deny; never manufacture an empty-but-authorized context.

Redis failure therefore degrades to MySQL truth. It never grants access and never makes Redis a commit point.

Versioning makes an old context key unreachable after a durable version bump. Explicit best-effort deletion of known old/new context keys still occurs after commit to reduce retained stale data.

### 7.3 Fresh validation for sensitive administration

`ROLE_ASSIGN` and `USER_MANAGE` are sensitive. Before a mutation requiring either permission, the application service performs a fresh MySQL context validation in the same request instead of authorizing solely from the context cache. Inside the mutation transaction, it revalidates the actor's ACTIVE membership and locks the target mutable row with the authorization-sensitive lookup before changing state.

This rule applies even when the preceding non-sensitive page read came from Redis.

## 8. Role scope validity and permission applicability

The only scope types are:

```text
ORG
PROJECT
TEAM
COST_CENTER
```

No fifth scope is introduced. Scope IDs are interpreted by scope type:

- ORG: `scope_id` equals the current organization ID;
- PROJECT: `scope_id` is a project ID in the current organization;
- TEAM: `scope_id` is a team ID in the current organization;
- COST_CENTER: `scope_id` is a cost-center ID in the current organization.

There is no implicit hierarchy or inheritance. `TEAM:7` grants nothing for projects merely related to Team 7. `PROJECT:42` grants nothing for that project’s cost center. Any future relationship-based propagation requires a separate explicit design.

### 8.1 Role assignment scope validity

Role assignment creation first validates the requested Role and scope against this frozen matrix:

| Role | Valid assignment scopes |
|---|---|
| `EMPLOYEE` | ORG |
| `PROJECT_OWNER` | PROJECT |
| `FINANCE_REVIEWER` | ORG, COST_CENTER |
| `FINANCE_ADMIN` | ORG |
| `SYSTEM_ADMIN` | ORG, PROJECT, TEAM, COST_CENTER |

The scope ID must then identify the current authorization boundary: ORG requires `scope_id == organizationId`; PROJECT, TEAM and COST_CENTER require an existing resource of the matching type in the current organization. Role scope validity is independent of whether an M1 admin endpoint consumes any permission seeded on that Role.

Consequently, `EMPLOYEE + ORG`, `PROJECT_OWNER + PROJECT`, `FINANCE_REVIEWER + ORG`, `FINANCE_REVIEWER + COST_CENTER`, `FINANCE_ADMIN + ORG` and every matrix-listed SYSTEM_ADMIN scope are valid. `PROJECT_OWNER + ORG` and `FINANCE_REVIEWER + PROJECT` are invalid.

### 8.2 M1 admin permission applicability

M1 admin permission applicability is frozen as follows:

| Permission | Applicable scopes |
|---|---|
| `USER_READ` | ORG |
| `USER_MANAGE` | ORG |
| `USER_INVITE` | ORG |
| `ROLE_READ` | ORG |
| `ROLE_ASSIGN` | ORG |
| `PROJECT_READ` | ORG, PROJECT |
| `PROJECT_MANAGE` | ORG, PROJECT |
| `PROJECT_MEMBER_MANAGE` | ORG, PROJECT |
| `TEAM_READ` | ORG, TEAM |
| `TEAM_MANAGE` | ORG, TEAM |
| `COST_CENTER_READ` | ORG, COST_CENTER |
| `COST_CENTER_MANAGE` | ORG, COST_CENTER |
| `PROVIDER_ACCOUNT_READ` | ORG |
| `PROVIDER_ACCOUNT_MANAGE` | ORG |

This table is used only when an M1 admin endpoint evaluates its required permission against the explicit grants in `AuthorizationContext`. It does not decide whether a Role assignment can be created and does not filter grants during context resolution. An admin permission grant authorizes an endpoint only when the grant's explicit scope appears in that permission's applicable-scope row and the requested resource matches that scope.

Permissions outside the M1 admin endpoints remain in the context with their explicit assignment scopes but do not activate M2 APIs. Their existing catalog and finance separation remain unchanged.

## 9. Authorization decision semantics

### 9.1 Missing permission

If the context has no effective grant for the required permission at any applicable scope, return:

```text
403 FORBIDDEN
```

Example: an EMPLOYEE calls `POST /projects`.

### 9.2 Permission present but resource outside scope

If the permission exists but the requested resource is not visible through an effective grant, return privacy-preserving:

```text
404 RESOURCE_NOT_FOUND
```

The same 404 is used for a nonexistent ID, a cross-organization ID and an ID outside the caller’s data scope. The response never reveals which condition occurred.

Example: a caller with `PROJECT_MANAGE` only at `PROJECT:42` calls `PATCH /projects/99`.

### 9.3 Scoped lists

List queries use one of these SQL shapes:

```text
ORG grant:
WHERE resource.org_id = :organizationId

scoped grant:
WHERE resource.org_id = :organizationId
  AND resource.id IN (:allowedIds)
```

An empty scoped ID set returns an empty page. It never drops the scope predicate. Pagination and count queries use the same scope predicate so totals do not leak unauthorized resources.

Users, Roles, permissions and provider accounts are ORG-only and always use the current organization boundary where applicable. Role and Permission catalog rows are global reference rows, but visibility is allowed only through an ORG-scoped `ROLE_READ` grant.

### 9.4 Resource-state rules

Authorization does not override lifecycle rules:

- ACTIVE resources can be edited subject to permission and scope.
- DISABLED and ARCHIVED resources remain readable when the endpoint includes historical rows, but cannot receive new memberships.
- status transitions use PATCH; no master-data or membership hard-delete occurs.
- membership DELETE endpoints are semantic disable/archive operations against the current active membership row.
- invalid or repeated transitions return the existing `409 STATE_CONFLICT` contract unless the operation is explicitly repeat-safe.
- immutable codes/provider identifiers cannot be changed by PATCH.

## 10. SYSTEM_ADMIN and finance separation

The V3 seed remains authoritative:

```text
SYSTEM_ADMIN != FINANCE_ADMIN
```

`SYSTEM_ADMIN` does not automatically receive:

```text
LEDGER_POST
LEDGER_CORRECT
BUDGET_MANAGE
PERIOD_CLOSE
PERIOD_REOPEN
```

There is no super-admin bypass in controllers, application services, SQL or frontend routing. A user needing both administration and finance authority must receive an additional finance Role assignment. Context union combines the explicit assignments; it never infers one Role from another.

## 11. Authorization-change invalidation

The following mutations change the target user’s effective authorization:

- Role assignment create;
- Role assignment revoke;
- user disable;
- project membership add/remove/status change;
- team membership add/remove/status change.

The durable transaction is:

```text
begin MySQL transaction
  -> lock/validate target app_user and organization_member
  -> perform mutation
  -> append required audit_event
  -> UPDATE app_user
       SET security_version = security_version + 1,
           updated_at = :now
       WHERE id = :targetUserId
commit
  -> write the new version to aicostops:v1:auth:security:{targetUserId}
  -> delete authorization-context keys known for the old/new version
  -> best-effort revoke target refresh sessions where the existing auth runtime supports it
```

The mutation and version increment must affect exactly one target user or the transaction fails. The response does not report success before the durable transaction commits.

After the bump, an access token containing the previous `sv` is stale and is rejected by the authentication layer as:

```text
401 AUTH_SESSION_EXPIRED
```

Cache maintenance is post-commit and cannot roll back MySQL truth. If Redis is unavailable, subsequent security/context resolution falls back to MySQL and rejects the old version; the authorization path never accepts an old context merely because cache invalidation failed.

Project/team membership changes bump the affected member’s user version because those relationships can be used by current or future scoped business rules even though this design defines no implicit scope inheritance.

## 12. IAM admin API

All routes are under `/api/v1`, require bearer authentication and are limited to the current organization.

| Method | Path | Permission | Result |
|---|---|---|---|
| GET | `/users` | `USER_READ` / ORG | paged users |
| GET | `/users/{id}` | `USER_READ` / ORG | user detail |
| PATCH | `/users/{id}/status` | `USER_MANAGE` / ORG, fresh | updated user |
| GET | `/roles` | `ROLE_READ` / ORG | frozen Role catalog with permissions |
| GET | `/permissions` | `ROLE_READ` / ORG | frozen Permission catalog |
| POST | `/role-assignments` | `ROLE_ASSIGN` / ORG, fresh | created assignment |
| DELETE | `/role-assignments/{id}` | `ROLE_ASSIGN` / ORG, fresh | 204 |
| POST | `/invitations` | `USER_INVITE` / ORG | created invitation metadata |

These collection endpoints are paged with the existing `PageRequest`/`PageResponse` contract:

```text
GET /users
GET /projects
GET /projects/{id}/members
GET /teams
GET /teams/{id}/members
GET /cost-centers
GET /provider-accounts
```

`GET /roles` and `GET /permissions` are unpaged reference-catalog reads. For every paged endpoint, count and row queries use the same organization, scope and filter predicates. All IDs serialize as strings.

### 12.1 User representation

The complete M1 User representation returned by both `GET /users` and `GET /users/{id}` is:

```text
id
email
displayName
status: ACTIVE | DISABLED
organizationMember:
  id
  status
  employeeNo
  defaultCostCenterId
roleAssignments[]:
  id
  role { id, code, name }
  scopeType
  scopeId
  createdAt
```

`GET /users` returns this full representation for every page item. M1 does not introduce separate UserSummary and UserDetail product models. SQL joins are organization constrained and aggregate Role assignments without N+1 authorization queries. The count query uses the same organization and user filters as the row query.

`PATCH /users/{id}/status` accepts only `ACTIVE` or `DISABLED`. A real status change bumps `security_version`, invalidates old sessions and returns the updated representation. ACTIVE to DISABLED audits `USER_DISABLED`; DISABLED to ACTIVE audits `USER_ENABLED`. Repeating the current status returns the current representation without another version bump. A caller cannot use this endpoint to move a user into a different organization.

### 12.2 Role and Permission reads

Roles and permissions are read-only. Responses reflect V3 seed rows and `role_permission`; there are no create, edit or delete endpoints. The Roles page explains scope applicability but does not modify the catalog.

### 12.3 Role assignment create

Request:

```text
organizationMemberId
roleId
scopeType
scopeId
```

In one organization-constrained transaction, creation validates:

1. actor still has fresh ORG-scoped `ROLE_ASSIGN`;
2. target member is ACTIVE and belongs to the current organization;
3. Role exists in the frozen catalog;
4. scope type is one of the four supported values;
5. ORG scope ID equals the current organization ID, or the typed resource exists in the current organization;
6. the Role and requested scope match the Role Scope Validity matrix in section 8.1;
7. the natural tuple is unique.

Duplicate natural assignments return `409 STATE_CONFLICT`. Invalid request shape, unsupported scope type and a Role/scope pair absent from the Role Scope Validity matrix return `400 VALIDATION_FAILED`. A foreign or invisible scope resource returns privacy-preserving `404 RESOURCE_NOT_FOUND`.

On success, insert `role_assignment`, bump the target user’s security version and append `ROLE_ASSIGNED`.

### 12.4 Role assignment revoke

`DELETE /role-assignments/{id}` only revokes an assignment in the current organization. It performs a semantic removal of the current assignment row as allowed by the existing schema, appends `ROLE_REVOKED` and bumps the target user’s security version in the same transaction. Because `role_assignment` is an authorization fact rather than historical business master data, physical deletion of this current grant is permitted; history remains in append-only audit.

A nonexistent, foreign-org or inaccessible assignment returns 404. Repeating a successful revoke returns 404 because the grant no longer exists.

## 13. Invitations

### 13.1 Creation

`POST /invitations` requires ORG-scoped `USER_INVITE`. Request fields:

```text
email
initialRoleCode
expiresInHours (bounded by server policy; server default applies when absent)
```

The server default is 72 hours; accepted values are 1 through 168 hours.

Rules:

- normalize email using the existing identity rule;
- reject an existing organization member or conflicting active identity state with `409 STATE_CONFLICT`;
- generate a token from at least 32 random bytes using the platform cryptographic random source and encode it URL-safely;
- persist only its one-way digest in `invitation.token_hash`;
- store PENDING status, current organization, inviter member, initial Role code and bounded expiry;
- never place the raw token in API JSON, audit metadata or application logs;
- append `INVITATION_CREATED` without secret material.

`PROJECT_OWNER` is not valid as a generic ORG-scoped initial invitation Role. The supported path is:

```text
invite as EMPLOYEE
  -> accept invitation
  -> administrator explicitly assigns PROJECT_OWNER at PROJECT:{id}
```

Other initial Roles must be listed with ORG scope in the Role Scope Validity matrix and exist in the frozen Role catalog.

### 13.2 Delivery

Invitation creation introduces an `InvitationDelivery` application boundary following the existing `PasswordResetDelivery` pattern.

- A dev-profile implementation writes invitation delivery to a dev-only file mailbox with restrictive local handling; tests read that boundary directly.
- The ordinary default/production configuration does not claim an SMTP provider exists. Without an explicitly configured production delivery implementation, creation fails closed with `503 DEPENDENCY_TEMPORARILY_UNAVAILABLE` before reporting an invitation as delivered.
- The raw token is handed to delivery only in memory and is never logged.
- Invitation row insertion, `INVITATION_CREATED` audit append and the dev-file delivery call execute inside the creation transaction. A delivery exception rolls the database transaction back and returns 503; any file written before an unlikely commit failure contains a token with no valid database digest and is therefore unusable. Default/production rejection occurs before starting this transaction. Delivery failure never returns the token or a successful API result.

Acceptance continues through the existing public `POST /invitations/{token}/accept` implementation. The token remains single use and TTL-bound, and the database stores only its hash.

## 14. Organization master-data API

All routes use the current organization implicitly. Create requests never accept `orgId`.

| Method | Path | Permission / scope |
|---|---|---|
| GET | `/projects` | `PROJECT_READ`, ORG or PROJECT-scoped SQL |
| POST | `/projects` | `PROJECT_MANAGE`, ORG |
| PATCH | `/projects/{id}` | `PROJECT_MANAGE`, ORG or matching PROJECT |
| GET | `/projects/{id}/members` | `PROJECT_READ` or `PROJECT_MEMBER_MANAGE`, matching project |
| POST | `/projects/{id}/members` | `PROJECT_MEMBER_MANAGE`, matching project |
| DELETE | `/projects/{id}/members/{memberId}` | `PROJECT_MEMBER_MANAGE`, matching project |
| GET | `/teams` | `TEAM_READ`, ORG or TEAM-scoped SQL |
| POST | `/teams` | `TEAM_MANAGE`, ORG |
| PATCH | `/teams/{id}` | `TEAM_MANAGE`, ORG or matching TEAM |
| GET | `/teams/{id}/members` | `TEAM_READ` or `TEAM_MANAGE`, matching team |
| POST | `/teams/{id}/members` | `TEAM_MANAGE`, matching team |
| DELETE | `/teams/{id}/members/{memberId}` | `TEAM_MANAGE`, matching team |
| GET | `/cost-centers` | `COST_CENTER_READ`, ORG or COST_CENTER-scoped SQL |
| POST | `/cost-centers` | `COST_CENTER_MANAGE`, ORG |
| PATCH | `/cost-centers/{id}` | `COST_CENTER_MANAGE`, ORG or matching COST_CENTER |
| GET | `/provider-accounts` | `PROVIDER_ACCOUNT_READ`, ORG |
| POST | `/provider-accounts` | `PROVIDER_ACCOUNT_MANAGE`, ORG |
| PATCH | `/provider-accounts/{id}` | `PROVIDER_ACCOUNT_MANAGE`, ORG |

Create requires ORG scope because a scoped grant cannot authorize creation of an ID that does not yet exist. Read/update can use a matching resource scope as shown.

### 14.1 Project, team and cost center

Create fields:

```text
code
name
```

Created status is ACTIVE. `code` is normalized according to the existing API string rules, is unique within the current organization and is immutable after creation. PATCH accepts `name` and/or `status` but never `code`. Status is ACTIVE, DISABLED or ARCHIVED. A duplicate code returns `409 STATE_CONFLICT`.

List responses include ACTIVE, DISABLED and ARCHIVED records by explicit status filter; the default settings view includes all states so administrators can manage lifecycle. Scope predicates apply before status and pagination predicates.

### 14.2 Provider account

The current schema has no generic account code. Its contract therefore uses existing fields:

```text
providerCode       immutable after create
displayName        editable
externalAccountRef editable
status             ACTIVE | DISABLED | ARCHIVED
metadata           editable non-secret provider metadata
```

Create enforces the existing organization uniqueness rule `(providerCode, displayName)`. PATCH cannot change `providerCode`; editing `displayName` must continue to satisfy the same uniqueness constraint. Credentials or API keys are not accepted in `metadata` by this feature.

### 14.3 Memberships

Project and team membership request:

```text
organizationMemberId
```

Rules:

- parent project/team must be ACTIVE and in the current organization;
- target organization member must be ACTIVE and in the same organization;
- cross-organization membership is rejected without revealing the foreign record;
- the natural parent/member pair is unique;
- add creates or reactivates an ACTIVE membership according to current row state;
- DELETE performs a lifecycle transition, not a destructive delete;
- every effective membership change appends `MEMBERSHIP_CHANGED` and bumps the target user’s security version.

Membership lists expose membership ID, organization-member ID, user display fields, status and joined time. The `{memberId}` path parameter names the membership row ID, not the global user ID, matching the existing matrix naming.

## 15. Transaction and concurrency rules

Durable mutation boundaries are:

```text
user status + security_version + audit
role assignment + security_version + audit
role revoke + security_version + audit
project/team membership + security_version + audit
invitation creation + audit
master-data create/update
```

Authorization-sensitive mutations revalidate actor and target organization inside the transaction. Natural database constraints remain the final race-safe guard. Constraint races are translated to `409 STATE_CONFLICT` rather than leaking SQL details.

No API hard-deletes project, team, cost center, provider account, project membership or team membership. Role assignment revoke is the documented exception because the table represents a current grant and audit preserves the event history.

## 16. HTTP and error contract

| Condition | Status | Code |
|---|---:|---|
| unauthenticated/expired access token | 401 | existing auth code |
| stale security version or disabled target session | 401 | `AUTH_SESSION_EXPIRED` |
| missing required permission | 403 | `FORBIDDEN` |
| ID absent, cross-org or outside data scope | 404 | `RESOURCE_NOT_FOUND` |
| malformed/invalid fields | 400 | `REQUEST_MALFORMED` / `VALIDATION_FAILED` |
| duplicate code/assignment/membership or invalid state transition | 409 | `STATE_CONFLICT` |
| required invitation delivery unavailable | 503 | `DEPENDENCY_TEMPORARILY_UNAVAILABLE` |

Every error uses the existing ProblemDetail shape with `type`, `title`, `status`, `detail`, `instance`, `code` and `traceId`. Error detail must not distinguish missing, foreign-organization and out-of-scope resources.

## 17. API documentation synchronization

Implementation of this design must update both:

```text
docs/02-development/api/02-接口矩阵.md
docs/02-development/api/openapi.yaml
```

The matrix must add the approved GET/PATCH/member routes listed in section 14. OpenAPI must replace generic IAM/master-data objects with concrete request, response, page, status and ProblemDetail schemas, including string-form IDs and the 403/404 distinction. No additional M1 routes are implied beyond sections 12 and 14.

## 18. `/auth/me` contract

The current response remains compatible and gains:

```text
id
email
displayName
organizationId
organizationMemberId
permissions: string[]
```

`permissions` is the sorted, deduplicated projection of M1 admin permission codes having at least one explicit context grant whose scope is applicable under section 8.2. Thus a PROJECT-scoped SYSTEM_ADMIN assignment does not expose ORG-only `USER_READ`, while its PROJECT-applicable permissions remain visible. Non-M1 seeded permissions remain in the backend `AuthorizationContext` but are not included in this M1 browser projection because no M2 page or endpoint is delivered. Complete scope truth is not exposed to the browser. This list supports navigation and action visibility only; backend scope SQL remains authoritative.

`/auth/me` resolves the same versioned authorization context used by application services. A stale security version returns `401 AUTH_SESSION_EXPIRED`.

## 19. Frontend information architecture

### 19.1 Authenticated layout

Replace the current temporary protected application page with one authenticated application layout built on the existing router and auth provider. It contains navigation, current-user context, logout and an outlet for protected pages.

Required routes:

```text
/settings/users
/settings/roles
/settings/projects
/settings/teams
/settings/cost-centers
/settings/provider-accounts
```

No Redux or second UI/API framework is added. Server state uses TanStack Query; requests use the existing `apiClient`; forms, tables, feedback and layout use Ant Design.

### 19.2 Page behavior

- Users: list/detail, disable action, invitation creation and Role assignment/revoke management.
- Roles: read-only Role/Permission catalog and scope applicability.
- Projects: list, create, edit lifecycle and manage project members.
- Teams: list, create, edit lifecycle and manage team members.
- Cost Centers: list, create and edit lifecycle.
- Provider Accounts: list, create and edit lifecycle using the existing provider-account fields.

Every page has an explicit loading state, empty state, successful data state and ProblemDetail error state. Mutations show pending/disabled controls and display server validation/conflict messages. Role assignment, Role revoke, user-status and membership mutations invalidate the affected list/detail and `/auth/me`; master-data and invitation mutations invalidate only their affected list/detail queries.

### 19.3 Permission-aware UX

Navigation and actions use `/auth/me.permissions`:

- `USER_READ` shows Users; `USER_MANAGE` controls disable actions; `USER_INVITE` controls invitation actions; `ROLE_ASSIGN` controls assignment actions.
- `ROLE_READ` shows Roles.
- corresponding READ permission shows each master-data page.
- corresponding MANAGE permission shows create/edit/member actions.

A required READ permission code makes its settings route navigable. Backend list queries return the authorized scoped subset, and an inaccessible resource ID returns privacy-preserving 404. The UI does not attempt to reconstruct scope truth.

Navigation hides a settings item when the required READ permission is absent. If an authenticated user directly navigates to that settings URL without the required READ permission, the authenticated layout renders a 403 Forbidden page in place. It does not redirect and does not issue hidden API or mutation requests. Backend authorization remains authoritative.

### 19.4 Role/security change handling

For an authorization-related 403, the frontend refetches `/auth/me` once and updates navigation/actions. It then presents the original forbidden result; it does not blindly retry the mutation.

For `401 AUTH_SESSION_EXPIRED` after refresh/retry handling:

```text
clear in-memory access token
clear auth-related TanStack Query cache
set anonymous session state
redirect to /login
show that permissions changed and sign-in is required
```

This behavior extends the existing single-flight refresh client without creating a refresh loop.

## 20. Audit

Use the existing append-only `AuditService`/`audit_event` boundary. This feature records at least:

| Event | Subject | Required non-secret metadata |
|---|---|---|
| `USER_DISABLED` | user | previous/new status, target member ID |
| `USER_ENABLED` | user | previous/new status, target member ID |
| `ROLE_ASSIGNED` | role assignment | target member, Role code, scope type and scope ID |
| `ROLE_REVOKED` | role assignment | target member, Role code, scope type and scope ID |
| `MEMBERSHIP_CHANGED` | project/team membership | parent type/ID, member ID, previous/new status |
| `INVITATION_CREATED` | invitation | normalized email, initial Role code, expiry |

Each event includes current organization and actor user where available. Audit metadata never contains invitation raw tokens, passwords, JWTs, refresh secrets, reset secrets or provider credentials. The existing `AuditService` secret-key rejection remains defense in depth.

## 21. Test strategy and acceptance matrix

### 21.1 Backend authorization and Testcontainers

Real MySQL 8.4 and Redis containers cover:

| Scenario | Expected result |
|---|---|
| correct permission + correct scope | success |
| wrong Role / no required permission | 403 `FORBIDDEN` |
| correct permission + wrong resource scope | privacy-preserving 404 |
| ORG grant | all applicable current-org resources only |
| PROJECT grant | only explicit project IDs |
| TEAM grant | only explicit team IDs |
| COST_CENTER grant | only explicit cost-center IDs |
| context Redis hit | same decision as MySQL resolution |
| Redis unavailable | MySQL fallback, same decision |
| malformed cached context | ignore cache and resolve MySQL |
| EMPLOYEE + ORG | valid assignment; all seeded EMPLOYEE grants retained at ORG |
| PROJECT_OWNER + PROJECT | valid assignment; all seeded PROJECT_OWNER grants retained at that PROJECT |
| PROJECT_OWNER + ORG | 400 invalid Role/scope pair |
| FINANCE_REVIEWER + ORG | valid assignment; all seeded grants retained at ORG |
| FINANCE_REVIEWER + COST_CENTER | valid assignment; all seeded grants retained at that COST_CENTER |
| FINANCE_REVIEWER + PROJECT | 400 invalid Role/scope pair |
| FINANCE_ADMIN + ORG | valid assignment; all seeded FINANCE_ADMIN grants retained at ORG |
| Role revoked | assignment removed, audit written, version bumped, old JWT rejected |
| user disabled | durable disabled state, audit written, version bumped, old JWT rejected |
| SYSTEM_ADMIN only | no finance-sensitive permission |
| SYSTEM_ADMIN + explicit FINANCE_ADMIN | context union includes explicitly assigned FINANCE_ADMIN permissions |
| non-M1 permission present in context | no M2 endpoint is opened or implemented |
| cross-org project membership | rejected without foreign data disclosure |
| cross-org team membership | rejected without foreign data disclosure |
| disabled/archived master data | preserved; no destructive deletion |
| duplicate project/team/cost-center code | 409 |
| duplicate Role assignment/membership | 409 |
| Role assign/revoke | target version bump and required audit |
| project/team membership change | target version bump and `MEMBERSHIP_CHANGED` audit |
| sensitive mutation with warm cache | fresh MySQL actor validation |
| invitation create | high-entropy delivery token, hash-only DB, TTL, audit without secret |
| invitation reuse/expiry | rejected, no partial identity change |

Scoped list tests assert returned rows and count totals, proving filtering occurs in SQL rather than Java.

### 21.2 API contract tests

Contract tests cover string-form IDs, current-organization derivation, concrete request/response schemas, immutable fields, lifecycle states, pagination, 403 versus 404, and ProblemDetail codes. Security configuration tests prove only the delivered authenticated routes are opened and unfinished routes remain denied.

### 21.3 Frontend tests

Use the existing Vitest/Testing Library setup to cover:

```text
authenticated application layout and protected settings routes
permission-aware navigation and action visibility
missing READ permission hides the settings navigation item
direct unauthorized settings URL renders authenticated 403 without redirect or hidden request
USER_READ without USER_MANAGE hides Disable
PROJECT_READ without PROJECT_MANAGE hides Create/Edit
loading state
empty state
ProblemDetail error state
successful mutation cache invalidation
member and Role-assignment mutation refresh
403 causes one /auth/me refresh and permission UI update
401 AUTH_SESSION_EXPIRED clears session and redirects with message
no refresh or mutation retry loop
```

### 21.4 Issue acceptance traceability

GitHub #22 is satisfied by sections 6–11 and the authorization matrix: four scopes, context resolution, short Redis cache, version invalidation, wrong-Role/wrong-scope denial and SYSTEM_ADMIN/finance separation.

GitHub #23 is satisfied by sections 14–17 and master-data tests: organization-bound APIs, code uniqueness, membership rules, scoped authorization, lifecycle preservation and synchronized API documents. Organization CRUD itself remains excluded by the approved V1 boundary.

GitHub #25 is satisfied by sections 18–19 and frontend tests: all six settings pages, backend-aligned permission/scope behavior, lifecycle UX, shared ProblemDetail handling and loading/empty/error coverage.

## 22. Security and scope invariants

The feature is acceptable only while all of these remain true:

1. MySQL is authorization truth; Redis loss cannot grant access.
2. JWT contains no permission or project/team/cost-center lists.
3. organization ID comes from authenticated context, never client authority.
4. missing permission is 403; invisible resource is privacy-preserving 404.
5. lists are scope-filtered in SQL.
6. no implicit scope hierarchy exists.
7. sensitive administration receives fresh MySQL authorization validation.
8. every authorization-changing mutation bumps the target user’s durable security version.
9. SYSTEM_ADMIN has no finance bypass.
10. frontend checks are UX only.
11. historical master data is disabled/archived rather than deleted.
12. secrets never enter audit or ordinary logs.

## 23. Completion definition

Organization & Authorization E2E is complete when the six protected settings pages operate against the approved IAM and master-data APIs; permission, scope, organization and lifecycle rules are independently enforced by backend application services and scoped SQL; authorization changes invalidate old sessions through durable security versions; Redis outage falls back safely to MySQL; required audit events exist; and the #22/#23/#25 acceptance matrix passes with no M2 implementation or Organization CRUD introduced.
