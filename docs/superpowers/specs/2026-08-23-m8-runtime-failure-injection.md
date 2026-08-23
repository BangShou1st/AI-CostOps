# M8 Stage 1 / PR2 — AIC-069 Runtime Failure Injection Evidence

Date: 2026-08-23
Baseline: `c324742`
Branch: `test/m8-resilience-security`

## Failure contracts

- Authentication Redis is fail-closed: a Redis failure returns a stable 503 and
  cannot issue a session or token.
- Authorization-cache Redis is availability-only: MySQL remains the durable
  authorization truth.
- Workbench/dashboard Redis is cache-only: a cache failure is a MySQL fallback,
  not an empty or fabricated financial response.
- Evidence object-store failure is fail-closed: no false `AVAILABLE` row and no
  provider import queue success before evidence is stored.
- Import worker failure is recoverable: attempts are failed or recovered with
  explicit lineage, leases are fenced, and stale workers cannot finalize.

## Evidence matrix

| Area | Fault method | Expected behavior | Observed result | Bug / fix | Status |
|---|---|---|---|---|---|
| Redis restart | Real Redis Testcontainer is paused and unpaused through Docker; mapped port is preserved. | Login fails closed while Redis is unavailable, then recovers after the server resumes. | 503 `REDIS_UNAVAILABLE_FOR_AUTH`; no login-success audit or refresh session; valid login succeeds after bounded Redis recovery polling. | No production bug found. The initial stop/start experiment exposed only test wiring (new mapped port); it was replaced by pause/unpause. | PASS |
| Redis login failure policy | Real Redis outage plus existing Redis rate-limiter dependency tests. | No unlimited login bypass, no token/session issuance, stable ProblemDetail code. | `RedisRateLimiter` maps dependency failure to 503; login test confirms no partial session. Account/IP limits and TTL behavior remain covered. | No production bug found. | PASS |
| MinIO unavailable | Existing infrastructure-boundary outage adapter at the `ObjectStoragePort` seam and real MinIO-backed download path with a missing object. | Evidence/provider upload fails closed with dependency-unavailable; no false `AVAILABLE` row and no partial import success. | 503 `DEPENDENCY_TEMPORARILY_UNAVAILABLE`; provider upload leaves evidence `STAGING`; unavailable evidence is never opened. | No production bug found. | PASS |
| Worker crash | Real MySQL lease state is set to expired `RUNNING` and recovery is invoked; executor failure paths are exercised with bounded test adapters. | Old attempt becomes explicit failed/recoverable lineage; no canonical publish or duplicate confirm. | `WORKER_LEASE_EXPIRED` predecessor and one queued `LEASE_RECOVERY` successor; retry budget exhaustion fails the batch; raw rows remain auditable. | No production bug found. | PASS |
| Lease recovery / fencing | Two recovery workers race; lease version/owner changes; stale executor completion is attempted after takeover. | Exactly one successor; stale heartbeat, raw/canonical write, and finalization are rejected. | One recovery successor is created; stale owner operations affect zero rows; no double publish. | No production bug found. | PASS |
| Dashboard fallback | Real Redis is paused during a Workbench request after a healthy cache read. | Workbench remains 200, financial response is MySQL-correct, and permission trimming is unchanged; Redis recovery permits later cache use. | Response remains correct (`10.00000000` CNY) while Redis is unavailable and after recovery. | No production bug found. | PASS |

## Evidence tests

- `LoginServiceIntegrationTest.redisRestartFailsClosedDuringLoginAndRecoversWithoutIssuingPartialSession`
- `WorkbenchIntegrationTest.redisRestartFallsBackToMysqlAndQueriesRemainCorrectAfterRecovery`
- `ProviderImportStorageOutageApiIntegrationTest`
- `EvidenceDownloadApiIntegrationTest`
- `ImportLeaseServiceIntegrationTest`
- `ImportAttemptExecutorIntegrationTest`
- `ImportCanonicalizationIntegrationTest`
- `ImportWorkflowConcurrencyIntegrationTest`
- `RedisRateLimiterIntegrationTest`

No Redis outage was changed to fail-open, no MinIO outage was changed to fake
success, and no worker retry was made unbounded.
