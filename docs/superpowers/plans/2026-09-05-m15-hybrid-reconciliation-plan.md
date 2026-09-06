# M15 Hybrid Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:test-driven-development for every production behavior change, superpowers:systematic-debugging for any unexpected failure, and superpowers:verification-before-completion before claiming a task or milestone complete. Execute task-by-task; do not redesign the approved spec while implementing.

**Goal:** Deliver one complete M15 that evolves M6 reconciliation into the canonical hybrid Provider-statement vs Gateway/Ledger reconciliation system, prevents all known double-count paths, resolves reviewed Gateway financial uncertainty append-only, and preserves existing Close, routing, reservation, Settlement and immutable-Ledger invariants.

**Architecture:** Keep `reconciliation_run` / `reconciliation_case` and existing Close blockers canonical. External truth remains confirmed Provider statement `charge_fact`; internal truth becomes Provider-related immutable Ledger entries from V1 Provider Charge, V2 Gateway Settlement, M15 Reconciliation Adjustment, and their source-preserving corrections. Exact request matching is evidence-gated; otherwise reconciliation stays aggregate. Provider Charge posting and M13 Settlement each gain terminal guards through consumer-owned ports/adapters so application modules do not create cyclic dependencies on the reconciliation package. All financial writes start from BillingPeriod locking, use shared MySQL truth, are idempotent and append-only, and are proved with real MySQL races/fault injection.

**Tech Stack:** Java 21, Spring Boot 4.1, MyBatis, MySQL 8.4, Flyway, JUnit 5/Testcontainers, React 19, TypeScript 6, Ant Design 6, TanStack Query 5, Vitest, Playwright.

**Issue:** #148 — `feat(m15): deliver hybrid reconciliation`  
**Branch:** `feat/m15-hybrid-reconciliation`  
**Baseline:** `main@502b8aa38a70a0afc4751097365ec6543592280f`  
**Spec:** `docs/superpowers/specs/2026-09-05-m15-hybrid-reconciliation-design.md`

## Global Constraints

- Before coding, fetch `origin/main` and verify it is still compatible with the frozen baseline. If `main` moved, inspect the diff first; never silently implement against a stale migration/API reality.
- At the approved baseline V22 is highest. Never edit V1-V22. M15 uses only `V23__m15_hybrid_reconciliation.sql` unless a newly verified main has consumed V23.
- One M15 branch, one runtime PR, one implementation train. Task commits are checkpoints, not separate milestones.
- Do not merge during implementation/review. Merge only after independent Sol review and explicit user instruction.
- No fuzzy matching, nearest-time/amount matching, inferred request split, pro-rata request allocation, or ML/probabilistic financial matching.
- Aggregate matching must never create an automatic per-Charge disposition. Only exact certified correlation may produce `SYSTEM_EXACT`.
- `SETTLED` Gateway Settlement and historical POSTED Ledger entries are immutable. Corrections/adjustments are append-only.
- Never let the same real cost become both a full `PROVIDER_CHARGE` posting and covered Gateway financial truth.
- Never let an M15-resolved Gateway Request later receive a normal M13 Settlement.
- M15 Gateway resolution is forbidden for ordinary FINAL/no-Settlement, PENDING, RETRYABLE_FAILED and SETTLED normal M13 paths.
- Statement absence alone never proves zero cost.
- `SAFE_NO_BILLABLE_EXECUTION` is never a billable reconciliation candidate.
- No FX. Currency mismatch is unresolved/explicit, not converted.
- Money is BigDecimal / exact DECIMAL only. Existing M6 difference is `internal - external`; full adjustment is `external - internal`.
- OPEN and CLOSED periods may be reconciled; CLOSING may not. CLOSED reconciliation never reopens automatically.
- Any aggregate money action must reject a stale reconciliation basis.
- One request/evidence resolution never implicitly resolves sibling evidence in the same provider/currency case.
- Financial lock order begins with BillingPeriod(s), then sorted Budgets, optional Commitment, Reservation, reconciliation/source identity, Gateway Request source row when applicable, then Ledger uniqueness/insertion.
- Provider I/O and Redis operations never run inside Backend financial transactions.
- No new Provider adapters, no routing/failover redesign, no prompt/completion/reasoning persistence.
- Reuse current permissions. Do not invent a new M15 permission family.
- Reuse `api_idempotency`; do not create another idempotency table.
- All new audit/metrics data is bounded and secret-free; IDs never become metric labels.
- Preserve existing architecture direction. In particular, do not solve the posting fence by making Ledger application code depend directly on reconciliation application/domain classes.

---

## File / Responsibility Map

### Reconciliation core

- `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationAlgorithm.java` — switch canonical algorithm to `M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2`.
- `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationRunService.java` — OPEN/CLOSED run admission, hybrid evidence snapshot/finalization.
- `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationMatchEngine.java` — keep aggregate arithmetic; no request heuristic.
- `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationCaseService.java` — whole-case actions only where valid; evidence-item actions do not close siblings.
- `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationQueryService.java` — run/case/evidence/read projections.
- Create `backend/src/main/java/com/aicostops/reconciliation/application/HybridReconciliationEvidenceService.java` — deterministic exact/aggregate/unresolved evidence generation.
- Create `backend/src/main/java/com/aicostops/reconciliation/application/ProviderCorrelationProfileRegistry.java` — bounded provider/schema key semantics, default NONE.
- Create `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationAdjustmentService.java` — CASE_FULL reviewed financial action.
- Create `backend/src/main/java/com/aicostops/reconciliation/application/GatewayFinancialResolutionService.java` — request-level financial terminal action.
- Create `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationIdempotency.java` or extract a shared idempotency abstraction backed by existing `api_idempotency`.
- Extend `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationAuditPort.java` and `backend/src/main/java/com/aicostops/reconciliation/infrastructure/AuditReconciliationAdapter.java`.
- Create bounded domain records/enums under `backend/src/main/java/com/aicostops/reconciliation/domain/` for evidence, difference kind, charge disposition, adjustment scope and Gateway financial resolution.
- Create `backend/src/main/java/com/aicostops/reconciliation/infrastructure/HybridReconciliationMapper.java` for M15 persistence, exact lineage queries and Gateway Request source locks.
- Modify `backend/src/main/java/com/aicostops/reconciliation/infrastructure/ReconciliationMapper.java` only for run/case persistence/query evolution that belongs to the canonical M6 tables.

