package com.aicostops.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * MySQL + MinIO containers for storage-backed integration tests.
 *
 * <p>Tests that need real S3-compatible object storage extend this class; tests that
 * only need MySQL keep extending {@link MySqlContainerSupport}.
 */
public abstract class MinioContainerSupport extends MySqlContainerSupport {

    private static final String MINIO_ACCESS_KEY = "minio-test-access";
    private static final String MINIO_SECRET_KEY = "minio-test-only-secret-key";
    private static final String MINIO_BUCKET = "aicostops-evidence-test";

    protected static final GenericContainer<?> MINIO = new GenericContainer<>(
            DockerImageName.parse("quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z"))
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", MINIO_ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", MINIO_SECRET_KEY)
            .withCommand("server", "/data", "--console-address", ":9001")
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    static {
        MINIO.start();
    }

    @DynamicPropertySource
    static void registerMinioProperties(DynamicPropertyRegistry registry) {
        registry.add("aicostops.storage.endpoint",
                () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        registry.add("aicostops.storage.access-key", () -> MINIO_ACCESS_KEY);
        registry.add("aicostops.storage.secret-key", () -> MINIO_SECRET_KEY);
        registry.add("aicostops.storage.bucket", () -> MINIO_BUCKET);
        registry.add("aicostops.storage.auto-create-bucket", () -> "true");
    }
}
