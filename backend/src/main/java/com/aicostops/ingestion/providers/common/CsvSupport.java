package com.aicostops.ingestion.providers.common;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;

/**
 * Incremental CSV support on the frozen Commons CSV API:
 *
 * <pre>{@code CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get()}</pre>
 *
 * <p>Rows are emitted immediately and never materialized into a full in-memory list.
 * Header names are returned raw (BOM-stripped) so provider row maps keep original
 * header text; comparison uses {@link HeaderNormalizer}. Duplicate normalized headers
 * are rejected; unknown columns are preserved in the row map.
 *
 * <p>The caller owns the input stream lifecycle: the internal parser may close its
 * reader, but the supplied stream is never closed by this class, so callers can hand
 * in archive entry streams that must stay open for further iteration.
 */
public final class CsvSupport {

    public static final CSVFormat FORMAT = CSVFormat.RFC4180.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .get();

    private CsvSupport() {
    }

    /** Raw header names with the UTF-8 BOM stripped from the first cell. */
    public static List<String> readHeader(InputStream input) throws IOException {
        try (var parser = CSVParser.parse(nonClosing(input), StandardCharsets.UTF_8, FORMAT)) {
            var raw = parser.getHeaderNames();
            if (raw == null || raw.isEmpty()) {
                return List.of();
            }
            var header = stripBom(raw);
            var normalized = HeaderNormalizer.normalizeAll(header);
            if (normalized.stream().distinct().count() != normalized.size()) {
                throw new DuplicateCsvHeaderException(
                        "CSV header contains duplicate columns after normalization");
            }
            return List.copyOf(header);
        }
    }

    public static void forEachRecord(InputStream input, RowConsumer consumer) throws IOException {
        try (var parser = CSVParser.parse(nonClosing(input), StandardCharsets.UTF_8, FORMAT)) {
            var header = stripBom(parser.getHeaderNames());
            if (header.isEmpty()) {
                return;
            }
            var rowNumber = 0;
            for (var record : parser) {
                rowNumber++;
                var values = new LinkedHashMap<String, String>();
                for (var i = 0; i < header.size(); i++) {
                    values.put(header.get(i), i < record.size() ? record.get(i) : null);
                }
                consumer.accept(rowNumber, values);
            }
        }
    }

    private static InputStream nonClosing(InputStream input) {
        return new FilterInputStream(input) {
            @Override
            public void close() {
                // The caller owns the stream lifecycle (e.g. a ZIP entry stream that
                // must stay readable for the enclosing archive iteration).
            }
        };
    }

    private static List<String> stripBom(List<String> header) {
        if (header.isEmpty()) {
            return List.of();
        }
        var stripped = new ArrayList<>(header);
        var first = stripped.get(0);
        stripped.set(0, first.startsWith("\uFEFF") ? first.substring(1) : first);
        return List.copyOf(stripped);
    }

    @FunctionalInterface
    public interface RowConsumer {
        void accept(int rowNumber, Map<String, String> values);
    }

    /** Raised when normalized header names collide, making row semantics ambiguous. */
    public static final class DuplicateCsvHeaderException extends RuntimeException {
        public DuplicateCsvHeaderException(String message) {
            super(message);
        }
    }
}
