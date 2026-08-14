package com.aicostops.ingestion.providers.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class SafeZipReaderTest {

    private final ProviderParserProperties properties =
            new ProviderParserProperties(64, 1_073_741_824L, 100.0d, 1_048_576L, 10_000, 256);

    @Test
    void streamsTwoNormalCsvEntriesWithoutExtraction() throws IOException {
        var entries = new LinkedHashMap<String, String>();
        entries.put("amount-2026-08.csv", "user_id,model\n");
        entries.put("cost-2026-08.csv", "user_id,wallet_type\n");
        var zip = zip(entries);
        var seen = new LinkedHashMap<String, String>();

        new SafeZipReader(properties).forEachEntry(new ByteArrayInputStream(zip), (name, content) ->
                seen.put(name, new String(content.readAllBytes(), StandardCharsets.UTF_8)));

        assertThat(seen).containsExactly(
                Map.entry("amount-2026-08.csv", "user_id,model\n"),
                Map.entry("cost-2026-08.csv", "user_id,wallet_type\n"));
    }

    @Test
    void entryNamesAreNormalizedToForwardSlashes() throws IOException {
        var zip = zip(Map.of("folder\\amount.csv", "a\n"));

        var seen = new ArrayList<String>();
        new SafeZipReader(properties).forEachEntry(new ByteArrayInputStream(zip),
                (name, content) -> seen.add(name));

        assertThat(seen).containsExactly("folder/amount.csv");
    }

    @Test
    void rejectsParentTraversalEntries() throws IOException {
        assertRejected(zip(Map.of("../escape.csv", "x\n")), "escape.csv");
        assertRejected(zip(Map.of("a/../../escape.csv", "x\n")), "escape.csv");
    }

    @Test
    void rejectsAbsoluteAndWindowsDrivePaths() throws IOException {
        assertRejected(zip(Map.of("/absolute.csv", "x\n")), "/absolute.csv");
        assertRejected(zip(Map.of("C:/windows/evil.csv", "x\n")), "C:/windows/evil.csv");
    }

    @Test
    void rejectsNestedArchiveEntries() throws IOException {
        for (var nested : List.of("nested.zip", "data.tar", "backup.7z", "log.gz")) {
            var zip = zip(Map.of(nested, "PK"));
            assertThatThrownBy(() -> new SafeZipReader(properties)
                    .forEachEntry(new ByteArrayInputStream(zip), (name, content) -> { }))
                    .isInstanceOf(SafeZipReader.UnsafeArchiveException.class)
                    .extracting(e -> ((SafeZipReader.UnsafeArchiveException) e).reason())
                    .isEqualTo(SafeZipReader.UnsafeArchiveReason.NESTED_ARCHIVE);
        }
    }

    @Test
    void rejectsEntryCountAboveLimit() throws IOException {
        var many = new LinkedHashMap<String, String>();
        for (int i = 0; i < 65; i++) {
            many.put("f" + i + ".csv", "x\n");
        }
        var zip = zip(many);

        assertThatThrownBy(() -> new SafeZipReader(properties)
                .forEachEntry(new ByteArrayInputStream(zip), (name, content) -> { }))
                .isInstanceOf(SafeZipReader.UnsafeArchiveException.class)
                .extracting(e -> ((SafeZipReader.UnsafeArchiveException) e).reason())
                .isEqualTo(SafeZipReader.UnsafeArchiveReason.TOO_MANY_ENTRIES);
    }

    @Test
    void rejectsExpandedBytesAboveLimit() throws IOException {
        var properties = new ProviderParserProperties(64, 1_024L, 100.0d, 1_048_576L, 10_000, 256);
        var zip = zip(Map.of("big.csv", "A".repeat(2_048)));

        assertThatThrownBy(() -> new SafeZipReader(properties)
                .forEachEntry(new ByteArrayInputStream(zip), (name, content) -> { }))
                .isInstanceOf(SafeZipReader.UnsafeArchiveException.class)
                .extracting(e -> ((SafeZipReader.UnsafeArchiveException) e).reason())
                .isEqualTo(SafeZipReader.UnsafeArchiveReason.EXPANSION_LIMIT);
    }

    @Test
    void rejectsAbnormalCompressionRatioAfterCheckWindow() throws IOException {
        // ~2 MiB of repeated bytes deflates to a few KiB: ratio far above 100 after
        // the 1 MiB check window, but harmless to generate.
        var properties = new ProviderParserProperties(64, 1_073_741_824L, 100.0d, 1_048_576L, 10_000, 256);
        var zip = zip(Map.of("bomb.csv", "A".repeat(2 * 1_048_576)));

        assertThatThrownBy(() -> new SafeZipReader(properties)
                .forEachEntry(new ByteArrayInputStream(zip), (name, content) -> { }))
                .isInstanceOf(SafeZipReader.UnsafeArchiveException.class)
                .extracting(e -> ((SafeZipReader.UnsafeArchiveException) e).reason())
                .isEqualTo(SafeZipReader.UnsafeArchiveReason.EXPANSION_RATIO);
    }

    @Test
    void lowRatioLargeContentUnderLimitsStreamsFine() throws IOException {
        var properties = new ProviderParserProperties(64, 1_073_741_824L, 100.0d, 1_048_576L, 10_000, 256);
        // Deterministic pseudo-random bytes are incompressible: ratio ~1, size above
        // the check window, so the ratio defense must not false-positive.
        var random = new java.util.Random(42);
        var noise = new byte[2 * 1_048_576];
        random.nextBytes(noise);
        var zip = zipBytes(Map.of("noise.csv", noise));
        var seen = new long[1];

        new SafeZipReader(properties).forEachEntry(new ByteArrayInputStream(zip),
                (name, content) -> seen[0] = content.readAllBytes().length);

        assertThat(seen[0]).isEqualTo(noise.length);
    }

    @Test
    void expandedByteLimitFiresDuringReadNotAfterDrain() throws IOException {
        var properties = new ProviderParserProperties(64, 1_024L, 100.0d, 1_048_576L, 10_000, 256);
        var zip = zip(Map.of("big.csv", "A".repeat(64 * 1024)));
        var read = new long[1];

        assertThatThrownBy(() -> new SafeZipReader(properties).forEachEntry(
                new ByteArrayInputStream(zip), (name, content) -> {
                    var buffer = new byte[4096];
                    int n;
                    while ((n = content.read(buffer)) != -1) {
                        read[0] += n;
                    }
                }))
                .isInstanceOf(SafeZipReader.UnsafeArchiveException.class)
                .extracting(e -> ((SafeZipReader.UnsafeArchiveException) e).reason())
                .isEqualTo(SafeZipReader.UnsafeArchiveReason.EXPANSION_LIMIT);
        // The limit (1 KiB) fires while the consumer is still reading; it must not
        // consume the whole 64 KiB entry before the exception surfaces.
        assertThat(read[0]).isLessThan(8 * 1024);
    }

    @Test
    void ratioBombAfterIncompressiblePaddingIsStillRejected() throws IOException {
        var properties = new ProviderParserProperties(64, 1_073_741_824L, 100.0d, 1_048_576L, 10_000, 256);
        var random = new java.util.Random(42);
        var padding = new byte[2 * 1_048_576];
        random.nextBytes(padding);
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("padding.csv", padding);
        entries.put("bomb.csv", "A".repeat(2 * 1_048_576).getBytes(StandardCharsets.UTF_8));
        var zip = zipBytes(entries);

        assertThatThrownBy(() -> new SafeZipReader(properties).forEachEntry(
                new ByteArrayInputStream(zip), (name, content) -> {
                    var buffer = new byte[8192];
                    while (content.read(buffer) != -1) {
                        // consume
                    }
                }))
                .isInstanceOf(SafeZipReader.UnsafeArchiveException.class)
                .extracting(e -> ((SafeZipReader.UnsafeArchiveException) e).reason())
                .isEqualTo(SafeZipReader.UnsafeArchiveReason.EXPANSION_RATIO);
    }

    private void assertRejected(byte[] zip, String entry) {
        assertThatThrownBy(() -> new SafeZipReader(properties)
                .forEachEntry(new ByteArrayInputStream(zip), (name, content) -> { }))
                .isInstanceOf(SafeZipReader.UnsafeArchiveException.class)
                .extracting(e -> ((SafeZipReader.UnsafeArchiveException) e).reason())
                .isEqualTo(SafeZipReader.UnsafeArchiveReason.UNSAFE_PATH);
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] zipBytes(Map<String, byte[]> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @SuppressWarnings("unused")
    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
