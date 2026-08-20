package com.aicostops.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.ledger.application.LedgerIntegrityPort;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class LedgerCorrectionIntegrityIntegrationTest extends MySqlContainerSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired LedgerIntegrityPort integrity;

    private long orgId;
    private long memberId;
    private long projectId;
    private long originalPeriodId;
    private long correctionPeriodId;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        var suffix = "m6-correction-integrity-" + System.nanoTime();

        jdbc.update("""
                INSERT INTO organization(name,slug,status,created_at,updated_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix, suffix);
        orgId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix + "@example.com", "Integrity User");
        var userId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        memberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Integrity Project','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "integrity-" + suffix);
        projectId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,'2026-01-01 00:00:00.000000','2026-02-01 00:00:00.000000',
                        'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        originalPeriodId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,'2026-02-01 00:00:00.000000','2026-03-01 00:00:00.000000',
                        'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        correctionPeriodId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    void canonicalM5CorrectionPassesAndGroupIdSourceSemanticsFail() {
        var originalPostingId = insertPosting(
                "ORIGINAL:FOR:CORRECTION", "PROVIDER_CHARGE", 9001L, originalPeriodId);

        // Keep the target entry id distinct from the first correction_group id,
        // so this test detects the historical source_id=groupId join bug.
        insertEntry(originalPostingId, 0, "COST", "1.00000000", null, null);
        var targetEntryId = insertEntry(originalPostingId, 1, "COST", "10.00000000", null, null);

        jdbc.update("""
                INSERT INTO correction_group(
                    org_id,correction_key,reason_code,reason_text,target_entry_id,target_posting_id,
                    status,created_by_member_id,created_at)
                VALUES (?,?,'TEST',NULL,?,?,'POSTED',?,UTC_TIMESTAMP(6))
                """, orgId, "CORRECTION:TEST:" + targetEntryId,
                targetEntryId, originalPostingId, memberId);
        var correctionGroupId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        assertThat(targetEntryId).isNotEqualTo(correctionGroupId);

        var correctionPostingId = insertPosting(
                "CORRECTION:" + correctionGroupId, "CORRECTION",
                targetEntryId, correctionPeriodId);
        insertEntry(correctionPostingId, 0, "REVERSAL", "-10.00000000",
                correctionGroupId, targetEntryId);

        var healthy = integrity.inspect(orgId, correctionPeriodId);
        assertThat(healthy.total()).isZero();
        assertThat(integrity.sampleProblemPostingIds(orgId, correctionPeriodId, 20)).isEmpty();

        // The old, incorrect M6 interpretation used correction_group.id as the
        // CORRECTION source_id. Corrupt the row into exactly that shape and prove
        // the integrity scan now rejects it.
        jdbc.update("UPDATE ledger_posting SET source_id=? WHERE id=?",
                correctionGroupId, correctionPostingId);

        var corrupted = integrity.inspect(orgId, correctionPeriodId);
        assertThat(corrupted.correctionMismatches()).isEqualTo(1);
        assertThat(integrity.sampleProblemPostingIds(orgId, correctionPeriodId, 20))
                .contains(correctionPostingId);
    }

    private long insertPosting(String postingKey, String sourceType, long sourceId, long periodId) {
        jdbc.update("""
                INSERT INTO ledger_posting(
                    org_id,posting_key,source_type,source_id,allocation_decision_id,billing_period_id,
                    status,posted_by_member_id,posted_at,created_at)
                VALUES (?,?,?,?,NULL,?,'POSTED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, postingKey, sourceType, sourceId, periodId, memberId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertEntry(long postingId, int entryIndex, String entryType, String amount,
            Long correctionGroupId, Long reversesEntryId) {
        jdbc.update("""
                INSERT INTO ledger_entry(
                    org_id,posting_id,entry_index,entry_type,amount,currency,
                    project_id,cost_center_id,team_id,budget_id,
                    source_charge_fact_id,source_expense_claim_id,allocation_line_id,
                    correction_group_id,reverses_entry_id,created_at)
                VALUES (?,?,?,?,?,'CNY',?,NULL,NULL,NULL,NULL,NULL,NULL,?,?,UTC_TIMESTAMP(6))
                """, orgId, postingId, entryIndex, entryType, amount, projectId,
                correctionGroupId, reversesEntryId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
