-- M5 review fix: make polymorphic ledger targets same-organization FKs.
-- V1-V14 remain untouched; nullable target columns retain normal NULL FK semantics.
ALTER TABLE project
    ADD CONSTRAINT uq_project_id_org UNIQUE (id, org_id);

ALTER TABLE team
    ADD CONSTRAINT uq_team_id_org UNIQUE (id, org_id);

ALTER TABLE cost_center
    ADD CONSTRAINT uq_cost_center_id_org UNIQUE (id, org_id);

ALTER TABLE ledger_entry
    ADD CONSTRAINT fk_ledger_entry_project_org
        FOREIGN KEY (project_id, org_id)
        REFERENCES project (id, org_id),
    ADD CONSTRAINT fk_ledger_entry_team_org
        FOREIGN KEY (team_id, org_id)
        REFERENCES team (id, org_id),
    ADD CONSTRAINT fk_ledger_entry_cost_center_org
        FOREIGN KEY (cost_center_id, org_id)
        REFERENCES cost_center (id, org_id);
