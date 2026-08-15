package com.aicostops.ingestion.api;

import com.aicostops.ingestion.api.ImportWorkflowResponses.ImportResponse;
import com.aicostops.ingestion.application.ImportCommandResponseSerializer;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.AttemptSummary;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.EvidenceRef;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.ImportSummary;
import com.aicostops.ingestion.application.ImportWorkflowReadModels.ProviderAccountRef;
import com.aicostops.ingestion.domain.ImportAttemptStatus;
import com.aicostops.ingestion.domain.ImportAttemptTrigger;
import com.aicostops.ingestion.domain.ImportBatchStatus;
import com.aicostops.ingestion.domain.ImportSourceType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes command responses through the same browser-facing DTO mapping the
 * read API uses, so an idempotent replay returns byte-identical semantics.
 */
@Component
public class ImportWorkflowResponseSerializer implements ImportCommandResponseSerializer {

    private final ObjectMapper objectMapper;

    public ImportWorkflowResponseSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String importDetailJson(ImportSummary detail) {
        try {
            return objectMapper.writeValueAsString(ImportResponse.from(detail));
        } catch (Exception exception) {
            throw new IllegalStateException("Import detail response serialization failed", exception);
        }
    }

    @Override
    public ImportSummary importDetailFromJson(String json) {
        try {
            var response = objectMapper.readValue(json, ImportResponse.class);
            return new ImportSummary(
                    parseId(response.id()),
                    new EvidenceRef(parseId(response.evidence().id()),
                            response.evidence().originalFilename()),
                    new ProviderAccountRef(parseId(response.providerAccount().id()),
                            response.providerAccount().displayName()),
                    response.expectedProviderCode(),
                    response.sourceType() == null ? null : ImportSourceType.valueOf(response.sourceType()),
                    response.parserVersion(),
                    response.status() == null ? null : ImportBatchStatus.valueOf(response.status()),
                    response.periodStart(),
                    response.periodEnd(),
                    response.latestAttempt() == null ? null : new AttemptSummary(
                            parseId(response.latestAttempt().id()),
                            response.latestAttempt().attemptNo(),
                            response.latestAttempt().status() == null ? null
                                    : ImportAttemptStatus.valueOf(response.latestAttempt().status()),
                            response.latestAttempt().triggerType() == null ? null
                                    : ImportAttemptTrigger.valueOf(response.latestAttempt().triggerType()),
                            response.latestAttempt().predecessorAttemptId() == null ? null
                                    : parseId(response.latestAttempt().predecessorAttemptId()),
                            response.latestAttempt().parserVersion(),
                            response.latestAttempt().detectedProviderCode(),
                            response.latestAttempt().schemaFingerprint(),
                            response.latestAttempt().startedAt(),
                            response.latestAttempt().finishedAt(),
                            response.latestAttempt().createdAt(),
                            response.latestAttempt().recordsSeen(),
                            response.latestAttempt().recordsValid(),
                            response.latestAttempt().warningCount(),
                            response.latestAttempt().errorCount(),
                            response.latestAttempt().errorCode(),
                            response.latestAttempt().errorSummary()),
                    parseId(response.createdByMemberId()),
                    response.createdAt(),
                    response.updatedAt(),
                    response.confirmedAttemptId() == null ? null : parseId(response.confirmedAttemptId()),
                    response.retryable(),
                    response.cancelable());
        } catch (Exception exception) {
            throw new IllegalStateException("Stored import detail response is not valid", exception);
        }
    }

    private static long parseId(String id) {
        return Long.parseLong(id);
    }
}
