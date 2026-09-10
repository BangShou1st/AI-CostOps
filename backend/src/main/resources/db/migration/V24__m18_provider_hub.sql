-- M18: V3 Provider Hub schema + endpoint-authority migration.
-- V1-V23 immutable. Dispatch authority moves to ACTIVE connection profile.
CREATE TABLE provider_connection_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    provider_account_id BIGINT NOT NULL,
    version INT NOT NULL,
    connection_kind VARCHAR(32) NOT NULL,
    template_code VARCHAR(100) NULL,
    protocol_code VARCHAR(100) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    completion_path VARCHAR(200) NOT NULL,
    models_path VARCHAR(200) NULL,
    auth_type VARCHAR(32) NOT NULL,
    auth_header_name VARCHAR(200) NULL,
    network_policy VARCHAR(32) NOT NULL,
    user_agent VARCHAR(300) NULL,
    connect_timeout_ms INT NOT NULL,
    response_timeout_ms INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_by BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    activated_at DATETIME(6) NULL,
    retired_at DATETIME(6) NULL,
    active_slot TINYINT GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    CONSTRAINT uq_connection_profile_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_connection_profile_account_version UNIQUE (org_id, provider_account_id, version),
    CONSTRAINT uq_connection_profile_account_active UNIQUE (org_id, provider_account_id, active_slot),
    CONSTRAINT fk_connection_profile_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_connection_profile_account_org FOREIGN KEY (provider_account_id, org_id) REFERENCES provider_account (id, org_id),
    CONSTRAINT chk_connection_profile_kind CHECK (connection_kind IN ('BUILTIN','CUSTOM')),
    CONSTRAINT chk_connection_profile_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    CONSTRAINT chk_connection_profile_version CHECK (version >= 1),
    CONSTRAINT chk_connection_profile_network CHECK (network_policy IN ('DIRECT_ONLY','DIRECT_PUBLIC_ONLY')),
    CONSTRAINT chk_connection_profile_auth CHECK (auth_type IN ('BEARER','API_KEY_HEADER','NONE')),
    KEY idx_connection_profile_active (org_id, provider_account_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- Catalog seeds: server-owned provider definitions.
INSERT INTO provider_catalog(provider_code, name, adapter_code, base_url, status, capabilities_json, created_at, updated_at)
VALUES ('OPENCODE_ZEN', 'OpenCode Zen', 'OPENCODE_ZEN', 'https://opencode.ai/zen/v1', 'ACTIVE', CAST('{"protocols": ["OPENAI_CHAT_COMPLETIONS"]}' AS JSON), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)),
('CUSTOM_OPENAI_COMPATIBLE', 'Custom OpenAI-Compatible', 'CUSTOM_OPENAI_COMPATIBLE', 'https://example.invalid', 'DISABLED', CAST('{"protocols": ["OPENAI_CHAT_COMPLETIONS"]}' AS JSON), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE name = VALUES(name), adapter_code = VALUES(adapter_code), updated_at = UTC_TIMESTAMP(6);
-- Backfill ACTIVE v1 for every structurally routable account (same endpoint).
INSERT INTO provider_connection_profile(org_id, provider_account_id, version, connection_kind, template_code, protocol_code, base_url, completion_path, models_path, auth_type, auth_header_name, network_policy, user_agent, connect_timeout_ms, response_timeout_ms, status, created_by, created_at, activated_at, retired_at)
SELECT pa.org_id, pa.id, 1, 'CUSTOM', NULL,
    CASE WHEN pa.provider_code = 'MIMO' THEN 'MIMO_CHAT_COMPLETIONS' ELSE 'OPENAI_CHAT_COMPLETIONS' END,
    pc.base_url, '/chat/completions', '/models',
    CASE WHEN EXISTS(SELECT 1 FROM provider_credential p2 WHERE p2.org_id = pa.org_id AND p2.provider_account_id = pa.id AND p2.status = 'ACTIVE' AND p2.credential_type = 'BEARER_TOKEN') THEN 'BEARER' ELSE 'API_KEY_HEADER' END,
    CASE WHEN EXISTS(SELECT 1 FROM provider_credential p3 WHERE p3.org_id = pa.org_id AND p3.provider_account_id = pa.id AND p3.status = 'ACTIVE' AND p3.credential_type = 'BEARER_TOKEN') THEN NULL ELSE 'X-API-Key' END,
    'DIRECT_PUBLIC_ONLY', NULL, 5000, 60000, 'ACTIVE', NULL, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), NULL
