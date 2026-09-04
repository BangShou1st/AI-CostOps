# M12 Identity / Attribution / Budget Reservation — Acceptance Evidence

> Branch: `feat/m12-identity-budget-reservation` | M11 baseline: `main@b57d12a`
> Implementation HEAD: `baf3158` (this docs commit only appends this file)
> Status: implementation + verification complete; evidence recorded, awaiting
> independent final code review. No merge performed.

## 1. Scope (frozen)

Implemented (M12 slice):

```text
Gateway credential-governed identity / attribution
MySQL-authoritative Budget Reservation (TX1)
Dispatch fence gating (TX2, shared BillingPeriod lock)
Reservation TTL + scheduled DB recovery (conservative)
Redis operational quota (per credential / UTC day, non-money)
Close blocker extension (ACTIVE / PENDING_HOLD block Close)
Real-MySQL reservation concurrency + architecture guardrails
```

Explicitly absent (M13+, not implemented even opportunistically):

```text
gateway_usage_fact / gateway_usage_dimension / gateway_settlement
GATEWAY_SETTLEMENT Ledger source / SYSTEM posting / Actual posting
Budget Actual consumption / Commitment consumption from Gateway
M14 failover / M15 reconciliation runtime / generic policy DSL
```

## 2. Migration

Only one new migration vs `main`:

```text
backend/src/main/resources/db/migration/V19__m12_budget_reservation.sql
```

`V1-V18` untouched (`git diff main...HEAD -- backend/src/main/resources/db/migration`
shows exactly one added file, +83 lines). V19 creates the single M12 table
`budget_reservation` with `UNIQUE(org_id, route_attempt_id)`, generated
`effective_slot` + `UNIQUE(org_id, request_id, effective_slot)` (at most one
effective ACTIVE/PENDING_HOLD hold per request), amount/status/version CHECKs
and same-org FKs. No M13 or Ledger schema is created.

## 3. Identity / attribution

Canonical principals `HUMAN_MEMBER` / `SERVICE` (no invented `MEMBER`).
Per-request identity is permanently credential-governed (client cannot
override): organization / principal / project / financial scope / budget
enforcement mode / allowed models. SERVICE cannot pick another Project
dynamically; a different governed Project needs a different credential.
Financial target is exactly one of `PROJECT` / `TEAM` / `COST_CENTER`.
M11 auth chain (prefix/digest, principal/project/scope active, explicit model
allowlist, Provider Account/Credential, Pricing Version) is unchanged; M12
only adds budget-enforcement semantics on top.

## 4. Reservation architecture

TX1 (admission, `BudgetReservationService.admitSync` via explicit
`TransactionTemplate`): lock OPEN BillingPeriod → resolve exact Budget else
ORG fallback in the pricing currency (no FX; different-currency Budget is no
matching Budget) → lock the Budget row → observe Total/Actual/Committed plus
effective ACTIVE/PENDING_HOLD reservations under the same lock → insert the
ACTIVE hold + move VALIDATED to RESERVED (or persist REJECTED_BUDGET /
REJECTED_DEPENDENCY as a terminal business result and return it).

TX2 (dispatch fence, `DispatchFenceService.commitDispatchFence`): lock OPEN
BillingPeriod → verify the matching ACTIVE reservation on the matching route
attempt/period (an allowed unbudgeted OPTIONAL request may proceed without
one) → move request + route attempt to DISPATCH_INTENT → commit. Provider I/O
is legal only after the fence commits. TX1 and TX2 are never merged.

Rejection durability: TX1 never throws the public rejection from inside the
committing transaction (that would roll the REJECTED_BUDGET state back).
`GatewayRequestService` maps the committed outcome to the public error after
commit (`REJECTED_BUDGET` → 429 `GATEWAY_BUDGET_EXHAUSTED`,
`REJECTED_DEPENDENCY` → 503 `GATEWAY_DEPENDENCY_UNAVAILABLE`).

MySQL is the sole monetary authority; Redis never authorizes money.

## 5. Pricing upper bound

