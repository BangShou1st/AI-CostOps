-- M18 repair: bind Advisor INTERNAL_SYSTEM execution credential to the exact ACTIVE profile.
-- Append-only fix for P0: every ACTIVE profile revision rotates a new credential carrying the
-- profile exact project / financial scope / budget mode / allowed model, retiring predecessor.
-- V1-V25 are immutable.
ALTER TABLE gateway_credential
    ADD COLUMN advisor_profile_id BIGINT NULL AFTER predecessor_credential_id,
    ADD CONSTRAINT fk_gateway_credential_advisor_profile_org
        FOREIGN KEY (advisor_profile_id, org_id) REFERENCES advisor_profile (id, org_id),
    ADD KEY idx_gateway_credential_advisor_identity (org_id, service_identity_id, status),
    ADD KEY idx_gateway_credential_advisor_profile (org_id, advisor_profile_id);
