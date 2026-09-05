package com.aicostops.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Runs the real V1-V22 migration sequence with legacy M11 data present before
 * V22. This deliberately exercises the INSERT ... SELECT backfill instead of
 * inspecting the V22 script or a schema-only projection.
 */
@SpringBootTest(properties = "spring.flyway.enabled=false")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V22LegacyRoutingBackfillIntegrationTest {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("aicostops_v22_backfill_test")
            .withUsername("aicostops")
            .withPassword("aicostops-test-only")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withConnectTimeoutSeconds(60);

    private static final List<String> CASES = List.of("expired", "future", "current");

    static {
        MYSQL.start();
    }

    @Autowired
    private JdbcTemplate jdbc;

    private long legacyModelId;
    private long legacyProviderModelId;

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeAll
    void applyV1ToV22AroundLegacyData() {
        var dataSource = MYSQL.getJdbcUrl() + "?serverTimezone=UTC";
        Flyway.configure()
                .dataSource(dataSource, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("21")
                .load()
                .migrate();

        jdbc.update("""
                INSERT INTO provider_catalog(
                  provider_code,name,adapter_code,base_url,status,capabilities_json,
                  created_at,updated_at)
                VALUES ('MIMO','MiMo','MIMO','https://example.invalid/v1','ACTIVE',
                        JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        legacyModelId = insertId("""
                INSERT INTO model_catalog(
                  model_key,name,status,capabilities_json,default_max_output_tokens,
                  max_output_tokens,created_at,updated_at)
                VALUES ('v22-backfill-model','V22 Backfill Model','ACTIVE',JSON_OBJECT(),
                        16,128,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """);
        legacyProviderModelId = insertId("""
                INSERT INTO provider_model(
                  provider_code,model_id,provider_model_name,status,routing_eligible,
                  capabilities_json,created_at,updated_at)
                VALUES ('MIMO',?,'mimo-v22-backfill','ACTIVE',TRUE,JSON_OBJECT(),
                        UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, legacyModelId);
        CASES.forEach(this::insertLegacyMimoRoute);

        Flyway.configure()
                .dataSource(dataSource, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("22")
                .load()
                .migrate();
    }

    @Test
    void backfillOnlyCreatesOneActiveOrgDefaultPolicyAndOneCandidateForCurrentRoute() {
        assertThat(countPolicies("expired")).isZero();
        assertThat(countPolicies("future")).isZero();

        var currentOrgId = orgId("current");
        var currentModelId = modelId();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM routing_policy
                WHERE org_id=? AND project_id IS NULL AND model_id=?
                  AND version=1 AND status='ACTIVE'
                """, Integer.class, currentOrgId, currentModelId)).isOne();

        var policyId = jdbc.queryForObject("""
                SELECT id FROM routing_policy
                WHERE org_id=? AND project_id IS NULL AND model_id=?
                  AND version=1 AND status='ACTIVE'
                """, Long.class, currentOrgId, currentModelId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM routing_policy_candidate
                WHERE org_id=? AND routing_policy_id=? AND status='ACTIVE'
                """, Integer.class, currentOrgId, policyId)).isOne();

        var expectedAccountId = accountId("current");
        var expectedProviderModelId = providerModelId();
        var candidate = jdbc.queryForList("""
                SELECT provider_account_id, provider_model_id, priority
                FROM routing_policy_candidate
                WHERE org_id=? AND routing_policy_id=? AND status='ACTIVE'
                """, currentOrgId, policyId);
        assertThat(candidate).singleElement().satisfies(row -> {
            assertThat(row.get("provider_account_id")).isEqualTo(expectedAccountId);
            assertThat(row.get("provider_model_id")).isEqualTo(expectedProviderModelId);
            assertThat(row.get("priority")).isEqualTo(0);
        });
    }

    private void insertLegacyMimoRoute(String state) {
        var suffix = "v22-" + state;
        var orgId = insertId("""
                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, "V22 " + suffix, suffix);
        var accountId = insertId("""
                INSERT INTO provider_account(
                  org_id,provider_code,display_name,external_account_ref,status,metadata_json,
                  created_at,updated_at)
                VALUES (?,?,?,'legacy-ref','ACTIVE',JSON_OBJECT(),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, orgId, "MIMO", "Legacy " + suffix);
        jdbc.update("""
                INSERT INTO provider_credential(
                  org_id,provider_account_id,credential_type,ciphertext,nonce,
                  encryption_key_version,safe_label,status,predecessor_credential_id,
                  created_at,rotated_at,revoked_at)
                VALUES (?,?, 'API_KEY',?,?,1,'legacy','ACTIVE',NULL,
                  UTC_TIMESTAMP(6),NULL,NULL)
                """, orgId, accountId, new byte[] {1}, new byte[12]);

        var effectiveWindow = switch (state) {
            case "expired" -> "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 DAY),"
                    + "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY)";
            case "future" -> "DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 DAY),NULL";
            case "current" -> "DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY),NULL";
            default -> throw new IllegalArgumentException("Unknown test state: " + state);
        };
        jdbc.update("""
                INSERT INTO pricing_version(
                  org_id,provider_account_id,provider_model_id,version,currency,
                  effective_from,effective_to,status,created_at,activated_at,retired_at)
                VALUES (?,?,?,?,?,%s,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),NULL)
                """.formatted(effectiveWindow), orgId, accountId, legacyProviderModelId, 1, "USD");

        jdbc.update("""
                INSERT INTO pricing_rate(org_id,pricing_version_id,dimension_code,unit_quantity,unit_price)
                VALUES (?,(SELECT id FROM pricing_version WHERE org_id=? AND provider_account_id=?),
                        'INPUT_TOKEN',1000000,'1.00000000')
                """, orgId, orgId, accountId);
    }

    private int countPolicies(String state) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM routing_policy
                WHERE org_id=? AND model_id=? AND project_id IS NULL
                """, Integer.class, orgId(state), modelId());
    }

    private long orgId(String state) {
        return jdbc.queryForObject("SELECT id FROM organization WHERE slug=?", Long.class, "v22-" + state);
    }

    private long modelId() {
        return legacyModelId;
    }

    private long accountId(String state) {
        return jdbc.queryForObject("""
                SELECT pa.id FROM provider_account pa
                JOIN organization o ON o.id=pa.org_id
                WHERE o.slug=?
                """, Long.class, "v22-" + state);
    }

    private long providerModelId() {
        return legacyProviderModelId;
    }

    private long insertId(String sql, Object... args) {
        jdbc.update(sql, args);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
