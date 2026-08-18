CREATE TABLE billing_period (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    close_generation BIGINT NOT NULL DEFAULT 0,
    closing_started_at DATETIME(6) NULL,
    closed_at DATETIME(6) NULL,
    reopened_at DATETIME(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_billing_period_id_org (id, org_id),
    UNIQUE KEY uq_billing_period_org_range (org_id, period_start, period_end),
    KEY idx_billing_period_org_status_range (org_id, status, period_start, period_end),
    CONSTRAINT fk_billing_period_org
        FOREIGN KEY (org_id) REFERENCES organization(id),
    CONSTRAINT chk_billing_period_range
        CHECK (period_start < period_end),
    CONSTRAINT chk_billing_period_status
        CHECK (status IN ('OPEN', 'CLOSING', 'CLOSED')),
    CONSTRAINT chk_billing_period_close_generation
        CHECK (close_generation >= 0),
    CONSTRAINT chk_billing_period_version
        CHECK (version >= 0)
);

CREATE TABLE budget (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    billing_period_id BIGINT NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    total_amount DECIMAL(20,8) NOT NULL DEFAULT 0.00000000,
    actual_amount DECIMAL(20,8) NOT NULL DEFAULT 0.00000000,
    committed_amount DECIMAL(20,8) NOT NULL DEFAULT 0.00000000,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_budget_id_org (id, org_id),
    UNIQUE KEY uq_budget_scope_currency (
        org_id, billing_period_id, scope_type, scope_id, currency
    ),
    KEY idx_budget_org_period_status (org_id, billing_period_id, status),
    KEY idx_budget_org_scope (org_id, scope_type, scope_id),
    CONSTRAINT fk_budget_org
        FOREIGN KEY (org_id) REFERENCES organization(id),
    CONSTRAINT fk_budget_period_org
        FOREIGN KEY (billing_period_id, org_id)
        REFERENCES billing_period(id, org_id),
    CONSTRAINT chk_budget_total_amount
        CHECK (total_amount >= 0),
    CONSTRAINT chk_budget_committed_amount
        CHECK (committed_amount >= 0),
    CONSTRAINT chk_budget_version
        CHECK (version >= 0)
);

CREATE TABLE budget_commitment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    budget_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    requested_amount DECIMAL(20,8) NOT NULL,
    approved_amount DECIMAL(20,8) NULL,
    remaining_amount DECIMAL(20,8) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_budget_commitment_id_org (id, org_id),
    KEY idx_budget_commitment_org_budget_status (org_id, budget_id, status),
    CONSTRAINT fk_budget_commitment_org
        FOREIGN KEY (org_id) REFERENCES organization(id),
    CONSTRAINT fk_budget_commitment_budget_org
        FOREIGN KEY (budget_id, org_id)
        REFERENCES budget(id, org_id),
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
    CONSTRAINT chk_budget_commitment_requested_amount
        CHECK (requested_amount > 0),
    CONSTRAINT chk_budget_commitment_approved_amount
        CHECK (approved_amount IS NULL OR approved_amount >= 0),
    CONSTRAINT chk_budget_commitment_remaining_amount
        CHECK (remaining_amount IS NULL OR remaining_amount >= 0),
    CONSTRAINT chk_budget_commitment_version
        CHECK (version >= 0)
);

CREATE TABLE budget_commitment_usage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    budget_commitment_id BIGINT NOT NULL,
    ledger_entry_id BIGINT NOT NULL,
    consumed_amount DECIMAL(20,8) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uq_budget_commitment_usage_lineage (
        org_id, budget_commitment_id, ledger_entry_id
    ),
    KEY idx_budget_commitment_usage_org_commitment (
        org_id, budget_commitment_id
    ),
    KEY idx_budget_commitment_usage_org_ledger_entry (
        org_id, ledger_entry_id
    ),
    CONSTRAINT fk_budget_commitment_usage_org
        FOREIGN KEY (org_id) REFERENCES organization(id),
    CONSTRAINT fk_budget_commitment_usage_commitment_org
        FOREIGN KEY (budget_commitment_id, org_id)
        REFERENCES budget_commitment(id, org_id),
    CONSTRAINT chk_budget_commitment_usage_consumed_amount
        CHECK (consumed_amount > 0)
);
