package com.aicostops.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Bounded application metrics facade.
 *
 * <p>Only workflows with clear operational value are instrumented, and every
 * label is a low-cardinality enum/code. The API deliberately does not accept
 * metadata maps, identifiers, or free-form text, so no user id, organization
 * id, request id, email, provider raw account id, prompt, response, or error
 * message can ever become a metric label.
 *
 * <p>Infrastructure signals (HTTP, JVM, GC, Hikari pool) are covered by the
 * Spring Boot / Micrometer built-ins and are not reimplemented here.
 */
@Component
public class AiCostOpsMetrics {

    private final MeterRegistry registry;

    public AiCostOpsMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Auth outcome: SUCCESS / INVALID_CREDENTIALS / ACCOUNT_DISABLED / RATE_LIMITED. */
    public void loginResult(String result) {
        registry.counter("aicostops.login.result", "result", result).increment();
    }

    /** Import final outcome per provider code: provider is a registered adapter code. */
    public void importCompleted(String provider, String result) {
        registry.counter("aicostops.import.completed",
                "provider", provider, "result", result).increment();
    }

    /** Ledger posting outcome: sourceType CHARGE / EXPENSE; result POSTED / FAILED. */
    public void ledgerPosting(String sourceType, String result) {
        registry.counter("aicostops.ledger.posting",
                "sourceType", sourceType, "result", result).increment();
    }

    /** Ledger correction outcome: mode REVERSE / REISSUE; result POSTED / FAILED. */
    public void correction(String mode, String result) {
        registry.counter("aicostops.ledger.correction",
                "mode", mode, "result", result).increment();
    }

    /** Budget commitment activation outcome: ACTIVATED / CONFLICT. */
    public void budgetActivation(String result) {
        registry.counter("aicostops.budget.activation", "result", result).increment();
    }

    /** Reconciliation run outcome: COMPLETED / FAILED. */
    public void reconciliationRun(String result) {
        registry.counter("aicostops.reconciliation.run", "result", result).increment();
    }

    /** Period close outcome: CLOSED / BLOCKED / FAILED. */
    public void periodClose(String result) {
        registry.counter("aicostops.period.close", "result", result).increment();
    }

    /** Period reopen outcome: reopened periods are an explicit lifecycle signal. */
    public void periodReopen(String result) {
        registry.counter("aicostops.period.reopen", "result", result).increment();
    }

    /** Dependency failure signal: dependency REDIS / OBJECT_STORAGE / DATABASE. */
    public void dependencyError(String dependency) {
        registry.counter("aicostops.dependency.error", "dependency", dependency).increment();
    }
}