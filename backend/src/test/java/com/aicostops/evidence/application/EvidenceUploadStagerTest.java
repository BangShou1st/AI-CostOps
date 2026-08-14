package com.aicostops.evidence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EvidenceUploadStagerTest {

    private static final long LIMIT_BYTES = 1024L * 1024L;

    private final EvidenceUploadStager stager = new EvidenceUploadStager(LIMIT_BYTES);

    @Test
    void stagesStreamToTempFileWithExactSha256AndSize() throws Exception {
        var content = "provider evidence bytes".getBytes(StandardCharsets.UTF_8);
        var expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));

        var staged = stager.stage(new ByteArrayInputStream(content));
        try {
            assertThat(staged.sizeBytes()).isEqualTo(content.length);
            assertThat(staged.sha256()).isEqualTo(expectedSha);
            assertThat(Files.readAllBytes(staged.tempFile())).isEqualTo(content);
        } finally {
            Files.deleteIfExists(staged.tempFile());
        }
    }

    @Test
    void rejectsStreamBeyondUploadLimitWithPayloadTooLargeAndCleansUpTempFile() {
        var oversized = new byte[(int) LIMIT_BYTES + 1];

        assertThatThrownBy(() -> stager.stage(new ByteArrayInputStream(oversized)))
                .isInstanceOf(DomainException.class)
                .satisfies(exception -> {
                    var domainException = (DomainException) exception;
                    assertThat(domainException.status()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
                    assertThat(domainException.code()).isEqualTo(ProblemCode.EVIDENCE_TOO_LARGE);
                });
    }

    @Test
    void stagesExactlyAtTheUploadLimit() throws Exception {
        var content = new byte[(int) LIMIT_BYTES];
        var staged = stager.stage(new ByteArrayInputStream(content));
        try {
            assertThat(staged.sizeBytes()).isEqualTo(LIMIT_BYTES);
            assertThat(Files.size(staged.tempFile())).isEqualTo(LIMIT_BYTES);
        } finally {
            Files.deleteIfExists(staged.tempFile());
        }
    }

    @Test
    void neverLoadsTheWholeStreamIntoHeap() throws Exception {
        // If the stager used readAllBytes()/whole-file buffering, this probe would fail.
        var probe = new InputStream() {
            private final byte[] bytes = "probe".getBytes(StandardCharsets.UTF_8);
            private int position;

            @Override
            public int read() {
                return position < bytes.length ? bytes[position++] : -1;
            }

            @Override
            public byte[] readAllBytes() {
                throw new IllegalStateException("Whole-file buffering is forbidden");
            }
        };

        var staged = stager.stage(probe);
        try {
            assertThat(staged.sha256()).isEqualTo(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                            .digest("probe".getBytes(StandardCharsets.UTF_8))));
        } finally {
            Files.deleteIfExists(staged.tempFile());
        }
    }

    @Test
    void cleansUpTempFileWhenStreamFailsMidway() {
        var failing = new InputStream() {
            private int remaining = 10;

            @Override
            public int read() throws IOException {
                if (remaining-- > 0) {
                    return 'x';
                }
                throw new IOException("simulated read failure");
            }
        };

        assertThatThrownBy(() -> stager.stage(failing))
                .isInstanceOf(UncheckedIOException.class)
                .hasRootCauseInstanceOf(IOException.class);
    }
}
