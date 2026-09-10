package com.aicostops.providerhub.domain;

import java.time.Instant;

/** Versioned organization-scoped Provider Connection Profile (M18 V3). */
public record ProviderConnection(
        long id,
        long organizationId,
        long providerAccountId,
        int version,
        String connectionKind,
        String templateCode,
        String protocolCode,
        String baseUrl,
        String completionPath,
        String modelsPath,
        String authType,
        String authHeaderName,
        String networkPolicy,
        String userAgent,
        int connectTimeoutMs,
        int responseTimeoutMs,
        String status,
        Long createdBy,
        Instant createdAt,
        Instant activatedAt,
        Instant retiredAt) {
}
