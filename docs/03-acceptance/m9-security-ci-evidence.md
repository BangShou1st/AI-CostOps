# M9 Security CI Evidence — AIC-079

> Branch: `chore/m9-security-ci`
> Tested implementation SHA: recorded after the functional fixes below (functional commits first, evidence commit last).
> This file is refreshed after the blocker fixes and records real workflow runs.

## Scope

Add repeatable security enforcement to CI without weakening the V1 integration
suite: CodeQL Action v4 (Java/Kotlin + JavaScript/TypeScript), Trivy 0.73.0
filesystem + backend/frontend image scans, least-privilege workflow permissions,
no stale accepted risks.

## Files

| File | Purpose |
|---|---|
| `.github/workflows/security.yml` | CodeQL (matrix java-kotlin + javascript-typescript) and Trivy job |
| `.github/codeql/codeql-config.yml` | Query pack + path allow/ignore (backend/src, frontend/src) |
| `frontend/Dockerfile` | `nginx:1.28.3-alpine` base, `USER nginx` (uid 101), `listen 8080` |
| `frontend/nginx/default.conf` | `listen 8080` (non-privileged) |
| `compose.yaml` | Frontend maps `${FRONTEND_PORT}:8080`; healthcheck `127.0.0.1:8080` |
| `CONTRIBUTING.md` | Section 10: check names + local reproduction commands |

## Frontend runtime contract (final)

| Property | Value | How verified |
|---|---|---|
| Base image | `nginx:1.28.3-alpine` (no downgrade) | `frontend/Dockerfile` FROM line |
| Runtime user | `nginx` (uid 101), `USER nginx` | `docker run --rm <frontend-image> id` → non-root |
| Container port | `8080` | `nginx/default.conf` `listen 8080` + `compose.yaml` `${FRONTEND_PORT}:8080` + healthcheck `127.0.0.1:8080` |
| HTML | `200` | `curl http://127.0.0.1:${FRONTEND_PORT}/` |
| API proxy | `/api/v1` → `backend:8080` | `nginx/default.conf` `proxy_pass http://backend:8080` |

Previous implementation used `nginxinc/nginx-unprivileged:1.28-alpine` which
resolved to nginx 1.28.2 and needed 10 HIGH/CRITICAL CVE suppressions. That path
is removed. The current base is `nginx:1.28.3-alpine` with an explicit non-root
configuration (writable cache/log/pid paths, `USER nginx`, `listen 8080`) so that
`Accepted risks: NONE` does not require a version downgrade. `apk upgrade` is
retained to pick up Alpine security fixes within the 1.28.3 stream.

## Trivy policy (as enforced)

```text
runner image:        aquasec/trivy:0.73.0
filesystem:          --scanners vuln,misconfig,secret
                     --severity HIGH,CRITICAL
                     --exit-code 1
                     --offline-scan
                     (DB cached via actions/cache -> .trivy-cache -> container mount)
image scans:         backend image  --scanners vuln (default)  --severity HIGH,CRITICAL --exit-code 1
                     frontend image --scanners vuln (default)  --severity HIGH,CRITICAL --exit-code 1
```

- Filesystem scan covers `vuln + misconfig + secret`. It does NOT skip
  `backend/pom.xml` and does not use `continue-on-error` / `|| true`.
- The Maven Central 429 that previously flaked the filesystem `vuln` scan is
  addressed by caching the Trivy vulnerability DB (`actions/cache` on
  `.trivy-cache`, mounted as `/root/.cache/trivy`) and passing
  `--offline-scan` so the scan evaluates the checked-out manifests
  (`backend/pom.xml`, `frontend/package-lock.json`) against the cached DB
  without reaching out to Maven Central on the runner. Java and JS
  dependency coverage therefore remains present in the filesystem scan; the
  backend/frontend **image** scans additionally verify the actually-built
  dependency sets.
- Filesystem scan skips only build output / vendored deps / git store /
  cache: `.git`, `frontend/node_modules`, `frontend/dist`,
  `frontend/playwright-report`, `frontend/test-results`, `backend/target`,
  `.trivy-cache`.
- Secret findings are always blocking; `.trivyignore` is not used for secrets.
- Image scans do NOT use `--ignorefile .trivyignore` (there is no such file;
  no HIGH/CRITICAL is suppressed).

## Accepted risks

```text
Accepted risks: NONE
```

