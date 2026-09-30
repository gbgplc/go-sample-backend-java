package com.gbg.samples.onboarding.api;

import com.gbg.samples.onboarding.api.dto.ErrorCode;
import com.gbg.samples.onboarding.api.dto.ErrorEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps every failure onto the one error envelope shape the front end expects
 * (front-end handoff, section 2) — an HTTP status, a stable machine `code`,
 * a customer-safe `message`, an optional per-field `fields` map, and `retryable`.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} rather than catching only
 * {@link Exception} at the bottom: that catch-all previously mapped every
 * Spring MVC client error — malformed JSON, a missing multipart part, an
 * unsupported content-type, a disallowed method, an unmatched path — onto
 * "500, retryable", which is both the wrong status and a lie about
 * retryability (retrying a malformed request fails identically every time).
 * {@link ResponseEntityExceptionHandler} already computes the right status
 * for each of those; {@link #handleExceptionInternal} below is the one hook
 * every one of its built-in handler methods funnels through, so overriding
 * it once covers all of them without enumerating each exception type.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(OnboardingException.class)
    public ResponseEntity<ErrorEnvelope> handleOnboarding(OnboardingException ex) {
        ErrorCode code = ex.code();
        ErrorEnvelope body = new ErrorEnvelope(code, code.httpStatus().value(), ex.getMessage(), ex.fields(), ex.retryable());
        return ResponseEntity.status(code.httpStatus()).body(body);
    }

    /**
     * {@link ResponseEntityExceptionHandler} already has a built-in mapping
     * for this exact exception type (through its own {@code handleException}),
     * so a separate {@code @ExceptionHandler} method for it is ambiguous to
     * Spring at startup — {@code BeanCreationException:
     * "Ambiguous @ExceptionHandler method mapped for ... MethodArgumentNotValidException"}.
     * Overriding this specific protected hook is the supported way to
     * customise one exception type's handling without that clash.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
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

    /**
     * {@link ResponseEntityExceptionHandler} has a built-in mapping for this
     * one too (confirmed by reading the real 6.2.19 class — it isn't in
     * every Spring version), so this overrides the specific protected hook
     * rather than adding a separate {@code @ExceptionHandler}, for the same
     * ambiguity reason as {@link #handleMethodArgumentNotValid}.
     */
    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ErrorEnvelope body = new ErrorEnvelope(
                ErrorCode.PAYLOAD_TOO_LARGE,
                ErrorCode.PAYLOAD_TOO_LARGE.httpStatus().value(),
                "That file is too large. Try a smaller image.",
                null,
                false);
        return ResponseEntity.status(ErrorCode.PAYLOAD_TOO_LARGE.httpStatus()).body(body);
    }

    /**
     * Catches everything {@link ResponseEntityExceptionHandler} itself
     * resolves a status for — {@code HttpMessageNotReadableException}
     * (malformed JSON), {@code MissingServletRequestPartException} (no
     * {@code file} part on {@code /attachments}), {@code
     * HttpMediaTypeNotSupportedException}, {@code
     * HttpRequestMethodNotSupportedException}, {@code
     * NoResourceFoundException} (an unknown path), and more — translated
     * into our one envelope shape instead of Spring's default
     * {@code ProblemDetail} body, using the status Spring already computed
     * rather than re-deriving it.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        log.warn("Client/framework error: {} {}", statusCode, ex.toString());
        ErrorCode code = codeFor(statusCode);
        ErrorEnvelope envelope = new ErrorEnvelope(
                code, statusCode.value(), "The request could not be processed.", null, code.defaultRetryable());
        return ResponseEntity.status(statusCode).body(envelope);
    }

    private static ErrorCode codeFor(HttpStatusCode statusCode) {
        if (statusCode.isSameCodeAs(HttpStatus.NOT_FOUND)) return ErrorCode.NOT_FOUND;
        if (statusCode.isSameCodeAs(HttpStatus.METHOD_NOT_ALLOWED)) return ErrorCode.METHOD_NOT_ALLOWED;
        if (statusCode.isSameCodeAs(HttpStatus.UNSUPPORTED_MEDIA_TYPE)) return ErrorCode.UNSUPPORTED_MEDIA_TYPE;
        if (statusCode.is4xxClientError()) return ErrorCode.BAD_REQUEST;
        // ResponseEntityExceptionHandler's own built-ins are all 4xx; a 5xx
        // reaching here would be unexpected, not a client mistake.
        return ErrorCode.UPSTREAM_UNAVAILABLE;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorEnvelope> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        ErrorEnvelope body = new ErrorEnvelope(
                ErrorCode.UPSTREAM_UNAVAILABLE,
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Something went wrong on our end. Try again shortly.",
                null,
                true);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