Conservative MiMo `mimo-v2.5-pro` bound (`ReservationAmountCalculator`, pure
function): input quantity is the fixed context ceiling 1,048,576 tokens (no
chars/token estimate); output quantity is the exact effective
max_completion_tokens the Gateway validated and sends upstream (capped by the
enforced 131,072 output ceiling). With CACHED_INPUT_TOKEN present, every
conservative input token uses the higher normalized unit rate of INPUT_TOKEN
vs CACHED_INPUT_TOKEN; cache hits are never predicted. BigDecimal only;
positive money rounds UP to scale 8 (`setScale(8, CEILING)`), never down.
Missing INPUT_TOKEN / missing OUTPUT_TOKEN for a positive output limit /
unknown dimension / non-positive or non-representable result fails closed.

## 6. Budget semantics

Lookup: exact financial scope + BillingPeriod + pricing currency → ORG + same
period + same currency → no Budget. No `TEAM → PROJECT → ORG` /
`COST_CENTER → PROJECT → ORG` implicit Project fallback. No FX.

REQUIRED: no matching Budget / insufficient Budget / unsafe bound → fail
before any Provider call. OPTIONAL: no matching Budget → explicitly allowed
unbudgeted; matching Budget sufficient → reserve; matching Budget exhausted →
reject. OPTIONAL is never a bypass around an existing exhausted Budget.

## 7. Recovery

`ReservationRecoveryScheduler` (periodic trigger) is separated from
`ReservationRecoveryService` (transaction logic); tests disable the scheduler
and drive recovery deterministically. Recovery scans expired ACTIVE holds in
bounded batches; per-hold transaction lock order is BillingPeriod → Budget →
Reservation. A definitively pre-dispatch hold (request RESERVED/VALIDATED,
attempt PLANNED, no DISPATCH_INTENT evidence) becomes RELEASED with the
request moved to FAILED_PRE_DISPATCH; anything that may have dispatched
becomes PENDING_HOLD and keeps holding money until Settlement/reconciliation.
Post-dispatch states are never released. TTL expiry alone is only a recovery
trigger, never proof of no cost. Recovery vs dispatch fence can never yield
DISPATCH_INTENT + RELEASED together.

## 8. Quota

Credential-level UTC-daily request quota (`RedisDailyQuotaLimiter` over
`redis/gateway-quota.lua`), operational only. Redis key is
`aicostops:v2:gateway:quota:{credentialId}:{yyyyMMddUTC}` — no raw API key
material. Quota exhaustion rejects with 429 before Provider dispatch. Redis
failure while quota is enabled fails closed (503
`GATEWAY_DEPENDENCY_UNAVAILABLE`, no Provider call). Redis can never
authorize spend.

## 9. Close

`PENDING_GATEWAY_FINANCIAL_WORK` now blocks normal Close on both unresolved
possible-billable requests (at/after DISPATCH_INTENT without a durable
Settlement) and unresolved reservations: any ACTIVE or PENDING_HOLD
reservation blocks; RELEASED / FINALIZED never block on their own. The
Close-blocker evaluation validates all blocker codes (M11 had 7 checks, M12
has 8 with the Gateway blocker; `PeriodCloseService.validateAllBlockers`
replaces the stale `validateSeven` name).

## 10. Test evidence

Final full `mvn verify` runs against implementation HEAD `baf3158`
(Gateway re-run as `clean verify` 2026-09-04 10:35–10:37 in this session to
repair a partially overwritten surefire window; Backend reports from the
final 01:20–01:33 full run, after the last Backend change `d0facda` 00:26):

```text
Gateway surefire:  103 (14 files, 0 failures/errors/skipped)
Gateway failsafe:  50 (12 files, 0 failures/errors/skipped)
Backend surefire:  542 XML-aggregated (72 files, 0 failures/errors, 1 skipped);
                   38 of those are stale *IntegrationTest duplicates executed
                   manually before the final run — true unit total is 504
                   (67 files, 1 skipped: DevInvitationMailboxTest)
Backend failsafe:  871 (136 files, 0 failures/errors/skipped)
```

Key M12 test classes (all PASS):

