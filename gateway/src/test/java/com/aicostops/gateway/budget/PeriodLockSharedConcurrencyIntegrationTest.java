package com.aicostops.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.gateway.auth.GatewayPrincipal;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionCommand;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionOutcome;
import com.aicostops.gateway.budget.BudgetReservationService.AdmissionResult;
import com.aicostops.gateway.persistence.BudgetReservationMapper;
import com.aicostops.gateway.persistence.GatewayRequestMapper;
import com.aicostops.gateway.request.DispatchFenceService;
import com.aicostops.gateway.request.RequestIdentityService;
import com.aicostops.gateway.testsupport.GatewayMySqlContainerSupport;
import com.aicostops.gateway.testsupport.GatewayTestFixture;
import com.aicostops.gateway.testsupport.GatewayTestFixture.SeededEnv;
import com.aicostops.gateway.web.GatewayErrorException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * HC-02: the billing-period lock is shared for admission (TX1) and dispatch
 * (TX2), while the Budget row stays exclusive and Close keeps its exclusive
 * period lock. These tests fail on the HC-01 exclusive-period design
 * (overlap tests block) and, without TX1 READ COMMITTED, the barrier test
 * overspends because the non-locking SUM reads a stale REPEATABLE READ view.
 */
@SpringBootTest
@Tag("integration")
class PeriodLockSharedConcurrencyIntegrationTest extends GatewayMySqlContainerSupport {

    private static final String HMAC_KEY = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=";
    private static final long EIGHTY_TOKENS = 809_000L;

    @Autowired
    private BudgetReservationService reservations;

    @Autowired
    private DispatchFenceService dispatchFence;

    @Autowired
    private GatewayRequestMapper requestMapper;

    @Autowired
    private RequestIdentityService identityService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private BarrierState barrier;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private SeededEnv env;

    @BeforeEach
    void seed() {
        barrier.reset();
        env = GatewayTestFixture.seed(jdbc, "hc02-lock-" + System.nanoTime(), HMAC_KEY, rawKey());
    }

    @AfterEach
    void clean() {
        executor.shutdownNow();
        GatewayTestFixture.clean(jdbc);
    }

