-- M5 AIC-047: immutable ledger and correction history.
-- V1-V12 are deliberately left untouched. Ledger rows are append-only at the
-- application boundary; the schema only exposes INSERT/SELECT seams to M5.

-- allocation_line is an existing parent of normal LedgerEntry rows. The
-- composite key is required before adding the same-organization FK.
ALTER TABLE allocation_line
    ADD CONSTRAINT uq_allocation_line_id_org UNIQUE (id, org_id);

CREATE TABLE ledger_posting (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    posting_key VARCHAR(255) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    allocation_decision_id BIGINT NULL,
    billing_period_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    posted_by_member_id BIGINT NOT NULL,
    posted_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_ledger_posting_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_ledger_posting_org_key UNIQUE (org_id, posting_key),
    CONSTRAINT fk_ledger_posting_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_ledger_posting_period_org
        FOREIGN KEY (billing_period_id, org_id)
        REFERENCES billing_period (id, org_id),
    CONSTRAINT fk_ledger_posting_allocation_org
        FOREIGN KEY (allocation_decision_id, org_id)
        REFERENCES allocation_decision (id, org_id),
    CONSTRAINT fk_ledger_posting_poster_org
        FOREIGN KEY (posted_by_member_id, org_id)
        REFERENCES organization_member (id, org_id),
    CONSTRAINT chk_ledger_posting_source_type
        CHECK (source_type IN ('PROVIDER_CHARGE','EXPENSE_CLAIM','CORRECTION')),
    CONSTRAINT chk_ledger_posting_status CHECK (status = 'POSTED'),
    KEY idx_ledger_posting_org_posted (org_id, posted_at DESC, id),
    KEY idx_ledger_posting_org_source (org_id, source_type, source_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- correction_group is created before the back-reference is attached to
-- ledger_entry. This staged order avoids a circular DDL dependency.
CREATE TABLE ledger_entry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    posting_id BIGINT NOT NULL,
    entry_index INT NOT NULL,
    entry_type VARCHAR(32) NOT NULL,
    amount DECIMAL(20,8) NOT NULL,
    currency CHAR(3) NOT NULL,
    project_id BIGINT NULL,
    cost_center_id BIGINT NULL,
    team_id BIGINT NULL,
    budget_id BIGINT NULL,
    source_charge_fact_id BIGINT NULL,
    source_expense_claim_id BIGINT NULL,
    allocation_line_id BIGINT NULL,
    correction_group_id BIGINT NULL,
    reverses_entry_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_ledger_entry_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_ledger_entry_posting_index UNIQUE (posting_id, entry_index),
    CONSTRAINT fk_ledger_entry_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_ledger_entry_posting_org
        FOREIGN KEY (posting_id, org_id)
        REFERENCES ledger_posting (id, org_id),
    CONSTRAINT fk_ledger_entry_budget_org
        FOREIGN KEY (budget_id, org_id)
        REFERENCES budget (id, org_id),
    CONSTRAINT fk_ledger_entry_charge_org
        FOREIGN KEY (source_charge_fact_id, org_id)
        REFERENCES charge_fact (id, org_id),
    CONSTRAINT fk_ledger_entry_expense_org
        FOREIGN KEY (source_expense_claim_id, org_id)
        REFERENCES expense_claim (id, org_id),
    CONSTRAINT fk_ledger_entry_allocation_line_org
        FOREIGN KEY (allocation_line_id, org_id)
        REFERENCES allocation_line (id, org_id),
    CONSTRAINT fk_ledger_entry_reverses_org
        FOREIGN KEY (reverses_entry_id, org_id)
        REFERENCES ledger_entry (id, org_id),
    CONSTRAINT chk_ledger_entry_index CHECK (entry_index >= 0),
    CONSTRAINT chk_ledger_entry_type
        CHECK (entry_type IN ('COST','CREDIT','ADJUSTMENT','REVERSAL')),
    CONSTRAINT chk_ledger_entry_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$')),
    CONSTRAINT chk_ledger_entry_target CHECK (
        (project_id IS NOT NULL)
      + (cost_center_id IS NOT NULL)
      + (team_id IS NOT NULL) = 1
    ),
    CONSTRAINT chk_ledger_entry_source_xor CHECK (
        NOT (source_charge_fact_id IS NOT NULL AND source_expense_claim_id IS NOT NULL)
    ),
    KEY idx_ledger_entry_org_posting (org_id, posting_id, entry_index),
    KEY idx_ledger_entry_org_target_project (org_id, project_id),
    KEY idx_ledger_entry_org_target_cost_center (org_id, cost_center_id),
    KEY idx_ledger_entry_org_target_team (org_id, team_id),
    KEY idx_ledger_entry_org_source_charge (org_id, source_charge_fact_id),
    KEY idx_ledger_entry_org_source_expense (org_id, source_expense_claim_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE correction_group (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    correction_key VARCHAR(255) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    reason_text VARCHAR(2000) NULL,
    target_entry_id BIGINT NOT NULL,
    target_posting_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by_member_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_correction_group_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_correction_group_org_key UNIQUE (org_id, correction_key),
    CONSTRAINT uq_correction_group_target UNIQUE (org_id, target_entry_id),
    CONSTRAINT fk_correction_group_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_correction_group_target_entry_org
        FOREIGN KEY (target_entry_id, org_id)
        REFERENCES ledger_entry (id, org_id),
    CONSTRAINT fk_correction_group_target_posting_org
        FOREIGN KEY (target_posting_id, org_id)
        REFERENCES ledger_posting (id, org_id),
    CONSTRAINT fk_correction_group_creator_org
        FOREIGN KEY (created_by_member_id, org_id)
        REFERENCES organization_member (id, org_id),
    CONSTRAINT chk_correction_group_status CHECK (status = 'POSTED'),
    KEY idx_correction_group_org_created (org_id, created_at DESC, id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE ledger_entry
    ADD CONSTRAINT fk_ledger_entry_correction_group_org
        FOREIGN KEY (correction_group_id, org_id)
        REFERENCES correction_group (id, org_id);

ALTER TABLE budget_commitment_usage
    ADD CONSTRAINT fk_budget_commitment_usage_ledger_entry
        FOREIGN KEY (ledger_entry_id, org_id)
        REFERENCES ledger_entry (id, org_id);
