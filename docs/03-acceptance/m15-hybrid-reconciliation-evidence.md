# M15 Hybrid Reconciliation — Acceptance Evidence

> Status: **independent-review remediation complete on `feat/m15-hybrid-reconciliation`**, awaiting GPT-5.6 Sol re-review and user merge instruction. Hosted CI will be rerun only after independent Sol re-review passes.
> Issue: #148 — `feat(m15): deliver hybrid reconciliation` (PR #149)
> Spec: `docs/superpowers/specs/2026-09-05-m15-hybrid-reconciliation-design.md`
> Plan: `docs/superpowers/plans/2026-09-05-m15-hybrid-reconciliation-plan.md`
> Base: `main@502b8aa38a70a0afc4751097365ec6543592280f`
> Review anchor reviewed by Sol: `cc8ebe12a2d0c42b28bf4aa293543e3c7088b01b`
> This document records only results actually executed and observed on this machine. The previous "implementation complete" self-report and its hosted-CI evidence (PR run 33966798874/33966798865) were superseded by the independent review and are no longer claimed as correctness evidence.

## 0. What this round changed (Sol review remediation)

Every P0/P1 finding from the independent review of PR #149 was fixed with a RED test first, then a minimal implementation, then targeted GREEN. The previously recorded claims that the review refuted are retracted; the evidence below replaces them.

### P0-1 Charge disposition scope (BLOCKER)

- Root cause: `HybridReconciliationActionService.decideChargeDisposition` validated only same-org existence of the charge and the case, so an unrelated same-org charge could be dispositioned under any case and bypass the Hybrid posting fence.
- RED tests: `HybridChargeDispositionScopeIntegrationTest` — same org + wrong provider account → reject; wrong currency → reject; `period_start` outside the run BillingPeriod window → reject; unconfirmed import batch → reject; unsupported review status (`EXCLUDED_NONCOST`) → reject; in-scope CLEAN/SUSPECTED_DUPLICATE charge → MANUAL disposition succeeds (both `DIRECT_PROVIDER_CHARGE` and `RECONCILIATION_EVIDENCE`).
- Implementation: one bounded projection `HybridReconciliationMapper.selectChargeScopeContext` (charge currency/period/review status + confirmed-import batch lineage `provider_account_id`, `status='CONFIRMED'`, `confirmed_attempt_id`, run period window) validated inside the disposition transaction. The posting guard still trusts the disposition; creation is now strictly scoped.

### P0-2 Server-derived statement adjustment amount (BLOCKER)

- Root cause: `GatewayResolutionRequest.adjustmentAmount` flowed client → command → `reconciliation_adjustment` → Ledger → Budget Actual. The client could define a Ledger amount.
- RED tests: `GatewayFinancialResolutionIntegrationTest.statementResolutionDerivesAmountFromBoundStatementCharge` (amount = bound charge amount, `statement_charge_fact_id` persisted on both the adjustment and the resolution), `.statementResolutionSubtractsInternalLedgerTruthOfTheSameRequest` (RECONCILIATION_REQUIRED settlement posting of 0.50 subtracted: 2.00 − 0.50 = 1.50 posted), `.statementResolutionRequiresABoundStatementCharge` (no charge → reject).
- Implementation: `adjustmentAmount` and `commitmentId` were **removed** from the public API, DTOs, OpenAPI, frontend types/client/UI and all tests. The amount is now `server-derived = bound statement Charge amount − immutable Ledger amount attributable to the same request` via `selectRequestPostedInternalAmount` (direct-source lineage: `source_gateway_settlement_id → gateway_settlement.request_id` and `source_reconciliation_adjustment_id → reconciliation_adjustment.gateway_request_id`; corrections contribute because they preserve direct sources). Aggregate case differences and pro-rata are never referenced. `ReconciliationMoney.requireScale8Exact` is applied; a zero derived amount is rejected with an explicit conflict.

### P0-3 Run/case/request lineage (BLOCKER)

