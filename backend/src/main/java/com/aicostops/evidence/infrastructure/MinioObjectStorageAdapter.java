package com.aicostops.evidence.infrastructure;

import com.aicostops.evidence.application.ObjectStoragePort;
import com.aicostops.evidence.application.StoredObjectMetadata;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MinIO/S3-backed {@link ObjectStoragePort}.
 *
 * <p>The bucket is initialized lazily on the first actual storage operation, never
 * during bean construction or application startup, so M1 contexts boot without MinIO.
 */
public class MinioObjectStorageAdapter implements ObjectStoragePort {

    private static final String SHA_256_METADATA_KEY = "sha256";

    private final MinioClient client;
    private final String bucket;
    private final boolean autoCreateBucket;
    private final AtomicBoolean bucketInitialized = new AtomicBoolean(false);
    private final Object bucketInitMonitor = new Object();

    public MinioObjectStorageAdapter(MinioClient client, String bucket, boolean autoCreateBucket) {
        this.client = client;
        this.bucket = bucket;
        this.autoCreateBucket = autoCreateBucket;
    }

    @Override
    public void put(String objectKey, Path file, long sizeBytes, String sha256) {
        ensureBucket();
        try (var in = Files.newInputStream(file)) {
            client.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, sizeBytes, -1L)
                    .userMetadata(Map.of(SHA_256_METADATA_KEY, sha256))
                    .build());
        } catch (Exception exception) {
            throw new ObjectStorageException("Failed to store evidence object", exception);
        }
    }

    @Override
    public Optional<StoredObjectMetadata> stat(String objectKey) {
        ensureBucket();
        try {
            var stat = client.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            var sha256 = stat.userMetadata().getFirst(SHA_256_METADATA_KEY);
            return Optional.of(new StoredObjectMetadata(stat.size(), sha256));
        } catch (ErrorResponseException error) {
            if (error.errorResponse() != null && "NoSuchKey".equals(error.errorResponse().code())) {
                return Optional.empty();
            }
            throw new ObjectStorageException("Failed to stat evidence object", error);
        } catch (Exception exception) {
            throw new ObjectStorageException("Failed to stat evidence object", exception);
        }
    }

    @Override
    public InputStream open(String objectKey) {
        ensureBucket();
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new ObjectStorageException("Failed to open evidence object", exception);
        }
    }

    private void ensureBucket() {
        if (bucketInitialized.get()) {
            return;
        }
        // Double-checked locking: probe + create must be atomic within this JVM so
        // two first-time callers cannot both try to create the same bucket.
        synchronized (bucketInitMonitor) {
            if (bucketInitialized.get()) {
                return;
            }
            try {
                var exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    if (!autoCreateBucket) {
                        throw new ObjectStorageException(
                                "Evidence bucket '" + bucket + "' does not exist and auto-creation is disabled");
                    }
                    try {
                        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    } catch (ErrorResponseException alreadyExists) {
                        // Another instance may have created the bucket between our probe
                        // and create; that is success only if the bucket now exists.
                        if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                            throw alreadyExists;
                        }
                    }
                }
                // Only memoize after a successful probe so a transient outage can be retried later.
                bucketInitialized.set(true);
            } catch (ObjectStorageException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new ObjectStorageException("Failed to initialize evidence bucket", exception);
            }
        }
    }
}
