package com.aicostops.ingestion.domain;

public enum ImportSourceType {
    FILE_EXPORT,
    USAGE_API_JSON,
    COSTS_API_JSON,
    /** Import batch persisted by the UAT regression suite. */
    COST_EXPORT
}
