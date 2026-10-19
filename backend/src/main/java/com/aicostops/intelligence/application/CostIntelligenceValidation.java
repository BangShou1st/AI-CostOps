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

    /** Version 3 added explicit coverage reviews and immutable input identities.
     * Version 4 added the forecast eligibility rule that withholds reversal-dominated
     * (non-positive net) histories from future-usage snapshots.
     * Version 5 aligns eligibility with the actual extrapolation window (the trailing
     * RECENT_RUN_RATE window, not the whole suffix) and lets budget risk accept a negative
     * signed actual; old version-4 snapshots stop being consumed because the input
     * fingerprint includes the version. */
    public static final int RUN_VERSION = 5;

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