### Existing external/internal truth

- Modify `backend/src/main/java/com/aicostops/cost/infrastructure/ReconciliationExternalTruthAdapter.java` only if a projection extension is needed; preserve confirmed Charge aggregate semantics.
- Modify `backend/src/main/java/com/aicostops/ledger/infrastructure/ReconciliationInternalTruthAdapter.java` to aggregate direct Provider Charge, Gateway Settlement and Reconciliation Adjustment source lineage plus corrections.
- Keep `backend/src/main/java/com/aicostops/cost/application/ReconciliationExternalTruthPort.java` and `backend/src/main/java/com/aicostops/ledger/application/ReconciliationInternalTruthPort.java` as the M6 aggregate seams unless tests prove a small backward-compatible projection extension is required.

### Provider Charge posting fence

- Create `backend/src/main/java/com/aicostops/ledger/application/ProviderChargeHybridPostingGuard.java` as the consumer-owned Ledger seam; it returns bounded ALLOW/DIRECT_REQUIRED/RECONCILIATION_EVIDENCE outcomes without importing reconciliation types.
- Create `backend/src/main/java/com/aicostops/reconciliation/infrastructure/ProviderChargeHybridPostingGuardAdapter.java` implementing that seam with M15 tables + Gateway overlap SQL.
- Modify `backend/src/main/java/com/aicostops/ledger/application/ProviderChargePostingService.java` to revalidate the guard after BillingPeriod lock and before posting.
- Modify `backend/src/main/java/com/aicostops/cost/infrastructure/ChargePostingMapper.java` / `ChargePostingAdapter.java` only as needed for stable source identity, not to duplicate M15 policy logic.

### Ledger / correction / adjustment

- Modify `backend/src/main/java/com/aicostops/ledger/infrastructure/LedgerPostingMapper.java` to preserve all direct-source lineage and insert `RECONCILIATION_ADJUSTMENT` postings/entries.
- Modify `backend/src/main/java/com/aicostops/ledger/application/LedgerCorrectionService.java` so reversal/replacement copies `source_gateway_settlement_id` and `source_reconciliation_adjustment_id` exactly.
- Create `backend/src/main/java/com/aicostops/ledger/application/ReconciliationAdjustmentLedgerPort.java` — caller-transaction-friendly append-only Ledger mutation seam using neutral commands.
- Create `backend/src/main/java/com/aicostops/ledger/infrastructure/ReconciliationAdjustmentLedgerAdapter.java` — posting key/entry insertion and result lookup.
- Modify Ledger query/read/API classes to expose Gateway Settlement and Reconciliation Adjustment lineage.

### Gateway Settlement terminal guard

- Create `backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewayFinancialTerminalPort.java` — consumer-owned read/revalidation seam saying whether an M15 terminal resolution exists.
- Create `backend/src/main/java/com/aicostops/gatewaysettlement/infrastructure/GatewayFinancialTerminalAdapter.java` — query `gateway_financial_resolution` without Java dependency on reconciliation package.
- Modify `backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewaySettlementDiscoveryService.java` and its mapper query so resolved requests are not discovered.
- Modify `backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewaySettlementService.java` to revalidate no M15 terminal resolution before Ledger posting.
- Extend the existing narrow Backend reservation authority with reviewed `RELEASED` / `FINALIZED` transitions needed by M15; do not add create/resize/retarget semantics.

### Close

- Modify `backend/src/main/java/com/aicostops/reconciliation/infrastructure/GatewayCloseBlockerMapper.java` so a valid M15 request resolution terminates only that request's Gateway financial blocker.
- Keep `backend/src/main/java/com/aicostops/reconciliation/application/blockers/GatewayFinancialWorkBlockerProvider.java` and `OpenMaterialReconciliationBlockerProvider.java` as the two existing blockers; no new blocker enum.

### API / OpenAPI

- Modify `backend/src/main/java/com/aicostops/reconciliation/api/ReconciliationController.java`.
- Modify/create request/response records in `backend/src/main/java/com/aicostops/reconciliation/api/` following existing `ReconciliationResponses.java` conventions.
- Modify `docs/02-development/api/openapi.yaml`.
- Extend existing M6 authorization/OpenAPI contract tests.

### Frontend

- Modify `frontend/src/features/reconciliation/types.ts`.
- Modify `frontend/src/features/reconciliation/api/reconciliationApi.ts` and query keys/tests.
- Modify `frontend/src/features/reconciliation/ReconciliationRunDetailPage.tsx` + test.
- Modify `frontend/src/features/reconciliation/ReconciliationCaseDetailPage.tsx` + test.
- Modify `frontend/src/features/reconciliation/presentation.ts`.
- Modify `frontend/src/features/ledger/api/ledgerApi.ts`, Ledger presentation/detail components and tests to support `GATEWAY_SETTLEMENT`, `RECONCILIATION_ADJUSTMENT`, `sourceGatewaySettlementId`, `sourceReconciliationAdjustmentId`.
- Extend browser E2E with Hybrid reconciliation workflow using local fixtures only.

