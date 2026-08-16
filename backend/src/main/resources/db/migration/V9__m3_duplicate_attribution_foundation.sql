-- M3 Group 2: duplicate review + attribution persistence foundation.
-- duplicate_candidate / allocation_rule / allocation_decision / allocation_line,
-- same-org composite duplicate pointer, and the composite current-decision pointer.

-- 1. charge_fact support constraints: same-org composite self-FK for the duplicate
--    pointer. A `duplicate_of_charge_id <> id` CHECK is impossible here: MySQL 8.4
--    rejects CHECK constraints that reference an auto-increment column (Error 3818);
--    the self/chain guard stays an application-level responsibility.
ALTER TABLE charge_fact
    ADD CONSTRAINT uq_charge_fact_id_org UNIQUE (id, org_id);

ALTER TABLE charge_fact
    DROP FOREIGN KEY fk_charge_fact_duplicate;

ALTER TABLE charge_fact
    ADD CONSTRAINT fk_charge_fact_duplicate
        FOREIGN KEY (duplicate_of_charge_id, org_id)
        REFERENCES charge_fact (id, org_id);

-- 2. duplicate_candidate
CREATE TABLE duplicate_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    charge_fact_id BIGINT NOT NULL,
    matched_charge_id BIGINT NOT NULL,
    candidate_type VARCHAR(32) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    algorithm_version VARCHAR(32) NOT NULL,
    match_reason VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    created_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_duplicate_candidate_pair_version
        UNIQUE (org_id, charge_fact_id, matched_charge_id, algorithm_version),

    CONSTRAINT fk_duplicate_candidate_org
        FOREIGN KEY (org_id) REFERENCES organization (id),

    CONSTRAINT fk_duplicate_candidate_charge_org
        FOREIGN KEY (charge_fact_id, org_id)
        REFERENCES charge_fact (id, org_id),

    CONSTRAINT fk_duplicate_candidate_matched_org
        FOREIGN KEY (matched_charge_id, org_id)
        REFERENCES charge_fact (id, org_id),

    CONSTRAINT chk_duplicate_candidate_type
        CHECK (candidate_type IN ('EXACT','OVERLAP')),

    CONSTRAINT chk_duplicate_candidate_status
        CHECK (status IN (
            'OPEN',
            'KEPT_CLEAN',
            'CONFIRMED_DUPLICATE',
            'SUPERSEDED'
        )),

    CONSTRAINT chk_duplicate_candidate_order
        CHECK (charge_fact_id < matched_charge_id),

    CONSTRAINT chk_duplicate_candidate_resolution
        CHECK (
            (status = 'OPEN' AND resolved_at IS NULL)
            OR
            (status <> 'OPEN' AND resolved_at IS NOT NULL)
        ),

    KEY idx_duplicate_candidate_org_status
        (org_id, status, created_at, id),

    KEY idx_duplicate_candidate_org_charge
        (org_id, charge_fact_id, status),

    KEY idx_duplicate_candidate_org_matched
        (org_id, matched_charge_id, status)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- 3. allocation_rule
CREATE TABLE allocation_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,

    rule_key VARCHAR(100) NOT NULL,
    version INT NOT NULL,
    name VARCHAR(200) NOT NULL,

    provider_code VARCHAR(100) NOT NULL,
    provider_account_id BIGINT NULL,

    match_hint_type VARCHAR(32) NOT NULL,
    match_value VARCHAR(500) NOT NULL,

    priority INT NOT NULL,

    target_project_id BIGINT NULL,
    target_cost_center_id BIGINT NULL,
    target_team_id BIGINT NULL,

    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,

    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',

    created_by_member_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT uq_allocation_rule_key_version
        UNIQUE (org_id, rule_key, version),

    CONSTRAINT fk_allocation_rule_org
        FOREIGN KEY (org_id) REFERENCES organization (id),

    CONSTRAINT fk_allocation_rule_provider_account
        FOREIGN KEY (provider_account_id) REFERENCES provider_account (id),

    CONSTRAINT fk_allocation_rule_project
        FOREIGN KEY (target_project_id) REFERENCES project (id),

    CONSTRAINT fk_allocation_rule_cost_center
        FOREIGN KEY (target_cost_center_id) REFERENCES cost_center (id),

    CONSTRAINT fk_allocation_rule_team
        FOREIGN KEY (target_team_id) REFERENCES team (id),

    CONSTRAINT fk_allocation_rule_creator
        FOREIGN KEY (created_by_member_id) REFERENCES organization_member (id),

    CONSTRAINT chk_allocation_rule_version
        CHECK (version > 0),

    CONSTRAINT chk_allocation_rule_match_hint_type
        CHECK (match_hint_type IN (
            'PROVIDER_API_KEY',
            'PROVIDER_PROJECT',
            'PROVIDER_USER'
        )),

    CONSTRAINT chk_allocation_rule_priority
        CHECK (priority BETWEEN 1 AND 9999),

    CONSTRAINT chk_allocation_rule_status
        CHECK (status IN ('ACTIVE','ARCHIVED')),

    CONSTRAINT chk_allocation_rule_target
        CHECK (
            (target_project_id IS NOT NULL)
          + (target_cost_center_id IS NOT NULL)
          + (target_team_id IS NOT NULL)
          = 1
        ),

    CONSTRAINT chk_allocation_rule_effective
        CHECK (
            effective_to IS NULL
            OR effective_from < effective_to
        ),

    KEY idx_allocation_rule_org_key_version
        (org_id, rule_key, version),

    KEY idx_allocation_rule_org_status_priority_effective
        (org_id, status, priority, effective_from),

    KEY idx_allocation_rule_org_match
        (org_id, provider_code, match_hint_type, match_value)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- 4. allocation_decision
