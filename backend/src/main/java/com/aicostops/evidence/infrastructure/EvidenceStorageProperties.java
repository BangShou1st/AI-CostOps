package com.aicostops.evidence.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "aicostops.storage")
public record EvidenceStorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        DataSize uploadLimit,
        boolean autoCreateBucket) {

    public EvidenceStorageProperties {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("aicostops.storage.endpoint is required");
        }
        if (accessKey == null || accessKey.isBlank()) {
            throw new IllegalArgumentException("aicostops.storage.access-key is required");
        }
        if (secretKey == null || secretKey.isBlank()) {
            throw new IllegalArgumentException("aicostops.storage.secret-key is required");
        }
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("aicostops.storage.bucket is required");
        }
        if (uploadLimit == null || uploadLimit.isNegative()) {
            throw new IllegalArgumentException("aicostops.storage.upload-limit must be positive");
        }
    }
}
