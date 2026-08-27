package com.aicostops.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

/**
 * Bounded business metrics contract: stable {@code aicostops.*} names with
 * enum/code tags only. API methods never accept arbitrary metadata maps or
 * identifiers, so the metric cardinality is bounded by design.
 */
class AiCostOpsMetricsTest {

    @Test
    void importCounterCountsByProviderAndResult() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AiCostOpsMetrics(registry);

        metrics.importCompleted("DEEPSEEK", "SUCCEEDED");
        metrics.importCompleted("DEEPSEEK", "SUCCEEDED");
        metrics.importCompleted("GLM", "FAILED");

        assertThat(registry.get("aicostops.import.completed")
                .tags("provider", "DEEPSEEK", "result", "SUCCEEDED")
                .counter().count()).isEqualTo(2.0);
        assertThat(registry.get("aicostops.import.completed")
                .tags("provider", "GLM", "result", "FAILED")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void exposesOnlyTheStableBoundedMetricSet() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AiCostOpsMetrics(registry);

        metrics.loginResult("RATE_LIMITED");
        metrics.importCompleted("DEEPSEEK", "FAILED");
        metrics.ledgerPosting("CHARGE", "POSTED");
        metrics.correction("REVERSE", "POSTED");
        metrics.budgetActivation("CONFLICT");
        metrics.reconciliationRun("COMPLETED");
        metrics.periodClose("CLOSED");
        metrics.periodReopen("REOPENED");
        metrics.dependencyError("REDIS");

        assertThat(registry.getMeters())
                .extracting(meter -> meter.getId().getName())
                .containsExactlyInAnyOrder(
                        "aicostops.login.result",
                        "aicostops.import.completed",
                        "aicostops.ledger.posting",
                        "aicostops.ledger.correction",
                        "aicostops.budget.activation",
                        "aicostops.reconciliation.run",
                        "aicostops.period.close",
                        "aicostops.period.reopen",
                        "aicostops.dependency.error");
    }

    @Test
    void loginCounterCountsByResult() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AiCostOpsMetrics(registry);

        metrics.loginResult("SUCCESS");
        metrics.loginResult("INVALID_CREDENTIALS");
        metrics.loginResult("SUCCESS");

        assertThat(registry.get("aicostops.login.result")
                .tag("result", "SUCCESS").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("aicostops.login.result")
                .tag("result", "INVALID_CREDENTIALS").counter().count()).isEqualTo(1.0);
    }

    @Test
    void ledgerFinancialCountersStaySeparate() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AiCostOpsMetrics(registry);

        metrics.ledgerPosting("CHARGE", "POSTED");
        metrics.ledgerPosting("EXPENSE", "FAILED");
        metrics.correction("REVERSAL_ONLY", "POSTED");
        metrics.correction("REPLACE", "FAILED");

        assertThat(registry.get("aicostops.ledger.posting")
                .tags("sourceType", "EXPENSE", "result", "FAILED")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("aicostops.ledger.correction")
                .tags("mode", "REVERSAL_ONLY", "result", "POSTED")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void lifecycleCountersTrackOutcomeOnly() {
        var registry = new SimpleMeterRegistry();
        var metrics = new AiCostOpsMetrics(registry);

        metrics.budgetActivation("CONFLICT");
        metrics.reconciliationRun("COMPLETED");
        metrics.periodClose("BLOCKED");
        metrics.periodReopen("REOPENED");
        metrics.dependencyError("OBJECT_STORAGE");

        assertThat(registry.get("aicostops.budget.activation")
                .tag("result", "CONFLICT").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("aicostops.reconciliation.run")
                .tag("result", "COMPLETED").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("aicostops.period.close")
                .tag("result", "BLOCKED").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("aicostops.period.reopen")
                .tag("result", "REOPENED").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("aicostops.dependency.error")
                .tag("dependency", "OBJECT_STORAGE").counter().count()).isEqualTo(1.0);
    }
}