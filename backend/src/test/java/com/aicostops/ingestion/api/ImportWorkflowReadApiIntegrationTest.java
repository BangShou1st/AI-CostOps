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
        "aicostops.auth.jwt-signing-secret=import-workflow-read-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class ImportWorkflowReadApiIntegrationTest extends AuthenticationContainersSupport {

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
    private long evidenceId;
    private long foreignEvidenceId;
    private long accountId;
    private long batchId;
    private long foreignBatchId;
    private long attempt1Id;
    private long attempt2Id;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        organizationId = insertOrganization("Import Read Api", "import-read-api");
        foreignOrganizationId = insertOrganization("Foreign", "import-read-api-foreign");
        actorUserId = insertUser("import-reader@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        createPermissionRole("IMPORT_READER", List.of("IMPORT_READ"));
        createPermissionRole("EVIDENCE_ONLY", List.of("EVIDENCE_READ"));
        assign("IMPORT_READER", "ORG", organizationId);

        evidenceId = insertEvidence(organizationId, "1".repeat(64));
        foreignEvidenceId = insertEvidence(foreignOrganizationId, "2".repeat(64));
        accountId = insertProviderAccount(organizationId, "TEST_PROVIDER", "Primary");
        batchId = insertBatch(organizationId, evidenceId, accountId, "FAILED", "2026-08-03 09:00:00");
        foreignBatchId = insertBatch(foreignOrganizationId, foreignEvidenceId, accountId, "PENDING",
                "2026-08-05 09:00:00");
        attempt1Id = insertAttempt(batchId, 1, "FAILED", "INITIAL", null, "2026-08-03 09:00:00");
        attempt2Id = insertAttempt(batchId, 2, "QUEUED", "MANUAL_RETRY", attempt1Id, "2026-08-03 10:00:00");
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void importsListExposesStringIdsPaginationAndNoLeaseOrObjectInternals() throws Exception {
        mockMvc.perform(get("/api/v1/imports?page=0&size=10").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.items[0].evidence.id").isString())
                .andExpect(jsonPath("$.items[0].providerAccount.id").isString())
                .andExpect(jsonPath("$.items[0].status").value("FAILED"))
                .andExpect(jsonPath("$.items[0].retryable").value(true))
                .andExpect(jsonPath("$.items[0].cancelable").value(false))
                .andExpect(jsonPath("$.items[0].latestAttempt.id").isString())
                .andExpect(jsonPath("$.items[0].latestAttempt.attemptNo").value(2))
                .andExpect(jsonPath("$.items[0].objectKey").doesNotExist())
                .andExpect(jsonPath("$.items[0].evidence.objectKey").doesNotExist())
                .andExpect(jsonPath("$.items[0].latestAttempt.leaseOwner").doesNotExist())
                .andExpect(jsonPath("$.items[0].latestAttempt.leaseUntil").doesNotExist());
    }

    @Test
    void importsListSupportsStatusAndProviderAccountFilters() throws Exception {
        mockMvc.perform(get("/api/v1/imports?status=FAILED").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/imports?status=PARSED").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/imports?providerAccountId=" + accountId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/imports?providerAccountId=999999").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/imports?status=INVENTED").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void importDetailAndAttemptsExposeReviewFieldsOnly() throws Exception {
        mockMvc.perform(get("/api/v1/imports/{importId}", batchId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.evidence.originalFilename").value("evidence-" + evidenceId + ".csv"))
                .andExpect(jsonPath("$.providerAccount.displayName").value("Primary"))
                .andExpect(jsonPath("$.sourceType").value("FILE_EXPORT"))
                .andExpect(jsonPath("$.parserVersion").value("test-parser-v1"))
                .andExpect(jsonPath("$.createdByMemberId").isString());

        mockMvc.perform(get("/api/v1/imports/{importId}/attempts?page=0&size=10", batchId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].attemptNo").value(2))
                .andExpect(jsonPath("$.items[0].triggerType").value("MANUAL_RETRY"))
                .andExpect(jsonPath("$.items[0].predecessorAttemptId").isString())
                .andExpect(jsonPath("$.items[1].attemptNo").value(1))
                .andExpect(jsonPath("$.items[1].predecessorAttemptId").doesNotExist())
                .andExpect(jsonPath("$.items[0].leaseOwner").doesNotExist())
                .andExpect(jsonPath("$.items[0].leaseUntil").doesNotExist());
    }

    @Test
    void issuesListIsPaginatedFilteredAndNeverUnmasksValues() throws Exception {
        insertIssue(attempt1Id, null, "WARN", "FIELD_EMPTY", "row=1", "model", "model is empty", "sk-x", 1);
        insertIssue(attempt1Id, null, "ERROR", "UNKNOWN_FIELD", "row=2", "cost", "cost unknown", "sk-y", 2);

        mockMvc.perform(get("/api/v1/imports/{importId}/attempts/{attemptId}/issues?page=0&size=1",
                        batchId, attempt1Id).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.items[0].severity").value("WARN"))
                .andExpect(jsonPath("$.items[0].rawValueMasked").value("sk-x"));

        mockMvc.perform(get("/api/v1/imports/{importId}/attempts/{attemptId}/issues?severity=ERROR",
                        batchId, attempt1Id).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].issueCode").value("UNKNOWN_FIELD"));

        mockMvc.perform(get("/api/v1/imports/{importId}/attempts/{attemptId}/issues?issueCode=FIELD_EMPTY",
                        batchId, attempt1Id).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").isString());
    }

    @Test
    void rawRecordListReturnsKeySummariesWithoutPayloadValues() throws Exception {
        insertRawRecord(attempt2Id, 0, "cost.csv:row=1", "row-key-1",
                "{\"model\":\"x\",\"future_note\":\"safe\",\"api_key\":\"[REDACTED]\"}",
                "{\"model\":\"x\"}", "NORMALIZED");
        insertRawRecord(attempt2Id, 1, "cost.csv:row=2", "row-key-2",
                "{\"model\":\"y\"}", null, "WARN");

        var body = mockMvc.perform(get("/api/v1/imports/{importId}/attempts/{attemptId}/raw-records",
                        batchId, attempt2Id).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].recordIndex").value(0))
                .andExpect(jsonPath("$.items[0].rawPayloadKeys.keyCount").value(3))
                .andExpect(jsonPath("$.items[0].rawPayloadKeys.keysTruncated").value(false))
                .andExpect(jsonPath("$.items[0].normalizedPayloadKeys.keyCount").value(1))
                .andExpect(jsonPath("$.items[1].rawPayloadKeys.keyCount").value(1))
                .andExpect(jsonPath("$.items[0].rawPayload").doesNotExist())
                .andExpect(jsonPath("$.items[0].normalizedPayload").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"x\"", "\"safe\"");
    }

    @Test
    void rawRecordDetailLazyLoadsRedactedPayload() throws Exception {
        insertRawRecord(attempt2Id, 0, "cost.csv:row=1", "row-key-1",
                "{\"model\":\"x\",\"future_note\":\"safe\",\"api_key\":\"sk-SECRET-SENTINEL\"}",
                "{\"model\":\"x\"}", "NORMALIZED");
        var recordId = jdbc.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attempt2Id);

        var body = mockMvc.perform(get("/api/v1/imports/{importId}/attempts/{attemptId}/raw-records/{recordId}",
                        batchId, attempt2Id, recordId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.rawPayload.model").value("x"))
                .andExpect(jsonPath("$.rawPayload.api_key").value("[REDACTED]"))
                .andExpect(jsonPath("$.normalizedPayload.model").value("x"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("sk-SECRET-SENTINEL");
    }

    @Test
    void evidenceImportsChildEndpointRequiresImportReadAndIsOrgScoped() throws Exception {
        mockMvc.perform(get("/api/v1/evidence/{evidenceId}/imports", evidenceId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/evidence/{evidenceId}/imports", foreignEvidenceId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void evidenceImportsPageAndCountShareTheSameTenantConsistentDataset() throws Exception {
        // Anomalous lineage: current-org Evidence + current-org ImportBatch, but
        // the ProviderAccount points into a foreign org (FKs only check id
        // existence). The org-consistent joins must exclude it from BOTH items
        // and totalElements.
        var foreignAccountId = insertProviderAccount(foreignOrganizationId, "TEST_PROVIDER", "Foreign Account");
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'PENDING',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, evidenceId, foreignAccountId, "TEST_PROVIDER", "FILE_EXPORT",
                "test-parser-v1", actorMemberId);
        var anomalousBatchId = jdbc.queryForObject("""
                SELECT id FROM import_batch WHERE org_id=? AND evidence_id=? AND provider_account_id=?
                """, Long.class, organizationId, evidenceId, foreignAccountId);

        var body = mockMvc.perform(get("/api/v1/evidence/{evidenceId}/imports", evidenceId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(batchId)))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(Long.toString(anomalousBatchId) + "\"");
    }

    @Test
    void missingImportReadIsForbiddenBeforeLookup() throws Exception {
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();

        for (var url : List.of(
                "/api/v1/imports",
                "/api/v1/imports/999999",
                "/api/v1/imports/999999/attempts",
                "/api/v1/imports/999999/attempts/999999/issues",
                "/api/v1/imports/999999/attempts/999999/raw-records",
                "/api/v1/imports/999999/attempts/999999/raw-records/999999",
                "/api/v1/evidence/" + evidenceId + "/imports")) {
            mockMvc.perform(get(url).header("Authorization", bearer()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
    }

    @Test
    void crossOrganizationAndParentChildMismatchesAreNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/imports/{importId}", foreignBatchId).header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/imports/{importId}/attempts", foreignBatchId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/imports/{importId}/attempts/{attemptId}/issues",
                        batchId, 999999L).header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/imports/{importId}/attempts/{attemptId}/raw-records/{recordId}",
                        batchId, attempt1Id, 999999L).header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private long insertEvidence(long orgId, String sha256) {
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,'pending.csv','text/csv',1,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, sha256, "org/" + orgId + "/evidence/" + sha256, actorMemberId);
        var evId = jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, orgId, sha256);
        jdbc.update("UPDATE evidence SET original_filename=? WHERE id=?",
                "evidence-" + evId + ".csv", evId);
        return evId;
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

    private long insertBatch(long orgId, long evId, long account, String status, String createdAt) {
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,NULL,NULL,?,?,?)
                """, orgId, evId, account, "TEST_PROVIDER", "FILE_EXPORT", "test-parser-v1", status,
                actorMemberId, createdAt, createdAt);
        return jdbc.queryForObject("SELECT id FROM import_batch WHERE evidence_id=?", Long.class, evId);
    }

    private long insertAttempt(long batch, int attemptNo, String status, String trigger, Long predecessor,
            String createdAt) {
        jdbc.update("""
                INSERT INTO import_attempt(
                    import_batch_id,attempt_no,status,trigger_type,predecessor_attempt_id,
                    available_at,lease_owner,lease_until,lease_version,parser_version,
                    detected_provider_code,schema_fingerprint,started_at,finished_at,error_code,error_summary,
                    records_seen,records_valid,warning_count,error_count,created_at)
                VALUES (?,?,?,?,?,?,NULL,NULL,0,'test-parser-v1',
                    NULL,NULL,NULL,NULL,'ERR','summary',0,0,0,0,?)
                """, batch, attemptNo, status, trigger, predecessor, createdAt, createdAt);
        return jdbc.queryForObject("""
                SELECT id FROM import_attempt WHERE import_batch_id=? AND attempt_no=?
                """, Long.class, batch, attemptNo);
    }

    private void insertRawRecord(long attempt, long recordIndex, String locator, String recordKey,
            String rawJson, String normalizedJson, String normalizeStatus) {
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,?,?,?,CAST(? AS JSON),CAST(? AS JSON),NULL,NULL,?,UTC_TIMESTAMP(6))
                """, attempt, recordIndex, locator, recordKey, rawJson, normalizedJson, normalizeStatus);
    }

    private void insertIssue(long attempt, Long recordId, String severity, String code, String locator,
            String fieldName, String message, String rawValueMasked, long id) {
        jdbc.update("""
                INSERT INTO import_issue(
                    id,import_attempt_id,raw_provider_record_id,severity,issue_code,record_locator,
                    field_name,message,raw_value_masked,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, id, attempt, recordId, severity, code, locator, fieldName, message, rawValueMasked);
    }

    private void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code IN ('IMPORT_READER','EVIDENCE_ONLY')
                """);
        jdbc.update("DELETE FROM `role` WHERE code IN ('IMPORT_READER','EVIDENCE_ONLY')");
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
                VALUES (?,'Import Reader','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
