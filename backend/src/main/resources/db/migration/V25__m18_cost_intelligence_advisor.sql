-- M18: Cost Intelligence runs/evidence + AI Advisor jobs + V3 permissions.
-- Derived analytics only; Ledger remains the financial truth. No FX.
CREATE TABLE cost_intelligence_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    analysis_date DATE NOT NULL,
    currency CHAR(3) NOT NULL,
    run_version INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    failure_code VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_ci_run_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_ci_run_identity UNIQUE (org_id, analysis_date, currency, run_version),
    CONSTRAINT fk_ci_run_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT chk_ci_run_status CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    CONSTRAINT chk_ci_run_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE cost_anomaly (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    grain_type VARCHAR(32) NOT NULL,
    grain_key VARCHAR(300) NOT NULL,
    currency CHAR(3) NOT NULL,
    observed_amount DECIMAL(20,8) NOT NULL,
    baseline_amount DECIMAL(20,8) NOT NULL,
    delta_amount DECIMAL(20,8) NOT NULL,
    delta_percent DECIMAL(20,8) NOT NULL,
    robust_z_score DOUBLE NOT NULL,
    drivers_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_anomaly_run_org FOREIGN KEY (run_id, org_id) REFERENCES cost_intelligence_run (id, org_id),
    CONSTRAINT chk_anomaly_grain CHECK (grain_type IN ('ORGANIZATION','PROJECT','PROVIDER','LOGICAL_MODEL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE cost_forecast_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_key VARCHAR(300) NOT NULL,
    currency CHAR(3) NOT NULL,
    projected_amount DECIMAL(20,8) NOT NULL,
    method VARCHAR(32) NOT NULL,
    history_bucket_count INT NOT NULL,
    confidence VARCHAR(16) NOT NULL,
    observed_through DATE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_forecast_run_org FOREIGN KEY (run_id, org_id) REFERENCES cost_intelligence_run (id, org_id),
    CONSTRAINT chk_forecast_method CHECK (method IN ('DAMPED_HOLT','RECENT_RUN_RATE')),
    CONSTRAINT chk_forecast_confidence CHECK (confidence IN ('LOW','MEDIUM','HIGH'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE savings_recommendation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    run_id BIGINT NOT NULL,
    logical_model_id BIGINT NOT NULL,
    current_provider_account_id BIGINT NOT NULL,
    current_provider_model_id BIGINT NOT NULL,
    current_pricing_version_id BIGINT NOT NULL,
    candidate_provider_account_id BIGINT NOT NULL,
    candidate_provider_model_id BIGINT NOT NULL,
    candidate_pricing_version_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    evidence_window_start DATE NOT NULL,
    evidence_window_end DATE NOT NULL,
    current_cost DECIMAL(20,8) NOT NULL,
    candidate_cost DECIMAL(20,8) NOT NULL,
    potential_saving DECIMAL(20,8) NOT NULL,
    potential_saving_percent DECIMAL(20,8) NOT NULL,
    evidence_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    routing_policy_id BIGINT NULL,
    calculated_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_saving_run_org FOREIGN KEY (run_id, org_id) REFERENCES cost_intelligence_run (id, org_id),
    CONSTRAINT chk_saving_status CHECK (status IN ('OPEN','ACKNOWLEDGED','DISMISSED','APPLIED','EXPIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE advisor_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    version INT NOT NULL,
    provider_model_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    financial_scope_type VARCHAR(32) NOT NULL,
    financial_scope_id BIGINT NOT NULL,
    budget_enforcement_mode VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END) STORED,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_advisor_profile_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_advisor_profile_org_active UNIQUE (org_id, active_slot),
    CONSTRAINT fk_advisor_profile_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_advisor_profile_model FOREIGN KEY (provider_model_id) REFERENCES provider_model (id),
    CONSTRAINT chk_advisor_profile_status CHECK (status IN ('ACTIVE','RETIRED')),
    CONSTRAINT chk_advisor_profile_scope CHECK (financial_scope_type IN ('PROJECT','TEAM','COST_CENTER')),
    CONSTRAINT chk_advisor_profile_budget_mode CHECK (budget_enforcement_mode IN ('REQUIRED','OPTIONAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE advisor_inference_job (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    requested_by BIGINT NOT NULL,
    subject_type VARCHAR(100) NOT NULL,
    subject_id BIGINT NOT NULL,
    evidence_fingerprint CHAR(64) NOT NULL,
    advisor_profile_version INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    evidence_refs_json JSON NOT NULL,
    claim_token CHAR(40) NULL,
    claim_expires_at DATETIME(6) NULL,
    gateway_request_id BIGINT NULL,
    attempt_count INT NOT NULL,
    failure_code VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_advisor_job_id_org UNIQUE (id, org_id),
    CONSTRAINT fk_advisor_job_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_advisor_job_gateway_org FOREIGN KEY (gateway_request_id, org_id) REFERENCES gateway_request (id, org_id),
    CONSTRAINT chk_advisor_job_status CHECK (status IN ('PENDING','CLAIMED','DISPATCHING','RUNNING','COMPLETED','FAILED')),
    KEY idx_advisor_job_claim (org_id, status, claim_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE advisor_inference_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    gateway_request_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    failure_code VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_advisor_attempt_job_no UNIQUE (job_id, attempt_no),
    CONSTRAINT fk_advisor_attempt_job_org FOREIGN KEY (job_id, org_id) REFERENCES advisor_inference_job (id, org_id),
    CONSTRAINT chk_advisor_attempt_status CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE advisor_explanation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    schema_version INT NOT NULL,
    summary VARCHAR(2000) NOT NULL,
    drivers_explanation VARCHAR(4000) NOT NULL,
    recommended_actions_json JSON NOT NULL,
    warnings_json JSON NOT NULL,
    fact_reference_ids_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_explanation_job_org FOREIGN KEY (job_id, org_id) REFERENCES advisor_inference_job (id, org_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
INSERT INTO permission (code, name) VALUES
('AI_ADVISOR_USE','Use AI advisor explanations'),
('AI_ADVISOR_MANAGE','Manage AI advisor configuration')
ON DUPLICATE KEY UPDATE name = VALUES(name);
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM `role` r JOIN permission p
WHERE r.code = 'SYSTEM_ADMIN' AND p.code IN ('AI_ADVISOR_USE','AI_ADVISOR_MANAGE')
AND NOT EXISTS(SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id FROM `role` r JOIN permission p
WHERE r.code = 'FINANCE_ADMIN' AND p.code IN ('AI_ADVISOR_USE','AI_ADVISOR_MANAGE')
AND NOT EXISTS(SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id);