- Root cause: any same-org COMPLETED run could resolve any request; `caseId` was not validated beyond same-org existence.
- RED tests: `.resolutionNeverCrossesRunPeriodOrCaseLineage` — COMPLETED run of another period → reject; case of another run → reject; case provider account mismatch → reject; case currency mismatch → reject.
- Implementation: pre-read lineage proves `run.billingPeriodId == request.billingPeriodId` before any financial lock; an optional case must belong to the run, the request's provider account and the request's financial currency. Everything is revalidated after the identity locks and again after the request source-row lock.

### Statement Charge ↔ Gateway Request strong binding (§7/§18)

- V23 `chk_reconciliation_adjustment_scope_shape` now requires `statement_charge_fact_id IS NOT NULL` for `GATEWAY_REQUEST` adjustments (and forbids it for `CASE_FULL`); `chk_gateway_financial_resolution_type_shape` requires statement-charge lineage for `STATEMENT_ADJUSTMENT_POSTED` and forbids it for `NO_CHARGE_CONFIRMED`. No V24 was needed; M15 is unmerged so V23 was amended in place and re-proven by the full Flyway V1→V23 real-MySQL run.
- Exact binding: when the run holds `EXACT_PROVIDER_REQUEST` evidence for the request, the server revalidates it against the *current* immutable source lineage (same run/request/org/provider account/currency, unique charge and request, certified profile, attempt not PLANNED/SAFE) and requires a client-supplied `statementChargeFactId` to equal it. RED test: `manualStatementBindingValidatesChargeScopeAgainstTheRequest` (foreign account, wrong currency, outside period, unconfirmed batch, excluded duplicate all reject).
- Manual binding: without exact evidence the reviewer selects `statementChargeFactId`; the server validates it against the same scope rules and persists a `MANUAL_BINDING` reconciliation_evidence row (charge + request + attempt lineage) in the same transaction. RED test: `.statementResolutionDerivesAmountFromBoundStatementCharge` asserts the MANUAL_BINDING evidence row exists.
- Exclusivity: a statement charge can back at most one resolution per org and cannot be manually bound to another request in the run. RED test: `.statementChargeIsBoundExclusivelyToOneRequest`.

### NO_CHARGE_CONFIRMED positive proof (§10)

- Root cause: any free-text `reasonCode`/`reasonNote` could confirm no-charge.
- RED tests: `.statementAbsenceOrGenericReasonIsNeverPositiveNoChargeProof` (statement-absence code → reject; generic review code → reject; bounded proof code without an auditable reference → reject), `.noChargeWithoutRunUnresolvedEvidenceIsRejected` (no `GATEWAY_UNRESOLVED` evidence in the run → reject).
- Implementation: bounded positive-proof vocabulary `PROVIDER_PORTAL_CONFIRMED_NO_CHARGE`, `PROVIDER_SUPPORT_CONFIRMED_NO_CHARGE`, `EXPLICIT_ZERO_PROVIDER_RECORD` plus a required persisted `positiveEvidenceReference` (6–256 chars), stored on the resolution evidence (`reconciliation_evidence.evidence_reference`, new bounded V23 column). Statement absence never proves zero cost.

### P0-4 Exact matching same provider account (BLOCKER)

- Root cause: `selectExactCorrelationGroups` joined `gateway_route_attempt` without `ra.provider_account_id=ib.provider_account_id`, so a charge of account A exactly matched a request of account B on the same provider request id. `pricing_version` was not org-qualified either.
- RED test: `HybridReconciliationEvidenceIntegrationTest.exactCorrelationRequiresTheSameProviderAccount` (account A charge + account B request, same certified id, same currency → MUST NOT exact match); the happy-path test now constructs same-account fixtures.
- Implementation: exact join requires `ra.provider_account_id=ib.provider_account_id`, `pv.org_id=ra.org_id`, and groups by `(provider_request_id, provider_account_id)`.

### P1-5 Reservation-bound commitment only (§12)

