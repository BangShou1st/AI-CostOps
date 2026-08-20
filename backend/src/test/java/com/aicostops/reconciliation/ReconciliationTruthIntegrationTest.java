package com.aicostops.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.cost.application.ReconciliationExternalTruthPort;
import com.aicostops.ledger.application.ReconciliationInternalTruthPort;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class ReconciliationTruthIntegrationTest extends MySqlContainerSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired ReconciliationExternalTruthPort externalTruth;
    @Autowired ReconciliationInternalTruthPort internalTruth;

    private long orgId;
    private long memberId;
    private long providerAccountId;
    private long periodId;
    private long projectId;
    private long batchId;
    private long confirmedAttemptId;

    @BeforeEach
    void setUp() {
        M2DatabaseCleaner.clean(jdbc);
        var suffix = "truth-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,created_at,updated_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix, suffix);
        orgId = lastId();
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix + "@example.test", suffix);
        var userId = lastId();
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        memberId = lastId();
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,status,created_at,updated_at)
                VALUES (?,'OPENAI',?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix);
        providerAccountId = lastId();
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, suffix, suffix);
        projectId = lastId();
        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,'2026-08-01 00:00:00.000000','2026-09-01 00:00:00.000000',
                    'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        periodId = lastId();

        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,created_at,updated_at)
                VALUES (?,?,'m6/truth','truth.csv','text/csv',1,?,'AVAILABLE',
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "a".repeat(64), memberId);
        var evidenceId = lastId();
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,'OPENAI','COST_EXPORT','v1','READY_FOR_REVIEW',
                    '2026-08-01 00:00:00.000000','2026-09-01 00:00:00.000000',?,
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, evidenceId, providerAccountId, memberId);
        batchId = lastId();
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,available_at,lease_version,
                    parser_version,started_at,finished_at,records_seen,records_valid,warning_count,
                    error_count,created_at)
                VALUES (?,1,'SUCCEEDED','INITIAL',UTC_TIMESTAMP(6),0,'v1',UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6),1,1,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        confirmedAttemptId = lastId();
        jdbc.update("""
                UPDATE import_batch
                SET status='CONFIRMED',confirmed_attempt_id=?,updated_at=UTC_TIMESTAMP(6)
                WHERE id=?
                """, confirmedAttemptId, batchId);
    }

    @Test
    void externalTruthUsesConfirmedAttemptReviewStateAndHalfOpenPeriod() {
        var confirmedRaw = insertRaw(confirmedAttemptId, 0);
        insertCharge(confirmedRaw, 0, "10.00000000", "CLEAN", "2026-08-01 00:00:00.000000");
        insertCharge(insertRaw(confirmedAttemptId, 1), 0, "2.00000000", "SUSPECTED_DUPLICATE",
                "2026-08-15 00:00:00.000000");
        insertCharge(insertRaw(confirmedAttemptId, 2), 0, "50.00000000", "EXCLUDED_DUPLICATE",
                "2026-08-20 00:00:00.000000");
        insertCharge(insertRaw(confirmedAttemptId, 3), 0, "99.00000000", "CLEAN",
                "2026-09-01 00:00:00.000000");

        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,available_at,lease_version,
                    parser_version,started_at,finished_at,records_seen,records_valid,warning_count,
                    error_count,created_at)
                VALUES (?,2,'SUCCEEDED','MANUAL_RETRY',UTC_TIMESTAMP(6),0,'v1',UTC_TIMESTAMP(6),
                    UTC_TIMESTAMP(6),1,1,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        var nonConfirmedAttempt = lastId();
        insertCharge(insertRaw(nonConfirmedAttempt, 0), 0, "500.00000000", "CLEAN",
                "2026-08-10 00:00:00.000000");

        var rows = externalTruth.aggregateConfirmedCharges(orgId,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.providerAccountId()).isEqualTo(providerAccountId);
            assertThat(row.currency()).isEqualTo("USD");
            assertThat(row.rowCount()).isEqualTo(2);
            assertThat(row.amount()).isEqualByComparingTo("12.00000000");
        });
    }

    @Test
    void internalTruthIncludesProviderCorrectionEntriesAndExcludesNonChargeEntries() {
        var raw = insertRaw(confirmedAttemptId, 0);
        var chargeId = insertCharge(raw, 0, "10.00000000", "CLEAN",
                "2026-08-10 00:00:00.000000");

        insertLedgerEntry("CHARGE:1", "PROVIDER_CHARGE", chargeId, chargeId,
                "10.00000000", 0);
        insertLedgerEntry("CORRECTION:1", "CORRECTION", 9001, chargeId,
                "-3.00000000", 0);
        insertLedgerEntry("EXPENSE:1", "EXPENSE_CLAIM", 7001, null,
                "100.00000000", 0);

        var rows = internalTruth.aggregateProviderLedger(orgId, periodId);
        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.providerAccountId()).isEqualTo(providerAccountId);
            assertThat(row.currency()).isEqualTo("USD");
            assertThat(row.rowCount()).isEqualTo(2);
            assertThat(row.amount()).isEqualByComparingTo("7.00000000");
        });
    }

    private long insertRaw(long attemptId, long index) {
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,raw_payload,normalize_status,created_at)
                VALUES (?,?,?,JSON_OBJECT(),'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId, index, "row-" + attemptId + "-" + index);
        return lastId();
    }

    private long insertCharge(long rawId, int factIndex, String amount, String reviewStatus,
            String periodStart) {
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,?,'OPENAI','USAGE',?,'USD',?,DATE_ADD(?,INTERVAL 1 DAY),?,UTC_TIMESTAMP(6))
                """, orgId, rawId, factIndex, amount, periodStart, periodStart, reviewStatus);
        return lastId();
    }

    private void insertLedgerEntry(String postingKey, String sourceType, long sourceId,
            Long chargeId, String amount, int entryIndex) {
        jdbc.update("""
                INSERT INTO ledger_posting(
                    org_id,posting_key,source_type,source_id,allocation_decision_id,billing_period_id,
                    status,posted_by_member_id,posted_at,created_at)
                VALUES (?,?,?,?,NULL,?,'POSTED',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, postingKey, sourceType, sourceId, periodId, memberId);
        var postingId = lastId();
        jdbc.update("""
                INSERT INTO ledger_entry(
                    org_id,posting_id,entry_index,entry_type,amount,currency,project_id,
                    source_charge_fact_id,created_at)
                VALUES (?,?,?,'COST',?,'USD',?,?,UTC_TIMESTAMP(6))
                """, orgId, postingId, entryIndex, amount, projectId, chargeId);
    }

    private long lastId() {
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