---

## Task 0 — Execution preflight and immutable baseline proof

**Files:** none.

- [ ] **Step 1: Verify Git state and base**

```powershell
Set-Location "E:\AI-CostOps"
git fetch origin
git status
git branch --show-current
git rev-parse origin/main
git rev-parse HEAD
```

Expected before production implementation:

```text
branch = feat/m15-hybrid-reconciliation
origin/main compatible with frozen baseline 502b8aa...
working tree clean except intentional local-only ignored/untracked developer files
```

If `origin/main` advanced, inspect:

```powershell
git log --oneline --decorate 502b8aa38a70a0afc4751097365ec6543592280f..origin/main
git diff --name-status 502b8aa38a70a0afc4751097365ec6543592280f..origin/main
```

Stop and reconcile any M15-relevant migration/schema/API change before coding.

- [ ] **Step 2: Re-prove migration slot**

```powershell
Get-ChildItem backend\src\main\resources\db\migration\V*.sql | Sort-Object Name | Select-Object -ExpandProperty Name
```

Expected: V22 remains highest or, if not, update the plan/spec before creating a migration. Never edit historical migrations.

---

## Task 1 — Land V23 schema with database-enforced terminal lineage

**Files:**
- Create: `backend/src/main/resources/db/migration/V23__m15_hybrid_reconciliation.sql`
- Create: `backend/src/test/java/com/aicostops/reconciliation/M15HybridSchemaIntegrationTest.java`
- Modify: migration-version/schema tests that currently assert V22 is latest.
- Modify: `backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java` only if a new consumer-owned seam needs an explicit allowed direction.

- [ ] **Step 1: Write RED real-MySQL schema tests**

Assert absence before V23 / expected shape after V23 for:

```text
provider_charge_disposition
reconciliation_adjustment
gateway_financial_resolution
reconciliation_evidence
ledger_posting RECONCILIATION_ADJUSTMENT source_type
ledger_entry.source_reconciliation_adjustment_id
```

Test CHECK/FK/unique behavior, including:

```text
one disposition per Charge
one resolution per Gateway Request
CASE_FULL requires case and forbids gateway_request_id
GATEWAY_REQUEST requires request + route attempt
NO_CHARGE_CONFIRMED forbids adjustment
STATEMENT_ADJUSTMENT_POSTED requires GATEWAY_REQUEST adjustment
same-org FK rejection
Ledger direct-source XOR
```

Also seed pre-V23 `PROVIDER_CHARGE` postings and assert V23 backfills them as `DIRECT_PROVIDER_CHARGE / LEGACY_POSTED` exactly once.

Run RED against a branch copy without V23 or before writing migration:

```powershell
Set-Location "E:\AI-CostOps\backend"
.\mvnw.cmd -Dtest=M15HybridSchemaIntegrationTest test
```

Expected: RED for missing M15 schema.

- [ ] **Step 2: Implement only V23**

Use same-org composite FKs and bounded CHECKs. Do not create another idempotency table. Add indexes for:

```text
(org_id, charge_fact_id)
(org_id, request_id)
(org_id, reconciliation_run_id, evidence_key)
(org_id, reconciliation_run_id, provider_account_id, currency)
(org_id, reconciliation_case_id)
(org_id, adjustment_period_id)
```

- [ ] **Step 3: GREEN schema tests + immutable migration diff**

```powershell
.\mvnw.cmd -Dtest=M15HybridSchemaIntegrationTest test
Set-Location "E:\AI-CostOps"
git diff --name-only origin/main...HEAD -- backend/src/main/resources/db/migration
```

Expected migration diff: only `V23__m15_hybrid_reconciliation.sql`.

- [ ] **Step 4: Commit checkpoint**

```powershell
git add backend/src/main/resources/db/migration/V23__m15_hybrid_reconciliation.sql backend/src/test/java/com/aicostops/reconciliation/M15HybridSchemaIntegrationTest.java
git commit -m "feat(m15): add hybrid reconciliation schema"
```

---

## Task 2 — Expand internal truth and freeze M15 aggregate algorithm

**Files:**
- Modify: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationAlgorithm.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/infrastructure/ReconciliationInternalTruthAdapter.java`
- Modify if required: `backend/src/main/java/com/aicostops/ledger/application/ReconciliationInternalTruthPort.java`
- Modify: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationTruthIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationMatchEngineTest.java`

- [ ] **Step 1: RED tests for all Provider-related Ledger sources**

Add real-MySQL truth fixtures proving one provider/currency total includes:

```text
PROVIDER_CHARGE entry
GATEWAY_SETTLEMENT entry
source-preserving CORRECTION reversal/replacement
RECONCILIATION_ADJUSTMENT entry
```

and excludes Expense entries. Include mixed direct+Gateway sources and signed credit/reversal amounts.

- [ ] **Step 2: Implement direct-source union**

Resolve provider account from the entry's direct source, not parent posting type. Do not double-join a correction into multiple source branches. Require exactly one recognized Provider-related direct source for inclusion.

- [ ] **Step 3: Switch algorithm version**

Set exactly:

```text
M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2
```

Do not change M6 difference sign/tolerance semantics.

- [ ] **Step 4: Verify**

