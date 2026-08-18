-- M4 Group 4 (AIC-044): extend the approval shell so the same approval_case
-- can legally represent exactly one of two subjects:
--   A. an EXPENSE_CLAIM approval, or
--   B. a BUDGET_COMMITMENT approval.
--
-- V10 made approval_case expense-only (expense_claim_id NOT NULL + same-org
-- FK). This forward-only migration relaxes that without weakening any V10
-- integrity: explicit nullable same-org composite FKs stay (no generic
-- subject_type + subject_id lossy pointer), a real XOR CHECK enforces the
-- exactly-one-subject invariant in MySQL, and every V10 constraint keeps its
-- name and meaning so existing expense rows and the
-- expense_claim.approval_case_id composite identity remain fully valid.
--
-- NULL semantics of the surviving constraints on a V10 database:
--   * uq_approval_case_org_expense      UNIQUE(org_id, expense_claim_id):
--     MySQL UNIQUE indexes ignore NULLs -> commitment cases (expense NULL)
--     do not collide with each other; expense cases keep their identity.
--   * uq_approval_case_id_expense_org   UNIQUE(id, expense_claim_id, org_id):
--     still the FK target of expense_claim.approval_case_id; a NULL
--     expense_claim_id in a commitment case simply cannot be referenced by
--     an expense pointer (FK with a NULL parent column never matches), so
--     expense_claim pointers can never hijack a commitment case.
--   * fk_approval_case_expense_org      FK (expense_claim_id, org_id):
--     with the column now nullable, a commitment case (NULL) is skipped by
--     the FK check; any non-NULL expense_claim_id still must resolve to the
--     same organization (V10 semantics unchanged).
--
-- The added commitment side mirrors the expense side exactly:
--   * fk_approval_case_commitment_org   FK (budget_commitment_id, org_id)
--     -> budget_commitment(id, org_id)  (same-org referential integrity;
--     uq_budget_commitment_id_org from V11 is the FK target)
--   * uq_approval_case_org_commitment   UNIQUE(org_id, budget_commitment_id):
--     one approval case per commitment (mirror of the expense uniqueness)
--   * uq_approval_case_id_commitment_org UNIQUE(id, budget_commitment_id,
--     org_id): composite identity mirror, ready for a future
--     budget_commitment.approval_case_id pointer FK if a later milestone
--     needs a back-pointer (same pattern as expense).

-- 1. expense_claim_id becomes nullable for commitment cases. The V10 FK and
--    UNIQUE constraints survive the MODIFY (MySQL preserves constraints on
--    column definition changes); they simply ignore NULL subject rows.
ALTER TABLE approval_case
    MODIFY COLUMN expense_claim_id BIGINT NULL;

-- 2. the commitment subject column, adjacent to the expense subject.
ALTER TABLE approval_case
    ADD COLUMN budget_commitment_id BIGINT NULL AFTER expense_claim_id;

-- 3. same-org FK: a commitment case can only reference a commitment of its
--    own organization (budget_commitment(id, org_id) unique target from V11).
ALTER TABLE approval_case
    ADD CONSTRAINT fk_approval_case_commitment_org
        FOREIGN KEY (budget_commitment_id, org_id)
        REFERENCES budget_commitment (id, org_id);

-- 4. one approval case per commitment (mirror of uq_approval_case_org_expense).
ALTER TABLE approval_case
    ADD CONSTRAINT uq_approval_case_org_commitment
        UNIQUE (org_id, budget_commitment_id);

-- 5. composite identity mirror of uq_approval_case_id_expense_org.
ALTER TABLE approval_case
    ADD CONSTRAINT uq_approval_case_id_commitment_org
        UNIQUE (id, budget_commitment_id, org_id);

-- 6. exactly-one-subject invariants, enforced by MySQL for every future row:
--    both NULL and both non-NULL are rejected (XOR of the two presence flags).
ALTER TABLE approval_case
    ADD CONSTRAINT chk_approval_case_subject
        CHECK ((expense_claim_id IS NULL) <> (budget_commitment_id IS NULL));
