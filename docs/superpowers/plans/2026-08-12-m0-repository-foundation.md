# M0 Repository Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver AIC-002 through AIC-008 and AIC-010 as a buildable, tested,
runnable M0 monorepo foundation without implementing M1+ business features.

**Architecture:** A Java 21 Spring Boot modular monolith and a React 19
management shell share one repository and run behind Nginx with MySQL 8.4,
Redis, and MinIO through Docker Compose. Stable behavior lives in focused
package-by-feature/shared units and is developed test-first; declarative
infrastructure is verified through executable build and runtime checks.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Maven Wrapper, MyBatis Spring Boot
Starter 4.1.0 / MyBatis Core 3.5.19, Flyway, MySQL 8.4, Testcontainers, React
19.2.8, TypeScript 7, Vite 8, Vitest, Docker Compose, Nginx, GitHub Actions.

## Global Constraints

- Work only on `chore/m0-repository-foundation`; never merge a PR.
- Do not implement Login, CRUD, Provider Adapter, ingestion, allocation, budget,
  ledger, reconciliation, period close, reporting, or any other M1+ behavior.
- Backend root package is `com.aicostops` and follows package-by-feature.
- All API IDs and decimal money values serialize as JSON strings.
- Configuration/generated files use executable validation; behavior code uses
  red-green-refactor and must be observed failing before implementation.
- Integration tests use a real MySQL Testcontainer and never H2.
- AIC-009 is excluded until real checks have run successfully on a GitHub PR.
- Preserve issue-aligned commits and do not squash local history.

---

### Task 1: AIC-002 Spring Boot backend bootstrap

**Files:**
- Create: `backend/pom.xml`
- Create: `backend/.mvn/wrapper/maven-wrapper.properties`
- Create: `backend/mvnw`
- Create: `backend/mvnw.cmd`
- Create: `backend/src/main/java/com/aicostops/AiCostOpsApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/com/aicostops/AiCostOpsApplicationTest.java`

**Interfaces:**
- Produces: Spring application `com.aicostops.AiCostOpsApplication` and Maven
  profiles/test naming that later CI tasks invoke.

- [ ] **Step 1: Generate and pin the Maven Wrapper**

Run from `backend/` after creating the POM:

```powershell
mvn wrapper:wrapper
```

Commit the generated wrapper scripts and `.mvn/wrapper` files.

- [ ] **Step 2: Create the dependency-managed POM**

Use Spring Boot parent `4.1.0`, Java `21`, MyBatis starter `4.1.0`, and include
Web, Validation, Security, Actuator, Flyway, MySQL driver, Testcontainers MySQL,
Testcontainers JUnit Jupiter, and Spring Boot Test. Configure Surefire so
`*Test` runs in `test` and Failsafe so `*IntegrationTest` runs in `verify`.

- [ ] **Step 3: Add the application entry point and safe defaults**

```java
package com.aicostops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AiCostOpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiCostOpsApplication.class, args);
    }
}
```

Configure environment-backed datasource values and expose only health/info.
Disable Flyway in the default unit-test profile so the smoke test does not
require locally installed MySQL.

- [ ] **Step 4: Add and run the context smoke test**

```java
@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
})
class AiCostOpsApplicationTest {
    @Test void contextLoads() {}
}
```

Run:

```powershell
Set-Location backend
.\mvnw.cmd test
Set-Location ..
```

Expected: `BUILD SUCCESS`, one context test passing.

- [ ] **Step 5: Commit**

```powershell
git add backend
git commit -m "chore(backend): bootstrap Spring Boot application"
```

### Task 2: AIC-003 React frontend bootstrap

**Files:**
- Create: `frontend/package.json`, `frontend/package-lock.json`
- Create: `frontend/index.html`, `frontend/vite.config.ts`
- Create: `frontend/tsconfig.json`, `frontend/tsconfig.app.json`, `frontend/tsconfig.node.json`
- Create: `frontend/eslint.config.js`
- Create: `frontend/src/main.tsx`, `frontend/src/app/App.tsx`
- Create: `frontend/src/test/setup.ts`, `frontend/src/app/App.test.tsx`

**Interfaces:**
- Produces: scripts `dev`, `lint`, `test`, and `build`; React entry point for
  Task 6; deterministic dependencies through `package-lock.json`.

- [ ] **Step 1: Scaffold the Vite React TypeScript project**

