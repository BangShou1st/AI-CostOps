-- Retire the former built-in Provider catalog rows.
--
-- OPENCODE_ZEN and KILO_GATEWAY depended on the now-removed external shared libraries.
-- MIMO was the legacy single-provider route and no longer has a Gateway adapter. Existing
-- accounts, connection profiles, pricing versions, charges and usage facts are historical
-- financial evidence and are intentionally left untouched; only new catalog-driven routing
-- is disabled.
UPDATE provider_catalog
SET status = 'DISABLED',
    updated_at = UTC_TIMESTAMP(6)
WHERE provider_code IN ('OPENCODE_ZEN', 'KILO_GATEWAY', 'MIMO')
  AND status <> 'DISABLED';
