# M11 Local AI Execution Guardrails

> **MANDATORY EXECUTION OVERRIDE.** The local AI must read this file before `2026-09-02-m11-gateway-edge-mvp-final-plan.md`. These guardrails override the final plan only for local execution cadence, Docker usage, disk-safety, and what the agent must automate itself. M10 architecture/business invariants remain higher authority.

## 1. Agent owns the whole M11 workflow

The local AI is responsible for the complete milestone without handing routine setup or testing back to the human:

```text
sync/check branch
→ inspect frozen M10 docs
→ implement Tasks 1..10
→ generate local-only synthetic dev secrets when safe
→ start/reuse required dev infrastructure
→ run focused tests
→ run final full verification
→ produce sanitized acceptance evidence
→ push feature branch
→ open one final PR
```

Do not ask the human to manually create ordinary development keys, edit routine config, start MySQL/Redis, run Maven tests, build artifacts, write evidence, push commits, or create the PR.

The only unavoidable external input is a **real MiMo API key** for real-provider smoke. If `AICOSTOPS_MIMO_API_KEY` is not already available in the environment, do not invent one and do not block the rest of M11. Complete all mock/local/CI work, mark only the real-provider smoke as `BLOCKED: missing external MiMo credential`, and never report it as PASS.

Local synthetic secrets such as HMAC keys, KEK and dev Gateway keys may be generated automatically with cryptographically secure randomness and stored only in a git-ignored local environment file or process environment. Never commit them.

## 2. HARD DISK-SAFETY RULE: do not repeatedly build Docker images

The previous development pattern of repeatedly running full Docker builds is forbidden.

### Tasks 1 through 8

Normal implementation/test loop uses:

```text
Backend:  backend\mvnw.cmd ...
Gateway:  gateway\mvnw.cmd ...
Frontend: npm commands only if frontend is actually touched
```

**Do not run any of the following during routine Task 1-8 iterations:**

```text
docker compose up --build
docker compose build
docker build backend
docker build frontend
docker build gateway
full Compose rebuilds after every code change
```

A failing Java/unit/integration test is fixed with Maven/test commands first. Docker is not a generic retry mechanism.

## 3. Reuse dev infrastructure instead of rebuilding application containers

For local development, follow the repository's established native-app pattern:

```text
Backend runs natively
Gateway runs natively
MySQL / Redis / MinIO come from the existing dev infrastructure only when required
```

Start infrastructure without application image rebuilds. Prefer the existing `compose.dev.yaml` services and reuse the same Compose project for the whole M11 session.

Do not repeatedly `down`/`up` the same infrastructure between tests unless a test explicitly requires a clean external state.

If a fresh database is required for correctness tests, prefer the test's own isolated Testcontainers lifecycle or a dedicated temporary database/schema. Do not rebuild Backend/Gateway images merely to obtain a clean DB.

## 4. Testcontainers policy

Testcontainers is allowed where the final plan requires real MySQL/Redis behavior, but use it deliberately:

- run the focused integration test for the current task while developing;
- do not run the entire integration suite after every small edit;
- run the complete Backend/Gateway integration suites once at the final acceptance gate, plus again only when a later fix materially affects them;
- Testcontainers must clean up its containers normally;
- do not disable Ryuk/cleanup merely to save startup time;
- do not create custom Docker images for ordinary Testcontainers tests when official MySQL/Redis images are sufficient.

Persistent pulled base images are acceptable; repeated custom image builds are not.

## 5. Local Docker build budget

Local Docker image construction is a **final validation gate**, not a development loop.

### Gateway

Build the Gateway image locally only after the Gateway Dockerfile and application are functionally complete (Task 9 / AIC-101).

Target budget:

```text
1 successful local Gateway image build for final Dockerfile verification
```

A second build is allowed only if the first build itself exposes a Dockerfile/packaging defect and the Dockerfile or packaging is then changed.

### Backend

