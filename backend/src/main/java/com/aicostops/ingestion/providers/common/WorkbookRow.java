package com.aicostops.ingestion.providers.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One streamed XLSX data row. {@code rowNumber} is the spreadsheet row position
 * (1-based, header included) so reviewers can locate the row in Excel. Values are
 * keyed by the raw header text; blank cells are null.
 */
public record WorkbookRow(String sheetName, long rowNumber, Map<String, String> values) {

    public WorkbookRow {
        if (values == null || values.isEmpty()) {
            values = Map.of();
        } else {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }
}
