package com.aicostops.providerhub;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Proves the V23 -&gt; V24 endpoint-authority migration: routable accounts
 * keep their exact endpoint through an ACTIVE v1 profile, catalog seeds land,
 * single-ACTIVE and org-private namespaces hold on real MySQL.
 */
@SpringBootTest(properties = "spring.flyway.enabled=false")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V24ProviderHubMigrationIntegrationTest {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("aicostops_v24_test")
            .withUsername("aicostops")
            .withPassword("aicostops-test-only")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withConnectTimeoutSeconds(60);

    static {
        MYSQL.start();
    }

    @Autowired
    private JdbcTemplate jdbc;

    private long orgId;
    private long mimoAccountId;

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeAll
    void migrateToV23SeedLegacyThenV24() {
        var dataSource = MYSQL.getJdbcUrl() + "?serverTimezone=UTC";
        Flyway.configure()
                .dataSource(dataSource, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("23")
                .load()
                .migrate();
        orgId = insertId("INSERT INTO organization(name,slug,status,created_at,updated_at)"
                + " VALUES ('V24 Acme','v24-acme','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        jdbc.update("INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,"
                + "capabilities_json,created_at,updated_at) VALUES"
                + " ('MIMO','MiMo','MIMO','https://mimo.example.test/v1','ACTIVE',JSON_OBJECT(),"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
                + " ON DUPLICATE KEY UPDATE base_url=VALUES(base_url)");
        var modelId = insertId("INSERT INTO model_catalog(model_key,name,status,capabilities_json,"
                + "default_max_output_tokens,max_output_tokens,created_at,updated_at)"
                + " VALUES ('v24-chat','V24 Chat','ACTIVE',JSON_OBJECT(),1024,8192,"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        var providerModelId = insertId("INSERT INTO provider_model(provider_code,model_id,"
                + "provider_model_name,status,routing_eligible,capabilities_json,created_at,updated_at)"
                + " VALUES ('MIMO',?,'mimo-v24','ACTIVE',TRUE,JSON_OBJECT(),"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", modelId);
        mimoAccountId = insertId("INSERT INTO provider_account(org_id,provider_code,display_name,"
                + "status,created_at,updated_at)"
                + " VALUES (?,'MIMO','MiMo Main','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId);
        jdbc.update("INSERT INTO provider_credential(org_id,provider_account_id,credential_type,"
                + "ciphertext,nonce,encryption_key_version,status,created_at)"
                + " VALUES (?,?, 'API_KEY',?,?,1,'ACTIVE',UTC_TIMESTAMP(6))",
                orgId, mimoAccountId, new byte[] {1}, new byte[12]);
        jdbc.update("INSERT INTO pricing_version(org_id,provider_account_id,provider_model_id,"
                + "version,currency,effective_from,status,created_at,activated_at)"
                + " VALUES (?,?,?,1,'USD',DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY),'ACTIVE',"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId, mimoAccountId, providerModelId);
        var lonelyAccountId = insertId("INSERT INTO provider_account(org_id,provider_code,"
                + "display_name,status,created_at,updated_at)"
                + " VALUES (?,'MIMO','Lonely','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId);

        Flyway.configure()
                .dataSource(dataSource, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("24")
                .load()
                .migrate();
    }

    @Test
    void routableAccountKeepsExactEndpointThroughActiveV1() {
        var profiles = jdbc.queryForList("SELECT version,status,base_url,protocol_code,network_policy"
                + " FROM provider_connection_profile WHERE org_id=? AND provider_account_id=?",
                orgId, mimoAccountId);
        assertThat(profiles).singleElement().satisfies(row -> {
            assertThat(row.get("version")).isEqualTo(1);
            assertThat(row.get("status")).isEqualTo("ACTIVE");
            assertThat(row.get("base_url")).isEqualTo("https://mimo.example.test/v1");
            assertThat(row.get("protocol_code")).isEqualTo("MIMO_CHAT_COMPLETIONS");
            assertThat(row.get("network_policy")).isEqualTo("DIRECT_PUBLIC_ONLY");
        });
    }

    @Test
    void nonRoutableAccountGetsNoProfile() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_connection_profile"
                + " WHERE org_id=? AND provider_account_id=(SELECT id FROM provider_account"
                + " WHERE org_id=? AND display_name='Lonely')",
                Integer.class, orgId, orgId)).isZero();
    }

    @Test
    void catalogSeedsLand() {
        assertThat(jdbc.queryForObject("SELECT base_url FROM provider_catalog"
                + " WHERE provider_code='OPENCODE_ZEN'", String.class))
                .isEqualTo("https://opencode.ai/zen/v1");
        assertThat(jdbc.queryForObject("SELECT status FROM provider_catalog"
                + " WHERE provider_code='CUSTOM_OPENAI_COMPATIBLE'", String.class))
                .isEqualTo("DISABLED");
    }

    @Test
    void secondActiveProfileIsRejected() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO provider_connection_profile(org_id,"
                + "provider_account_id,version,connection_kind,protocol_code,base_url,completion_path,"
                + "auth_type,network_policy,connect_timeout_ms,response_timeout_ms,status,created_at,"
                + "activated_at) VALUES (?,?,2,'CUSTOM','MIMO_CHAT_COMPLETIONS','https://mimo.example.test/v1',"
                + "'/chat/completions','BEARER','DIRECT_PUBLIC_ONLY',5000,60000,'ACTIVE',"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId, mimoAccountId))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void sameWireModelCoexistsAcrossTwoCustomAccounts() {
        var accountA = insertId("INSERT INTO provider_account(org_id,provider_code,display_name,"
                + "status,created_at,updated_at) VALUES (?,'CUSTOM_OPENAI_COMPATIBLE','Custom A',"
                + "'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId);
        var accountB = insertId("INSERT INTO provider_account(org_id,provider_code,display_name,"
                + "status,created_at,updated_at) VALUES (?,'CUSTOM_OPENAI_COMPATIBLE','Custom B',"
                + "'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId);
        var logicalId = insertId("INSERT INTO model_catalog(model_key,owner_org_id,name,status,"
                + "capabilities_json,default_max_output_tokens,max_output_tokens,created_at,updated_at)"
                + " VALUES ('model-x',?,'Model X','ACTIVE',JSON_OBJECT(),1024,8192,"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId);
        jdbc.update("INSERT INTO provider_model(provider_code,owner_org_id,provider_account_id,"
                + "model_id,provider_model_name,status,routing_eligible,capabilities_json,created_at,"
                + "updated_at) VALUES ('CUSTOM_OPENAI_COMPATIBLE',?,?,?, 'model-x','ACTIVE',TRUE,"
                + "JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId, accountA, logicalId);
        jdbc.update("INSERT INTO provider_model(provider_code,owner_org_id,provider_account_id,"
                + "model_id,provider_model_name,status,routing_eligible,capabilities_json,created_at,"
                + "updated_at) VALUES ('CUSTOM_OPENAI_COMPATIBLE',?,?,?, 'model-x','ACTIVE',TRUE,"
                + "JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", orgId, accountB, logicalId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM provider_model"
                + " WHERE owner_org_id=? AND provider_model_name='model-x'", Integer.class, orgId))
                .isEqualTo(2);
    }

    @Test
    void routeAttemptLineageColumnExistsAndIsNullableForHistory() {
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.columns"
                + " WHERE table_schema=DATABASE() AND table_name='gateway_route_attempt'"
                + " AND column_name='provider_connection_profile_id'", String.class))
                .isEqualTo("YES");
    }

    @Test
    void v25IntelligenceAdvisorSchemaAndPermissionsExist() {
        var dataSource = MYSQL.getJdbcUrl() + "?serverTimezone=UTC";
        Flyway.configure()
                .dataSource(dataSource, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("25")
                .load()
                .migrate();
        for (var table : new String[] {"cost_intelligence_run", "cost_anomaly",
                "cost_forecast_snapshot", "savings_recommendation", "advisor_profile",
                "advisor_inference_job", "advisor_inference_attempt", "advisor_explanation"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables"
                    + " WHERE table_schema=DATABASE() AND table_name=?", Integer.class, table))
                    .as(table).isOne();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM permission"
                + " WHERE code IN ('AI_ADVISOR_USE','AI_ADVISOR_MANAGE')", Integer.class))
                .isEqualTo(2);
    }

    private long insertId(String sql, Object... args) {
        jdbc.update(sql, args);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