M11 modifies Backend code, but ordinary verification remains Maven-native. Do **not** rebuild the Backend Docker image locally during each Task.

Only build Backend locally if a final local smoke path truly requires its image. Prefer running Backend natively instead. GitHub Actions is the authoritative remote Docker build gate.

### Frontend

M11 should not require frontend changes. Do not locally rebuild the frontend Docker image unless M11 actually changes frontend container/runtime files. Existing frontend lint/test/build commands are enough otherwise.

## 6. Prefer GitHub Actions for full image matrix

CI/Security may build Backend/Frontend/Gateway images on GitHub-hosted runners. That remote work does not consume the user's local Docker disk and should be preferred for repeated full-image verification.

Local acceptance should prove only what cannot reasonably be delegated to CI:

```text
native Backend/Gateway behavior
mock-provider smoke
one final Gateway Dockerfile build
optional real MiMo smoke
```

Do not duplicate every CI image build locally "for safety".

## 7. Before and after the one local Docker build

Before the final local Docker image build:

```powershell
docker system df
```

Use a unique disposable tag, for example:

```text
ai-costops-gateway:m11-local-check
```

After the build has been verified and evidence recorded, remove **only that disposable M11 verification image** if it is no longer needed:

```powershell
docker image rm ai-costops-gateway:m11-local-check
```

If the image is referenced by an active container, stop/remove only that M11 test container first. Do not touch unrelated project/user images.

Run `docker system df` again and record only the aggregate numbers; do not dump sensitive environment/config values into evidence.

## 8. Forbidden destructive/global Docker commands

The local AI must never run any of these without explicit human authorization:

```text
docker system prune
docker system prune -a
docker builder prune
docker image prune -a
docker volume prune
docker network prune
docker compose down -v
any global cleanup that can remove unrelated images, caches, volumes or databases
```

Also forbidden:

```text
periodic "cleanup" scripts that delete all dangling images
removing the user's existing MySQL/Redis/MinIO volumes
recreating the entire full stack merely because one unit test failed
```

Project-scoped cleanup of a disposable M11 container/image created by the agent itself is allowed and encouraged after verification.

## 9. Build/test cadence

Use this cadence:

```text
Task 1-8:
  focused unit/integration tests only
  no application Docker builds

Task 9:
  finalize Dockerfile/CI/runbook
  one local Gateway Docker build
  native mock smoke preferred

Task 10:
  full Maven verification once
  frontend lint/test/build once if applicable
  do not rebuild Docker if Task 9 image already proved the unchanged Dockerfile/package
  push branch / open PR
  let GitHub CI + Security perform the remote full build matrix
```

If Task 10 requires a code fix that does not affect the Dockerfile/package inputs, rerun the relevant Maven test; do not reflexively rebuild the image.

If a fix materially changes Dockerfile/package contents, one follow-up local Gateway image build is permitted.

## 10. Acceptance evidence must include disk-safe execution

`docs/03-acceptance/m11-gateway-edge-evidence.md` must explicitly record:

```text
local Docker application image builds performed: <count by image>
Gateway final Docker build: PASS/FAIL
repeated compose --build loop used: NO
global Docker prune used: NO
destructive volume cleanup used: NO
GitHub CI/Security full image gates: PASS/FAIL/pending
```

Do not claim `PASS` for any command not actually run.

## 11. Completion behavior

The local AI should continue through the entire M11 plan autonomously. Routine failures are debugged and fixed by the agent.

Stop only for:

1. a frozen M10 contradiction that cannot be resolved without changing business/financial semantics;
2. an external service/account permission blocker that prevents a required non-optional acceptance gate;
3. evidence that continuing would risk destructive data loss or expose a secret.

Missing real MiMo credentials alone does **not** stop M11 implementation; it blocks only the real-provider smoke evidence.

At completion, push `feat/m11-gateway-edge-mvp`, open the final PR, and report results. Do not merge the PR.