```powershell
Set-Location "E:\AI-CostOps\backend"
.\mvnw.cmd -Dtest=ReconciliationTruthIntegrationTest,ReconciliationMatchEngineTest test
```

Expected: all existing M6 arithmetic tests plus new Hybrid truth tests pass.

---

## Task 3 — Build deterministic evidence generation and OPEN/CLOSED run admission

**Files:**
- Create: `backend/src/main/java/com/aicostops/reconciliation/domain/ReconciliationEvidence.java`
- Create: bounded evidence/match/difference enums under `.../reconciliation/domain/`.
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ProviderCorrelationProfileRegistry.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/HybridReconciliationEvidenceService.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/HybridReconciliationMapper.java`
- Modify: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationRunService.java`
- Modify: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/ReconciliationMapper.java`
- Modify: `backend/src/main/java/com/aicostops/budget/application/BillingPeriodFinancialWriteFence.java` / its adapter only to expose a neutral lock/read that accepts OPEN/CLOSED and rejects CLOSING for reconciliation identity creation.
- Create: `backend/src/test/java/com/aicostops/reconciliation/HybridReconciliationEvidenceIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationPersistenceIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/budget/BillingPeriodFinancialWriteFenceIntegrationTest.java`

- [ ] **Step 1: RED exact-match safety tests**

Prove:

```text
certified PROVIDER_REQUEST_ID + one external Charge + one non-SAFE Gateway attempt -> EXACT_PROVIDER_REQUEST
SAFE attempt -> never match
PLANNED attempt -> never match
ambiguous external duplicate -> no auto binding
ambiguous Gateway duplicate/conflicting lineage -> no auto binding
provider schema profile NONE -> no exact match
same amount / nearby time only -> no exact match
```

Default every current unsupported schema to `NONE`; do not infer request-id semantics from `provider_record_key`.

- [ ] **Step 2: RED aggregate/run-level evidence tests**

Prove:

```text
provider/currency/BillingPeriod aggregate evidence exists
aggregate evidence never auto-creates Charge disposition
UNKNOWN/no-ledger/no-external Gateway work can create GATEWAY_UNRESOLVED run evidence with case_id NULL
```

- [ ] **Step 3: RED period-state tests**

```text
OPEN -> run allowed
CLOSED -> run allowed and no financial mutation
CLOSING -> run rejected
CLOSED run vs Reopen race -> one stable state, no auto-reopen
```

- [ ] **Step 4: Implement evidence generation inside the read-only snapshot**

Evidence keys must be deterministic and bounded. Evidence persistence happens only in run finalization after the snapshot is successfully computed. Never persist prompt/provider body/free-form upstream data.

Automatic difference labels only fire when persisted evidence proves them. Unsupported late-charge confirmation timing must remain `UNCLASSIFIED`.

- [ ] **Step 5: Verify**

```powershell
.\mvnw.cmd -Dtest=HybridReconciliationEvidenceIntegrationTest,ReconciliationPersistenceIntegrationTest,BillingPeriodFinancialWriteFenceIntegrationTest test
```

---

## Task 4 — Add the Hybrid Provider Charge posting fence and dispositions

**Files:**
- Create: `backend/src/main/java/com/aicostops/ledger/application/ProviderChargeHybridPostingGuard.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/ProviderChargeHybridPostingGuardAdapter.java`
- Create: charge-disposition domain/service methods in reconciliation application/infrastructure.
- Modify: `backend/src/main/java/com/aicostops/ledger/application/ProviderChargePostingService.java`
- Modify only if needed: `backend/src/main/java/com/aicostops/cost/infrastructure/ChargePostingMapper.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/ProviderChargeHybridFenceIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/ledger/ProviderChargePostingIntegrationTest.java` if present; otherwise the existing posting integration class that owns Provider Charge posting behavior.
- Modify: `backend/src/test/java/com/aicostops/ledger/PostingPortIntegrationTest.java` for seam behavior.

- [ ] **Step 1: RED behavior matrix**

Prove:

```text
existing posting replay -> succeeds even if later Hybrid overlap exists
non-Hybrid Charge -> unchanged V1 posting
Hybrid overlap + no disposition -> conflict, zero Ledger/Budget/Commitment mutation
RECONCILIATION_EVIDENCE -> permanently non-postable
DIRECT_PROVIDER_CHARGE -> normal V1 posting allowed
SYSTEM_EXACT only accepted with exact evidence
aggregate-only scope -> cannot create SYSTEM disposition
MANUAL requires member/reason/note and whole-Charge decision
```

- [ ] **Step 2: Implement consumer-owned guard**

`ProviderChargePostingService` must call the guard **inside its existing transaction after BillingPeriod lock and before Charge posting**. Do not call reconciliation application services from Ledger.

Hybrid overlap SQL is conservative: same org/provider account/currency/period + any non-PLANNED/non-SAFE possible-billable route. False block is acceptable; false allow is not.

- [ ] **Step 3: Real race proof with Gateway dispatch fence**

Create a deterministic two-thread Testcontainers test using latches/barriers, not sleeps:

```text
posting wins period lock first -> posts direct cost before any billable Gateway intent for that period snapshot
Gateway dispatch wins first -> posting observes Hybrid overlap and blocks
never both ambiguous financial paths
```

- [ ] **Step 4: Verify**

```powershell
.\mvnw.cmd -Dtest=ProviderChargeHybridFenceIntegrationTest,PostingPortIntegrationTest test
```

---

## Task 5 — Generalize Ledger direct-source lineage and repair Gateway correction

**Files:**
- Modify: `backend/src/main/java/com/aicostops/ledger/infrastructure/LedgerPostingMapper.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/application/LedgerCorrectionService.java`
- Modify: Ledger domain/read models carrying direct source ids.
- Modify: `backend/src/test/java/com/aicostops/ledger/LedgerCorrectionIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/ledger/LedgerFinancialInvariantIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/ledger/LedgerCorrectionRollbackIntegrationTest.java`

- [ ] **Step 1: RED correction lineage tests**

Create Gateway Settlement Ledger target fixture and prove current code loses `source_gateway_settlement_id`. Add future-source fixture for `source_reconciliation_adjustment_id`.

Expected corrected invariant:

```text
reversal direct source == target direct source
replacement direct source == target direct source
exactly one direct source preserved
historical target unchanged
```

- [ ] **Step 2: Refactor mapper insertion around one direct-source projection**

Avoid adding fragile positional parameter overloads per source forever. Introduce a small neutral direct-source record/command if useful, while preserving existing Provider/Expense/Gateway APIs.

- [ ] **Step 3: Regression**

```powershell
.\mvnw.cmd -Dtest=LedgerCorrectionIntegrationTest,LedgerFinancialInvariantIntegrationTest,LedgerCorrectionRollbackIntegrationTest test
```

---

## Task 6 — Implement CASE_FULL Reconciliation Adjustment

**Files:**
- Create: `backend/src/main/java/com/aicostops/reconciliation/domain/ReconciliationAdjustment.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/domain/ReconciliationAdjustmentScope.java`
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationAdjustmentService.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/ReconciliationAdjustmentLedgerPort.java`
- Create: `backend/src/main/java/com/aicostops/ledger/infrastructure/ReconciliationAdjustmentLedgerAdapter.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/HybridReconciliationMapper.java`
- Extend/extract shared idempotency over existing `api_idempotency`; preserve `LedgerCorrectionService` replay behavior.
- Create: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationAdjustmentIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationAdjustmentRollbackIntegrationTest.java`
- Extend authorization/audit tests later in Task 8.

- [ ] **Step 1: RED amount/target/basis tests**

Prove:

```text
amount must equal external - internal exactly
line sum must equal amount exactly
zero amount rejected as financial action
currency must equal case currency
exactly one target per line
same-org ACTIVE target required
no inferred target/split/remainder
stale run basis -> conflict before financial mutation
```

- [ ] **Step 2: RED OPEN/CLOSED period tests**

```text
case period OPEN -> only same period accepted
case period CLOSED + still CLOSED -> same-period write rejected
explicit alternate OPEN correction period -> allowed
historical+correction period locks sorted by id
CLOSING correction period -> rejected
```

- [ ] **Step 3: RED Budget/Commitment semantics**

```text
Budget selected by existing exact/ORG same-currency rules
no Budget -> Ledger still posts
signed amount mutates Actual exactly
CASE_FULL never infers/consumes Commitment
```

- [ ] **Step 4: RED idempotency/rollback**

Same key/same body -> one adjustment/one posting/one Actual mutation. Same key/different body -> conflict.

Inject failures after adjustment insert, Ledger insert, Actual mutation, Audit and case resolution update; all must rollback.

- [ ] **Step 5: Implement one caller-owned transaction**

The Reconciliation service owns orchestration; Ledger adapter participates in the caller transaction and does not start an independent transaction. Use posting key `RECONCILIATION_ADJUSTMENT:{id}`.

- [ ] **Step 6: Verify**

```powershell
.\mvnw.cmd -Dtest=ReconciliationAdjustmentIntegrationTest,ReconciliationAdjustmentRollbackIntegrationTest test
```

---

## Task 7 — Implement Gateway financial resolution and prevent future Settlement double-posting

**Files:**
- Create: Gateway resolution domain records/enums under `.../reconciliation/domain/`.
- Create: `backend/src/main/java/com/aicostops/reconciliation/application/GatewayFinancialResolutionService.java`
- Extend: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/HybridReconciliationMapper.java` with request/source locks and resolution persistence.
- Extend: Backend reservation authority with narrow `finalizeForReconciliation` / `releaseForReconciliation` operations; do not add create/resize/retarget.
- Create: `backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewayFinancialTerminalPort.java`
- Create: `backend/src/main/java/com/aicostops/gatewaysettlement/infrastructure/GatewayFinancialTerminalAdapter.java`
- Modify: `backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewaySettlementDiscoveryService.java`
- Modify: `backend/src/main/java/com/aicostops/gatewaysettlement/infrastructure/GatewaySettlementMapper.java`
- Modify: `backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewaySettlementService.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/GatewayFinancialResolutionIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/reconciliation/GatewayFinancialResolutionConcurrencyIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementDiscoveryIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementTransactionIntegrationTest.java`

