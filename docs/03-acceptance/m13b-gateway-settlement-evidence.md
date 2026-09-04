# M13-B Gateway Settlement Evidence

## Scope and provenance

- Issue: #138 (`feat(m13b): settle gateway usage into ledger and budget actual`)
- Branch: `feat/m13b-gateway-settlement`
- Frozen base: `19148d3b58789d6269ccbf7e77b7a0f24de67767`
- Spec: `origin/docs/m13-metering-settlement-design:docs/superpowers/specs/2026-09-04-m13-metering-settlement-design.md`
- Plan: `origin/docs/m13-metering-settlement-design:docs/superpowers/plans/2026-09-04-m13b-gateway-settlement-plan.md`
- Last implementation/test SHA containing the reviewed behavior:
  `729b7fc71ccc9a3d839536e857a82fcad1899a79`.
- Evidence/docs-only commit: this correctness-refresh commit; its exact SHA and
  the final hosted-tested PR head are recorded in the final handoff.

The implementation follows the frozen TDD, systematic-debugging, verification, and
review workflow. No Superpowers package is installed in this local checkout; the
equivalent test-first, failure-classification, full-matrix verification, and final
review checks were executed locally.

## Schema and lineage

- Added only `backend/src/main/resources/db/migration/V21__m13_gateway_settlement.sql`.
- `gateway_settlement` has bounded statuses, DECIMAL(38,18)/DECIMAL(20,8) amount
  precision, deterministic discovery indexes, and database uniqueness for
  `(org_id, settlement_key)`, `(org_id, request_id)`, and `(org_id, usage_fact_id)`.
- Same-organization composite foreign keys cover request, route attempt, usage fact,
  reservation, billing period, provider account, pricing version, and ledger posting;
  provider model remains linked to the global catalog relationship.
- Ledger V21 extension adds `GATEWAY_SETTLEMENT`, `posting_actor_type`, and
  `source_gateway_settlement_id`, including SYSTEM/MEMBER and direct-source checks.
- Existing Provider, Expense, and Correction ledger paths passed their regression
  integration tests.
- Schema RED: before V21, the new schema integration assertions failed because the
  settlement table and its ledger lineage constraints did not exist. Schema GREEN:
  real MySQL 8.4 applied all 21 migrations and the schema suite passed.

## Settlement, pricing, and financial transaction

- Discovery reads only `gateway_request.current_usage_fact_id` with `status='FINAL'`,
  uses deterministic ordering and bounded batches, and converges through database
  uniqueness on `GATEWAY_REQUEST:<public_request_id>`.
- The worker reads IDs without claiming or locking Settlement first; there is no
  durable PROCESSING/CLAIMED state. Retries use bounded `attempt_count`,
  `next_attempt_at`, and bounded error codes.
- Route Attempt remains the frozen source for provider account, provider model, and
  pricing version. A later active pricing version is not consulted.
- Costing uses only BigDecimal and computes `quantity * unit_price / unit_quantity`.
  Raw cost is DECIMAL(38,18); posted cost is scale 8 with CEILING; the saved delta is
  `posted_amount - calculated_amount_raw`. Exact, multiple-dimension, non-scale-8,
  tiny-positive, missing/unsupported-dimension, invalid-unit, and overflow cases are
  covered. Tiny positive cost posts as `0.00000001`.
- The formal MySQL transaction locks exactly:
  `BillingPeriod -> bound Budget -> explicit Commitment -> bound Reservation -> GatewaySettlement -> Ledger`.
  It revalidates current FINAL usage and all frozen lineage before posting.
- The period lock reads the exact period row without requiring OPEN, then applies
  the close boundary explicitly: `CLOSING` produces bounded `RETRYABLE_FAILED`
  with `PERIOD_CLOSING` and a later worker retry; `CLOSED` produces
  `RECONCILIATION_REQUIRED` with `BILLING_PERIOD_CLOSED`. Neither path performs
  Ledger, Actual, Commitment, Reservation, or Audit financial mutation.
- A Gateway Settlement creates one SYSTEM `GATEWAY_SETTLEMENT` posting and one COST
  entry. Budget Actual increments by the full incurred posted amount, including the
  overrun case `reserved=1.00`, `actual=1.80` => `actual += 1.80`.
- A null Commitment is untouched. A non-null Commitment locks and consumes only that
  exact bound Commitment, using the existing consume primitive; insufficient remaining
  commitment does not cap the full Actual or Ledger amount.
- An explicit Commitment with zero posted amount skips the positive-only consume
  primitive, preserving Commitment and Budget committed amount while still writing
  the zero COST Ledger entry, zero Actual, Audit, reservation finalization, and
  `SETTLED` outcome.
- Existing Gateway Settlement Ledger posting/source/entry conflicts are represented
  by a dedicated semantic conflict type and translated to
  `LEDGER_LINEAGE_CONFLICT -> RECONCILIATION_REQUIRED` only at the
  `gatewayLedger.post(...)` boundary; unrelated `IllegalStateException` failures
  still escape and roll back.
- Only the bound reservation may be finalized, and only from ACTIVE/PENDING_HOLD;
  RELEASED or mismatched lineage goes to reconciliation. Successful completion writes
  audit, finalizes the reservation, and marks Settlement SETTLED in the same commit.
