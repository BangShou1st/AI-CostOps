package com.aicostops.providerhub.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class ProviderHubDtos {

    private ProviderHubDtos() {
    }

    public record CreateConnectionRequest(
            Long providerAccountId,
            @Size(max = 100) String templateCode,
            @Size(max = 500) String baseUrl,
            @Size(max = 200) String completionPath,
            @Size(max = 200) String modelsPath,
            @Size(max = 32) String authType,
            @Size(max = 200) String authHeaderName,
            @Size(max = 300) String userAgent,
            @Min(100) @Max(60000) Integer connectTimeoutMs,
            @Min(1000) @Max(600000) Integer responseTimeoutMs) {
    }

    public record UpdateConnectionRequest(
            @Size(max = 500) String baseUrl,
            @Size(max = 200) String completionPath,
            @Size(max = 200) String modelsPath,
            @Size(max = 32) String authType,
            @Size(max = 200) String authHeaderName,
            @Size(max = 300) String userAgent,
            @Min(100) @Max(60000) Integer connectTimeoutMs,
            @Min(1000) @Max(600000) Integer responseTimeoutMs) {
    }

    public record ConnectionResponse(
            long id,
            long providerAccountId,
            int version,
            String connectionKind,
            String templateCode,
            String protocolCode,
            String baseUrl,
            String completionPath,
            String modelsPath,
            String authType,
            String networkPolicy,
            int connectTimeoutMs,
            int responseTimeoutMs,
            String status,
            Instant createdAt,
            Instant activatedAt,
            Instant retiredAt) {
    }

    public record TemplateResponse(
            String code,
            String name,
            String connectionKind,
            String protocolCode,
            String baseUrl,
            String networkPolicy,
            String authType,
            List<String> lockedFields) {
    }

    public record ProbeResponse(
            String status,
            String errorCode,
            Instant checkedAt) {
    }
}
