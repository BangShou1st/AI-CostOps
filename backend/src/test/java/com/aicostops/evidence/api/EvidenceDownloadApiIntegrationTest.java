package com.aicostops.evidence.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.evidence.application.EvidenceStorageService;
import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MinioAuthenticationContainersSupport;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
        "aicostops.auth.jwt-signing-secret=evidence-download-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class EvidenceDownloadApiIntegrationTest extends MinioAuthenticationContainersSupport {

    private static final byte[] CONTENT = "authorized evidence bytes".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private EvidenceStorageService storage;

    private long organizationId;
    private long foreignOrganizationId;
    private long actorUserId;
    private long actorMemberId;
    private long availableEvidenceId;
    private long foreignEvidenceId;
    private long stagingEvidenceId;
    private long failedEvidenceId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        organizationId = insertOrganization("Evidence Download", "evidence-download");
        foreignOrganizationId = insertOrganization("Foreign", "evidence-download-foreign");
        actorUserId = insertUser("evidence-downloader@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        createPermissionRole("EVIDENCE_READER", List.of("EVIDENCE_DOWNLOAD"));
        createPermissionRole("EVIDENCE_UPLOADER", List.of("EVIDENCE_UPLOAD_PROVIDER"));

        availableEvidenceId = storage.store(
                organizationId, actorMemberId, "invoice.csv", "text/csv", new ByteArrayInputStream(CONTENT)).id();
        foreignEvidenceId = storage.store(
                foreignOrganizationId, insertMember(foreignOrganizationId, insertUser("foreign@example.com")),
                "invoice.csv", "text/csv", new ByteArrayInputStream(CONTENT)).id();
        stagingEvidenceId = insertEvidence(organizationId, "STAGING", "c".repeat(64));
        failedEvidenceId = insertEvidence(organizationId, "FAILED", "d".repeat(64));
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void downloadsExactBytesWithOrgGrantAndDownloadPermission() throws Exception {
        assign("EVIDENCE_READER", "ORG", organizationId);

        mockMvc.perform(get("/api/v1/evidence/{id}/download", availableEvidenceId)
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(CONTENT));
    }

    @Test
    void downloadPermissionWithOnlyProjectScopeIsForbidden() throws Exception {
        assign("EVIDENCE_READER", "PROJECT", 42L);

        mockMvc.perform(get("/api/v1/evidence/{id}/download", availableEvidenceId)
                        .header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void missingDownloadPermissionIsForbidden() throws Exception {
        assign("EVIDENCE_UPLOADER", "ORG", organizationId);

        mockMvc.perform(get("/api/v1/evidence/{id}/download", availableEvidenceId)
                        .header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void crossOrganizationEvidenceIsNotFound() throws Exception {
        assign("EVIDENCE_READER", "ORG", organizationId);

        mockMvc.perform(get("/api/v1/evidence/{id}/download", foreignEvidenceId)
                        .header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void evidenceNotAvailableConflictsWithoutOpeningObjectStream() throws Exception {
        assign("EVIDENCE_READER", "ORG", organizationId);

        for (var id : new long[] {stagingEvidenceId, failedEvidenceId}) {
            mockMvc.perform(get("/api/v1/evidence/{id}/download", id)
                            .header("Authorization", bearer()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
        }
        assertThat(jdbc.queryForObject(
                "SELECT storage_status FROM evidence WHERE id=? AND org_id=?",
                String.class, stagingEvidenceId, organizationId)).isEqualTo("STAGING");
    }

    private long insertEvidence(long orgId, String storageStatus, String sha256) {
        jdbc.update("""
                INSERT INTO evidence(
                    org_id,sha256,object_key,original_filename,media_type,size_bytes,
                    uploaded_by_member_id,storage_status,storage_error_code,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,'%s',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """.formatted(storageStatus), orgId, sha256, "org/" + orgId + "/no-object",
                "staged.csv", "text/csv", 1L, actorMemberId);
        return jdbc.queryForObject(
                "SELECT id FROM evidence WHERE org_id=? AND sha256=?", Long.class, orgId, sha256);
    }

    private void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code IN ('EVIDENCE_READER','EVIDENCE_UPLOADER')
                """);
        jdbc.update("DELETE FROM `role` WHERE code IN ('EVIDENCE_READER','EVIDENCE_UPLOADER')");
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
                VALUES (?,'Evidence Downloader','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