- Root cause: `GatewayResolutionRequest.commitmentId` let the client pick any commitment of the budget.
- RED tests: commitment lineage assertions in `GatewayFinancialResolutionRollbackIntegrationTest.failureAfterCommitmentConsumeRollsEverythingBack` and eligibility rejections; cross-period consumption is still rejected (`A cross-period resolution never consumes the historical commitment`).
- Implementation: `commitmentId` was removed from the public API. The commitment is locked only from `budget_reservation.commitment_id` of the bound reservation and revalidated (`lockedCommitment.id == lockedReservation.commitmentId`, `commitment.budgetId == selected budget`, `canConsume`, same-period positive amount) before consumption.

### P1-6 Canonical financial lock order (§13)

- Root cause: both financial services locked BillingPeriod → run/case → Budget (CASE_FULL) and re-min/max-sorted already-locked cross-period rows (Gateway resolution), a reverse lock order.
- Implementation (both services): pre-read immutable ids/context without financial locks → determine all BillingPeriod ids → lock all period rows strictly ascending id in one pass → resolve & lock Budgets sorted → (Gateway resolution) lock the reservation-bound Commitment then the Reservation → lock reconciliation run/case → revalidate → Gateway Request source-row lock → mutation. Cross-period locking never depends on period creation order; `TreeMap`-sorted distinct ids guarantee ascending order regardless of which period id is larger.
- RED tests: `M15HybridRaceMatrixIntegrationTest` (posting vs dispatch, cross-period adjustment vs explicit reopen, CLOSED run admission vs reopen) and `M15FinancialConcurrencyIntegrationTest` pass repeatedly with no deadlock flakes; `ReconciliationAdjustmentIntegrationTest` cross-period/CLOSED cases stay green.

### P1-7 Idempotent replay returns the same business response (§14)

- Root cause: replay returned `(resolutionId, null, null)`.
- RED test: `.idempotentReplayReturnsTheCommittedBusinessResponse` — same key + same canonical request replays `resolutionId/runId/caseId/requestId/resolutionType/reservationOutcome/adjustmentId` field-by-field from the committed `gateway_financial_resolution` row; same key + different body → conflict.
- `GatewayResolutionResult` now carries the full committed business response.

### P1-8 React Rules-of-Hooks bug (§19)

- Root cause: `ReconciliationCaseDetailPage` called `useQuery(runDetail)` after an early return.
- RED test: `ReconciliationPages.test.tsx > survives the loading-to-loaded transition without a Rules-of-Hooks violation` — the case promise resolves after initial render; all hooks run unconditionally (`runDetail` uses `enabled: reconciliationRunId.length > 0`).

### P1-9 Frontend/E2E completeness (§20/§21)

- Case detail now separates **整体案例操作** (whole-case: CASE_FULL adjustment modal showing the server-required difference `external − internal`, explicit allocation line with a same-org ACTIVE target picker, OPEN adjustment period) from **单条证据操作** (evidence-item: charge disposition modal, gateway no-charge/statement-adjustment modal, correction link). The gateway statement-adjustment UI never asks for an amount (server-derived); the no-charge UI requires the bounded proof code + auditable reference. Evidence-item actions never claim to resolve the case.
- Permissions: financial actions render only with `RECONCILIATION_RESOLVE` (+ `LEDGER_CORRECT` for adjustments/gateway resolution); the backend rejects independently (tested server-side).
- A real sign bug found by E2E was fixed: the UI previously submitted `differenceAmount` (internal − external) as the CASE_FULL amount; it now submits its exact negation (`external − internal`), matching the server rule.
- New `frontend/e2e/m15-hybrid-reconciliation.spec.ts` (scenario B + E end-to-end in the browser over an isolated Compose stack): confirmed two-cost statement, partial posting → aggregate case with attached evidence, whole-case vs evidence-item separation, CASE_FULL adjustment posted with explicit lines, rerun clean (no fabricated case), close, CLOSED-period banner without auto reopen, explicit governed reopen. Scenarios A/D (run-level GATEWAY_UNRESOLVED without a case; statement-backed gateway resolution) need durable Gateway request facts that no public E2E surface can create; they are proven against real MySQL by `GatewayFinancialResolutionIntegrationTest` and the race matrix (documented in the spec header).

