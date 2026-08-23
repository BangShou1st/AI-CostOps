-- M8 AIC-066: cover the tenant + billing-period BudgetMapper page order.
-- Create the ordering index before removing the redundant V11 filter-only
-- index, so the migration never intentionally leaves the page workload
-- without its replacement access path.
CREATE INDEX idx_budget_org_period_created_id
    ON budget(org_id, billing_period_id, created_at DESC, id DESC);

-- V11's (org_id,billing_period_id) index is redundant with the left prefix of
-- the new index. The FK uses the separately generated
-- fk_budget_period_org(billing_period_id,org_id) index, not this one.
DROP INDEX idx_budget_org_period ON budget;