```text
BudgetReservationServiceIntegrationTest (7): TX1 exact/ORG/currency/REQUIRED/OPTIONAL/replay semantics
BudgetReservationConcurrencyIntegrationTest (5): real-MySQL financial concurrency (see section 11)
ReservationAmountCalculatorTest (10): MiMo baseline, CEILING, cached-input, fail-closed dimensions
ReservationRecoveryIntegrationTest (5): pre-dispatch RELEASE vs post-dispatch PENDING_HOLD + fence race
RedisDailyQuotaLimiterIntegrationTest (5): burst, race, no-raw-key, fail-closed, end-to-end 429
DispatchFenceIntegrationTest (5): fence/commit/close-race/no-reservation/non-planned semantics
GatewayRequestIdempotencyIntegrationTest (3): one request / one attempt / one dispatch, conflict, no re-dispatch
ChatCompletionControllerTest (failsafe, 14): HTTP surface incl. REQUIRED reserve+dispatch,
  REQUIRED no-budget 429 + UPSTREAM_CALLS 0, replay never re-dispatches, 1 MiB boundary
GatewayArchitectureTest (7 ArchRules): no backend deps, no Flyway, no Mono.block on DB path,
  Gateway budget code writes only its own tables
GatewayM12ReservationSchemaIntegrationTest (10, backend): V19 uniqueness/CHECK/FK/column contract
GatewayFinancialWorkCloseIntegrationTest (7, backend): ACTIVE/PENDING_HOLD block, RELEASED/FINALIZED pass
```

Provider zero-call rejection proof (`UPSTREAM_CALLS == 0` asserted):

```text
ChatCompletionControllerTest.requiredBudgetCredentialWithoutBudgetFailsClosedBeforeProviderDispatch
  REQUIRED + no Budget → 429 GATEWAY_BUDGET_EXHAUSTED, no Provider call
ChatCompletionControllerTest (oversize/unknown-field/auth/chunked/gzip edges)
  malformed or oversize requests → 4xx before Provider dispatch, no Provider call
RedisDailyQuotaLimiterIntegrationTest.quotaRejectsOverLimitBeforeProviderDispatch
  quota exhausted → 429 before Provider dispatch, no Provider call
RedisDailyQuotaLimiterIntegrationTest.redisUnavailableFailsClosedWithDependencyUnavailable
  Redis down while quota enabled → 503 GATEWAY_DEPENDENCY_UNAVAILABLE, no Provider call
BudgetReservationServiceIntegrationTest.requiredWithoutMatchingBudgetRejects
  TX1 persists REJECTED_BUDGET inside the committing transaction
```

## 11. Concurrency x5

Focused loop (correct failsafe entry: `*IntegrationTest` classes run under
failsafe; the surefire `-Dtest=` entry point skips them by pom design):

```text
mvn -B -f gateway/pom.xml test "-Dtest=BudgetReservationConcurrencyIntegrationTest" "-DfailIfNoTests=false"
run1 PASS (5/5, ~33 s)
run2 PASS (5/5, ~31 s)
run3 PASS (5/5, ~33 s)
run4 PASS (5/5, ~30 s)
run5 PASS (5/5, ~27 s)
```

Proven on real MySQL:

```text
Budget=100, 80+80 → exactly one ACTIVE hold (eightyPlusEightyAgainstOneHundredYieldsExactlyOneHold)
Budget=100, 50+50 → two ACTIVE holds (fiftyPlusFiftyAgainstOneHundredYieldsTwoHolds)
8 concurrent holds never exceed capacity; V1-style Actual increment races
deterministically with reservation; same idempotency identity → one request,
one route attempt, one effective hold
```

Lock order held: BillingPeriod → Budget → Commitment when applicable →
BudgetReservation → Gateway request/source.

## 12. Critical defects found and fixed (correctness evidence)

1. `@Transactional` self-invocation left TX1 without an effective
   transaction. Fixed to an explicit `TransactionTemplate` boundary with a
   synchronous entry (`admitSync`) for callers already on the blocking-DB
   scheduler; never call the blocking core directly or the Budget lock loses
   its transaction.
2. TX1 persisted `REJECTED_BUDGET` and then threw from the same rollbacking
   transaction, so the terminal rejection could not survive. TX1 now returns
   the business outcome, commits, and `GatewayRequestService` maps it to the
   public error after commit (durable + idempotent replay converges).
3. Recovery called `findAttemptById(orgId, attemptId)` with the arguments
   reversed. Fixed; covered by the recovery tests.
4. Stale tests: M11 schema test pinned "exactly V18 / no budget_reservation"
   and the close coordinator pinned "exactly 7 checks"; both updated to the
   M11/M12 truth (V19 wave exists; 8 checks incl. the Gateway blocker).
