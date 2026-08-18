-- M4 Group 3: billing period + budget foundation.
-- billing_period / budget / budget_commitment / budget_commitment_usage.
--
-- Global rules honored here: InnoDB, utf8mb4, money DECIMAL(20,8),
-- currency CHAR(3), UTC DATETIME(6), no hard delete of financial history,
-- same-org composite FKs (UNIQUE(id, org_id) targets everywhere a child row
-- can reference a parent of its own organization only).
--
-- billing_period.status foundation is OPEN | CLOSING | CLOSED. This PR only
-- establishes the state foundation and the OPEN guard; real Close / Reopen
-- commands are later milestones (AIC-058) and add no column here.
--
-- budget.status is intentionally constrained to the single frozen value the
-- later Atomic Activation UPDATE (AIC-044) depends on (status='ACTIVE'):
-- no invented full status enum. Any future status requires an explicit
-- forward-only migration.
--
-- budget.actual_amount carries NO non-negative CHECK: credits / reversals
-- can legitimately drive actual below zero. total_amount and
-- committed_amount must never be negative. available = total - actual -
-- committed is computed by the application read model, not stored as a
-- second authoritative column.
--
-- budget_commitment_usage is append-only consumption lineage. ledger_entry
-- does not exist yet (AIC-047), so ledger_entry_id stays a plain BIGINT NOT
-- NULL with no FK; the FK is added once ledger_entry is created. The stable
-- UNIQUE(org_id, budget_commitment_id, ledger_entry_id) already prevents one
-- ledger entry from consuming the same commitment twice.

-- 1. billing_period
CREATE TABLE billing_period (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    period_start DATETIME(6) NOT NULL,
    period_end DATETIME(6) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    close_generation BIGINT NOT NULL DEFAULT 0,
    closing_started_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    reopened_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_billing_period_id_org
        UNIQUE (id, org_id),

    -- Same-organization period identity: the exact half-open range
    -- [period_start, period_end) cannot repeat within one organization.
    -- Overlapping (but not identical) ranges are a data anomaly the schema
    -- cannot fully exclude on MySQL; the OPEN guard stays deterministic.
    CONSTRAINT uq_billing_period_org_range
        UNIQUE (org_id, period_start, period_end),

    CONSTRAINT fk_billing_period_org
        FOREIGN KEY (org_id) REFERENCES organization (id),

    CONSTRAINT chk_billing_period_range
        CHECK (period_start < period_end),

    CONSTRAINT chk_billing_period_status
        CHECK (status IN ('OPEN','CLOSING','CLOSED')),

    CONSTRAINT chk_billing_period_close_generation
        CHECK (close_generation >= 0),

    CONSTRAINT chk_billing_period_version
        CHECK (version >= 0),

    KEY idx_billing_period_org_status
        (org_id, status, period_start, id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- 2. budget
CREATE TABLE budget (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    billing_period_id BIGINT NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    total_amount DECIMAL(20,8) NOT NULL,
    actual_amount DECIMAL(20,8) NOT NULL DEFAULT 0,
    committed_amount DECIMAL(20,8) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_budget_id_org
        UNIQUE (id, org_id),

    -- Polymorphic scope identity: one budget per period/scope/currency.
    CONSTRAINT uq_budget_identity
        UNIQUE (org_id, billing_period_id, scope_type, scope_id, currency),

    CONSTRAINT fk_budget_org
        FOREIGN KEY (org_id) REFERENCES organization (id),

    -- Same-org period integrity: a budget can only reference a billing
    -- period of its own organization.
    CONSTRAINT fk_budget_period_org
        FOREIGN KEY (billing_period_id, org_id)
        REFERENCES billing_period (id, org_id),

    CONSTRAINT chk_budget_scope_type
        CHECK (scope_type IN ('ORG','PROJECT','TEAM','COST_CENTER')),

    CONSTRAINT chk_budget_currency
        CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$')),

    -- Total and outstanding commitment are never negative, but actual is
    -- deliberately unconstrained (credits/reversals may drive it negative).
    CONSTRAINT chk_budget_total_amount
        CHECK (total_amount >= 0),

    CONSTRAINT chk_budget_committed_amount
        CHECK (committed_amount >= 0),

    -- Frozen foundation only: AIC-044 Activation requires status='ACTIVE'.
    CONSTRAINT chk_budget_status
        CHECK (status IN ('ACTIVE')),

    CONSTRAINT chk_budget_version
        CHECK (version >= 0),

    KEY idx_budget_org_period
        (org_id, billing_period_id),

    KEY idx_budget_org_scope
        (org_id, scope_type, scope_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- 3. budget_commitment
CREATE TABLE budget_commitment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    budget_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_amount DECIMAL(20,8) NOT NULL,
    approved_amount DECIMAL(20,8) NULL,
    remaining_amount DECIMAL(20,8) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_budget_commitment_id_org
        UNIQUE (id, org_id),

    CONSTRAINT fk_budget_commitment_org
        FOREIGN KEY (org_id) REFERENCES organization (id),

    -- Same-org budget integrity: a commitment can only reference a budget of
    -- its own organization.
    CONSTRAINT fk_budget_commitment_budget_org
        FOREIGN KEY (budget_id, org_id)
        REFERENCES budget (id, org_id),

    CONSTRAINT chk_budget_commitment_status
        CHECK (status IN (
            'REQUESTED',
            'ACTIVE',
            'PARTIALLY_CONSUMED',
            'CONSUMED',
            'RELEASED',
            'REJECTED',
            'CANCELED'
        )),

    CONSTRAINT chk_budget_commitment_requested
        CHECK (requested_amount > 0),

    CONSTRAINT chk_budget_commitment_approved
        CHECK (approved_amount IS NULL OR approved_amount >= 0),

    CONSTRAINT chk_budget_commitment_remaining
        CHECK (remaining_amount IS NULL OR remaining_amount >= 0),

    CONSTRAINT chk_budget_commitment_version
        CHECK (version >= 0),

    KEY idx_budget_commitment_org_budget
        (org_id, budget_id, status)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- 4. budget_commitment_usage (append-only lineage foundation)
CREATE TABLE budget_commitment_usage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    budget_commitment_id BIGINT NOT NULL,
    ledger_entry_id BIGINT NOT NULL,
    consumed_amount DECIMAL(20,8) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_budget_commitment_usage_id_org
        UNIQUE (id, org_id),

    -- Stable lineage uniqueness: one ledger entry can consume a given
    -- commitment at most once.
    CONSTRAINT uq_budget_commitment_usage_lineage
        UNIQUE (org_id, budget_commitment_id, ledger_entry_id),

    CONSTRAINT fk_budget_commitment_usage_org
        FOREIGN KEY (org_id) REFERENCES organization (id),

    -- Same-org commitment integrity. ledger_entry_id deliberately has no FK
    -- yet: ledger_entry is created in AIC-047.
    CONSTRAINT fk_budget_commitment_usage_commitment_org
        FOREIGN KEY (budget_commitment_id, org_id)
        REFERENCES budget_commitment (id, org_id),

    CONSTRAINT chk_budget_commitment_usage_consumed
        CHECK (consumed_amount > 0),

    KEY idx_budget_commitment_usage_org_commitment
        (org_id, budget_commitment_id, created_at, id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;