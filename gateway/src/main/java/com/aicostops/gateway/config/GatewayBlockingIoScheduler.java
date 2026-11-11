package com.aicostops.gateway.config;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * One dedicated bounded Reactor scheduler for all blocking DB work. The shared
 * global bounded-elastic scheduler is never exposed as the DB boundary; this
 * instance is sized from the configured DB worker thread/queue limits.
 *
 * <p>Saturation is observable without changing execution: {@link #activeTasks()}
 * counts DB operations currently running on {@code gateway-db} threads and
 * {@link #queuedTasks()} counts subscribed operations waiting for a worker.
 * Both counters are published as Micrometer gauges for load-test evidence; the
 * scheduling semantics themselves remain exactly the bounded-elastic boundary.
 */
@Component
public final class GatewayBlockingIoScheduler implements BlockingIoScheduler {

    private final Scheduler scheduler;
    private final int maxThreads;
    private final int queueCapacity;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger outstanding = new AtomicInteger();

    public GatewayBlockingIoScheduler(GatewayProperties properties) {
        this.scheduler = Schedulers.newBoundedElastic(
                properties.getDbThreads(),
                properties.getDbQueueCapacity(),
                "gateway-db",
                60);
        this.maxThreads = properties.getDbThreads();
        this.queueCapacity = properties.getDbQueueCapacity();
    }

    @Override
    public <T> Mono<T> call(Callable<T> operation) {
        return Mono.defer(() -> {
            outstanding.incrementAndGet();
            return Mono.fromCallable(() -> {
                        active.incrementAndGet();
                        try {
                            return operation.call();
                        } finally {
                            active.decrementAndGet();
                        }
                    })
                    .subscribeOn(scheduler)
                    .doFinally(signal -> outstanding.decrementAndGet());
        });
    }

    @Override
    public Mono<Void> run(Runnable operation) {
        return call(() -> {
                    operation.run();
                    return Boolean.TRUE;
                })
                .then();
    }

    /** DB operations currently executing on the dedicated scheduler. */
    public int activeTasks() {
        return active.get();
    }

    /** Subscribed DB operations waiting for a {@code gateway-db} worker. */
    public int queuedTasks() {
        return Math.max(0, outstanding.get() - active.get());
    }

    /** Configured bounded worker count (evidence for saturation ratios). */
    public int maxThreads() {
        return maxThreads;
    }

    /** Configured per-thread queue bound. */
    public int queueCapacity() {
        return queueCapacity;
    }

    @PreDestroy
    @Override
    public void close() {
        scheduler.dispose();
    }
}