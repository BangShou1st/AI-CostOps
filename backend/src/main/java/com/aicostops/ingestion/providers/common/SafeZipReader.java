package com.aicostops.ingestion.providers.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.stereotype.Component;

/**
 * Streaming, bounded ZIP traversal using Commons Compress stream statistics.
 *
 * <p>Never extracts to the filesystem. Enforces path-traversal rejection, a maximum
 * entry count, bounded total expanded bytes, an abnormal compression-ratio defense
 * once enough bytes have been expanded, and rejection of nested archives for Group 2.
 * Entry names are normalized to forward slashes; directory entries are skipped.
 *
 * <p>The content {@link InputStream} handed to {@link EntryConsumer} is valid only
 * for the duration of the callback; consumers must fully consume or stop reading.
 */
@Component
public final class SafeZipReader {

    private static final Pattern NESTED_ARCHIVE = Pattern.compile(
            "(?i)\\.(zip|tar|tgz|gz|7z|rar)$");
    private static final Pattern WINDOWS_DRIVE = Pattern.compile("^[a-zA-Z]:/");

    private final ProviderParserProperties properties;

    public SafeZipReader(ProviderParserProperties properties) {
        this.properties = properties;
    }

    @FunctionalInterface
    public interface EntryConsumer {
        void accept(String entryName, InputStream content) throws IOException;
    }

    public void forEachEntry(InputStream archive, EntryConsumer consumer) throws IOException {
        var zip = new ZipArchiveInputStream(archive);
        var entries = 0;
        var expandedBytes = 0L;
        var compressedBytes = 0L;
        var uncompressedBytes = 0L;
        ZipArchiveEntry entry;
        while ((entry = zip.getNextZipEntry()) != null) {
            var rawName = entry.getName();
            if (rawName == null || rawName.isEmpty()) {
                continue;
            }
            var normalized = rawName.replace('\\', '/');
            if (normalized.endsWith("/")) {
                continue; // directory entries carry no provider data
            }
            entries++;
            if (entries > properties.maxArchiveEntries()) {
                throw new UnsafeArchiveException(UnsafeArchiveReason.TOO_MANY_ENTRIES, rawName);
            }
            if (isUnsafePath(normalized)) {
                throw new UnsafeArchiveException(UnsafeArchiveReason.UNSAFE_PATH, rawName);
            }
            if (NESTED_ARCHIVE.matcher(normalized).find()) {
                throw new UnsafeArchiveException(UnsafeArchiveReason.NESTED_ARCHIVE, rawName);
            }
            consumer.accept(normalized, zip);
            drain(zip);
            var entryExpanded = zip.getUncompressedCount() - uncompressedBytes;
            var entryCompressed = zip.getCompressedCount() - compressedBytes;
            uncompressedBytes = zip.getUncompressedCount();
            compressedBytes = zip.getCompressedCount();
            expandedBytes += entryExpanded;
            if (expandedBytes > properties.maxExpandedBytes()) {
                throw new UnsafeArchiveException(UnsafeArchiveReason.EXPANSION_LIMIT, rawName);
            }
            if (uncompressedBytes >= properties.compressionRatioCheckAfterBytes()) {
                if (compressedBytes == 0
                        || (double) uncompressedBytes / compressedBytes > properties.maxCompressionRatio()) {
                    throw new UnsafeArchiveException(UnsafeArchiveReason.EXPANSION_RATIO, rawName);
                }
            }
        }
    }

    private static void drain(InputStream content) throws IOException {
        var buffer = new byte[8192];
        while (content.read(buffer) != -1) {
            // Counts must reflect the full entry; consumers that stop early are drained here.
        }
    }

    private static boolean isUnsafePath(String normalized) {        if (normalized.startsWith("/") || WINDOWS_DRIVE.matcher(normalized).find()) {
            return true;
        }
        var seen = 0;
        for (var segment : normalized.split("/")) {
            if (segment.equals("..")) {
                return true;
            }
            if (!segment.isEmpty() && !segment.equals(".")) {
                seen++;
            }
        }
        return seen == 0;
    }

    /** Safety violation that makes an archive unusable for provider parsing. */
    public static final class UnsafeArchiveException extends RuntimeException {
        private final UnsafeArchiveReason reason;

        public UnsafeArchiveException(UnsafeArchiveReason reason, String entryName) {
            super("Unsafe archive entry '" + entryName + "': " + reason.name()
                    + " (reason codes are safe to persist; entry names are not)");
            this.reason = reason;
        }

        public UnsafeArchiveReason reason() {
            return reason;
        }
    }

    public enum UnsafeArchiveReason {
        UNSAFE_PATH,
        TOO_MANY_ENTRIES,
        EXPANSION_LIMIT,
        EXPANSION_RATIO,
        NESTED_ARCHIVE
    }
}
