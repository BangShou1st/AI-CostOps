package com.aicostops.ingestion.application;

import com.aicostops.ingestion.domain.ImportSourceType;
import java.util.Objects;

/**
 * Immutable adapter input context: repeatable source bytes plus the ImportBatch
 * source-type and Evidence metadata that make provider schema selection explicit.
 *
 * <p>Adapters still never touch MySQL; the worker constructs this value from the
 * {@code ImportBatch} and {@code Evidence} rows it already loaded.
 */
public record ProviderInput(
        ProviderSource source,
        ImportSourceType sourceType,
        String originalFilename,
        String mediaType) {

    public ProviderInput {
        Objects.requireNonNull(source, "ProviderInput source must not be null");
        Objects.requireNonNull(sourceType, "ProviderInput sourceType must not be null");
        originalFilename = originalFilename == null ? "" : originalFilename;
        mediaType = mediaType == null ? "" : mediaType;
    }
}
