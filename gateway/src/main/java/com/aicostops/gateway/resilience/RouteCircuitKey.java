package com.aicostops.gateway.resilience;

public record RouteCircuitKey(long organizationId, long providerAccountId, long providerModelId) {

    public String redisKey() {
        return "aicostops:gateway:circuit:v1:" + organizationId + ":"
                + providerAccountId + ":" + providerModelId;
    }
}
