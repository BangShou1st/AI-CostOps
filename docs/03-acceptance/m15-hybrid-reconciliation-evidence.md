# M15 Hybrid Reconciliation — Acceptance Evidence

> Status: **Sol Round 2 remediation (round 3 local fixes) complete on `feat/m15-hybrid-reconciliation`**, awaiting GPT-5.6 Sol Round 3 independent review and user merge instruction. Hosted CI will be rerun only after that review passes and the user publishes the branch.
> Issue: #148 — `feat(m15): deliver hybrid reconciliation` (PR #149)
> Spec: `docs/superpowers/specs/2026-09-05-m15-hybrid-reconciliation-design.md`
> Plan: `docs/superpowers/plans/2026-09-05-m15-hybrid-reconciliation-plan.md`
> Base: `main@502b8aa38a70a0afc4751097365ec6543592280f`
> Review anchor reviewed by Sol: `6296e6ec74d111b6bf3d96ad13be162b1f7f56cf` (round 2 remediation HEAD)
> This document records only results actually executed and observed on this machine. Nothing below claims hosted CI results for the current round (no push was performed).

## 0R. Round 3 — Sol Round 2 findings: charge financial ownership, exact identity, evidence freshness, run-level workflow

All Round 2 findings were fixed root-cause-first with RED tests observed failing on `6296e6e` before the production change (two RED waves: wave 1 captured the binding-source/exact-semantics findings, wave 2 — after the server-derived validation landed — captured the ownership findings that had been masked by the old client validation). The historical round-2 remediation record below (## 0.) is preserved unchanged.

### P0-1 Charge financial ownership: Provider posting vs Gateway statement resolution (BLOCKER)

- Root cause: `GatewayFinancialResolutionService` never inspected the Charge's financial fate. A Charge already posted as a normal `PROVIDER_CHARGE` Ledger source (or carrying a `DIRECT_PROVIDER_CHARGE` disposition) could later be bound by a statement resolution, double-counting the real cost (Ledger 10 + adjustment 10 = 20). Conversely, a statement resolution left **no** `provider_charge_disposition` at all, so the only thing blocking a later normal posting was the conservative Hybrid-overlap probe, not a durable ownership claim.
- RED tests (observed failing): `M15FinancialOwnershipIntegrationTest.providerPostedChargeCannotBeStatementResolved`, `.directDispositionChargeCannotBeStatementResolved` (resolution succeeded on a DIRECT charge), `.statementResolvedChargeBecomesReconciliationEvidenceAndCanNeverBecomeDirect` (no disposition row was written), `.postingGuardRejectsChargeConsumedByGatewayFinancialResolution` (guard blocked only via incidental `BLOCKED_HYBRID_OVERLAP`; with the attempt demoted to PLANNED it allowed the posting), `.sameChargeTwoRequestsConcurrentResolutionProducesExactlyOneOwner`, `.providerPostingAndStatementResolutionRaceNeverDoubleCounts` (both flows could win).
- Implementation:
  - `HybridReconciliationMapper.lockChargeForFinancialOwnership` — the `charge_fact` row is now the shared ownership serialization point. Every flow locks it as the LAST financial lock in the canonical order (Period(s) → Budget(s) → Commitment → Reservation → run/case → Gateway Request → **Charge**), matching the posting service's existing `charges.lockAndRequirePostable` position; no lock-order inversion exists.
  - Under the lock the resolution revalidates (all with `FOR UPDATE` current reads): posted `PROVIDER_CHARGE` postings for the Charge, the existing disposition (DIRECT/LEGACY → reject; RECONCILIATION_EVIDENCE → compatible only within the same run and the same decision source), adjustments and resolutions already consuming the Charge, conflicting manual bindings, and the full confirmed-import scope.
  - The statement resolution now **atomically** writes `provider_charge_disposition(disposition='RECONCILIATION_EVIDENCE', decision_source='SYSTEM_EXACT'|'MANUAL', run, optional case, member for MANUAL)` in the same transaction as the adjustment/Ledger/Budget/reservation/audit/resolution; any failure rolls the ownership claim back (new failpoint `CHARGE_DISPOSITION_INSERTED` proven by `GatewayFinancialResolutionRollbackIntegrationTest.failureAfterChargeOwnershipDispositionRollsEverythingBack`, and `assertZeroResidue` now also asserts zero dispositions for every failpoint).
  - `decideChargeDisposition` acquires the same Charge lock and rejects a `DIRECT_PROVIDER_CHARGE` decision when a gateway financial resolution, a GATEWAY_REQUEST adjustment or an incompatible binding already consumes the Charge.
  - `ProviderChargeHybridPostingGuardAdapter` also blocks when any `gateway_financial_resolution` references the Charge (defense in depth independent of the disposition row).
  - V23 (amended in place, M15 unmerged): `uq_gateway_financial_resolution_org_charge UNIQUE (org_id, statement_charge_fact_id)` — the database-level last line; `NO_CHARGE_CONFIRMED` rows keep NULL and never block each other. Proven by `M15HybridSchemaIntegrationTest.statementChargeOwnershipIsUniqueAcrossGatewayResolutions` plus the full Flyway V1→V23 run.
  - The concurrency loser returns a bounded conflict (`DuplicateKeyException` on the ownership claim is translated; the unique-violation on the resolution insert was already translated).
- GREEN: both terminal states are proven exclusively — `PROVIDER_DIRECT` (1 PROVIDER_CHARGE posting, 0 adjustment/resolution, resolution receives a conflict) and `RECONCILIATION` (1 RECONCILIATION_ADJUSTMENT posting, 1 resolution, disposition RECONCILIATION_EVIDENCE, posting blocked). No third state; exactly one Ledger entry either way.

### P0-2 Same statement Charge, two concurrent Gateway requests (BLOCKER)

- Root cause: exclusivity was enforced only by serial snapshot checks, so two concurrent resolutions of the same Charge for two different requests could both pass.
- RED test: `.sameChargeTwoRequestsConcurrentResolutionProducesExactlyOneOwner` (real MySQL, latch-started; pre-fix the DB had no defense at all).
- GREEN (10 consecutive full race-suite runs): exactly one succeeds, exactly one bounded conflict; `gateway_financial_resolution = 1`, `reconciliation_adjustment = 1`, one `RECONCILIATION_ADJUSTMENT` posting, exactly one `RECONCILIATION_EVIDENCE` disposition, one `GATEWAY_FINANCIAL_RESOLVED` audit event, Budget Actual moved exactly once (2.00).

### P1-3 Exact Provider request id is truly case-sensitive (P1)

- Root cause: the exact-correlation JOIN and GROUP BY ran under the table collation `utf8mb4_0900_ai_ci`, so `ReqAbc` and `reqabc` cross-matched and case-distinct ids were folded into one ambiguous group.
- RED tests: `HybridReconciliationEvidenceIntegrationTest.exactRequestIdComparisonIsCaseSensitive` (charge key `Prov-Req-Case-1` vs request id `prov-req-case-1` produced exact evidence; MUST be 0); `.caseDistinctProviderRequestIdsAreNeverFoldedByGrouping` (two case-distinct pairs were folded into one ambiguous group; MUST produce exactly two correct pairings).
- Implementation: `BINARY ra.provider_request_id = BINARY rpr.provider_record_key` in the JOIN and `GROUP BY BINARY rpr.provider_record_key, ra.provider_account_id` — comparison and grouping share the same binary semantics; `ReqAbc`/`reqabc` are distinct identities each pairing exactly once, while true duplicates remain ambiguous and fail closed.

### P1-4 Correlation certification is Provider + durable source schema (P1)

- Root cause: `correlation-certified-providers=GLM` certified every import of a provider, but `raw_provider_record.provider_record_key` is generic metadata whose meaning can differ per statement schema.
- RED test: `.uncertifiedSourceSchemaNeverMatchesExactlyEvenForCertifiedProvider` (a GLM charge from an uncertified parser/schema matched exactly pre-fix).
- Implementation: `ProviderCorrelationProfileRegistry` is keyed by `PROVIDER:SOURCE_TYPE:PARSER_VERSION` (`aicostops.reconciliation.correlation-certified-profiles`); the exact candidate SQL carries `cf.provider_code`, `ib.source_type`, `ia.parser_version`, and both the evidence generator and the resolution-side revalidation consult the full profile identity. Every unlisted provider/schema (and empty configuration) resolves to `NONE`.

### P1-5 Resolution must be grounded in this run's current-attempt evidence (P1)

- Root cause: `countUnresolvedEvidenceForRequest` counted evidence rows by request only, and the statement manual path did not consult run evidence at all — a request created after the run, or one that failed over to a new route attempt after the run, could still be resolved on stale review state.
- RED tests: `M15FinancialOwnershipIntegrationTest.requestCreatedAfterRunCannotBindStatementManually` (statement manual resolution succeeded pre-fix), `.failoverToNewAttemptMakesOldRunEvidenceStale` (both NO_CHARGE and STATEMENT succeeded on attempt-A evidence for a request now on attempt B).
- Implementation: `selectUnresolvedEvidenceBindingsForRequest` returns a bounded projection (evidence id, match kind, request, route attempt, provider account, currency, charge); every resolution requires at least one `GATEWAY_UNRESOLVED` row whose attempt/provider account/currency equal the request's current lineage (the exact path additionally revalidates its own evidence lineage); otherwise a bounded stale-evidence conflict demands a reconciliation rerun.

### P2-6 Server-derived binding source + automatic exact binding (P2)

- Root cause: the client declared `reasonCode=EXACT_PROVIDER_REQUEST|MANUAL_BINDING` — the financial truth of the binding was client-asserted — and had to repeat `statementChargeFactId` even when the run already held unique exact evidence.
- RED tests: `M15FinancialOwnershipIntegrationTest.clientCannotDeclareTheBindingClassification` (both classification codes were accepted pre-fix), `.exactStatementResolutionClaimsChargeAsSystemExactEvidence` (omitting the charge id was rejected pre-fix).
- Implementation: the classification is fully server-derived (`SYSTEM_EXACT` with a member-less disposition when unique exact evidence binds, `MANUAL` otherwise); `EXACT_PROVIDER_REQUEST`/`MANUAL_BINDING` are rejected as client values; on the exact path `statementChargeFactId` may be omitted and a supplied value is only an equality assertion. The client `reasonCode` is a bounded business reason. OpenAPI, frontend types, Case Detail and Run Detail UI were updated in the same change.

### P2-7 Run-level case_id=NULL Gateway work is operable (P2)

- Root cause: Run Detail listed 未决网关财务工作（运行级） as an inert alert with no action.
- Implementation: the run-level card is now an evidence table; each `GATEWAY_UNRESOLVED` case-null row offers 处理 (gated by `RECONCILIATION_RESOLVE` + `LEDGER_CORRECT`) opening a shared `GatewayResolutionModal` supporting `NO_CHARGE_CONFIRMED` (bounded positive proof code + auditable reference, no amount) and `STATEMENT_ADJUSTMENT_POSTED` (server-derived amount, exact binding auto-derived and displayed as 精确请求关联, manual path requires the charge id). Case Detail now uses the same component and no longer fabricates `MANUAL_BINDING` classifications.
- Component tests: `ReconciliationPages.test.tsx` — run-level case-null resolution submits `caseId` absent / `requestId` correct / no amount / positive-evidence fields; the exact statement path never re-declares the charge or a classification; the manual statement path requires the charge id; permission gating hides the action.

### Adjacent-path sweep (round-3 mandate)

All `statement_charge_fact_id` / disposition / `PROVIDER_CHARGE` mutation surfaces were re-audited: the only writers of `provider_charge_disposition` are the disposition command (now ownership-locked) and the statement resolution (ownership-locked, atomic); the only `PROVIDER_CHARGE` posting writer is `ProviderChargePostingService` (charge row lock + guard); `LedgerCorrectionService` only appends corrections that preserve direct-source lineage; duplicate-review and allocation only mutate `charge_fact` review/allocation columns, which are revalidated by the scope checks at decision time. No unguarded path remains.

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

## 3. Full local verification results (actual totals, round 3)

```text
Backend unit        (mvnw -B -DexcludedGroups=architecture,integration test)
                    : Tests run 487, Failures 0, Errors 0, Skipped 1 (pre-existing M8/M9 scale benchmark skip) — BUILD SUCCESS
Backend architecture(mvnw -B -Dgroups=architecture test)
                    : Tests run 36,  Failures 0, Errors 0, Skipped 0 — BUILD SUCCESS
Backend integration (mvnw -B -Dgroups=integration verify)
                    : Tests run 992, Failures 0, Errors 0, Skipped 0 — BUILD SUCCESS, EXIT=0
                      (includes M15FinancialOwnershipIntegrationTest 10 tests,
                       M15HybridSchemaIntegrationTest 10 tests incl. the new
                       unique statement-charge ownership proof)

Gateway unit        : Tests run 107, Failures 0, Errors 0, Skipped 0 — BUILD SUCCESS
Gateway architecture: Tests run 0 (groups=architecture finds no tagged gateway tests, same as before M15)
Gateway integration : Tests run 79,  Failures 0, Errors 0, Skipped 0 — BUILD SUCCESS, EXIT=0 (M14 failover/safety suites green)

Frontend npm test --run --maxWorkers=1 : 48 files, 440 tests passed
                      (incl. 10 reconciliation tests: run-level case-null resolution,
                       exact/manual statement semantics, no-amount submission,
                       permission gating, hooks regression)
Frontend npm run lint  : 0 problems
Frontend npm run build : success (pre-existing bundle-size warning only)

Playwright E2E (freshly rebuilt isolated Compose stack, serial) : 7 passed / 0 failed, 36.6s
  includes frontend/e2e/m15-hybrid-reconciliation.spec.ts

Ownership/concurrency races x10 (M15FinancialOwnershipIntegrationTest +
M15FinancialConcurrencyIntegrationTest + M15HybridRaceMatrixIntegrationTest,
10 consecutive full runs) : 10 x "Tests run 19, Failures 0, Errors 0" +
10 x BUILD SUCCESS, no flakes, no sleeps, no deadlocks

Docker builds (CI definition) : ai-costops-backend:ci / ai-costops-frontend:ci /
ai-costops-gateway:ci all built successfully, EXIT=0 each
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

1. Scenarios A (run-level GATEWAY_UNRESOLVED in the browser) and D (statement-backed gateway resolution driven from the UI) of the E2E plan require durable Gateway request facts; no public E2E surface can create them, so they are covered by real-MySQL backend suites and by frontend component tests instead (the Run Detail case-null workflow is proven at the component level with the exact request payload asserted). Documented in the E2E spec header.
2. No production adapter ships a certified correlation profile: the registry defaults every provider/source-schema to `NONE` until an operator explicitly certifies `PROVIDER:SOURCE_TYPE:PARSER_VERSION` entries.
3. `compose.yaml` network name is parameterized (`AICOSTOPS_NETWORK_NAME`, default unchanged) so a local isolated E2E stack does not share the dev stack's DNS aliases. CI behavior is unchanged.
4. The E2E local stack runs on port 18080 because the developer's dev backend occupies 8080; CI keeps 8080.
5. Hosted CI / Security were **not** rerun this round (no push allowed). They will be rerun only after Sol Round 3 review passes and the user publishes the branch.
6. `GatewayFinancialResolutionRollbackIntegrationTest` asserts the new `CHARGE_DISPOSITION_INSERTED` failpoint on the manual path only; the exact path reuses the same atomic transaction and failpoint site (verified by shared code path, not by a separate test).

## 6. Definition-of-Done checklist (updated)

```text
[x] one M6-evolved reconciliation run/case lifecycle
[x] run-level Gateway evidence without fabricating zero-amount cases
[x] external statement truth vs Provider/Gateway/Adjustment Ledger truth
[x] exact matching requires strong unique certified evidence (same provider account; case-sensitive ids; provider + source-schema certification)
[x] aggregate matching never invents request ownership or per-Charge disposition
[x] bounded, evidence-gated difference vocabulary (UNCLASSIFIED fallback)
[x] Provider Charge Hybrid posting fence prevents realtime + statement double count
[x] charge financial ownership: one Charge = DIRECT posting XOR RECONCILIATION_EVIDENCE (charge row lock + atomic disposition + posting guard + DB unique, all four layers)
[x] statement-resolved Charge can never become DIRECT nor normal-post; provider-posted/DIRECT Charge can never be statement-resolved
[x] same statement Charge cannot back two Gateway resolutions (serially or concurrently; DB unique defense)
[x] resolution grounded in this run's evidence bound to the current route attempt (request-after-run and failover stale-evidence rejection)
[x] binding classification server-derived (SYSTEM_EXACT/MANUAL); exact binding auto-derived; client cannot lie
[x] run-level case_id=NULL Gateway work operable from Run Detail with shared resolution modal
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
[x] charge dispositions are case-scoped (account/currency/period/batch/review status) and ownership-locked
[x] OPEN and CLOSED periods reconcilable; CLOSING rejected; CLOSED never auto-reopened
[x] financial mutations force reconciliation rerun through basis staleness
[x] existing Close blockers reused; no ninth blocker
[x] reservation-bound commitment only; cross-period never consumes
[x] canonical financial lock order (periods ascending, budgets sorted, commitment/reservation, identity, source row, charge ownership last)
[x] idempotent replay returns the committed business response field-by-field
[x] adjustmentPeriodId returned as the real decimal-string id on post and replay
[x] linkCorrection bound to correction lineage (provider account/currency)
[x] exact/request evidence attached to the matching aggregate case
[x] Case Detail Rules-of-Hooks fixed with a real loading→loaded regression
[x] frontend whole-case vs evidence-item actions complete; no client amounts for statement adjustments
[x] m15-hybrid-reconciliation.spec.ts exists and passes in the isolated Compose run
[x] ownership rollback matrix includes the disposition claim failpoint
[x] ownership/concurrency races repeated x10 green (no sleeps, no deadlock flakes)
[x] backend/gateway/frontend full local verification green (real totals above)
[x] local browser E2E green (7 passed, freshly rebuilt stack)
[x] local Docker builds green (backend/gateway/frontend)
[x] V23 is the only M15 migration from the approved baseline (amended in place twice while unmerged; V1–V22 untouched)
[ ] hosted CI / Security — intentionally not rerun (no push this round; rerun after Sol Round 3 review and user publish)
```
