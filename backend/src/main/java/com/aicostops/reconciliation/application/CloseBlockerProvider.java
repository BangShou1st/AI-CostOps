package com.aicostops.reconciliation.application;

import com.aicostops.reconciliation.domain.CloseBlockerCode;

public interface CloseBlockerProvider {
    CloseBlockerCode code();
    CloseBlockerResult evaluate(CloseBlockerContext context);
}
