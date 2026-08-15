package com.aicostops.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=import-confirm-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class ImportWorkflowConfirmApiIntegrationTest extends AuthenticationContainersSupport {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private StringRedisTemplate redis;

    private long organizationId;
    private long foreignOrganizationId;
    private long actorUserId;
    private long actorMemberId;
    private long accountId;
    private long readyBatchId;
    private long readyAttemptId;
    private long parsedBatchId;
    private long foreignReadyBatchId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        organizationId = insertOrganization("Confirm Api", "confirm-api");
        foreignOrganizationId = insertOrganization("Foreign", "confirm-api-foreign");
        actorUserId = insertUser("confirmer-api@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        createPermissionRole("READER_ONLY", List.of("IMPORT_READ"));
        createPermissionRole("CONFIRMER", List.of("IMPORT_CONFIRM"));
        assign("CONFIRMER", "ORG", organizationId);

        accountId = insertProviderAccount(organizationId, "TEST_PROVIDER", "Primary");
        readyBatchId = insertBatch(organizationId, "READY_FOR_REVIEW");
        readyAttemptId = insertAttempt(readyBatchId, 1, "SUCCEEDED", "INITIAL", null);
        parsedBatchId = insertBatch(organizationId, "PARSED");
        insertAttempt(parsedBatchId, 1, "SUCCEEDED", "INITIAL", null);
        foreignReadyBatchId = insertBatch(foreignOrganizationId, "READY_FOR_REVIEW");
        insertAttempt(foreignReadyBatchId, 1, "SUCCEEDED", "INITIAL", null);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void confirmSucceedsWithStringIdsAndConfirmedAttemptId() throws Exception {
        mockMvc.perform(post("/api/v1/imports/{importId}/confirm", readyBatchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "idem-confirm-api-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAttemptId").isString())
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.cancelable").value(false));

        assertThat(jdbc.queryForObject("SELECT status FROM import_batch WHERE id=?",
                String.class, readyBatchId)).isEqualTo("CONFIRMED");
        assertThat(((Number) jdbc.queryForObject(
                "SELECT confirmed_attempt_id FROM import_batch WHERE id=?",
                Long.class, readyBatchId)).longValue()).isEqualTo(readyAttemptId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isEqualTo(1);
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/imports/{importId}/confirm", readyBatchId)
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM import_batch WHERE id=?", String.class, readyBatchId))
                .isEqualTo("READY_FOR_REVIEW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isZero();
    }

    @Test
    void missingPermissionIsForbiddenBeforeResourceLookup() throws Exception {
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        assign("READER_ONLY", "ORG", organizationId);

        mockMvc.perform(post("/api/v1/imports/{importId}/confirm", 999999L)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "idem-confirm-noperm"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void crossOrganizationBatchIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/imports/{importId}/confirm", foreignReadyBatchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "idem-confirm-foreign"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_idempotency", Integer.class)).isZero();
    }

    @Test
    void nonReviewReadyStatusConflicts() throws Exception {
        mockMvc.perform(post("/api/v1/imports/{importId}/confirm", parsedBatchId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "idem-confirm-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    private long batchCounter;

    private long insertBatch(long orgId, String status) {
        var sha = String.format("%064d", ++batchCounter);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,'obj','confirm.csv','text/csv',1,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, sha, actorMemberId);
        var evId = jdbc.queryForObject("SELECT id FROM evidence WHERE org_id=? AND sha256=?",
                Long.class, orgId, sha);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, evId, accountId, "TEST_PROVIDER", "FILE_EXPORT", "test-parser-v1", status,
                actorMemberId);
        return jdbc.queryForObject("""
                SELECT id FROM import_batch WHERE org_id=? AND evidence_id=?
                """, Long.class, orgId, evId);
    }

    private long insertAttempt(long batch, int attemptNo, String status, String trigger, Long predecessor) {
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,?,?,?,?,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,UTC_TIMESTAMP(6),NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, batch, attemptNo, status, trigger, predecessor);
        return jdbc.queryForObject("""
                SELECT id FROM import_attempt WHERE import_batch_id=? AND attempt_no=?
                """, Long.class, batch, attemptNo);
    }

    private long insertProviderAccount(long orgId, String providerCode, String displayName) {
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, displayName);
        return jdbc.queryForObject("""
                SELECT id FROM provider_account WHERE org_id=? AND provider_code=? AND display_name=?
                """, Long.class, orgId, providerCode, displayName);
    }

    private void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code IN ('READER_ONLY','CONFIRMER')
                """);
        jdbc.update("DELETE FROM `role` WHERE code IN ('READER_ONLY','CONFIRMER')");
    }

    private void createPermissionRole(String roleCode, List<String> permissions) {
        jdbc.update("INSERT INTO `role`(code,name) VALUES (?,?)", roleCode, roleCode);
        for (var permission : permissions) {
            jdbc.update("""
                    INSERT INTO role_permission(role_id,permission_id)
                    SELECT r.id,p.id FROM `role` r JOIN permission p
                    WHERE r.code=? AND p.code=?
                    """, roleCode, permission);
        }
    }

    private void assign(String roleCode, String scopeType, long scopeId) {
        jdbc.update("""
                INSERT INTO role_assignment(org_member_id,role_id,scope_type,scope_id,assigned_by,created_at)
                SELECT ?,id,?,?,NULL,UTC_TIMESTAMP(6) FROM `role` WHERE code=?
                """, actorMemberId, scopeType, scopeId, roleCode);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    private long insertOrganization(String name, String slug) {
        jdbc.update("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, name, slug);
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, slug);
    }

    private long insertUser(String email) {
        jdbc.update("""
                INSERT INTO app_user(email_normalized,display_name,status,security_version,created_at,updated_at)
                VALUES (?,'Confirmer','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?", Long.class, email);
    }

    private long insertMember(long orgId, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, orgId, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?", Long.class, orgId, userId);
    }

    private String bearer() {
        return "Bearer " + tokens.issue(actorUserId, 7).token();
    }
}
