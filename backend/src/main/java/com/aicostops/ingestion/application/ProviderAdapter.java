package com.aicostops.ingestion.application;

/**
 * Streaming provider adapter contract. Adapters never touch MySQL or canonical cost
 * types; ingestion owns persistence and transaction behavior.
 */
public interface ProviderAdapter {

    String providerCode();

    String parserVersion();

    InspectionResult inspect(ProviderSource source);

    void parse(ProviderSource source, InspectionResult inspection, ProviderRecordSink sink);

    NormalizedProviderRecord normalize(ParsedProviderRecord record);
}
