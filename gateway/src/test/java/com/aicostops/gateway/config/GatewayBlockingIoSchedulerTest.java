package com.aicostops.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.observability.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * Saturation counters are instrumentation-only, but they must still reflect
 * the real bounded-elastic boundary: with one worker, the first operation is
 * active and the rest are queued, and both return to zero after completion.
 */
class GatewayBlockingIoSchedulerTest {

    @Test
    void activeAndQueuedTasksTrackTheBoundedScheduler() throws Exception {
        var properties = new GatewayProperties();
        properties.setDbThreads(1);
        properties.setDbQueueCapacity(8);
        var scheduler = new GatewayBlockingIoScheduler(properties);
        var registry = new SimpleMeterRegistry();
        new GatewayMetrics(registry).bindDbScheduler(scheduler);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            for (int i = 0; i < 3; i++) {
                final int operation = i;
                scheduler.call(() -> {
                    started.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return operation;
                }).subscribe();
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            awaitUntil(() -> scheduler.activeTasks() == 1 && scheduler.queuedTasks() == 2,
                    Duration.ofSeconds(5));
            assertThat(registry.get("gateway_db_active").gauge().value()).isEqualTo(1.0);
            assertThat(registry.get("gateway_db_queued").gauge().value()).isEqualTo(2.0);
            assertThat(registry.get("gateway_db_threads_max").gauge().value()).isEqualTo(1.0);
            assertThat(registry.get("gateway_db_queue_capacity").gauge().value()).isEqualTo(8.0);

            release.countDown();
            awaitUntil(() -> scheduler.activeTasks() == 0 && scheduler.queuedTasks() == 0,
                    Duration.ofSeconds(5));
        } finally {
            release.countDown();
            scheduler.close();
            registry.close();
        }
    }

    @Test
    void runCountsQueuedWorkAndReturnsToZero() throws Exception {
        var properties = new GatewayProperties();
        properties.setDbThreads(1);
        properties.setDbQueueCapacity(4);
        var scheduler = new GatewayBlockingIoScheduler(properties);
        var release = new CountDownLatch(1);
        try {
            scheduler.run(() -> {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }).subscribe();
            awaitUntil(() -> scheduler.activeTasks() == 1, Duration.ofSeconds(5));
            release.countDown();
            awaitUntil(() -> scheduler.activeTasks() == 0 && scheduler.queuedTasks() == 0,
                    Duration.ofSeconds(5));
        } finally {
            release.countDown();
            scheduler.close();
        }
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        var deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).as("condition within %s", timeout).isTrue();
    }
}
