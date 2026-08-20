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
        // M5 immutable history is append-only in production, but test fixtures
        // must remove its children before allocation/budget source rows.
        jdbc.update("DELETE FROM budget_commitment_usage");
        // CorrectionGroup and LedgerEntry have a deliberate circular FK: remove
        // correction entries first, then their groups, then historical entries.
        jdbc.update("DELETE FROM ledger_entry WHERE correction_group_id IS NOT NULL");
        jdbc.update("DELETE FROM correction_group");
        jdbc.update("DELETE FROM ledger_entry");
        jdbc.update("DELETE FROM ledger_posting");
        jdbc.update("DELETE FROM duplicate_candidate");
        jdbc.update("DELETE FROM allocation_line");
        // V11 budget/period chain: usage -> commitment -> budget -> period.
        // V12 (AIC-044) added approval_case.budget_commitment_id, so approval
        // cases must be deleted before their referenced commitments.
        // M4 pointers on expense_claim must be cleared before their referenced
        // approval_case / allocation_decision rows are deleted; allocation
        // decisions themselves reference expense_claim (V10 FK), so decisions
        // are deleted before their expense rows.
        jdbc.update("DELETE FROM approval_action");
        jdbc.update(
                "UPDATE expense_claim SET current_allocation_decision_id=NULL, approval_case_id=NULL");
        jdbc.update("DELETE FROM approval_case");
        jdbc.update("DELETE FROM budget_commitment");
        jdbc.update("DELETE FROM budget");
        jdbc.update("DELETE FROM billing_period");
        // current decision and duplicate pointers on charge_fact must be cleared
        // before their referenced decision/charge rows are deleted.
        jdbc.update(
                "UPDATE charge_fact SET current_allocation_decision_id=NULL, duplicate_of_charge_id=NULL");
        jdbc.update("DELETE FROM allocation_decision");
        jdbc.update("DELETE FROM expense_claim");
        jdbc.update("DELETE FROM allocation_rule");
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
