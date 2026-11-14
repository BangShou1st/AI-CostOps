package com.aicostops.gatewayadmin.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gatewayadmin.security.GatewayKeyCodec;
import com.aicostops.testsupport.M2DatabaseCleaner;
import com.aicostops.testsupport.MySqlContainerSupport;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * AIC-095 dev provisioning on real MySQL: the dev profile boots the M11
 * Gateway runtime projection once, idempotently, without ever persisting raw
 * secrets.
 */
@SpringBootTest(
        properties = {
            "spring.profiles.active=dev",
            "aicostops.gateway.dev-bootstrap-enabled=true",
            "aicostops.gateway.dev-raw-key=" + DevGatewayBootstrapIntegrationTest.RAW_KEY,
            "aicostops.gateway.credential-hmac-key-v1=" + DevGatewayBootstrapIntegrationTest.HMAC_KEY,
            "aicostops.gateway.request-hmac-key-v1=" + DevGatewayBootstrapIntegrationTest.HMAC_KEY,
        })
@Tag("integration")
class DevGatewayBootstrapIntegrationTest extends MySqlContainerSupport {

    static final String RAW_KEY = "aic_0123456789ab_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    static final String HMAC_KEY =
            "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DevGatewayBootstrap bootstrap;

    @AfterEach
    void tearDown() {
        M2DatabaseCleaner.clean(jdbc);
    }

    @BeforeEach
    void ensureProvisioned() {
        // Every test may run after another test's cleanup; the bootstrap is
        // idempotent, so re-running it restores the exact provisioned state.
        bootstrap.run(null);
    }

    @Test
    void seedsExactlyOneOfEachGatewayRuntimeRow() {
        var orgId = organizationId();
        assertThat(count("SELECT COUNT(*) FROM service_identity WHERE org_id=? AND code='aicostops-gateway-dev'",
                orgId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM gateway_credential WHERE credential_prefix='0123456789ab'",
                null)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM gateway_credential_model", null)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM model_catalog WHERE model_key='default-chat'", null))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM provider_account WHERE org_id=?", orgId)).isZero();
        assertThat(count("SELECT COUNT(*) FROM provider_connection_profile WHERE org_id=?", orgId)).isZero();
        assertThat(bootstrapEnabledCredential(orgId)).isTrue();
    }

    @Test
    void bootstrapIsIdempotent() {
        var snapshot = runtimeSnapshot();
        bootstrap.run(null);
        var after = runtimeSnapshot();
        assertThat(after).isEqualTo(snapshot);
    }

    @Test
    void gatewayCredentialStoresKeyedDigestNotRawSecret() {
        var secret = RAW_KEY.substring(RAW_KEY.lastIndexOf('_') + 1);
        var expected = Hex.encodeHexString(GatewayKeyCodec.digestSecret(secret, HMAC_KEY));
        var stored = jdbc.queryForObject("""
                SELECT HEX(secret_digest) FROM gateway_credential WHERE credential_prefix='0123456789ab'
                """, String.class);

        assertThat(stored).isEqualToIgnoringCase(expected);
        assertThat(stored).isNotEqualTo(Hex.encodeHexString(secret.getBytes(StandardCharsets.UTF_8)));
        assertThat(stored).isNotEqualTo(Hex.encodeHexString(RAW_KEY.getBytes(StandardCharsets.UTF_8)));
    }

    private int count(String sql, Object orgId) {
        if (orgId == null) {
            return jdbc.queryForObject(sql, Integer.class);
        }
        return jdbc.queryForObject(sql, Integer.class, orgId);
    }

    private long organizationId() {
        return jdbc.queryForObject("""
                SELECT id FROM organization WHERE slug='local-dev' AND status='ACTIVE'
                """, Long.class);
    }

    private boolean bootstrapEnabledCredential(long orgId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM gateway_credential gc
                JOIN service_identity si ON si.id=gc.service_identity_id
                WHERE gc.org_id=? AND gc.status='ACTIVE'
                  AND gc.budget_enforcement_mode='OPTIONAL'
                  AND si.code='aicostops-gateway-dev'
                """, Integer.class, orgId) == 1;
    }

    private java.util.Map<String, Object> runtimeSnapshot() {
        return java.util.Map.of(
                "service", count("SELECT COUNT(*) FROM service_identity WHERE code='aicostops-gateway-dev'", null),
                "credential", count("SELECT COUNT(*) FROM gateway_credential", null),
                "credModel", count("SELECT COUNT(*) FROM gateway_credential_model", null),
                "modelCatalog", count("SELECT COUNT(*) FROM model_catalog WHERE model_key='default-chat'", null),
                "providerAccount", count("SELECT COUNT(*) FROM provider_account", null),
                "providerProfile", count("SELECT COUNT(*) FROM provider_connection_profile", null));
    }

    /** Minimal lowercase hex codec matching MySQL HEX(). */
    private static final class Hex {
        static String encodeHexString(byte[] bytes) {
            var builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        }
    }
}
