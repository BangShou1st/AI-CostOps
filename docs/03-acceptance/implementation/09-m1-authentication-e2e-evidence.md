# M1 Authentication E2E Implementation Evidence

Date: 2026-08-13 (Asia/Shanghai)

Branch: `feat/m1-authentication-e2e`

## Scope

Covers AIC-011/#16, AIC-012/#17, AIC-013/#18, AIC-014/#19,
AIC-015/#20, AIC-016/#21, and AIC-019/#24.

Does not cover AIC-017/#22, AIC-018/#23, or AIC-020/#25.

## Verification evidence

All results below were observed locally from the repository root or the named
module on 2026-08-13. GitHub Actions was not invoked.

| Area | Command | Observed result |
|---|---|---|
| Backend unit | `cd backend; .\mvnw.cmd -B "-DexcludedGroups=architecture,integration" test` | PASS — 33 tests, 0 failures, 0 errors, 0 skipped |
| Architecture | `cd backend; .\mvnw.cmd -B -Dgroups=architecture test` | PASS — 1 test, 0 failures, 0 errors, 0 skipped |
| Backend integration | `cd backend; .\mvnw.cmd -B -Dgroups=integration verify` | PASS — 34 tests, 0 failures, 0 errors, 0 skipped; MySQL 8.4 and Redis 8.8.1 Testcontainers |
| Frontend install | `cd frontend; npm ci` | PASS — 294 packages installed |
| Frontend lint | `cd frontend; npm run lint` | PASS |
| Frontend tests | `cd frontend; npm test -- --run` | PASS — 6 files, 13 tests |
| Frontend build | `cd frontend; npm run build` | PASS — TypeScript and Vite production bundle |
| OpenAPI parse | Python `yaml.safe_load` of `docs/02-development/api/openapi.yaml` | PASS — OpenAPI 3.1.0, all nine Authentication paths present |
| Compose config | `docker compose --env-file .env.example config --quiet` | PASS |
| Docker images | `docker compose --env-file .env.example build` | PASS — backend and frontend images built |
| Clean startup | `docker compose --env-file .env.example down --volumes --remove-orphans`; `docker compose --env-file .env.example up -d` | PASS — clean MySQL, Redis, and MinIO volumes created |
| Compose health | `docker compose --env-file .env.example ps` | PASS — mysql, redis, minio, backend, and frontend all healthy |
| HTTP | `(Invoke-WebRequest http://localhost:8080 -UseBasicParsing).StatusCode` | PASS — 200 |
| Auth acceptance | `.\scripts\auth-smoke.ps1 -BaseUrl http://localhost:8080/api/v1 -EnvFile .env.example` | PASS — `AUTH_SMOKE_PASS` |
| Whitespace | `git diff --check` | PASS |

The Auth acceptance run used a clean Compose database and Redis and proved:

- the `local-dev` organization exists only under the explicit `dev` profile;
- Local Development Registration creates the identity, credential, membership,
  and `EMPLOYEE` assignment;
- login returns a short-lived JWT and an HttpOnly refresh cookie;
- `/auth/me` accepts the JWT;
- refresh rotates credentials;
- an obsolete refresh credential is rejected as `AUTH_REFRESH_REPLAY`;
- logout revokes the current refresh session;
- forgot password returns the generic acceptance shape;
- a test-only reset challenge is consumed once;
- password reset changes the credential and invalidates the old JWT/session;
- the new password can authenticate.

## RED/GREEN and fault evidence

- Login API initially failed with 403 before the endpoint and cookie contract
  were implemented; the focused test then passed (2 tests).
- Refresh/logout lifecycle initially failed with 403 before bearer security and
  lifecycle endpoints were implemented; the focused test then passed (2 tests).
- Password reset initially failed compilation because the delivery boundary did
  not exist; the completed focused suite passed (4 tests).
- The full integration run initially exposed an invalid fixture cleanup order:
  `audit_event` still referenced `app_user`. The fixture was corrected to delete
  audit rows first and the complete 34-test integration suite was rerun and passed.
- Docker initially exposed CRLF in the Windows Maven wrapper. The Docker build
  now normalizes the wrapper line endings and the complete image build passed.
- The smoke script was rerun from its first step after PowerShell compatibility
  fixes; the final complete run passed.

## Known warnings and limitations

- Maven emits the existing Mockito/Byte Buddy dynamic-agent warning on Java 21.
  It did not fail any test.
- Docker CLI warns when MySQL/Redis passwords are supplied to their command-line
  clients during the local acceptance script. Values come from the local example
  env file and are not logged by the application.
- `PasswordResetDelivery` is a secret-safe no-op production boundary in M1;
  SMTP/provider delivery is not configured. Tests inject a delivery spy and the
  smoke test injects a Redis challenge directly.
- Cross-tab coordination is intentionally not implemented; refresh is
  single-flight within one browser tab, as scoped.
- Not yet verified on real GitHub PR CI.
