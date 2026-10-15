package com.aicostops.gateway.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guards the fixture's generated-id derivation against the shared connection
 * pool. {@code GatewayTestFixture} derives every AUTO_INCREMENT id from
 * {@code LAST_INSERT_ID()}, which is connection-scoped: read through a pooled
 * {@code JdbcTemplate} (one borrow/release per statement) the value can be
 * answered by a different connection than the insert that produced it.
 *
 * <p>A corrupted generated id either violates a foreign key (an intermittent
 * {@code DataIntegrityViolationException} on {@code billing_period.org_id}) or,
 * for {@code gateway_credential.project_id} / {@code financial_scope_id}, which
 * carry no foreign key, is persisted silently and later surfaces as a 403
 * {@code GATEWAY_FORBIDDEN "Project is not active"}. Both were observed from a
 * full gateway run. The fixture now pins one physical connection per seed.
 */
@SpringBootTest
@Tag("integration")
class GatewaySeedIdDerivationIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void everySeededReferenceResolvesInsideItsOwnOrganization() {
        var env = GatewayTestFixture.seed(jdbc, "derive-" + System.nanoTime(), HMAC_KEY, rawKey());

        assertThat(count("SELECT COUNT(*) FROM project WHERE id=? AND org_id=? AND status='ACTIVE'",
                env.projectId(), env.orgId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM service_identity WHERE id=? AND org_id=? AND status='ACTIVE'",
                env.serviceIdentityId(), env.orgId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM billing_period WHERE id=? AND org_id=? AND status='OPEN'",
                env.periodId(), env.orgId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM provider_account WHERE id=? AND org_id=?",
                env.providerAccountId(), env.orgId())).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM pricing_version"
                        + " WHERE id=? AND org_id=? AND provider_account_id=? AND provider_model_id=?",
                env.pricingVersionId(), env.orgId(), env.providerAccountId(), env.providerModelId()))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM routing_policy_candidate"
                        + " WHERE org_id=? AND provider_account_id=? AND provider_model_id=?",
                env.orgId(), env.providerAccountId(), env.providerModelId())).isEqualTo(1);

        // The credential projection is what GatewayAuthenticationManager
        // validates: project_id and financial_scope_id have no foreign key, so a
        // corrupted derived id is only caught here (or by the 403 at request time).
        var credential = jdbc.queryForMap(
                "SELECT org_id, project_id, service_identity_id, financial_scope_id"
                        + " FROM gateway_credential WHERE credential_prefix=?",
                env.prefix());
        assertThat(number(credential.get("org_id"))).isEqualTo(env.orgId());
        assertThat(number(credential.get("project_id"))).isEqualTo(env.projectId());
        assertThat(number(credential.get("service_identity_id"))).isEqualTo(env.serviceIdentityId());
        assertThat(number(credential.get("financial_scope_id"))).isEqualTo(env.projectId());
    }

    @Test
    void idsStayConsistentWhileThePoolIsConcurrentlyBorrowedByOtherThreads() throws Exception {
        // Keeps more borrowers queued than the two configured connections, so the
        // pool actually hands a released connection to a waiter. That handoff is
        // what let a derived id be read off a different connection before the
        // fixture pinned one connection per seed.
        var stop = new AtomicBoolean(false);
        var borrowers = new ArrayList<Thread>();
        for (var index = 0; index < 3; index++) {
            var borrower = new Thread(() -> {
                while (!stop.get()) {
                    try {
                        jdbc.update("""
                                INSERT INTO organization(name,slug,status,settings_json,created_at,updated_at)
                                VALUES (?,?,'ACTIVE',NULL,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                                """, "pool-pressure", "gw-pool-pressure-" + System.nanoTime());
                    } catch (RuntimeException ignored) {
                        // Contention on a two-connection pool may reject a borrow;
                        // the fixture's own consistency is what this test asserts.
                    }
                }
            }, "gateway-fixture-pool-pressure-" + index);
            borrower.start();
            borrowers.add(borrower);
        }

        try {
            for (var iteration = 0; iteration < 10; iteration++) {
                // credential_prefix is UNIQUE per container: every seed needs its
                // own key, exactly as the per-class tests do with unique suffixes.
                var env = GatewayTestFixture.seed(jdbc, "pressure-" + iteration + "-" + System.nanoTime(),
                        HMAC_KEY, uniqueRawKey(iteration));
                assertThat(count("SELECT COUNT(*) FROM project WHERE id=? AND org_id=? AND status='ACTIVE'",
                        env.projectId(), env.orgId())).isEqualTo(1);
                assertThat(count("SELECT COUNT(*) FROM service_identity"
                                + " WHERE id=? AND org_id=? AND status='ACTIVE'",
                        env.serviceIdentityId(), env.orgId())).isEqualTo(1);
                assertThat(count("SELECT COUNT(*) FROM billing_period WHERE id=? AND org_id=?",
                        env.periodId(), env.orgId())).isEqualTo(1);
            }
        } finally {
            stop.set(true);
            for (var borrower : borrowers) {
                borrower.join(10_000L);
            }
        }
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }

    private static String uniqueRawKey(int iteration) {
        var prefix = String.format("%012d", System.nanoTime() % 1_000_000_000_000L + iteration);
        return "aic_" + prefix + "_" + "A".repeat(43);
    }
}
