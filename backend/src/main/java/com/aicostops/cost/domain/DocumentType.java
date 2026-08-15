package com.aicostops.cost.domain;

/** Provider-reported document kinds persisted in {@code external_document}. */
public enum DocumentType {
    USAGE_EXPORT,
    COST_EXPORT,
    STATEMENT,
    INVOICE,
    BILL_SUMMARY
}
