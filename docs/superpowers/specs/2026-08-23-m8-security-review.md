# M8 Stage 1 / PR2 — AIC-070 Security and Secret Leak Review

Date: 2026-08-23
Baseline: `c324742`
Branch: `test/m8-resilience-security`

## Review matrix

| Area | Threat / failure | Investigation | Evidence | Existing control | Gap | Fix | Verification | Status |
|---|---|---|---|---|---|---|---|---|
| Raw Evidence authorization | IDOR by guessed evidence id, foreign organization, wrong scope, or non-available object. | Traced Evidence list/read/download services through org-scoped mapper queries and service authorization; checked provider and expense evidence paths. | `EvidenceDownloadApiIntegrationTest`, `EvidenceReadApiIntegrationTest`, `ExpenseEvidenceIntegrationTest`, `ImportWorkflowSecurityIntegrationTest`. | Permission is checked before org-scoped lookup; only `AVAILABLE` objects open; owner/reviewer paths are distinct. | None found. | None required. | Targeted Evidence/Expense/Import security integration tests. | PASS |
| Data scope | Path/query ids expanding ORG/PROJECT/TEAM/COST_CENTER caller scope. | Reviewed Budget, Expense, Allocation, Ledger, Audit, Evidence, Import, and Workbench read/write services and mapper predicates. | Budget/Allocation/Ledger/Workbench/IAM integration suites include foreign-org, wrong-scope, and bounded read cases. | `M1AuthorizationService`, scope-aware mapper predicates, and org-bound identifiers. | None found in reviewed high-sensitivity paths. | None required. | Targeted authorization and read API suites. | PASS |
| Secret logging and audit metadata | Passwords, bearer/refresh tokens, provider payloads, or secret-shaped fields reaching logs, ProblemDetail, audit, or import issue surfaces. | Searched logger/exception/audit/toString/request/worker surfaces; reviewed `AuditService`, payload redaction, issue sanitizer, safe failure summary, and storage exception messages. | `ProviderAdapterSecurityIntegrationTest`, `ImportWorkflowSecurityIntegrationTest`, `AuditServiceTest`, `IssueSanitizerTest`, `PayloadRedactorTest`. | Audit rejects secret key fragments; provider failures persist only exception categories; raw payloads are redacted/masked. | No confirmed leak. | None required. | Synthetic sentinel assertions plus source scan; no real credential used. | PASS |
| Refresh replay | Old refresh token replay or race issuing a new long-lived session. | Reviewed Redis Lua rotation, race window, replay revocation, security version invalidation, and API behavior. | `RefreshAndLogoutApiIntegrationTest.authenticatesMeAndRejectsReplay` and logout/session invalidation cases. | Digest-only Redis state, atomic rotation, race/replay distinction, revocation. | None found. | None required. | Refresh API integration tests. | PASS |
| Rate limit | Boundary/TTL/normalized identity bypass or Redis outage fail-open. | Reviewed account/IP fixed windows, hashed keys, TTLs, login ordering, and dependency mapping. | `RedisRateLimiterIntegrationTest`, `RedisRateLimiterTest`, `LoginServiceIntegrationTest`, plus the real Redis pause test. | Rate check precedes credential verification; Redis failure maps to `REDIS_UNAVAILABLE_FOR_AUTH`. | None found. | None required. | Integration and unit tests; no token/session on outage. | PASS |
| VITE_* safety | Backend credentials or signing keys entering browser build through Vite env. | Scanned `import.meta.env`, `VITE_*`, Vite config, Dockerfile/compose, frontend source and build configuration. | Only backend proxy `process.env.BACKEND_PORT` is used in Vite config; no `VITE_*` application secret reference exists. | Backend-only credentials remain Compose/Spring environment variables; public browser config is not secret-bearing. | Existing design documentation contains forbidden-name examples as negative guidance, not active configuration. | None required. | `rg`/tracked-file scan; frontend lint/test/build. | PASS |
| `.gitignore` | Local env, credentials, logs, dumps, build output, or raw provider exports tracked accidentally. | Reviewed `.gitignore`, tracked paths, and ignore resolution for `.env`, targets, node modules, dist, logs, dumps, credential files, and raw exports. | `.env`, `.env.local`, `backend/target`, `frontend/node_modules`, `frontend/dist`, and `*.log` resolve to ignore rules; `.env.example` remains intentionally tracked. | Explicit allow-list for `.env.example`, sanitized fixtures, and source. | None found. | None required. | `git check-ignore -v`; `git ls-files` inventory. | PASS |
| Git history secret | A real credential previously committed or pushed. | Scanned all reachable history for AWS-style keys, private-key headers, provider token shapes, JWTs, bearer values, and generic credential assignments; classified synthetic fixtures separately. | No confirmed real secret found in Git history. Pattern hits are synthetic test sentinels, redacted examples, or ordinary source identifiers. | Synthetic fixtures are test-only and assertions ensure they do not persist to sensitive surfaces. | None confirmed. | No rotation required. | `git log --all -G ...`, current tracked `git grep`, and path inventory; outputs were path/commit summaries only. | PASS |

## Security findings

- P0: 0
- P1: 0
- P2/follow-up: 0 new blockers
- Confirmed real secret in Git history: none

No production security behavior was weakened. No secret value is included in
this document, commit metadata, PR text, or test output summary.
