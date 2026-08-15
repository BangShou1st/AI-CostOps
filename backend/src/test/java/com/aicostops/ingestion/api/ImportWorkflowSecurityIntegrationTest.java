package com.aicostops.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
        "aicostops.auth.jwt-signing-secret=import-security-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class ImportWorkflowSecurityIntegrationTest extends AuthenticationContainersSupport {

    private static final String SECRET_SENTINEL = "sk-SECRET-SENTINEL-DO-NOT-RETURN";

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
    private long batchId;
    private long foreignBatchId;
    private long attemptId;
    private long foreignAttemptId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        organizationId = insertOrganization("Security", "security");
        foreignOrganizationId = insertOrganization("Foreign", "security-foreign");
        actorUserId = insertUser("security-reviewer@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        createPermissionRole("IMPORT_READER", List.of("IMPORT_READ"));
        assign("IMPORT_READER", "ORG", organizationId);

        accountId = insertProviderAccount(organizationId, "TEST_PROVIDER", "Primary");
        batchId = insertBatch(organizationId, "FAILED");
        attemptId = insertAttempt(batchId, 1, "FAILED", "INITIAL", null);
        foreignBatchId = insertBatch(foreignOrganizationId, "PENDING");
        foreignAttemptId = insertAttempt(foreignBatchId, 1, "QUEUED", "INITIAL", null);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void everyChildEndpointReturns404ForPermittedCrossOrganizationParents() throws Exception {
        for (var url : List.of(
                "/api/v1/imports/{id}/attempts",
                "/api/v1/imports/{id}/attempts/{aid}/issues",
                "/api/v1/imports/{id}/attempts/{aid}/raw-records",
                "/api/v1/imports/{id}/attempts/{aid}/raw-records/{rid}")) {
            mockMvc.perform(get(url, foreignBatchId, foreignAttemptId, foreignAttemptId)
                            .header("Authorization", bearer()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Test
    void everyChildEndpointReturns404ForMismatchedChildIds() throws Exception {
        mockMvc.perform(get("/api/v1/imports/{id}/attempts/{aid}/issues", batchId, 999999L)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/imports/{id}/attempts/{aid}/raw-records", batchId, 999999L)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/imports/{id}/attempts/{aid}/raw-records/{rid}",
                        batchId, attemptId, 999999L).header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void missingPermissionIsForbiddenBeforeLookupOnEveryEndpoint() throws Exception {
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        // Foreign ids prove authorization runs before any resource lookup: a
        // lookup-first implementation would return 404 instead of 403 here.
        for (var url : List.of(
                "/api/v1/imports",
                "/api/v1/imports/{id}",
                "/api/v1/imports/{id}/attempts",
                "/api/v1/imports/{id}/attempts/{aid}/issues",
                "/api/v1/imports/{id}/attempts/{aid}/raw-records",
                "/api/v1/imports/{id}/attempts/{aid}/raw-records/{rid}")) {
            mockMvc.perform(get(url, foreignBatchId, foreignAttemptId, foreignAttemptId)
                            .header("Authorization", bearer()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void rawDetailNeverReturnsSecretShapedValuesEvenWhenPersisted() throws Exception {
        var rawJson = "{\"credentialId\":\"keyid_fake\",\"future_note\":\"" + SECRET_SENTINEL + "\"}";
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,'cost.csv:row=1','k',CAST(? AS JSON),NULL,NULL,NULL,'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId, rawJson);
        var recordId = jdbc.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attemptId);

        var body = mockMvc.perform(get("/api/v1/imports/{id}/attempts/{aid}/raw-records/{rid}",
                        batchId, attemptId, recordId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawPayload.credentialId").value("keyid_fake"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(SECRET_SENTINEL);
    }

    @Test
    void rawListAndDetailNeverExposeSecretShapedLocatorOrRecordKeyEvenWhenPersisted() throws Exception {
        // Directly persisted user-controlled metadata (as Group 2 adapters could
        // produce): the read boundary must redact it as defense in depth.
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,?,?,CAST(? AS JSON),NULL,NULL,NULL,'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId, SECRET_SENTINEL + ":row=1", "credentialId=keyid_fake&" + SECRET_SENTINEL,
                "{\"model\":\"x\"}");
        var recordId = jdbc.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attemptId);

        var listBody = mockMvc.perform(get("/api/v1/imports/{id}/attempts/{aid}/raw-records",
                        batchId, attemptId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recordLocator").value("[REDACTED]:row=1"))
                .andExpect(jsonPath("$.items[0].providerRecordKey").value("credentialId=keyid_fake&[REDACTED]"))
                .andReturn().getResponse().getContentAsString();
        assertThat(listBody).doesNotContain(SECRET_SENTINEL);

        var detailBody = mockMvc.perform(get("/api/v1/imports/{id}/attempts/{aid}/raw-records/{rid}",
                        batchId, attemptId, recordId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordLocator").value("[REDACTED]:row=1"))
                .andExpect(jsonPath("$.providerRecordKey").value("credentialId=keyid_fake&[REDACTED]"))
                .andReturn().getResponse().getContentAsString();
        assertThat(detailBody).doesNotContain(SECRET_SENTINEL);
    }

    @Test
    void legacySecretShapedJsonKeysNeverReachRawListOrDetail() throws Exception {
        // Bypass the persistence sanitizer on purpose: these rows stand in for
        // history persisted before key-level sanitization existed. Both the raw
        // and normalized payloads carry the sentinel inside JSON object keys.
        var rawJson = "{\"" + SECRET_SENTINEL + "\":\"anything\",\"model\":\"x\"}";
        var normalizedJson = "{\"api_key=" + SECRET_SENTINEL + "\":\"anything\",\"usage\":1}";
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,'cost.csv:row=1','k',CAST(? AS JSON),CAST(? AS JSON),NULL,NULL,'NORMALIZED',
                    UTC_TIMESTAMP(6))
                """, attemptId, rawJson, normalizedJson);
        var recordId = jdbc.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attemptId);

        var listBody = mockMvc.perform(get("/api/v1/imports/{id}/attempts/{aid}/raw-records",
                        batchId, attemptId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].rawPayloadKeys.keyCount").value(2))
                .andExpect(jsonPath("$.items[0].normalizedPayloadKeys.keyCount").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(listBody).doesNotContain(SECRET_SENTINEL);

        var detailBody = mockMvc.perform(get("/api/v1/imports/{id}/attempts/{aid}/raw-records/{rid}",
                        batchId, attemptId, recordId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawPayload.model").value("x"))
                .andExpect(jsonPath("$.normalizedPayload.usage").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(detailBody).doesNotContain(SECRET_SENTINEL);
    }

    @Test
    void crossOrganizationLineageIsNeverExposedThroughImportReads() throws Exception {
        // Anomalous row: the Batch belongs to the current org but its Evidence
        // lineage points into a foreign org (FKs only check id existence). The
        // org-consistent join must hide the Batch entirely instead of displaying
        // foreign lineage inside an authorized read.
        var sha = String.format("%064d", 900001L);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,'obj','foreign-lineage.csv','text/csv',1,?,'AVAILABLE',NULL,
                    UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, foreignOrganizationId, sha, actorMemberId);
        var foreignEvidenceId = jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, foreignOrganizationId, sha);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, foreignEvidenceId, accountId, "TEST_PROVIDER", "FILE_EXPORT",
                "test-parser-v1", actorMemberId);
        var anomalousBatchId = jdbc.queryForObject(
                "SELECT id FROM import_batch WHERE evidence_id=?", Long.class, foreignEvidenceId);
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,1,'QUEUED','INITIAL',NULL,UTC_TIMESTAMP(6),NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
                """, anomalousBatchId);

        mockMvc.perform(get("/api/v1/imports").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(batchId)));

        mockMvc.perform(get("/api/v1/imports/{importId}", anomalousBatchId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void boundedReadsReturnOnlyRequestedPageSizeWithCorrectTotals() throws Exception {
        for (var i = 1; i <= 60; i++) {
            jdbc.update("""
                    INSERT INTO import_issue(
                        import_attempt_id,raw_provider_record_id,severity,issue_code,record_locator,
                        field_name,message,raw_value_masked,created_at)
                    VALUES (?,NULL,'WARN',CONCAT('CODE_', ?),NULL,'field','message',NULL,UTC_TIMESTAMP(6))
                    """, attemptId, String.format("%03d", i));
        }

        var page1 = mockMvc.perform(get("/api/v1/imports/{id}/attempts/{aid}/issues?page=0&size=50",
                        batchId, attemptId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(60))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andReturn().getResponse().getContentAsString();
        assertThat(countOccurrences(page1, "\"id\":\"")).isEqualTo(50);

        var page2 = mockMvc.perform(get("/api/v1/imports/{id}/attempts/{aid}/issues?page=1&size=50",
                        batchId, attemptId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(countOccurrences(page2, "\"id\":\"")).isEqualTo(10);
    }

    private static int countOccurrences(String text, String needle) {
        var count = 0;
        var index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private long batchCounter;

    private long insertBatch(long orgId, String status) {
        var sha = String.format("%064d", ++batchCounter);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,'obj','pending.csv','text/csv',1,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
                    NULL,NULL,NULL,NULL,NULL,NULL,0,0,0,0,UTC_TIMESTAMP(6))
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
                WHERE r.code='IMPORT_READER'
                """);
        jdbc.update("DELETE FROM `role` WHERE code='IMPORT_READER'");
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
                VALUES (?,'Security Reviewer','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
