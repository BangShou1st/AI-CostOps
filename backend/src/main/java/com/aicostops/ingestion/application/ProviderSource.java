package com.aicostops.ingestion.application;

import java.io.InputStream;

/**
 * Repeatable provider-file source. Every {@link #openStream()} call returns a fresh
 * stream, so {@code inspect()} and {@code parse()} never share an exhausted stream.
 */
public interface ProviderSource {

    InputStream openStream();

    long sizeBytes();

    String objectKey();
}
