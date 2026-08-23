# M8 Stage 1 / PR1 — AIC-066 Schema / Query Index Review

## Baseline and environment

- Starting main SHA: `02df36df87634256a659855707411941589e55f3`.
- Review branch: `perf/m8-performance-hardening`.
- Schema review ran against a clean Flyway database with migrations V1–V17.
- MySQL: `8.4.11`, Testcontainers image `mysql:8.4`.
- EXPLAIN harness: `com.aicostops.M8SchemaQueryExplainIntegrationTest`.
- EXPLAIN ANALYZE was not used; ordinary `EXPLAIN` was sufficient and kept the
  integration run deterministic.

## Schema summary

The review covered the actual tables present in the V1–V16 schema, including
`audit_event`, `evidence`, `import_batch`, `import_attempt`,
`raw_provider_record`, `import_issue`, `external_document`,
`consumption_fact`, `pricing_fact`, `charge_fact`, `attribution_hint`,
`duplicate_candidate`, allocation tables, `budget`, `budget_commitment`,
`expense_claim`, ledger posting/entry tables, `billing_period`,
`reconciliation_run`, `reconciliation_case`, and period-close tables.

Important existing access paths were confirmed before changing anything:

- Import workflow: `idx_import_attempt_queue(status, available_at, id)`,
  `idx_import_attempt_lease(status, lease_until, id)`,
  `idx_import_attempt_batch_status(import_batch_id, status, id)`, the V7
  `import_batch` organization/status/provider indexes, and the V7 raw-record
  and issue review indexes.
- Canonical cost: `idx_charge_fact_org_review(org_id, review_status)`,
  `idx_charge_fact_org_provider_period(org_id, provider_code, period_start)`,
  and the raw-record/fact uniqueness key used by lineage joins.
- Duplicate review: `idx_duplicate_candidate_org_status`,
  `idx_duplicate_candidate_org_charge`, and
  `idx_duplicate_candidate_org_matched`.
- Budget: `idx_budget_org_period_created_id(org_id, billing_period_id,
  created_at DESC, id DESC)`, `idx_budget_org_scope(org_id, scope_type,
  scope_id)`, the identity uniqueness key, and the FK-support index
  `fk_budget_period_org(billing_period_id, org_id)`. The redundant V11
  filter-only index `idx_budget_org_period(org_id, billing_period_id)` is
  removed by V17.
- Ledger/reconciliation/close: the existing organization + period/status/date
  indexes in V13 and V16, including the V16 period-close read indexes.

## Query inventory

The inventory was built from the actual Mapper and service SQL, not from
assumed table names:

| Workload | Actual path reviewed |
| --- | --- |
| Import queue / worker claim and lease recovery | `ImportAttemptMapper`, `ImportLeaseService` |
| Import review and raw-record review | `ImportWorkflowQueryMapper`, `ImportWorkflowQueryService` |
| Canonical charge lookup and publication lineage | `CostFactMapper`, `ChargePostingMapper`, `CanonicalFactsMapper` |
| Duplicate review | `DuplicateCandidateMapper` |
| Allocation lookup | `AllocationDecisionMapper`, `AllocationChargeFactMapper`, allocation repositories |
| Expense workflow hot reads | `ExpenseClaimMapper`, approval-case queries |
| Budget and commitment lookup | `BudgetMapper`, `BudgetCommitmentMapper`, `BillingPeriodMapper` |
| Ledger posting/read | `LedgerPostingMapper`, `LedgerQueryMapper` |
| Reconciliation and period-close blockers | `ReconciliationMapper`, `PeriodCloseMapper`, close-blocker adapters |
| Workbench | `WorkbenchQueryMapper` |
| Audit query | `AuditMapper` |

## EXPLAIN evidence

The fixture uses one organization, 240 confirmed DeepSeek charge-lineage rows,
120 open duplicate candidates, and 10,000 period-scoped budgets. The larger
budget cardinality is intentional: a 240-row toy table caused the optimizer to
reasonably prefer a scan even when an ordering index existed.

### Duplicate Review

Query: `DuplicateCandidateMapper.pageCandidates` with tenant, `OPEN` status,
`EXACT` type, newest-first ordering and a 50-row page.

- Before (schema V16, first 120-row sample): `type=ALL`, `rows=120`,
  `Extra=Using where; Using filesort`.
- After/current schema: `type=range`, `key=idx_duplicate_candidate_org_status`,
  `rows=120`, `Extra=Using index condition; Using where; Backward index scan`.
