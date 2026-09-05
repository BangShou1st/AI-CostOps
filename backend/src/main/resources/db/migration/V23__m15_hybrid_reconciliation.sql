-- M15: hybrid reconciliation terminal lineage.
-- V1-V22 are immutable. This migration adds the M15 bounded evidence,
-- Charge disposition, Reconciliation Adjustment, Gateway financial resolution
-- tables plus the Ledger forward extension, and backfills already-posted
-- Provider Charge history as DIRECT_PROVIDER_CHARGE / LEGACY_POSTED so M15
-- never reinterprets committed Ledger history as Gateway-covered evidence.

-- ---------------------------------------------------------------------------
-- provider_charge_disposition
-- One immutable final posting disposition per canonical Charge.
-- ---------------------------------------------------------------------------
CREATE TABLE provider_charge_disposition (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    charge_fact_id BIGINT NOT NULL,
    disposition VARCHAR(32) NOT NULL,
    decision_source VARCHAR(32) NOT NULL,
    reconciliation_run_id BIGINT NULL,
    reconciliation_case_id BIGINT NULL,
    decided_by_member_id BIGINT NULL,
    reason_code VARCHAR(100) NULL,
    resolution_note VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_provider_charge_disposition_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_provider_charge_disposition_org_charge UNIQUE (org_id, charge_fact_id),
    CONSTRAINT fk_provider_charge_disposition_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_provider_charge_disposition_charge_org
        FOREIGN KEY (charge_fact_id, org_id) REFERENCES charge_fact (id, org_id),
    CONSTRAINT fk_provider_charge_disposition_run_org
        FOREIGN KEY (reconciliation_run_id, org_id) REFERENCES reconciliation_run (id, org_id),
    CONSTRAINT fk_provider_charge_disposition_case_org
        FOREIGN KEY (reconciliation_case_id, org_id) REFERENCES reconciliation_case (id, org_id),
    CONSTRAINT fk_provider_charge_disposition_member_org
        FOREIGN KEY (decided_by_member_id, org_id) REFERENCES organization_member (id, org_id),
    CONSTRAINT chk_provider_charge_disposition_value
        CHECK (disposition IN ('RECONCILIATION_EVIDENCE','DIRECT_PROVIDER_CHARGE')),
    CONSTRAINT chk_provider_charge_disposition_source
        CHECK (decision_source IN ('LEGACY_POSTED','SYSTEM_EXACT','MANUAL')),
    CONSTRAINT chk_provider_charge_disposition_actor CHECK (
        (decision_source = 'MANUAL'
            AND decided_by_member_id IS NOT NULL
            AND reason_code IS NOT NULL
            AND CHAR_LENGTH(TRIM(reason_code)) > 0
            AND resolution_note IS NOT NULL
            AND CHAR_LENGTH(TRIM(resolution_note)) > 0)
        OR
        (decision_source IN ('LEGACY_POSTED','SYSTEM_EXACT')
            AND decided_by_member_id IS NULL)),
    CONSTRAINT chk_provider_charge_disposition_system_lineage CHECK (
        decision_source <> 'SYSTEM_EXACT' OR reconciliation_run_id IS NOT NULL)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- reconciliation_adjustment
-- First-class append-only Ledger source for reviewed reconciliation money.
-- ---------------------------------------------------------------------------
CREATE TABLE reconciliation_adjustment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    reconciliation_run_id BIGINT NOT NULL,
    reconciliation_case_id BIGINT NULL,
    adjustment_key VARCHAR(96) NOT NULL,
    adjustment_scope VARCHAR(32) NOT NULL,
    provider_account_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    amount DECIMAL(20,8) NOT NULL,
    adjustment_period_id BIGINT NOT NULL,
    gateway_request_id BIGINT NULL,
    gateway_route_attempt_id BIGINT NULL,
    statement_charge_fact_id BIGINT NULL,
    created_by_member_id BIGINT NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    reason_note VARCHAR(2000) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_reconciliation_adjustment_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_reconciliation_adjustment_org_key UNIQUE (org_id, adjustment_key),
    CONSTRAINT fk_reconciliation_adjustment_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_reconciliation_adjustment_run_org
        FOREIGN KEY (reconciliation_run_id, org_id) REFERENCES reconciliation_run (id, org_id),
    CONSTRAINT fk_reconciliation_adjustment_case_org
        FOREIGN KEY (reconciliation_case_id, org_id) REFERENCES reconciliation_case (id, org_id),
    CONSTRAINT fk_reconciliation_adjustment_account_org
        FOREIGN KEY (provider_account_id, org_id) REFERENCES provider_account (id, org_id),
    CONSTRAINT fk_reconciliation_adjustment_period_org
        FOREIGN KEY (adjustment_period_id, org_id) REFERENCES billing_period (id, org_id),
    CONSTRAINT fk_reconciliation_adjustment_request_org
        FOREIGN KEY (gateway_request_id, org_id) REFERENCES gateway_request (id, org_id),
    CONSTRAINT fk_reconciliation_adjustment_attempt_org
        FOREIGN KEY (gateway_route_attempt_id, org_id)
        REFERENCES gateway_route_attempt (id, org_id),
    CONSTRAINT fk_reconciliation_adjustment_charge_org
        FOREIGN KEY (statement_charge_fact_id, org_id) REFERENCES charge_fact (id, org_id),
    CONSTRAINT fk_reconciliation_adjustment_creator_org
        FOREIGN KEY (created_by_member_id, org_id) REFERENCES organization_member (id, org_id),
    CONSTRAINT chk_reconciliation_adjustment_scope
        CHECK (adjustment_scope IN ('CASE_FULL','GATEWAY_REQUEST')),
    CONSTRAINT chk_reconciliation_adjustment_currency
        CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$')),
    CONSTRAINT chk_reconciliation_adjustment_amount_nonzero CHECK (amount <> 0),
    CONSTRAINT chk_reconciliation_adjustment_scope_shape CHECK (
        (adjustment_scope = 'CASE_FULL'
            AND reconciliation_case_id IS NOT NULL
            AND gateway_request_id IS NULL
            AND gateway_route_attempt_id IS NULL)
        OR
        (adjustment_scope = 'GATEWAY_REQUEST'
            AND gateway_request_id IS NOT NULL
            AND gateway_route_attempt_id IS NOT NULL)),
    KEY idx_reconciliation_adjustment_org_period
        (org_id, adjustment_period_id, id),
    KEY idx_reconciliation_adjustment_org_case
        (org_id, reconciliation_case_id, id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Ledger forward extension: RECONCILIATION_ADJUSTMENT becomes a bounded
-- posting source and ledger_entry gains the fourth direct-source lineage.
-- ---------------------------------------------------------------------------
ALTER TABLE ledger_posting
    DROP CHECK chk_ledger_posting_source_type,
    ADD CONSTRAINT chk_ledger_posting_source_type CHECK (
        source_type IN ('PROVIDER_CHARGE','EXPENSE_CLAIM','CORRECTION','GATEWAY_SETTLEMENT',
            'RECONCILIATION_ADJUSTMENT'));

ALTER TABLE ledger_entry
    ADD COLUMN source_reconciliation_adjustment_id BIGINT NULL
        AFTER source_gateway_settlement_id;

ALTER TABLE ledger_entry
    DROP CHECK chk_ledger_entry_source_xor,
    ADD CONSTRAINT chk_ledger_entry_source_xor CHECK (
        (source_charge_fact_id IS NOT NULL)
        + (source_expense_claim_id IS NOT NULL)
        + (source_gateway_settlement_id IS NOT NULL)
        + (source_reconciliation_adjustment_id IS NOT NULL) <= 1),
    ADD CONSTRAINT fk_ledger_entry_reconciliation_adjustment_org
        FOREIGN KEY (source_reconciliation_adjustment_id, org_id)
        REFERENCES reconciliation_adjustment (id, org_id),
    ADD KEY idx_ledger_entry_org_source_reconciliation_adjustment
        (org_id, source_reconciliation_adjustment_id);

-- ---------------------------------------------------------------------------
-- gateway_financial_resolution
-- One immutable reviewed terminal financial decision per Gateway Request.
-- ---------------------------------------------------------------------------
CREATE TABLE gateway_financial_resolution (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    reconciliation_run_id BIGINT NOT NULL,
    reconciliation_case_id BIGINT NULL,
    request_id BIGINT NOT NULL,
    route_attempt_id BIGINT NOT NULL,
    usage_fact_id BIGINT NULL,
    gateway_settlement_id BIGINT NULL,
    statement_charge_fact_id BIGINT NULL,
    reconciliation_adjustment_id BIGINT NULL,
    reservation_id BIGINT NULL,
    resolution_type VARCHAR(40) NOT NULL,
    reservation_outcome VARCHAR(16) NOT NULL,
    resolved_by_member_id BIGINT NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    reason_note VARCHAR(2000) NOT NULL,
    resolved_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_gateway_financial_resolution_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_gateway_financial_resolution_org_request UNIQUE (org_id, request_id),
    CONSTRAINT fk_gateway_financial_resolution_org
        FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_gateway_financial_resolution_run_org
        FOREIGN KEY (reconciliation_run_id, org_id) REFERENCES reconciliation_run (id, org_id),
    CONSTRAINT fk_gateway_financial_resolution_case_org
        FOREIGN KEY (reconciliation_case_id, org_id) REFERENCES reconciliation_case (id, org_id),
    CONSTRAINT fk_gateway_financial_resolution_request_org
        FOREIGN KEY (request_id, org_id) REFERENCES gateway_request (id, org_id),
    CONSTRAINT fk_gateway_financial_resolution_attempt_org
        FOREIGN KEY (route_attempt_id, org_id) REFERENCES gateway_route_attempt (id, org_id),
    CONSTRAINT fk_gateway_financial_resolution_usage_org
        FOREIGN KEY (usage_fact_id, org_id) REFERENCES gateway_usage_fact (id, org_id),
    CONSTRAINT fk_gateway_financial_resolution_settlement_org
        FOREIGN KEY (gateway_settlement_id, org_id) REFERENCES gateway_settlement (id, org_id),
    CONSTRAINT fk_gateway_financial_resolution_charge_org
        FOREIGN KEY (statement_charge_fact_id, org_id) REFERENCES charge_fact (id, org_id),
    CONSTRAINT fk_gateway_financial_resolution_adjustment_org
        FOREIGN KEY (reconciliation_adjustment_id, org_id)
        REFERENCES reconciliation_adjustment (id, org_id),
    CONSTRAINT fk_gateway_financial_resolution_reservation_org
        FOREIGN KEY (reservation_id, org_id) REFERENCES budget_reservation (id, org_id),
    CONSTRAINT fk_gateway_financial_resolution_member_org
        FOREIGN KEY (resolved_by_member_id, org_id) REFERENCES organization_member (id, org_id),
    CONSTRAINT chk_gateway_financial_resolution_type
        CHECK (resolution_type IN ('STATEMENT_ADJUSTMENT_POSTED','NO_CHARGE_CONFIRMED')),
    CONSTRAINT chk_gateway_financial_resolution_outcome
        CHECK (reservation_outcome IN ('FINALIZED','RELEASED','NONE')),
    CONSTRAINT chk_gateway_financial_resolution_type_shape CHECK (
        (resolution_type = 'STATEMENT_ADJUSTMENT_POSTED'
            AND reconciliation_adjustment_id IS NOT NULL)
        OR
        (resolution_type = 'NO_CHARGE_CONFIRMED'
            AND reconciliation_adjustment_id IS NULL)),
    CONSTRAINT chk_gateway_financial_resolution_reservation_outcome CHECK (
        (resolution_type = 'STATEMENT_ADJUSTMENT_POSTED'
            AND reservation_outcome IN ('FINALIZED','NONE'))
        OR
        (resolution_type = 'NO_CHARGE_CONFIRMED'
            AND reservation_outcome IN ('RELEASED','NONE')))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- reconciliation_evidence
-- Immutable bounded lineage/evidence per run; run-level rows may omit the case.
-- ---------------------------------------------------------------------------
CREATE TABLE reconciliation_evidence (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    reconciliation_run_id BIGINT NOT NULL,
    reconciliation_case_id BIGINT NULL,
    evidence_key VARCHAR(128) NOT NULL,
    provider_account_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    match_kind VARCHAR(32) NOT NULL,
    difference_kind VARCHAR(64) NULL,
    charge_fact_id BIGINT NULL,
    gateway_request_id BIGINT NULL,
    gateway_route_attempt_id BIGINT NULL,
    gateway_usage_fact_id BIGINT NULL,
    gateway_settlement_id BIGINT NULL,
    correction_group_id BIGINT NULL,
    reconciliation_adjustment_id BIGINT NULL,
    gateway_financial_resolution_id BIGINT NULL,
    ledger_posting_id BIGINT NULL,
    provider_request_id VARCHAR(255) NULL,
    external_amount DECIMAL(20,8) NULL,
    internal_amount DECIMAL(20,8) NULL,
    difference_amount DECIMAL(20,8) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_reconciliation_evidence_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_reconciliation_evidence_org_run_key
        UNIQUE (org_id, reconciliation_run_id, evidence_key),
    CONSTRAINT fk_reconciliation_evidence_org
        FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_reconciliation_evidence_run_org
        FOREIGN KEY (reconciliation_run_id, org_id) REFERENCES reconciliation_run (id, org_id),
    CONSTRAINT fk_reconciliation_evidence_case_org
        FOREIGN KEY (reconciliation_case_id, org_id) REFERENCES reconciliation_case (id, org_id),
    CONSTRAINT fk_reconciliation_evidence_account_org
        FOREIGN KEY (provider_account_id, org_id) REFERENCES provider_account (id, org_id),
    CONSTRAINT fk_reconciliation_evidence_charge_org
        FOREIGN KEY (charge_fact_id, org_id) REFERENCES charge_fact (id, org_id),
    CONSTRAINT fk_reconciliation_evidence_request_org
        FOREIGN KEY (gateway_request_id, org_id) REFERENCES gateway_request (id, org_id),
    CONSTRAINT fk_reconciliation_evidence_attempt_org
        FOREIGN KEY (gateway_route_attempt_id, org_id) REFERENCES gateway_route_attempt (id, org_id),
    CONSTRAINT fk_reconciliation_evidence_usage_org
        FOREIGN KEY (gateway_usage_fact_id, org_id) REFERENCES gateway_usage_fact (id, org_id),
    CONSTRAINT fk_reconciliation_evidence_settlement_org
        FOREIGN KEY (gateway_settlement_id, org_id) REFERENCES gateway_settlement (id, org_id),
    CONSTRAINT fk_reconciliation_evidence_correction_org
        FOREIGN KEY (correction_group_id, org_id) REFERENCES correction_group (id, org_id),
    CONSTRAINT fk_reconciliation_evidence_adjustment_org
        FOREIGN KEY (reconciliation_adjustment_id, org_id)
        REFERENCES reconciliation_adjustment (id, org_id),
    CONSTRAINT fk_reconciliation_evidence_resolution_org
        FOREIGN KEY (gateway_financial_resolution_id, org_id)
        REFERENCES gateway_financial_resolution (id, org_id),
    CONSTRAINT fk_reconciliation_evidence_posting_org
        FOREIGN KEY (ledger_posting_id, org_id) REFERENCES ledger_posting (id, org_id),
    CONSTRAINT chk_reconciliation_evidence_currency
        CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$')),
    CONSTRAINT chk_reconciliation_evidence_match_kind
        CHECK (match_kind IN ('EXACT_PROVIDER_REQUEST','AGGREGATE_SCOPE','GATEWAY_UNRESOLVED',
            'MANUAL_BINDING','RESOLUTION_ACTION')),
    CONSTRAINT chk_reconciliation_evidence_difference
        CHECK (difference_kind IS NULL OR difference_kind IN (
            'PRICING_DRIFT','DISCOUNT','ROUNDING','PROVIDER_CORRECTION','LATE_CHARGE',
            'BILLING_PERIOD_MISMATCH','MISSING_GATEWAY_USAGE','UNKNOWN_PROVIDER_CHARGE',
            'DUPLICATE_EXTERNAL_CHARGE','UNCLASSIFIED')),
    KEY idx_reconciliation_evidence_org_run_scope
        (org_id, reconciliation_run_id, provider_account_id, currency),
    KEY idx_reconciliation_evidence_org_case
        (org_id, reconciliation_case_id, id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- Legacy backfill: every already-POSTED PROVIDER_CHARGE source Charge is a
-- direct provider cost. LEGACY_POSTED never reinterprets committed history.
-- The NOT EXISTS guard makes re-execution a no-op (exactly-once per Charge).
-- ---------------------------------------------------------------------------
INSERT INTO provider_charge_disposition(
    org_id,charge_fact_id,disposition,decision_source,created_at)
SELECT lp.org_id,lp.source_id,'DIRECT_PROVIDER_CHARGE','LEGACY_POSTED',lp.posted_at
FROM ledger_posting lp
JOIN charge_fact cf
  ON cf.id=lp.source_id AND cf.org_id=lp.org_id
WHERE lp.source_type='PROVIDER_CHARGE'
  AND NOT EXISTS (
    SELECT 1 FROM provider_charge_disposition pcd
    WHERE pcd.org_id=lp.org_id AND pcd.charge_fact_id=lp.source_id);
