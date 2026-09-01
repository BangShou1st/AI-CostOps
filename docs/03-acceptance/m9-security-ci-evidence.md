# M9 Security CI Evidence — AIC-079

> Branch: `chore/m9-security-ci`
> Tested implementation SHA: `e730bd32ca05b9c168702259bf6a35d84bea7dc8`
> NGINX base: `nginx:1.30.4-alpine`
> Runtime: uid 101 (`nginx`) / non-root, container port 8080

## Scope

Add repeatable security enforcement to CI without weakening the V1 integration suite:
CodeQL Action v4 (Java/Kotlin + JavaScript/TypeScript), Trivy 0.73.0 filesystem +
backend/frontend image scans, least-privilege workflow permissions, and no stale
accepted risks.

## Final frontend runtime contract

| Property | Final value | Verification |
|---|---|---|
| Base image | `nginx:1.30.4-alpine` | `frontend/Dockerfile` |
| Runtime user | `nginx` / uid 101 | `USER nginx`; local `docker run ... id` |
| Container port | `8080` | nginx `listen 8080`, Dockerfile `EXPOSE 8080`, Compose `${FRONTEND_PORT}:8080` |
| Healthcheck | `http://127.0.0.1:8080/` | local isolated runtime PASS |
| API proxy | `/api/v1` -> `backend:8080` | `frontend/nginx/default.conf` |

The prior 1.28.x accepted-risk path is superseded. This PR pins
`nginx:1.30.4-alpine`; the final frontend image scan reports zero HIGH/CRITICAL
findings without an ignore file.

## Trivy policy as enforced

```text
runner image:        aquasec/trivy:0.73.0
filesystem:          --scanners vuln,misconfig,secret
                     --severity HIGH,CRITICAL
                     --exit-code 1
                     --offline-scan
image scans:         backend image  --severity HIGH,CRITICAL --exit-code 1
                     frontend image --severity HIGH,CRITICAL --exit-code 1
accepted-risk file:  NONE
```

The filesystem scan does not skip `backend/pom.xml` and does not use
`continue-on-error` / `|| true`. Trivy DB state is cached in `.trivy-cache` and
mounted into the Trivy container; `--offline-scan` removes the Maven Central 429
runtime dependency without removing Java/JS dependency-manifest coverage.

Skipped directories are build output, vendored dependencies, VCS/cache state and
local drill artifacts only: `.git`, `frontend/node_modules`, `frontend/dist`,
`frontend/playwright-report`, `frontend/test-results`, `backend/target`,
`.trivy-cache`, `.local-backups`, `.local-restore-drill`, and
`frontend/.trivy-cache`.

### Local scan results on tested implementation `e730bd3`

```text
Trivy 0.73.0
filesystem vuln       PASS  (backend/pom.xml 0, frontend/package-lock.json 0)
filesystem misconfig  PASS  (backend/Dockerfile 0, frontend/Dockerfile 0)
filesystem secret     PASS  (0 secrets)
backend image vuln    PASS
frontend image vuln   PASS  (alpine 3.24.1, nginx 1.30.4-r1, 0 HIGH/CRITICAL)
```

## Accepted risks

```text
Accepted risks: NONE
```

`.trivyignore` is deleted and the workflow passes no `--ignorefile` argument.
The previous nginx findings are resolved on the final 1.30.4 image:

- CVE-2026-42055 — RESOLVED
- CVE-2026-42533 — RESOLVED
- CVE-2026-49975 — RESOLVED
- CVE-2026-60005 — RESOLVED
- CVE-2026-9256 — RESOLVED

Secrets are never suppressible. Any future HIGH/CRITICAL exception must be an
individual, reviewed, time-bounded record with finding, exposure/context, owner
and expiry.

## Permissions and CodeQL

- Workflow default: `permissions: contents: read`.
- Only the CodeQL job adds `security-events: write`.
- No `write-all` and no secret-upload path.
- `github/codeql-action@v4` runs a matrix for `java-kotlin` and
  `javascript-typescript`.
- Extractors observe real builds: backend `./mvnw -B -DskipTests package`,
  frontend `npm ci && npm run build`.

