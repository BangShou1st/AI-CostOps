package com.aicostops.attribution.domain;

/**
 * Explicit deterministic matcher of an allocation rule version. V1 knows
 * exactly the Group 1 attribution-hint evidence types; no regex, expression,
 * generic dimension, or JSON DSL exists in this foundation.
 */
public enum AllocationRuleMatchType {
    PROVIDER_API_KEY,
    PROVIDER_PROJECT,
    PROVIDER_USER
}
