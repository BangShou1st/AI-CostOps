-- M13-A: immutable Gateway Provider usage observations.
--
-- Backend remains the sole Flyway owner. These tables intentionally stop at
-- durable metering facts; Settlement, Ledger and Budget Actual belong to M13-B.

CREATE TABLE gateway_usage_fact (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    request_id BIGINT NOT NULL,
    route_attempt_id BIGINT NOT NULL,
    sequence INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    supersedes_usage_fact_id BIGINT NULL,
    provider_request_id VARCHAR(255) NULL,
    usage_effective_at DATETIME(6) NOT NULL,
    usage_effective_at_source VARCHAR(48) NOT NULL,
    pricing_version_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    safe_provider_metadata_json JSON NULL,
    observed_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    final_slot TINYINT
        GENERATED ALWAYS AS (CASE WHEN status='FINAL' THEN 1 ELSE NULL END) VIRTUAL,
    PRIMARY KEY (id),
    CONSTRAINT uq_gateway_usage_fact_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_gateway_usage_fact_request_sequence UNIQUE (org_id, request_id, sequence),
    CONSTRAINT uq_gateway_usage_fact_request_final UNIQUE (org_id, request_id, final_slot),
    CONSTRAINT fk_gateway_usage_fact_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_gateway_usage_fact_request_org
        FOREIGN KEY (request_id, org_id) REFERENCES gateway_request (id, org_id),
    CONSTRAINT fk_gateway_usage_fact_attempt_org
        FOREIGN KEY (route_attempt_id, org_id) REFERENCES gateway_route_attempt (id, org_id),
    CONSTRAINT fk_gateway_usage_fact_supersedes_org
        FOREIGN KEY (supersedes_usage_fact_id, org_id) REFERENCES gateway_usage_fact (id, org_id),
    CONSTRAINT fk_gateway_usage_fact_pricing_org
        FOREIGN KEY (pricing_version_id, org_id) REFERENCES pricing_version (id, org_id),
    CONSTRAINT chk_gateway_usage_fact_sequence CHECK (sequence >= 1),
    CONSTRAINT chk_gateway_usage_fact_status CHECK (status IN ('FINAL','INCOMPLETE','UNKNOWN')),
    CONSTRAINT chk_gateway_usage_fact_effective_source CHECK (usage_effective_at_source IN (
        'PROVIDER_BILLING_TIMESTAMP', 'PROVIDER_REQUEST_TIMESTAMP',
        'GATEWAY_DISPATCH_INTENT_TIMESTAMP')),
    CONSTRAINT chk_gateway_usage_fact_currency CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE gateway_usage_dimension (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    usage_fact_id BIGINT NOT NULL,
    dimension_code VARCHAR(64) NOT NULL,
    quantity DECIMAL(30,8) NOT NULL,
    provenance VARCHAR(32) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_gateway_usage_dimension_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_gateway_usage_dimension_fact_code UNIQUE (usage_fact_id, dimension_code),
    CONSTRAINT fk_gateway_usage_dimension_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_gateway_usage_dimension_fact_org
        FOREIGN KEY (usage_fact_id, org_id) REFERENCES gateway_usage_fact (id, org_id),
    CONSTRAINT chk_gateway_usage_dimension_code CHECK (
        dimension_code IN ('INPUT_TOKEN','OUTPUT_TOKEN','CACHED_INPUT_TOKEN','REQUEST')),
    CONSTRAINT chk_gateway_usage_dimension_quantity CHECK (quantity >= 0),
    CONSTRAINT chk_gateway_usage_dimension_provenance CHECK (
        provenance IN ('PROVIDER_FINAL','PROVIDER_PARTIAL','GATEWAY_DETERMINISTIC'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- gateway_request already carries this nullable forward pointer from V18.
-- Attach the circular FK only after gateway_usage_fact exists.
ALTER TABLE gateway_request
    ADD CONSTRAINT fk_gateway_request_current_usage_org
        FOREIGN KEY (current_usage_fact_id, org_id)
        REFERENCES gateway_usage_fact (id, org_id);
