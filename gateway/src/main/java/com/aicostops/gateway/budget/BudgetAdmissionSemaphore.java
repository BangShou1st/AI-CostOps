package com.aicostops.gateway.budget;

import com.aicostops.gateway.config.GatewayProperties;
import com.aicostops.gateway.observability.GatewayMetrics;
import com.aicostops.gateway.web.GatewayErrorCode;
import com.aicostops.gateway.web.GatewayErrorException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * HC-04 A3: Per-budget in-memory admission semaphore.
 *
 * <p>Serialization/contention on a hot budget waits on the {@code blockingIo}
 * thread before opening TX1 or borrowing a Hikari connection, preventing hot
 * budget lock queues from exhausting the shared DB connection pool.
 *
 * <p>Bounded by a Caffeine cache ({@code maximumSize = 10_000},
 * {@code expireAfterAccess = 10m}). Each acquired {@link Permit} retains the
 * exact {@link Semaphore} instance from which it was acquired so {@code release()}
 * never re-queries the cache and cannot drift permits across evictions.
 */
@Component
public class BudgetAdmissionSemaphore {

    static final long DEFAULT_MAXIMUM_SIZE = 10_000L;
    static final Duration DEFAULT_EXPIRE_AFTER_ACCESS = Duration.ofMinutes(10);

    private final int permits;
    private final long timeoutMs;
    private final Cache<Long, Semaphore> semaphores;
    private final GatewayMetrics metrics;

    @Autowired
    public BudgetAdmissionSemaphore(GatewayProperties properties, GatewayMetrics metrics) {
        this(properties.getBudgetSemaphorePermits(),
                properties.getBudgetSemaphoreTimeoutMs(),
                DEFAULT_MAXIMUM_SIZE,
                DEFAULT_EXPIRE_AFTER_ACCESS,
                Ticker.systemTicker(),
                metrics);
    }

    public BudgetAdmissionSemaphore(GatewayProperties properties) {
        this(properties, null);
    }

    BudgetAdmissionSemaphore(
            int permits,
            long timeoutMs,
            long maximumSize,
            Duration expireAfterAccess,
            Ticker ticker,
            GatewayMetrics metrics) {
        if (permits <= 0) {
            throw new IllegalArgumentException("budgetSemaphorePermits must be positive, got " + permits);
        }
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("budgetSemaphoreTimeoutMs must be positive, got " + timeoutMs);
        }
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("maximumSize must be positive, got " + maximumSize);
        }
        this.permits = permits;
        this.timeoutMs = timeoutMs;
        this.metrics = metrics;
        this.semaphores = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .expireAfterAccess(expireAfterAccess)
                .ticker(ticker)
                .build();
    }

    /**
     * Acquires one admission permit for {@code budgetId}, blocking up to the
     * configured timeout. Never proceeds without a permit: timeouts and
     * interrupts fail closed with {@link GatewayErrorCode#GATEWAY_DEPENDENCY_UNAVAILABLE}.
     */
    public Permit acquire(long budgetId) {
        var semaphore = semaphores.get(budgetId, ignored -> new Semaphore(permits, true));
        boolean acquired;
        try {
            acquired = semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            record("INTERRUPTED");
            var error = new GatewayErrorException(
                    GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Interrupted while waiting for budget admission permit");
            error.initCause(ex);
            throw error;
        }
        if (!acquired) {
            record("TIMEOUT");
            throw new GatewayErrorException(
                    GatewayErrorCode.GATEWAY_DEPENDENCY_UNAVAILABLE,
                    "Budget admission queue timed out");
        }
        record("ACQUIRED");
        return new Permit(semaphore);
    }

    int permits() {
        return permits;
    }

    long timeoutMs() {
        return timeoutMs;
    }

    int availablePermitsIfCached(long budgetId) {
        var semaphore = semaphores.getIfPresent(budgetId);
        return semaphore == null ? permits : semaphore.availablePermits();
    }

    int queueLengthIfCached(long budgetId) {
        var semaphore = semaphores.getIfPresent(budgetId);
        return semaphore == null ? 0 : semaphore.getQueueLength();
    }

    void cleanUp() {
        semaphores.cleanUp();
    }

    private void record(String outcome) {
        if (metrics != null) {
            metrics.recordBudgetSemaphoreAcquire(outcome);
        }
    }

    /**
     * Lease holding the exact {@link Semaphore} instance from which the permit
     * was taken. Calling {@link #release()} or {@link #close()} is idempotent
     * and always releases back to that exact instance.
     */
    public static final class Permit implements AutoCloseable {

        private final Semaphore semaphore;
        private final AtomicBoolean released = new AtomicBoolean(false);

        private Permit(Semaphore semaphore) {
            this.semaphore = semaphore;
        }

        public void release() {
            if (released.compareAndSet(false, true)) {
                semaphore.release();
            }
        }

        @Override
        public void close() {
            release();
        }
    }
}
