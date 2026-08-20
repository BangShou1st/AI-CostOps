-- M5 AIC-049: allow the immutable posting transition on expense_claim.
-- V1-V13 remain untouched; this is a forward-only constraint migration.
ALTER TABLE expense_claim
    DROP CHECK chk_expense_claim_status,
    ADD CONSTRAINT chk_expense_claim_status
        CHECK (status IN (
            'DRAFT',
            'SUBMITTED',
            'NEEDS_INFO',
            'APPROVED',
            'POSTED',
            'REJECTED',
            'CANCELED'
        ));
