package com.aicostops.testsupport;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * FK-safe cleanup for tests that share one MySQL Testcontainer.
 *
 * <p>The order is child-before-parent for every foreign key. The seeded
 * {@code role}/{@code permission}/{@code role_permission} catalogs are never deleted,
 * so M1 seed assertions remain stable across tests.
 */
public final class M2DatabaseCleaner {

    private M2DatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM attribution_hint");
        jdbc.update("DELETE FROM charge_fact");
        jdbc.update("DELETE FROM pricing_fact");
        jdbc.update("DELETE FROM consumption_fact");
        jdbc.update("DELETE FROM external_document");
        jdbc.update("DELETE FROM import_issue");
        jdbc.update("DELETE FROM raw_provider_record");
        // confirmed_attempt_id and predecessor_attempt_id reference import_attempt; clear them before deletion.
        jdbc.update("UPDATE import_batch SET confirmed_attempt_id=NULL");
        jdbc.update("UPDATE import_attempt SET predecessor_attempt_id=NULL");
        jdbc.update("DELETE FROM import_attempt");
        jdbc.update("DELETE FROM import_batch");
        jdbc.update("DELETE FROM evidence");
        jdbc.update("DELETE FROM api_idempotency");
        jdbc.update("DELETE FROM audit_event");
        jdbc.update("DELETE FROM invitation");
        jdbc.update("DELETE FROM role_assignment");
        jdbc.update("DELETE FROM project_member");
        jdbc.update("DELETE FROM team_member");
        jdbc.update("DELETE FROM provider_account");
        jdbc.update("DELETE FROM organization_member");
        jdbc.update("DELETE FROM project");
        jdbc.update("DELETE FROM team");
        jdbc.update("DELETE FROM cost_center");
        jdbc.update("DELETE FROM user_credential");
        jdbc.update("DELETE FROM app_user");
        jdbc.update("DELETE FROM organization");
    }
}
