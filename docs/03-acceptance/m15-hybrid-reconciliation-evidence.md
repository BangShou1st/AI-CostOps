# M15 Hybrid Reconciliation — Acceptance Evidence

> Status: implementation complete on `feat/m15-hybrid-reconciliation`, pending independent review (GPT-5.6 Sol) and user merge instruction.
> Issue: #148 — `feat(m15): deliver hybrid reconciliation`
> Spec: `docs/superpowers/specs/2026-09-05-m15-hybrid-reconciliation-design.md`
> Plan: `docs/superpowers/plans/2026-09-05-m15-hybrid-reconciliation-plan.md`
> Base: `main@502b8aa38a70a0afc4751097365ec6543592280f`
> This document records only results that were actually executed and observed on this machine. Hosted CI/Security results are recorded separately in the PR once observed.

## 1. Git

```text
Repository        : BangShou1st/AI-CostOps
Branch            : feat/m15-hybrid-reconciliation
Base SHA          : 502b8aa38a70a0afc4751097365ec6543592280f (origin/main at freeze)
Design freeze     : 31c744c (docs), plan merge 435fa78
Implementation    : 26c8e1f → 1b852e6 (11 semantic commits, see §12)
Working tree      : clean at evidence time (excluding this document commit)
```

## 2. Schema / migration proof

- Migration: `backend/src/main/resources/db/migration/V23__m15_hybrid_reconciliation.sql` — the only M15 migration; V1–V22 untouched (`git diff --name-only origin/main...HEAD -- backend/src/main/resources/db/migration` shows only V23).
- Test class: `M15HybridSchemaIntegrationTest` (9 tests, real MySQL 8.4 Testcontainer, Flyway V1→V23):
  - M15 tables exist (`provider_charge_disposition`, `reconciliation_adjustment`, `gateway_financial_resolution`, `reconciliation_evidence`) with DECIMAL(20,8) money and CHAR(3) currency.
  - One disposition per Charge (`uq_provider_charge_disposition_org_charge`), MANUAL actor/reason constraints, LEGACY_POSTED/SYSTEM_EXACT never impersonate a member, SYSTEM_EXACT requires run lineage.
  - CASE_FULL requires case and forbids gateway_request/route_attempt; GATEWAY_REQUEST requires request + attempt (`chk_reconciliation_adjustment_scope_shape`); amount <> 0; DECIMAL(20,8) bound.
  - One resolution per Gateway Request (`uq_gateway_financial_resolution_org_request`); STATEMENT_ADJUSTMENT_POSTED requires GATEWAY_REQUEST adjustment lineage; NO_CHARGE_CONFIRMED forbids adjustment; reservation outcome is type-bound (FINALIZED/NONE vs RELEASED/NONE).
  - Evidence: unique `(org, run, evidence_key)`; run-level rows with `reconciliation_case_id NULL` are legal; bounded match/difference vocabularies.
  - Ledger forward extension: `ledger_posting.source_type` accepts `RECONCILIATION_ADJUSTMENT`; `ledger_entry.source_reconciliation_adjustment_id` participates in the direct-source XOR (`chk_ledger_entry_source_xor`, at most one of charge/expense/settlement/adjustment).
  - Legacy backfill is exactly-once idempotent: re-executing the shipped statement against seeded `PROVIDER_CHARGE` postings yields exactly one `DIRECT_PROVIDER_CHARGE / LEGACY_POSTED` row per Charge.

## 3. Hybrid internal truth and algorithm

- `ReconciliationInternalTruthAdapter` aggregates Provider-related Ledger truth by direct source lineage: Provider Charge (via confirmed import lineage), Gateway Settlement, Reconciliation Adjustment; append-only corrections contribute through the preserved direct source of the historical entry they correct; Expense entries are excluded.
- Test: `ReconciliationTruthIntegrationTest.internalTruthIncludesGatewaySettlementAdjustmentAndSourcePreservingCorrections` proves mixed Provider A (charge 10 − correction 2 = 8) and Provider B (settlement 4 + adjustment 1 = 5) aggregate per provider account/currency.
- Algorithm version switched to `M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2` (`ReconciliationAlgorithm.VERSION`). Old M6 runs remain immutable history; `OPEN_MATERIAL_RECONCILIATION` treats a stale algorithm/tolerance/basis as stale (existing rule reused, unchanged).

## 4. Matching safety

Test: `HybridReconciliationEvidenceIntegrationTest` (9 tests, certified-profile property `aicostops.reconciliation.correlation-certified-providers=GLM`):

