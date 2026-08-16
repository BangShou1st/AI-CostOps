package com.aicostops.cost.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP contract of the five duplicate-candidate operations: authentication,
 * DUPLICATE_REVIEW authorization, privacy-preserving cross-org visibility,
 * string ids/money, Idempotency-Key enforcement, and replay stability.
 */
@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=duplicate-api-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class DuplicateCandidateApiIntegrationTest extends AuthenticationContainersSupport {

    private static final String JAN_1 = "2026-01-01 00:00:00.000000";
    private static final String FEB_1 = "2026-02-01 00:00:00.000000";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private StringRedisTemplate redis;

    private long fixtureCounter;

    private long orgId;
    private long foreignOrgId;
    private long actorUserId;
    private long actorMemberId;
    private long rawRecordId;
    private long charge1;
    private long charge2;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        var suffix = ++fixtureCounter + "-" + System.nanoTime();
        orgId = insertOrganization("Api Org", "dup-api-" + suffix);
        foreignOrgId = insertOrganization("Api Foreign", "dup-api-foreign-" + suffix);
        actorUserId = insertUser("dup-api-" + suffix + "@example.com");
        actorMemberId = insertMember(orgId, actorUserId);
        createPermissionRole("DUP_API_ROLE", List.of("DUPLICATE_REVIEW"));
        assign("DUP_API_ROLE", "ORG", orgId);
        rawRecordId = insertConfirmedRawRecord(suffix);
        charge1 = insertCharge("10.00000000");
        charge2 = insertCharge("10.00000000");
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void unauthenticatedRequestsAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/duplicate-candidates/scan"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/duplicate-candidates"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void missingDuplicateReviewPermissionIsForbidden() throws Exception {
        jdbc.update("DELETE FROM role_assignment WHERE org_member_id=?", actorMemberId);
        createPermissionRole("READER_ONLY", List.of("COST_READ"));
        assign("READER_ONLY", "ORG", orgId);

        mockMvc.perform(post("/api/v1/duplicate-candidates/scan").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/duplicate-candidates").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void scanCreatesCandidatesAndReturnsSummaryCounts() throws Exception {
        mockMvc.perform(post("/api/v1/duplicate-candidates/scan").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chargesScanned").value(2))
                .andExpect(jsonPath("$.candidatePairsEvaluated").value(1))
                .andExpect(jsonPath("$.candidatesCreated").value(1))
                .andExpect(jsonPath("$.candidatesAlreadyPresent").value(0))
                .andExpect(jsonPath("$.scannedAt").exists());

        // reentrant: the same pair is already present, nothing re-created
        mockMvc.perform(post("/api/v1/duplicate-candidates/scan").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidatesCreated").value(0))
                .andExpect(jsonPath("$.candidatesAlreadyPresent").value(1));
    }

    @Test
    void listsCandidatesWithStringIdsAndFilters() throws Exception {
        mockMvc.perform(post("/api/v1/duplicate-candidates/scan").header("Authorization", bearer()))
                .andExpect(status().isOk());
        jdbc.update("""
                INSERT INTO duplicate_candidate(
                    org_id,charge_fact_id,matched_charge_id,candidate_type,fingerprint,algorithm_version,
                    match_reason,status,created_at)
                VALUES (?,?,?,'OVERLAP',SHA2('api',256),'v2','overlap fixture','OPEN',UTC_TIMESTAMP(6))
                """, orgId, charge1, charge2);

        mockMvc.perform(get("/api/v1/duplicate-candidates")
                        .header("Authorization", bearer())
                        .queryParam("page", "0").queryParam("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.items[0].candidateType").value("EXACT"))
                .andExpect(jsonPath("$.items[0].fingerprint").isString())
                .andExpect(jsonPath("$.items[0].algorithmVersion").value("v1"))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"))
                .andExpect(jsonPath("$.items[0].chargeFact.id").isString())
                .andExpect(jsonPath("$.items[0].chargeFact.amount").value("10.00000000"))
                .andExpect(jsonPath("$.items[0].chargeFact.reviewStatus").value("SUSPECTED_DUPLICATE"))
                .andExpect(jsonPath("$.items[0].chargeFact.duplicateOfChargeId").doesNotExist());

        mockMvc.perform(get("/api/v1/duplicate-candidates")
                        .header("Authorization", bearer())
                        .queryParam("candidateType", "OVERLAP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].candidateType").value("OVERLAP"));
    }

    @Test
    void getCandidateReturnsBothChargesAsDecimalStrings() throws Exception {
        mockMvc.perform(post("/api/v1/duplicate-candidates/scan").header("Authorization", bearer()))
                .andExpect(status().isOk());
        var candidateId = candidateOf(charge1, charge2);

        mockMvc.perform(get("/api/v1/duplicate-candidates/{candidateId}", candidateId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.chargeFact.id").isString())
                .andExpect(jsonPath("$.chargeFact.amount").value("10.00000000"))
                .andExpect(jsonPath("$.matchedChargeFact.amount").value("10.00000000"))
                .andExpect(jsonPath("$.chargeFact.periodStart").exists())
                .andExpect(jsonPath("$.resolvedAt").doesNotExist());

        mockMvc.perform(get("/api/v1/duplicate-candidates/{candidateId}", 999999L)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void crossOrgCandidateIsNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/duplicate-candidates/scan").header("Authorization", bearer()))
                .andExpect(status().isOk());
        var foreignRaw = insertConfirmedRawRecord(foreignOrgId, "foreign-" + System.nanoTime());
        var foreignChargeA = insertCharge(foreignOrgId, foreignRaw, "7.00000000");
        var foreignChargeB = insertCharge(foreignOrgId, foreignRaw, "7.00000000");
        insertCandidateDirectly(foreignOrgId, foreignChargeA, foreignChargeB);
        var foreignCandidateId = candidateOf(foreignOrgId, foreignChargeA, foreignChargeB);

        mockMvc.perform(get("/api/v1/duplicate-candidates/{candidateId}", foreignCandidateId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void keepEnforcesIdempotencyKeyContract() throws Exception {
        mockMvc.perform(post("/api/v1/duplicate-candidates/scan").header("Authorization", bearer()))
                .andExpect(status().isOk());
        var candidateId = candidateOf(charge1, charge2);

        mockMvc.perform(post("/api/v1/duplicate-candidates/{candidateId}/keep", candidateId)
                        .header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("REQUEST_MALFORMED"));

        mockMvc.perform(post("/api/v1/duplicate-candidates/{candidateId}/keep", candidateId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/duplicate-candidates/{candidateId}/keep", candidateId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "k".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void keepResolvesCandidateAndReplaysIdenticalBody() throws Exception {
        mockMvc.perform(post("/api/v1/duplicate-candidates/scan").header("Authorization", bearer()))
                .andExpect(status().isOk());
        var candidateId = candidateOf(charge1, charge2);

        var first = mockMvc.perform(post("/api/v1/duplicate-candidates/{candidateId}/keep", candidateId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "api-keep-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("KEPT_CLEAN"))
                .andExpect(jsonPath("$.chargeFact.reviewStatus").value("CLEAN"))
                .andReturn().getResponse().getContentAsString();

        var replay = mockMvc.perform(post("/api/v1/duplicate-candidates/{candidateId}/keep", candidateId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "api-keep-1"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(replay).isEqualTo(first);
    }

    @Test
    void excludeRequiresExcludedChargeFactIdInBody() throws Exception {
        mockMvc.perform(post("/api/v1/duplicate-candidates/scan").header("Authorization", bearer()))
                .andExpect(status().isOk());
        var candidateId = candidateOf(charge1, charge2);

        mockMvc.perform(post("/api/v1/duplicate-candidates/{candidateId}/exclude", candidateId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "api-exc-null-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/duplicate-candidates/{candidateId}/exclude", candidateId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "api-exc-null-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excludedChargeFactId\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listRejectsInvalidPageAndSize() throws Exception {
        mockMvc.perform(get("/api/v1/duplicate-candidates")
                        .header("Authorization", bearer())
                        .queryParam("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/duplicate-candidates")
                        .header("Authorization", bearer())
                        .queryParam("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get("/api/v1/duplicate-candidates")
                        .header("Authorization", bearer())
                        .queryParam("size", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void excludeRequiresBodyTargetInsideThePair() throws Exception {
        mockMvc.perform(post("/api/v1/duplicate-candidates/scan").header("Authorization", bearer()))
                .andExpect(status().isOk());
        var candidateId = candidateOf(charge1, charge2);

        var raw = insertCharge("30.00000000");
        mockMvc.perform(post("/api/v1/duplicate-candidates/{candidateId}/exclude", candidateId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "api-exc-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excludedChargeFactId\":\"" + raw + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/v1/duplicate-candidates/{candidateId}/exclude", candidateId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "api-exc-bad2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excludedChargeFactId\":\"not-a-number\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void excludeMarksDuplicateAndAudits() throws Exception {
        mockMvc.perform(post("/api/v1/duplicate-candidates/scan").header("Authorization", bearer()))
                .andExpect(status().isOk());
        var candidateId = candidateOf(charge1, charge2);

        mockMvc.perform(post("/api/v1/duplicate-candidates/{candidateId}/exclude", candidateId)
                        .header("Authorization", bearer())
                        .header("Idempotency-Key", "api-exc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"excludedChargeFactId\":\"" + charge2 + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED_DUPLICATE"))
                .andExpect(jsonPath("$.matchedChargeFact.reviewStatus").value("EXCLUDED_DUPLICATE"))
                .andExpect(jsonPath("$.matchedChargeFact.duplicateOfChargeId")
                        .value(Long.toString(charge1)));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Integer.class)).isEqualTo(1);
    }

    // -- helpers ----------------------------------------------------------------

    private String bearer() {
        return "Bearer " + tokens.issue(actorUserId, 7).token();
    }

    private long candidateOf(long low, long high) {
        return candidateOf(orgId, low, high);
    }

    private long candidateOf(long org, long low, long high) {
        return jdbc.queryForObject("""
                SELECT id FROM duplicate_candidate
                WHERE org_id=? AND charge_fact_id=? AND matched_charge_id=?
                """, Long.class, org, low, high);
    }

    private void insertCandidateDirectly(long org, long low, long high) {
        jdbc.update("""
                INSERT INTO duplicate_candidate(
                    org_id,charge_fact_id,matched_charge_id,candidate_type,fingerprint,algorithm_version,
                    match_reason,status,created_at)
                VALUES (?,?,?,'EXACT',SHA2('api2',256),'v1','api fixture','OPEN',UTC_TIMESTAMP(6))
                """, org, low, high);
    }

    private long insertCharge(String amount) {
        return insertCharge(orgId, rawRecordId, amount);
    }

    private long insertCharge(long org, long raw, String amount) {
        var nextIndex = jdbc.queryForObject(
                "SELECT COALESCE(MAX(fact_index),-1)+1 FROM charge_fact WHERE raw_record_id=?",
                Integer.class, raw);
        jdbc.update("""
                INSERT INTO charge_fact(
                    org_id,raw_record_id,fact_index,provider_code,charge_category,amount,currency,
                    period_start,period_end,created_at)
                VALUES (?,?,?,'GLM','USAGE',?,'CNY',?,?,UTC_TIMESTAMP(6))
                """, org, raw, nextIndex, amount, JAN_1, FEB_1);
        return jdbc.queryForObject(
                "SELECT MAX(id) FROM charge_fact WHERE org_id=? AND raw_record_id=?",
                Long.class, org, raw);
    }

    private long insertConfirmedRawRecord(String suffix) {
        return insertConfirmedRawRecord(orgId, suffix);
    }

    private long insertConfirmedRawRecord(long org, String suffix) {
        var sha256 = (suffix.replace("-", "") + "0123456789abcdef").repeat(4).substring(0, 64);
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'AVAILABLE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, sha256, "org/" + org + "/evidence/" + sha256, "usage.csv",
                "text/csv", 1L, actorMemberId);
        var evidenceId = jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, org, sha256);
        jdbc.update("""
                INSERT IGNORE INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,'Api Account',NULL,'ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, "GLM");
        var accountId = jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code='GLM'",
                Long.class, org);
        jdbc.update("""
                INSERT INTO import_batch(
                    org_id,evidence_id,provider_account_id,expected_provider_code,source_type,
                    parser_version,status,period_start,period_end,created_by_member_id,created_at,updated_at)
                VALUES (?,?,?,?,?,?,'CONFIRMED',NULL,NULL,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, org, evidenceId, accountId, "GLM", "FILE_EXPORT", "test-parser-v1", actorMemberId);
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
                "SELECT id FROM import_attempt WHERE import_batch_id=?", Long.class, batchId);
        jdbc.update("UPDATE import_batch SET confirmed_attempt_id=? WHERE id=?", attemptId, batchId);
        jdbc.update("""
                INSERT INTO raw_provider_record(
                    import_attempt_id,record_index,record_locator,provider_record_key,
                    raw_payload,normalized_payload,usage_start,usage_end,normalize_status,created_at)
                VALUES (?,0,?,NULL,JSON_OBJECT(),NULL,?,?,'NORMALIZED',UTC_TIMESTAMP(6))
                """, attemptId, "api:" + suffix, JAN_1, FEB_1);
        return jdbc.queryForObject(
                "SELECT id FROM raw_provider_record WHERE import_attempt_id=? AND record_index=0",
                Long.class, attemptId);
    }

    private void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code IN ('DUP_API_ROLE','READER_ONLY')
                """);
        jdbc.update("DELETE FROM `role` WHERE code IN ('DUP_API_ROLE','READER_ONLY')");
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
                VALUES (?,?,'ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, email, "Api Reviewer");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE email_normalized=?",
                Long.class, email);
    }

    private long insertMember(long org, long userId) {
        jdbc.update("""
                INSERT INTO organization_member(org_id,user_id,status,joined_at)
                VALUES (?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """, org, userId);
        return jdbc.queryForObject(
                "SELECT id FROM organization_member WHERE org_id=? AND user_id=?",
                Long.class, org, userId);
    }
}
