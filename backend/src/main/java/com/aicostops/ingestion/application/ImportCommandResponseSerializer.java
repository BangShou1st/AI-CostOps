package com.aicostops.ingestion.application;

import com.aicostops.ingestion.application.ImportWorkflowReadModels.ImportSummary;

/**
 * Serializes the Import detail response body stored in the idempotency table.
 * Implemented at the HTTP layer so the command service never depends on API DTOs.
 */
public interface ImportCommandResponseSerializer {

    String importDetailJson(ImportSummary detail);

    ImportSummary importDetailFromJson(String json);
}
