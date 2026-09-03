package com.aicostops.gateway.config;

import java.util.concurrent.Callable;
import reactor.core.publisher.Mono;

/**
 * Narrow blocking boundary for synchronous JDBC/MyBatis seams. Implementations
 * run off the Reactor Netty event loops on a dedicated bounded scheduler.
 */
public interface BlockingIoScheduler extends AutoCloseable {

    <T> Mono<T> call(Callable<T> operation);

    Mono<Void> run(Runnable operation);
}