CREATE TABLE allocation_decision (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,

    subject_type VARCHAR(32) NOT NULL,
    charge_fact_id BIGINT NULL,
    expense_claim_id BIGINT NULL,

    decision_source VARCHAR(32) NOT NULL,
    allocation_rule_id BIGINT NULL,

    status VARCHAR(32) NOT NULL,

    confirmed_charge_fact_id BIGINT
        GENERATED ALWAYS AS (
            CASE
                WHEN status = 'CONFIRMED'
                THEN charge_fact_id
                ELSE NULL
            END
        ) STORED,

    created_by_member_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT uq_allocation_decision_confirmed_charge
        UNIQUE (confirmed_charge_fact_id),

    CONSTRAINT uq_allocation_decision_id_org
        UNIQUE (id, org_id),

    CONSTRAINT uq_allocation_decision_id_charge_org
        UNIQUE (id, charge_fact_id, org_id),

    CONSTRAINT fk_allocation_decision_org
        FOREIGN KEY (org_id)
        REFERENCES organization (id),

    CONSTRAINT fk_allocation_decision_charge_org
        FOREIGN KEY (charge_fact_id, org_id)
        REFERENCES charge_fact (id, org_id),

    CONSTRAINT fk_allocation_decision_rule
        FOREIGN KEY (allocation_rule_id)
        REFERENCES allocation_rule (id),

    CONSTRAINT fk_allocation_decision_creator
        FOREIGN KEY (created_by_member_id)
        REFERENCES organization_member (id),

    CONSTRAINT chk_allocation_decision_subject_type
        CHECK (subject_type IN ('CHARGE_FACT','EXPENSE_CLAIM')),

    CONSTRAINT chk_allocation_decision_source
        CHECK (decision_source IN ('MANUAL','RULE')),

    CONSTRAINT chk_allocation_decision_status
        CHECK (status IN ('DRAFT','CONFIRMED','SUPERSEDED')),

    CONSTRAINT chk_allocation_decision_subject
        CHECK (
            (
                subject_type = 'CHARGE_FACT'
                AND charge_fact_id IS NOT NULL
                AND expense_claim_id IS NULL
            )
            OR
            (
                subject_type = 'EXPENSE_CLAIM'
                AND expense_claim_id IS NOT NULL
                AND charge_fact_id IS NULL
            )
        ),

    CONSTRAINT chk_allocation_decision_source_rule
        CHECK (
            (decision_source = 'MANUAL' AND allocation_rule_id IS NULL)
            OR
            (decision_source = 'RULE' AND allocation_rule_id IS NOT NULL)
        ),

    KEY idx_allocation_decision_org_charge
        (org_id, charge_fact_id, created_at),

    KEY idx_allocation_decision_org_expense
        (org_id, expense_claim_id, created_at),

    KEY idx_allocation_decision_org_status
        (org_id, status, created_at)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- 5. allocation_line
CREATE TABLE allocation_line (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    decision_id BIGINT NOT NULL,

    line_index INT NOT NULL,

    allocated_amount DECIMAL(20,8) NOT NULL,
    currency CHAR(3) NOT NULL,

    project_id BIGINT NULL,
    cost_center_id BIGINT NULL,
    team_id BIGINT NULL,

    created_at DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    CONSTRAINT uq_allocation_line_decision_index
        UNIQUE (decision_id, line_index),

    CONSTRAINT fk_allocation_line_decision_org
        FOREIGN KEY (decision_id, org_id)
        REFERENCES allocation_decision (id, org_id),

    CONSTRAINT fk_allocation_line_project
        FOREIGN KEY (project_id) REFERENCES project (id),

    CONSTRAINT fk_allocation_line_cost_center
        FOREIGN KEY (cost_center_id) REFERENCES cost_center (id),

    CONSTRAINT fk_allocation_line_team
        FOREIGN KEY (team_id) REFERENCES team (id),

    CONSTRAINT chk_allocation_line_index
        CHECK (line_index >= 0),

    CONSTRAINT chk_allocation_line_target
        CHECK (
            (project_id IS NOT NULL)
          + (cost_center_id IS NOT NULL)
          + (team_id IS NOT NULL)
          = 1
        ),

    KEY idx_allocation_line_org_decision
        (org_id, decision_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- 6. current decision composite FK: the pointer can only reference a decision of
--    the same charge in the same organization.
ALTER TABLE charge_fact
    ADD CONSTRAINT fk_charge_fact_current_allocation_decision
        FOREIGN KEY (
            current_allocation_decision_id,
            id,
            org_id
        )
        REFERENCES allocation_decision (
            id,
            charge_fact_id,
            org_id
        );