Use the current stable packages resolved on 2026-08-12 and install React Router,
TanStack Query, Ant Design, ECharts, Axios, ESLint, Vitest, jsdom, and React
Testing Library. Preserve exact resolved versions in `package-lock.json`.

- [ ] **Step 2: Add an M0-only application shell**

Render a title, a short “repository foundation” status, and no business pages.

- [ ] **Step 3: Configure lint, test, and build**

`npm test -- --run` must run Vitest once, `npm run lint` must lint TypeScript,
and `npm run build` must execute `tsc -b && vite build`.

- [ ] **Step 4: Verify the bootstrap**

```powershell
Set-Location frontend
npm ci
npm run lint
npm test -- --run
npm run build
Set-Location ..
```

Expected: all four commands exit 0 and `dist/` is ignored.

- [ ] **Step 5: Commit**

```powershell
git add frontend
git commit -m "chore(frontend): bootstrap React application"
```

### Task 3: AIC-004 local infrastructure Compose

**Files:**
- Create: `compose.yaml`
- Create: `compose.dev.yaml`
- Modify: `.env.example`

**Interfaces:**
- Produces: services `mysql`, `redis`, `minio`; network `aicostops`; named
  volumes `mysql-data`, `redis-data`, `minio-data`; environment names consumed by
  backend and Task 8.

- [ ] **Step 1: Define infrastructure services**

Use `mysql:8.4`, a stable Redis image, and the official MinIO image. Add actual
health commands (`mysqladmin ping`, authenticated `redis-cli ping`, and MinIO
health endpoint), restart policies suitable for local development, and named
volumes.

- [ ] **Step 2: Align environment variables**

Ensure `.env.example` contains every Compose interpolation with local-only sample
values and contains no real credential. Keep browser-inaccessible services
internal in `compose.yaml`; place development host ports in `compose.dev.yaml`.

- [ ] **Step 3: Validate resolved Compose configuration**

```powershell
docker compose --env-file .env.example config
docker compose --env-file .env.example -f compose.yaml -f compose.dev.yaml config
```

Expected: both commands exit 0 with no missing-variable warning.

- [ ] **Step 4: Start and inspect infrastructure**

```powershell
docker compose --env-file .env.example -f compose.yaml -f compose.dev.yaml up -d mysql redis minio
docker compose --env-file .env.example -f compose.yaml -f compose.dev.yaml ps
```

Expected: all three services become healthy.

- [ ] **Step 5: Commit**

```powershell
git add compose.yaml compose.dev.yaml .env.example
git commit -m "chore(infra): add local infrastructure compose"
```

### Task 4: AIC-005 backend shared behavior

**Files:**
- Test/Create: `backend/src/test/java/com/aicostops/shared/money/CurrencyCodeTest.java`
- Test/Create: `backend/src/test/java/com/aicostops/shared/money/MoneyTest.java`
- Test/Create: `backend/src/test/java/com/aicostops/shared/json/ApiJsonTest.java`
- Test/Create: `backend/src/test/java/com/aicostops/shared/web/PageRequestTest.java`
- Test/Create: `backend/src/test/java/com/aicostops/shared/web/ProblemDetailAdviceTest.java`
- Create matching production files under `backend/src/main/java/com/aicostops/shared/`

**Interfaces:**
- Produces: `CurrencyCode.of(String)`, `Money.of(BigDecimal, CurrencyCode)`,
  `PageRequest.of(int,int)`, `ProblemCode`, `DomainException`, and a global
  exception mapper returning Spring `ProblemDetail`.

- [ ] **Step 1: RED CurrencyCode and Money tests**

Test uppercase normalization, rejection of null/non-three-letter codes,
scale-insensitive amount equality, currency-sensitive equality, and a mismatch
guard for arithmetic. Run focused tests and confirm compilation/failure because
the types do not exist.

- [ ] **Step 2: GREEN minimal money types**

Implement immutable records/classes with explicit validation and `BigDecimal`
comparison semantics. Re-run focused tests and the full unit suite.

- [ ] **Step 3: RED JSON ID and Money tests**

Use the application `ObjectMapper` to assert a `Long` ID is emitted as
`"9007199254740993"` and a money amount as a decimal string. Confirm failure
before registering serializers/configuration.

- [ ] **Step 4: GREEN JSON configuration**

Add focused Jackson configuration without converting every numeric field to a
string. Re-run tests.