- Certified PROVIDER_REQUEST_ID + exactly one external charge + exactly one non-PLANNED/non-SAFE current attempt → `EXACT_PROVIDER_REQUEST` evidence; no `provider_charge_disposition` is ever created by matching.
- `SAFE_NO_BILLABLE_EXECUTION` and `PLANNED` attempts never match exactly.
- Ambiguous duplicate Provider request id (two charges, one request) never auto-binds.
- Uncertified provider profile (registry default `NONE`) never matches exactly; reconciliation stays aggregate.
- Amount/time proximity alone never matches (no fuzzy fallback).
- Aggregate case carries `AGGREGATE_SCOPE` evidence with fail-closed `UNCLASSIFIED` difference label.
- UNKNOWN/no-ledger/no-external Gateway work produces run-level `GATEWAY_UNRESOLVED` evidence with `reconciliation_case_id NULL`; no zero-amount case is fabricated.
- Run admission: OPEN allowed, CLOSED allowed (evidence/read-only; zero financial mutation; period stays CLOSED), CLOSING rejected (`lockForReconciliationAdmission`).

## 5. Double-count prevention (Provider Charge posting fence)

Test: `ProviderChargeHybridFenceIntegrationTest` (5 tests):

- Hybrid overlap (same org/provider account/currency/period + non-PLANNED/non-SAFE route attempt) without disposition → V1 posting blocked (`HYBRID_RECONCILIATION_REQUIRED` conflict), zero Ledger/Budget mutation.
- Explicit `DIRECT_PROVIDER_CHARGE` disposition → normal V1 posting works.
- `RECONCILIATION_EVIDENCE` disposition → permanently non-postable through the normal V1 path.
- Non-Hybrid charge → V1 posting unchanged.
- Already-posted legacy charge remains replayable under later overlap and is not reclassified.
- Guard evaluated inside the posting transaction after the BillingPeriod lock and before posting (`ProviderChargeHybridPostingGuard` consumer-owned port + reconciliation-owned adapter; no reconciliation imports from Ledger).

## 6. Correction lineage

Test: `LedgerCorrectionIntegrationTest` (new gateway/adjustment lineage tests):

- Gateway Settlement correction (REVERSAL_ONLY and REPLACE) preserves `source_gateway_settlement_id` on reversal and replacement entries; exactly one direct source; historical target unchanged.
- Reconciliation Adjustment correction preserves `source_reconciliation_adjustment_id`.
- Provider/Expense paths unchanged (`LedgerCorrectionRollbackIntegrationTest`, `LedgerFinancialInvariantIntegrationTest`, `LedgerCorrectionIntegrityIntegrationTest`, `GatewaySettlementLedgerServiceTest` green).

## 7. CASE_FULL Reconciliation Adjustment

Tests: `ReconciliationAdjustmentIntegrationTest` (12) + `ReconciliationAdjustmentRollbackIntegrationTest`:

- Amount must equal current external − internal exactly (scale-8); zero required adjustment rejected; stale run basis rejected (`STALE_BASIS`) before any financial mutation, validated after period + reconciliation identity locks.
- Explicit allocation lines only: sum must equal amount, exactly one same-org ACTIVE target per line, no inferred split/remainder.
- OPEN case period → same-period posting only; CLOSED case period → only an explicit OPEN correction period (same-period write rejected); CLOSING rejected; historical CLOSED period never reopened.
- Budget selected by existing exact/ORG same-currency rules; signed amount mutates Actual exactly; no Budget → Ledger still posts (unbudgeted).
- CASE_FULL never consumes Commitments.
- Idempotency over shared `api_idempotency` (operation `RECONCILIATION_ADJUSTMENT`): same key replays once; different body conflicts; reservation participates in the financial transaction so rollback leaves no provisional row.
- Failure injection after each boundary (adjustment insert, ledger entry insert, budget actual, audit, case resolution) rolls the whole transaction back — no partial state, no provisional idempotency row.
- Success marks the historical case RESOLVED atomically for audit (`RECONCILIATION_ADJUSTMENT_POSTED` reason), but the financial mutation still makes the run basis stale and forces a rerun before Close.

## 8. Gateway financial resolution

Tests: `GatewayFinancialResolutionIntegrationTest` (9):

