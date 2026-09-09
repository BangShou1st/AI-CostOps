# M16 Browser UAT — Runbook & Round-2 Runtime Fixture

**Status:** runtime + fixture notes for Browser UAT Round 2 (F01–F07 still
BLOCKED until the browser reviewer runs them). This document records only
runtime/environment facts and the R2 fixture plan; it never records a Browser
PASS.

## 1. Browser-UAT acceptance runtime (P1-C fix, runtime-only)

Browser UAT runs against the isolated M16 acceptance stack (`m16accept`
project, backend `:18080`, frontend `:18082` via nginx, gateway `:18081`,
mock provider `:18089`, Prometheus `:19090`).

### Root cause (deterministically reproduced, no product auth defect)

`POST /api/v1/auth/refresh` and `POST /api/v1/auth/logout` enforce
`AuthController.validateOrigin` (403 `FORBIDDEN` "Origin rejected"). The
acceptance backend container ran with the Spring default
`allowed-origins=http://localhost:8080`, so every Browser request that carried
`Origin: http://127.0.0.1:18082` was rejected:

- refresh 403 → reload/bootstrap cannot restore the session;
- logout 403 → the refresh cookie is never cleared server-side → next reload
  re-authenticates → logout appears broken.

Live evidence on the acceptance stack (round 2 container):

| Call | Origin | Result |
| --- | --- | --- |
| refresh | `http://127.0.0.1:18082` | before fix 403 / after fix 200 |
| refresh | `http://localhost:8080` (allowed) | 200 (control) |
| logout | `http://127.0.0.1:18082` | before fix 403 / after fix 204 |
| refresh | `https://evil.example` | 403 (unallowed origins still rejected) |
| login | — | 200 (login never validates origin) |

H2 (Secure cookie over plain-HTTP loopback) was not the operative failure in
Chrome/127.0.0.1; H1 alone explains both symptoms.

### Runtime-only config (production defaults untouched)

- Product code: **no auth change**.
- `application.yml` default `refresh-cookie-secure: true` and prod
  `application-prod.yml` unchanged; `ProductionConfigurationValidator`
  unchanged.
- The isolated acceptance backend container overrides two values at runtime:
  - `AICOSTOPS_ALLOWED_ORIGINS=http://localhost:8080,http://127.0.0.1:18082,http://localhost:18082`
  - `AICOSTOPS_REFRESH_COOKIE_SECURE=false` (loopback HTTP boundary only)
- Local fixture file (gitignored): `.env.m16-browser-uat` carries the same two
  overrides so the backend compose/docker-run startup stays consistent.
- Automated regressions for the auth UX contract already exist and are run in
  the machine gates: `frontend/e2e/auth-session.spec.ts` (login → reload →
  session restored; logout → anonymous; anonymous → protected redirect;
  expired cookie → login) plus unit suites `authSession.test.ts` (bootstrap,
  refresh-race, logout cookie lock), `AuthSessionProvider.test.tsx`, and
  `crossTabAuthCoordinator.test.ts`.

## 2. R2 organization fixture

