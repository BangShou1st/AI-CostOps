-- HC-04 A1: Maintain redundant effective reservation counter on budget so
-- Gateway admission uses atomic conditional UPDATE without lock-held SUM.
ALTER TABLE budget ADD COLUMN reserved_amount DECIMAL(20,8) NOT NULL DEFAULT 0;

UPDATE budget b SET reserved_amount = (
  SELECT COALESCE(SUM(br.reserved_amount), 0)
  FROM budget_reservation br
  WHERE br.org_id = b.org_id
    AND br.budget_id = b.id
    AND br.status IN ('ACTIVE', 'PENDING_HOLD')
);

ALTER TABLE budget ADD CONSTRAINT chk_budget_reserved_nonneg CHECK (reserved_amount >= 0);
