-- M14: bounded multi-provider routing administration and route lineage.
-- V1-V21 are immutable. Routing is explicit data, never a policy DSL.

CREATE TABLE routing_policy (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    project_id BIGINT NULL,
    model_id BIGINT NOT NULL,
    version INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    project_scope_key BIGINT GENERATED ALWAYS AS (COALESCE(project_id, 0)) STORED,
    active_slot TINYINT GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN 1 ELSE NULL END
    ) STORED,
    created_at DATETIME(6) NOT NULL,
    activated_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_routing_policy_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_routing_policy_scope_version
        UNIQUE (org_id, project_scope_key, model_id, version),
    CONSTRAINT uq_routing_policy_scope_active
        UNIQUE (org_id, project_scope_key, model_id, active_slot),
    CONSTRAINT fk_routing_policy_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_routing_policy_project_org
        FOREIGN KEY (project_id, org_id) REFERENCES project (id, org_id),
    CONSTRAINT fk_routing_policy_model FOREIGN KEY (model_id) REFERENCES model_catalog (id),
    CONSTRAINT chk_routing_policy_project CHECK (project_id IS NULL OR project_id > 0),
    CONSTRAINT chk_routing_policy_version CHECK (version >= 1),
    CONSTRAINT chk_routing_policy_status CHECK (status IN ('DRAFT','ACTIVE','RETIRED')),
    KEY idx_routing_policy_lookup (org_id, project_scope_key, model_id, status, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE routing_policy_candidate (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    routing_policy_id BIGINT NOT NULL,
    provider_account_id BIGINT NOT NULL,
    provider_model_id BIGINT NOT NULL,
    priority INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    privacy_region_code VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_routing_policy_candidate_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_routing_policy_candidate_route
        UNIQUE (routing_policy_id, provider_account_id, provider_model_id),
    CONSTRAINT fk_routing_policy_candidate_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_routing_policy_candidate_policy_org
        FOREIGN KEY (routing_policy_id, org_id) REFERENCES routing_policy (id, org_id),
    CONSTRAINT fk_routing_policy_candidate_account_org
        FOREIGN KEY (provider_account_id, org_id) REFERENCES provider_account (id, org_id),
    CONSTRAINT fk_routing_policy_candidate_provider_model
        FOREIGN KEY (provider_model_id) REFERENCES provider_model (id),
    CONSTRAINT chk_routing_policy_candidate_priority CHECK (priority >= 0),
    CONSTRAINT chk_routing_policy_candidate_status CHECK (status IN ('ACTIVE','DISABLED')),
    KEY idx_routing_policy_candidate_order (routing_policy_id, status, priority, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE gateway_route_attempt
    ADD COLUMN route_reason_code VARCHAR(64) NULL AFTER route_decision_id,
    ADD CONSTRAINT fk_gateway_route_attempt_policy_org
        FOREIGN KEY (routing_policy_id, org_id) REFERENCES routing_policy (id, org_id),
    ADD CONSTRAINT chk_gateway_route_attempt_route_reason CHECK (
        route_reason_code IS NULL
        OR route_reason_code IN ('INITIAL_PRIMARY','INITIAL_FALLBACK','SAFE_FAILOVER')),
    ADD KEY idx_gateway_route_attempt_policy (org_id, routing_policy_id, route_reason_code);

-- Preserve the existing single-provider MiMo route for every organization and
-- logical model that already has a valid route. The correlated MIN account
-- keeps the historical M11 selection deterministic while allowing later
-- administrators to add ordered candidates.
INSERT INTO routing_policy(
    org_id, project_id, model_id, version, status, created_at, activated_at)
SELECT pa.org_id, NULL, pm.model_id, 1, 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
FROM provider_account pa
JOIN provider_catalog pc
  ON pc.provider_code = pa.provider_code AND pc.status = 'ACTIVE'
JOIN provider_model pm
  ON pm.provider_code = pa.provider_code
 AND pm.status = 'ACTIVE' AND pm.routing_eligible = TRUE
JOIN provider_credential pcred
  ON pcred.org_id = pa.org_id AND pcred.provider_account_id = pa.id
 AND pcred.status = 'ACTIVE'
JOIN pricing_version pv
  ON pv.org_id = pa.org_id AND pv.provider_account_id = pa.id
  AND pv.provider_model_id = pm.id AND pv.status = 'ACTIVE'
  AND pv.effective_from <= UTC_TIMESTAMP(6)
  AND (pv.effective_to IS NULL OR pv.effective_to > UTC_TIMESTAMP(6))
WHERE pa.provider_code = 'MIMO'
  AND pa.status = 'ACTIVE'
GROUP BY pa.org_id, pm.model_id;

INSERT INTO routing_policy_candidate(
    org_id, routing_policy_id, provider_account_id, provider_model_id,
    priority, status, privacy_region_code, created_at)
SELECT rp.org_id, rp.id, pa.id, pm.id, 0, 'ACTIVE', NULL, UTC_TIMESTAMP(6)
FROM routing_policy rp
JOIN provider_model pm
  ON pm.model_id = rp.model_id AND pm.provider_code = 'MIMO'
 AND pm.status = 'ACTIVE' AND pm.routing_eligible = TRUE
JOIN provider_account pa
  ON pa.org_id = rp.org_id AND pa.provider_code = pm.provider_code
 AND pa.status = 'ACTIVE'
JOIN provider_credential pcred
  ON pcred.org_id = pa.org_id AND pcred.provider_account_id = pa.id
 AND pcred.status = 'ACTIVE'
JOIN pricing_version pv
  ON pv.org_id = pa.org_id AND pv.provider_account_id = pa.id
  AND pv.provider_model_id = pm.id AND pv.status = 'ACTIVE'
  AND pv.effective_from <= UTC_TIMESTAMP(6)
  AND (pv.effective_to IS NULL OR pv.effective_to > UTC_TIMESTAMP(6))
WHERE rp.project_id IS NULL AND rp.version = 1 AND rp.status = 'ACTIVE'
  AND pv.id = (
      SELECT MIN(pv2.id)
      FROM pricing_version pv2
      JOIN provider_account pa2
        ON pa2.org_id = pv2.org_id AND pa2.id = pv2.provider_account_id
       AND pa2.provider_code = 'MIMO' AND pa2.status = 'ACTIVE'
      JOIN provider_model pm2
        ON pm2.id = pv2.provider_model_id AND pm2.provider_code = 'MIMO'
       AND pm2.model_id = rp.model_id
       AND pm2.status = 'ACTIVE' AND pm2.routing_eligible = TRUE
      JOIN provider_catalog pc2
        ON pc2.provider_code = pa2.provider_code AND pc2.status = 'ACTIVE'
      JOIN provider_credential pcred2
        ON pcred2.org_id = pa2.org_id AND pcred2.provider_account_id = pa2.id
       AND pcred2.status = 'ACTIVE'
      WHERE pv2.status = 'ACTIVE'
        AND pv2.effective_from <= UTC_TIMESTAMP(6)
        AND (pv2.effective_to IS NULL OR pv2.effective_to > UTC_TIMESTAMP(6))
        AND pv2.org_id = rp.org_id
  );