Round-1 org 116 (period #111, now CLOSED) is first-round Browser evidence and
**must not be modified**. Round 2 uses a fresh org seeded by the gitignored
local fixture `/.m16r2fixture.sql` (created AFTER this round's product commits;
it stays OFF the tracked tree, like the other `.m16*` runtime files):

- org slug `m16-uat-browser-r2-<ts>`;
- one **OPEN** billing period (`[now-7d, now+90d)`);
- one org-scope ACTIVE budget (`total = 100.00000000` USD) — deliberate
  constraint for UAT-03 exhaustion;
- one provider account whose `provider_code` is read from the ACTIVE
  `provider_catalog` (round 1 seeded org 116 with a **non-catalog code
  `MOCK`**, which is the root cause of the routing eligibility RED; R2 must
  never repeat that);

And then, still **before** the reviewer:

- users `m16-r2-admin / m16-r2-finance / m16-r2-viewer / m16-r2-unauthorized`
  are provisioned via the registration API and assigned org roles
  (admin `SYSTEM_ADMIN` or `PROVIDER_ACCOUNT_MANAGE`+`AUDIT_READ`,
  finance `FINANCE_ADMIN`+reconciliation perms, viewer read-only, unauthorized
  none) — no tracked credentials in the repo.

UAT-01 remains reviewer-driven: the reviewer creates project, budget, service
identity, gateway credential, pricing version, routing policy themselves
(F02/F03 must NOT be pre-done by the fixture).

The round-2 session (or reviewer) can now also use the new governed pages
through the strengthened stack:
`/settings/service-identities`, `/settings/gateway-credentials`,
`/settings/model-pricing` (all require `PROVIDER_ACCOUNT_READ` to view and
`PROVIDER_ACCOUNT_MANAGE` to mutate; server-side enforced).

## 3. F05 deterministic setup

Create the synthetic Provider statement **from the round's own gateway usage**
so reconciliation input always has data (never "0 difference" by accident):

1. Run 3 synthetic Gateway requests through the R2 credential:
   - one request whose usage will fully match the statement line → **exact**;
   - one request whose usage is later corrected in the statement → **difference**;
   - one statement line referencing an idempotency key / amount with no
     matching gateway usage → **unresolved**.
2. Export the statement fixture (gitignored) at `/.m16r2-provider-statement.csv`
   with the three classes; the reviewer imports it via the existing evidence
   import UI, then runs reconciliation, resolves, reruns, and exercises the
   CLOSED-period append-only correction.

The fixture file is a starter; only the live round's actual usage records make
the three classes deterministic.

## 4. Governed Control Plane surface added this round (P1-B)

Backend (all org-scoped, server-side auth, audit, cross-org safe):

- `GET/POST /api/v1/service-identities`, `GET /{id}`
- `GET/POST /api/v1/gateway-credentials`, `GET /{id}`,
  `POST /{id}/revoke` (raw key in `POST /` response exactly once)
- `GET /api/v1/model-catalog`, `GET /api/v1/provider-models`,
  `GET/POST /api/v1/pricing-versions`, `POST /{id}/activate`

Permissions reuse `PROVIDER_ACCOUNT_READ` / `PROVIDER_ACCOUNT_MANAGE`
(demonstrated sufficient; no new permission seed, therefore no migration).

Frontend: three settings pages behind `PermissionRoute` + `SETTINGS_NAV`
entries, with one-time raw-key presentation and revoke confirmation.

## 5. Deterministic Browser startup (Harness GREEN, R3-ready)

Single entry point (harness-only, idempotent, no product change):

- ./scripts/m16-browser-uat-start.ps1
- optional -PublicRegistrationOrgSlug pins the isolated backend
  registration org (R3 provisioning through the governed registration API);
  -Rebuild forces image rebuilds.

What it guarantees on the durable m16-accept-net network:

- images ai-costops-backend/frontend/gateway:m16 carry label
  m16.tree=<exact HEAD>; label mismatch triggers a tracked-tree rebuild
  (timestamps are not a freshness signal);
- stateless containers are recreated only on image/label/env change, reusing
  their runtime env verbatim (durable MySQL/Redis volumes never touched,
  no Redis key is ever deleted);
- the backend container always carries the stable Docker DNS alias backend,
  which the frontend nginx upstream (http://backend:8080) requires — this
  was the proven R2 RED (frontend could not resolve backend, reviewer used
  a forbidden hardcoded 172.x IP);
- Browser runtime keeps AICOSTOPS_ALLOWED_ORIGINS with both 18082 origins
  and AICOSTOPS_REFRESH_COOKIE_SECURE=false (loopback only); production
  defaults, validateOrigin, SameSite/HttpOnly semantics unchanged.

Reviewer discipline: use http://127.0.0.1:18082 exclusively for the whole
session (refresh cookie is host-scoped; do not alternate with localhost).

## 6. R3 fixture record (pre-R3 preconditions only)

- R3 org m16-uat-browser-r3-20260909022652 (id 143) + OPEN period 138,
  org-scope ACTIVE budget 100.00000000 USD (UAT-03 exhaustion setup),
  catalog-compatible provider account (M16MOCK, ACTIVE);
- other org m16-uat-browser-r3-other-20260909022652 (id 144) + OPEN period
  139 for cross-org isolation checks;
- users provisioned via the governed registration API into the R3 org:
  ADMIN (SYSTEM_ADMIN), FINANCE (FINANCE_ADMIN), VIEWER (FINANCE_REVIEWER),
  UNAUTHORIZED (EMPLOYEE only); credentials live only in the git-excluded
  local .m16r3-browser-uat.env, never in the report;
- F05 starter .m16r3-provider-statement.csv is a placeholder template only;
  exact/difference/unresolved rows must derive from R3 live gateway usage.
- NOT pre-created (Browser R3 does them): Project, F01 Budget, Service
  Identity, Gateway Credential, Pricing Version, Routing Policy.
- Round-1 org 116 / period 111 and the R2 org are untouched; no Redis key
  was deleted for R3.
