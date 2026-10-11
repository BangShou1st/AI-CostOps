package com.aicostops.intelligence.application;

import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.http.HttpStatus;

/**
 * Frozen Cost Intelligence input contract shared by analysis, query, risk and command services
 * (M18 V3).
 *
 * <p>Only values genuinely reused across those services live here: the run version that forms the
 * analysis identity and the currency format rule. Date validation stays with its single caller, so
 * this class does not grow into a second, competing rule set.
 */
public final class CostIntelligenceValidation {

    /** Version 2 excludes unobserved calendar padding from forecast sample counts. */
    public static final int RUN_VERSION = 2;

    private CostIntelligenceValidation() {
    }

    public static String validatedCurrency(String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                    "Cost intelligence validation failed", "Currency must be ISO-4217.");
        }
        return currency;
    }
}