- The same harness rerun inside the full integration suite selected
  `type=ALL`, `rows=120`, `Extra=Using where; Using filesort` again. This is
  an optimizer/statistics choice for the small synthetic table, not a stable
  regression caused by V17.
- Charge-specific review query uses `index_merge` over the charge/matched
  foreign-key indexes and sorts a small candidate set.
- Decision: no new duplicate-review index. The existing status index matches
  the primary page workload; the small-table scan is not a blocker, and the
  OR-by-charge query would require a different query shape before another index
  could be justified. The plan variance was not attributed to the budget
  migration; it is optimizer/cardinality/statistics-sensitive.

### Budget Lookup and Short-Index Redundancy

Query: `BudgetMapper.selectPage` with `org_id`, `billing_period_id`, newest
`created_at/id` ordering and a 50-row page.

- Before, on the same 10,000-row fixture with the new index explicitly ignored:
  `type=ref`, `key=uq_budget_identity`, `rows=4917`,
  `Extra=Using filesort`. This is the pre-ordering-index comparison.
- With both V11 and V17 indexes present, the real period page chose
  `idx_budget_org_period_created_id`, with `rows=4885` and `Extra=null`.
- After dropping only `idx_budget_org_period`, the same period page still chose
  `idx_budget_org_period_created_id`, with `rows=4459` and `Extra=null`.
- The period count used the FK-support index `fk_budget_period_org` with
  `Using index` both before and after. Identity lookup used
  `uq_budget_identity` with `type=const` and `Using index` both before and
  after. The order-by-id and integrity probes did not gain an independent
  short-index plan; they used a primary/FK-support access path with the same
  filtered result shape.
- `SHOW INDEX FROM budget` and `information_schema.KEY_COLUMN_USAGE` confirmed
  that `idx_budget_org_period` is not an FK dependency. The FK-support index
  is separately named `fk_budget_period_org(billing_period_id, org_id)`.
- Decision: add one forward-only index in V17 and remove the redundant V11
  filter-only index:
  `idx_budget_org_period_created_id(org_id, billing_period_id, created_at DESC, id DESC)`.
  It solves the real period-scoped page query, does not duplicate the existing
  scope index, and removes the observed filesort at representative cardinality.
  The shorter index has no independent workload value once the longer index is
  present and is not required by the FK.

### Workbench

Queries: `WorkbenchQueryMapper.sumChargesByProvider` and
`sumUnallocatedByCurrency`, which share the confirmed-import, CLEAN-charge,
period-bounded lineage basis.

- Before/current join shape: `import_attempt` uses the small covering
  `uq_import_attempt_batch_no` index; `import_batch` is `eq_ref` on PRIMARY;
  `raw_provider_record` is `ref` on `uq_raw_provider_record_attempt_index`;
  `charge_fact` is `ref` on `uq_charge_fact_raw_fact`; allocation lookup is
  `eq_ref` on PRIMARY where present.
- `Using temporary; Using filesort` appears on the aggregate driver because the
  query groups and orders by computed sums. This is not evidence that a simple
  lookup index would remove the aggregation work.
- Decision: no Workbench index change. The join keys are indexed and the
  aggregate sort is intrinsic to the requested result. A larger multi-attempt,
  multi-period production-shaped benchmark is a follow-up, not a speculative
  index change in this PR.

### Import Queue / Claim

`ImportAttemptMapper.claimNextQueued` uses `type=range`,
`key=idx_import_attempt_queue`, `rows=1`, `Extra=Using index condition`.
Decision: no change.

### Other reviewed paths

Import review pages use the V7 organization/status/provider indexes and bounded
SQL `LIMIT/OFFSET`; raw-record pages use the attempt/index ordering key. Ledger,
reconciliation, period-close, expense and audit reads all have tenant-first
access paths matching their current predicates. No additional index had a
measured, non-overlapping benefit in this review.

## Index changes

- Added: `backend/src/main/resources/db/migration/V17__m8_budget_lookup_index.sql`
  creates `idx_budget_org_period_created_id` before removing the redundant
  V11 filter-only index.
- Modified: none.
- Dropped by V17: `idx_budget_org_period` only. The FK-support
  `fk_budget_period_org` index remains.
- Production Java: none.

## Limitations and follow-ups

- EXPLAIN is based on representative synthetic cardinalities, not production
  statistics; the plan should be rechecked after materially different tenant
  volumes or data distributions.
- EXPLAIN ANALYZE, live query latency, and concurrent-reader plans were not
  required for this PR and were not collected.
- No AIC-068–AIC-073 work was started. Any broader concurrency, Redis, MinIO,
  security, or compose findings remain outside this PR.
