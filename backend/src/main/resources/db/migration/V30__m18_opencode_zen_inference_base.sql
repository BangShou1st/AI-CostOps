-- Adopt the OpenCode Zen shared-library CLI inference posture (explicit, versioned endpoint decision
-- recorded 2026-10-17; see docs/03-acceptance/2026-10-17-opencode-zen-inference-posture.md).
--
-- V24 seeded provider_catalog with the legacy discovery base https://opencode.ai/zen/v1 and backfilled
-- provider_connection_profile from pc.base_url; its INSERT ... ON DUPLICATE KEY UPDATE never rewrote
-- base_url, so an already-migrated database keeps the legacy endpoint until this migration runs.
-- V24 is immutable evidence: never edit it. This migration moves the ACTIVE routing authority of the
-- built-in OPENCODE_ZEN provider to the CLI inference base. Retired/historical profile rows are left
-- untouched so the frozen profile history is not rewritten.

UPDATE provider_catalog
SET base_url = 'https://opencode.ai/inference/v1',
    updated_at = UTC_TIMESTAMP(6)
WHERE provider_code = 'OPENCODE_ZEN';

UPDATE provider_connection_profile p
JOIN provider_account pa ON pa.id = p.provider_account_id AND pa.org_id = p.org_id
SET p.base_url = 'https://opencode.ai/inference/v1'
WHERE pa.provider_code = 'OPENCODE_ZEN'
  AND p.status = 'ACTIVE';
