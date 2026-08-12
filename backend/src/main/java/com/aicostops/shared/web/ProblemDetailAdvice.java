package com.aicostops.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ProblemDetailAdvice {

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> handleDomainException(
            DomainException exception,
            HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(exception.status(), exception.getMessage());
        problem.setTitle(exception.title());
        problem.setType(URI.create("https://aicostops.dev/problems/" + toProblemSlug(exception.code())));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", exception.code().name());
        problem.setProperty("traceId", request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
        return ResponseEntity.status(exception.status()).body(problem);
    }

    private String toProblemSlug(ProblemCode code) {
        return code.name().toLowerCase().replace('_', '-');
    }
}
