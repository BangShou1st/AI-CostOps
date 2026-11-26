package com.aicostops.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Real-MySQL proof for HC-04 A3 per-budget admission semaphore:
 * <ul>
 *   <li>Semaphore timeouts fail closed with {@code GATEWAY_DEPENDENCY_UNAVAILABLE} without entering TX1.</li>
 *   <li>Hot-budget queue waits do not hold Hikari DB connections, leaving connections free for other budgets.</li>
 *   <li>Permits never leak across normal or exceptional TX1 exit paths, and {@code budget.reserved_amount}
 *       stays equal to {@code SUM(ACTIVE + PENDING_HOLD)}.</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=4",
        "aicostops.gateway.budget-semaphore-permits=2",
        "aicostops.gateway.budget-semaphore-timeout-ms=1200"
})
@Tag("integration")
class BudgetAdmissionSemaphoreIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";

    @Autowired
    private BudgetReservationService reservationService;

    @Autowired
    private BudgetAdmissionSemaphore budgetSemaphore;

    @Autowired
    private GatewayRequestMapper requestMapper;

    @Autowired
    private RequestIdentityService identityService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private SemaphoreTestState state;

    @BeforeEach
    void setUp() {
        state.reset();
        jdbc.queryForObject("SELECT 1", Integer.class);
    }

    @AfterEach
    void clean() {
        state.reset();
        GatewayTestFixture.clean(jdbc);
    }

    @Test
    void semaphoreTimeoutRejectsWithoutEnteringTx1AndNeverLeaksPermits() throws Exception {
        var env = GatewayTestFixture.seed(jdbc, "sem-timeout-" + System.nanoTime(), HMAC_KEY, rawKey());
        jdbc.update("UPDATE budget SET total_amount='500.00000000' WHERE id=?", env.budgetId());
        var req1 = insertValidatedRequest(env, "sem-to-1");
        var req2 = insertValidatedRequest(env, "sem-to-2");
        var req3 = insertValidatedRequest(env, "sem-to-3");
        var req4 = insertValidatedRequest(env, "sem-to-4");

        state.armTx1Park(env.orgId(), 2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<AdmissionResult> f1 = pool.submit(() -> admit(env, req1));
            Future<AdmissionResult> f2 = pool.submit(() -> admit(env, req2));
            assertThat(state.parkedLatch.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(budgetSemaphore.availablePermitsIfCached(env.budgetId())).isZero();

            // 3rd request for the same budget waits on the semaphore, times out,
            // and must fail closed WITHOUT entering TX1.
            assertThatThrownBy(() -> admit(env, req3))
                    .isInstanceOf(GatewayErrorException.class)
                    .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                            .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE));

            assertThat(state.tx1BudgetLookups.get()).isEqualTo(2);
            assertThat(jdbc.queryForObject(
                    "SELECT state FROM gateway_request WHERE id=?", String.class, req3.requestId()))
                    .isEqualTo("VALIDATED");
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM budget_reservation WHERE request_id=?",
                    Integer.class, req3.requestId())).isZero();

            state.releaseTx1.countDown();
            assertThat(f1.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(AdmissionOutcome.RESERVED);
            assertThat(f2.get(10, TimeUnit.SECONDS).outcome()).isEqualTo(AdmissionOutcome.RESERVED);
        } finally {
            state.releaseTx1.countDown();
            pool.shutdownNow();
        }

        assertThat(budgetSemaphore.availablePermitsIfCached(env.budgetId())).isEqualTo(2);
        var fourth = admit(env, req4);
        assertThat(fourth.outcome()).isEqualTo(AdmissionOutcome.RESERVED);
        assertThat(budgetSemaphore.availablePermitsIfCached(env.budgetId())).isEqualTo(2);
        assertBudgetReservedMatchesHolds(env);
    }

    @Test
    void hotBudgetContentionBoundsHikariConnectionsAndDoesNotStarveOtherBudget() throws Exception {
        var env1 = GatewayTestFixture.seed(jdbc, "sem-hot-" + System.nanoTime(), HMAC_KEY, rawKey("0123456789ab"));
        var env2 = GatewayTestFixture.seed(jdbc, "sem-free-" + System.nanoTime(), HMAC_KEY, rawKey("123456789abc"));
        jdbc.update("UPDATE budget SET total_amount='500.00000000' WHERE id IN (?,?)",
                env1.budgetId(), env2.budgetId());

        var hotReqs = new ArrayList<RequestIds>(6);
        for (int i = 0; i < 6; i++) {
            hotReqs.add(insertValidatedRequest(env1, "sem-hot-" + i));
        }
        var otherReq = insertValidatedRequest(env2, "sem-other-0");

        var hikari = dataSource.unwrap(HikariDataSource.class);
        var poolMxBean = hikari.getHikariPoolMXBean();
        assertThat(poolMxBean).isNotNull();

        state.armTx1Park(env1.orgId(), 2);
        var pool = Executors.newFixedThreadPool(6);
        try {
            var hotFutures = new ArrayList<Future<AdmissionResult>>(6);
            hotFutures.add(pool.submit(() -> admit(env1, hotReqs.get(0))));
            hotFutures.add(pool.submit(() -> admit(env1, hotReqs.get(1))));
            assertThat(state.parkedLatch.await(10, TimeUnit.SECONDS)).isTrue();

            // Submit 4 more contenders on env1's hot budget; they must wait on
            // the in-memory semaphore WITHOUT holding any Hikari connections.
            for (int i = 2; i < 6; i++) {
                var req = hotReqs.get(i);
                hotFutures.add(pool.submit(() -> admit(env1, req)));
            }
            awaitCondition(Duration.ofSeconds(5),
                    () -> budgetSemaphore.queueLengthIfCached(env1.budgetId()) == 4);

            // Only the 2 TX1 holders occupy Hikari connections out of max=4.
            assertThat(poolMxBean.getActiveConnections()).isEqualTo(2);
            assertThat(poolMxBean.getThreadsAwaitingConnection()).isZero();

            // Another budget admits immediately using one of the free connections.
            var otherResult = admit(env2, otherReq);
            assertThat(otherResult.outcome()).isEqualTo(AdmissionOutcome.RESERVED);

            state.releaseTx1.countDown();
            for (var future : hotFutures) {
                assertThat(future.get(10, TimeUnit.SECONDS).outcome())
                        .isEqualTo(AdmissionOutcome.RESERVED);
            }
        } finally {
            state.releaseTx1.countDown();
            pool.shutdownNow();
        }

        assertThat(budgetSemaphore.availablePermitsIfCached(env1.budgetId())).isEqualTo(2);
        assertThat(budgetSemaphore.availablePermitsIfCached(env2.budgetId())).isEqualTo(2);
        assertBudgetReservedMatchesHolds(env1);
        assertBudgetReservedMatchesHolds(env2);
    }

    @Test
    void exceptionsInsideTx1NeverLeakPermitsAndPreserveInvariant() {
        var env = GatewayTestFixture.seed(jdbc, "sem-ex-" + System.nanoTime(), HMAC_KEY, rawKey());

        state.failReserve.set(true);
        for (int i = 0; i < 5; i++) {
            var req = insertValidatedRequest(env, "sem-ex-db-" + i);
            assertThatThrownBy(() -> admit(env, req))
                    .isInstanceOf(DataAccessResourceFailureException.class);
        }
        state.failReserve.set(false);
        assertThat(budgetSemaphore.availablePermitsIfCached(env.budgetId())).isEqualTo(2);

        jdbc.update("UPDATE billing_period SET status='CLOSING' WHERE id=?", env.periodId());
        for (int i = 0; i < 3; i++) {
            var req = insertValidatedRequest(env, "sem-ex-closing-" + i);
            assertThatThrownBy(() -> admit(env, req))
                    .isInstanceOf(GatewayErrorException.class)
                    .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                            .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE));
        }
        assertThat(budgetSemaphore.availablePermitsIfCached(env.budgetId())).isEqualTo(2);

        jdbc.update("UPDATE billing_period SET status='OPEN' WHERE id=?", env.periodId());
        var recoveryReq = insertValidatedRequest(env, "sem-ex-ok");
        var result = admit(env, recoveryReq);
        assertThat(result.outcome()).isEqualTo(AdmissionOutcome.RESERVED);
        assertThat(budgetSemaphore.availablePermitsIfCached(env.budgetId())).isEqualTo(2);
        assertBudgetReservedMatchesHolds(env);
    }

    private static void awaitCondition(Duration timeout, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for semaphore queue condition");
            }
            Thread.sleep(10L);
        }
    }

    private AdmissionResult admit(SeededEnv env, RequestIds ids) {
        return reservationService.admit(new AdmissionCommand(
                principal(env), ids.requestId(), ids.attemptId(),
                env.periodId(), env.pricingVersionId(), "USD", 8192L, -1L)).block();
    }

    private void assertBudgetReservedMatchesHolds(SeededEnv env) {
        var budgetReserved = jdbc.queryForObject(
                "SELECT reserved_amount FROM budget WHERE id=?",
                BigDecimal.class, env.budgetId());
        var sumHolds = jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_amount), 0) FROM budget_reservation
                WHERE org_id=? AND budget_id=? AND status IN ('ACTIVE','PENDING_HOLD')
                """, BigDecimal.class, env.orgId(), env.budgetId());
        assertThat(budgetReserved).isEqualByComparingTo(sumHolds);
    }

    private RequestIds insertValidatedRequest(SeededEnv env, String idempotencyKey) {
        var idemDigest = identityService.idempotencyKeyDigest(idempotencyKey);
        var fingerprint = identityService.requestFingerprint(
                ("{\"model\":\"sem-" + idempotencyKey + "\"}").getBytes(StandardCharsets.UTF_8));
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
        return rawKey("0123456789ab");
    }

    private static String rawKey(String prefix) {
        return "aic_" + prefix + "_" + "A".repeat(43);
    }

    private record RequestIds(long requestId, long attemptId) {
    }

    @TestConfiguration
    static class SemaphoreMapperConfiguration {

        @Bean
        @Primary
        BudgetReservationMapper instrumentedBudgetReservationMapper(
                @Qualifier("budgetReservationMapper") BudgetReservationMapper delegate,
                SemaphoreTestState state) {
            return (BudgetReservationMapper) Proxy.newProxyInstance(
                    BudgetReservationMapper.class.getClassLoader(),
                    new Class<?>[]{BudgetReservationMapper.class},
                    (proxy, method, args) -> {
                        if ("reserveBudgetAmount".equals(method.getName()) && state.failReserve.get()) {
                            throw new DataAccessResourceFailureException("Simulated TX1 reserve failure");
                        }
                        Object result;
                        try {
                            result = method.invoke(delegate, args);
                        } catch (InvocationTargetException ex) {
                            throw ex.getTargetException();
                        }
                        if ("selectBudgetByIdentity".equals(method.getName())
                                && TransactionSynchronizationManager.isActualTransactionActive()
                                && args != null
                                && args.length > 0
                                && args[0] instanceof Long orgId
                                && orgId == state.parkOrgId.get()) {
                            state.tx1BudgetLookups.incrementAndGet();
                            if (state.parkRemaining.getAndDecrement() > 0) {
                                state.parkedLatch.countDown();
                                if (!state.releaseTx1.await(15, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException("Test TX1 park timed out");
                                }
                            }
                        }
                        return result;
                    });
        }

        @Bean
        SemaphoreTestState semaphoreTestState() {
            return new SemaphoreTestState();
        }
    }

    static final class SemaphoreTestState {
        final AtomicLong parkOrgId = new AtomicLong(-1L);
        final AtomicInteger parkRemaining = new AtomicInteger(0);
        final AtomicInteger tx1BudgetLookups = new AtomicInteger(0);
        final AtomicBoolean failReserve = new AtomicBoolean(false);
        volatile CountDownLatch parkedLatch = new CountDownLatch(0);
        volatile CountDownLatch releaseTx1 = new CountDownLatch(0);

        void armTx1Park(long orgId, int count) {
            parkOrgId.set(orgId);
            parkRemaining.set(count);
            tx1BudgetLookups.set(0);
            parkedLatch = new CountDownLatch(count);
            releaseTx1 = new CountDownLatch(1);
        }

        void reset() {
            parkOrgId.set(-1L);
            parkRemaining.set(0);
            tx1BudgetLookups.set(0);
            failReserve.set(false);
            parkedLatch = new CountDownLatch(0);
            releaseTx1 = new CountDownLatch(1);
        }
    }
}
