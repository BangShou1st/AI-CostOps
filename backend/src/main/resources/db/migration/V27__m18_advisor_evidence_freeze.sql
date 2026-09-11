-- M18 round-3: freeze Advisor job execution semantics + immutable evidence snapshot.
-- V1-V26 immutable. All additions are additive/nullable or new tables.
-- advisor_inference_job.advisor_profile_id binds each job to its exact profile revision;
-- advisor_evidence_snapshot persists the bounded server-generated evidence the model receives;
-- savings_recommendation.routing_change_required marks candidates needing a routing revision.
ALTER TABLE advisor_inference_job
    ADD COLUMN advisor_profile_id BIGINT NULL AFTER advisor_profile_version,
    ADD CONSTRAINT fk_advisor_job_profile_org
        FOREIGN KEY (advisor_profile_id, org_id) REFERENCES advisor_profile (id, org_id),
    ADD KEY idx_advisor_job_profile (org_id, advisor_profile_id);
CREATE TABLE advisor_evidence_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    job_id BIGINT NOT NULL,
    schema_version INT NOT NULL,
    subject_type VARCHAR(100) NOT NULL,
    subject_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    facts_json JSON NOT NULL,
    drivers_json JSON NOT NULL,
    summary_json JSON NOT NULL,
    evidence_fingerprint CHAR(64) NOT NULL,
    generated_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_advisor_snapshot_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_advisor_snapshot_job_org UNIQUE (job_id, org_id),
    CONSTRAINT fk_advisor_snapshot_job_org FOREIGN KEY (job_id, org_id)
        REFERENCES advisor_inference_job (id, org_id),
    CONSTRAINT fk_advisor_snapshot_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT chk_advisor_snapshot_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$')),
    KEY idx_advisor_snapshot_job (org_id, job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
ALTER TABLE savings_recommendation
    ADD COLUMN routing_change_required TINYINT(1) NOT NULL DEFAULT 0 AFTER routing_policy_id,
    ADD KEY idx_savings_recommendation_routing_flag (org_id, routing_change_required);
