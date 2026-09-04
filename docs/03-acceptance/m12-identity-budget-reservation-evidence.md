# M12 Identity / Attribution / Budget Reservation — Acceptance Evidence

> Branch: `feat/m12-identity-budget-reservation` | M11 baseline: `main@b57d12a`
> Core fully-verified implementation SHA: `baf315891b89d6a9f171ed18c93721cfd5691240`
> Post-evidence test-isolation fix SHA: `15195c6b1222714f8f5da6e381843a5de58f35e1`
> Last runtime-code SHA before this docs-only closure refresh: `8ecdfb1f2158b3e8fa3aac08bf430ddceb519d62`
> Status: implementation and verification complete; PR closure evidence refreshed. No merge performed by this document update.

## 1. Scope (frozen)

Implemented in M12:

```text
Gateway credential-governed identity / attribution
MySQL-authoritative Budget Reservation (TX1)
Dispatch fence gating (TX2, shared BillingPeriod lock)
Reservation TTL + scheduled DB recovery (conservative)
Redis operational quota (per credential / UTC day, non-money)
Close blocker extension (ACTIVE / PENDING_HOLD block Close)
Real-MySQL reservation concurrency + architecture guardrails
```

Explicitly absent (M13+, not implemented opportunistically):

```text
gateway_usage_fact / gateway_usage_dimension / gateway_settlement
GATEWAY_SETTLEMENT Ledger source / SYSTEM posting / Actual posting
Budget Actual consumption / Commitment consumption from Gateway
M14 failover / M15 reconciliation runtime / generic policy DSL
```

## 2. Migration

Only one new migration vs the M11 baseline:

```text
backend/src/main/resources/db/migration/V19__m12_budget_reservation.sql
```

`V1-V18` are unchanged. V19 creates the single M12 table `budget_reservation` with `UNIQUE(org_id, route_attempt_id)`, generated `effective_slot` + `UNIQUE(org_id, request_id, effective_slot)` so at most one effective `ACTIVE`/`PENDING_HOLD` hold exists per request, plus amount/status/version checks and same-org foreign keys. No M13 or Ledger schema is created.

## 3. Identity / attribution

Canonical principals remain `HUMAN_MEMBER` / `SERVICE` (no invented `MEMBER`). Per-request identity is credential-governed and clients cannot override organization, principal, project, financial scope, budget enforcement mode, or allowed models. SERVICE cannot dynamically select another Project; another governed Project requires another credential. The financial target is exactly one of `PROJECT` / `TEAM` / `COST_CENTER`.

The M11 authentication chain remains intact: prefix/digest, active principal/project/scope, explicit model allowlist, Provider Account/Credential, and frozen Pricing Version. M12 adds budget-enforcement semantics on top rather than replacing identity governance.

## 4. Reservation architecture

TX1 (`BudgetReservationService.admitSync` through an explicit `TransactionTemplate`) performs one short MySQL transaction off the Reactor Netty event loop:

```text
lock OPEN BillingPeriod
→ resolve exact Budget, else ORG fallback in pricing currency
→ lock Budget
→ read Total / Actual / Committed + effective ACTIVE/PENDING_HOLD reservations
→ insert ACTIVE reservation
→ VALIDATED → RESERVED
→ commit
```

When budget admission is terminally rejected, TX1 persists `REJECTED_BUDGET` / `REJECTED_DEPENDENCY` and returns a business outcome. The public HTTP error is mapped only after the transaction commits, avoiding rollback of the terminal state.

TX2 (`DispatchFenceService.commitDispatchFence`) then:

```text
lock OPEN BillingPeriod
→ verify matching ACTIVE reservation for budget-controlled admission
  (or an explicitly admitted OPTIONAL-unbudgeted request)
→ request + route attempt → DISPATCH_INTENT
→ commit
→ only then Provider I/O may begin
```

TX1 and TX2 remain separate by design. MySQL is the sole monetary authority; Redis never authorizes money.

## 5. Pricing upper bound

`ReservationAmountCalculator` uses a conservative MiMo `mimo-v2.5-pro` upper bound:

```text
input ceiling: 1,048,576 tokens
output ceiling: the exact effective max_completion_tokens sent upstream,
                capped by the enforced model maximum of 131,072
```

No chars/token average is used. With `CACHED_INPUT_TOKEN`, every conservative input token uses the higher normalized rate of `INPUT_TOKEN` vs `CACHED_INPUT_TOKEN`; cache hits are never predicted. Money uses `BigDecimal`; positive reservation amounts round upward to scale 8 (`CEILING`). Missing required pricing dimensions, unknown dimensions, or non-positive / non-representable bounds fail closed.

## 6. Budget semantics

Selection is exactly:

```text
exact financial scope + BillingPeriod + pricing currency
→ ORG + same BillingPeriod + same currency
→ no Budget
```

There is no implicit `TEAM/COST_CENTER → PROJECT → ORG` fallback and no automatic FX.

