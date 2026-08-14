package com.aicostops.ingestion.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ImportWorkerPropertiesTest {

    @Test
    void healthyDefaultsAreAccepted() {
        var properties = new ImportWorkerProperties(
                true, 2, Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(20), 3, 500);

        assertThat(properties.workerEnabled()).isTrue();
        assertThat(properties.leaseDuration()).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.heartbeatInterval()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void zeroPollIntervalIsRejected() {
        assertThatThrownBy(() -> new ImportWorkerProperties(
                true, 2, Duration.ZERO, Duration.ofSeconds(60), Duration.ofSeconds(20), 3, 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poll-interval");
    }

    @Test
    void zeroLeaseDurationIsRejected() {
        assertThatThrownBy(() -> new ImportWorkerProperties(
                true, 2, Duration.ofSeconds(1), Duration.ZERO, Duration.ofSeconds(20), 3, 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease-duration");
    }

    @Test
    void zeroHeartbeatIntervalIsRejected() {
        assertThatThrownBy(() -> new ImportWorkerProperties(
                true, 2, Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ZERO, 3, 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeat-interval");
    }

    @Test
    void heartbeatNotStrictlyBelowLeaseIsRejected() {
        assertThatThrownBy(() -> new ImportWorkerProperties(
                true, 2, Duration.ofSeconds(1), Duration.ofSeconds(60), Duration.ofSeconds(60), 3, 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeat-interval must be shorter than lease-duration");
        assertThatThrownBy(() -> new ImportWorkerProperties(
                true, 2, Duration.ofSeconds(1), Duration.ofSeconds(20), Duration.ofSeconds(30), 3, 500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heartbeat-interval must be shorter than lease-duration");
    }
}
