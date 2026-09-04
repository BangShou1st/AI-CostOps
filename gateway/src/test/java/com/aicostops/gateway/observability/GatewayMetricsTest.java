package com.aicostops.gateway.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.gateway.metering.GatewayUsageStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class GatewayMetricsTest {

    @Test
    void meteringMetricsUseOnlyBoundedLabels() {
        var registry = new SimpleMeterRegistry();
        var metrics = new GatewayMetrics(registry);

        metrics.recordUsageStatus(GatewayUsageStatus.FINAL);
        metrics.recordMeteringIncomplete("MIMO", "MISSING_DIMENSION");
        metrics.recordMeteringUnknown("provider-with-request-id", "user supplied reason");
        metrics.recordProviderUsageParseError("provider-with-request-id");

        assertThat(registry.get("gateway_usage_total").tag("status", "FINAL").counter().count())
                .isEqualTo(1);
        assertThat(registry.get("gateway_metering_incomplete_total")
                .tag("provider_code", "MIMO").tag("reason_code", "MISSING_DIMENSION")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("gateway_metering_unknown_total")
                .tag("provider_code", "UNKNOWN").tag("reason_code", "UNKNOWN")
                .counter().count()).isEqualTo(1);
        assertThat(registry.get("gateway_provider_usage_parse_error_total")
                .tag("provider_code", "UNKNOWN").counter().count()).isEqualTo(1);
    }
}
