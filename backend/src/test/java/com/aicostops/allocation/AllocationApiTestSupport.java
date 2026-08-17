package com.aicostops.allocation;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared fixtures for the allocation workflow tests: an actor with the full
 * allocation permission set, a confirmed-import charge chain, and ACTIVE
 * targets. Mirrors the duplicate-review test fixture style.
 */
public abstract class AllocationApiTestSupport extends AuthenticationContainersSupport {

    protected static final String JAN_1 = "2026-01-01 00:00:00.000000";
    protected static final String FEB_1 = "2026-02-01 00:00:00.000000";
    protected static final String MAR_1 = "2026-03-01 00:00:00.000000";

    protected static final List<String> WORKER_PERMISSIONS = List.of(
            "COST_READ", "DUPLICATE_REVIEW", "ALLOCATION_READ",
            "ALLOCATION_EDIT", "ALLOCATION_CONFIRM", "ALLOCATION_RULE_MANAGE");

    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected JwtTokenService tokens;

    protected long fixtureCounter;

    protected long orgId;
    protected long foreignOrgId;
    protected long actorUserId;
    protected long actorMemberId;
    protected long accountId;
    protected long rawRecordId;
    protected long projectId;
    protected long costCenterId;
    protected long teamId;

    @BeforeEach
    void setUpBase() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        var suffix = ++fixtureCounter + "-" + System.nanoTime();
        orgId = insertOrganization("Alloc Org", "alloc-" + suffix);
        foreignOrgId = insertOrganization("Alloc Foreign", "alloc-foreign-" + suffix);
        actorUserId = insertUser("alloc-" + suffix + "@example.com");
        actorMemberId = insertMember(orgId, actorUserId);
        createPermissionRole("ALLOC_WORKER", WORKER_PERMISSIONS);
        assign("ALLOC_WORKER", "ORG", orgId);
        accountId = insertProviderAccount(orgId, "GLM");
        rawRecordId = insertConfirmedRawRecord(orgId, actorMemberId, accountId, suffix);
        projectId = insertTarget("project", orgId, "alloc-p-" + suffix);
        costCenterId = insertTarget("cost_center", orgId, "alloc-c-" + suffix);
        teamId = insertTarget("team", orgId, "alloc-t-" + suffix);
    }

    @AfterEach
    void tearDownBase() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    // -- authentication --------------------------------------------------------

    protected String bearer() {
        return "Bearer " + tokens.issue(actorUserId, 7).token();
    }

    // -- charge fixtures -------------------------------------------------------

    protected long insertCharge(String amount) {
        return insertCharge(orgId, rawRecordId, amount, "CNY", "CLEAN", JAN_1, FEB_1);
    }

    protected long insertCharge(long org, long raw, String amount, String currency,
            String reviewStatus, String periodStart, String periodEnd) {
        var nextIndex = jdbc.queryForObject(
                "SELECT COALESCE(MAX(fact_index),-1)+1 FROM charge_fact WHERE raw_record_id=?",
                Integer.class, raw);
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,?,'GLM','USAGE',?,'CNY',?,?,?,UTC_TIMESTAMP(6))
                """, org, raw, nextIndex, amount, periodStart, periodEnd, reviewStatus);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM charge_fact WHERE org_id=? AND raw_record_id=?",
                Long.class, org, raw);
    }

    /** Charge with periodStart NULL: rules have no effective time to evaluate. */
    protected long insertChargeWithoutPeriod(long org, long raw, String amount) {
        var nextIndex = jdbc.queryForObject(
                "SELECT COALESCE(MAX(fact_index),-1)+1 FROM charge_fact WHERE raw_record_id=?",
                Integer.class, raw);
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,review_status,created_at)
                VALUES (?,?,?,'GLM','USAGE',?,'CNY',NULL,NULL,'CLEAN',UTC_TIMESTAMP(6))
                """, org, raw, nextIndex, amount);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM charge_fact WHERE org_id=? AND raw_record_id=?",
                Long.class, org, raw);
    }

    protected long insertConfirmedRawRecord(long org, long memberId, long account, String suffix) {
        return insertImportChain(org, memberId, account, suffix, true);
    }

    protected long insertUnconfirmedRawRecord(long org, long memberId, long account, String suffix) {
        return insertImportChain(org, memberId, account, suffix, false);
    }

    /**
     * Builds evidence -> provider_account -> import_batch -> import_attempt ->
     * raw_provider_record. When {@code confirmed} the batch is CONFIRMED and its
     * confirmed_attempt_id points at the producing attempt.
     */
    private long insertImportChain(long org, long memberId, long account, String suffix,
            boolean confirmed) {
        var sha256 = (suffix.replace("-", "") + "0123456789abcdef").repeat(4).substring(0, 64);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, sha256, "org/" + org + "/evidence/" + sha256, "usage.csv",
                "text/csv", 1L, memberId);
        var evidenceId = jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, org, sha256);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, evidenceId, account, "GLM", "FILE_EXPORT", "test-parser-v1", memberId);
        var batchId = jdbc.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?",
                Long.class, evidenceId);
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'SUCCEEDED','INITIAL',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        var attemptId = jdbc.queryForObject(
                "SELECT id FROM import_attempt WHERE import_batch_id=? AND attempt_no=1",
                Long.class, batchId);
        if (confirmed) {
            jdbc.update("UPDATE import_batch SET status='CONFIRMED', confirmed_attempt_id=? WHERE id=?",
                    attemptId, batchId);
        }
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,?,NULL,JSON_OBJECT(),NULL,?,?,'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId, "alloc:" + suffix, JAN_1, FEB_1);
        return jdbc.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attemptId);
    }

    /**
     * Confirmed batch whose confirmed attempt is NOT the one that produced the
     * raw record: the charge lineage must not count as eligible.
     */
    protected long insertWrongLineageRawRecord(long org, long memberId, long account, String suffix) {
        var sha256 = (suffix.replace("-", "") + "0123456789abcdef").repeat(4).substring(0, 64);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, sha256, "org/" + org + "/evidence/" + sha256, "usage.csv",
                "text/csv", 1L, memberId);
        var evidenceId = jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, org, sha256);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, evidenceId, account, "GLM", "FILE_EXPORT", "test-parser-v1", memberId);
        var batchId = jdbc.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?",
                Long.class, evidenceId);
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'SUCCEEDED','INITIAL',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId);
        var confirmedAttempt = jdbc.queryForObject(
                "SELECT id FROM import_attempt WHERE import_batch_id=? AND attempt_no=1",
                Long.class, batchId);
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,2,'SUCCEEDED','MANUAL_RETRY',?,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batchId, confirmedAttempt);
        var producingAttempt = jdbc.queryForObject(
                "SELECT id FROM import_attempt WHERE import_batch_id=? AND attempt_no=2",
                Long.class, batchId);
        jdbc.update("UPDATE import_batch SET status='CONFIRMED', confirmed_attempt_id=? WHERE id=?",
                confirmedAttempt, batchId);
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,?,NULL,JSON_OBJECT(),NULL,?,?,'NORMALIZED',UTC_TIMESTAMP(6))
                """, producingAttempt, "alloc-wrong:" + suffix, JAN_1, FEB_1);
        return jdbc.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, producingAttempt);
    }

    // -- target fixtures -------------------------------------------------------

    protected long insertTarget(String table, long org, String code) {
        jdbc.update("""
                INSERT INTO %s(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,'Target','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """.formatted(table), org, code);
        return jdbc.queryForObject(
                "SELECT id FROM " + table + " WHERE org_id=? AND code=?", Long.class, org, code);
    }

    protected void deactivateTarget(String table, long org, long id) {
        jdbc.update("UPDATE " + table + " SET status='ARCHIVED' WHERE org_id=? AND id=?", org, id);
    }

    // -- decision fixtures -----------------------------------------------------

    /** Inserts a DRAFT RULE decision with one full-amount line to its project. */
    protected long insertRuleDraft(long org, long chargeId, long ruleId, long project,
            String amount, String currency) {
        jdbc.update("""
                INSERT INTO allocation_decision(
                    org_id,subject_type,charge_fact_id,expense_claim_id,decision_source,
                    allocation_rule_id,status,created_by_member_id,created_at)
                VALUES (?,'CHARGE_FACT',?,NULL,'RULE',?,'DRAFT',NULL,UTC_TIMESTAMP(6))
                """, org, chargeId, ruleId);
        var decisionId = jdbc.queryForObject(
                "SELECT MAX(id) FROM allocation_decision WHERE org_id=? AND charge_fact_id=?",
                Long.class, org, chargeId);
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?,?,0,?,?,?,NULL,NULL,UTC_TIMESTAMP(6))
                """, org, decisionId, amount, currency, project);
        return decisionId;
    }

    protected long insertRule(long org, long memberId, long project, long account,
            String matchType, String matchValue) {
        jdbc.update("""
                INSERT INTO allocation_rule(
                    org_id,rule_key,version,name,provider_code,provider_account_id,
                    match_hint_type,match_value,priority,
                    target_project_id,target_cost_center_id,target_team_id,
                    effective_from,effective_to,status,created_by_member_id,created_at)
                VALUES (?,?,1,'Fixture rule','GLM',?,
                    ?,?,1,
                    ?,NULL,NULL,
                    '2020-01-01 00:00:00.000000',NULL,'ACTIVE',?,UTC_TIMESTAMP(6))
                """, org, "fixture-key-" + System.nanoTime(), account, matchType, matchValue,
                project, memberId);
        return jdbc.queryForObject("SELECT MAX(id) FROM allocation_rule WHERE org_id=?",
                Long.class, org);
    }

    /** Inserts a line directly (used to corrupt a draft for negative tests). */
    protected void insertLineDirectly(long org, long decisionId, int lineIndex, String amount,
            String currency, Long project, Long costCenter, Long team) {
        jdbc.update("""
                INSERT INTO allocation_line(
                    org_id,decision_id,line_index,allocated_amount,currency,
                    project_id,cost_center_id,team_id,created_at)
                VALUES (?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, org, decisionId, lineIndex, amount, currency, project, costCenter, team);
    }

    // -- rule fixtures ---------------------------------------------------------

    /** Full-control rule version insert for proposal tests. */
    protected long insertRuleFull(long org, long memberId, String ruleKey, int version,
            String providerCode, Long providerAccountId, String matchType, String matchValue,
            int priority, Long targetProjectId, Long targetCostCenterId, Long targetTeamId,
            String effectiveFrom, String effectiveTo, String status) {
        jdbc.update("""
                INSERT INTO allocation_rule(
                    org_id,rule_key,version,name,provider_code,provider_account_id,
                    match_hint_type,match_value,priority,
                    target_project_id,target_cost_center_id,target_team_id,
                    effective_from,effective_to,status,created_by_member_id,created_at)
                VALUES (?,?,?,?,?,?,
                    ?,?,?,
                    ?,?,?,
                    ?,?,?,?,UTC_TIMESTAMP(6))
                """, org, ruleKey, version, "Rule " + ruleKey + " v" + version, providerCode,
                providerAccountId, matchType, matchValue, priority,
                targetProjectId, targetCostCenterId, targetTeamId,
                effectiveFrom, effectiveTo, status, memberId);
        return jdbc.queryForObject(
                "SELECT id FROM allocation_rule WHERE org_id=? AND rule_key=? AND version=?",
                Long.class, org, ruleKey, version);
    }

    /** Attribution hint bound to the charge's raw record + fact index. */
    protected void insertHintForCharge(long org, long chargeId, String hintType,
            String providerValue) {
        jdbc.update("""
                INSERT INTO attribution_hint(
                    org_id,raw_record_id,fact_index,hint_type,candidate_scope_type,candidate_scope_id,
                    provider_value,confidence,metadata_json,created_at)
                SELECT org_id,raw_record_id,fact_index,?,NULL,NULL,
                       ?,NULL,NULL,UTC_TIMESTAMP(6)
                FROM charge_fact WHERE id=?
                """, hintType, providerValue, chargeId);
    }

    // -- assertions helpers ----------------------------------------------------

    protected String decisionStatus(long decisionId) {
        return jdbc.queryForObject(
                "SELECT status FROM allocation_decision WHERE id=?", String.class, decisionId);
    }

    protected Long currentDecisionPointer(long chargeId) {
        return jdbc.queryForObject(
                "SELECT current_allocation_decision_id FROM charge_fact WHERE id=?",
                Long.class, chargeId);
    }

    protected int lineCount(long decisionId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM allocation_line WHERE decision_id=?",
                Integer.class, decisionId);
    }

    protected int auditCount(String eventType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE event_type=?", Integer.class, eventType);
    }

    protected int idempotencyCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class);
    }

    protected String lineSum(long decisionId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(allocated_amount),0) FROM allocation_line WHERE decision_id=?",
                String.class, decisionId);
    }

    // -- identity fixtures -----------------------------------------------------

    protected long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    protected long insertUser(String email) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,?,'ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, "Alloc Reviewer");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?",
                Long.class, email);
    }

    protected long insertMember(long org, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, org, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?",
                Long.class, org, userId);
    }

    protected long insertProviderAccount(long org, String providerCode) {
        return insertProviderAccount(org, providerCode, "Alloc Account");
    }

    protected long insertProviderAccount(long org, String providerCode, String displayName) {
        jdbc.update("""
                INSERT IGNORE INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, providerCode, displayName);
        return jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code=? AND display_name=?",
                Long.class, org, providerCode, displayName);
    }

    protected void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code IN ('ALLOC_WORKER','ALLOC_READER','ALLOC_EDITOR')
                """);
        jdbc.update("DELETE FROM `role` WHERE code IN ('ALLOC_WORKER','ALLOC_READER','ALLOC_EDITOR')");
    }

    protected void createPermissionRole(String roleCode, List<String> permissions) {
        jdbc.update("INSERT INTO `role`(code,name) VALUES (?,?)", roleCode, roleCode);
        for (var permission : permissions) {
            jdbc.update("""
                    INSERT INTO role_permission(role_id,permission_id)
                    SELECT r.id,p.id FROM `role` r JOIN permission p
                    WHERE r.code=? AND p.code=?
                    """, roleCode, permission);
        }
    }

    protected void assign(String roleCode, String scopeType, long scopeId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, actorMemberId, scopeType, scopeId, roleCode);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    protected void revokeAllAssignments() {
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }
}
