package com.aicostops.ingestion.providers.fixtures;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Deterministic XLSX/CSV-ZIP fixture generation from explicit frozen schemas.
 *
 * <p>Every fixture produced here is synthetic and labeled by its evidence class in
 * the fixture README; no real private billing data is ever written into the
 * repository. Null cells in a row represent blank spreadsheet cells.
 *
 * <p>This helper exists only in tests; production parsing never builds a full
 * {@link XSSFWorkbook} object graph.
 */
public final class ProviderFixtureFactory {

    private ProviderFixtureFactory() {
    }

    public static byte[] xlsx(Map<String, List<List<String>>> sheets) throws IOException {
        try (var workbook = new XSSFWorkbook()) {
            for (var entry : sheets.entrySet()) {
                var sheet = workbook.createSheet(entry.getKey());
                var rowIndex = 0;
                for (var row : entry.getValue()) {
                    var xRow = sheet.createRow(rowIndex++);
                    for (var column = 0; column < row.size(); column++) {
                        var value = row.get(column);
                        if (value != null) {
                            xRow.createCell(column).setCellValue(value);
                        }
                    }
                }
            }
            var bytes = new ByteArrayOutputStream();
            workbook.write(bytes);
            return bytes.toByteArray();
        }
    }

    /** ZIP of raw file contents keyed by entry name, written in insertion order. */
    public static byte[] zip(Map<String, String> entries) throws IOException {
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
}
