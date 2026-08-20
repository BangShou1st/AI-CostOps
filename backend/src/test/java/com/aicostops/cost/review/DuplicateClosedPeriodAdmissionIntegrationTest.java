package com.aicostops.cost.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.cost.review.application.DuplicateReviewCommandService;
import com.aicostops.shared.security.AuthenticatedUser;
import com.aicostops.shared.web.DomainException;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@Tag("integration")
class DuplicateClosedPeriodAdmissionIntegrationTest extends AuthenticationContainersSupport {

    private static final String JAN_1 = "2026-01-01 00:00:00.000000";
    private static final String FEB_1 = "2026-02-01 00:00:00.000000";

    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired DuplicateReviewCommandService commands;

    private long orgId;
    private long actorUserId;
    private long actorMemberId;
    private long rawRecordId;
    private long periodId;
    private long charge1;
    private long charge2;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRole();

        var suffix = "dup-close-" + System.nanoTime();
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "Duplicate Close", suffix);
        orgId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, suffix + "@example.com", "Duplicate Reviewer");
        actorUserId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, actorUserId);
        actorMemberId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbc.update("INSERT INTO `role`(code,name) VALUES ('DUP_CLOSE_WORKER','DUP_CLOSE_WORKER')");
        jdbc.update("""
                INSERT INTO role_permission(role_id,permission_id)
                SELECT r.id,p.id FROM `role` r JOIN permission p
                WHERE r.code='DUP_CLOSE_WORKER' AND p.code='DUPLICATE_REVIEW'
                """);
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,r.id,'ORG',?,NULL,UTC_TIMESTAMP(6)
                FROM `role` r WHERE r.code='DUP_CLOSE_WORKER'
                """, actorMemberId, orgId);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        var accountId = insertProviderAccount();
        rawRecordId = insertConfirmedRawRecord(accountId, suffix);
        charge1 = insertCharge(0, "10.00000000");
        charge2 = insertCharge(1, "10.00000000");

        jdbc.update("""
                INSERT INTO billing_period(
                    org_id,period_start,period_end,status,close_generation,version,created_at,updated_at)
                VALUES (?,?,?,'OPEN',0,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, JAN_1, FEB_1);
        periodId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRole();
    }

    @Test
    void scanCannotCreateCandidateInsideClosedPeriod() {
        jdbc.update("UPDATE billing_period SET status='CLOSED',closed_at=UTC_TIMESTAMP(6) WHERE id=?", periodId);

        assertThatThrownBy(() -> commands.scan(user()))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.code().name()).isEqualTo("PERIOD_NOT_OPEN");
                });

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM duplicate_candidate WHERE org_id=?",
                Integer.class, orgId)).isZero();
        assertThat(reviewStatus(charge1)).isEqualTo("CLEAN");
        assertThat(reviewStatus(charge2)).isEqualTo("CLEAN");
    }

    @Test
    void excludeCannotRewriteExternalTruthAfterPeriodClosed() {
        commands.scan(user());
        var candidateId = jdbc.queryForObject("""
                SELECT id FROM duplicate_candidate
                WHERE org_id=? AND charge_fact_id=? AND matched_charge_id=?
                """, Long.class, orgId, charge1, charge2);
        jdbc.update("UPDATE billing_period SET status='CLOSED',closed_at=UTC_TIMESTAMP(6) WHERE id=?", periodId);

        assertThatThrownBy(() -> commands.exclude(user(), candidateId, "closed-exclude", charge2))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(409);
                    assertThat(error.code().name()).isEqualTo("PERIOD_NOT_OPEN");
                });

        assertThat(jdbc.queryForObject(
                "SELECT status FROM duplicate_candidate WHERE id=?",
                String.class, candidateId)).isEqualTo("OPEN");
        assertThat(reviewStatus(charge2)).isEqualTo("SUSPECTED_DUPLICATE");
        assertThat(jdbc.queryForObject(
                "SELECT duplicate_of_charge_id FROM charge_fact WHERE id=?",
                Long.class, charge2)).isNull();
    }

    private AuthenticatedUser user() {
        return new AuthenticatedUser(actorUserId, 7);
    }

    private String reviewStatus(long chargeId) {
        return jdbc.queryForObject("SELECT review_status FROM charge_fact WHERE id=?",
                String.class, chargeId);
    }

    private long insertProviderAccount() {
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,'GLM','Duplicate Close Account',NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertConfirmedRawRecord(long accountId, String suffix) {
        var sha256 = (suffix.replace("-", "") + "0123456789abcdef").repeat(4).substring(0, 64);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, sha256, "org/" + orgId + "/evidence/" + sha256,
                "usage.csv", "text/csv", 1L, actorMemberId);
        var evidenceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, evidenceId, accountId, "GLM", "FILE_EXPORT", "test-parser-v1", actorMemberId);
        var batchId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'SUCCEEDED','INITIAL',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        var attemptId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update("UPDATE import_batch SET status='CONFIRMED',confirmed_attempt_id=? WHERE id=?",
                attemptId, batchId);
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,?,NULL,JSON_OBJECT(),NULL,?,?,'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId, "dup-close:" + suffix, JAN_1, FEB_1);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertCharge(int factIndex, String amount) {
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,?,'GLM','USAGE',?,'CNY',?,?,'CLEAN',UTC_TIMESTAMP(6))
                """, orgId, rawRecordId, factIndex, amount, JAN_1, FEB_1);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void deleteCustomRole() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code='DUP_CLOSE_WORKER'
                """);
        jdbc.update("DELETE FROM `role` WHERE code='DUP_CLOSE_WORKER'");
    }
}
