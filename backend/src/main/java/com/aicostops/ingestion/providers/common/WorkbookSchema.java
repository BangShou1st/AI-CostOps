package com.aicostops.ingestion.providers.common;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Header schema of one XLSX workbook: sheet name -> raw header cells of the first
 * non-empty row. A present sheet with no non-empty row maps to an empty header list,
 * which lets adapters distinguish "sheet exists but is empty" from "sheet missing".
 */
public record WorkbookSchema(Map<String, List<String>> headersBySheet) {

    public WorkbookSchema {
        headersBySheet = headersBySheet == null ? Map.of() : headersBySheet.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    public Optional<List<String>> headers(String sheetName) {
        return Optional.ofNullable(headersBySheet.get(sheetName));
    }
}
