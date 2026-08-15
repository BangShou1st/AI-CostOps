package com.aicostops.ingestion.providers.common;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.springframework.stereotype.Component;

/**
 * Streaming, bounded ZIP traversal using Commons Compress stream statistics.
 *
 * <p>Never extracts to the filesystem. Enforces path-traversal rejection, a maximum
 * entry count, bounded total expanded bytes, and an abnormal compression-ratio
 * defense. Limits are enforced <em>while bytes are being read</em>: the entry stream
 * handed to {@link EntryConsumer} is a monitoring wrapper that re-checks total
 * expanded bytes and the current entry's compressed/uncompressed delta on every
 * read, so an oversized or high-ratio entry fails the moment it crosses a limit
 * instead of after being fully consumed. Per-entry ratio detection cannot be diluted
 * by large incompressible padding entries. Nested archives are rejected for Group 2.
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
        var totalExpanded = 0L;
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
            var monitored = new MonitoredEntryStream(zip, properties, totalExpanded);
            consumer.accept(normalized, monitored);
            // Consumers may stop early; drain the remainder through the same monitor so
            // limits still apply and the archive stream stays positioned.
            var buffer = new byte[8192];
            while (monitored.read(buffer) != -1) {
                // drain
            }
            totalExpanded = monitored.totalExpanded();
        }
    }

    /**
     * Entry stream that enforces expanded-byte and per-entry ratio limits on every
     * read. The underlying {@link ZipArchiveInputStream} counts compressed bytes for
     * the current entry, so per-entry deltas are computed against the entry start.
     */
    private static final class MonitoredEntryStream extends FilterInputStream {

        private final ProviderParserProperties properties;
        private final long compressedAtEntryStart;
        private long totalExpanded;
        private long entryExpanded;

        private MonitoredEntryStream(
                ZipArchiveInputStream zip, ProviderParserProperties properties, long totalExpandedBefore) {
            super(zip);
            this.properties = properties;
            this.compressedAtEntryStart = zip.getCompressedCount();
            this.totalExpanded = totalExpandedBefore;
        }

        @Override
        public int read() throws IOException {
            var value = super.read();
            if (value != -1) {
                account(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            var read = super.read(buffer, offset, length);
            if (read > 0) {
                account(read);
            }
            return read;
        }

        @Override
        public void close() {
            // The caller owns the archive stream lifecycle; closing an entry must not
            // close the archive before the remaining entries are traversed.
        }

        private void account(int expandedDelta) {
            entryExpanded += expandedDelta;
            totalExpanded += expandedDelta;
            if (totalExpanded > properties.maxExpandedBytes()) {
                throw new UnsafeArchiveException(UnsafeArchiveReason.EXPANSION_LIMIT, "entry");
            }
            if (entryExpanded >= properties.compressionRatioCheckAfterBytes()) {
                var entryCompressed = ((ZipArchiveInputStream) in).getCompressedCount() - compressedAtEntryStart;
                if (entryCompressed <= 0
                        || (double) entryExpanded / entryCompressed > properties.maxCompressionRatio()) {
                    throw new UnsafeArchiveException(UnsafeArchiveReason.EXPANSION_RATIO, "entry");
                }
            }
        }

        long totalExpanded() {
            return totalExpanded;
        }
    }

    private static boolean isUnsafePath(String normalized) {
        if (normalized.startsWith("/") || WINDOWS_DRIVE.matcher(normalized).find()) {
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