## Authoritative GitHub Actions evidence

Both workflows below ran on PR head
`0881cb3a5a6a26e64e94a9f7f632aeb7c27a5224`, which already contains the merge of
`main@63e781aafd4dd04b631c706a4b168dc7dcecb47d` (AIC-078 / PR #120).

### Security workflow

- Run ID: `33487158213`
- Workflow run number: `9`
- Result: **SUCCESS**
- Jobs: **3/3 SUCCESS**

| Job | Result |
|---|---|
| `codeql (java-kotlin)` | SUCCESS |
| `codeql (javascript-typescript)` | SUCCESS |
| `trivy` | SUCCESS |

The `trivy` job completed these enforcement steps successfully:

- Trivy filesystem scan (`vuln, misconfig, secret`)
- Trivy backend image scan (`vuln`)
- Trivy frontend image scan (`vuln`)

### Core CI workflow

- Run ID: `33487158178`
- Workflow run number: `182`
- Result: **SUCCESS**
- Required core jobs: **7/7 SUCCESS**
- Additional browser E2E job: **SUCCESS**

| Job | Result |
|---|---|
| `backend-unit` | SUCCESS |
| `backend-architecture` | SUCCESS |
| `backend-integration` | SUCCESS |
| `frontend-build` | SUCCESS |
| `frontend-lint` | SUCCESS |
| `frontend-test` | SUCCESS |
| `docker-build` | SUCCESS |
| `browser-e2e` | SUCCESS |

The repository ruleset requires the seven core checks above with strict latest-base
status-check policy. They were all successful on the recorded head.

## Defects found and resolved during AIC-079

| Finding | Resolution | Status |
|---|---|---|
| Frontend runtime was root | `USER nginx`, writable runtime dirs, non-privileged port 8080 | RESOLVED |
| Temporary downgrade path exposed nginx 1.28.2 findings | Returned to official nginx image and then moved to patched 1.30.4 | RESOLVED |
| nginx 1.28.3-r1 retained five HIGH findings | Moved final base to `nginx:1.30.4-alpine`; zero HIGH/CRITICAL without ignore file | RESOLVED |
| Filesystem scan had dropped `vuln` / skipped `backend/pom.xml` to avoid 429 | Restored `vuln,misconfig,secret`; cached DB + `--offline-scan`; no blanket skip | RESOLVED |

## Local reproduction

```powershell
Set-Location "E:\AI-CostOps"

docker build --tag ai-costops-backend:security backend
docker build --tag ai-costops-frontend:security frontend
docker run --rm ai-costops-frontend:security id
docker run --rm ai-costops-frontend:security nginx -v

# Filesystem: vuln + misconfig + secret
docker run --rm `
  -v "${PWD}:/workspace" `
  -v "${PWD}/.trivy-cache:/root/.cache/trivy" `
  aquasec/trivy:0.73.0 `
  fs --scanners vuln,misconfig,secret --severity HIGH,CRITICAL --exit-code 1 --offline-scan `
  --skip-dirs /workspace/.git,/workspace/frontend/node_modules,/workspace/frontend/dist,/workspace/frontend/playwright-report,/workspace/frontend/test-results,/workspace/backend/target,/workspace/.trivy-cache,/workspace/.local-backups,/workspace/.local-restore-drill,/workspace/frontend/.trivy-cache `
  /workspace

# Image scans: no ignore file; HIGH/CRITICAL block
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "${PWD}/.trivy-cache:/root/.cache/trivy" `
  aquasec/trivy:0.73.0 image --severity HIGH,CRITICAL --exit-code 1 ai-costops-backend:security
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "${PWD}/.trivy-cache:/root/.cache/trivy" `
  aquasec/trivy:0.73.0 image --severity HIGH,CRITICAL --exit-code 1 ai-costops-frontend:security
```

## Known limitation

Dependency vulnerability scans require a cached or network-downloaded Trivy DB.
If that DB cannot be obtained, the correct response is retry/cache repair — not
weakening the policy by dropping `vuln`, skipping `backend/pom.xml`, or adding a
blanket ignore.