- [ ] **Step 1: RED eligibility matrix**

Allowed:

```text
no usage + possible-billable attempt
INCOMPLETE
UNKNOWN
existing RECONCILIATION_REQUIRED Settlement
```

Rejected:

```text
SAFE attempt
ordinary FINAL with no Settlement
PENDING Settlement
RETRYABLE_FAILED Settlement
SETTLED Settlement
foreign/mismatched request/attempt/provider/currency
```

- [ ] **Step 2: RED run-without-case path**

`NO_CHARGE_CONFIRMED` must work from run-level unresolved evidence with `case_id = NULL`, positive reviewed reason/evidence, zero Ledger mutation, and RELEASED effective reservation.

Statement absence alone must fail validation.

- [ ] **Step 3: RED GATEWAY_REQUEST adjustment path**

For authoritative statement-backed unresolved cost:

```text
adjustment_scope = GATEWAY_REQUEST
amount derives from exact request external truth, never aggregate pro-rata
financial target frozen from gateway_request
same-period positive amount may consume only explicitly bound Commitment
cross-period never consumes historical Commitment
reservation FINALIZED
one immutable gateway_financial_resolution
aggregate sibling case/evidence not auto-resolved
```

- [ ] **Step 4: RED resolution-vs-late-FINAL race**

Use deterministic barriers around Gateway Request row/source lock:

