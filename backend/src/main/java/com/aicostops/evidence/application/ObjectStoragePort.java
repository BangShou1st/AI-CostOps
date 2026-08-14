package com.aicostops.evidence.application;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;

/**
 * S3-compatible object storage behind a MinIO-free application port.
 *
 * <p>The Evidence checksum contract is the explicit SHA-256 user metadata written
 * with {@link #put}; S3/MinIO ETag semantics are never treated as the SHA-256 truth.
 */
public interface ObjectStoragePort {

    /**
     * Uploads the file bytes to the deterministic object key with explicit SHA-256
     * user metadata and an exact byte size.
     */
    void put(String objectKey, Path file, long sizeBytes, String sha256);

    /** Returns size and explicit SHA-256 metadata for the key, or empty if absent. */
    Optional<StoredObjectMetadata> stat(String objectKey);

    /** Opens a fresh stream for the object bytes. Caller closes the stream. */
    InputStream open(String objectKey);
}
