-- M11 AIC-094: Gateway edge foundation schema (Wave M11).
--
-- Implements the exact AIC-092 logical schema contract for the eleven M11
-- tables:
--   service_identity, gateway_credential, gateway_credential_model,
--   provider_credential, provider_catalog, model_catalog, provider_model,
--   pricing_version, pricing_rate, gateway_request, gateway_route_attempt
--
-- Same-organization integrity convention: every organization-owned table has
-- PRIMARY KEY (id), UNIQUE (id, org_id) and composite same-org FKs pointing
-- at parent (id, org_id). provider_catalog / model_catalog / provider_model
-- are global server-governed catalogs and are intentionally not org-scoped.
--
-- V1-V17 are deliberately untouched. No M12 (budget_reservation), M13
-- (gateway_usage_fact / gateway_settlement) or Ledger schema is created here.

-- ---------------------------------------------------------------------------
-- 6.1 service_identity
-- ---------------------------------------------------------------------------
CREATE TABLE service_identity (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_service_identity_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_service_identity_org_code UNIQUE (org_id, code),
    CONSTRAINT fk_service_identity_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT chk_service_identity_status CHECK (status IN ('ACTIVE','DISABLED','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 6.2 gateway_credential
-- ---------------------------------------------------------------------------
CREATE TABLE gateway_credential (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    credential_prefix CHAR(12) NOT NULL,
    secret_digest BINARY(32) NOT NULL,
    secret_digest_version SMALLINT UNSIGNED NOT NULL,
    principal_type VARCHAR(32) NOT NULL,
    organization_member_id BIGINT NULL,
    service_identity_id BIGINT NULL,
    project_id BIGINT NOT NULL,
    financial_scope_type VARCHAR(32) NOT NULL,
    financial_scope_id BIGINT NOT NULL,
    budget_enforcement_mode VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NULL,
    predecessor_credential_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_gateway_credential_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_gateway_credential_prefix UNIQUE (credential_prefix),
    CONSTRAINT fk_gateway_credential_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_gateway_credential_member_org
        FOREIGN KEY (organization_member_id, org_id) REFERENCES organization_member (id, org_id),
    CONSTRAINT fk_gateway_credential_service_org
        FOREIGN KEY (service_identity_id, org_id) REFERENCES service_identity (id, org_id),
    CONSTRAINT fk_gateway_credential_predecessor_org
        FOREIGN KEY (predecessor_credential_id, org_id) REFERENCES gateway_credential (id, org_id),
    CONSTRAINT chk_gateway_credential_principal_type CHECK (principal_type IN ('HUMAN_MEMBER','SERVICE')),
    -- Exactly one principal FK is populated and matches principal_type.
    -- project/team/cost-center same-org ACTIVE-state validation is performed
    -- in the Control Plane transaction (polymorphic scope has no single FK).
    CONSTRAINT chk_gateway_credential_principal_xor CHECK (
        (principal_type = 'HUMAN_MEMBER' AND organization_member_id IS NOT NULL AND service_identity_id IS NULL)
        OR
        (principal_type = 'SERVICE' AND organization_member_id IS NULL AND service_identity_id IS NOT NULL)),
    CONSTRAINT chk_gateway_credential_financial_scope_type
        CHECK (financial_scope_type IN ('PROJECT','TEAM','COST_CENTER')),
    CONSTRAINT chk_gateway_credential_budget_mode CHECK (budget_enforcement_mode IN ('REQUIRED','OPTIONAL')),
    CONSTRAINT chk_gateway_credential_status CHECK (status IN ('ACTIVE','REVOKED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 7.3 model_catalog (global, server-governed)
-- ---------------------------------------------------------------------------
CREATE TABLE model_catalog (
    id BIGINT NOT NULL AUTO_INCREMENT,
    model_key VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    capabilities_json JSON NOT NULL,
    default_max_output_tokens INT NULL,
    max_output_tokens INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_model_catalog_key UNIQUE (model_key),
    CONSTRAINT chk_model_catalog_status CHECK (status IN ('ACTIVE','DISABLED','ARCHIVED')),
    CONSTRAINT chk_model_catalog_max_output_tokens CHECK (max_output_tokens > 0),
    CONSTRAINT chk_model_catalog_default_tokens CHECK (
        default_max_output_tokens IS NULL
        OR (default_max_output_tokens > 0 AND default_max_output_tokens <= max_output_tokens))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 7.2 provider_catalog (global, server-governed)
-- ---------------------------------------------------------------------------
CREATE TABLE provider_catalog (
    provider_code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    adapter_code VARCHAR(100) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    status VARCHAR(32) NOT NULL,
    capabilities_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (provider_code),
    CONSTRAINT chk_provider_catalog_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 7.4 provider_model (global mapping logical model -> Provider wire model)
-- ---------------------------------------------------------------------------
CREATE TABLE provider_model (
    id BIGINT NOT NULL AUTO_INCREMENT,
    provider_code VARCHAR(100) NOT NULL,
    model_id BIGINT NOT NULL,
    provider_model_name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    routing_eligible BOOLEAN NOT NULL,
    capabilities_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_provider_model_code_name UNIQUE (provider_code, provider_model_name),
    CONSTRAINT uq_provider_model_code_model_name UNIQUE (provider_code, model_id, provider_model_name),
    CONSTRAINT fk_provider_model_catalog FOREIGN KEY (provider_code) REFERENCES provider_catalog (provider_code),
    CONSTRAINT fk_provider_model_model FOREIGN KEY (model_id) REFERENCES model_catalog (id),
    CONSTRAINT chk_provider_model_status CHECK (status IN ('ACTIVE','DISABLED','RETIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 6.3 gateway_credential_model (explicit-only credential model allowlist)
-- ---------------------------------------------------------------------------
CREATE TABLE gateway_credential_model (
    credential_id BIGINT NOT NULL,
    org_id BIGINT NOT NULL,
    model_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (credential_id, model_id),
    CONSTRAINT fk_gateway_credential_model_credential_org
        FOREIGN KEY (credential_id, org_id) REFERENCES gateway_credential (id, org_id),
    CONSTRAINT fk_gateway_credential_model_model
        FOREIGN KEY (model_id) REFERENCES model_catalog (id),
    CONSTRAINT chk_gateway_credential_model_status CHECK (status IN ('ACTIVE','DISABLED')),
    KEY idx_gateway_credential_model_org (org_id, model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 7.1 provider_credential (encrypted at rest, never plaintext)
-- ---------------------------------------------------------------------------
CREATE TABLE provider_credential (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    provider_account_id BIGINT NOT NULL,
    credential_type VARCHAR(32) NOT NULL,
    ciphertext VARBINARY(2048) NOT NULL,
    nonce BINARY(12) NOT NULL,
    encryption_key_version SMALLINT UNSIGNED NOT NULL,
    safe_label VARCHAR(200) NULL,
    status VARCHAR(32) NOT NULL,
    predecessor_credential_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    rotated_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_provider_credential_id_org UNIQUE (id, org_id),
    CONSTRAINT fk_provider_credential_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_provider_credential_account_org
        FOREIGN KEY (provider_account_id, org_id) REFERENCES provider_account (id, org_id),
    CONSTRAINT fk_provider_credential_predecessor_org
        FOREIGN KEY (predecessor_credential_id, org_id) REFERENCES provider_credential (id, org_id),
    CONSTRAINT chk_provider_credential_type CHECK (credential_type IN ('API_KEY','BEARER_TOKEN')),
    CONSTRAINT chk_provider_credential_status CHECK (status IN ('ACTIVE','REVOKED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 8.1 pricing_version (immutable after use; org-scoped commercial context)
-- ---------------------------------------------------------------------------
CREATE TABLE pricing_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    provider_account_id BIGINT NOT NULL,
    provider_model_id BIGINT NOT NULL,
    version INT NOT NULL,
    currency CHAR(3) NOT NULL,
    effective_from DATETIME(6) NOT NULL,
    effective_to DATETIME(6) NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    activated_at DATETIME(6) NULL,
    retired_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_pricing_version_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_pricing_version_identity UNIQUE (org_id, provider_account_id, provider_model_id, version),
    CONSTRAINT fk_pricing_version_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_pricing_version_account_org
        FOREIGN KEY (provider_account_id, org_id) REFERENCES provider_account (id, org_id),
    CONSTRAINT fk_pricing_version_provider_model
        FOREIGN KEY (provider_model_id) REFERENCES provider_model (id),
    CONSTRAINT chk_pricing_version_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    CONSTRAINT chk_pricing_version_interval CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT chk_pricing_version_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 8.2 pricing_rate (exact typed financial rates, DECIMAL never float)
-- ---------------------------------------------------------------------------
CREATE TABLE pricing_rate (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    pricing_version_id BIGINT NOT NULL,
    dimension_code VARCHAR(64) NOT NULL,
    unit_quantity BIGINT NOT NULL,
    unit_price DECIMAL(20,8) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_pricing_rate_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_pricing_rate_version_dimension UNIQUE (pricing_version_id, dimension_code),
    CONSTRAINT fk_pricing_rate_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_pricing_rate_version_org
        FOREIGN KEY (pricing_version_id, org_id) REFERENCES pricing_version (id, org_id),
    CONSTRAINT chk_pricing_rate_dimension CHECK (dimension_code IN ('INPUT_TOKEN','OUTPUT_TOKEN','CACHED_INPUT_TOKEN','REQUEST')),
    CONSTRAINT chk_pricing_rate_unit_quantity CHECK (unit_quantity > 0),
    CONSTRAINT chk_pricing_rate_unit_price CHECK (unit_price >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 10. gateway_request (Gateway-owned durable business/request identity)
--
-- current_route_attempt_id is a Gateway-owned convenience pointer; the FK is
-- attached after gateway_route_attempt exists (circular reference).
-- current_usage_fact_id is a nullable forward pointer with no FK until the
-- M13 gateway_usage_fact table is created.
-- ---------------------------------------------------------------------------
CREATE TABLE gateway_request (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    public_request_id CHAR(40) NOT NULL,
    credential_id BIGINT NOT NULL,
    principal_type VARCHAR(32) NOT NULL,
    organization_member_id BIGINT NULL,
    service_identity_id BIGINT NULL,
    project_id BIGINT NOT NULL,
    financial_scope_type VARCHAR(32) NOT NULL,
    financial_scope_id BIGINT NOT NULL,
    logical_model_id BIGINT NOT NULL,
    api_surface VARCHAR(32) NOT NULL,
    idempotency_key_digest BINARY(32) NOT NULL,
    request_fingerprint BINARY(32) NOT NULL,
    request_hmac_version SMALLINT UNSIGNED NOT NULL,
    state VARCHAR(32) NOT NULL,
    billing_period_id BIGINT NULL,
    current_route_attempt_id BIGINT NULL,
    current_usage_fact_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    validated_at DATETIME(6) NOT NULL,
    dispatch_intent_at DATETIME(6) NULL,
    terminal_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_gateway_request_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_gateway_request_public UNIQUE (public_request_id),
    CONSTRAINT uq_gateway_request_idem UNIQUE (org_id, credential_id, idempotency_key_digest),
    CONSTRAINT fk_gateway_request_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_gateway_request_credential_org
        FOREIGN KEY (credential_id, org_id) REFERENCES gateway_credential (id, org_id),
    CONSTRAINT fk_gateway_request_member_org
        FOREIGN KEY (organization_member_id, org_id) REFERENCES organization_member (id, org_id),
    CONSTRAINT fk_gateway_request_service_org
        FOREIGN KEY (service_identity_id, org_id) REFERENCES service_identity (id, org_id),
    CONSTRAINT fk_gateway_request_model FOREIGN KEY (logical_model_id) REFERENCES model_catalog (id),
    CONSTRAINT fk_gateway_request_period_org
        FOREIGN KEY (billing_period_id, org_id) REFERENCES billing_period (id, org_id),
    CONSTRAINT chk_gateway_request_api_surface CHECK (api_surface IN ('CHAT_COMPLETIONS')),
    CONSTRAINT chk_gateway_request_principal_type CHECK (principal_type IN ('HUMAN_MEMBER','SERVICE')),
    CONSTRAINT chk_gateway_request_principal_xor CHECK (
        (principal_type = 'HUMAN_MEMBER' AND organization_member_id IS NOT NULL AND service_identity_id IS NULL)
        OR
        (principal_type = 'SERVICE' AND organization_member_id IS NULL AND service_identity_id IS NOT NULL)),
    CONSTRAINT chk_gateway_request_financial_scope_type
        CHECK (financial_scope_type IN ('PROJECT','TEAM','COST_CENTER')),
    CONSTRAINT chk_gateway_request_state CHECK (state IN (
        'VALIDATED','RESERVED','DISPATCH_INTENT','UPSTREAM_ACTIVE','TRANSPORT_COMPLETED',
        'REJECTED_BUDGET','CANCELED_PRE_DISPATCH','FAILED_PRE_DISPATCH',
        'CANCELED_AFTER_DISPATCH','TIMED_OUT_AFTER_DISPATCH','FAILED_AFTER_DISPATCH'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 11. gateway_route_attempt (append-only Provider attempt history)
--
-- routing_policy_id is a nullable forward pointer with no FK until the M14
-- routing administration tables exist.
-- ---------------------------------------------------------------------------
CREATE TABLE gateway_route_attempt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    request_id BIGINT NOT NULL,
    attempt_no INT NOT NULL,
    route_decision_id CHAR(40) NOT NULL,
    routing_policy_id BIGINT NULL,
    provider_account_id BIGINT NOT NULL,
    provider_model_id BIGINT NOT NULL,
    pricing_version_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    safety_reason_code VARCHAR(64) NULL,
    provider_request_id VARCHAR(255) NULL,
    created_at DATETIME(6) NOT NULL,
    dispatch_intent_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_gateway_route_attempt_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_gateway_route_attempt_attempt UNIQUE (org_id, request_id, attempt_no),
    CONSTRAINT uq_gateway_route_attempt_decision UNIQUE (org_id, route_decision_id),
    CONSTRAINT fk_gateway_route_attempt_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_gateway_route_attempt_request_org
        FOREIGN KEY (request_id, org_id) REFERENCES gateway_request (id, org_id),
    CONSTRAINT fk_gateway_route_attempt_account_org
        FOREIGN KEY (provider_account_id, org_id) REFERENCES provider_account (id, org_id),
    CONSTRAINT fk_gateway_route_attempt_provider_model
        FOREIGN KEY (provider_model_id) REFERENCES provider_model (id),
    CONSTRAINT fk_gateway_route_attempt_pricing_org
        FOREIGN KEY (pricing_version_id, org_id) REFERENCES pricing_version (id, org_id),
    CONSTRAINT chk_gateway_route_attempt_no CHECK (attempt_no >= 1),
    CONSTRAINT chk_gateway_route_attempt_status CHECK (status IN (
        'PLANNED','DISPATCH_INTENT','SAFE_NO_BILLABLE_EXECUTION','BILLABLE_POSSIBLE','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- The circular request <-> route-attempt FK is attached only now that both
-- tables exist and both have UNIQUE (id, org_id).
ALTER TABLE gateway_request
    ADD CONSTRAINT fk_gateway_request_current_route_org
        FOREIGN KEY (current_route_attempt_id, org_id) REFERENCES gateway_route_attempt (id, org_id);

-- AIC-096 (M11, Task 4) introduces PENDING_GATEWAY_FINANCIAL_WORK as a Close
-- blocker code. V1-V17 migrations are never edited; extend the frozen V16
-- CHECK forward so the Gateway financial-work blocker can persist its result.
ALTER TABLE period_close_check
    DROP CHECK chk_period_close_check_blocker;
ALTER TABLE period_close_check
    ADD CONSTRAINT chk_period_close_check_blocker CHECK (blocker_code IN (
        'OPEN_IMPORTS',
        'UNRESOLVED_DUPLICATES',
        'UNALLOCATED_CHARGES',
        'UNPOSTED_APPROVED_EXPENSES',
        'OPEN_MATERIAL_RECONCILIATION',
        'PENDING_CORRECTIONS',
        'LEDGER_INTEGRITY',
        'PENDING_GATEWAY_FINANCIAL_WORK'
    ));