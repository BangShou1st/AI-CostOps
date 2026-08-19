package com.aicostops.budget.api;

import com.aicostops.budget.domain.BillingPeriod;
import java.time.Instant;

public final class BillingPeriodResponses {

    private BillingPeriodResponses() {}

    public record BillingPeriodResponse(
            String id,
            Instant periodStart,
            Instant periodEnd,
            String status,
            long version) {

        public static BillingPeriodResponse from(BillingPeriod period) {
            return new BillingPeriodResponse(
                    Long.toString(period.id()),
                    period.periodStart(),
                    period.periodEnd(),
                    period.status().name(),
                    period.version());
        }
    }
}
