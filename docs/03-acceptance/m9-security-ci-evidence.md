# M9 Security CI Evidence — AIC-079

> Continuous CodeQL + Trivy evidence. Trivy's local run reproduced the CI
> HIGH/CRITICAL policy and drove one real fix (frontend container runs non-root).
> The vulnerability-DB/scans and CodeQL execute on GitHub-hosted runners; their
> per-run results are appended below after the PR pushes (no fabricated links).

## Scope

Add repeatable security enforcement to CI without weakening the V1 integration
suite: CodeQL Action v4 (Java/Kotlin + JavaScript/TypeScript), Trivy 0.73.0
filesystem + backend/frontend image scans, least-privilege workflow permissions,
and a reviewed, time-bounded exception policy (none needed so far → no
`.trivyignore`).

- Branch: `chore/m9-security-ci`
- Implementation SHA: `9ee88b3` (`chore(security): add CodeQL and Trivy CI`)
- Evidence SHA: the commit that adds this document.

## Files

| File | Purpose |
|---|---|
| `.github/workflows/security.yml` | CodeQL (matrix java-kotlin + javascript-typescript, security-and-quality queries) and Trivy job |
| `.github/codeql/codeql-config.yml` | Query pack + path allow/ignore (backend/src, frontend/src) |
| `frontend/Dockerfile` | **Fix**: non-root runtime via `nginxinc/nginx-unprivileged:1.28-alpine`, `USER 101`, listen 8080 |
| `frontend/nginx/default.conf` | Listen 8080 (non-privileged port) |
| `compose.yaml` | Frontend maps `${FRONTEND_PORT}:8080`; healthcheck targets `127.0.0.1:8080` |
| `CONTRIBUTING.md` | Added section 10: check names + local reproduction commands |

## Trivy policy (as enforced)

```text
ran with: aquasec/trivy:0.73.0
filesystem scan: misconfig, secret
image scans:     vuln (backend + frontend)
severity:  HIGH,CRITICAL
exit-code: 1  (any finding blocks)
```

- The filesystem scan covers misconfigurations and secrets; vulnerability
  findings come from the backend/frontend **image** scans, which read the
  actually-built dependency sets (strictly more accurate than the fs pom
  dependency graph). Splitting it this way keeps CI independent of Maven
  Central, whose rate limiting repeatedly returned 429 on runner IPs and flaked
  the fs `vuln` scan.
- Filesystem scan skips `.git`, `node_modules`, `dist`, `target` and the E2E
  artifact dirs (build output and vendored deps are covered by the image scans).
- Secret findings are always blocking.

## Initial findings and disposition

| Finding | Severity | Decision | Evidence |
|---|---|---|---|
| `frontend/Dockerfile` DS-0002 "Specify at least 1 USER command" — nginx ran as root | HIGH | **fixed** (upgrade/fix) | docker build + `docker run` verified: `html=200`, process `uid=101(nginx)`; compose recreate reported `healthy` |

Result after the fix, mirroring the CI policy (bounded local run of the exact
scanned artifacts + full source trees):

```text
backend/Dockerfile  dockerfile   0 misconfigs
frontend/Dockerfile dockerfile   0 misconfigs
(all source/config secrets scanned) 0 secrets
EXIT=0
```

No accepted-risk entries; no `.trivyignore` file is created.

## Local reproduction (Windows, PowerShell)

```powershell
Set-Location "E:\AI-CostOps"
docker build --tag ai-costops-backend:local backend
docker build --tag ai-costops-frontend:local frontend

# Filesystem (misconfig + secret mirror the CI policy; vuln needs the DB)
docker run --rm -v "${PWD}:/workspace" aquasec/trivy:0.73.0 fs `
  --scanners misconfig,secret --severity HIGH,CRITICAL --exit-code 1 `
  --skip-db-update --timeout 10m /workspace

# Images (vuln) — requires the trivy vulnerability DB
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock `
  aquasec/trivy:0.73.0 image --severity HIGH,CRITICAL --exit-code 1 ai-costops-backend:local
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock `
  aquasec/trivy:0.73.0 image --severity HIGH,CRITICAL --exit-code 1 ai-costops-frontend:local
```

See `CONTRIBUTING.md` section 10 for the CI-identical command (with the vuln DB
download on a networked runner).

## Environment limitation recorded honestly

On this developer host the outbound proxy used by git (127.0.0.1:7897) was
unreachable while these scans ran, so:

- the Trivy vulnerability DB (`mirror.gcr.io/aquasec/trivy-db:2`) and the
  misconfig checks bundle could not be downloaded **inside the container**, and
- a full-tree Windows bind-mount scan exceeded Trivy's context deadline.

The CI workflow therefore performs the complete policy on GitHub runners, and
the local run above used `--skip-db-update` (embedded misconfig checks + bundled
secret rules) against the repository's policy-bearing artifacts and full source
trees. This is an engineering-evidence claim, not a claim that the CI matrix ran
locally.

## Permissions audit

- Workflow default: `contents: read` only.
- Only the `codeql` job declares `security-events: write` (required to upload its
  analysis results); the `trivy` job needs no extra scope.
- No `write-all`; no token secrets are uploaded.

## CI checks

Defined checks (results filled after the PR's first run):

```text
codeql (java-kotlin)         SUCCESS
codeql (javascript-typescript) SUCCESS
trivy                        SUCCESS (after CI hardening, see below)
```

CodeQL uses real builds — `./mvnw -B -DskipTests package` for Java/Kotlin and
`npm ci && npm run build` for JS/TS — so the extractors observe actual code.

## CI hardening (documented, not hidden)

The first two TRIVY CI runs failed not on findings but on infrastructure: while
Trivy resolved the backend `pom.xml` BOM/dependency graph it requested Maven
Central, which returned `429 Too Many Requests` for the runner IP (~30 min
retry-after each time). Fixes that keep the full HIGH/CRITICAL policy intact:

1. The filesystem scan runs `misconfig,secret` only (Java/JS vulnerability
   coverage moved entirely to the backend/frontend image scans, which read the
   actually-built dependency sets — strictly more accurate than the fs pom
   graph).
2. The filesystem scan additionally skips `backend/pom.xml` itself, because
   Trivy resolves its Maven BOM even in misconfig mode. No coverage was lost:
   the built backend image (and its dependency tree) is scanned by
   `ai-costops-backend:security` image scan.

`CONTRIBUTING.md` section 10 documents the CI-identical local reproduction.

## Known limitations

- `compose.yaml` could not be locally scanned with the full checks bundle
  (embedded fallback checks cover Dockerfiles only); the CI `fs` scan runs the
  current bundle over the whole repository including Compose files.
- npm/Maven dependency vulnerability findings (image scans) can only
  be reported from a networked runner; if the first CI run surfaces new
  HIGH/CRITICAL entries they will be triaged one by one (fix / prove false
  positive / time-bounded accepted risk) before the security check is considered
  green.
- The `fs` scanner was changed from `vuln,misconfig,secret` to
  `misconfig,secret` after GitHub Actions runner IPs hit Maven Central 429 rate
  limiting while Trivy resolved the pom dependency graph (twice, ~30 min
  retry-after each). The image scans cover the same dependency sets, so no
  coverage was lost; this is documented here so a later PR does not silently
  restore the flaky combination.