```text
late FINAL wins -> resolution sees ordinary FINAL and rejects
resolution wins -> late FINAL may persist as evidence but discovery produces zero Settlement
```

Then directly call discovery and Settlement service to prove defense in depth.

- [ ] **Step 5: RED resolution-vs-Settlement race**

```text
PENDING/RETRYABLE_FAILED -> resolution rejected
SETTLED commits before resolution revalidation -> Settlement wins, no adjustment
resolution committed first for eligible UNKNOWN/RECON_REQUIRED -> discovery/service refuse future normal Settlement
```

- [ ] **Step 6: Implement transaction and source lock**

Lock period(s) -> budget(s) -> optional commitment -> reservation -> reconciliation identity -> Gateway Request FOR UPDATE -> current usage/Settlement -> adjustment/Ledger -> Actual/Commitment -> reservation -> Audit -> resolution/evidence.

Do not mutate `gateway_request`, `gateway_usage_fact`, or historical `gateway_settlement` state.

- [ ] **Step 7: Verify**

```powershell
.\mvnw.cmd -Dtest=GatewayFinancialResolutionIntegrationTest,GatewayFinancialResolutionConcurrencyIntegrationTest,GatewaySettlementDiscoveryIntegrationTest,GatewaySettlementTransactionIntegrationTest test
```

---

## Task 8 — Evolve case actions, queries, authorization, Audit, metrics and OpenAPI

**Files:**
- Modify: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationCaseService.java`
- Modify: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationQueryService.java`
- Modify: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationReadModels.java`
- Modify: `backend/src/main/java/com/aicostops/reconciliation/application/ReconciliationAuditPort.java`
- Modify: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/AuditReconciliationAdapter.java`
- Modify: `backend/src/main/java/com/aicostops/observability/AiCostOpsMetrics.java`
- Modify: `backend/src/main/java/com/aicostops/reconciliation/api/ReconciliationController.java`
- Modify/create DTO records in `backend/src/main/java/com/aicostops/reconciliation/api/`.
- Modify: `docs/02-development/api/openapi.yaml`
- Modify: `backend/src/test/java/com/aicostops/reconciliation/ReconciliationApiIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/reconciliation/M6AuthorizationIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/reconciliation/M6OpenApiContractTest.java` or rename/extend to an M15 contract class without deleting M6 regression assertions.
- Extend audit integration tests covering sensitive finance actions.

- [ ] **Step 1: RED API compatibility tests**

Existing M6 endpoints and JSON fields remain compatible. Existing `/reconciliation-cases/{id}/resolve` remains the explicit non-financial whole-case `ACCEPT_EXPLAINED_DIFFERENCE` path.

- [ ] **Step 2: RED new endpoint tests**

Implement/test:

```text
GET  /reconciliation-runs/{runId}/evidence
GET  /reconciliation-cases/{caseId}/evidence
POST /reconciliation-cases/{caseId}/charge-dispositions
POST /reconciliation-cases/{caseId}/adjustments
POST /reconciliation-runs/{runId}/gateway-resolutions
POST /reconciliation-cases/{caseId}/link-correction
```

IDs serialize as decimal strings; money as exact scale-8 strings. Pagination/filter semantics follow existing API conventions.

- [ ] **Step 3: Permission matrix**

```text
RECONCILIATION_READ -> reads only
RECONCILIATION_RUN -> run
RECONCILIATION_RESOLVE -> investigate/explain/disposition/link
RECONCILIATION_RESOLVE + LEDGER_CORRECT -> adjustment + Gateway financial resolution
LEDGER_POST -> existing direct Provider Charge post
PERIOD_REOPEN -> unchanged
```

Test same-org privacy-preserving 404/authorization behavior.

- [ ] **Step 4: Audit/metrics**

Audit events:

```text
RECONCILIATION_CHARGE_DISPOSITION_DECIDED
RECONCILIATION_ADJUSTMENT_POSTED
RECONCILIATION_CORRECTION_LINKED
GATEWAY_FINANCIAL_RESOLVED
```

Bounded metrics only; assert no request/org/provider-request ids are labels.

- [ ] **Step 5: OpenAPI contract**

Update enums/source types/requests/responses and add test assertions that API docs contain no secret-bearing fields.

- [ ] **Step 6: Verify**

```powershell
.\mvnw.cmd -Dtest=ReconciliationApiIntegrationTest,M6AuthorizationIntegrationTest,M6OpenApiContractTest test
```

---

## Task 9 — Integrate existing Close blockers and basis staleness