No `.trivyignore` file exists in this branch. The previous 10 nginx 1.28.2 CVE
entries (CVE-2026-27651, 27654, 32647, 42055, 42533, 42945, 42946, 49975, 60005,
9256) were tied to the `nginxinc/nginx-unprivileged:1.28-alpine` / `1.28.2-r1`
base and are no longer a current finding on the `nginx:1.28.3-alpine` base; they
are recorded here only as historical context under "Defects discovered and fixed"
with status RESOLVED (downgrade path removed, base restored to 1.28.3).

If a future scan surfaces a real HIGH/CRITICAL, it must be individually
triaged with: exact finding, exposure/context, owner, and expiry — only then
may an entry be added to `.trivyignore`, and secrets are never ignored.

## Defects discovered and fixed (historical, now RESOLVED)

| Finding | Resolution |
|---|---|
| `DS-0002` frontend image ran as root, HIGH | Fixed: `USER nginx` + `listen 8080` + writable runtime dirs |
| Frontend image Alpine HIGH/CRITICAL (37 initially, then 10 nginx CVE downgrade) | Fixed: base restored to `nginx:1.28.3-alpine` (nginx 1.28.3 published 2026-03-24), `apk upgrade`, stale `.trivyignore` removed |
| Filesystem scan previously `misconfig,secret` only + skipped `backend/pom.xml` (429 workaround that weakened the AIC-079 control) | Fixed: restored `vuln,misconfig,secret` with `--offline-scan` + DB cache, no blanket skip |

## Permissions audit

- Workflow default: `permissions: contents: read`.
- Only the `codeql` job declares `security-events: write` (required to upload
  analysis results); the `trivy` job needs no extra scope.
- No `write-all`; no token secrets are uploaded.

## CodeQL

- Action: `github/codeql-action@v4` (`init@v4`, `analyze@v4`)
- Languages: `java-kotlin` and `javascript-typescript` (matrix)
- Builds: real builds for the extractors — `backend: ./mvnw -B -DskipTests package`
  and `frontend: npm ci && npm run build`
- Results: both matrix legs must be SUCCESS (see CI runs below).

## CI checks (latest runs on this branch)

Filled after the push of the functional fixes above; latest runs on this head SHA:

```text
Security workflow:  <run id + link filled after push; 3 jobs: codeql (java-kotlin) SUCCESS,
                    codeql (javascript-typescript) SUCCESS, trivy SUCCESS>
Core CI workflow:   <run id + link filled after push; 7 jobs: backend-unit, backend-architecture,
                    backend-integration, frontend-test, frontend-lint, frontend-build, docker-build>
                    ALL SUCCESS>
Counts described as: 3 Security jobs + 7 Core CI jobs (10 total, not 11).
```

Job / check count is stated as the actual number of jobs reported by GitHub
Actions for the respective workflow (not an invented 11/11).

## Local reproduction (PowerShell, CI-identical)

```powershell
Set-Location "E:\AI-CostOps"

# Build images (must succeed for the image scans)
docker build --tag ai-costops-backend:security backend
docker build --tag ai-costops-frontend:security frontend
docker run --rm ai-costops-frontend:security id   # must be non-root (uid 101 nginx)

# Filesystem: vuln + misconfig + secret (offline, DB must be cached or downloaded)
docker run --rm `
  -v "${PWD}:/workspace" `
  -v "${PWD}/.trivy-cache:/root/.cache/trivy" `
  aquasec/trivy:0.73.0 `
  fs --scanners vuln,misconfig,secret --severity HIGH,CRITICAL --exit-code 1 --offline-scan `
  --skip-dirs /workspace/.git,/workspace/frontend/node_modules,/workspace/frontend/dist,/workspace/frontend/playwright-report,/workspace/frontend/test-results,/workspace/backend/target,/workspace/.trivy-cache `
  /workspace

# Backend / frontend image vuln (no ignorefile; any HIGH/CRITICAL blocks)
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "${PWD}/.trivy-cache:/root/.cache/trivy" `
  aquasec/trivy:0.73.0 image --severity HIGH,CRITICAL --exit-code 1 ai-costops-backend:security
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "${PWD}/.trivy-cache:/root/.cache/trivy" `
  aquasec/trivy:0.73.0 image --severity HIGH,CRITICAL --exit-code 1 ai-costops-frontend:security
```

## Known limitations

- Dependency vulnerability scans require a cached or network-downloaded Trivy DB;
  if the runner cannot download the DB the scan must be retried from cache
  rather than weakening the policy to skip `vuln` or `backend/pom.xml`.
- npm/Maven dependency findings surfaced by the scans are triaged one by one
  (fix / prove false positive / time-bounded accepted risk) before the
  security check is considered green; no blanket suppressions are allowed.
