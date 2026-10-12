-- Manual review evidence of completeness for UTC posting dates, never import usage dates.
CREATE TABLE cost_history_coverage_revision (
    org_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (org_id,currency),
    CONSTRAINT fk_cost_coverage_revision_org FOREIGN KEY (org_id) REFERENCES organization(id),
    CONSTRAINT chk_cost_coverage_revision CHECK (revision >= 0)
) ENGINE=InnoDB;

CREATE TABLE cost_history_coverage (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_id BIGINT NOT NULL,
    posting_day DATE NOT NULL,
    status VARCHAR(32) NOT NULL,
    basis VARCHAR(32) NOT NULL,
    review_note VARCHAR(500) NOT NULL,
    ledger_entry_count BIGINT NOT NULL,
    ledger_max_entry_id BIGINT NOT NULL,
    reviewed_by_user_id BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_cost_coverage_day (org_id,currency,scope_type,scope_id,posting_day),
    CONSTRAINT fk_cost_coverage_org FOREIGN KEY (org_id) REFERENCES organization(id),
    CONSTRAINT fk_cost_coverage_actor FOREIGN KEY (reviewed_by_user_id) REFERENCES app_user(id),
    CONSTRAINT chk_cost_coverage_scope CHECK (scope_type IN ('ORGANIZATION','PROJECT','TEAM','COST_CENTER')),
    CONSTRAINT chk_cost_coverage_scope_id CHECK (scope_id > 0),
    CONSTRAINT chk_cost_coverage_status CHECK (status IN ('REVIEWED_COMPLETE','REVOKED')),
    CONSTRAINT chk_cost_coverage_basis CHECK (basis='MANUAL_LEDGER_REVIEW'),
    CONSTRAINT chk_cost_coverage_stamp CHECK (ledger_entry_count >= 0 AND ledger_max_entry_id >= 0
      AND ((ledger_entry_count=0 AND ledger_max_entry_id=0)
        OR (ledger_entry_count>0 AND ledger_max_entry_id>0)))
) ENGINE=InnoDB;

INSERT INTO permission(code,name) VALUES('COST_COVERAGE_MANAGE','Review cost history coverage');
INSERT INTO role_permission(role_id,permission_id)
SELECT r.id,p.id FROM `role` r JOIN permission p ON p.code='COST_COVERAGE_MANAGE'
WHERE r.code='FINANCE_ADMIN';
