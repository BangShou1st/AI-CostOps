package com.aicostops.gateway.quota;

import reactor.core.publisher.Mono;

/**
 * Operational request quota per Gateway credential. This is runtime-only
 * coordination: it never authorizes spend and Redis loss must fail closed
 * when the quota is enabled.
 */
public interface GatewayQuotaLimiter {

    /** Bounded cost-one acquire against the credential UTC-day window. */
    Mono<QuotaResult> tryAcquire(long credentialId);

    record QuotaResult(boolean allowed, long used) {

        public static QuotaResult allowed(long used) {
            return new QuotaResult(true, used);
        }

        public static QuotaResult rejected(long used) {
            return new QuotaResult(false, used);
        }
    }
}
