package com.aicostops.reconciliation.application.blockers;

import com.aicostops.ingestion.application.ImportCloseBlockerPort;
import com.aicostops.reconciliation.application.CloseBlockerContext;
import com.aicostops.reconciliation.application.CloseBlockerProvider;
import com.aicostops.reconciliation.application.CloseBlockerResult;
import com.aicostops.reconciliation.domain.CloseBlockerCode;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class OpenImportsBlockerProvider implements CloseBlockerProvider {
    private static final int SAMPLE_LIMIT = 20;
    private final ImportCloseBlockerPort imports;

    public OpenImportsBlockerProvider(ImportCloseBlockerPort imports) {
        this.imports = imports;
    }

    @Override public CloseBlockerCode code() { return CloseBlockerCode.OPEN_IMPORTS; }

    @Override
    public CloseBlockerResult evaluate(CloseBlockerContext context) {
        var count = imports.countOpenImports(
                context.organizationId(), context.periodStart(), context.periodEnd());
        var summary = Map.<String, Object>of(
                "sampleImportBatchIds", imports.sampleOpenImportIds(
                        context.organizationId(), context.periodStart(), context.periodEnd(), SAMPLE_LIMIT),
                "unknownPeriodIsRelevant", true);
        return count == 0 ? CloseBlockerResult.pass(code(), summary)
                : CloseBlockerResult.fail(code(), count, summary);
    }
}
