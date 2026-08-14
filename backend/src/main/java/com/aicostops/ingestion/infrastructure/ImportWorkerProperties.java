package com.aicostops.ingestion.infrastructure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aicostops.ingestion")
public record ImportWorkerProperties(
        boolean workerEnabled,
        int workerConcurrency,
        Duration pollInterval,
        Duration leaseDuration,
        Duration heartbeatInterval,
        int maxLeaseRecoveries,
        int persistenceBatchSize) {

    public ImportWorkerProperties {
        if (workerConcurrency <= 0) {
            throw new IllegalArgumentException("aicostops.ingestion.worker-concurrency must be positive");
        }
        if (leaseDuration == null || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("aicostops.ingestion.lease-duration must be positive");
        }
        if (heartbeatInterval == null || heartbeatInterval.isNegative()) {
            throw new IllegalArgumentException("aicostops.ingestion.heartbeat-interval must be positive");
        }
        if (maxLeaseRecoveries < 0) {
            throw new IllegalArgumentException("aicostops.ingestion.max-lease-recoveries must not be negative");
        }
        if (persistenceBatchSize <= 0) {
            throw new IllegalArgumentException("aicostops.ingestion.persistence-batch-size must be positive");
        }
    }
}