### linkCorrection lineage (§16)

- Root cause: only same-org existence of the correction group was checked.
- RED tests: `HybridChargeDispositionScopeIntegrationTest.linkCorrection*` — correction of another provider account/currency → reject; mixed-scope correction group → reject; entries without a recognizable provider source (expense-sourced) → reject; own-scope charge correction → link succeeds with evidence.
- Implementation: every corrected Ledger entry must resolve through its preserved direct source (`source_charge_fact_id` → confirmed import lineage, `source_gateway_settlement_id` → settlement, `source_reconciliation_adjustment_id` → adjustment) to exactly one provider account+currency equal to the case scope.

### Exact/request evidence attached to the case (§17)

- Root cause: only `AGGREGATE_SCOPE` evidence got `reconciliation_case_id`; exact and request-level evidence stayed NULL.
- RED test: `GatewayFinancialResolutionIntegrationTest`/`HybridReconciliationEvidenceIntegrationTest` aggregate attachment assertions and the E2E `AGGREGATE_SCOPE` case-id assertion.
- Implementation: run finalization attaches every evidence item whose `(provider account, currency)` matches an aggregate case to that case; only evidence without a matching aggregate case stays run-level (`case_id NULL`), and no zero-amount case is fabricated.

### Meaningless concurrency assertion removed (§22) + Task 11 races (§23/§24)

- `assertThat(postings).isEqualTo(resolutionCount == 1 ? 0L : 0L)` was deleted and replaced with exact per-outcome invariants: resolution win → zero settlement postings/zero adjustments, discovery empty; late-FINAL win → the request goes through the *whole normal M13 path* (discovery finds exactly that request, settlement posts exactly one `GATEWAY_SETTLEMENT` posting), never both.
- New `M15HybridRaceMatrixIntegrationTest` (real MySQL, latches/row locks, no sleeps, direct DB counts): Provider Charge posting vs Gateway dispatch (dispatch-first blocks without disposition, never a false allow; legal scoped `DIRECT_PROVIDER_CHARGE` still posts), cross-period CASE_FULL adjustment vs explicit PERIOD_REOPEN (both converge; no auto reopen), CLOSED-period run admission vs reopen (stable state, zero financial mutation).
- Rollback matrix for the gateway resolution transaction (§25): `GatewayFinancialResolutionRollbackIntegrationTest` injects failure after adjustment insert / Ledger entry / Budget Actual / Commitment consume / Reservation transition / Audit / resolution insert / evidence insert and after the no-charge release — every run leaves zero adjustments, zero Ledger postings/entries, unchanged Budget Actual/Commitment usage, `ACTIVE` reservation, no resolution/evidence and no idempotency residue. Production injector remains a noop component.

## 1. Git

```text
Repository        : BangShou1st/AI-CostOps
Branch            : feat/m15-hybrid-reconciliation
Review anchor SHA : cc8ebe12a2d0c42b28bf4aa293543e3c7088b01b (Sol's reviewed HEAD; no rebase/reset performed)
Base SHA          : 502b8aa38a70a0afc4751097365ec6543592280f (origin/main at freeze)
Remediation       : one local commit on top of cc8ebe1 (see git log; not pushed)
Working tree      : clean at evidence time
```

## 2. Schema / migration proof

- Still exactly one M15 migration: `V23__m15_hybrid_reconciliation.sql`; V1–V22 untouched (`git diff --name-only origin/main...HEAD -- backend/src/main/resources/db/migration`).
- V23 amendments this round (M15 unmerged): `reconciliation_evidence.evidence_reference VARCHAR(256) NULL`; `chk_reconciliation_adjustment_scope_shape` requires statement-charge lineage for `GATEWAY_REQUEST` and forbids it for `CASE_FULL`; `chk_gateway_financial_resolution_type_shape` requires statement-charge lineage for `STATEMENT_ADJUSTMENT_POSTED` and forbids it for `NO_CHARGE_CONFIRMED`.
- `M15HybridSchemaIntegrationTest` (9 tests, real MySQL 8.4, Flyway V1→V23) re-proves all previous constraints plus the strengthened structural checks (including new negative inserts for the amended CHECKs).