5. `ChatCompletionControllerTest.bodyAtExactlyMaxBytesIsAccepted` flaked
   (502) because the mock upstream responded without draining the 1 MiB
   request body (TCP RST race). Test-fixture fix: drain the body first. No
   production Gateway defect.

## 13. Security / privacy

```text
No prompt persistence; no completion persistence
No raw Gateway API key logs; no raw Idempotency-Key logs
No provider key logs; no HMAC key logs; no KEK logs
Status API returns meteringStatus=null / settlementStatus=null (M13-owned),
  never prompt/completion/Provider secrets/Budget totals/Ledger detail
Quota Redis key carries the credential id + UTC day only, never raw key material
Gateway metrics use bounded labels only (outcome/error class/provider code),
  never high-cardinality financial ids
```

## 14. M13 absence

No Gateway usage-fact runtime, no Gateway Settlement runtime, no Ledger
settlement posting, no Budget Actual consumption, no Commitment consumption
from Gateway. `git diff main...HEAD --name-only` contains no
settlement/usage_fact/usage_dimension/failover runtime file; the only
matches for those words are frozen design/plan prose, the M12
not-implemented list, and the M11 schema test's negative assertion. V19
creates only `budget_reservation`; `commitment_id` stays NULL and
`commitment_backed_amount` stays 0 in M12 (Gateway never infers a Commitment
binding). OpenAPI (`docs/02-development/api/gateway-openapi.yaml`) is
unchanged vs `main` (empty diff): no project_id/team_id/cost_center_id/
budget_id/currency/pricing_version_id/provider_id request extension.

## 15. Known limitations (non-blocking)

```text
M12 conservative MiMo input reservation uses the provider context ceiling
(1,048,576 tokens) rather than a certified exact hosted tokenizer estimator,
intentionally over-reserving rather than under-reserving.
FINALIZED exists as schema/lifecycle compatibility for M13; M12 itself
creates no final financial Settlement (holds converge to RELEASED or
PENDING_HOLD only).
```

## 16. Commits and SHAs

```text
M11 baseline: b57d12a (feat(m11): deliver Gateway Edge MVP (#131))
Implementation SHA: baf3158 (all Java/SQL/YAML/test implementation; full
  Gateway + Backend verify ran against this SHA)
Evidence SHA: the HEAD docs-only commit appending this file only
  (`git show --stat HEAD` shows exactly one file); the exact value is
  recorded in the session Final Report, not hardcoded here (hardcoding it
  would change the hash on every amend).
Commit chain (main...HEAD):
  9126bdc docs(m12): freeze identity and reservation implementation design
  56f0042 feat(m12): add durable budget reservation schema
  962207f feat(m12): calculate conservative reservation upper bounds
  d009647 feat(m12): enforce mysql-authoritative budget admission
  83bfd48 feat(m12): recover reservation holds conservatively
  e7a544a feat(m12): add redis operational request quota
  d0facda feat(m12): block close on unresolved reservation holds
  0fb6e4b test(m12): prove reservation concurrency safety
  2200f93 test(m12): enforce reservation architecture and telemetry guards
  0520b0a test(m12): cover reservation dispatch over mock upstream
  baf3158 test(m12): scope quota key assertion per credential
Working tree: clean. git diff --check: clean. V1-V18: unchanged.
```

## 17. Environment note (this session)

During evidence collection the working tree was found checked out at `main`
(reflog: `checkout: moving from feat/m12-identity-budget-reservation to
main`); it was switched back with `git checkout
feat/m12-identity-budget-reservation` — HEAD is again `baf3158`, working
tree clean, all M12 files present. Two earlier evidence-probe mistakes are
recorded so the numbers above are not misread: (a) a `-Dtest=` concurrency
loop went through surefire, which excludes `*IntegrationTest` by pom design
(~1.5 s BUILD SUCCESS with zero tests executed — discarded, re-run through
the correct entry point, section 11); (b) a main-branch `mvn -B -f
gateway/pom.xml verify` pass overwrote a subset of surefire XMLs with M11
results — superseded by the clean `mvn -B -f gateway/pom.xml clean verify`
re-run whose totals are reported in section 10.