- [ ] **Step 5: RED/GREEN pagination tests**

Assert defaults `(0,50)`, page lower bound 0, size range 1..200, and deterministic
exceptions. Implement only `PageRequest` and a reusable page response shape.

- [ ] **Step 6: RED/GREEN ProblemDetail tests**

With MockMvc, throw a typed `DomainException` and assert content type
`application/problem+json`, HTTP status, stable code, detail, and trace ID.
Implement the exception/advice and a request trace filter only after observing
the failure.

- [ ] **Step 7: Verify and commit**

```powershell
Set-Location backend
.\mvnw.cmd test
Set-Location ..
git add backend
git commit -m "feat(shared): establish backend shared foundation"
```

### Task 5: AIC-006 Flyway and Testcontainers foundation

**Files:**
- Create: `backend/src/main/resources/db/migration/V1__foundation_baseline.sql`
- Create: `backend/src/test/java/com/aicostops/testsupport/MySqlContainerSupport.java`
- Create: `backend/src/test/java/com/aicostops/FoundationMigrationIntegrationTest.java`
- Create/Modify: `backend/src/test/resources/application-test.yml`
- Modify: `backend/pom.xml`

**Interfaces:**
- Produces: reusable dynamic datasource registration and the `integration` Maven
  verification path used by CI.

- [ ] **Step 1: Write the integration test first**

The test starts `MySQLContainer` using a MySQL 8.4 image, registers datasource
properties through `@DynamicPropertySource`, loads the Spring context, and
queries `flyway_schema_history` for successful migration `1`.

- [ ] **Step 2: Run and observe RED**

```powershell
Set-Location backend
.\mvnw.cmd -DskipUnitTests verify
```

Expected: failure because the migration/support configuration is absent.

- [ ] **Step 3: Add the minimal baseline and support**

Create a non-domain foundation marker table with `BIGINT`, UTC timestamp, and
utf8mb4-safe schema conventions. Do not add IAM or financial tables.

- [ ] **Step 4: Run GREEN integration verification**

```powershell
.\mvnw.cmd verify
Set-Location ..
```

Expected: unit and real-MySQL integration tests pass; Docker, not local MySQL,
provides the database.

- [ ] **Step 5: Commit**

```powershell
git add backend
git commit -m "test(db): establish Flyway and Testcontainers foundation"
```

### Task 6: AIC-007 frontend application foundation

**Files:**
- Create tests and matching implementation under `frontend/src/api/`,
  `frontend/src/app/providers/`, `frontend/src/app/router/`,
  `frontend/src/features/auth/`, and `frontend/src/shared/`.
- Modify: `frontend/src/app/App.tsx`, `frontend/src/main.tsx`

**Interfaces:**
- Produces: `accessTokenStore`, configured `apiClient`, `toProblemDetail`,
  `AppProviders`, `PublicRoute`, and `ProtectedRoute`.

- [ ] **Step 1: RED/GREEN token store**

Test initial null state, set/get, clear, and that no browser persistence API is
called. Implement an in-memory module only.

- [ ] **Step 2: RED/GREEN ProblemDetail mapper**

Test full server shape and safe fallback for network/unknown errors. Implement a
typed mapper that branches on Axios response data, not localized detail strings.

- [ ] **Step 3: RED/GREEN Axios client**

Test `/api/v1` base URL, Bearer header injection, a single bounded retry after a
refresh hook, and no retry loop. Use an injectable adapter/hook so tests exercise
real interceptor behavior without a server.

- [ ] **Step 4: RED/GREEN route shells**

Test that a protected route renders an outlet when a token exists and navigates
to the public foundation route when it does not. Do not create a real login UI.

- [ ] **Step 5: Compose application providers**

Add a QueryClient provider and BrowserRouter wiring, then retain only the M0
shell/status routes.

- [ ] **Step 6: Verify and commit**

```powershell
Set-Location frontend
npm run lint
npm test -- --run
npm run build
Set-Location ..
git add frontend
git commit -m "feat(frontend): establish frontend application foundation"
```

### Task 7: AIC-008 GitHub Actions baseline

**Files:**
- Create: `.github/workflows/ci.yml`
- Modify: `backend/pom.xml`
- Create: `backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java`

**Interfaces:**
- Produces stable job/check names consumed later by AIC-009.

- [ ] **Step 1: Add seven explicit jobs**

