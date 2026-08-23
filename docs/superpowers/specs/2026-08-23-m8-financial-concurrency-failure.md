# M8 Stage 1 / PR2 — AIC-068 Financial Concurrency and Failure Evidence

Date: 2026-08-23
Baseline: `c324742`
Branch: `test/m8-resilience-security`

## Protected invariants

- `budget.committed_amount` never becomes negative and never exceeds
  `total_amount - actual_amount`.
- A financial mutation either commits all ledger, budget, commitment, usage,
  source-state, idempotency, and audit rows required by its existing contract,
  or commits none of them.
- A business posting key identifies one authoritative posting and one set of
  ledger entries.
- The period write fence serializes Close admission with eligible financial
  writes; a closed/closing period cannot accept a late post.
- Idempotent replay is deterministic and does not repeat financial side effects.
- Deadlock retry is finite and limited to the existing transient database
  exceptions.

## Evidence matrix

| Case | Setup / fault or race | Expected invariant | Observed result | Bug / fix | Final result | Evidence |
|---|---|---|---|---|---|---|
| 100 concurrent commitments | Real MySQL Testcontainer; one `10.00000000` budget, 100 requested commitments of `1.00000000`, 16 executor workers released by `CountDownLatch`. | Exactly ten activations; 90 explainable `BUDGET_INSUFFICIENT` outcomes; no lost update, oversell, negative counter, or half approval state. | 10 `ACTIVE`, 90 `REQUESTED`; committed amount `10.00000000`; budget version 10; exactly 10 activation audits. | No production bug found. | PASS | `CommitmentActivationConcurrencyIntegrationTest.hundredConcurrentActivationsConvergeToExactCapacity` |
| Duplicate ledger post | Two real provider-post transactions start together for the same charge/allocation; stable posting key and MySQL uniqueness are exercised. | One authoritative posting, one ledger entry, one audit, identical returned posting identity. | Both calls converge to the same posting; counts are one posting, one entry, one audit. | No production bug found. | PASS | `ProviderPostingConcurrencyIntegrationTest.concurrentDuplicatePostsCreateOnePostingAndOneEntry` |
| Duplicate/idempotent commitment activation | Same commitment/same idempotency key raced, then same commitment/different keys raced. | Same-key replay is deterministic; different keys have one winner; no duplicate approval action or audit. | Same-key calls both return `ACTIVE` with one financial mutation; different keys produce one `ACTIVE` and one state conflict. | No production bug found. | PASS | `CommitmentActivationConcurrencyIntegrationTest` two same-commitment cases |
| Transaction rollback | Provider posting writes posting, entries, budget actual, and commitment usage, then a test-only audit adapter fails inside the transaction. | No partial ledger row, counter mutation, usage row, source advancement, idempotency success, or audit success. | Posting/entries/usage/audit counts remain zero; budget actual/version and commitment remaining remain unchanged. | No production bug found. | PASS | `ProviderPostingRollbackIntegrationTest.auditFailureRollsBackPostingEntriesActualAndCommitmentUsage`; `CommitmentMutationRollbackIntegrationTest` |
| Period Close / post fence | Real MySQL provider posting and Close transactions race on the actual billing-period row lock. Close acquires the period fence and pauses; eligible Ledger Post starts concurrently and blocks at its `lockOpenAt` fence until Close commits `CLOSING`. | The winner’s committed boundary is observed; no post can commit after the period becomes closing/closed. | Close commits `CLOSING`; the blocked provider post fails closed with no posting, entries, budget actual, commitment usage, or audit mutation. A committed post also replays without mutation after the period is closed. | No production bug found. | PASS | `ProviderChargePostingIntegrationTest.closeWinningRaceRejectsEligibleLedgerPostWithoutFinancialMutation`; `KnownPeriodCloseRaceIntegrationTest`; `BillingPeriodFinancialWriteFenceIntegrationTest`; `ProviderChargePostingIntegrationTest.committedPostingReplaysAfterPeriodClosesWithoutMutation` |
| Bounded deadlock retry | Deterministic test fault supplier emits deadlock twice, then succeeds; a second case emits deadlock forever; validation is a non-retry control. | Transient deadlock recovers within the bound; persistent deadlock fails after the bound; validation is not retried. | Attempts are exactly 3 for both transient recovery and persistent failure, and exactly 1 for validation. | No production bug found. | PASS | `M8FinancialDeadlockRetryTest` |

## Existing controls confirmed

`BudgetCommitmentCommandService` performs the bounded transaction retry around
`TransactionTemplate`; activation locks the open period, locks the budget and
commitment, and applies a conditional capacity update. Provider posting uses the
frozen period → budget → commitment → source → decision → line order, a stable
unique posting key, and a fresh transaction for duplicate-key convergence.

## Scope note

Compose fresh-volume behavior, Quick Start/README changes, release packaging,
and tags/releases are outside AIC-068 and remain follow-up scope for AIC-071 or
AIC-072. No production financial isolation, precision, uniqueness, or retry
guarantee was weakened.
