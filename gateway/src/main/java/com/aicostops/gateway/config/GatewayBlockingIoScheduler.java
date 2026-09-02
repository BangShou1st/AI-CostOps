package com.aicostops.gateway.config;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * One dedicated bounded Reactor scheduler for all blocking DB work. The shared
 * global bounded-elastic scheduler is never exposed as the DB boundary; this
 * instance is sized from the configured DB worker thread/queue limits.
 */
@Component
public final class GatewayBlockingIoScheduler implements BlockingIoScheduler {

    private final Scheduler scheduler;

    public GatewayBlockingIoScheduler(GatewayProperties properties) {
        this.scheduler = Schedulers.newBoundedElastic(
                properties.getDbThreads(),
                properties.getDbQueueCapacity(),
                "gateway-db",
                60);
    }

    @Override
    public <T> Mono<T> call(Callable<T> operation) {
        return Mono.fromCallable(operation).subscribeOn(scheduler);
    }

    @Override
    public Mono<Void> run(Runnable operation) {
        return Mono.fromRunnable(operation)
                .subscribeOn(scheduler)
                .then();
    }

    @PreDestroy
    @Override
    public void close() {
        scheduler.dispose();
    }
}