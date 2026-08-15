-- M2 Group 3: review/read indexes for the Evidence / Import workflow.
-- Non-semantic: no table, column, status, permission, or trigger is introduced.

CREATE INDEX idx_evidence_org_created
    ON evidence(org_id, created_at DESC, id DESC);
CREATE INDEX idx_import_batch_org_created
    ON import_batch(org_id, created_at DESC, id DESC);
CREATE INDEX idx_import_batch_org_status_created
    ON import_batch(org_id, status, created_at DESC, id DESC);
CREATE INDEX idx_import_batch_org_provider_created
    ON import_batch(org_id, provider_account_id, created_at DESC, id DESC);
CREATE INDEX idx_raw_provider_record_attempt_status_index
    ON raw_provider_record(import_attempt_id, normalize_status, record_index, id);
CREATE INDEX idx_import_issue_attempt_severity_id
    ON import_issue(import_attempt_id, severity, id);
CREATE INDEX idx_import_issue_attempt_code_id
    ON import_issue(import_attempt_id, issue_code, id);
