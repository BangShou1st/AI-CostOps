-- M2 Group 1: FINANCE_REVIEWER may read/select Provider Accounts for imports.
-- This grants read only; PROVIDER_ACCOUNT_MANAGE remains Finance Admin / System Admin territory.

INSERT INTO role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM `role` r
JOIN permission p
WHERE r.code='FINANCE_REVIEWER'
  AND p.code='PROVIDER_ACCOUNT_READ'
  AND NOT EXISTS (
    SELECT 1 FROM role_permission rp
    WHERE rp.role_id=r.id AND rp.permission_id=p.id
  );