```text
REQUIRED + no Budget              → reject before Provider
REQUIRED + insufficient Budget    → reject before Provider
REQUIRED + unsafe bound           → fail closed before Provider
OPTIONAL + no Budget              → explicitly allowed unbudgeted
OPTIONAL + matching Budget enough → reserve
OPTIONAL + matching Budget short  → reject; cannot bypass existing Budget
```

## 7. Recovery

`ReservationRecoveryScheduler` is separated from `ReservationRecoveryService`; tests disable the scheduler and drive recovery deterministically. Recovery scans expired `ACTIVE` holds in bounded batches and preserves lock order:

```text
BillingPeriod → Budget → BudgetReservation
```

A definitively pre-dispatch hold (`VALIDATED`/`RESERVED`, route attempt `PLANNED`, no dispatch evidence) can become `RELEASED`, with the request moved to `FAILED_PRE_DISPATCH`. Anything that may have dispatched becomes `PENDING_HOLD` and continues to hold money until later Settlement/reconciliation. TTL expiry is only a recovery trigger and is never proof of zero cost. Recovery vs dispatch fencing cannot produce `DISPATCH_INTENT + RELEASED`.

## 8. Redis operational quota

`RedisDailyQuotaLimiter` implements a credential-scoped UTC-daily request quota using `redis/gateway-quota.lua`.

```text
aicostops:v2:gateway:quota:{credentialId}:{yyyyMMddUTC}
```

No raw API key material is included. Quota exhaustion returns 429 before Provider dispatch. Redis failure while quota is enabled fails closed with `GATEWAY_DEPENDENCY_UNAVAILABLE` before Provider dispatch. Redis remains operational state only and never becomes monetary truth.

## 9. Close blocker

`PENDING_GATEWAY_FINANCIAL_WORK` covers both unresolved possible-billable Gateway requests and unresolved M12 reservations. `ACTIVE` / `PENDING_HOLD` reservations block normal Close; `RELEASED` / `FINALIZED` do not block by themselves. M12 reuses the existing blocker code rather than introducing another financial-close code.

## 10. Full verification evidence at core implementation SHA

The full verification baseline was executed against core implementation SHA:

```text
baf315891b89d6a9f171ed18c93721cfd5691240
```

Recorded totals:

```text
Gateway surefire: 103 (14 files, 0 failures/errors/skipped)
Gateway failsafe: 50 (12 files, 0 failures/errors/skipped)
Backend surefire: 504 true unit tests (67 files, 1 existing skip)
Backend failsafe: 871 (136 files, 0 failures/errors/skipped)
Architecture rules: PASS
Frontend: 432 tests + lint/build PASS
Smoke: SMOKE_V1_PASS
```

The original XML aggregation also contained 38 stale manually executed `*IntegrationTest` duplicates in surefire reports; they are not counted as unit tests above.

Key M12 suites recorded PASS:

```text
BudgetReservationServiceIntegrationTest (7)
BudgetReservationConcurrencyIntegrationTest (5)
ReservationAmountCalculatorTest (10)
ReservationRecoveryIntegrationTest (5)
RedisDailyQuotaLimiterIntegrationTest (5)
DispatchFenceIntegrationTest (5)
GatewayRequestIdempotencyIntegrationTest (3)
ChatCompletionControllerTest (14)
GatewayArchitectureTest (7 ArchRules)
GatewayM12ReservationSchemaIntegrationTest (10, backend)
GatewayFinancialWorkCloseIntegrationTest (7, backend)
```

Provider-zero-call rejection coverage includes REQUIRED no-Budget rejection, quota exhaustion, Redis unavailability, malformed/oversize request rejection, and TX1 budget rejection before dispatch.

## 11. Concurrency evidence

The real-MySQL critical concurrency suite was repeated five times and passed 5/5 each run.

Proven invariants include:

```text
Budget=100, 80+80 → exactly one ACTIVE hold
Budget=100, 50+50 → two ACTIVE holds
8 concurrent holds never exceed capacity
V1-style Actual mutation serializes with reservation
same idempotency identity → one request, one route attempt, one effective hold
```

Lock order remains:

```text
BillingPeriod → Budget → Commitment when applicable → BudgetReservation → Gateway request/source
```

## 12. Correctness defects found and fixed during M12

1. `@Transactional` self-invocation originally left TX1 without an effective transaction. It was replaced by an explicit `TransactionTemplate` boundary plus the synchronous `admitSync` entry for callers already on the blocking DB scheduler.
2. Persisting `REJECTED_BUDGET` and then throwing inside the same rollbacking TX1 lost the terminal state. TX1 now returns a business outcome, commits it, and the caller maps it to the public error afterward.
3. Recovery originally called `findAttemptById(orgId, attemptId)` with the arguments reversed. The call order was corrected and covered by recovery tests.
4. M11-era stale tests pinned “exactly V18 / no budget_reservation” and “exactly 7 close checks”; they were updated to the M12 truth.
5. The 1 MiB controller boundary test had a mock-upstream TCP reset race because the mock responded without draining the request body. The test fixture now drains the request body before responding; production Gateway behavior was not changed.