## 3. Full local verification results (actual totals)

```text
Backend unit        (mvnw -B -DexcludedGroups=architecture,integration test)
                    : Tests run 487, Failures 0, Errors 0, Skipped 1 (pre-existing M8/M9 scale benchmark skip) — BUILD SUCCESS
Backend architecture(mvnw -B -Dgroups=architecture test)
                    : Tests run 36,  Failures 0, Errors 0, Skipped 0 — BUILD SUCCESS
                    (ModuleDependencyArchitectureTest + LedgerImmutabilityArchitectureTest included)
Backend integration (mvnw -B -Dgroups=integration verify)
                    : Tests run 977, Failures 0, Errors 0, Skipped 0 — BUILD SUCCESS, EXIT=0

Gateway unit        : Tests run 107, Failures 0, Errors 0, Skipped 0 — BUILD SUCCESS
Gateway architecture: Tests run 0 (groups=architecture finds no tagged gateway tests, same as before M15; GatewayArchitectureTest runs inside the unit phase)
Gateway integration : Tests run 80,  Failures 0, Errors 0, Skipped 0 — BUILD SUCCESS, EXIT=0 (M14 failover/safety suites included and green)

Frontend npm test --run --maxWorkers=1 : 48 files, 437 tests passed (incl. 7 reconciliation tests:
                    hooks regression + whole-case/evidence separation + permission hiding + no-amount submission)
Frontend npm run lint  : 0 problems
Frontend npm run build : success (pre-existing bundle-size warning only)

Playwright E2E (isolated Compose stack, serial) : 7 passed / 0 failed, ~25–31s
  includes new frontend/e2e/m15-hybrid-reconciliation.spec.ts

Concurrency x5 (M15FinancialConcurrencyIntegrationTest +
M15HybridRaceMatrixIntegrationTest + GatewayFinancialResolutionConcurrencyIntegrationTest,
5 consecutive full runs) : 5 x "Tests run 9, Failures 0, Errors 0" + 5 x BUILD SUCCESS, no flakes/sleeps

Docker builds (CI definition) : backend / gateway / frontend images all build successfully, EXIT=0 each
```

## 4. Financial invariants — how each is proven now

```text
no Provider/Gateway double count
  : Hybrid posting fence tests + dispatch/posting race matrix (no false allow);
    disposition creation is case-scoped so the fence cannot be bypassed
    (HybridChargeDispositionScopeIntegrationTest)
no client-defined ledger amount
  : adjustmentAmount/commitmentId removed from API/OpenAPI/frontend;
    server-derived amount tests (derive/subtract/zero-reject);
    CASE_FULL amount must equal external-internal (existing tests) and the UI
    now submits the exact negated difference (E2E)
no arbitrary commitment consumption
    : commitment locked only via bound reservation lineage; rollback test asserts
    remaining_amount/usage unchanged on failure; cross-period never consumes
no request resolution cross-run
    : run-period equality + case lineage tests (P0-3)
no late FINAL double settlement
    : resolution-vs-late-FINAL race now proves the FINAL-winner branch executes
    the full M13 path (discovery + SETTLED posting) and the resolution-winner
    branch stays discovery-empty with zero postings
no sibling implicit resolution
    : requestResolutionNeverResolvesSiblingCaseEvidence (case stays OPEN) + E2E
    assertion that an evidence-item action never calls the whole-case resolve
```

## 5. Known limitations / deviations

