CREATE TABLE organization (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(200) NOT NULL,
    slug VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    settings_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_organization_slug UNIQUE (slug),
    CONSTRAINT chk_organization_status CHECK (status IN ('ACTIVE','ARCHIVED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE app_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    email_normalized VARCHAR(320) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    security_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_app_user_email_normalized UNIQUE (email_normalized),
    KEY idx_app_user_status (status),
    CONSTRAINT chk_app_user_status CHECK (status IN ('ACTIVE','DISABLED')),
    CONSTRAINT chk_app_user_security_version CHECK (security_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE user_credential (
    user_id BIGINT NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    password_changed_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_user_credential_user FOREIGN KEY (user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cost_center (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_cost_center_org_code UNIQUE (org_id, code),
    CONSTRAINT fk_cost_center_org FOREIGN KEY (org_id) REFERENCES organization(id),
    CONSTRAINT chk_cost_center_status CHECK (status IN ('ACTIVE','ARCHIVED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE organization_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    employee_no VARCHAR(100) NULL,
    default_cost_center_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    joined_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_organization_member_org_user UNIQUE (org_id, user_id),
    KEY idx_organization_member_user_status (user_id, status),
    CONSTRAINT fk_organization_member_org FOREIGN KEY (org_id) REFERENCES organization(id),
    CONSTRAINT fk_organization_member_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_organization_member_cost_center FOREIGN KEY (default_cost_center_id) REFERENCES cost_center(id),
    CONSTRAINT chk_organization_member_status CHECK (status IN ('ACTIVE','ARCHIVED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE `role` (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_role_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE permission (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_permission_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE role_permission (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES `role`(id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE role_assignment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_member_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    scope_type VARCHAR(32) NOT NULL,
    scope_id BIGINT NOT NULL,
    assigned_by BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_role_assignment_natural UNIQUE (org_member_id, role_id, scope_type, scope_id),
    CONSTRAINT fk_role_assignment_member FOREIGN KEY (org_member_id) REFERENCES organization_member(id),
    CONSTRAINT fk_role_assignment_role FOREIGN KEY (role_id) REFERENCES `role`(id),
    CONSTRAINT fk_role_assignment_assigned_by FOREIGN KEY (assigned_by) REFERENCES organization_member(id),
    CONSTRAINT chk_role_assignment_scope CHECK (scope_type IN ('ORG','PROJECT','TEAM','COST_CENTER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE invitation (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    email_normalized VARCHAR(320) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    initial_role_code VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    invited_by BIGINT NULL,
    accepted_by_user_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    accepted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_invitation_token_hash UNIQUE (token_hash),
    KEY idx_invitation_token_hash (token_hash),
    KEY idx_invitation_email_status (email_normalized, status),
    CONSTRAINT fk_invitation_org FOREIGN KEY (org_id) REFERENCES organization(id),
    CONSTRAINT fk_invitation_role FOREIGN KEY (initial_role_code) REFERENCES `role`(code),
    CONSTRAINT fk_invitation_inviter FOREIGN KEY (invited_by) REFERENCES organization_member(id),
    CONSTRAINT fk_invitation_accepted_user FOREIGN KEY (accepted_by_user_id) REFERENCES app_user(id),
    CONSTRAINT chk_invitation_status CHECK (status IN ('PENDING','ACCEPTED','EXPIRED','REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_team_org_code UNIQUE (org_id, code),
    CONSTRAINT fk_team_org FOREIGN KEY (org_id) REFERENCES organization(id),
    CONSTRAINT chk_team_status CHECK (status IN ('ACTIVE','ARCHIVED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE team_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    team_id BIGINT NOT NULL,
    org_member_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    joined_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_team_member_team_member UNIQUE (team_id, org_member_id),
    CONSTRAINT fk_team_member_team FOREIGN KEY (team_id) REFERENCES team(id),
    CONSTRAINT fk_team_member_org_member FOREIGN KEY (org_member_id) REFERENCES organization_member(id),
    CONSTRAINT chk_team_member_status CHECK (status IN ('ACTIVE','ARCHIVED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_project_org_code UNIQUE (org_id, code),
    CONSTRAINT fk_project_org FOREIGN KEY (org_id) REFERENCES organization(id),
    CONSTRAINT chk_project_status CHECK (status IN ('ACTIVE','ARCHIVED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE project_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    project_id BIGINT NOT NULL,
    org_member_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    joined_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_project_member_project_member UNIQUE (project_id, org_member_id),
    CONSTRAINT fk_project_member_project FOREIGN KEY (project_id) REFERENCES project(id),
    CONSTRAINT fk_project_member_org_member FOREIGN KEY (org_member_id) REFERENCES organization_member(id),
    CONSTRAINT chk_project_member_status CHECK (status IN ('ACTIVE','ARCHIVED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE provider_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    provider_code VARCHAR(100) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    external_account_ref VARCHAR(255) NULL,
    status VARCHAR(32) NOT NULL,
    metadata_json JSON NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_provider_account_org_provider_name UNIQUE (org_id, provider_code, display_name),
    CONSTRAINT fk_provider_account_org FOREIGN KEY (org_id) REFERENCES organization(id),
    CONSTRAINT chk_provider_account_status CHECK (status IN ('ACTIVE','ARCHIVED','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE audit_event (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NULL,
    actor_user_id BIGINT NULL,
    event_type VARCHAR(100) NOT NULL,
    subject_type VARCHAR(100) NULL,
    subject_id BIGINT NULL,
    metadata_json JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_event_org_created (org_id, created_at),
    KEY idx_audit_event_actor_created (actor_user_id, created_at),
    CONSTRAINT fk_audit_event_org FOREIGN KEY (org_id) REFERENCES organization(id),
    CONSTRAINT fk_audit_event_actor FOREIGN KEY (actor_user_id) REFERENCES app_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE api_idempotency (
    id BIGINT NOT NULL AUTO_INCREMENT,
    org_id BIGINT NOT NULL,
    actor_member_id BIGINT NOT NULL,
    operation VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status INT NOT NULL,
    response_body JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_api_idempotency_natural UNIQUE (org_id, actor_member_id, operation, idempotency_key),
    CONSTRAINT fk_api_idempotency_org FOREIGN KEY (org_id) REFERENCES organization(id),
    CONSTRAINT fk_api_idempotency_actor FOREIGN KEY (actor_member_id) REFERENCES organization_member(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