Use names `backend-unit`, `backend-integration`, `backend-architecture`,
`frontend-lint`, `frontend-test`, `frontend-build`, and `docker-build`. Pin major
versions of official checkout/setup actions and use Java 21 plus Node 24.

- [ ] **Step 2: Make each check execute real work**

Backend unit runs `./mvnw test`; integration runs the Failsafe path with Docker;
architecture runs the architecture-test selector; frontend jobs use `npm ci`;
docker builds both Dockerfiles. Add a minimal ArchUnit rule that asserts
`com.aicostops.shared..` does not depend on `com.aicostops` feature packages;
this makes the architecture job real without inventing M1 modules.

- [ ] **Step 3: Validate YAML and local equivalent commands**

Parse the workflow as YAML and rerun the Maven/npm commands locally.

- [ ] **Step 4: Commit**

```powershell
git add .github/workflows/ci.yml backend
git commit -m "chore(ci): establish GitHub Actions baseline"
```

### Task 8: AIC-010 application images and complete Compose

**Files:**
- Create: `backend/Dockerfile`, `backend/.dockerignore`
- Create: `frontend/Dockerfile`, `frontend/.dockerignore`
- Create: `deploy/nginx/default.conf`
- Modify: `compose.yaml`, `compose.dev.yaml`, `.env.example`

**Interfaces:**
- Produces images/services `backend` and `frontend`; frontend proxies
  `/api/v1/` to `http://backend:8080/api/v1/`.

- [ ] **Step 1: Add multi-stage Dockerfiles**

Backend builds with the committed wrapper on a Java 21 builder and runs on a
Java 21 JRE as a non-root user. Frontend builds with Node 24 and runs via Nginx
as the SPA/proxy image.

- [ ] **Step 2: Add Nginx SPA and API routing**

Use `try_files $uri /index.html` for routes and preserve `/api/v1` when proxying
to backend. Add an HTTP health location or use `/` for Compose health.

- [ ] **Step 3: Wire complete Compose dependencies**

Backend waits for healthy MySQL/Redis/MinIO, receives only environment-backed
configuration, and exposes Actuator health internally. Frontend waits for a
healthy backend. Keep full-stack host exposure limited to the frontend by
default; development overrides may expose backend/infrastructure.

- [ ] **Step 4: Validate and run**

```powershell
docker compose --env-file .env.example config
docker compose --env-file .env.example build
docker compose --env-file .env.example up -d
docker compose --env-file .env.example ps
```

Expected: five services healthy/running, frontend returns HTTP 200, backend
Actuator health returns `UP`, and Flyway is at the baseline.

- [ ] **Step 5: Commit**

```powershell
git add backend frontend deploy compose.yaml compose.dev.yaml .env.example
git commit -m "chore(docker): add application container images"
```

### Task 9: Documentation synchronization and final verification

**Files:**
- Modify: `README.md`, `PROJECT_CONTEXT.md`, `.env.example`
- Modify: relevant files under `docs/02-development/implementation/`
- Modify: relevant files under `docs/03-acceptance/`
- Modify: inaccurate stack descriptions that say `Plain MyBatis 4`

**Interfaces:**
- Produces: reproducible commands and an honest M0 evidence record matching the
  actual implementation.

- [ ] **Step 1: Update only implementation-affected documentation**

Document exact Spring Boot/MyBatis meanings, quick-start commands, service URLs,
M0 status, CI check names, and verification evidence. Preserve frozen business
architecture and make no M1+ completion claim.

- [ ] **Step 2: Run the complete verification matrix**

```powershell
Set-Location backend
.\mvnw.cmd test
.\mvnw.cmd verify
Set-Location ..\frontend
npm ci
npm run lint
npm test -- --run
npm run build
Set-Location ..
docker compose --env-file .env.example config
docker compose --env-file .env.example up -d --build
docker compose --env-file .env.example ps
git diff --check
```

Record exact pass/fail results; diagnose and repair any failure before continuing.

- [ ] **Step 3: Commit documentation**

```powershell
git add README.md PROJECT_CONTEXT.md .env.example docs
git commit -m "docs(project): sync M0 foundation implementation"
```

- [ ] **Step 4: Final repository checks**

```powershell
git status
git diff --check
git log --oneline main..HEAD
```

Expected: clean working tree, no whitespace errors, issue-aligned history.