1. Scenarios A (run-level GATEWAY_UNRESOLVED in the browser) and D (statement-backed gateway resolution driven from the UI) of the E2E plan require durable Gateway request facts; no public E2E surface can create them, so they are covered by real-MySQL backend suites instead. Documented in the E2E spec header.
2. `ProviderCorrelationProfileRegistry` still defaults every provider to `NONE` (unchanged from the previous round; no adapter certifies a request-id field).
3. `compose.yaml` network name is now parameterized (`AICOSTOPS_NETWORK_NAME`, default unchanged to `ai-costops-network`) so a local isolated E2E stack does not share the dev stack's DNS aliases. CI behavior is unchanged.
4. The E2E local stack runs on port 18080 because the developer's dev backend occupies 8080; CI keeps 8080.
5. Hosted CI / Security were **not** rerun this round (no push allowed). They will be rerun only after independent Sol re-review passes.

## 6. Definition-of-Done checklist (updated)

```text
[x] one M6-evolved reconciliation run/case lifecycle
[x] run-level Gateway evidence without fabricating zero-amount cases
[x] external statement truth vs Provider/Gateway/Adjustment Ledger truth
[x] exact matching requires strong unique certified evidence (same provider account enforced)
[x] aggregate matching never invents request ownership or per-Charge disposition
[x] bounded, evidence-gated difference vocabulary (UNCLASSIFIED fallback)
[x] Provider Charge Hybrid posting fence prevents realtime + statement double count
[x] committed Provider Charge history stays compatible (LEGACY_POSTED backfill, replay preserved)
[x] SETTLED Gateway history never rewritten; corrections/adjustments append-only
[x] Gateway corrections preserve source_gateway_settlement_id; adjustment corrections preserve source_reconciliation_adjustment_id
[x] no-history/aggregate differences use first-class RECONCILIATION_ADJUSTMENT with explicit scope
[x] request-level resolution never resolves sibling evidence or trusts aggregate pro-rata
[x] aggregate money actions reject stale reconciliation basis (STALE_BASIS)
[x] unresolved Gateway work requires reviewed gateway_financial_resolution (bounded positive proof + auditable reference for NO_CHARGE)
[x] statement adjustments are strongly bound to one authoritative statement Charge; amount server-derived
[x] manual statement bindings are real, validated and audited (MANUAL_BINDING evidence)
[x] M15 resolution never competes with normal FINAL/PENDING/RETRYABLE_FAILED/SETTLED M13 paths
[x] committed M15 resolution prevents later late-FINAL M13 Settlement double posting (M13 path proven end-to-end)
[x] PENDING_HOLD finalized/released only by a valid financial terminal path
[x] charge dispositions are case-scoped (account/currency/period/batch/review status)
[x] OPEN and CLOSED periods reconcilable; CLOSING rejected; CLOSED never auto-reopened
[x] financial mutations force reconciliation rerun through basis staleness
[x] existing Close blockers reused; no ninth blocker
[x] reservation-bound commitment only; cross-period never consumes
[x] canonical financial lock order (periods ascending, budgets sorted, commitment/reservation, identity, source row)
[x] idempotent replay returns the committed business response field-by-field
[x] adjustmentPeriodId returned as the real decimal-string id on post and replay
[x] linkCorrection bound to correction lineage (provider account/currency)
[x] exact/request evidence attached to the matching aggregate case
[x] Case Detail Rules-of-Hooks fixed with a real loading→loaded regression
[x] frontend whole-case vs evidence-item actions complete; no client amounts for statement adjustments
[x] m15-hybrid-reconciliation.spec.ts exists and passes in the isolated Compose run
[x] meaningless concurrency assertion removed; M13 settlement path proven in the FINAL-wins branch
[x] Task 11 races covered (posting vs dispatch, adjustment vs Close/Reopen, CLOSED run vs Reopen included)
[x] Gateway resolution rollback matrix covered
[x] backend/gateway/frontend full local verification green (real totals above)
[x] local browser E2E green (7 passed)
[x] local Docker builds green (backend/gateway/frontend)
[x] V23 is the only M15 migration from the approved baseline (amended in place, V1–V22 untouched)
[ ] hosted CI / Security — intentionally not rerun (no push this round; rerun after Sol re-review)
```
