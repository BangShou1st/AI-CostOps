-- M4: expense claim + approval workflow foundation.
-- expense_claim / approval_case / approval_action, and the allocation_decision
-- increments that close the V9 expense_claim_id gap (confirmed_expense_claim_id
-- uniqueness, symmetric with confirmed_charge_fact_id).
--
-- Staged ALTER order resolves the circular FKs: expense_claim points at both
-- allocation_decision (current allocation pointer) and approval_case (current
-- approval pointer), while both of those reference expense_claim. Pointer FKs
-- are therefore added only after all three new tables and the
-- allocation_decision increments exist. All pointers start NULL, so the staged
-- ADD CONSTRAINTs are safe on any populated V9 database.

-- 0. evidence needs UQ(id, org_id) as the same-org FK target for
--    expense_claim.evidence_id (mirrors V9 uq_charge_fact_id_org).
ALTER TABLE evidence
    ADD CONSTRAINT uq_evidence_id_org UNIQUE (id, org_id);

-- 1. expense_claim
CREATE TABLE expense_claim (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    claimant_member_id BIGINT NOT NULL,
    evidence_id BIGINT NULL,
    expense_date DATE NOT NULL,
    amount DECIMAL(20,8) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_allocation_decision_id BIGINT NULL,
    approval_case_id BIGINT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_expense_claim_id_org
        UNIQUE (id, org_id),

    CONSTRAINT fk_expense_claim_org
        FOREIGN KEY (org_id) REFERENCES organization (id),

    CONSTRAINT fk_expense_claim_claimant
        FOREIGN KEY (claimant_member_id) REFERENCES organization_member (id),

    CONSTRAINT fk_expense_claim_evidence_org
        FOREIGN KEY (evidence_id, org_id) REFERENCES evidence (id, org_id),

    CONSTRAINT chk_expense_claim_status
        CHECK (status IN (
            'DRAFT',
            'SUBMITTED',
            'NEEDS_INFO',
            'APPROVED',
            'REJECTED',
            'CANCELED'
        )),

    CONSTRAINT chk_expense_claim_currency
        CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$')),

    CONSTRAINT chk_expense_claim_version
        CHECK (version >= 0),

    KEY idx_expense_claim_org_status
        (org_id, status, created_at, id),

    KEY idx_expense_claim_org_claimant
        (org_id, claimant_member_id, created_at, id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- 2. approval_case (1:1 per expense; created on first SUBMIT, reused across
--    REQUEST_INFO -> RESUBMIT cycles).
CREATE TABLE approval_case (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    expense_claim_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_approval_case_id_org
        UNIQUE (id, org_id),

    CONSTRAINT uq_approval_case_org_expense
        UNIQUE (org_id, expense_claim_id),

    CONSTRAINT uq_approval_case_id_expense_org
        UNIQUE (id, expense_claim_id, org_id),

    CONSTRAINT fk_approval_case_org
        FOREIGN KEY (org_id) REFERENCES organization (id),

    CONSTRAINT fk_approval_case_expense_org
        FOREIGN KEY (expense_claim_id, org_id)
        REFERENCES expense_claim (id, org_id),

    CONSTRAINT chk_approval_case_status
        CHECK (status IN (
            'PENDING',
            'NEEDS_INFO',
            'APPROVED',
            'REJECTED',
            'CANCELED'
        )),

    KEY idx_approval_case_org_status
        (org_id, status, created_at, id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- 3. approval_action (append-only: application layer only INSERTs)
CREATE TABLE approval_action (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    approval_case_id BIGINT NOT NULL,
    actor_member_id BIGINT NOT NULL,
    action_type VARCHAR(32) NOT NULL,
    from_state VARCHAR(32) NULL,
    to_state VARCHAR(32) NULL,
    comment VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),

    CONSTRAINT uq_approval_action_id_org
        UNIQUE (id, org_id),

    CONSTRAINT fk_approval_action_org
        FOREIGN KEY (org_id) REFERENCES organization (id),

    CONSTRAINT fk_approval_action_case_org
        FOREIGN KEY (approval_case_id, org_id)
        REFERENCES approval_case (id, org_id),

    CONSTRAINT fk_approval_action_actor
        FOREIGN KEY (actor_member_id) REFERENCES organization_member (id),

    CONSTRAINT chk_approval_action_type
        CHECK (action_type IN (
            'SUBMIT',
            'REQUEST_INFO',
            'RESUBMIT',
            'APPROVE',
            'REJECT',
            'CANCEL'
        )),

    KEY idx_approval_action_org_case
        (org_id, approval_case_id, created_at, id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci;

-- 4. allocation_decision increments: close the V9 expense_claim_id gap and add
--    confirmed uniqueness. NULL expense_claim_id rows (CHARGE_FACT) are not
--    touched by any of these (MySQL skips UNIQUE/FK checks on NULLs), so the
--    M3 charge path is unaffected.
ALTER TABLE allocation_decision
    ADD CONSTRAINT uq_allocation_decision_id_expense_org
        UNIQUE (id, expense_claim_id, org_id);

ALTER TABLE allocation_decision
    ADD CONSTRAINT fk_allocation_decision_expense_org
        FOREIGN KEY (expense_claim_id, org_id)
        REFERENCES expense_claim (id, org_id);

ALTER TABLE allocation_decision
    ADD COLUMN confirmed_expense_claim_id BIGINT
        GENERATED ALWAYS AS (
            CASE
                WHEN status = 'CONFIRMED'
                THEN expense_claim_id
                ELSE NULL
            END
        ) STORED;

ALTER TABLE allocation_decision
    ADD CONSTRAINT uq_allocation_decision_confirmed_expense
        UNIQUE (confirmed_expense_claim_id);

-- 5. expense_claim current-allocation pointer: can only reference a decision
--    about this expense in the same organization (mirrors
--    fk_charge_fact_current_allocation_decision).
ALTER TABLE expense_claim
    ADD CONSTRAINT fk_expense_claim_current_allocation_decision
        FOREIGN KEY (
            current_allocation_decision_id,
            id,
            org_id
        )
        REFERENCES allocation_decision (
            id,
            expense_claim_id,
            org_id
        );

-- 6. expense_claim current-approval pointer: can only reference the approval
--    case of this expense in the same organization.
ALTER TABLE expense_claim
    ADD CONSTRAINT fk_expense_claim_approval_case
        FOREIGN KEY (
            approval_case_id,
            id,
            org_id
        )
        REFERENCES approval_case (
            id,
            expense_claim_id,
            org_id
        );
