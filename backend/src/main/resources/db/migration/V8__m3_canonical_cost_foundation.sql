-- M3 Group 1: canonical cost foundation.
-- Five canonical fact tables + import_batch extension (READY_FOR_REVIEW / CONFIRMED + confirmed_attempt_id).
-- All five tables share: id BIGINT AI PK, org_id, raw_record_id, fact_index, created_at,
-- UQ(raw_record_id, fact_index), CHECK (fact_index >= 0), FK org/raw, idx_<t>_org_created.

-- 1. import_batch: extend the status CHECK and add confirmed_attempt_id (nullable, FK to import_attempt).
ALTER TABLE import_batch DROP CHECK chk_import_batch_status;
ALTER TABLE import_batch
    ADD CONSTRAINT chk_import_batch_status
        CHECK (status IN ('PENDING','PROCESSING','PARSED','READY_FOR_REVIEW','CONFIRMED','FAILED','CANCELED'));
ALTER TABLE import_batch ADD COLUMN confirmed_attempt_id BIGINT NULL;
ALTER TABLE import_batch
    ADD CONSTRAINT fk_import_batch_confirmed_attempt FOREIGN KEY (confirmed_attempt_id) REFERENCES import_attempt (id);
CREATE INDEX idx_import_batch_confirmed_attempt ON import_batch (confirmed_attempt_id);

