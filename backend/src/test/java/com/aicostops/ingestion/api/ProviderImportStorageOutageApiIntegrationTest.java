package com.aicostops.ingestion.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aicostops.evidence.application.ObjectStoragePort;
import com.aicostops.evidence.application.StoredObjectMetadata;
import com.aicostops.evidence.infrastructure.ObjectStorageException;
import com.aicostops.iam.infrastructure.JwtTokenService;
import com.aicostops.ingestion.application.InspectionResult;
import com.aicostops.ingestion.application.NormalizedProviderRecord;
import com.aicostops.ingestion.application.ParsedProviderRecord;
import com.aicostops.ingestion.application.ProviderAdapter;
import com.aicostops.ingestion.application.ProviderInput;
import com.aicostops.ingestion.application.ProviderRecordSink;
import com.aicostops.ingestion.application.ProviderSource;
import com.aicostops.ingestion.domain.RawRecordNormalizeStatus;
import com.aicostops.testsupport.AuthenticationContainersSupport;
import com.aicostops.testsupport.M2DatabaseCleaner;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties =
        "aicostops.auth.jwt-signing-secret=provider-import-503-test-only-signing-secret-with-more-than-32-bytes")
@AutoConfigureMockMvc
@Tag("integration")
class ProviderImportStorageOutageApiIntegrationTest extends AuthenticationContainersSupport {

    private static final byte[] CONTENT = "provider bytes for outage".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtTokenService tokens;
    @Autowired
    private StringRedisTemplate redis;

    private long organizationId;
    private long actorUserId;
    private long actorMemberId;
    private long providerAccountId;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();

        organizationId = insertOrganization("Import 503", "import-503");
        actorUserId = insertUser("import-503@example.com");
        actorMemberId = insertMember(organizationId, actorUserId);
        providerAccountId = insertProviderAccount(organizationId, "TEST_PROVIDER", "Test", "ACTIVE");
        createPermissionRole("IMPORT_CREATOR", List.of("EVIDENCE_UPLOAD_PROVIDER"));
        assign("IMPORT_CREATOR", "ORG", organizationId);
    }

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
        deleteCustomRoles();
    }

    @Test
    void objectStorageOutageDuringUploadReturns503DependencyUnavailable() throws Exception {
        mockMvc.perform(multipart("/api/v1/provider-imports")
                        .file(new MockMultipartFile("file", "invoice.csv", "text/csv", CONTENT))
                        .param("providerAccountId", Long.toString(providerAccountId))
                        .param("sourceType", "FILE_EXPORT")
                        .header("Authorization", bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DEPENDENCY_TEMPORARILY_UNAVAILABLE"));

        // The outage happened before any object was written; the Evidence row stays
        // STAGING rather than being destroyed by a transient stat outage.
        assertThat(jdbc.queryForObject(
                "SELECT storage_status FROM evidence WHERE org_id=?", String.class, organizationId))
                .isEqualTo("STAGING");
    }

    private void deleteCustomRoles() {
        jdbc.update("""
                DELETE rp FROM role_permission rp
                JOIN `role` r ON r.id=rp.role_id
                WHERE r.code IN ('IMPORT_CREATOR')
                """);
        jdbc.update("DELETE FROM `role` WHERE code IN ('IMPORT_CREATOR')");
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
                VALUES (?,'Import 503','ACTIVE',7,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
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
    static class StorageOutageConfiguration {

        @Bean
        @Primary
        ObjectStoragePort outageObjectStoragePort() {
            return new ObjectStoragePort() {
                @Override
                public void put(String objectKey, Path file, long sizeBytes, String sha256) {
                    throw new ObjectStorageException("simulated storage outage");
                }

                @Override
                public Optional<StoredObjectMetadata> stat(String objectKey) {
                    throw new ObjectStorageException("simulated storage outage");
                }

                @Override
                public InputStream open(String objectKey) {
                    throw new ObjectStorageException("simulated storage outage");
                }
            };
        }

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
                public InspectionResult inspect(ProviderInput input) {
                    return new InspectionResult("TEST_PROVIDER", "test.file.v1", "fingerprint", true, List.of());
                }

                @Override
                public void parse(ProviderInput input, InspectionResult inspection, ProviderRecordSink sink) {
                }

                @Override
                public NormalizedProviderRecord normalize(ParsedProviderRecord record, InspectionResult inspection) {
                    return new NormalizedProviderRecord(record.index(), record.locator(), null,
                            Map.of(), Map.of(), null, null, RawRecordNormalizeStatus.NORMALIZED, List.of());
                }
            };
        }
    }
}
