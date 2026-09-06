package com.flashreserve.common;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** Global error model: stable codes, no stack traces leaked. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(String code, String message, String requestId, Instant timestamp,
                           Map<String, Object> details) {
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex) {
        return build(ex.getCode().name(), ex.getMessage(), ex.getCode().httpStatus());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException ex) {
        return build("NOT_FOUND", ex.getMessage(), 404);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> fields = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        fe -> fe.getField(),
                        fe -> String.valueOf(fe.getDefaultMessage()),
                        (a, b) -> a,
                        LinkedHashMap::new));
        return build("INVALID_REQUEST", "Request validation failed", 400, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        Map<String, Object> fields = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        v -> v.getPropertyPath().toString(),
                        ConstraintViolation::getMessage,
                        (a, b) -> a,
                        LinkedHashMap::new));
        return build("INVALID_REQUEST", "Request validation failed", 400, fields);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiError> handleUnreadable(Exception ex) {
        return build("INVALID_REQUEST", "Malformed request body or parameter", 400);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        return build("NOT_FOUND", "No such endpoint", 404);
    }

    /**
     * The strict HTTP firewall rejects malformed/unsafe requests (e.g.
     * form-encoded bodies with non-identifier parameter names). That is a
     * client error, not a server fault — must surface as 400, never 500.
     */
    @ExceptionHandler(org.springframework.security.web.firewall.RequestRejectedException.class)
    public ResponseEntity<ApiError> handleFirewallRejection(
            org.springframework.security.web.firewall.RequestRejectedException ex) {
        return build("INVALID_REQUEST", "Malformed request rejected", 400);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build("INTERNAL_ERROR", "An unexpected error occurred", 500);
    }

    private ResponseEntity<ApiError> build(String code, String message, int status) {
        return build(code, message, status, null);
    }

    private ResponseEntity<ApiError> build(String code, String message, int status, Map<String, Object> details) {
        String requestId = MDC.get("requestId");
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, requestId, Instant.now(), details));
    }
}
