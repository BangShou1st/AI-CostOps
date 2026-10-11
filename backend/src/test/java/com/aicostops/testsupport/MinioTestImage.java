package com.aicostops.testsupport;

import org.testcontainers.utility.DockerImageName;

/** One reproducible MinIO image for both storage-backed test container bases. */
final class MinioTestImage {

    // Public Chainguard MinIO image index (linux/amd64 and linux/arm64), pinned so
    // registry tag updates cannot silently change our S3 integration fixtures.
    private static final String DEFAULT_IMAGE =
            "cgr.dev/chainguard/minio@sha256:f74600a1a46330cdbda1ef760d17a96bd6e0f4a6f0a2c49792ca3ee7e4c6fa18";

    private MinioTestImage() {
    }

    static DockerImageName imageName() {
        return DockerImageName.parse(System.getProperty("aicostops.test.minio-image", DEFAULT_IMAGE));
    }
}
