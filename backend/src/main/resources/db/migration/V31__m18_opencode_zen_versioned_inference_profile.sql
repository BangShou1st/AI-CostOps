-- V30 is already published and may be present in persistent Flyway histories, so it is immutable.
-- V30 moved the catalog and ACTIVE profile endpoint in place. Preserve each row's current contents
-- and route-attempt identity, retire that profile, and activate a new version using the same
-- server-owned CLI inference endpoint. If V30 has already run, its former ACTIVE base_url cannot be
-- reconstructed safely for requests dispatched during that interval; see the acceptance record.

UPDATE provider_catalog
SET base_url = 'https://opencode.ai/inference/v1',
    updated_at = UTC_TIMESTAMP(6)
WHERE provider_code = 'OPENCODE_ZEN';

CREATE TEMPORARY TABLE opencode_zen_active_profile_snapshot ENGINE=InnoDB AS
SELECT p.org_id,
       p.provider_account_id,
       COALESCE(versions.max_version, 0) + 1 AS next_version,
       p.connection_kind,
       p.template_code,
       p.protocol_code,
       p.completion_path,
       p.models_path,
       p.auth_type,
       p.auth_header_name,
       p.network_policy,
       p.user_agent,
       p.connect_timeout_ms,
       p.response_timeout_ms,
       p.created_by
FROM provider_connection_profile p
JOIN provider_account pa
  ON pa.id = p.provider_account_id
 AND pa.org_id = p.org_id
LEFT JOIN (
    SELECT org_id, provider_account_id, MAX(version) AS max_version
    FROM provider_connection_profile
    GROUP BY org_id, provider_account_id
) versions
  ON versions.org_id = p.org_id
 AND versions.provider_account_id = p.provider_account_id
WHERE pa.provider_code = 'OPENCODE_ZEN'
  AND p.status = 'ACTIVE';

UPDATE provider_connection_profile p
JOIN provider_account pa
  ON pa.id = p.provider_account_id
 AND pa.org_id = p.org_id
SET p.status = 'RETIRED',
    p.retired_at = UTC_TIMESTAMP(6)
WHERE pa.provider_code = 'OPENCODE_ZEN'
  AND p.status = 'ACTIVE';

INSERT INTO provider_connection_profile(
    org_id,provider_account_id,version,connection_kind,template_code,protocol_code,base_url,
    completion_path,models_path,auth_type,auth_header_name,network_policy,user_agent,
    connect_timeout_ms,response_timeout_ms,status,created_by,created_at,activated_at,retired_at)
SELECT org_id,provider_account_id,next_version,connection_kind,template_code,protocol_code,
       'https://opencode.ai/inference/v1',completion_path,models_path,auth_type,auth_header_name,
       network_policy,user_agent,connect_timeout_ms,response_timeout_ms,'ACTIVE',created_by,
       UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL
FROM opencode_zen_active_profile_snapshot;

DROP TEMPORARY TABLE opencode_zen_active_profile_snapshot;
