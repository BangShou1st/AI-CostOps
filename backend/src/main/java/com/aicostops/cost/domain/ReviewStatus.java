package com.aicostops.cost.domain;

/** Review lifecycle of a persisted {@code charge_fact}. AIC-034 is not implemented; all rows start CLEAN. */
public enum ReviewStatus {
    CLEAN,
    SUSPECTED_DUPLICATE,
    EXCLUDED_DUPLICATE,
    EXCLUDED_NONCOST
}