-- 2. external_document
CREATE TABLE external_document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    raw_record_id BIGINT NOT NULL,
    fact_index INT NOT NULL,
    document_type VARCHAR(32) NOT NULL,
    period_start DATETIME(6) NULL,
    period_end DATETIME(6) NULL,
    currency CHAR(3) NULL,
    reported_total_amount DECIMAL(20,8) NULL,
    reported_payable_amount DECIMAL(20,8) NULL,
    reported_paid_amount DECIMAL(20,8) NULL,
    reported_outstanding_amount DECIMAL(20,8) NULL,
    metadata_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_external_document_raw_fact UNIQUE (raw_record_id, fact_index),
    CONSTRAINT fk_external_document_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_external_document_raw FOREIGN KEY (raw_record_id) REFERENCES raw_provider_record (id),
    CONSTRAINT chk_external_document_fact_index CHECK (fact_index >= 0),
    CONSTRAINT chk_external_document_type CHECK (document_type IN
        ('USAGE_EXPORT','COST_EXPORT','STATEMENT','INVOICE','BILL_SUMMARY')),
    CONSTRAINT chk_external_document_period CHECK
        (period_start IS NULL OR period_end IS NULL OR period_start <= period_end),
    KEY idx_external_document_org_created (org_id, created_at DESC, id),
    KEY idx_external_document_org_type_period (org_id, document_type, period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 3. consumption_fact
CREATE TABLE consumption_fact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    raw_record_id BIGINT NOT NULL,
    fact_index INT NOT NULL,
    provider_code VARCHAR(100) NOT NULL,
    service_code VARCHAR(100) NULL,
    model VARCHAR(200) NULL,
    meter_code VARCHAR(100) NOT NULL,
    quantity DECIMAL(30,8) NOT NULL,
    unit VARCHAR(64) NOT NULL,
    usage_start DATETIME(6) NULL,
    usage_end DATETIME(6) NULL,
    time_grain VARCHAR(32) NULL,
    provider_org_ref VARCHAR(200) NULL,
    provider_project_ref VARCHAR(200) NULL,
    provider_user_ref VARCHAR(200) NULL,
    provider_api_key_hash CHAR(64) NULL,
    provider_api_key_label VARCHAR(200) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_consumption_fact_raw_fact UNIQUE (raw_record_id, fact_index),
    CONSTRAINT fk_consumption_fact_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_consumption_fact_raw FOREIGN KEY (raw_record_id) REFERENCES raw_provider_record (id),
    CONSTRAINT chk_consumption_fact_fact_index CHECK (fact_index >= 0),
    CONSTRAINT chk_consumption_fact_usage CHECK
        (usage_start IS NULL OR usage_end IS NULL OR usage_start <= usage_end),
    KEY idx_consumption_fact_org_created (org_id, created_at DESC, id),
    KEY idx_consumption_fact_org_provider_usage (org_id, provider_code, usage_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 4. pricing_fact
CREATE TABLE pricing_fact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    raw_record_id BIGINT NOT NULL,
    fact_index INT NOT NULL,
    provider_code VARCHAR(100) NOT NULL,
    service_code VARCHAR(100) NULL,
    model VARCHAR(200) NULL,
    meter_code VARCHAR(100) NULL,
    unit_price DECIMAL(20,8) NOT NULL,
    currency CHAR(3) NOT NULL,
    pricing_unit VARCHAR(64) NULL,
    period_start DATETIME(6) NULL,
    period_end DATETIME(6) NULL,
    metadata_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_pricing_fact_raw_fact UNIQUE (raw_record_id, fact_index),
    CONSTRAINT fk_pricing_fact_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_pricing_fact_raw FOREIGN KEY (raw_record_id) REFERENCES raw_provider_record (id),
    CONSTRAINT chk_pricing_fact_fact_index CHECK (fact_index >= 0),
    CONSTRAINT chk_pricing_fact_period CHECK
        (period_start IS NULL OR period_end IS NULL OR period_start <= period_end),
    KEY idx_pricing_fact_org_created (org_id, created_at DESC, id),
    KEY idx_pricing_fact_org_provider_period (org_id, provider_code, period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 5. charge_fact
CREATE TABLE charge_fact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    raw_record_id BIGINT NOT NULL,
    fact_index INT NOT NULL,
    provider_code VARCHAR(100) NOT NULL,
    charge_category VARCHAR(64) NOT NULL,
    amount DECIMAL(20,8) NOT NULL,
    currency CHAR(3) NOT NULL,
    funding_source VARCHAR(64) NULL,
    payable_amount DECIMAL(20,8) NULL,
    paid_amount DECIMAL(20,8) NULL,
    outstanding_amount DECIMAL(20,8) NULL,
    period_start DATETIME(6) NULL,
    period_end DATETIME(6) NULL,
    review_status VARCHAR(32) NOT NULL DEFAULT 'CLEAN',
    duplicate_of_charge_id BIGINT NULL,
    current_allocation_decision_id BIGINT NULL,
    metadata_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_charge_fact_raw_fact UNIQUE (raw_record_id, fact_index),
    CONSTRAINT fk_charge_fact_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_charge_fact_raw FOREIGN KEY (raw_record_id) REFERENCES raw_provider_record (id),
    CONSTRAINT fk_charge_fact_duplicate FOREIGN KEY (duplicate_of_charge_id) REFERENCES charge_fact (id),
    CONSTRAINT chk_charge_fact_fact_index CHECK (fact_index >= 0),
    CONSTRAINT chk_charge_fact_review_status CHECK (review_status IN
        ('CLEAN','SUSPECTED_DUPLICATE','EXCLUDED_DUPLICATE','EXCLUDED_NONCOST')),
    CONSTRAINT chk_charge_fact_period CHECK
        (period_start IS NULL OR period_end IS NULL OR period_start <= period_end),
    KEY idx_charge_fact_org_created (org_id, created_at DESC, id),
    KEY idx_charge_fact_org_review (org_id, review_status),
    KEY idx_charge_fact_org_provider_period (org_id, provider_code, period_start)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 6. attribution_hint
CREATE TABLE attribution_hint (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    raw_record_id BIGINT NOT NULL,
    fact_index INT NOT NULL,
    hint_type VARCHAR(32) NOT NULL,
    candidate_scope_type VARCHAR(32) NULL,
    candidate_scope_id BIGINT NULL,
    provider_value VARCHAR(500) NULL,
    confidence DECIMAL(9,8) NULL,
    metadata_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_attribution_hint_raw_fact UNIQUE (raw_record_id, fact_index),
    CONSTRAINT fk_attribution_hint_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_attribution_hint_raw FOREIGN KEY (raw_record_id) REFERENCES raw_provider_record (id),
    CONSTRAINT chk_attribution_hint_fact_index CHECK (fact_index >= 0),
    CONSTRAINT chk_attribution_hint_type CHECK (hint_type IN
        ('PROVIDER_API_KEY','PROVIDER_PROJECT','PROVIDER_USER','EMPLOYEE_SELECTION')),
    CONSTRAINT chk_attribution_hint_scope_type CHECK (candidate_scope_type IS NULL OR
        candidate_scope_type IN ('PROJECT','COST_CENTER','TEAM')),
    CONSTRAINT chk_attribution_hint_confidence CHECK
        (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    CONSTRAINT chk_attribution_hint_scope_consistency CHECK (
        (candidate_scope_type IS NULL) = (candidate_scope_id IS NULL)),
    KEY idx_attribution_hint_org_created (org_id, created_at DESC, id),
    KEY idx_attribution_hint_org_type (org_id, hint_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
