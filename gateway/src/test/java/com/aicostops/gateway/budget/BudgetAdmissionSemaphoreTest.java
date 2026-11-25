package com.aicostops.gateway.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.gateway.observability.GatewayMetrics;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BudgetAdmissionSemaphoreTest {

    @Test
    void permitsAreBoundedAndTimeoutFailsClosedWithoutBypass() {
        var registry = new SimpleMeterRegistry();
        var semaphore = new BudgetAdmissionSemaphore(
                2, 40L, 10_000L, Duration.ofMinutes(10), Ticker.systemTicker(), new GatewayMetrics(registry));

        var first = semaphore.acquire(101L);
        var second = semaphore.acquire(101L);
        assertThat(semaphore.availablePermitsIfCached(101L)).isZero();

        assertThatThrownBy(() -> semaphore.acquire(101L))
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE));

        first.release();
        assertThat(semaphore.availablePermitsIfCached(101L)).isEqualTo(1);

        var third = semaphore.acquire(101L);
        assertThat(semaphore.availablePermitsIfCached(101L)).isZero();

        second.release();
        third.release();
        assertThat(semaphore.availablePermitsIfCached(101L)).isEqualTo(2);

        assertThat(registry.get("gateway_budget_semaphore_acquire_total")
                .tag("outcome", "ACQUIRED").counter().count()).isEqualTo(3.0);
        assertThat(registry.get("gateway_budget_semaphore_acquire_total")
                .tag("outcome", "TIMEOUT").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("gateway_budget_semaphore_acquire_total").counters())
                .allSatisfy(counter -> assertThat(counter.getId().getTags())
                        .noneMatch(tag -> tag.getKey().toLowerCase().contains("budget")));
    }

    @Test
    void cacheEvictionNeverCausesPermitDriftIntoNewSemaphoreInstance() {
        var semaphore = new BudgetAdmissionSemaphore(
                1, 40L, 1L, Duration.ofMinutes(10), Ticker.systemTicker(), null);

        var evictedPermit = semaphore.acquire(100L);
        var otherPermit = semaphore.acquire(200L);
        semaphore.cleanUp();

        // Budget 100 was evicted from the 1-entry cache; a fresh Semaphore(1) is created.
        var freshPermit = semaphore.acquire(100L);
        assertThat(semaphore.availablePermitsIfCached(100L)).isZero();

        // Releasing the evicted permit must release ONLY on the old Semaphore instance,
        // never inflating or corrupting the newly cached Semaphore for budget 100.
        evictedPermit.release();
        assertThat(semaphore.availablePermitsIfCached(100L)).isZero();
        assertThatThrownBy(() -> semaphore.acquire(100L))
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE));

        freshPermit.release();
        assertThat(semaphore.availablePermitsIfCached(100L)).isEqualTo(1);
        otherPermit.release();
    }

    @Test
    void doubleReleaseOnSamePermitIsIdempotent() {
        var semaphore = new BudgetAdmissionSemaphore(
                2, 40L, 10_000L, Duration.ofMinutes(10), Ticker.systemTicker(), null);

        var permit = semaphore.acquire(555L);
        assertThat(semaphore.availablePermitsIfCached(555L)).isEqualTo(1);

        permit.release();
        permit.release();
        permit.close();

        assertThat(semaphore.availablePermitsIfCached(555L)).isEqualTo(2);
    }

    @Test
    void interruptedWaitRestoresInterruptFlagAndThrowsDependencyUnavailable() throws Exception {
        var registry = new SimpleMeterRegistry();
        var semaphore = new BudgetAdmissionSemaphore(
                1, 5_000L, 10_000L, Duration.ofMinutes(10), Ticker.systemTicker(), new GatewayMetrics(registry));

        var holder = semaphore.acquire(999L);
        var started = new CountDownLatch(1);
        var thrown = new AtomicReference<Throwable>();
        var interruptedFlag = new AtomicBoolean(false);

        var waiter = new Thread(() -> {
            started.countDown();
            try {
                semaphore.acquire(999L);
            } catch (Throwable ex) {
                thrown.set(ex);
                interruptedFlag.set(Thread.currentThread().isInterrupted());
            }
        });
        waiter.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        waiter.interrupt();
        waiter.join(5_000L);

        holder.release();
        assertThat(waiter.isAlive()).isFalse();
        assertThat(thrown.get())
                .isInstanceOf(GatewayErrorException.class)
                .satisfies(ex -> assertThat(((GatewayErrorException) ex).code())
                        .isEqualTo(GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE));
        assertThat(interruptedFlag.get()).isTrue();
        assertThat(registry.get("gateway_budget_semaphore_acquire_total")
                .tag("outcome", "INTERRUPTED").counter().count()).isEqualTo(1.0);
        assertThat(semaphore.availablePermitsIfCached(999L)).isEqualTo(1);
    }
}
