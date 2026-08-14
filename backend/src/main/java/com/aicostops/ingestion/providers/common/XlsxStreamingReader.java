package com.aicostops.ingestion.providers.common;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler.SheetContentsHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.util.XMLHelper;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * Event/SAX-style XLSX reader built on POI's {@code OPCPackage} + {@code XSSFReader}.
 *
 * <p>Only the current row is held in memory: the handler builds one row map, emits it
 * and clears it. Production parsing never constructs a full {@code XSSFWorkbook}
 * object graph, and POI's default ZIP-bomb thresholds are never relaxed. Formula
 * cells expose the cached displayed value; no workbook formula is executed.
 *
 * <p>The first non-empty row of each sheet is treated as the header row; rows before
 * it are skipped. Data row numbers are real spreadsheet positions (1-based).
 */
@Component
public final class XlsxStreamingReader {

    public WorkbookSchema inspect(InputStream workbook) throws IOException {
        try {
            try (var pkg = OPCPackage.open(workbook)) {
                var headersBySheet = new LinkedHashMap<String, List<String>>();
                var reader = new XSSFReader(pkg);
                var strings = new ReadOnlySharedStringsTable(pkg);
                var styles = reader.getStylesTable();
                var sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
                while (sheets.hasNext()) {
                    try (var sheet = sheets.next()) {
                        var collector = new HeaderCollector();
                        parseSheet(sheet, styles, strings, collector, true);
                        headersBySheet.put(sheets.getSheetName(), collector.headers());
                    }
                }
                return new WorkbookSchema(headersBySheet);
            }
        } catch (OpenXML4JException | SAXException failure) {
            throw new IOException("Failed to inspect XLSX workbook", failure);
        }
    }

    public void forEachRow(
            InputStream workbook, Set<String> targetSheets, Consumer<WorkbookRow> consumer) throws IOException {
        try {
            try (var pkg = OPCPackage.open(workbook)) {
                var reader = new XSSFReader(pkg);
                var strings = new ReadOnlySharedStringsTable(pkg);
                var styles = reader.getStylesTable();
                var sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
                while (sheets.hasNext()) {
                    try (var sheet = sheets.next()) {
                        var sheetName = sheets.getSheetName();
                        if (!targetSheets.contains(sheetName)) {
                            continue;
                        }
                        parseSheet(sheet, styles, strings, new RowCollector(sheetName, consumer), false);
                    }
                }
            }
        } catch (OpenXML4JException | SAXException failure) {
            throw new IOException("Failed to stream XLSX workbook rows", failure);
        }
    }

    private static void parseSheet(
            InputStream sheet,
            org.apache.poi.xssf.model.StylesTable styles,
            ReadOnlySharedStringsTable strings,
            SheetContentsHandler handler,
            boolean abortAfterHeader) throws IOException {
        try {
            XMLReader xmlReader = XMLHelper.newXMLReader();
            xmlReader.setContentHandler(new XSSFSheetXMLHandler(styles, strings, handler, false));
            xmlReader.parse(new InputSource(sheet));
        } catch (HeaderFoundException stop) {
            if (!abortAfterHeader) {
                throw new IllegalStateException("Header stop must only be used during inspection", stop);
            }
        } catch (SAXException | ParserConfigurationException failure) {
            // Some SAX implementations wrap handler exceptions; unwrap the stop signal.
            if (abortAfterHeader && hasRootCause(failure, HeaderFoundException.class)) {
                return;
            }
            throw new IOException("Failed to stream XLSX sheet", failure);
        }
    }

    private static boolean hasRootCause(Throwable failure, Class<? extends Throwable> type) {
        var current = failure;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    // ------------------------------------------------------------------
    // callbacks
    // ------------------------------------------------------------------

    private abstract static class BaseCollector implements SheetContentsHandler {

        final List<String> rowCells = new ArrayList<>();
        int lastColumn = -1;

        @Override
        public void startRow(int rowNum) {
            rowCells.clear();
            lastColumn = -1;
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            var column = columnOf(cellReference, lastColumn);
            while (rowCells.size() <= column) {
                rowCells.add(null);
            }
            rowCells.set(column, formattedValue);
            lastColumn = column;
        }

        boolean isNonEmpty() {
            return rowCells.stream().anyMatch(cell -> cell != null && !cell.isBlank());
        }
    }

    private static final class HeaderCollector extends BaseCollector {

        private List<String> headers = List.of();
        private boolean found;

        @Override
        public void endRow(int rowNum) {
            if (!found && isNonEmpty()) {
                headers = List.copyOf(rowCells);
                found = true;
                throw new HeaderFoundException();
            }
        }

        List<String> headers() {
            return headers;
        }
    }

    private static final class RowCollector extends BaseCollector {

        private final String sheetName;
        private final Consumer<WorkbookRow> consumer;
        private List<String> header;

        private RowCollector(String sheetName, Consumer<WorkbookRow> consumer) {
            this.sheetName = sheetName;
            this.consumer = consumer;
        }

        @Override
        public void endRow(int rowNum) {
            if (header == null) {
                if (isNonEmpty()) {
                    header = List.copyOf(rowCells);
                }
                return;
            }
            if (!isNonEmpty()) {
                return; // fully blank rows carry no provider data
            }
            var values = new LinkedHashMap<String, String>();
            for (var i = 0; i < header.size(); i++) {
                values.put(header.get(i), i < rowCells.size() ? rowCells.get(i) : null);
            }
            consumer.accept(new WorkbookRow(sheetName, rowNum + 1L, values));
        }
    }

    private static int columnOf(String cellReference, int fallback) {
        if (cellReference == null) {
            return fallback + 1;
        }
        var letters = new StringBuilder();
        for (var i = 0; i < cellReference.length(); i++) {
            var ch = cellReference.charAt(i);
            if (Character.isLetter(ch)) {
                letters.append(ch);
            } else {
                break;
            }
        }
        var value = 0;
        for (var i = 0; i < letters.length(); i++) {
            value = value * 26 + (letters.charAt(i) - 'A' + 1);
        }
        return value - 1;
    }

    /** Internal signal to stop a sheet parse once the header row is complete. */
    private static final class HeaderFoundException extends RuntimeException {
    }
}
