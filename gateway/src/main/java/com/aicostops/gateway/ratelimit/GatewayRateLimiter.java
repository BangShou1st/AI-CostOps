package com.aicostops.gateway.ratelimit;

import reactor.core.publisher.Mono;

/**
 * Request-rate coordination per Gateway credential. This is runtime-only:
 * it never authorizes spend and Redis loss must fail closed when the limiter
 * is enabled.
 */
public interface GatewayRateLimiter {

    /**
     * Bounded cost-one acquire. An allowed result consumes one unit; a rejected
     * result carries the bounded {@code retryAfterMillis} before the next token
     * is expected.
     */
    Mono<RateLimitResult> tryAcquire(long credentialId);

    record RateLimitResult(boolean allowed, long remaining, long retryAfterMillis) {

        public static RateLimitResult allowed(long remaining) {
            return new RateLimitResult(true, remaining, 0L);
        }

        public static RateLimitResult rejected(long remaining, long retryAfterMillis) {
            return new RateLimitResult(false, remaining, retryAfterMillis);
        }
    }
}