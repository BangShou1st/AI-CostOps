-- M12 AIC-087/AIC-092: MySQL-authoritative budget reservation (Wave M12).
--
-- Implements the exact AIC-092 section 12 logical schema contract for the
-- single M12 table:
--   budget_reservation
--
-- Same-organization integrity convention: PRIMARY KEY (id),
-- UNIQUE (id, org_id) and composite same-org FKs pointing at parent
-- (id, org_id). V1-V18 are deliberately untouched. No M13
-- (gateway_usage_fact / gateway_usage_dimension / gateway_settlement) or
-- Ledger schema is created here.
--
-- Reservation identity is per Route Attempt: UNIQUE(org_id,
-- route_attempt_id). At most one economically effective ACTIVE/PENDING_HOLD
-- reservation may exist per request at a time, enforced by the generated
-- effective_slot plus UNIQUE(org_id, request_id, effective_slot). A safe
-- failover must first durably release the old hold.
--
-- M12 Commitment fields stay NULL/0: Gateway never infers a Commitment
-- binding in M12. FINALIZED is schema/lifecycle compatibility for M13;
-- M12 itself creates no final financial Settlement.

-- ---------------------------------------------------------------------------
-- 12. budget_reservation (Gateway-owned, MySQL-authoritative spend hold)
-- ---------------------------------------------------------------------------
CREATE TABLE budget_reservation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    request_id BIGINT NOT NULL,
    route_attempt_id BIGINT NOT NULL,
    billing_period_id BIGINT NOT NULL,
    budget_id BIGINT NOT NULL,
    financial_scope_type VARCHAR(32) NOT NULL,
    financial_scope_id BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    reserved_amount DECIMAL(20,8) NOT NULL,
    commitment_id BIGINT NULL,
    commitment_backed_amount DECIMAL(20,8) NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL,
    effective_slot TINYINT
        GENERATED ALWAYS AS (
            CASE WHEN status IN ('ACTIVE','PENDING_HOLD') THEN 1 ELSE NULL END
        ) VIRTUAL,
    expires_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    released_at DATETIME(6) NULL,
    finalized_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_budget_reservation_id_org UNIQUE (id, org_id),
    CONSTRAINT uq_budget_reservation_route UNIQUE (org_id, route_attempt_id),
    CONSTRAINT uq_budget_reservation_effective UNIQUE (org_id, request_id, effective_slot),
    CONSTRAINT fk_budget_reservation_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_budget_reservation_request_org
        FOREIGN KEY (request_id, org_id) REFERENCES gateway_request (id, org_id),
    CONSTRAINT fk_budget_reservation_attempt_org
        FOREIGN KEY (route_attempt_id, org_id) REFERENCES gateway_route_attempt (id, org_id),
    CONSTRAINT fk_budget_reservation_period_org
        FOREIGN KEY (billing_period_id, org_id) REFERENCES billing_period (id, org_id),
    CONSTRAINT fk_budget_reservation_budget_org
        FOREIGN KEY (budget_id, org_id) REFERENCES budget (id, org_id),
    CONSTRAINT fk_budget_reservation_commitment_org
        FOREIGN KEY (commitment_id, org_id) REFERENCES budget_commitment (id, org_id),
    CONSTRAINT chk_budget_reservation_scope_type
        CHECK (financial_scope_type IN ('PROJECT','TEAM','COST_CENTER')),
    CONSTRAINT chk_budget_reservation_currency
        CHECK (REGEXP_LIKE(currency, '^[A-Z]{3}$')),
    CONSTRAINT chk_budget_reservation_reserved_amount
        CHECK (reserved_amount > 0),
    CONSTRAINT chk_budget_reservation_commitment_backed
        CHECK (commitment_backed_amount >= 0 AND commitment_backed_amount <= reserved_amount),
    CONSTRAINT chk_budget_reservation_status
        CHECK (status IN ('ACTIVE','PENDING_HOLD','RELEASED','FINALIZED')),
    CONSTRAINT chk_budget_reservation_version
        CHECK (version >= 0),
    -- Request-time availability: SUM of effective holds for one Budget row.
    KEY idx_budget_reservation_org_budget
        (org_id, budget_id, status),
    -- Bounded TTL recovery scan: expired ACTIVE holds first.
    KEY idx_budget_reservation_recovery
        (status, expires_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
