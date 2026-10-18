package com.aicostops.providerhub;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
 * Proves V32 registers the second built-in Provider (Kilo Gateway) on real MySQL and keeps the exact
 * catalog row the Gateway projects {@code adapter_code} from.
 *
 * <p>V24 is immutable evidence and seeded only OPENCODE_ZEN / CUSTOM_OPENAI_COMPATIBLE, so the Kilo
 * catalog row arrives in a new forward migration. The row must be ACTIVE with an {@code adapter_code}
 * identical to the provider code, otherwise the Gateway cannot dispatch the KILO_GATEWAY adapter.
 */
@SpringBootTest(properties = "spring.flyway.enabled=false")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V32KiloGatewayCatalogMigrationIntegrationTest {

    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("aicostops_v32_test")
            .withUsername("aicostops")
            .withPassword("aicostops-test-only")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withConnectTimeoutSeconds(60);

    static {
        MYSQL.start();
    }

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeAll
    void migrateToV31ThenV32() {
        var dataSource = MYSQL.getJdbcUrl() + "?serverTimezone=UTC";
        Flyway.configure()
                .dataSource(dataSource, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("31")
                .load()
                .migrate();
        // V31 must not know about Kilo: the registration is a V32 forward migration.
        assertThat(count("SELECT COUNT(*) FROM provider_catalog WHERE provider_code='KILO_GATEWAY'"))
                .isZero();
        Flyway.configure()
                .dataSource(dataSource, MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("32")
                .load()
                .migrate();
    }

    @Test
    void v32RegistersTheBuiltInKiloGatewayCatalogRow() {
        assertThat(jdbc.queryForObject("SELECT adapter_code FROM provider_catalog"
                + " WHERE provider_code='KILO_GATEWAY'", String.class)).isEqualTo("KILO_GATEWAY");
        assertThat(jdbc.queryForObject("SELECT name FROM provider_catalog"
                + " WHERE provider_code='KILO_GATEWAY'", String.class)).isEqualTo("Kilo Gateway");
        assertThat(jdbc.queryForObject("SELECT status FROM provider_catalog"
                + " WHERE provider_code='KILO_GATEWAY'", String.class)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT base_url FROM provider_catalog"
                + " WHERE provider_code='KILO_GATEWAY'", String.class))
                .isEqualTo("https://ai-gateway.kilo.ai/api/v1");
    }

    @Test
    void theKiloSeedIsIdempotentAndKeepsOpenCodeZen() {
        assertThat(count("SELECT COUNT(*) FROM provider_catalog WHERE provider_code='OPENCODE_ZEN'"))
                .isEqualTo(1);
        // The exact V32 statement, replayed: ON DUPLICATE KEY UPDATE must keep a single row.
        jdbc.update("INSERT INTO provider_catalog(provider_code,name,adapter_code,base_url,status,"
                + "capabilities_json,created_at,updated_at) VALUES ('KILO_GATEWAY','Kilo Gateway',"
                + "'KILO_GATEWAY','https://ai-gateway.kilo.ai/api/v1','ACTIVE',"
                + "CAST('{\"protocols\": [\"OPENAI_CHAT_COMPLETIONS\"]}' AS JSON),"
                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"
                + " ON DUPLICATE KEY UPDATE name=VALUES(name), adapter_code=VALUES(adapter_code),"
                + " base_url=VALUES(base_url), status=VALUES(status),"
                + " capabilities_json=VALUES(capabilities_json), updated_at=UTC_TIMESTAMP(6)");
        assertThat(count("SELECT COUNT(*) FROM provider_catalog WHERE provider_code='KILO_GATEWAY'"))
                .isEqualTo(1);
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }
}