- The audit actor is SYSTEM (`actor_user_id=NULL`) and records bounded business and
  financial identifiers only; no prompt, completion, reasoning, provider body, or
  credential material is persisted.

## Retry, idempotency, Close, and concurrency evidence

- Duplicate discovery, sequential duplicate processing, concurrent workers, and retry
  paths converge to one Settlement, one Ledger posting, one Actual mutation, one
  audit, and one reservation finalization.
- Fault injection after Ledger insertion, after Actual mutation, after audit, and at
  `BEFORE_SETTLEMENT_SETTLED` (after reservation finalization) proves full rollback:
  no partial financial mutation remains, the reservation returns to ACTIVE with
  `finalized_at=NULL`, the explicit Commitment and its usage lineage are unchanged,
  and the Settlement remains pending.
- Close keeps `PENDING_GATEWAY_FINANCIAL_WORK`. It blocks absent/non-final usage,
  non-SETTLED current FINAL usage, and ACTIVE/PENDING_HOLD reservations. The matrix
  explicitly covers no usage, INCOMPLETE, UNKNOWN, FINAL without Settlement, FINAL
  with PENDING/RETRYABLE_FAILED/RECONCILIATION_REQUIRED, ACTIVE, and PENDING_HOLD.
  FINAL + SETTLED + FINALIZED passes for both completed and failed transport; a
  FAILED_AFTER_DISPATCH request with that same financial truth is terminal and does
  not block. Only the existing `PENDING_GATEWAY_FINANCIAL_WORK` blocker code is used.
- Real Testcontainers MySQL race coverage proves both sequences with the actual
  `PeriodCloseService`: Settlement wins, Close waits and then closes from committed
  terminal truth; Close wins, Settlement records `PERIOD_CLOSING` retryable work,
  Close returns OPEN/BLOCKED, and the due worker retry settles it. The close begin
  transaction retries only MySQL deadlock/serialization losers caused by the
  cross-module lock order. A genuinely CLOSED period routes a later Settlement to
  reconciliation without Ledger/Actual mutation.

## RED/GREEN checkpoints

- Schema: missing V21 table/FKs RED; V21 MySQL schema assertions GREEN.
- Discovery: current FINAL-only bounded discovery and duplicate convergence GREEN on
  real MySQL; INCOMPLETE and non-current facts are ignored.
- Cost: corrected test inputs were required before interpretation (an initial expected
  value typo and an unsupported nine-decimal fixture were discarded as invalid RED);
  the corrected exact BigDecimal boundary suite is GREEN.
- Ledger: SYSTEM actor/source and one-entry cardinality tests GREEN.
- Atomic transaction: happy path, optional unbudgeted, explicit/null Commitment,
  zero-cost explicit Commitment, RELEASED reservation, overrun, rollback injections
  including `BEFORE_SETTLEMENT_SETTLED`, existing Ledger lineage conflict, duplicate
  worker, and real Close races are covered by the correctness-refresh tests.

## Verification totals

Backend:

- Unit: 487 tests, 0 failures, 0 errors, 1 skipped.
- Architecture: 36 tests, 0 failures, 0 errors.
- Full integration: 862 tests, 0 failures, 0 errors.
- Settlement transaction integration: 14 tests in the correctness-refresh suite.
- Real Close coordinator integration: 7 tests in the correctness-refresh suite.
- Gateway Close blocker integration: 14 tests in the correctness-refresh suite.

Gateway regression:

- Unit command: 95 tests, 0 failures, 0 errors.
- Architecture class (`GatewayArchitectureTest`): 7 tests, 0 failures, 0 errors.
- Integration Failsafe: 57 tests, 0 failures, 0 errors; the same command's tagged
  Surefire phase ran 22 tests successfully.

Commands used:

```text
backend: .\\mvnw.cmd -B -DexcludedGroups=architecture,integration test
backend: .\\mvnw.cmd -B -Dgroups=architecture test
backend: .\\mvnw.cmd -B -Dgroups=integration verify
gateway: .\\mvnw.cmd -B -DexcludedGroups=architecture,integration test
gateway: .\\mvnw.cmd -B -Dgroups=architecture test
gateway: .\\mvnw.cmd -B -Dtest=GatewayArchitectureTest test
gateway: .\\mvnw.cmd -B -Dgroups=integration verify
```

`git diff --check origin/main...HEAD`: clean before this evidence-only change.

## Explicit non-scope and hosted review

- V1-V20 are unchanged; the migration diff contains only V21.
- No M14, M15, FX, negative realtime credits, new Provider calls, credential
  decryption, Redis financial truth, new Budget fallback selection, or new Commitment
  binding policy was added.
- Hosted CI and Security for the final PR head are recorded in the final handoff.
  The required run covers backend architecture/unit/integration, gateway
  architecture/unit/integration, frontend, Docker, browser E2E, CodeQL Java/Kotlin,
  CodeQL JavaScript/TypeScript, and Trivy.
- PR #143: https://github.com/BangShou1st/AI-CostOps/pull/143, against `main`,
  closing #138. It remains OPEN/unmerged for independent Sol review.
