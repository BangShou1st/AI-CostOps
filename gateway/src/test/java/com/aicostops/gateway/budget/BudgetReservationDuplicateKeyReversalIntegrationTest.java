package com.aicostops.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionCommand;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionOutcome;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionResult;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.request.RequestIdentityService;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Real-MySQL proof of the HC-04 A1 DuplicateKeyException reversal guard: when
 * two identical admissions pass the pre-TX1 fast-path together and both
 * increment {@code budget.reserved_amount} in TX1, the loser's
 * {@code DuplicateKeyException} reverts its {@code +amount} increment under the
 * held Budget X lock before converging via {@code replayExisting}.
 */
@SpringBootTest
@Tag("integration")
class BudgetReservationDuplicateKeyReversalIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Autowired
    private BudgetReservationService reservationService;

    @Autowired
    private GatewayRequestMapper requestMapper;

    @Autowired
    private RequestIdentityService identityService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RaceState raceState;

    private SeededEnv env;

    @BeforeEach
    void setUp() {
        raceState.reset();
        env = GatewayTestFixture.seed(jdbc, "hc04-dupkey-" + System.nanoTime(), HMAC_KEY, rawKey());
    }

    @AfterEach
    void clean() {
        raceState.reset();
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void duplicateKeyAfterBudgetIncrementRevertsIncrementAndConvergesOnOneHold() throws Exception {
        var ids = insertValidatedRequest(env, "hc04-dup-reversal");
        var command = new AdmissionCommand(
                principal(env), ids.requestId(), ids.attemptId(), env.periodId(),
                env.pricingVersionId(), "USD", 8192L, -1L);

        raceState.armed.set(true);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var firstFuture = pool.submit(() -> reservationService.admit(command).block());
            var secondFuture = pool.submit(() -> reservationService.admit(command).block());

            AdmissionResult first = firstFuture.get(20, TimeUnit.SECONDS);
            AdmissionResult second = secondFuture.get(20, TimeUnit.SECONDS);

            assertThat(first.outcome()).isEqualTo(AdmissionOutcome.RESERVED);
            assertThat(second.outcome()).isEqualTo(AdmissionOutcome.RESERVED);
            assertThat(first.reservationId()).isEqualTo(second.reservationId());
            assertThat(raceState.duplicateKeyHits.get()).isEqualTo(1);
            assertThat(raceState.reversals.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        var expected = new BigDecimal("31.94880000");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_reservation WHERE org_id=? AND status='ACTIVE'",
                Integer.class, env.orgId())).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT reserved_amount FROM budget WHERE id=?",
                BigDecimal.class, env.budgetId())).isEqualByComparingTo(expected);
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_amount), 0) FROM budget_reservation
                WHERE org_id=? AND budget_id=? AND status IN ('ACTIVE','PENDING_HOLD')
                """, BigDecimal.class, env.orgId(), env.budgetId())).isEqualByComparingTo(expected);
    }

    private RequestIds insertValidatedRequest(SeededEnv env, String idempotencyKey) {
        var idemDigest = identityService.idempotencyKeyDigest(idempotencyKey);
        var fingerprint = identityService.requestFingerprint(
                ("{\"model\":\"dup-" + idempotencyKey + "\"}").getBytes(StandardCharsets.UTF_8));
        requestMapper.insertRequest(new GatewayRequestMapper.GatewayRequestInsert(
                env.orgId(), identityService.newPublicRequestId(), env.credentialId(), "SERVICE",
                null, env.serviceIdentityId(), env.projectId(), "PROJECT", env.projectId(),
                env.modelId(), idemDigest, fingerprint));
        var requestId = jdbc.queryForObject("""
                SELECT id FROM gateway_request WHERE org_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, env.orgId());
        requestMapper.insertRouteAttempt(new GatewayRequestMapper.RouteAttemptInsert(
                env.orgId(), requestId, identityService.newRouteDecisionId(),
                env.providerAccountId(), env.providerModelId(), env.pricingVersionId()));
        var attemptId = jdbc.queryForObject("""
                SELECT id FROM gateway_route_attempt WHERE request_id=? ORDER BY id DESC LIMIT 1
                """, Long.class, requestId);
        return new RequestIds(requestId, attemptId);
    }

    private static GatewayPrincipal principal(SeededEnv env) {
        return new GatewayPrincipal(
                env.credentialId(), env.orgId(), env.projectId(), "SERVICE", null,
                env.serviceIdentityId(), "PROJECT", env.projectId(), "REQUIRED");
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }

    private record RequestIds(long requestId, long attemptId) {
    }

    @TestConfiguration
    static class RaceMapperConfiguration {

        @Bean
        @Primary
        BudgetReservationMapper racingBudgetReservationMapper(
                @Qualifier("budgetReservationMapper") BudgetReservationMapper delegate,
                RaceState state) {
            return (BudgetReservationMapper) Proxy.newProxyInstance(
                    BudgetReservationMapper.class.getClassLoader(),
                    new Class<?>[]{BudgetReservationMapper.class},
                    (proxy, method, args) -> {
                        if ("findPricingRates".equals(method.getName()) && state.armed.get()) {
                            state.arrived.countDown();
                            if (!state.arrived.await(20, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("Duplicate-key race barrier timed out");
                            }
                        }
                        try {
                            Object result = method.invoke(delegate, args);
                            if ("releaseReservedBudgetAmount".equals(method.getName()) && state.armed.get()) {
                                state.reversals.incrementAndGet();
                            }
                            return result;
                        } catch (InvocationTargetException ex) {
                            Throwable target = ex.getTargetException();
                            if ("insertActiveReservation".equals(method.getName())
                                    && target instanceof DuplicateKeyException
                                    && state.armed.get()) {
                                state.duplicateKeyHits.incrementAndGet();
                            }
                            throw target;
                        }
                    });
        }

        @Bean
        RaceState raceState() {
            return new RaceState();
        }
    }

    static final class RaceState {
        final AtomicBoolean armed = new AtomicBoolean(false);
        volatile CountDownLatch arrived = new CountDownLatch(2);
        final AtomicInteger duplicateKeyHits = new AtomicInteger();
        final AtomicInteger reversals = new AtomicInteger();

        void reset() {
            armed.set(false);
            arrived = new CountDownLatch(2);
            duplicateKeyHits.set(0);
            reversals.set(0);
        }
    }
}
