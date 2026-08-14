package com.aicostops.evidence.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.evidence.application.ObjectStoragePort;
import com.aicostops.testsupport.MinioContainerSupport;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Tag("integration")
class MinioObjectStorageAdapterIntegrationTest extends MinioContainerSupport {

    @Autowired
    private ObjectStoragePort storage;
    @Autowired
    private MinioClient minioClient;

    @Test
    void storesAndReadsExactBytesWithExplicitSha256Metadata() throws Exception {
        var content = "provider-evidence-bytes".getBytes(StandardCharsets.UTF_8);
        var sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var key = "org/7/evidence/sha256/" + sha256.substring(0, 2) + "/" + sha256;
        var tempFile = Files.createTempFile("minio-adapter-", ".bin");
        try {
            Files.write(tempFile, content);

            storage.put(key, tempFile, content.length, sha256);

            var stat = storage.stat(key).orElseThrow();
            assertThat(stat.sizeBytes()).isEqualTo(content.length);
            assertThat(stat.sha256()).isEqualTo(sha256);

            try (var in = storage.open(key)) {
                assertThat(in.readAllBytes()).isEqualTo(content);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void missingObjectReturnsEmptyStat() {
        assertThat(storage.stat("org/7/evidence/sha256/ff/never-uploaded")).isEmpty();
    }

    @Test
    void statReportsExactSizeOfStoredObject() throws Exception {
        var content = "size-probe".getBytes(StandardCharsets.UTF_8);
        var sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        var key = "org/9/evidence/sha256/" + sha256.substring(0, 2) + "/" + sha256;
        var tempFile = Files.createTempFile("minio-adapter-size-", ".bin");
        try {
            Files.write(tempFile, content);
            storage.put(key, tempFile, content.length, sha256);

            var stat = storage.stat(key).orElseThrow();
            assertThat(stat.sizeBytes()).isEqualTo(content.length);
            assertThat(storage.open(key).readAllBytes()).hasSize(content.length);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void concurrentFirstUseOnAFreshBucketInitializesItExactlyOnce() throws Exception {
        // A brand-new bucket that no shared context has ever touched.
        var bucket = "aicostops-concurrent-" + UUID.randomUUID().toString().substring(0, 8);
        var freshAdapter = new MinioObjectStorageAdapter(minioClient, bucket, true);

        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            java.util.concurrent.Callable<Optional<com.aicostops.evidence.application.StoredObjectMetadata>> probe = () -> {
                start.await();
                return freshAdapter.stat("org/1/evidence/sha256/aa/probe");
            };
            var first = pool.submit(probe);
            var second = pool.submit(probe);
            start.countDown();

            assertThat(first.get(30, TimeUnit.SECONDS)).isEmpty();
            assertThat(second.get(30, TimeUnit.SECONDS)).isEmpty();
        } finally {
            pool.shutdownNow();
        }
        assertThat(minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(bucket).build())).isTrue();
    }
}
