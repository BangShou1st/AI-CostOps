package com.aicostops.gateway.resilience;

import com.aicostops.gateway.provider.ProviderHealthSignal;
import reactor.core.publisher.Mono;

public interface CircuitBreakerService {

    Mono<CircuitDecision> beforeCall(RouteCircuitKey key);

    Mono<Void> recordSuccess(RouteCircuitKey key);

    Mono<Void> recordFailure(RouteCircuitKey key, ProviderHealthSignal signal);
}
