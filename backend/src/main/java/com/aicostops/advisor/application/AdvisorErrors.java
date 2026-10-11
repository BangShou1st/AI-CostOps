package com.aicostops.advisor.application;

import com.aicostops.shared.web.DomainException;
import com.aicostops.shared.web.ProblemCode;
import org.springframework.http.HttpStatus;

final class AdvisorErrors {
    private AdvisorErrors() {}

    static DomainException validationFailed(String detail) {
        return new DomainException(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Advisor validation failed", detail);
    }

    static DomainException notFound(String detail) {
        return new DomainException(HttpStatus.NOT_FOUND, ProblemCode.RESOURCE_NOT_FOUND,
                "Advisor resource not found", detail);
    }

    static DomainException stateConflict(String detail) {
        return new DomainException(HttpStatus.CONFLICT, ProblemCode.STATE_CONFLICT,
                "Advisor state conflict", detail);
    }
}
