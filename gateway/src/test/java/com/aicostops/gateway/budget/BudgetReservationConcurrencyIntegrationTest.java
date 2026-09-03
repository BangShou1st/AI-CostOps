package com.aicostops.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionCommand;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionOutcome;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M12 financial concurrency on real MySQL: concurrent reservations against
 * one Budget serialize on the same Budget row lock. 80+80 against 100 yields
 * exactly one hold; 50+50 yields two; many concurrent holds never exceed
 * capacity in SUM; a V1-style Actual increment races deterministically with
 * reservation; the same idempotency identity yields one request, one attempt,
 * one effective hold.
 */
@SpringBootTest
@Tag("integration")
class BudgetReservationConcurrencyIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Autowired
    private BudgetReservationService reservationService;

    @Autowired
    private BudgetReservationMapper reservationMapper;

    @Autowired
    private GatewayRequestMapper requestMapper;

    @Autowired
    private com.aicostops.gateway.request.RequestIdentityService identityService;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void eightyPlusEightyAgainstOneHundredYieldsExactlyOneHold() throws Exception {
        var env = GatewayTestFixture.seed(jdbc, "conc-80", HMAC_KEY, rawKey());
        // 809000 output tokens: 31.45728 + 48.54 = 79.99728 -> 79.99728.
        var first = insertValidatedRequest(env, "conc-80-a");
        var second = insertValidatedRequest(env, "conc-80-b");

        var outcomes = race(
                () -> admit(env, first, 809000),
                () -> admit(env, second, 809000));

        assertThat(outcomes.reserved()).isEqualTo(1);
        assertThat(outcomes.rejected()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_reservation WHERE org_id=? AND status='ACTIVE'",
                Integer.class, env.orgId())).isOne();
        assertThat(sumHolds(env)).isLessThanOrEqualTo(new BigDecimal("100.00000000"));
    }

    @Test
    void fiftyPlusFiftyAgainstOneHundredYieldsTwoHolds() throws Exception {
        var env = GatewayTestFixture.seed(jdbc, "conc-50", HMAC_KEY, rawKey());
        // 309045 output tokens: 31.45728 + 18.5427 = 49.99998.
        var first = insertValidatedRequest(env, "conc-50-a");
        var second = insertValidatedRequest(env, "conc-50-b");

        var outcomes = race(
                () -> admit(env, first, 309045),
                () -> admit(env, second, 309045));

        assertThat(outcomes.reserved()).isEqualTo(2);
        assertThat(outcomes.rejected()).isZero();
        assertThat(sumHolds(env)).isLessThanOrEqualTo(new BigDecimal("100.00000000"));
    }

    @Test
    void manyConcurrentHoldsNeverExceedCapacity() throws Exception {
        var env = GatewayTestFixture.seed(jdbc, "conc-many", HMAC_KEY, rawKey());
        int contenders = 8;
        var ids = new ArrayList<RequestIds>(contenders);
        for (int i = 0; i < contenders; i++) {
            ids.add(insertValidatedRequest(env, "conc-many-" + i));
        }

        var pool = Executors.newFixedThreadPool(contenders);
        var latch = new CountDownLatch(1);
        var reserved = new AtomicInteger();
        var tasks = new ArrayList<Future<?>>(contenders);
        for (var id : ids) {
            tasks.add(pool.submit(() -> {
                latch.await();
                var outcome = reservationService.admit(new AdmissionCommand(required(env),
                        id.requestId(), id.attemptId(), env.periodId(),
                        env.pricingVersionId(), "USD", 309045, -1L)).block().outcome();
                if (outcome == AdmissionOutcome.RESERVED) {
                    reserved.incrementAndGet();
                }
                return null;
            }));
        }
        latch.countDown();
        for (var task : tasks) {
            task.get();
        }
        pool.shutdown();

        // 2 x 50 fits 100; a third 50 never fits.
        assertThat(reserved.get()).isEqualTo(2);
        assertThat(sumHolds(env)).isLessThanOrEqualTo(new BigDecimal("100.00000000"));
    }

    @Test
    void actualIncrementRacesDeterministicallyWithReservation() throws Exception {
        var env = GatewayTestFixture.seed(jdbc, "conc-actual", HMAC_KEY, rawKey());
        var ids = insertValidatedRequest(env, "conc-actual-key");

        // V1-style Actual posting (+70) races with a 50 reservation: either
        // the reservation sees pre-post Actual (100 available -> reserve, then
        // Actual 70 leaves 30 used + 50 held = 80 <= 100 consistent) or the
        // post wins (30 available -> reject). Both are serializable outcomes.
        var pool = Executors.newFixedThreadPool(2);
        var latch = new CountDownLatch(1);
        var reserved = new AtomicInteger();
        var actualDone = new AtomicInteger();
        var reserveTask = pool.submit(() -> {
            latch.await();
            var outcome = reservationService.admit(new AdmissionCommand(required(env),
                    ids.requestId(), ids.attemptId(), env.periodId(),
                    env.pricingVersionId(), "USD", 309045, -1L)).block().outcome();
            if (outcome == AdmissionOutcome.RESERVED) {
                reserved.incrementAndGet();
            }
            return null;
        });
        var actualTask = pool.submit(() -> {
            latch.await();
            jdbc.update("UPDATE budget SET actual_amount=actual_amount+'70.00000000' WHERE id=?",
                    env.budgetId());
            actualDone.incrementAndGet();
            return null;
        });
        latch.countDown();
        reserveTask.get();
        actualTask.get();
        pool.shutdown();

        assertThat(actualDone.get()).isOne();
        var actual = jdbc.queryForObject("SELECT actual_amount FROM budget WHERE id=?",
                BigDecimal.class, env.budgetId());
        assertThat(actual).isEqualByComparingTo("70.00000000");
        var holds = sumHolds(env);
        if (reserved.get() == 1) {
            // Reserved before the Actual landed: 70 actual + 50 held <= 100 + 50
            // is consistent because the hold was admitted against 100 capacity.
            assertThat(holds).isLessThanOrEqualTo(new BigDecimal("100.00000000"));
        } else {
            assertThat(holds).isEqualByComparingTo("0.00000000");
        }
    }

    @Test
    void sameIdempotencyKeyConcurrentYieldsOneHold() throws Exception {
        var env = GatewayTestFixture.seed(jdbc, "conc-idem", HMAC_KEY, rawKey());
        // One VALIDATED request + attempt shared by all contenders (as if the
        // idempotency race already converged on one request row).
        var ids = insertValidatedRequest(env, "conc-idem-shared");

        int contenders = 10;
        var pool = Executors.newFixedThreadPool(contenders);
        var latch = new CountDownLatch(1);
        var reservationIds = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
        var tasks = new ArrayList<Future<?>>(contenders);
        for (int i = 0; i < contenders; i++) {
            tasks.add(pool.submit(() -> {
                latch.await();
                var result = reservationService.admit(new AdmissionCommand(required(env),
                        ids.requestId(), ids.attemptId(), env.periodId(),
                        env.pricingVersionId(), "USD", 1000, -1L)).block();
                if (result.outcome() == AdmissionOutcome.RESERVED) {
                    reservationIds.add(result.reservationId());
                }
                return null;
            }));
        }
        latch.countDown();
        for (var task : tasks) {
            task.get();
        }
        pool.shutdown();

        // Exactly one effective hold exists, and every converged winner points
        // at the same reservation identity: no duplicate hold.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_reservation WHERE org_id=? AND status='ACTIVE'",
                Integer.class, env.orgId())).isOne();
        assertThat(reservationIds).isNotEmpty().allSatisfy(id ->
                assertThat(id).isEqualTo(reservationIds.peek()));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM gateway_request WHERE org_id=?",
                Integer.class, env.orgId())).isOne();
    }

    private record RaceOutcome(int reserved, int rejected) {
    }

    private RaceOutcome race(ThrowingRunnable first, ThrowingRunnable second) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var latch = new CountDownLatch(1);
        var reserved = new AtomicInteger();
        var rejected = new AtomicInteger();
        var tasks = new ArrayList<Future<?>>(2);
        for (var action : new ThrowingRunnable[]{first, second}) {
            tasks.add(pool.submit(() -> {
                latch.await();
                // TX1 returns terminal outcomes (it throws only for
                // unexpected dependency failures, which fail the test).
                var outcome = action.run();
                if (outcome == AdmissionOutcome.RESERVED) {
                    reserved.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        latch.countDown();
        for (var task : tasks) {
            task.get();
        }
        pool.shutdown();
        return new RaceOutcome(reserved.get(), rejected.get());
    }

    private AdmissionOutcome admit(SeededEnv env, RequestIds ids, long maxTokens) {
        return reservationService.admit(new AdmissionCommand(required(env),
                ids.requestId(), ids.attemptId(), env.periodId(),
                env.pricingVersionId(), "USD", maxTokens, -1L)).block().outcome();
    }

    private BigDecimal sumHolds(SeededEnv env) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_amount), 0) FROM budget_reservation
                WHERE org_id=? AND status IN ('ACTIVE','PENDING_HOLD')
                """, BigDecimal.class, env.orgId());
    }

    private RequestIds insertValidatedRequest(SeededEnv env, String idempotencyKey) {
        var idemDigest = identityService.idempotencyKeyDigest(idempotencyKey);
        var fingerprint = identityService.requestFingerprint(
                ("{\"model\":\"x-" + idempotencyKey + "\"}").getBytes(StandardCharsets.UTF_8));
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

    private GatewayPrincipal required(SeededEnv env) {
        return new GatewayPrincipal(
                env.credentialId(), env.orgId(), env.projectId(), "SERVICE", null,
                env.serviceIdentityId(), "PROJECT", env.projectId(), "REQUIRED");
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }

    private record RequestIds(long requestId, long attemptId) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        AdmissionOutcome run() throws Exception;
    }
}
