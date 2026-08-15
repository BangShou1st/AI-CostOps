package com.aicostops.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingRequestHeaderException;

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
        var response = ResponseEntity.status(exception.status());
        if (exception.retryAfterSeconds() > 0) {
            response.header("Retry-After", Long.toString(exception.retryAfterSeconds()));
        }
        return response.body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ProblemCode.VALIDATION_FAILED,
                "Validation failed", "One or more request fields are invalid.", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleMalformed(HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ProblemCode.REQUEST_MALFORMED,
                "Malformed request", "The request body is not valid JSON.", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
            HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ProblemCode.REQUEST_MALFORMED,
                "Malformed request", "A request parameter has an invalid value.", request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException exception,
            HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ProblemCode.REQUEST_MALFORMED,
                "Malformed request", "A required request header is missing.", request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, ProblemCode code, String title,
            String detail, HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://aicostops.dev/problems/" + toProblemSlug(code)));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.name());
        problem.setProperty("traceId", request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE));
        return ResponseEntity.status(status).body(problem);
    }

    private String toProblemSlug(ProblemCode code) {
        return code.name().toLowerCase().replace('_', '-');
    }
}