- Eligible: usage absent / INCOMPLETE / UNKNOWN, or Settlement `RECONCILIATION_REQUIRED`.
- Rejected: SAFE attempt; ordinary FINAL usage without Settlement; PENDING; RETRYABLE_FAILED; SETTLED (bounded conflict messages).
- `NO_CHARGE_CONFIRMED` works from run-level unresolved evidence with `case_id NULL`, zero Ledger mutation, effective reservation RELEASED. Reviewed positive evidence is required (bounded reason); a resolution contradicting a RECONCILIATION_REQUIRED Settlement is rejected.
- `STATEMENT_ADJUSTMENT_POSTED` posts a first-class `GATEWAY_REQUEST` `reconciliation_adjustment` + `RECONCILIATION_ADJUSTMENT` Ledger posting/entry (entry carries `source_reconciliation_adjustment_id`), mutates Budget Actual, FINALIZES an effective reservation; historical `RECONCILIATION_REQUIRED` Settlement is not rewritten.
- Resolution never marks sibling case evidence resolved (case stays OPEN).
- Gateway Request source row is locked (`FOR UPDATE`) as the serialization point; Gateway request/usage/settlement facts are never mutated.
- M13 defense in depth: `GatewaySettlementDiscoveryService`/mapper exclude resolved requests (also in the org-work scan); `GatewaySettlementService.settle` revalidates `GATEWAY_FINANCIAL_RESOLUTION_EXISTS` after locks before any financial write. Late FINAL usage after a committed resolution persists as evidence but discovery yields nothing and settle refuses (`RECONCILIATION_REQUIRED`).
- Reservation authority: only `finalizeForSettlement` and the new reviewed `releaseForReconciliation` transitions exist (no create/resize/retarget); enforced by `GatewayReservationSettlementMapperTest`.

## 9. Close integration

Tests: `GatewayFinancialWorkCloseIntegrationTest` (20, incl. 5 new M15 cases) + coordinator regression:

- Valid immutable resolution (NO_CHARGE_CONFIRMED + RELEASED, or STATEMENT_ADJUSTMENT_POSTED + FINALIZED) terminates only that request's `PENDING_GATEWAY_FINANCIAL_WORK` contribution; sibling unresolved request still blocks.
- Resolution with a contradicting still-effective reservation (recorded RELEASED, actual ACTIVE) does not clear the blocker.
- No ninth Close blocker: blocker set remains exactly the frozen eight; no `HYBRID_RECONCILIATION` blocker added.
- Financial mutation → basis staleness → `FINANCIAL_BASIS_CHANGED` blocks Close; rerun produces a fresh basis and (after explicit case resolution) Close may pass (`PeriodCloseCoordinatorIntegrationTest.realCloseWaitsForSettlementAndThenEvaluatesTerminalGatewayTruth`, updated for the M15 truth model).

## 10. Real-MySQL concurrency / fault injection

Test: `M15FinancialConcurrencyIntegrationTest` (4 races, deterministic latches + row locks, no sleeps), repeated 5 consecutive runs — all green:

```text
Race 1  duplicate CASE_FULL adjustment commands (same idempotency key, 2 threads)
        invariant: one adjustment row / one posting / one Actual mutation; both commands converge to the same adjustmentId
Race 2  CASE_FULL adjustment vs Close
        invariant: either the adjustment posts and Close cannot close on stale basis, or Close wins and the adjustment cannot write; never both; ledger entry count stays exact
Race 3  M15 resolution vs normal M13 Settlement (PENDING settlement)
        invariant: resolution rejected ("PENDING"), settlement SETTLED, zero resolutions/adjustments, exactly one settlement posting
Race 4  M15 resolution vs late FINAL usage publication (same request-row serialization point)
        invariant: either FINAL wins and resolution is rejected, or resolution wins and late FINAL cannot create a normal Settlement (discovery empty); no duplicate financial posting in either ordering
```

Rollback/failure-injection matrix: `ReconciliationAdjustmentRollbackIntegrationTest` (after adjustment insert / ledger entry insert / budget actual / audit / case resolution) — full rollback, no partial terminal state.

## 11. Full verification results

Recorded from the actual final runs (Surefire/Failsafe totals):

```text
Backend  unit (mvnw -B test)          : Tests run 523, Failures 0, Errors 0, Skipped 1  (BUILD SUCCESS)
Backend  verify (mvnw -B verify)      : BUILD SUCCESS, EXIT=0 (unit 523 + failsafe integration 968, all green)
Gateway  unit (mvnw -B test)          : Tests run 129, Failures 0, Errors 0, Skipped 0  (BUILD SUCCESS)
Gateway  verify (mvnw -B verify)      : BUILD SUCCESS, EXIT=0 (failsafe integration 80, all green)
Frontend npm test --run --maxWorkers=1: 48 files, 434 tests passed
Frontend npm run lint                 : 0 problems
Frontend npm run build                : success (pre-existing bundle-size warning only, not a failure per plan)
Architecture                          : ModuleDependencyArchitectureTest + LedgerImmutabilityArchitectureTest run inside backend `test`; GatewayArchitectureTest inside gateway `test` — all green
```

### 11.1 Backend / Gateway integration totals

```text
Backend  failsafe integration : Tests run 968, Failures 0, Errors 0, Skipped 0  (BUILD SUCCESS, EXIT=0, 15:24 min)
Gateway  failsafe integration : Tests run 80,  Failures 0, Errors 0, Skipped 0  (BUILD SUCCESS, EXIT=0)
```

