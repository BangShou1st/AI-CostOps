package com.aicostops.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.ingestion.application.InspectionResult;
import com.aicostops.ingestion.application.NormalizedProviderRecord;
import com.aicostops.ingestion.application.ParsedProviderRecord;
import com.aicostops.ingestion.application.ProviderAdapter;
import com.aicostops.ingestion.application.ProviderRecordSink;
import com.aicostops.ingestion.application.ProviderSource;
import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MinioAuthenticationContainersSupport;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=provider-import-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class ProviderImportApiIntegrationTest extends MinioAuthenticationContainersSupport {

    private static final byte[] CONTENT = "provider import bytes".getBytes(StandardCharsets.UTF_8);

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
    private long activeProviderAccountId;
    private long unknownProviderAccountId;
    private long disabledProviderAccountId;
    private long foreignProviderAccountId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        organizationId = insertOrganization("Provider Import", "provider-import");
        foreignOrganizationId = insertOrganization("Foreign", "provider-import-foreign");
        actorUserId = insertUser("provider-importer@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        activeProviderAccountId = insertProviderAccount(organizationId, "TEST_PROVIDER", "Test", "ACTIVE");
        unknownProviderAccountId = insertProviderAccount(organizationId, "UNKNOWN_PROVIDER", "Unknown", "ACTIVE");
        disabledProviderAccountId = insertProviderAccount(organizationId, "TEST_PROVIDER", "Disabled", "DISABLED");
        foreignProviderAccountId = insertProviderAccount(foreignOrganizationId, "TEST_PROVIDER", "Foreign", "ACTIVE");
        createPermissionRole("IMPORT_CREATOR", List.of("EVIDENCE_UPLOAD_PROVIDER"));
        createPermissionRole("IMPORT_READER", List.of("IMPORT_READ"));
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void newContextCreatesEvidenceBatchAndInitialQueuedAttempt() throws Exception {
        assign("IMPORT_CREATOR", "ORG", organizationId);

        mockMvc.perform(importRequest(activeProviderAccountId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchStatus").value("PENDING"))
                .andExpect(jsonPath("$.duplicateEvidence").value(false))
                .andExpect(jsonPath("$.duplicateBatch").value(false))
                .andExpect(jsonPath("$.latestAttemptId").isNumber());

        var result = mockMvc.perform(importRequest(activeProviderAccountId)).andReturn();
        // Verify durable rows directly instead of parsing the body twice.
        var body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"duplicateEvidence\":true");
        assertThat(body).contains("\"duplicateBatch\":true");
    }

    @Test
    void sameBytesAccountSourceAndParserReuseBatchWithoutImplicitRetry() throws Exception {
        assign("IMPORT_CREATOR", "ORG", organizationId);

        var firstBody = mockMvc.perform(importRequest(activeProviderAccountId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var first = jsonNumbers(firstBody);
        var secondBody = mockMvc.perform(importRequest(activeProviderAccountId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var second = jsonNumbers(secondBody);

        assertThat(second.evidenceId).isEqualTo(first.evidenceId);
        assertThat(second.importBatchId).isEqualTo(first.importBatchId);
        assertThat(second.latestAttemptId).isEqualTo(first.latestAttemptId);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM import_attempt WHERE import_batch_id=?",
                Integer.class, first.importBatchId)).isEqualTo(1);
    }

    @Test
    void sameBytesWithDifferentProviderAccountCreateADifferentBatch() throws Exception {
        assign("IMPORT_CREATOR", "ORG", organizationId);
        insertProviderAccount(organizationId, "TEST_PROVIDER", "Second Account", "ACTIVE");
        var secondAccountId = jdbc.queryForObject("""
                SELECT id FROM provider_account WHERE org_id=? AND display_name='Second Account'
                """, Long.class, organizationId);

        var first = jsonNumbers(mockMvc.perform(importRequest(activeProviderAccountId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        var second = jsonNumbers(mockMvc.perform(importRequest(secondAccountId))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());

        assertThat(second.evidenceId).isEqualTo(first.evidenceId);
        assertThat(second.importBatchId).isNotEqualTo(first.importBatchId);
    }

    @Test
    void uploadPermissionWithOnlyNonOrgScopeIsForbidden() throws Exception {
        assign("IMPORT_CREATOR", "PROJECT", 42L);

        mockMvc.perform(importRequest(activeProviderAccountId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void inactiveOrCrossOrganizationProviderAccountIsNotFound() throws Exception {
        assign("IMPORT_CREATOR", "ORG", organizationId);

        for (var accountId : new long[] {disabledProviderAccountId, foreignProviderAccountId}) {
            mockMvc.perform(importRequest(accountId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Test
    void unsupportedProviderFailsBeforeAnyBatchIsCreated() throws Exception {
        assign("IMPORT_CREATOR", "ORG", organizationId);

        mockMvc.perform(importRequest(unknownProviderAccountId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_batch", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM evidence", Integer.class)).isZero();
    }

    @Test
    void missingUploadPermissionIsForbidden() throws Exception {
        assign("IMPORT_READER", "ORG", organizationId);

        mockMvc.perform(importRequest(activeProviderAccountId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void concurrentSameIdentityUploadsConvergeOnOneBatchWithOneAttempt() throws Exception {
        assign("IMPORT_CREATOR", "ORG", organizationId);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Long> upload = () -> {
                start.await();
                var body = mockMvc.perform(importRequest(activeProviderAccountId))
                        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
                return jsonNumbers(body).importBatchId;
            };
            var first = pool.submit(upload);
            var second = pool.submit(upload);
            start.countDown();

            assertThat(first.get()).isEqualTo(second.get());
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_batch", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM import_attempt", Integer.class)).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder importRequest(
            long providerAccountId) {
        return multipart("/api/v1/provider-imports")
                .file(new MockMultipartFile("file", "invoice.csv", "text/csv", CONTENT))
                .param("providerAccountId", Long.toString(providerAccountId))
                .param("sourceType", "FILE_EXPORT")
                .header("Authorization", bearer());
    }

    private static Ids jsonNumbers(String body) {
        var evidenceId = extractLong(body, "evidenceId");
        var importBatchId = extractLong(body, "importBatchId");
        var latestAttemptId = extractLong(body, "latestAttemptId");
        return new Ids(evidenceId, importBatchId, latestAttemptId);
    }

    private static long extractLong(String body, String field) {
        var marker = "\"" + field + "\":";
        var start = body.indexOf(marker) + marker.length();
        var end = body.indexOf(',', start);
        if (end < 0) {
            end = body.indexOf('}', start);
        }
        return Long.parseLong(body.substring(start, end).trim());
    }

    private record Ids(long evidenceId, long importBatchId, long latestAttemptId) {
    }

    private void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code IN ('IMPORT_CREATOR','IMPORT_READER')
                """);
        jdbc.update("DELETE FROM `role` WHERE code IN ('IMPORT_CREATOR','IMPORT_READER')");
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
                VALUES (?,'Provider Importer','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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

    private long insertProviderAccount(long orgId, String providerCode, String displayName, String status) {
        jdbc.update("""
                INSERT INTO provider_account(
                    org_id,provider_code,display_name,external_account_ref,status,metadata_json,created_at,updated_at)
                VALUES (?,?,?,NULL,?,JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, providerCode, displayName, status);
        return jdbc.queryForObject("""
                SELECT id FROM provider_account
                WHERE org_id=? AND provider_code=? AND display_name=?
                """, Long.class, orgId, providerCode, displayName);
    }

    private String bearer() {
        return "Bearer " + tokens.issue(actorUserId, 7).token();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestProviderAdapterConfiguration {

        @Bean
        ProviderAdapter testProviderAdapter() {
            return new ProviderAdapter() {
                @Override
                public String providerCode() {
                    return "TEST_PROVIDER";
                }

                @Override
                public String parserVersion() {
                    return "test-parser-v1";
                }

                @Override
                public InspectionResult inspect(ProviderSource source) {
                    return new InspectionResult("TEST_PROVIDER", "test-schema-fingerprint", true, List.of());
                }

                @Override
                public void parse(ProviderSource source, InspectionResult inspection, ProviderRecordSink sink) {
                }

                @Override
                public NormalizedProviderRecord normalize(ParsedProviderRecord record) {
                    return new NormalizedProviderRecord(record.index(), record.locator(), null,
                            Map.of(), Map.of(), null, null, RawRecordNormalizeStatus.NORMALIZED, List.of());
                }
            };
        }
    }
}
