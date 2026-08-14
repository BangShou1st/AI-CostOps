package com.aicostops.ingestion.application;

/** Ingestion-owned stream of normalized records; adapters call this, never MySQL. */
public interface ProviderRecordSink {

    void accept(NormalizedProviderRecord record);
}
