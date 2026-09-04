-- M13-B: Backend-owned Gateway settlement and the forward-only Ledger seam.
-- V1-V20 are immutable. No Provider or Gateway runtime writes this schema.

-- ---------------------------------------------------------------------------
-- 14.1 gateway_settlement
-- ---------------------------------------------------------------------------
CREATE TABLE gateway_settlement (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    settlement_key VARCHAR(96) NOT NULL,
    request_id BIGINT NOT NULL,
    route_attempt_id BIGINT NOT NULL,
    usage_fact_id BIGINT NOT NULL,
    reservation_id BIGINT NULL,
    billing_period_id BIGINT NOT NULL,
    financial_scope_type VARCHAR(32) NOT NULL,
    financial_scope_id BIGINT NOT NULL,
    provider_account_id BIGINT NOT NULL,
    provider_model_id BIGINT NOT NULL,
    pricing_version_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    calculated_amount_raw DECIMAL(38,18) NULL,
    posted_amount DECIMAL(20,8) NULL,
    rounding_delta DECIMAL(38,18) NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    last_error_code VARCHAR(64) NULL,
    ledger_posting_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    settled_at DATETIME(6) NULL,
    reconciliation_required_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_gateway_settlement_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_gateway_settlement_org_key UNIQUE (org_id, settlement_key),
    CONSTRAINT uq_gateway_settlement_org_request UNIQUE (org_id, request_id),
    CONSTRAINT uq_gateway_settlement_org_usage UNIQUE (org_id, usage_fact_id),
    CONSTRAINT fk_gateway_settlement_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_gateway_settlement_request_org
        FOREIGN KEY (request_id, org_id) REFERENCES gateway_request (id, org_id),
    CONSTRAINT fk_gateway_settlement_attempt_org
        FOREIGN KEY (route_attempt_id, org_id)
        REFERENCES gateway_route_attempt (id, org_id),
    CONSTRAINT fk_gateway_settlement_usage_org
        FOREIGN KEY (usage_fact_id, org_id)
        REFERENCES gateway_usage_fact (id, org_id),
    CONSTRAINT fk_gateway_settlement_reservation_org
        FOREIGN KEY (reservation_id, org_id)
        REFERENCES budget_reservation (id, org_id),
    CONSTRAINT fk_gateway_settlement_period_org
        FOREIGN KEY (billing_period_id, org_id)
        REFERENCES billing_period (id, org_id),
    CONSTRAINT fk_gateway_settlement_account_org
        FOREIGN KEY (provider_account_id, org_id)
        REFERENCES provider_account (id, org_id),
    CONSTRAINT fk_gateway_settlement_provider_model
        FOREIGN KEY (provider_model_id) REFERENCES provider_model (id),
    CONSTRAINT fk_gateway_settlement_pricing_org
        FOREIGN KEY (pricing_version_id, org_id)
        REFERENCES pricing_version (id, org_id),
    CONSTRAINT fk_gateway_settlement_ledger_org
        FOREIGN KEY (ledger_posting_id, org_id)
        REFERENCES ledger_posting (id, org_id),
    CONSTRAINT chk_gateway_settlement_scope_type
        CHECK (financial_scope_type IN ('PROJECT','TEAM','COST_CENTER')),
    CONSTRAINT chk_gateway_settlement_currency
        CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$')),
    CONSTRAINT chk_gateway_settlement_status CHECK (
        status IN ('PENDING','RETRYABLE_FAILED','RECONCILIATION_REQUIRED','SETTLED')),
    CONSTRAINT chk_gateway_settlement_attempt CHECK (attempt_count >= 0),
    CONSTRAINT chk_gateway_settlement_amounts CHECK (
        (calculated_amount_raw IS NULL OR calculated_amount_raw >= 0)
        AND (posted_amount IS NULL OR posted_amount >= 0)
        AND (status <> 'SETTLED'
            OR (calculated_amount_raw IS NOT NULL AND posted_amount IS NOT NULL
                AND rounding_delta IS NOT NULL AND ledger_posting_id IS NOT NULL
                AND settled_at IS NOT NULL))),
    KEY idx_gateway_settlement_discovery (org_id, status, next_attempt_at, id),
    KEY idx_gateway_settlement_period (org_id, billing_period_id, status, id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 21.1 Ledger actor/source forward extension
-- ---------------------------------------------------------------------------
ALTER TABLE ledger_posting
    ADD COLUMN posting_actor_type VARCHAR(16) NOT NULL DEFAULT 'MEMBER' AFTER status,
    MODIFY COLUMN posted_by_member_id BIGINT NULL;

ALTER TABLE ledger_posting
    DROP CHECK chk_ledger_posting_source_type,
    ADD CONSTRAINT chk_ledger_posting_source_type CHECK (
        source_type IN ('PROVIDER_CHARGE','EXPENSE_CLAIM','CORRECTION','GATEWAY_SETTLEMENT')),
    ADD CONSTRAINT chk_ledger_posting_actor CHECK (
        (posting_actor_type = 'MEMBER' AND posted_by_member_id IS NOT NULL)
        OR (posting_actor_type = 'SYSTEM' AND posted_by_member_id IS NULL));

ALTER TABLE ledger_entry
    ADD COLUMN source_gateway_settlement_id BIGINT NULL AFTER source_expense_claim_id;

ALTER TABLE ledger_entry
    DROP CHECK chk_ledger_entry_source_xor,
    ADD CONSTRAINT chk_ledger_entry_source_xor CHECK (
        (source_charge_fact_id IS NOT NULL)
        + (source_expense_claim_id IS NOT NULL)
        + (source_gateway_settlement_id IS NOT NULL) <= 1),
    ADD CONSTRAINT fk_ledger_entry_gateway_settlement_org
        FOREIGN KEY (source_gateway_settlement_id, org_id)
        REFERENCES gateway_settlement (id, org_id),
    ADD KEY idx_ledger_entry_org_source_gateway_settlement
        (org_id, source_gateway_settlement_id);
