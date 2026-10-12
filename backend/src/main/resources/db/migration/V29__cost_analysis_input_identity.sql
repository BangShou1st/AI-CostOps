-- Retain existing runs. New algorithms identify the review and ledger snapshot they consumed.
ALTER TABLE cost_intelligence_run
    ADD COLUMN input_fingerprint CHAR(64) NOT NULL DEFAULT '' AFTER run_version,
    DROP INDEX uq_ci_run_identity,
    ADD CONSTRAINT uq_ci_run_identity UNIQUE (org_id,analysis_date,currency,run_version,input_fingerprint);
