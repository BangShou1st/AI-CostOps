package com.aicostops.ingestion.application;

import com.aicostops.evidence.application.ObjectStoragePort;
import java.io.InputStream;

/**
 * Evidence-backed repeatable {@link ProviderSource}. Every {@link #openStream()}
 * opens a fresh object stream, so inspect and parse never share an exhausted stream.
 */
public class EvidenceBackedProviderSource implements ProviderSource {

    private final ObjectStoragePort storage;
    private final String objectKey;
    private final long sizeBytes;

    public EvidenceBackedProviderSource(ObjectStoragePort storage, String objectKey, long sizeBytes) {
        this.storage = storage;
        this.objectKey = objectKey;
        this.sizeBytes = sizeBytes;
    }

    @Override
    public InputStream openStream() {
        return storage.open(objectKey);
    }

    @Override
    public long sizeBytes() {
        return sizeBytes;
    }

    @Override
    public String objectKey() {
        return objectKey;
    }
}