## 13. Post-evidence PR closure changes

The original acceptance evidence was recorded after the full runtime verification at `baf3158`. Two later commits were intentionally narrow and are recorded separately instead of pretending the original full-suite run occurred on a later SHA.

### 13.1 Test-isolation fix

```text
15195c6b1222714f8f5da6e381843a5de58f35e1
```

Root cause: `InvitationAcceptanceServiceIntegrationTest` could leave an `invitation` row. `AuthorizationContextServiceIntegrationTest.setUp()` attempted to delete `organization` before cleaning `invitation`, so `invitation.fk_invitation_org` rejected the delete.

Change: add `DELETE FROM invitation` before `DELETE FROM organization` in `AuthorizationContextServiceIntegrationTest` only. No production code, migration, or M12 semantics changed.

Verification recorded for this change:

```text
failing testcase: 1/1 PASS
test class: 5/5 PASS
affected IAM suites: 8/8 PASS
exact backend-integration CI command: 842 tests, 0 failures/errors, BUILD SUCCESS
GitHub CI run 33835159440: SUCCESS
GitHub Security run 33835159575: SUCCESS
```

### 13.2 CodeQL unused-parameter cleanup

```text
8ecdfb1f2158b3e8fa3aac08bf430ddceb519d62
```

Change: remove only three unused private-method parameters in `BudgetReservationService` and their corresponding call arguments:

```text
reservationImpossible: remove unused locked
insufficient: remove unused locked
replayExisting: remove unused principal
```

No method body, transaction boundary, Budget semantics, migration, Redis quota, recovery, Close logic, or M13 scope changed.

Verification recorded before push:

```text
ReservationAmountCalculatorTest + BudgetReservationServiceIntegrationTest:
17 tests, 0 failures/errors/skipped, BUILD SUCCESS
Gateway mvn verify: BUILD SUCCESS (final failsafe summary: 50 tests, 0 failures/errors/skipped)
git diff --check: clean
```

The three GitHub Advanced Security / CodeQL “useless parameter” review threads became resolved and outdated after this commit.

Because this document is now being refreshed by a subsequent docs-only commit, do not interpret `8ecdfb1` as the eventual PR head after this document commit. It is the last runtime-code SHA before the docs-only closure refresh.

## 14. Security / privacy

```text
No prompt persistence; no completion persistence
No raw Gateway API key logs; no raw Idempotency-Key logs
No provider key logs; no HMAC key logs; no KEK logs
Status API does not expose prompt/completion/provider secrets/Budget totals/Ledger detail
Quota Redis key carries credential id + UTC day only, never raw key material
Gateway metrics use bounded labels only, without high-cardinality financial IDs
```

## 15. M13 absence

M12 contains no Gateway usage-fact runtime, no Gateway Settlement runtime, no Ledger settlement posting, no Budget Actual consumption, and no Commitment consumption from Gateway. V19 creates only `budget_reservation`; `commitment_id` remains NULL and `commitment_backed_amount` remains 0 in M12. The public Gateway OpenAPI remains structurally unchanged and does not add client-controlled project/team/cost-center/budget/currency/provider/pricing fields.

## 16. Known limitations (non-blocking)

```text
The MiMo input reservation uses the provider context ceiling (1,048,576 tokens)
rather than a certified exact hosted tokenizer estimator. This intentionally
over-reserves rather than under-reserves.

FINALIZED exists for lifecycle/schema compatibility with M13. M12 itself does
not create final financial Settlement; recovery converges safe cases to
RELEASED and possible-billable cases to PENDING_HOLD.
```

## 17. SHA and evidence interpretation

Use the following SHA roles when auditing M12:

```text
M11 baseline:
  b57d12abc816a9fae0d576e9c8f9f16f995df165

Core M12 fully-verified implementation:
  baf315891b89d6a9f171ed18c93721cfd5691240
  Full Gateway + Backend verification evidence belongs to this SHA.

Post-evidence test-only CI isolation fix:
  15195c6b1222714f8f5da6e381843a5de58f35e1
  Test fixture only; no production/runtime/migration change.

Last runtime-code SHA before final docs refresh:
  8ecdfb1f2158b3e8fa3aac08bf430ddceb519d62
  Behavior-preserving unused-parameter cleanup only.

Final docs-only closure SHA:
  Do not hardcode it inside this file because changing the value would itself
  create another SHA. Use Git history / PR head to identify the docs-only
  commit whose message is "docs(m12): refresh final PR closure evidence".
```

The earlier wording `HEAD is again baf3158` described the repository state during the original evidence-collection session and is intentionally removed here because it is no longer the current PR state.

## 18. Final merge gate

Before merging PR #132, verify the actual current PR head rather than relying on a hardcoded docs SHA:

```text
PR is open and mergeable
CI = success on current head
Security = success on current head
no unresolved blocking review threads
V1-V18 unchanged
no M13 runtime scope creep
```

This evidence document itself does not perform or authorize the merge.
