package com.aicostops.evidence.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aicostops.evidence.application.ObjectStoragePort;
import com.aicostops.testsupport.MinioContainerSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@Tag("integration")
class MinioObjectStorageAdapterIntegrationTest extends MinioContainerSupport {

    @Autowired
    private ObjectStoragePort storage;

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
}