**Files:**
- Modify: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/GatewayCloseBlockerMapper.java`
- Modify only if summary output changes: `backend/src/main/java/com/aicostops/reconciliation/application/blockers/GatewayFinancialWorkBlockerProvider.java`
- Keep and test: `backend/src/main/java/com/aicostops/reconciliation/application/blockers/OpenMaterialReconciliationBlockerProvider.java`
- Modify: `backend/src/test/java/com/aicostops/reconciliation/GatewayFinancialWorkCloseIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/reconciliation/CloseBlockerProviderIntegrationTest.java`
- Modify: `backend/src/test/java/com/aicostops/reconciliation/application/PeriodCloseCoordinatorIntegrationTest.java`

- [ ] **Step 1: RED Gateway blocker tests**

```text
unresolved UNKNOWN/INCOMPLETE/PENDING_HOLD -> block
valid NO_CHARGE_CONFIRMED + RELEASED -> that request passes
valid STATEMENT_ADJUSTMENT_POSTED + FINALIZED -> that request passes
resolution with contradictory still-effective reservation -> block
resolution for request A never clears request B
run-level resolution with no case still clears only Gateway blocker for that request
```

- [ ] **Step 2: RED material reconciliation staleness**

```text
financial adjustment/correction/direct post after run -> FINANCIAL_BASIS_CHANGED
old M6 algorithm run -> stale after M15 algorithm switch
CASE_FULL action does not patch basis hash
rerun current truth -> fresh
```

- [ ] **Step 3: Verify no ninth blocker**

Schema and enum tests must still contain exactly the current blocker set including `PENDING_GATEWAY_FINANCIAL_WORK`; no `HYBRID_RECONCILIATION` blocker is added.

- [ ] **Step 4: Verify**

```powershell
.\mvnw.cmd -Dtest=GatewayFinancialWorkCloseIntegrationTest,CloseBlockerProviderIntegrationTest,PeriodCloseCoordinatorIntegrationTest test
```

---

## Task 10 — Fix Ledger read/API lineage and build the Hybrid frontend workflow

**Backend files:**
- Modify Ledger query/read/API projection files under `backend/src/main/java/com/aicostops/ledger/` to expose new source types/ids and safe reconciliation lineage.
- Extend Ledger API integration tests.

**Frontend files:**
- Modify: `frontend/src/features/reconciliation/types.ts`
- Modify: `frontend/src/features/reconciliation/api/reconciliationApi.ts`
- Modify: reconciliation query keys/tests.
- Modify: `frontend/src/features/reconciliation/presentation.ts`
- Modify: `frontend/src/features/reconciliation/ReconciliationRunDetailPage.tsx`
- Modify: `frontend/src/features/reconciliation/ReconciliationRunDetailPage.test.tsx`
- Modify: `frontend/src/features/reconciliation/ReconciliationCaseDetailPage.tsx`
- Modify: `frontend/src/features/reconciliation/ReconciliationCaseDetailPage.test.tsx`
- Modify: `frontend/src/features/ledger/api/ledgerApi.ts`
- Modify Ledger presentation/detail pages/tests.
- Create/extend: `frontend/e2e/m15-hybrid-reconciliation.spec.ts` using isolated local API fixtures.

- [ ] **Step 1: RED Backend Ledger API regression**

Assert APIs expose:

```text
sourceType GATEWAY_SETTLEMENT
sourceType RECONCILIATION_ADJUSTMENT
sourceGatewaySettlementId
sourceReconciliationAdjustmentId
safe run/case linkage where available
```

No Provider secrets/raw request content.

- [ ] **Step 2: RED frontend API/type tests**

Add exact DTO coverage for evidence, dispositions, CASE_FULL adjustments, run-level Gateway resolution and all Ledger source types.

- [ ] **Step 3: Implement Run detail UX**

Chinese-localized UI must show run status/algorithm/BillingPeriod state, aggregate cases, exact evidence counts and unresolved run-level Gateway evidence even when no case exists.

For CLOSED periods, show that reconciliation does not reopen automatically and route users to the existing governed Reopen flow only when authorized.

- [ ] **Step 4: Implement Case detail UX**

Clearly distinguish:

```text
whole-case actions
vs
evidence-item actions
```

Never present resolving one request as resolving all siblings. Display exact/aggregate/manual match kind, difference evidence, statement/Gateway/Ledger lineage and charge disposition.

- [ ] **Step 5: Implement Ledger lineage debt fix**

Update labels and detail links for `GATEWAY_SETTLEMENT` and `RECONCILIATION_ADJUSTMENT`.

- [ ] **Step 6: Verify frontend**

```powershell
Set-Location "E:\AI-CostOps\frontend"
npm test -- --run --maxWorkers=1
npm run lint
npm run build
```

Expected: all pass; existing bundle-size warning alone is not failure.

---

## Task 11 — Prove cross-module concurrency and failure atomicity on real MySQL

**Files:**
- Create: `backend/src/test/java/com/aicostops/reconciliation/M15FinancialConcurrencyIntegrationTest.java`
- Create/extend fault injector only at testable financial checkpoints; production default is noop.
- Extend: `backend/src/test/java/com/aicostops/reconciliation/KnownPeriodCloseRaceIntegrationTest.java` where reuse is clearer.
- Extend M13 Settlement transaction tests where the race belongs to Settlement.

- [ ] **Step 1: Deterministic concurrency, no sleeps**

Use `CountDownLatch`/barriers and two real worker threads. Include repeated runs where useful.

Required races:

```text
Provider Charge post vs Gateway dispatch
CASE_FULL adjustment vs Close
cross-period adjustment vs Close/Reopen
CLOSED reconciliation run vs Reopen
Gateway resolution duplicate commands
Gateway resolution vs late FINAL usage
Gateway resolution vs Settlement
```

- [ ] **Step 2: Assert business uniqueness, not only HTTP outcomes**

After every race query MySQL directly and assert exact counts for:

```text
Ledger postings/entries
reconciliation_adjustment
gateway_financial_resolution
Budget actual delta
Commitment usage
Reservation status/version
Audit events
case/evidence rows
Gateway Settlement rows
```

- [ ] **Step 3: Fault injection rollback matrix**

Inject after each financial mutation boundary from the spec. After exception, assert no partial terminal state survives.

- [ ] **Step 4: Run focused suite repeatedly**

```powershell
Set-Location "E:\AI-CostOps\backend"
1..5 | ForEach-Object { .\mvnw.cmd -Dtest=M15FinancialConcurrencyIntegrationTest test; if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE } }
```

Expected: all repetitions pass without timing sleeps/deadlock flakes.

---

## Task 12 — Architecture, complete regression, evidence and final PR handoff

**Files:**
- Modify: `backend/src/test/java/com/aicostops/architecture/ModuleDependencyArchitectureTest.java` as needed to codify consumer-owned port directions.
- Create: `docs/03-acceptance/m15-hybrid-reconciliation-evidence.md`
- Update stale milestone/roadmap/context docs only where M15 completion changes status; do not rewrite historical evidence.
- Update Issue #148 / final PR description with exact evidence.

- [ ] **Step 1: Architecture proof**

Assert at minimum:

```text
Ledger application does not depend on reconciliation application/domain
Gateway Settlement application does not depend on reconciliation application/domain
reconciliation may consume Ledger/Budget/Gateway-settlement application seams
infrastructure adapters may read cross-module tables only through explicit bounded responsibilities
no Gateway module dependency introduced for M15 unless an approved missing-lineage defect forced it
```

Run:

```powershell
Set-Location "E:\AI-CostOps\backend"
.\mvnw.cmd -B -Dgroups=architecture test
```

- [ ] **Step 2: Backend full verification**

```powershell
.\mvnw.cmd -B -DexcludedGroups=architecture,integration test
.\mvnw.cmd -B -Dgroups=architecture test
.\mvnw.cmd -B -Dgroups=integration verify
```

Record exact totals/failures/skips from Surefire/Failsafe reports.

- [ ] **Step 3: Gateway regression — no M15 routing behavior change**

```powershell
Set-Location "E:\AI-CostOps\gateway"
.\mvnw.cmd -B -DexcludedGroups=architecture,integration test
.\mvnw.cmd -B -Dgroups=architecture test
.\mvnw.cmd -B -Dgroups=integration verify
```

M14 safe-failover tests must remain green.

- [ ] **Step 4: Frontend + E2E + Docker**

```powershell
Set-Location "E:\AI-CostOps\frontend"
npm test -- --run --maxWorkers=1
npm run lint
npm run build
```

Run the repository's existing isolated Compose + Playwright command and Backend/Gateway/Frontend Docker build commands exactly as CI defines them. Do not substitute a developer-drifted local environment for isolated evidence.

- [ ] **Step 5: Diff hygiene**

```powershell
Set-Location "E:\AI-CostOps"
git diff --check origin/main...HEAD
git diff --name-status origin/main...HEAD
git diff --name-only origin/main...HEAD -- backend/src/main/resources/db/migration
```

Expected migrations: only V23. Review the complete file list for accidental M14/routing/non-M15 scope changes.

- [ ] **Step 6: Write acceptance evidence**

`docs/03-acceptance/m15-hybrid-reconciliation-evidence.md` must record:

```text
Issue / branch / base / implementation SHA / evidence SHA
schema + migration proof
matching safety proof
posting-fence proof
Gateway-resolution terminal precedence proof
Ledger/correction/adjustment proof
OPEN/CLOSED/reopen proof
Close proof
real concurrency/fault-injection proof
unit/integration/architecture/frontend/E2E/Docker totals
hosted CI/Security run ids
unresolved review-thread count
explicit non-scope
```

Do not claim a test/run not actually executed.

- [ ] **Step 7: Hosted PR and independent review**

Open one PR from `feat/m15-hybrid-reconciliation` to `main`, `Closes #148`. Wait for hosted CI/Security. Resolve every review thread with code/evidence or a reasoned rejection. Do not merge.