    /**
     * The stale-view regression for the naive fix. B performs its plain Budget
     * lookup (which fixes a REPEATABLE READ read view) and then parks; A
     * reserves 80 and commits; only then B locks the Budget and sums effective
     * holds. With READ COMMITTED B sees 80 and must reject; with REPEATABLE
     * READ it would still see 0 and both requests would reserve 160 > 100.
     */
    @Test
    void staleSnapshotAcrossSharedPeriodLockCannotOverspend() throws Exception {
        var first = insertValidatedRequest(env.projectId(), "hc02-stale-a");
        var second = insertValidatedRequest(env.projectId(), "hc02-stale-b");

        barrier.gate.set(true);
        var secondFuture = executor.submit(() -> admit(second, EIGHTY_TOKENS));
        if (!barrier.entered.await(20, TimeUnit.SECONDS)) {
            if (secondFuture.isDone()) {
                try {
                    throw new AssertionError(
                            "second admission completed before the Budget barrier: "
                                    + secondFuture.get(1, TimeUnit.SECONDS), null);
                } catch (java.util.concurrent.ExecutionException ex) {
                    throw new AssertionError("second admission failed before the Budget barrier",
                            ex.getCause());
                }
            }
        }
        assertThat(barrier.entered.await(1, TimeUnit.SECONDS))
                .describedAs("second admission must reach the Budget lookup under the shared period lock")
                .isTrue();

        var firstResult = admit(first, EIGHTY_TOKENS);
        assertThat(firstResult.outcome()).isEqualTo(AdmissionOutcome.RESERVED);

        barrier.release.countDown();
        var secondResult = secondFuture.get(20, TimeUnit.SECONDS);

        assertThat(secondResult.outcome())
                .describedAs("the second admission must see the first committed hold, not a stale snapshot")
                .isEqualTo(AdmissionOutcome.REJECTED_BUDGET);
        assertThat(sumEffectiveHolds(env)).isLessThanOrEqualTo(new BigDecimal("100.00000000"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM budget_reservation WHERE org_id=? AND status='ACTIVE'
                """, Integer.class, env.orgId())).isOne();
    }

    /**
     * A shared period lock must let a second Budget in the same period be
     * admitted while another transaction holds the same period. The fallback
     * ORG Budget keeps the hot fixture Budget untouched.
     */
    @Test
    void sharedPeriodLockAllowsDifferentBudgetAdmission() throws Exception {
        var projectB = insertProject("hc02-b");
        insertOrgBudget();
        var requestB = insertValidatedRequest(projectB, "hc02-multi-b");

        var holderLocked = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var holder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            lockPeriodShared();
            holderLocked.countDown();
            await(releaseHolder, 60_000);
            return null;
        }));
        assertThat(holderLocked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<AdmissionResult> admission;
        try {
            admission = executor.submit(() -> admitFor(principalFor(projectB), requestB, EIGHTY_TOKENS));
            assertThat(admission.get(5, TimeUnit.SECONDS).outcome())
                    .describedAs("a different Budget must not be blocked by another request's shared period lock")
                    .isEqualTo(AdmissionOutcome.RESERVED);
        } finally {
            releaseHolder.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    /** TX2 must also use the shared lock; otherwise it becomes the new global serialization point. */
    @Test
    void sharedPeriodLockAllowsDispatchWhileAnotherTransactionHoldsIt() throws Exception {
        var request = insertValidatedRequest(env.projectId(), "hc02-tx2");
        var admission = admit(request, EIGHTY_TOKENS);
        assertThat(admission.outcome()).isEqualTo(AdmissionOutcome.RESERVED);

        var holderLocked = new CountDownLatch(1);
        var releaseHolder = new CountDownLatch(1);
        var holder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
            lockPeriodShared();
            holderLocked.countDown();
            await(releaseHolder, 60_000);
            return null;
        }));
        assertThat(holderLocked.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            var fence = executor.submit(() -> {
                dispatchFence.commitDispatchFence(env.orgId(), request.requestId(),
                        request.attemptId(), env.periodId(), admission);
                return null;
            });
            fence.get(5, TimeUnit.SECONDS);
        } finally {
            releaseHolder.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        assertThat(jdbc.queryForObject("""
                SELECT state FROM gateway_request WHERE id=? AND org_id=?
                """, String.class, request.requestId(), env.orgId()))
                .isEqualTo("DISPATCH_INTENT");
    }

    /** A Close that already owns the exclusive period lock must reject new admission and dispatch. */
    @Test
    void closingPeriodRejectsAdmissionAndDispatch() {
        var dispatchRequest = insertValidatedRequest(env.projectId(), "hc02-closing-dispatch");
        var admission = admit(dispatchRequest, EIGHTY_TOKENS);
        assertThat(admission.outcome()).isEqualTo(AdmissionOutcome.RESERVED);

        jdbc.update("UPDATE billing_period SET status='CLOSING',version=version+1 WHERE id=? AND org_id=?",
                env.periodId(), env.orgId());

        var admissionRequest = insertValidatedRequest(env.projectId(), "hc02-closing-admit");
        assertThatThrownBy(() -> admit(admissionRequest, EIGHTY_TOKENS))
                .isInstanceOf(GatewayErrorException.class)
                .hasMessageContaining("not open");
        assertThatThrownBy(() -> dispatchFence.commitDispatchFence(env.orgId(),
                dispatchRequest.requestId(), dispatchRequest.attemptId(), env.periodId(), admission))
                .isInstanceOf(GatewayErrorException.class)
                .hasMessageContaining("not open");
    }

    private AdmissionResult admit(RequestIds request, long maxTokens) {
        return admitFor(principal(), request, maxTokens);
    }

    private AdmissionResult admitFor(GatewayPrincipal principal, RequestIds request, long maxTokens) {
        return reservations.admit(new AdmissionCommand(principal, request.requestId(),
                request.attemptId(), env.periodId(), env.pricingVersionId(), "USD", maxTokens, -1L)).block();
    }

    private GatewayPrincipal principal() {
        return new GatewayPrincipal(env.credentialId(), env.orgId(), env.projectId(), "SERVICE", null,
                env.serviceIdentityId(), "PROJECT", env.projectId(), "REQUIRED");
    }

    private GatewayPrincipal principalFor(long projectId) {
        return new GatewayPrincipal(env.credentialId(), env.orgId(), projectId, "SERVICE", null,
                env.serviceIdentityId(), "PROJECT", projectId, "REQUIRED");
    }

    private void lockPeriodShared() {
        jdbc.queryForObject("""
                SELECT status FROM billing_period WHERE id=? AND org_id=? FOR SHARE
                """, String.class, env.periodId(), env.orgId());
    }

    private long insertProject(String code) {
        jdbc.update("""
                INSERT INTO project(org_id,code,name,status,created_at,updated_at)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, env.orgId(), code, code);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void insertOrgBudget() {
        jdbc.update("""
                INSERT INTO budget(org_id,billing_period_id,scope_type,scope_id,currency,
                  total_amount,actual_amount,committed_amount,status,version,created_at,updated_at)
                VALUES (?,?,'ORG',?,'USD','100.00000000',0,0,'ACTIVE',0,
                  UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                """, env.orgId(), env.periodId(), env.orgId());
    }

    private BigDecimal sumEffectiveHolds(SeededEnv env) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(reserved_amount),0) FROM budget_reservation
                WHERE org_id=? AND status IN ('ACTIVE','PENDING_HOLD')
                """, BigDecimal.class, env.orgId());
    }

    private RequestIds insertValidatedRequest(long projectId, String idempotencyKey) {
        var idemDigest = identityService.idempotencyKeyDigest(idempotencyKey);
        var fingerprint = identityService.requestFingerprint(
                ("{\"model\":\"x-" + idempotencyKey + "\"}").getBytes(StandardCharsets.UTF_8));
        requestMapper.insertRequest(new GatewayRequestMapper.GatewayRequestInsert(
                env.orgId(), identityService.newPublicRequestId(), env.credentialId(), "SERVICE",
                null, env.serviceIdentityId(), projectId, "PROJECT", projectId,
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

    private static void await(CountDownLatch latch, long timeoutMillis) {
        try {
            if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("test latch timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static String rawKey() {
        return "aic_0123456789ab_" + "A".repeat(43);
    }

    /**
     * Test-only gate on the plain Budget lookup. It delegates every mapper
     * call to the real MyBatis mapper and parks exactly one lookup so the test
     * can commit the competing reservation while the other transaction still
     * holds a pre-lock read view.
     */
    @TestConfiguration
    static class GatedMapperConfiguration {

        @Bean
        @Primary
        BudgetReservationMapper gatedBudgetReservationMapper(
                @Qualifier("budgetReservationMapper") BudgetReservationMapper delegate,
                BarrierState barrier) {
            return (BudgetReservationMapper) Proxy.newProxyInstance(
                    BudgetReservationMapper.class.getClassLoader(),
                    new Class<?>[]{BudgetReservationMapper.class},
                    (proxy, method, args) -> {
                        Object result;
                        try {
                            result = method.invoke(delegate, args);
                        } catch (InvocationTargetException ex) {
                            throw ex.getTargetException();
                        }
                        if ("selectBudgetByIdentity".equals(method.getName())
                                && barrier.gate.compareAndSet(true, false)) {
                            barrier.entered.countDown();
                            if (!barrier.release.await(20, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test barrier timed out");
                            }
                        }
                        return result;
                    });
        }

        @Bean
        BarrierState barrierState() {
            return new BarrierState();
        }
    }

    static final class BarrierState {

        final AtomicBoolean gate = new AtomicBoolean(false);
        volatile CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);

        void reset() {
            gate.set(false);
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }
    }

    private record RequestIds(long requestId, long attemptId) {
    }
}