The 1 skipped backend unit test is the pre-existing M8/M9 scale benchmark skip
(unrelated to M15; it was skipped before the M15 branch as well).

## 12. Commits on this branch

```text
435fa78 docs: add M15 hybrid reconciliation implementation plan
26c8e1f feat(m15): add hybrid reconciliation schema
b9838e7 feat(m15): extend reconciliation truth to hybrid ledger sources
3416bc9 feat(m15): generate hybrid evidence and admit OPEN/CLOSED reconciliation
05cbb7e feat(m15): prevent provider gateway double counting via hybrid posting fence
7df12df feat(m15): preserve correction direct source lineage for gateway settlements and adjustments
b144f19 feat(m15): add CASE_FULL reconciliation adjustments
bd4df1c feat(m15): resolve gateway financial uncertainty and guard m13 settlement
18a5aac feat(m15): expose hybrid reconciliation workflow api
1b5f908 feat(m15): integrate gateway financial resolution into close blockers
1b852e6 test(m15): prove reconciliation financial races on real mysql
bb5adfa feat(m15): expose hybrid reconciliation workflow in frontend and ledger lineage
```

(plus the evidence/documentation commit that carries this file)

## 13. Known limitations / deviations

1. `PeriodCloseCoordinatorIntegrationTest.realCloseWaitsForSettlementAndThenEvaluatesTerminalGatewayTruth` was updated: under the M15-approved design the Gateway Settlement Ledger is part of reconciliation internal truth (spec §13/§75), so a settlement committed after the run makes the basis stale; the test now proves Close blocks with `FINANCIAL_BASIS_CHANGED`, reruns, explicitly explains the resulting MISSING_EXTERNAL case (ACCEPT_EXPLAINED_DIFFERENCE), and then closes. This is a mandated semantic evolution, not a weakening.
2. `GatewayReservationSettlementMapperTest` was updated to the M15 narrow authority set (adds the reviewed `releaseForReconciliation` transition mandated by the plan; still forbids create/resize/retarget/insert).
3. Difference classification is deliberately fail-closed: only the stored duplicate-review state could prove `DUPLICATE_EXTERNAL_CHARGE`, and it is not yet wired into automatic labels — every automatic aggregate label is `UNCLASSIFIED` pending reviewed evidence, exactly as the spec requires ("如果证据不足:UNCLASSIFIED。不要猜。").
4. `ProviderCorrelationProfileRegistry` defaults every provider to `NONE` (no current import adapter certifies a request-id field); exact correlation activates only via the bounded configuration property. No adapter was forced to fabricate a key.
5. Hosted CI / CodeQL / Trivy results are not asserted here; they will be recorded from the PR checks after push.

## 14. Definition-of-Done checklist

```text
[x] one M6-evolved reconciliation run/case lifecycle
[x] run-level Gateway evidence without fabricating zero-amount cases
[x] external statement truth vs Provider/Gateway/Adjustment Ledger truth
[x] exact matching requires strong unique certified evidence
[x] aggregate matching never invents request ownership or per-Charge disposition
[x] bounded, evidence-gated difference vocabulary (UNCLASSIFIED fallback)
[x] Provider Charge Hybrid posting fence prevents realtime + statement double count
[x] committed Provider Charge history stays compatible (LEGACY_POSTED backfill, replay preserved)
[x] SETTLED Gateway history never rewritten; corrections/adjustments append-only
[x] Gateway corrections preserve source_gateway_settlement_id; adjustment corrections preserve source_reconciliation_adjustment_id
[x] no-history/aggregate differences use first-class RECONCILIATION_ADJUSTMENT with explicit scope
[x] request-level resolution never resolves sibling evidence or trusts aggregate pro-rata
[x] aggregate money actions reject stale reconciliation basis (STALE_BASIS)
[x] unresolved Gateway work requires reviewed gateway_financial_resolution
[x] M15 resolution never competes with normal FINAL/PENDING/RETRYABLE_FAILED/SETTLED M13 paths
[x] committed M15 resolution prevents later late-FINAL M13 Settlement double posting
[x] PENDING_HOLD finalized/released only by a valid financial terminal path
[x] OPEN and CLOSED periods reconcilable; CLOSING rejected; CLOSED never auto-reopened
[x] financial mutations force reconciliation rerun through basis staleness
[x] existing Close blockers reused; no ninth blocker
[x] idempotency/atomicity/real-MySQL races proven (incl. 5x repeated concurrency suite)
[x] frontend exposes Gateway/Adjustment lineage without secrets (decimal-string money preserved)
[x] V23 is the only M15 migration from the approved baseline
```
