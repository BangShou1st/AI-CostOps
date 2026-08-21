package com.aicostops.reconciliation.application.blockers;

import com.aicostops.reconciliation.application.CloseBlockerContext;
import com.aicostops.reconciliation.application.CloseBlockerProvider;
import com.aicostops.reconciliation.application.CloseBlockerResult;
import com.aicostops.reconciliation.domain.CloseBlockerCode;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class PendingCorrectionsBlockerProvider implements CloseBlockerProvider {

    @Override public CloseBlockerCode code() { return CloseBlockerCode.PENDING_CORRECTIONS; }

    @Override
    public CloseBlockerResult evaluate(CloseBlockerContext context) {
        return CloseBlockerResult.pass(code(), Map.of(
                "notApplicable", true,
                "model", "POSTED_ONLY",
                "reason", "M5 corrections have no durable pending state"));
    }
}