FROM provider_account pa JOIN provider_catalog pc ON pc.provider_code = pa.provider_code AND pc.status = 'ACTIVE'
WHERE pa.status = 'ACTIVE'
AND EXISTS(SELECT 1 FROM provider_credential pcred WHERE pcred.org_id = pa.org_id AND pcred.provider_account_id = pa.id AND pcred.status = 'ACTIVE')
AND EXISTS(SELECT 1 FROM provider_model pm JOIN pricing_version pv ON pv.org_id = pa.org_id AND pv.provider_account_id = pa.id AND pv.provider_model_id = pm.id AND pv.status = 'ACTIVE' AND pv.effective_from <= UTC_TIMESTAMP(6) AND (pv.effective_to IS NULL OR pv.effective_to > UTC_TIMESTAMP(6)) WHERE pm.provider_code = pa.provider_code AND pm.status = 'ACTIVE' AND pm.routing_eligible = TRUE)
AND NOT EXISTS(SELECT 1 FROM provider_connection_profile pcp WHERE pcp.org_id = pa.org_id AND pcp.provider_account_id = pa.id);
-- model_catalog org ownership: NULL = global, org id = private.
ALTER TABLE model_catalog ADD COLUMN owner_org_id BIGINT NULL AFTER model_key,
    ADD COLUMN namespace_key BIGINT GENERATED ALWAYS AS (COALESCE(owner_org_id, 0)) STORED AFTER owner_org_id,
    ADD CONSTRAINT fk_model_catalog_owner_org FOREIGN KEY (owner_org_id) REFERENCES organization (id);
ALTER TABLE model_catalog DROP KEY uq_model_catalog_key;
ALTER TABLE model_catalog ADD CONSTRAINT uq_model_catalog_namespace_key UNIQUE (namespace_key, model_key);
-- provider_model exact account identity: global (NULL,NULL) vs private (org,account).
ALTER TABLE provider_model ADD COLUMN owner_org_id BIGINT NULL AFTER provider_code,
    ADD COLUMN provider_account_id BIGINT NULL AFTER owner_org_id,
    ADD COLUMN namespace_key BIGINT GENERATED ALWAYS AS (COALESCE(owner_org_id, 0)) STORED AFTER provider_account_id,
    ADD COLUMN provider_account_scope BIGINT GENERATED ALWAYS AS (COALESCE(provider_account_id, 0)) STORED AFTER namespace_key,
    ADD CONSTRAINT fk_provider_model_owner_org FOREIGN KEY (owner_org_id) REFERENCES organization (id),
    ADD CONSTRAINT fk_provider_model_account_org FOREIGN KEY (provider_account_id, owner_org_id) REFERENCES provider_account (id, org_id);
ALTER TABLE provider_model DROP KEY uq_provider_model_code_name;
ALTER TABLE provider_model DROP KEY uq_provider_model_code_model_name;
ALTER TABLE provider_model ADD CONSTRAINT uq_provider_model_namespace_name UNIQUE (namespace_key, provider_account_scope, provider_code, provider_model_name),
    ADD CONSTRAINT uq_provider_model_namespace_mapping UNIQUE (namespace_key, provider_account_scope, provider_code, model_id, provider_model_name);
-- provider_model_discovery: scoped observations, never routing truth.
CREATE TABLE provider_model_discovery (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    provider_connection_profile_id BIGINT NOT NULL,
    provider_model_name VARCHAR(200) NOT NULL,
    display_name VARCHAR(200) NULL,
    source VARCHAR(32) NOT NULL,
    availability VARCHAR(32) NOT NULL,
    protocol_code VARCHAR(100) NOT NULL,
    pricing_classification VARCHAR(32) NOT NULL,
    declared_capabilities_json JSON NOT NULL,
    verified_capabilities_json JSON NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    last_probed_at DATETIME(6) NULL,
    last_probe_status VARCHAR(32) NULL,
    last_probe_error_code VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_discovery_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_discovery_profile_model UNIQUE (org_id, provider_connection_profile_id, provider_model_name),
    CONSTRAINT fk_discovery_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_discovery_profile_org FOREIGN KEY (provider_connection_profile_id, org_id) REFERENCES provider_connection_profile (id, org_id),
    CONSTRAINT chk_discovery_source CHECK (source IN ('LIVE_DISCOVERY','MANUAL')),
    CONSTRAINT chk_discovery_availability CHECK (availability IN ('AVAILABLE','UNAVAILABLE','UNKNOWN')),
    CONSTRAINT chk_discovery_pricing_class CHECK (pricing_classification IN ('VERIFIED_FREE','PAID','UNKNOWN')),
    KEY idx_discovery_profile (org_id, provider_connection_profile_id, availability)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
-- Route-attempt connection lineage: NULL = pre-V24 history.
ALTER TABLE gateway_route_attempt ADD COLUMN provider_connection_profile_id BIGINT NULL AFTER provider_model_id,
    ADD CONSTRAINT fk_gateway_route_attempt_connection_org FOREIGN KEY (provider_connection_profile_id, org_id) REFERENCES provider_connection_profile (id, org_id);
-- Gateway credential origin: external vs internal-system identity.
ALTER TABLE gateway_credential ADD COLUMN credential_origin VARCHAR(32) NOT NULL DEFAULT 'USER_ISSUED' AFTER principal_type,
    ADD CONSTRAINT chk_gateway_credential_origin CHECK (credential_origin IN ('USER_ISSUED','INTERNAL_SYSTEM'));
