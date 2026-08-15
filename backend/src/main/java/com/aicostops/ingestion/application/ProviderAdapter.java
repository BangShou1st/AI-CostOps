package com.aicostops.ingestion.application;

/**
 * Streaming provider adapter contract. Adapters never touch MySQL or canonical cost
 * types; ingestion owns persistence and transaction behavior.
 *
 * <p>{@code schemaVariant} is routed from {@link InspectionResult} into
 * {@link #normalize} explicitly; adapters must not smuggle variant or record-kind
 * routing through fake raw provider fields.
 */
public interface ProviderAdapter {

    String providerCode();

    String parserVersion();

    InspectionResult inspect(ProviderInput input);

    void parse(ProviderInput input, InspectionResult inspection, ProviderRecordSink sink);

    NormalizedProviderRecord normalize(ParsedProviderRecord record, InspectionResult inspection);
}
