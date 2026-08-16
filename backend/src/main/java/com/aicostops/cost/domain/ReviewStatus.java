package com.aicostops.cost.domain;

/**
 * Review lifecycle of a persisted {@code charge_fact}. All rows start CLEAN;
 * {@code SUSPECTED_DUPLICATE} is the materialized aggregate of open duplicate
 * candidates maintained by the cost.review workflow, and the {@code EXCLUDED_*}
 * states are terminal review outcomes.
 */
public enum ReviewStatus {
    CLEAN,
    SUSPECTED_DUPLICATE,
    EXCLUDED_DUPLICATE,
    EXCLUDED_NONCOST
}
