package com.gbg.samples.onboarding.api;

import com.gbg.samples.onboarding.api.dto.ErrorCode;
import com.gbg.samples.onboarding.api.dto.ErrorEnvelope;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps every failure onto the one error envelope shape the front end expects
 * (front-end handoff, section 2) — an HTTP status, a stable machine `code`,
 * a customer-safe `message`, an optional per-field `fields` map, and `retryable`.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(OnboardingException.class)
    public ResponseEntity<ErrorEnvelope> handleOnboarding(OnboardingException ex) {
        ErrorCode code = ex.code();
        ErrorEnvelope body = new ErrorEnvelope(code, code.httpStatus().value(), ex.getMessage(), ex.fields(), ex.retryable());
        return ResponseEntity.status(code.httpStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorEnvelope> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                fields.put(fe.getField(), fe.getDefaultMessage() == null ? "Invalid value" : fe.getDefaultMessage()));
        ErrorEnvelope body = new ErrorEnvelope(
                ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.httpStatus().value(),
                "One or more fields could not be validated.",
                fields,
                true);
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.httpStatus()).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleUnexpected(Exception ex) {
        ErrorEnvelope body = new ErrorEnvelope(
                ErrorCode.UPSTREAM_UNAVAILABLE,
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Something went wrong on our end. Try again shortly.",
                null,
                true);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
