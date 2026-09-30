package com.gbg.samples.onboarding.api.dto;

import org.springframework.http.HttpStatus;

/** Front-end handoff, section 2 — "Error envelope". Each code owns one HTTP status and one client treatment. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, true),
    SESSION_EXPIRED(HttpStatus.GONE, false),
    INTERACTION_STALE(HttpStatus.CONFLICT, true),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true),
    /**
     * Malformed request the client sent — unreadable JSON, a wrong/missing
     * multipart part, an unrecognised query param shape, and the like.
     * Retrying the identical request will fail the same way every time.
     */
    BAD_REQUEST(HttpStatus.BAD_REQUEST, false),
    /** An unknown path. Never true of the documented contract — a client bug or a stale URL. */
    NOT_FOUND(HttpStatus.NOT_FOUND, false),
    /** The documented path called with the wrong HTTP method. */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, false),
    /** A request body Content-Type this endpoint doesn't accept. */
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, false),
    /** An upload past {@code spring.servlet.multipart.max-file-size}. */
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, false);

    private final HttpStatus httpStatus;
    private final boolean defaultRetryable;

    ErrorCode(HttpStatus httpStatus, boolean defaultRetryable) {
        this.httpStatus = httpStatus;
        this.defaultRetryable = defaultRetryable;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public boolean defaultRetryable() {
        return defaultRetryable;
    }
}
