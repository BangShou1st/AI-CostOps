# M0 Repository Foundation Design

## Goal

Upgrade AI CostOps from a documentation-only repository into a buildable,
testable, runnable monorepo that can start through Docker Compose and provides
stable GitHub Actions checks. This work implements AIC-002 through AIC-008 and
AIC-010 on `chore/m0-repository-foundation`; AIC-009 remains a later repository
settings checkpoint.

## Frozen boundaries

- Keep the accepted modular-monolith architecture and package-by-feature layout.
- Do not implement M1+ business behavior, including authentication, CRUD,
  provider adapters, ingestion, allocation, budgets, ledger, reconciliation,
  close, or reporting.
- Use Java 21, Spring Boot 4.1.0, MySQL 8.4 LTS, Redis, MinIO, React 19,
  TypeScript, and Vite.
- Use plain MyBatis integration, not MyBatis-Plus or another enhanced ORM.
  The resolved stack is `mybatis-spring-boot-starter` 4.1.0 with MyBatis Core
  3.5.19; documentation must not describe this as “MyBatis Core 4”.
- MySQL remains the identity and financial source of truth. Redis is limited to
  session, TTL, rate-limit, and cache foundations. MinIO stores evidence objects.
- Preserve the API conventions already frozen in `openapi.yaml`: `/api/v1`,
  string IDs and decimal amounts, UTC instants, and `application/problem+json`.

## Delivery approach

Three organizations were considered:

1. Build each technology stack completely before cross-stack integration. This
   minimizes context switching but postpones integration feedback.
2. Treat every AIC issue as a fully isolated mini-project. This maximizes local
   separation but creates churn because later foundation issues modify the same
   bootstrap files.
3. Deliver dependency-ordered waves with issue-aligned commits and a checkpoint
   after each wave. This preserves reviewable history while validating integration
   early.

Use approach 3 because it matches the approved backlog and explicit task
instructions.

## Wave 1 — bootstraps and local infrastructure

### Backend (AIC-002)

Create a Maven Wrapper project under `backend/` with root package
`com.aicostops`, an application entry point, non-secret configuration, and an
application-context smoke test. Add Web, Validation, Security, Actuator,
Flyway, MySQL Connector/J, plain MyBatis Spring Boot integration, Spring Boot
Test, and Testcontainers dependencies. Security may permit the health endpoint
for M0, but no authentication workflow is implemented.

### Frontend (AIC-003)

Create a locked npm project under `frontend/` using React 19, TypeScript, Vite,
React Router, TanStack Query, Ant Design, ECharts, Axios, ESLint, Vitest, and
React Testing Library. The initial UI is only an application shell and M0 status
surface. It must provide real `lint`, `test`, and `build` scripts.

### Infrastructure (AIC-004)

Create `compose.yaml` for the complete stack and `compose.dev.yaml` for local
infrastructure port exposure. Define MySQL 8.4, Redis, and MinIO with named
volumes, an internal network, environment values aligned with `.env.example`,
and health checks. No real secret is committed.

## Wave 2 — shared runtime behavior and test foundations

### Backend shared foundation (AIC-005)

Create only stable cross-module primitives under `com.aicostops.shared`:

- `CurrencyCode`: validated, normalized three-letter currency code.
- `Money`: immutable `BigDecimal` plus currency value object with scale-safe
  equality and JSON decimal-string representation.
- ID JSON policy: BIGINT-compatible Java values serialize to JSON strings.
- Pagination value objects matching page `0`, size `50`, maximum `200`.
- UTC time configuration and injectable `Clock` only where a stable runtime
  seam is useful.
- typed problem codes/exceptions and a global Spring `ProblemDetail` mapper.

Do not add `CommonUtils`, base controllers/services/repositories, generic
managers, or unused abstractions.

### Database foundation (AIC-006)

Add a Flyway baseline migration that proves an empty MySQL schema can migrate
without introducing M1 domain tables. Add a reusable MySQL Testcontainers base
and an integration test that starts a real MySQL container, runs Flyway, and
loads the Spring context. Do not use H2. Redis container support is included as
test infrastructure without making the initial context test depend on a local
Redis installation.

### Frontend application foundation (AIC-007)

Add focused units under `src/app`, `src/api`, `src/features`, and `src/shared`:

- application providers and router shell;
- public and protected route shells without a real login page;
- in-memory access-token store;
- Axios client using `/api/v1`;
- one bounded refresh/retry path and normalized ProblemDetail mapping;
- reusable API pagination types.

Tests cover actual behavior: provider rendering, token storage, request headers,
single retry boundaries, problem mapping, and protected-route decisions.

## Wave 3 — CI and application containers

### GitHub Actions (AIC-008)

Create stable checks named exactly:

- `backend-unit`
- `backend-integration`
- `backend-architecture`
- `frontend-lint`
- `frontend-test`
- `frontend-build`
- `docker-build`

Unit, integration, and architecture test selection must be real and mutually
understandable. Docker validation builds both application images. The workflow
does not configure required checks; that is AIC-009 after checks appear on a PR.

### Docker and complete Compose (AIC-010)

Create multi-stage backend and frontend images. The frontend runtime uses Nginx,
serves the SPA, and proxies `/api/v1` to `backend`. Compose adds backend and
frontend health/dependency wiring while keeping databases on the internal
network in full-stack mode. The result is a development/demo foundation, not a
production deployment design.

## Testing and validation policy

Behavior code follows test-first red/green/refactor. This includes Money,
currency and ID serialization, ProblemDetail mapping, pagination validation,
API client behavior, token storage, and route protection.

Generated or declarative infrastructure—Maven Wrapper, Vite scaffolding,
Compose, Dockerfiles, GitHub Actions, and base configuration—does not receive
artificial unit tests. It is proven by executable validation: Maven tests,
frontend lint/test/build, `docker compose config`, image builds, service health,
and available full-stack smoke checks.

## Documentation and commits

Synchronize `README.md`, `PROJECT_CONTEXT.md`, `.env.example`, implementation
runbooks, acceptance evidence, and every inaccurate “MyBatis 4” statement that
actually means starter 4.x plus Core 3.5.x. Do not rewrite unrelated accepted
architecture or ADR decisions.

Keep issue-aligned logical commits for backend bootstrap, frontend bootstrap,
infrastructure, shared backend behavior, database tests, frontend foundation,
CI, Docker, and final documentation. Do not squash local history and do not
merge a pull request.
