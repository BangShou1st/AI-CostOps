package com.aicostops.reconciliation.application;

import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class ReconciliationTolerancePolicy {

    private final BigDecimal amount;

    public ReconciliationTolerancePolicy(
            @Value("${aicostops.reconciliation.tolerance:0.00000000}") String configured) {
        var parsed = ReconciliationMoney.requireScale8Exact(new BigDecimal(configured));
        if (parsed.signum() < 0) {
            throw new IllegalArgumentException("reconciliation tolerance must be non-negative");
        }
        this.amount = parsed;
    }

    public BigDecimal amount() {
        return amount;
    }
}
