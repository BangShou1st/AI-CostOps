-- M6 AIC-054: reconciliation and period close persistence.
-- V1-V15 are immutable. Reconciliation owns comparison/close history while
-- source financial facts remain in their existing modules.

-- M1 provider_account did not need a same-org composite key originally; M6
-- reconciliation_case uses it as a same-organization FK target.
ALTER TABLE provider_account
    ADD CONSTRAINT uq_provider_account_id_org UNIQUE (id, org_id);

CREATE TABLE reconciliation_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    billing_period_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    algorithm_version VARCHAR(100) NOT NULL,
    tolerance_amount DECIMAL(20,8) NOT NULL,
    basis_hash CHAR(64) NULL,
    summary_json JSON NOT NULL,
    created_by_member_id BIGINT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    error_code VARCHAR(100) NULL,
    error_summary VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_reconciliation_run_id_org UNIQUE (id, org_id),
    CONSTRAINT fk_reconciliation_run_org
        FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_reconciliation_run_period_org
        FOREIGN KEY (billing_period_id, org_id)
        REFERENCES billing_period (id, org_id),
    CONSTRAINT fk_reconciliation_run_creator_org
        FOREIGN KEY (created_by_member_id, org_id)
        REFERENCES organization_member (id, org_id),
    CONSTRAINT chk_reconciliation_run_status
        CHECK (status IN ('CREATED','RUNNING','COMPLETED','FAILED')),
    CONSTRAINT chk_reconciliation_run_tolerance
        CHECK (tolerance_amount >= 0),
    CONSTRAINT chk_reconciliation_run_terminal CHECK (
        (status IN ('CREATED','RUNNING')
            AND finished_at IS NULL
            AND error_code IS NULL
            AND error_summary IS NULL)
        OR
        (status = 'COMPLETED'
            AND finished_at IS NOT NULL
            AND basis_hash IS NOT NULL
            AND error_code IS NULL
            AND error_summary IS NULL)
        OR
        (status = 'FAILED'
            AND finished_at IS NOT NULL
            AND error_code IS NOT NULL
            AND error_summary IS NOT NULL)
    ),
    KEY idx_reconciliation_run_org_period_started
        (org_id, billing_period_id, started_at DESC, id DESC),
    KEY idx_reconciliation_run_org_period_status
        (org_id, billing_period_id, status, id DESC)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE reconciliation_case (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    reconciliation_run_id BIGINT NOT NULL,
    provider_account_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    case_type VARCHAR(32) NOT NULL,
    external_amount DECIMAL(20,8) NULL,
    internal_amount DECIMAL(20,8) NULL,
    difference_amount DECIMAL(20,8) NOT NULL,
    external_row_count BIGINT NOT NULL,
    internal_row_count BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    reason_code VARCHAR(100) NULL,
    resolution_note VARCHAR(2000) NULL,
    resolved_by_member_id BIGINT NULL,
    resolved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_reconciliation_case_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_reconciliation_case_run_provider_currency
        UNIQUE (reconciliation_run_id, provider_account_id, currency),
    CONSTRAINT fk_reconciliation_case_org
        FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_reconciliation_case_run_org
        FOREIGN KEY (reconciliation_run_id, org_id)
        REFERENCES reconciliation_run (id, org_id),
    CONSTRAINT fk_reconciliation_case_provider_org
        FOREIGN KEY (provider_account_id, org_id)
        REFERENCES provider_account (id, org_id),
    CONSTRAINT fk_reconciliation_case_resolver_org
        FOREIGN KEY (resolved_by_member_id, org_id)
        REFERENCES organization_member (id, org_id),
    CONSTRAINT chk_reconciliation_case_currency
        CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$')),
    CONSTRAINT chk_reconciliation_case_type
        CHECK (case_type IN ('MISSING_INTERNAL','MISSING_EXTERNAL','AMOUNT_MISMATCH')),
    CONSTRAINT chk_reconciliation_case_status
        CHECK (status IN ('OPEN','INVESTIGATING','RESOLVED')),
    CONSTRAINT chk_reconciliation_case_row_counts
        CHECK (external_row_count >= 0 AND internal_row_count >= 0),
    CONSTRAINT chk_reconciliation_case_presence CHECK (
        (case_type = 'MISSING_INTERNAL'
            AND external_amount IS NOT NULL
            AND internal_amount IS NULL
            AND external_row_count > 0
            AND internal_row_count = 0)
        OR
        (case_type = 'MISSING_EXTERNAL'
            AND external_amount IS NULL
            AND internal_amount IS NOT NULL
            AND external_row_count = 0
            AND internal_row_count > 0)
        OR
        (case_type = 'AMOUNT_MISMATCH'
            AND external_amount IS NOT NULL
            AND internal_amount IS NOT NULL
            AND external_row_count > 0
            AND internal_row_count > 0)
    ),
    CONSTRAINT chk_reconciliation_case_resolution CHECK (
        (status <> 'RESOLVED'
            AND reason_code IS NULL
            AND resolution_note IS NULL
            AND resolved_by_member_id IS NULL
            AND resolved_at IS NULL)
        OR
        (status = 'RESOLVED'
            AND reason_code IS NOT NULL
            AND CHAR_LENGTH(TRIM(reason_code)) > 0
            AND resolution_note IS NOT NULL
            AND CHAR_LENGTH(TRIM(resolution_note)) > 0
            AND resolved_by_member_id IS NOT NULL
            AND resolved_at IS NOT NULL)
    ),
    KEY idx_reconciliation_case_org_run_status
        (org_id, reconciliation_run_id, status, id),
    KEY idx_reconciliation_case_org_provider
        (org_id, provider_account_id, currency, id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE period_close_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    billing_period_id BIGINT NOT NULL,
    close_generation BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    reconciliation_run_id BIGINT NULL,
    started_by_member_id BIGINT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    error_code VARCHAR(100) NULL,
    error_summary VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_period_close_run_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_period_close_run_attempt
        UNIQUE (org_id, billing_period_id, close_generation, attempt_no),
    CONSTRAINT fk_period_close_run_org
        FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_period_close_run_period_org
        FOREIGN KEY (billing_period_id, org_id)
        REFERENCES billing_period (id, org_id),
    CONSTRAINT fk_period_close_run_reconciliation_org
        FOREIGN KEY (reconciliation_run_id, org_id)
        REFERENCES reconciliation_run (id, org_id),
    CONSTRAINT fk_period_close_run_starter_org
        FOREIGN KEY (started_by_member_id, org_id)
        REFERENCES organization_member (id, org_id),
    CONSTRAINT chk_period_close_run_generation
        CHECK (close_generation >= 0),
    CONSTRAINT chk_period_close_run_attempt
        CHECK (attempt_no > 0),
    CONSTRAINT chk_period_close_run_status
        CHECK (status IN ('CHECKING','BLOCKED','CLOSED','FAILED')),
    CONSTRAINT chk_period_close_run_terminal CHECK (
        (status = 'CHECKING'
            AND finished_at IS NULL
            AND error_code IS NULL
            AND error_summary IS NULL)
        OR
        (status IN ('BLOCKED','CLOSED')
            AND finished_at IS NOT NULL
            AND error_code IS NULL
            AND error_summary IS NULL)
        OR
        (status = 'FAILED'
            AND finished_at IS NOT NULL
            AND error_code IS NOT NULL
            AND error_summary IS NOT NULL)
    ),
    KEY idx_period_close_run_org_period_generation
        (org_id, billing_period_id, close_generation, attempt_no DESC),
    KEY idx_period_close_run_org_period_status
        (org_id, billing_period_id, status, id DESC)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE period_close_check (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    period_close_run_id BIGINT NOT NULL,
    blocker_code VARCHAR(64) NOT NULL,
    result VARCHAR(16) NOT NULL,
    item_count BIGINT NOT NULL,
    summary_json JSON NOT NULL,
    evaluated_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_period_close_check_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_period_close_check_run_blocker
        UNIQUE (period_close_run_id, blocker_code),
    CONSTRAINT fk_period_close_check_org
        FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_period_close_check_run_org
        FOREIGN KEY (period_close_run_id, org_id)
        REFERENCES period_close_run (id, org_id),
    CONSTRAINT chk_period_close_check_blocker CHECK (blocker_code IN (
        'OPEN_IMPORTS',
        'UNRESOLVED_DUPLICATES',
        'UNALLOCATED_CHARGES',
        'UNPOSTED_APPROVED_EXPENSES',
        'OPEN_MATERIAL_RECONCILIATION',
        'PENDING_CORRECTIONS',
        'LEDGER_INTEGRITY'
    )),
    CONSTRAINT chk_period_close_check_result
        CHECK (result IN ('PASS','FAIL','ERROR')),
    CONSTRAINT chk_period_close_check_item_count
        CHECK (item_count >= 0),
    KEY idx_period_close_check_org_run
        (org_id, period_close_run_id, blocker_code)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- Period-end read paths need period/status columns before timestamp columns.
CREATE INDEX idx_ledger_posting_org_period_id
    ON ledger_posting(org_id, billing_period_id, id);
CREATE INDEX idx_expense_claim_org_status_date_id
    ON expense_claim(org_id, status, expense_date, id);
CREATE INDEX idx_import_batch_org_status_period_id
    ON import_batch(org_id, status, period_start, period_end, id);
