package com.aicostops.providerhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Proves the V30 endpoint migration on real MySQL (2026-10-17 CLI inference posture decision): the
 * built-in OPENCODE_ZEN catalog row and its ACTIVE connection profile move from the legacy
 * {@code https://opencode.ai/zen/v1} origin to the CLI inference base
 * {@code https://opencode.ai/inference/v1}. V24 stays immutable; V30 carries the change.
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

    @BeforeAll
    void migrateToV29SeedLegacyProfileThenV30() {
        var url = MYSQL.getJdbcUrl() + "?serverTimezone=UTC";
        migrate(url, "29");

        jdbc.update("INSERT INTO organization(name,slug,status,created_at,updated_at)"
                + " VALUES ('V30 Acme','v30-acme','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        var orgId = jdbc.queryForObject("SELECT id FROM organization WHERE slug='v30-acme'", Long.class);
        jdbc.update("INSERT INTO provider_account(org_id,provider_code,display_name,status,created_at,updated_at)"
                + " VALUES (?,'OPENCODE_ZEN','V30 Zen','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId);
        var accountId = jdbc.queryForObject(
                "SELECT id FROM provider_account WHERE org_id=? AND provider_code='OPENCODE_ZEN'",
                Long.class, orgId);
        jdbc.update("INSERT INTO provider_connection_profile(org_id,provider_account_id,version,connection_kind,"
                + "template_code,protocol_code,base_url,completion_path,models_path,auth_type,network_policy,"
                + "connect_timeout_ms,response_timeout_ms,status,created_at,activated_at)"
                + " VALUES (?,?,1,'BUILTIN','OPENCODE_ZEN','OPENAI_CHAT_COMPLETIONS',"
                + "?,'/chat/completions','/models','BEARER','DIRECT_ONLY',5000,60000,'ACTIVE',"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId, accountId, LEGACY_ZEN_BASE);

        // Pre-V30 authority is exactly the legacy discovery origin V24 seeded.
        assertThat(catalogBaseUrl()).isEqualTo(LEGACY_ZEN_BASE);
        assertThat(activeProfileBaseUrl()).isEqualTo(LEGACY_ZEN_BASE);

        migrate(url, null);
    }

    @Test
    void catalogAndActiveProfileMoveToTheCliInferenceBase() {
        assertThat(catalogBaseUrl()).isEqualTo(INFERENCE_BASE);
        assertThat(activeProfileBaseUrl()).isEqualTo(INFERENCE_BASE);
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
}
