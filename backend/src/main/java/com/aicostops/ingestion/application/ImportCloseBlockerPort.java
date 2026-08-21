package com.aicostops.ingestion.application;

import java.time.Instant;
import java.util.List;

public interface ImportCloseBlockerPort {
    long countOpenImports(long organizationId, Instant periodStart, Instant periodEnd);
    List<Long> sampleOpenImportIds(long organizationId, Instant periodStart, Instant periodEnd,
            int limit);
}
