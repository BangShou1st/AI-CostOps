package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.allocation.AllocationApiTestSupport;
import com.aicostops.reconciliation.application.HybridReconciliationActionService;
import com.aicostops.reconciliation.application.HybridReconciliationActionService.ChargeDispositionCommand;
import com.aicostops.reconciliation.application.HybridReconciliationActionService.CorrectionLinkCommand;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * M15 evidence-item actions are bound to the real case scope: a manual charge
 * disposition is only legal for a Charge whose confirmed import lineage owns
 * the case's provider account, currency and run BillingPeriod window with an
 * eligible review status, and a correction link is only legal when every
 * corrected Ledger entry resolves to the case's provider account and currency
 * through its preserved direct-source lineage. Without these proofs an
 * unrelated same-organization Charge could be dispositioned under any case and
 * bypass the Hybrid posting fence.
 */
@SpringBootTest
@Tag("integration")
class HybridChargeDispositionScopeIntegrationTest extends AllocationApiTestSupport {

    private static final String AUG_START = "2026-08-01 00:00:00.000000";
    private static final String SEP_START = "2026-09-01 00:00:00.000000";

    @Autowired HybridReconciliationActionService actions;

    private AuthenticatedUser actor;
    private long periodId;
    private long runId;
    private long caseId;

    @BeforeEach
    void scopeSetup() {
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='ALLOC_WORKER' AND p.code IN ('RECONCILIATION_RESOLVE')
                """);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        actor = new AuthenticatedUser(actorUserId, 7);

        jdbc.update("""
                INSERT INTO billing_period(org_id,period_start,period_end,status,
                  close_generation,version,created_at,updated_at)
                VALUES (?,?,?,?,0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, AUG_START, SEP_START, "OPEN");
        periodId = jdbc.queryForObject(
                "SELECT MAX(id) FROM billing_period WHERE org_id=?", Long.class, orgId);
        jdbc.update("""
                INSERT INTO reconciliation_run(org_id,billing_period_id,status,algorithm_version,
                  tolerance_amount,basis_hash,summary_json,created_by_member_id,started_at,
                  finished_at,created_at,updated_at)
                VALUES (?,?,'COMPLETED','M15_HYBRID_PERIOD_PROVIDER_CURRENCY_V2','0.00000000',
                  ?,JSON_OBJECT(),?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),
                  UTC_TIMESTAMP(6))
                """, orgId, periodId, "d".repeat(64), actorMemberId);
        runId = jdbc.queryForObject(
                "SELECT id FROM reconciliation_run WHERE org_id=? AND billing_period_id=? "
                        + "ORDER BY id DESC LIMIT 1",
                Long.class, orgId, periodId);
        // The base fixture provider account and confirmed import chain.
        jdbc.update("""
                INSERT INTO reconciliation_case(org_id,reconciliation_run_id,provider_account_id,
                  currency,case_type,external_amount,internal_amount,difference_amount,
                  external_row_count,internal_row_count,status,created_at,updated_at)
                VALUES (?,?,?,'USD','AMOUNT_MISMATCH','10.00000000','8.00000000','-2.00000000',
                  1,1,'OPEN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, runId, accountId);
        caseId = jdbc.queryForObject(
                "SELECT id FROM reconciliation_case WHERE org_id=? AND reconciliation_run_id=?",
                Long.class, orgId, runId);
    }

    @Test
    void manualDispositionSucceedsForAChargeInExactCaseScope() {
        var chargeId = insertCharge("4.00000000", "USD", "2026-08-10 00:00:00", "CLEAN",
                rawRecordId);

        var dispositionId = actions.decideChargeDisposition(actor, caseId,
                new ChargeDispositionCommand(chargeId, "DIRECT_PROVIDER_CHARGE", "MANUAL_DIRECT",
                        "Statement-only charge confirmed by reviewer"),
                UUID.randomUUID().toString());

        assertThat(jdbc.queryForObject(
                "SELECT disposition FROM provider_charge_disposition WHERE id=?",
                String.class, dispositionId)).isEqualTo("DIRECT_PROVIDER_CHARGE");
        assertThat(jdbc.queryForObject(
                "SELECT reconciliation_case_id FROM provider_charge_disposition WHERE id=?",
                Long.class, dispositionId)).isEqualTo(caseId);
    }

    @Test
    void evidenceDispositionRequiresTheSameCaseScope() {
        var chargeId = insertCharge("4.00000000", "USD", "2026-08-10 00:00:00", "CLEAN",
                rawRecordId);

        var dispositionId = actions.decideChargeDisposition(actor, caseId,
                new ChargeDispositionCommand(chargeId, "RECONCILIATION_EVIDENCE",
                        "GATEWAY_COVERED", "Covered by gateway settlement"),
                UUID.randomUUID().toString());

        assertThat(jdbc.queryForObject(
                "SELECT disposition FROM provider_charge_disposition WHERE id=?",
                String.class, dispositionId)).isEqualTo("RECONCILIATION_EVIDENCE");
    }

    @Test
    void dispositionRejectsChargeFromAnotherProviderAccount() {
        // Same organization, same currency, but a confirmed import chain of a
        // different provider account.
        var suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                  capabilities_json,created_at,updated_at)
                VALUES (?,?, 'MIMO', 'https://provider.invalid', 'ACTIVE',JSON_OBJECT(),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "MIMO-" + suffix, suffix);
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,
                  external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "MIMO-" + suffix, suffix, suffix);
        var otherAccount = jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code=?",
                Long.class, orgId, "MIMO-" + suffix);
        var foreignRawRecord = insertConfirmedRawRecord(orgId, actorMemberId, otherAccount,
                suffix);
        var foreignCharge = insertCharge("4.00000000", "USD", "2026-08-10 00:00:00", "CLEAN",
                foreignRawRecord);

        assertThatThrownBy(() -> actions.decideChargeDisposition(actor, caseId,
                new ChargeDispositionCommand(foreignCharge, "DIRECT_PROVIDER_CHARGE",
                        "MANUAL_DIRECT", "Wrong account"), UUID.randomUUID().toString()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("provider account");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM provider_charge_disposition WHERE org_id=?",
                Long.class, orgId)).isZero();
    }

    @Test
    void dispositionRejectsChargeWithMismatchingCurrency() {
        var cnyCharge = insertCharge("4.00000000", "CNY", "2026-08-10 00:00:00", "CLEAN",
                rawRecordId);
        assertThatThrownBy(() -> actions.decideChargeDisposition(actor, caseId,
                new ChargeDispositionCommand(cnyCharge, "DIRECT_PROVIDER_CHARGE",
                        "MANUAL_DIRECT", "Wrong currency"), UUID.randomUUID().toString()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void dispositionRejectsChargeOutsideTheRunBillingPeriod() {
        var lateCharge = insertCharge("4.00000000", "USD", "2026-09-15 00:00:00", "CLEAN",
                rawRecordId);
        assertThatThrownBy(() -> actions.decideChargeDisposition(actor, caseId,
                new ChargeDispositionCommand(lateCharge, "DIRECT_PROVIDER_CHARGE",
                        "MANUAL_DIRECT", "Outside period"), UUID.randomUUID().toString()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("BillingPeriod");
    }

    @Test
    void dispositionRejectsChargeFromAnUnconfirmedImportBatch() {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        var unconfirmedRawRecord = insertUnconfirmedRawRecord(orgId, actorMemberId, accountId,
                suffix);
        var unconfirmedCharge = insertCharge("4.00000000", "USD", "2026-08-10 00:00:00",
                "CLEAN", unconfirmedRawRecord);
        assertThatThrownBy(() -> actions.decideChargeDisposition(actor, caseId,
                new ChargeDispositionCommand(unconfirmedCharge, "DIRECT_PROVIDER_CHARGE",
                        "MANUAL_DIRECT", "Unconfirmed batch"), UUID.randomUUID().toString()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("confirmed");
    }

    @Test
    void dispositionRejectsChargeWithUnsupportedReviewStatus() {
        var excludedCharge = insertCharge("4.00000000", "USD", "2026-08-10 00:00:00",
                "EXCLUDED_NONCOST", rawRecordId);
        assertThatThrownBy(() -> actions.decideChargeDisposition(actor, caseId,
                new ChargeDispositionCommand(excludedCharge, "DIRECT_PROVIDER_CHARGE",
                        "MANUAL_DIRECT", "Excluded"), UUID.randomUUID().toString()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("review status");
    }

    // ------------------------------------------------------------------
    // linkCorrection lineage
    // ------------------------------------------------------------------

    @Test
    void linkCorrectionAcceptsACorrectionOfTheCaseScope() {
        var chargeId = insertCharge("4.00000000", "USD", "2026-08-10 00:00:00", "CLEAN",
                rawRecordId);
        var groupId = insertCorrectionGroupWithEntries(new long[] {chargeId});

        actions.linkCorrection(actor, caseId, new CorrectionLinkCommand(groupId));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM reconciliation_evidence WHERE org_id=? "
                        + "AND reconciliation_case_id=? AND correction_group_id=? "
                        + "AND match_kind='RESOLUTION_ACTION'",
                Long.class, orgId, caseId, groupId)).isEqualTo(1L);
    }

    @Test
    void linkCorrectionRejectsACorrectionOfAnotherProviderScope() {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                  capabilities_json,created_at,updated_at)
                VALUES (?,?, 'MIMO', 'https://provider.invalid', 'ACTIVE',JSON_OBJECT(),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "MIMO-" + suffix, suffix);
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,
                  external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "MIMO-" + suffix, suffix, suffix);
        var otherAccount = jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code=?",
                Long.class, orgId, "MIMO-" + suffix);
        var foreignRawRecord = insertConfirmedRawRecord(orgId, actorMemberId, otherAccount,
                suffix);
        var foreignCharge = insertCharge("4.00000000", "USD", "2026-08-10 00:00:00", "CLEAN",
                foreignRawRecord);
        var groupId = insertCorrectionGroupWithEntries(new long[] {foreignCharge});

        assertThatThrownBy(() -> actions.linkCorrection(actor, caseId,
                new CorrectionLinkCommand(groupId)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("provider account and currency");
    }

    @Test
    void linkCorrectionRejectsAMixedScopeCorrectionGroup() {
        var suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("""
                INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,
                  capabilities_json,created_at,updated_at)
                VALUES (?,?, 'MIMO', 'https://provider.invalid', 'ACTIVE',JSON_OBJECT(),
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "MIMO-" + suffix, suffix);
        jdbc.update("""
                INSERT INTO provider_account(org_id,provider_code,display_name,
                  external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "MIMO-" + suffix, suffix, suffix);
        var otherAccount = jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code=?",
                Long.class, orgId, "MIMO-" + suffix);
        var foreignRawRecord = insertConfirmedRawRecord(orgId, actorMemberId, otherAccount,
                suffix);
        var ownCharge = insertCharge("4.00000000", "USD", "2026-08-10 00:00:00", "CLEAN",
                rawRecordId);
        var foreignCharge = insertCharge("2.00000000", "USD", "2026-08-11 00:00:00", "CLEAN",
                foreignRawRecord);
        var groupId = insertCorrectionGroupWithEntries(new long[] {ownCharge, foreignCharge});

        assertThatThrownBy(() -> actions.linkCorrection(actor, caseId,
                new CorrectionLinkCommand(groupId)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("mixes multiple provider financial scopes");
    }

    @Test
    void linkCorrectionRejectsEntriesWithoutProviderSource() {
        // An expense-sourced correction entry has no provider direct source,
        // so it can never prove the case's provider lineage.
        var expenseId = insertApprovedExpense(actorMemberId, "1.00000000");
        var groupId = insertCorrectionGroupWithExpenseSource(expenseId);

        assertThatThrownBy(() -> actions.linkCorrection(actor, caseId,
                new CorrectionLinkCommand(groupId)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("no recognizable provider");
    }

    private long insertCorrectionGroupWithExpenseSource(long expenseClaimId) {
        jdbc.update("""
                INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,
                  allocation_decision_id,billing_period_id,status,posting_actor_type,
                  posted_by_member_id,posted_at,created_at)
                VALUES (?,?,'EXPENSE_CLAIM',?,NULL,?,'POSTED','MEMBER',?,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "EXPENSE:" + expenseClaimId + ":HIST", expenseClaimId, periodId,
                actorMemberId);
        var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO ledger_entry(
                    org_id,posting_id,entry_index,entry_type,amount,currency,project_id,
                    source_expense_claim_id,created_at)
                VALUES (?,?,0,'COST','1.00000000','CNY',?,?,UTC_TIMESTAMP(6))
                """, orgId, postingId, projectId, expenseClaimId);
        var targetEntryId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO correction_group(org_id,correction_key,reason_code,reason_text,
                  target_entry_id,target_posting_id,status,created_by_member_id,created_at)
                VALUES (?,?,'REVIEWED','test correction',?,?,'POSTED',?,
                  UTC_TIMESTAMP(6))
                """, orgId, "CORRECTION_COMMAND:" + UUID.randomUUID(), targetEntryId,
                postingId, actorMemberId);
        var groupId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,
                  allocation_decision_id,billing_period_id,status,posting_actor_type,
                  posted_by_member_id,posted_at,created_at)
                VALUES (?,?,'CORRECTION',?,NULL,?,'POSTED','MEMBER',?,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "CORRECTION:" + groupId, targetEntryId, periodId, actorMemberId);
        var correctionPostingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO ledger_entry(
                    org_id,posting_id,entry_index,entry_type,amount,currency,project_id,
                    source_expense_claim_id,correction_group_id,created_at)
                VALUES (?,?,0,'REVERSAL','-1.00000000','CNY',?,?,?,UTC_TIMESTAMP(6))
                """, orgId, correctionPostingId, projectId, expenseClaimId, groupId);
        return groupId;
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private long insertCharge(String amount, String currency, String periodStart,
            String reviewStatus, long rawRecord) {
        var nextIndex = jdbc.queryForObject(
                "SELECT COALESCE(MAX(fact_index),-1)+1 FROM charge_fact WHERE raw_record_id=?",
                Integer.class, rawRecord);
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,?,'GLM','USAGE',?,?,?,DATE_ADD(?, INTERVAL 1 DAY),?,
                  UTC_TIMESTAMP(6))
                """, orgId, rawRecord, nextIndex, amount, currency, periodStart, periodStart,
                reviewStatus);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM charge_fact WHERE org_id=? AND raw_record_id=?",
                Long.class, orgId, rawRecord);
    }

    /**
     * A correction group mirroring the LedgerCorrectionService output shape:
     * one historical PROVIDER_CHARGE entry per source charge, the group
     * targeting the first one, and a CORRECTION posting whose REVERSAL
     * entries preserve each corrected entry's direct source lineage.
     */
    private long insertCorrectionGroupWithEntries(long[] sourceCharges) {
        var targetEntryId = 0L;
        var targetPostingId = 0L;
        for (var i = 0; i < sourceCharges.length; i++) {
            jdbc.update("""
                    INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,
                      allocation_decision_id,billing_period_id,status,posting_actor_type,
                      posted_by_member_id,posted_at,created_at)
                    VALUES (?,?,'PROVIDER_CHARGE',?,NULL,?,'POSTED','MEMBER',?,
                      UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                    """, orgId, "CHARGE:" + sourceCharges[i] + ":HIST", sourceCharges[i],
                    periodId, actorMemberId);
            var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
            jdbc.update("""
                    INSERT INTO ledger_entry(
                        org_id,posting_id,entry_index,entry_type,amount,currency,project_id,
                        source_charge_fact_id,created_at)
                    VALUES (?,?,0,'COST','1.00000000','USD',?,?,UTC_TIMESTAMP(6))
                    """, orgId, postingId, projectId, sourceCharges[i]);
            if (i == 0) {
                targetEntryId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                targetPostingId = postingId;
            }
        }
        jdbc.update("""
                INSERT INTO correction_group(org_id,correction_key,reason_code,reason_text,
                  target_entry_id,target_posting_id,status,created_by_member_id,created_at)
                VALUES (?,?,'REVIEWED','test correction',?,?,'POSTED',?,
                  UTC_TIMESTAMP(6))
                """, orgId, "CORRECTION_COMMAND:" + UUID.randomUUID(), targetEntryId,
                targetPostingId, actorMemberId);
        var groupId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO ledger_posting(org_id,posting_key,source_type,source_id,
                  allocation_decision_id,billing_period_id,status,posting_actor_type,
                  posted_by_member_id,posted_at,created_at)
                VALUES (?,?,'CORRECTION',?,NULL,?,'POSTED','MEMBER',?,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "CORRECTION:" + groupId, targetEntryId, periodId, actorMemberId);
        var postingId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        for (var i = 0; i < sourceCharges.length; i++) {
            jdbc.update("""
                    INSERT INTO ledger_entry(
                        org_id,posting_id,entry_index,entry_type,amount,currency,project_id,
                        source_charge_fact_id,correction_group_id,created_at)
                    VALUES (?,?,?,'REVERSAL','-1.00000000','USD',?,?,?,UTC_TIMESTAMP(6))
                    """, orgId, postingId, i, projectId, sourceCharges[i], groupId);
        }
        return groupId;
    }
}
