# M13-B Gateway Settlement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consume durable FINAL Gateway Usage Facts and atomically settle them into immutable Ledger history, Budget Actual, explicit Commitment usage, reservation FINALIZED and Close safety.

**Architecture:** M13-B begins only after M13-A (#137) is merged and evidenced. Backend/CostOps owns Settlement. It discovers durable current FINAL usage facts, creates one business-unique PENDING Settlement, and processes it under the frozen financial lock order `BillingPeriod -> Budget -> Commitment -> BudgetReservation -> GatewaySettlement -> Ledger`. Cost is calculated only from the Route Attempt's frozen Pricing Version. Ledger + Actual + Audit + reservation FINALIZED + SETTLED commit in one MySQL transaction.

**Tech Stack:** Java 21, Spring Boot, MyBatis, MySQL 8, Flyway, Spring transactions/TransactionTemplate, JUnit 5, AssertJ, Testcontainers, Maven Wrapper 3.9.11.

**Spec:** `docs/superpowers/specs/2026-09-04-m13-metering-settlement-design.md`

## Global Constraints

- Issue: `#138 feat(m13b): settle gateway usage into ledger and budget actual`.
- Do not create the implementation branch until #137 is merged. Base on the exact post-M13-A `main` SHA and record it.
- Add only `V21__m13_gateway_settlement.sql`; never edit V1-V20.
- Backend remains the sole Flyway owner.
- No Provider call or Provider credential decryption in Settlement.
- No Redis financial truth.
- Settlement must not select latest/current Pricing Version; only Route Attempt frozen pricing is valid.
- If a reservation exists, Settlement uses/verifies that reservation's Budget/scope/currency binding and never re-runs Budget fallback selection.
- Missing priced dimension never becomes zero.
- Positive incurred cost uses exact BigDecimal math and scale-8 upward/away-from-zero quantization; tiny positive cost cannot post zero.
- Actual greater than reservation posts the full Actual amount.
- M12 reservations have no inferred Commitment; only a non-null explicit binding may be consumed.
- Backend reservation authority is limited to the bound reservation `ACTIVE/PENDING_HOLD -> FINALIZED` inside the Settlement transaction.
- Global lock order is frozen: BillingPeriod -> Budget -> explicit Commitment -> BudgetReservation -> GatewaySettlement -> Ledger. Never invert it through worker claiming.
- No prompt/completion/raw keys/arbitrary Provider bodies in Settlement/Audit/logs.
- Use TDD for every behavioral change.

---

### Task 1: V21 Settlement and Ledger forward schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V21__m13_gateway_settlement.sql`
- Create: `backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementSchemaIntegrationTest.java`
- Modify test cleanup helpers that directly delete Ledger/Gateway rows only as required for new FK-safe ordering.

**Interfaces:**
- Creates `gateway_settlement` with request/usage uniqueness and same-org lineage.
- Forward-alters `ledger_posting` for SYSTEM actor + GATEWAY_SETTLEMENT source.
- Forward-alters `ledger_entry` with `source_gateway_settlement_id`.

- [ ] **Step 1: Write RED schema tests** proving: `gateway_settlement` columns/precision/status checks; `UNIQUE(org_id,settlement_key)`, request uniqueness, usage-fact uniqueness; same-org FKs; `ledger_posting.posting_actor_type`; existing rows backfill `MEMBER`; `posted_by_member_id` nullable only for SYSTEM; source type allows `GATEWAY_SETTLEMENT`; `ledger_entry.source_gateway_settlement_id` same-org FK; direct-source integrity allows at most one of charge/expense/gateway settlement.

- [ ] **Step 2: Run RED before V21 exists.**

```powershell
Set-Location "E:\project\AI-CostOps\backend"
.\mvnw.cmd -Dtest=GatewaySettlementSchemaIntegrationTest -Dgroups=integration test
```

Expected: FAIL on missing V21 schema.

- [ ] **Step 3: Implement V21 forward-only DDL.** Do not edit V13/V20. For the existing poster FK, drop/recreate only what is required to permit `posted_by_member_id=NULL` for SYSTEM; preserve same-org member validation for MEMBER rows. Add a CHECK equivalent to:

```text
(posting_actor_type='MEMBER' AND posted_by_member_id IS NOT NULL)
OR
(posting_actor_type='SYSTEM' AND posted_by_member_id IS NULL)
```

- [ ] **Step 4: Extend source integrity without breaking corrections.** Existing `chk_ledger_entry_source_xor` must be replaced by a forward V21 check whose sum across `source_charge_fact_id`, `source_expense_claim_id`, `source_gateway_settlement_id` is <= 1.

- [ ] **Step 5: Re-run the new schema test plus immutable Ledger schema/integrity tests.**

```powershell
.\mvnw.cmd -Dtest=GatewaySettlementSchemaIntegrationTest,LedgerFinancialInvariantIntegrationTest,LedgerCorrectionIntegrityIntegrationTest -Dgroups=integration test
```

Expected: all PASS.

- [ ] **Step 6: Commit.**

```powershell
git add backend/src/main/resources/db/migration/V21__m13_gateway_settlement.sql backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementSchemaIntegrationTest.java backend/src/test
git commit -m "feat(m13): add gateway settlement schema"
```

---

### Task 2: Settlement domain, persistence and discovery without lock inversion

**Files:**
- Create: `backend/src/main/java/com/aicostops/gatewaysettlement/domain/GatewaySettlement.java`
- Create: `backend/src/main/java/com/aicostops/gatewaysettlement/domain/GatewaySettlementStatus.java`
- Create: `backend/src/main/java/com/aicostops/gatewaysettlement/infrastructure/GatewaySettlementMapper.java`
- Create: `backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewaySettlementDiscoveryService.java`
- Create: `backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementDiscoveryIntegrationTest.java`

**Interfaces:**
- Settlement key is exactly `GATEWAY_REQUEST:<public_request_id>`.
- Discovery reads candidate current FINAL facts without holding a Settlement `FOR UPDATE` lock across financial processing.
- Duplicate discovery converges via DB uniqueness.

- [ ] **Step 1: Write RED integration tests** for one FINAL current fact -> one PENDING Settlement; same request rediscovered -> same row/no duplicate; non-current FINAL ignored; INCOMPLETE/UNKNOWN ignored; concurrent discovery produces one Settlement.

- [ ] **Step 2: Run RED.**

```powershell
.\mvnw.cmd -Dtest=GatewaySettlementDiscoveryIntegrationTest -Dgroups=integration test
```

- [ ] **Step 3: Implement bounded candidate discovery** using a deterministic `ORDER BY ... LIMIT`. Candidate ID reads must not take a long-lived Settlement row lock. Insert PENDING under business uniqueness and converge on duplicate key.

- [ ] **Step 4: Add retry candidate read** for `PENDING` and due `RETRYABLE_FAILED` ids without claiming them ahead of BillingPeriod.

- [ ] **Step 5: Re-run test.** Expected PASS.

- [ ] **Step 6: Commit.**

```powershell
git add backend/src/main/java/com/aicostops/gatewaysettlement backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementDiscoveryIntegrationTest.java
git commit -m "feat(m13): discover gateway settlements"
```

---

### Task 3: Frozen lineage reader and exact cost calculator

**Files:**
- Create: `backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewaySettlementLineageReader.java`
- Create: `backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewaySettlementCostCalculator.java`
- Create: `backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementCostCalculatorTest.java`
- Add mapper reads to `GatewaySettlementMapper.java` or a dedicated read mapper in the same module.

**Interfaces:**
- Input contains Route Attempt frozen Provider Account/Model/Pricing Version, FINAL Usage Fact dimensions and Pricing Rates for that exact version.
- Output record contains `calculatedAmountRaw`, `postedAmount`, `roundingDelta` as BigDecimal.

- [ ] **Step 1: Write RED unit tests** for exact formula `quantity * unit_price / unit_quantity`; multiple dimensions sum exactly; newer ACTIVE Pricing Version is ignored; missing priced dimension rejects/reconciliation; unsupported dimension rejects; tiny positive raw amount posts `0.00000001`; exact scale-8 amount has zero delta; non-scale-8 positive amount uses `RoundingMode.CEILING`; overflow outside `DECIMAL(38,18)`/`DECIMAL(20,8)` fails safely.

- [ ] **Step 2: Run RED.**

```powershell
.\mvnw.cmd -Dtest=GatewaySettlementCostCalculatorTest test
```

- [ ] **Step 3: Implement BigDecimal-only calculator.** No `double`/`float`; do not read `pricing_version status=ACTIVE` as a selection criterion; query by exact frozen id and org.

- [ ] **Step 4: Re-run unit test.** Expected PASS.

- [ ] **Step 5: Add integration lineage test** where route attempt freezes price V1, a newer V2 becomes ACTIVE, and calculation still uses V1.

- [ ] **Step 6: Commit.**

```powershell
git add backend/src/main/java/com/aicostops/gatewaysettlement backend/src/test/java/com/aicostops/gatewaysettlement
git commit -m "feat(m13): calculate frozen gateway cost"
```

---

### Task 4: First-class SYSTEM Ledger posting seam

**Files:**
- Modify: `backend/src/main/java/com/aicostops/ledger/domain/LedgerSourceType.java`
- Modify: `backend/src/main/java/com/aicostops/ledger/domain/LedgerPosting.java` if actor field is represented there
- Modify: `backend/src/main/java/com/aicostops/ledger/infrastructure/LedgerPostingMapper.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/GatewaySettlementLedgerPort.java`
- Create: `backend/src/main/java/com/aicostops/ledger/application/GatewaySettlementLedgerService.java`
- Create: `backend/src/test/java/com/aicostops/ledger/GatewaySettlementLedgerIntegrationTest.java`

**Interfaces:**
- `LedgerSourceType.GATEWAY_SETTLEMENT`.
- Stable posting key: `GATEWAY_SETTLEMENT:<settlementId>`.
- SYSTEM posting has `posted_by_member_id=NULL` and one COST entry with `source_gateway_settlement_id`.

- [ ] **Step 1: Write RED integration tests** for SYSTEM posting actor, exact source lineage, one target column, one posting per key, no fake member, existing MEMBER Provider/Expense posting still works.

- [ ] **Step 2: Run RED.**

```powershell
.\mvnw.cmd -Dtest=GatewaySettlementLedgerIntegrationTest,ProviderPostingConcurrencyIntegrationTest -Dgroups=integration test
```

- [ ] **Step 3: Extend Ledger models/mapper signatures deliberately.** Existing MEMBER callers must pass/retain MEMBER semantics. Do not weaken member validation globally in application code.

- [ ] **Step 4: Implement a narrow Gateway Settlement Ledger service instead of constructing synthetic ProviderCharge/allocation objects.**

- [ ] **Step 5: Re-run Ledger integration tests.** Expected PASS.

- [ ] **Step 6: Commit.**

```powershell
git add backend/src/main/java/com/aicostops/ledger backend/src/test/java/com/aicostops/ledger/GatewaySettlementLedgerIntegrationTest.java
git commit -m "feat(ledger): post gateway settlements as system"
```

---

### Task 5: Bound Budget, Commitment and Reservation financial ports

**Files:**
- Reuse/Modify: `backend/src/main/java/com/aicostops/budget/application/LedgerBudgetPort.java`
- Reuse/Modify: `backend/src/main/java/com/aicostops/budget/application/LedgerBudgetService.java`
- Create: `backend/src/main/java/com/aicostops/gatewaysettlement/infrastructure/GatewayReservationSettlementMapper.java`
- Create: `backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementBindingIntegrationTest.java`

**Interfaces:**
- Budget lock is by the reservation's `budget_id`, not a fresh scope selection.
- Reservation mapper exposes only read/lock + `ACTIVE/PENDING_HOLD -> FINALIZED` for settlement.
- Commitment consumption is only for an explicit `commitment_id`.

- [ ] **Step 1: Write RED tests** proving bound reservation selects exactly its Budget; no fallback re-selection; OPTIONAL unbudgeted request has no Budget mutation; RELEASED bound reservation is reconciliation-required; FINALIZED is not silently reposted; M12 null Commitment remains untouched; explicit Commitment locks/consumes using existing semantics.

- [ ] **Step 2: Run RED.**

```powershell
.\mvnw.cmd -Dtest=GatewaySettlementBindingIntegrationTest -Dgroups=integration test
```

- [ ] **Step 3: Add narrow reservation settlement SQL.** The only UPDATE permitted is:

```text
status IN ('ACTIVE','PENDING_HOLD')
-> status='FINALIZED', finalized_at=UTC_TIMESTAMP(6), version=version+1
```

qualified by org/id and expected state/version as appropriate. No create/release/resize/retarget SQL.

- [ ] **Step 4: Reuse `LedgerBudgetService.incrementActual(...)` for full posted amount.** Do not add availability/limit rejection at settlement.

- [ ] **Step 5: Re-run test.** Expected PASS.

- [ ] **Step 6: Commit.**

```powershell
git add backend/src/main/java/com/aicostops/budget backend/src/main/java/com/aicostops/gatewaysettlement backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementBindingIntegrationTest.java
git commit -m "feat(m13): bind settlement to reserved budget"
```

---

### Task 6: Atomic Settlement financial transaction

**Files:**
- Create: `backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewaySettlementService.java`
- Create: `backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewaySettlementAuditPort.java`
- Create/Reuse audit adapter in existing audit infrastructure following project pattern
- Create: `backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementTransactionIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementRollbackIntegrationTest.java`

**Interfaces:**
- Transaction owns the exact lock order and all financial mutation.
- Return result is idempotent SETTLED identity, never a trigger for duplicate mutation.

- [ ] **Step 1: Write RED happy-path test** proving one transaction produces: SETTLED settlement amounts; SYSTEM Ledger posting+entry; full Budget Actual increment; one audit event; reservation FINALIZED; all lineage ids match.

- [ ] **Step 2: Write RED overrun test** with reserved amount lower than Actual; assert full posted Actual increments Budget and reservation finalizes.

- [ ] **Step 3: Write RED OPTIONAL-unbudgeted test** proving Ledger+Settlement still post and no Budget/reservation mutation occurs.

- [ ] **Step 4: Write RED rollback tests** injecting failure after Ledger insert, after Actual increment, after Audit write and before SETTLED update; after rollback assert no partial posting/Actual/audit/finalization remains.

- [ ] **Step 5: Run RED focused integration tests.**

```powershell
.\mvnw.cmd -Dtest=GatewaySettlementTransactionIntegrationTest,GatewaySettlementRollbackIntegrationTest -Dgroups=integration test
```

- [ ] **Step 6: Implement transaction with `TransactionTemplate` or the existing proven Spring transaction pattern.** Exact ordered steps:

```text
lock BillingPeriod
lock Budget if present
lock explicit Commitment if present
lock reservation if present
lock Settlement
revalidate immutable lineage/current FINAL fact
converge Ledger posting key
calculate amount
insert Ledger posting/entry
increment full Budget Actual if present
consume explicit Commitment if present
write Audit
FINALIZE reservation if present
mark Settlement SETTLED
commit
```

- [ ] **Step 7: Re-run focused tests.** Expected PASS.

- [ ] **Step 8: Commit.**

```powershell
git add backend/src/main/java/com/aicostops/gatewaysettlement backend/src/test/java/com/aicostops/gatewaysettlement
git commit -m "feat(m13): atomically settle gateway usage"
```

---

### Task 7: Duplicate workers, lost response and retry states

**Files:**
- Create: `backend/src/main/java/com/aicostops/gatewaysettlement/application/GatewaySettlementWorker.java`
- Add configuration under existing Backend worker configuration pattern; default cadence/batch size must be bounded and testable
- Create: `backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementConcurrencyIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementRetryIntegrationTest.java`

**Interfaces:**
- Worker consumes candidate ids; it never claims Settlement before BillingPeriod.
- `RETRYABLE_FAILED` stores bounded reason code/attempt count/next attempt time after rollback.
- Semantic conflicts become `RECONCILIATION_REQUIRED` and stop automatic retry.

- [ ] **Step 1: Write RED concurrency test** with two workers processing same settlement concurrently; assert one Ledger posting, one entry, one Actual increment, one Audit, one FINALIZED reservation and one SETTLED result.

- [ ] **Step 2: Write RED simulated lost-response test**: first invocation commits, caller behaves as though result was lost, retry converges without mutation.

- [ ] **Step 3: Write RED retry-state tests** for transient DB failure -> RETRYABLE_FAILED with bounded backoff; due retry -> SETTLED; lineage mismatch/CLOSED period/RELEASED reservation -> RECONCILIATION_REQUIRED.

- [ ] **Step 4: Run RED.**

```powershell
.\mvnw.cmd -Dtest=GatewaySettlementConcurrencyIntegrationTest,GatewaySettlementRetryIntegrationTest -Dgroups=integration test
```

- [ ] **Step 5: Implement bounded worker without PROCESSING durable truth and without Settlement-first lock.** Do not persist stack traces or arbitrary exception messages.

- [ ] **Step 6: Re-run tests.** Expected PASS.

- [ ] **Step 7: Commit.**

```powershell
git add backend/src/main/java/com/aicostops/gatewaysettlement backend/src/test/java/com/aicostops/gatewaysettlement
git commit -m "feat(m13): retry gateway settlements safely"
```

---

### Task 8: Refine Gateway Close blocker and prove Close race

**Files:**
- Modify: `backend/src/main/java/com/aicostops/reconciliation/infrastructure/GatewayCloseBlockerMapper.java`
- Modify corresponding `CloseBlockerProvider` implementation if its logic is outside the mapper
- Create/Modify: `backend/src/test/java/com/aicostops/reconciliation/GatewayCloseBlockerIntegrationTest.java`
- Create: `backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementCloseConcurrencyIntegrationTest.java`

**Interfaces:**
- Reuse blocker code `PENDING_GATEWAY_FINANCIAL_WORK`.
- Financial truth, not transport state alone, decides whether a post-dispatch request remains a blocker.

- [ ] **Step 1: Write RED blocker matrix** for: no usage, INCOMPLETE, UNKNOWN, FINAL no Settlement, PENDING, RETRYABLE_FAILED, RECONCILIATION_REQUIRED, ACTIVE/PENDING_HOLD reservation => blocked; SETTLED + FINALIZED => not blocked; transport-failed request with FINAL+SETTLED => not blocked solely because transport failed.

- [ ] **Step 2: Write RED real-MySQL race test** where Settlement and Close contend on the same BillingPeriod row. Verify either Settlement commits before Close scan or Close sees unresolved work and refuses; Settlement never bypasses a CLOSED period.

- [ ] **Step 3: Run RED.**

```powershell
.\mvnw.cmd -Dtest=GatewayCloseBlockerIntegrationTest,GatewaySettlementCloseConcurrencyIntegrationTest -Dgroups=integration test
```

- [ ] **Step 4: Implement blocker query from durable current usage + settlement + effective reservation state.** Avoid N+1 unbounded scans; keep query period/org scoped.

- [ ] **Step 5: Re-run tests.** Expected PASS.

- [ ] **Step 6: Commit.**

```powershell
git add backend/src/main/java/com/aicostops/reconciliation backend/src/test/java/com/aicostops/reconciliation backend/src/test/java/com/aicostops/gatewaysettlement/GatewaySettlementCloseConcurrencyIntegrationTest.java
git commit -m "feat(m13): release close blocker after settlement"
```

---

### Task 9: Architecture, security and observability

**Files:**
- Add/modify Backend architecture tests under existing architecture package
- Add bounded Settlement metrics under existing observability conventions
- Add security integration tests for settlement/audit data

**Interfaces:**
- Backend settlement must not depend on Provider credential decryptor/provider HTTP adapters.
- Metrics labels are bounded status/provider/reason codes only.

- [ ] **Step 1: Add guards** proving no Provider secret access, no Gateway prompt/completion persistence, no request/org/credential ids as metric labels, no new Redis correctness dependency, no Provider I/O from settlement module.

- [ ] **Step 2: Add bounded metrics** `gateway_settlement_total{status}`, `gateway_settlement_retry_total{reason_code}`, `gateway_settlement_reconciliation_required_total{reason_code}`, `gateway_reservation_overrun_total{provider_code}`.

- [ ] **Step 3: Run Backend unit + architecture suites.**

```powershell
.\mvnw.cmd -B -DexcludedGroups=architecture,integration test
.\mvnw.cmd -B -Dgroups=architecture test
```

Expected: PASS.

- [ ] **Step 4: Commit.**

```powershell
git add backend/src/main backend/src/test
git commit -m "test(m13): guard settlement financial boundaries"
```

---

### Task 10: Full M13-B verification and evidence

**Files:**
- Create: `docs/03-acceptance/m13b-gateway-settlement-evidence.md`
- Optionally update the M13 design/evidence index only if existing repository pattern requires it.

- [ ] **Step 1: Verify diff hygiene.**

```powershell
Set-Location "E:\project\AI-CostOps"
git diff --check origin/main...HEAD
git status --short
```

- [ ] **Step 2: Run Backend unit/architecture/integration.**

```powershell
Set-Location backend
.\mvnw.cmd -B -DexcludedGroups=architecture,integration test
.\mvnw.cmd -B -Dgroups=architecture test
.\mvnw.cmd -B -Dgroups=integration verify
```

- [ ] **Step 3: Run Gateway unit/architecture/integration because M13-B consumes M13-A schema/runtime contracts and must not regress them.**

```powershell
Set-Location ..\gateway
.\mvnw.cmd -B -DexcludedGroups=architecture,integration test
.\mvnw.cmd -B -Dgroups=architecture test
.\mvnw.cmd -B -Dgroups=integration verify
```

- [ ] **Step 4: Record exact test counts, implementation SHA, Java/Maven versions, concurrency/rollback/overrun/Close-race evidence and explicit security/non-scope statements in the evidence doc.**

- [ ] **Step 5: Commit evidence.**

```powershell
Set-Location ..
git add docs/03-acceptance/m13b-gateway-settlement-evidence.md
git commit -m "docs(m13): record gateway settlement evidence"
```

- [ ] **Step 6: Push and open PR to `main` with `Closes #138`. Do not merge.** PR body must distinguish implementation SHA from later docs-only evidence SHA if applicable and list CI/Security results only after they actually complete.

---

## Plan self-review checklist

- V21 and Ledger forward migration are isolated to M13-B; V1-V20 remain immutable.
- Every financial mutation is covered by one atomic transaction and rollback tests.
- Frozen pricing, no missing-as-zero, scale-8 upward quantization and overrun semantics are explicit.
- Budget is not re-selected when reservation exists.
- Backend's reservation authority is only FINALIZED in Settlement transaction.
- Worker discovery does not invert lock order.
- Close blocker and Settlement race share the BillingPeriod fence.
- No Provider call/secret dependency appears in Settlement.
- M14/M15/FX/negative credits/new Commitment selection remain outside scope.