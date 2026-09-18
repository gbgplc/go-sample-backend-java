package com.gbg.samples.onboarding.api.dto;

import org.springframework.http.HttpStatus;

/** Front-end handoff, section 2 — "Error envelope". Each code owns one HTTP status and one client treatment. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, true),
    SESSION_EXPIRED(HttpStatus.GONE, false),
    INTERACTION_STALE(HttpStatus.CONFLICT, true),
    UPSTREAM_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true);

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
