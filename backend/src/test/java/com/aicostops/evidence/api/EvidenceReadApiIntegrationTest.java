package com.aicostops.evidence.api;

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
        "aicostops.auth.jwt-signing-secret=evidence-read-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class EvidenceReadApiIntegrationTest extends AuthenticationContainersSupport {

    private static final long BEYOND_SAFE_INTEGER = 9007199254740993L;

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
    private long newestEvidenceId;
    private long oldestEvidenceId;
    private long foreignEvidenceId;
    private long bigIdEvidenceId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        organizationId = insertOrganization("Evidence Read", "evidence-read");
        foreignOrganizationId = insertOrganization("Foreign", "evidence-read-foreign");
        actorUserId = insertUser("evidence-reader@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        createPermissionRole("EVIDENCE_READER", List.of("EVIDENCE_READ"));
        createPermissionRole("DOWNLOADER", List.of("EVIDENCE_DOWNLOAD"));

        newestEvidenceId = insertEvidence(organizationId, "b".repeat(64), "2026-08-03 10:00:00", "newest.csv");
        insertEvidence(organizationId, "a".repeat(64), "2026-08-02 10:00:00", "middle.csv");
        oldestEvidenceId = insertEvidence(organizationId, "c".repeat(64), "2026-08-01 10:00:00", "oldest.csv");
        foreignEvidenceId = insertEvidence(foreignOrganizationId, "d".repeat(64),
                "2026-08-03 11:00:00", "foreign.csv");
        bigIdEvidenceId = insertEvidenceWithId(organizationId, BEYOND_SAFE_INTEGER, "e".repeat(64),
                "2026-08-04 10:00:00", "big-id.csv");
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void listsEvidenceNewestFirstWithOwnOrgAndReadPermission() throws Exception {
        assign("EVIDENCE_READER", "ORG", organizationId);

        mockMvc.perform(get("/api/v1/evidence?page=0&size=2").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.items[0].id").value(Long.toString(bigIdEvidenceId)))
                .andExpect(jsonPath("$.items[1].id").value(Long.toString(newestEvidenceId)));
    }

    @Test
    void listAppliesDefaultPageSizeFifty() throws Exception {
        assign("EVIDENCE_READER", "ORG", organizationId);

        mockMvc.perform(get("/api/v1/evidence").header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void listValidationFailuresReturnValidationErrorInsteadOfClamping() throws Exception {
        assign("EVIDENCE_READER", "ORG", organizationId);

        for (var query : List.of("page=-1&size=50", "page=0&size=0", "page=0&size=201")) {
            mockMvc.perform(get("/api/v1/evidence?" + query).header("Authorization", bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void missingReadPermissionIsForbiddenBeforeResourceLookup() throws Exception {
        assign("DOWNLOADER", "ORG", organizationId);

        mockMvc.perform(get("/api/v1/evidence").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/evidence/{id}", 999999L).header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void crossOrganizationEvidenceDetailIsNotFound() throws Exception {
        assign("EVIDENCE_READER", "ORG", organizationId);

        mockMvc.perform(get("/api/v1/evidence/{id}", foreignEvidenceId).header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void detailExposesReviewMetadataWithStringIdsAndNeverObjectKey() throws Exception {
        assign("EVIDENCE_READER", "ORG", organizationId);

        mockMvc.perform(get("/api/v1/evidence/{id}", bigIdEvidenceId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.id").value(Long.toString(BEYOND_SAFE_INTEGER)))
                .andExpect(jsonPath("$.originalFilename").value("big-id.csv"))
                .andExpect(jsonPath("$.mediaType").value("text/csv"))
                .andExpect(jsonPath("$.sizeBytes").value(1))
                .andExpect(jsonPath("$.sha256").value("e".repeat(64)))
                .andExpect(jsonPath("$.storageStatus").value("AVAILABLE"))
                .andExpect(jsonPath("$.uploadedByMemberId").isString())
                .andExpect(jsonPath("$.uploadedByMemberId").value(Long.toString(actorMemberId)))
                .andExpect(jsonPath("$.objectKey").doesNotExist());

        mockMvc.perform(get("/api/v1/evidence/{id}", oldestEvidenceId).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.objectKey").doesNotExist());
    }

    private long insertEvidence(long orgId, String sha256, String createdAt, String filename) {
        return insertEvidenceWithId(orgId, null, sha256, createdAt, filename);
    }

    private long insertEvidenceWithId(Long orgId, Long id, String sha256, String createdAt, String filename) {
        jdbc.update("""
                INSERT INTO evidence(
                    id,org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,'AVAILABLE',NULL,?,?)
                """, id, orgId, sha256, "org/" + orgId + "/evidence/" + sha256, filename,
                "text/csv", 1L, actorMemberId, createdAt, createdAt);
        return jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, orgId, sha256);
    }

    private void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code IN ('EVIDENCE_READER','DOWNLOADER')
                """);
        jdbc.update("DELETE FROM `role` WHERE code IN ('EVIDENCE_READER','DOWNLOADER')");
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
                VALUES (?,'Evidence Reader','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