- [ ] **Step 8: Final verification-before-completion**

Re-run or inspect the latest authoritative commands/runs immediately before declaring M15 ready. The final handoff must distinguish:

```text
implementation complete
hosted green
independent review complete
merge pending explicit user instruction
```

---

## Implementation Order Rationale

The order is deliberate:

```text
schema
→ aggregate truth
→ evidence/run semantics
→ Provider Charge anti-double-count fence
→ source-lineage correctness
→ case-level adjustment
→ request-level Gateway terminal resolution + M13 exclusion
→ API/audit/auth
→ Close
→ frontend
→ cross-module concurrency/failure proof
→ full regression/evidence
```

Do not implement Gateway financial resolution before the Provider Charge fence, Ledger source preservation and M13 terminal-exclusion tests exist. Those are the safety rails that prevent M15 from creating a new double-count path while trying to close an old one.

## Definition of Implementation Complete

Implementation is not complete merely because APIs return success. It is complete only when the approved spec's Definition of Done is proven, including:

```text
one M6-evolved reconciliation truth
no fuzzy/per-request invented matching
no automatic aggregate Charge disposition
no Provider Charge + Gateway duplicate cost
no M15 adjustment + later Settlement duplicate cost
no mutation of SETTLED/POSTED history
source-preserving Gateway/Adjustment corrections
CASE_FULL stale-basis rejection
request-level actions do not close sibling evidence
run-level Gateway resolution without fake case
OPEN/CLOSED reconciliation with no automatic reopen
existing Close blockers only
real-MySQL atomicity/concurrency proof
full backend/gateway/frontend/E2E/Docker/hosted security regression
```
