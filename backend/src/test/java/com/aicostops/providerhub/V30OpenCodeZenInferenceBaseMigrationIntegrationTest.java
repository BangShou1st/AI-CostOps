package com.aicostops.providerhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Proves the V30/V31 OpenCode endpoint history on real MySQL: V30's catalog change is retained,
 * while the versioned correction moves routing authority to a new ACTIVE profile and keeps the
 * pre-correction profile id and configuration available to frozen references.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V30OpenCodeZenInferenceBaseMigrationIntegrationTest {

    private static final String LEGACY_ZEN_BASE = "https://opencode.ai/zen/v1";
    private static final String INFERENCE_BASE = "https://opencode.ai/inference/v1";

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("aicostops_v30_test")
            .withUsername("aicostops")
            .withPassword("aicostops-test-only")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withConnectTimeoutSeconds(60);

    static {
        MYSQL.start();
    }

    private final JdbcTemplate jdbc = new JdbcTemplate(
            new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    MYSQL.getJdbcUrl() + "?serverTimezone=UTC", MYSQL.getUsername(), MYSQL.getPassword()));
    private Long organizationId;
    private Long accountId;
    private Long preVersionedProfileId;
    private Long draftProfileId;
    private Map<String, Object> profileBeforeVersionedSwitch;
    private Map<String, Object> draftBeforeVersionedSwitch;

    @BeforeAll
    void migrateToV30SeedLegacyProfile() {
        var url = MYSQL.getJdbcUrl() + "?serverTimezone=UTC";
        migrate(url, "29");

        jdbc.update("INSERT INTO organization(name,slug,status,created_at,updated_at)"
                + " VALUES ('V30 Acme','v30-acme','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        organizationId = jdbc.queryForObject("SELECT id FROM organization WHERE slug='v30-acme'", Long.class);
        jdbc.update("INSERT INTO provider_account(org_id,provider_code,display_name,status,created_at,updated_at)"
                + " VALUES (?,'OPENCODE_ZEN','V30 Zen','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", organizationId);
        accountId = jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code='OPENCODE_ZEN'",
                Long.class, organizationId);
        jdbc.update("INSERT INTO provider_connection_profile(org_id,provider_account_id,version,connection_kind,"
                + "template_code,protocol_code,base_url,completion_path,models_path,auth_type,network_policy,"
                + "connect_timeout_ms,response_timeout_ms,status,created_at,activated_at)"
                + " VALUES (?,?,1,'BUILTIN','OPENCODE_ZEN','OPENAI_CHAT_COMPLETIONS',"
                + "?,'/chat/completions','/models','BEARER','DIRECT_ONLY',5000,60000,'ACTIVE',"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", organizationId, accountId, LEGACY_ZEN_BASE);
        jdbc.update("INSERT INTO provider_connection_profile(org_id,provider_account_id,version,connection_kind,"
                + "template_code,protocol_code,base_url,completion_path,models_path,auth_type,network_policy,"
                + "connect_timeout_ms,response_timeout_ms,status,created_at)"
                + " VALUES (?,?,2,'BUILTIN','OPENCODE_ZEN','OPENAI_CHAT_COMPLETIONS',"
                + "?,'/chat/completions','/models','BEARER','DIRECT_ONLY',5000,60000,'DRAFT',"
                + "UTC_TIMESTAMP(6))", organizationId, accountId, LEGACY_ZEN_BASE);
        draftProfileId = jdbc.queryForObject(
                "SELECT id FROM provider_connection_profile WHERE org_id=? AND provider_account_id=? AND version=2",
                Long.class, organizationId, accountId);
        draftBeforeVersionedSwitch = profileConfigSnapshot(draftProfileId);

        // The persisted profile begins at the legacy endpoint seeded by V24.
        assertThat(catalogBaseUrl()).isEqualTo(LEGACY_ZEN_BASE);
        assertThat(activeProfileBaseUrl()).isEqualTo(LEGACY_ZEN_BASE);

        // Run the already-published V30 separately. Its current behavior is the state that a
        // corrective migration must handle without changing the already-executed migration.
        migrate(url, "30");
        assertThat(catalogBaseUrl()).isEqualTo(INFERENCE_BASE);
        assertThat(activeProfileBaseUrl()).isEqualTo(INFERENCE_BASE);
        preVersionedProfileId = activeProfileId();
        profileBeforeVersionedSwitch = profileConfigSnapshot(preVersionedProfileId);
        assertThat(profileStatus(draftProfileId)).isEqualTo("DRAFT");

        // A profile-scoped observation must keep referring to this historical profile after the
        // active route moves to a new profile id.
        jdbc.update("""
                INSERT INTO provider_model_discovery(
                  org_id,provider_connection_profile_id,provider_model_name,display_name,source,
                  availability,protocol_code,pricing_classification,declared_capabilities_json,
                  verified_capabilities_json,last_seen_at,last_probed_at,last_probe_status,
                  last_probe_error_code,created_at,updated_at)
                VALUES (?,?,'zen-free-migration-test',NULL,'MANUAL','AVAILABLE',
                  'OPENAI_CHAT_COMPLETIONS','VERIFIED_FREE',JSON_OBJECT(),JSON_OBJECT(),
                  UTC_TIMESTAMP(6),NULL,NULL,NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, organizationId, preVersionedProfileId);
    }

    @Test
    void versionedSwitchPreservesTheOldProfileAndActivatesAUsableNewRoute() {
        var url = MYSQL.getJdbcUrl() + "?serverTimezone=UTC";
        migrate(url, null);

        assertThat(catalogBaseUrl()).isEqualTo(INFERENCE_BASE);
        assertThat(activeProfileBaseUrl()).isEqualTo(INFERENCE_BASE);
        assertThat(activeProfileId()).isNotEqualTo(preVersionedProfileId);
        assertThat(activeProfileVersion()).isEqualTo(3);
        assertThat(profileStatus(preVersionedProfileId)).isEqualTo("RETIRED");
        assertThat(profileConfigSnapshot(preVersionedProfileId)).isEqualTo(profileBeforeVersionedSwitch);
        assertThat(profileStatus(draftProfileId)).isEqualTo("DRAFT");
        assertThat(profileConfigSnapshot(draftProfileId)).isEqualTo(draftBeforeVersionedSwitch);
        assertThat(discoveryProfileId()).isEqualTo(preVersionedProfileId);
        assertThat(profileCount()).isEqualTo(3);

        // Flyway's already-applied history makes a repeat migrate a no-op; assert that neither
        // route authority nor the frozen historical profile changes on a repeated invocation.
        var activeId = activeProfileId();
        var activeConfig = profileConfigSnapshot(activeId);
        migrate(url, null);
        assertThat(activeProfileId()).isEqualTo(activeId);
        assertThat(profileConfigSnapshot(activeId)).isEqualTo(activeConfig);
        assertThat(profileConfigSnapshot(preVersionedProfileId)).isEqualTo(profileBeforeVersionedSwitch);
        assertThat(profileConfigSnapshot(draftProfileId)).isEqualTo(draftBeforeVersionedSwitch);
    }

    private void migrate(String url, String target) {
        var configuration = Flyway.configure()
                .dataSource(url, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration = configuration.target(target);
        }
        configuration.load().migrate();
    }

    private String catalogBaseUrl() {
        return jdbc.queryForObject(
                "SELECT base_url FROM provider_catalog WHERE provider_code='OPENCODE_ZEN'", String.class);
    }

    private String activeProfileBaseUrl() {
        return jdbc.queryForObject(
                "SELECT p.base_url FROM provider_connection_profile p"
                        + " JOIN provider_account pa ON pa.id=p.provider_account_id AND pa.org_id=p.org_id"
                        + " WHERE pa.provider_code='OPENCODE_ZEN' AND p.status='ACTIVE'",
                String.class);
    }

    private long activeProfileId() {
        return jdbc.queryForObject("""
                SELECT p.id FROM provider_connection_profile p
                JOIN provider_account pa ON pa.id=p.provider_account_id AND pa.org_id=p.org_id
                WHERE pa.provider_code='OPENCODE_ZEN' AND p.status='ACTIVE'
                """, Long.class);
    }

    private int activeProfileVersion() {
        return jdbc.queryForObject("SELECT version FROM provider_connection_profile WHERE id=?", Integer.class,
                activeProfileId());
    }

    private String profileStatus(long id) {
        return jdbc.queryForObject("SELECT status FROM provider_connection_profile WHERE id=?", String.class, id);
    }

    private Map<String, Object> profileConfigSnapshot(long id) {
        return jdbc.queryForMap("""
                SELECT id,org_id,provider_account_id,version,connection_kind,template_code,protocol_code,
                  base_url,completion_path,models_path,auth_type,auth_header_name,network_policy,user_agent,
                  connect_timeout_ms,response_timeout_ms,created_by,created_at,activated_at
                FROM provider_connection_profile WHERE id=?
                """, id);
    }

    private long discoveryProfileId() {
        return jdbc.queryForObject("""
                SELECT provider_connection_profile_id FROM provider_model_discovery
                WHERE org_id=? AND provider_model_name='zen-free-migration-test'
                """, Long.class, organizationId);
    }

    private int profileCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM provider_connection_profile p
                JOIN provider_account pa ON pa.id=p.provider_account_id AND pa.org_id=p.org_id
                WHERE pa.provider_code='OPENCODE_ZEN'
                """, Integer.class);
    }
}
