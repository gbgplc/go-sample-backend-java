package com.gbg.samples.onboarding.api;

import com.gbg.samples.onboarding.api.dto.ErrorCode;

import java.util.Map;

/** Carries an {@link ErrorCode} straight through to the HTTP response as the contract's error envelope. */
public class OnboardingException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, String> fields;
    private final boolean retryable;

    private OnboardingException(ErrorCode code, String message) {
        this(code, message, null);
    }

    private OnboardingException(ErrorCode code, String message, Map<String, String> fields) {
        this(code, message, fields, code.defaultRetryable());
    }

    private OnboardingException(ErrorCode code, String message, Map<String, String> fields, boolean retryable) {
        super(message);
        this.code = code;
        this.fields = fields;
        this.retryable = retryable;
    }

    public static OnboardingException validationFailed(String message, Map<String, String> fields) {
        return new OnboardingException(ErrorCode.VALIDATION_FAILED, message, fields);
    }

    public static OnboardingException sessionExpired(String message) {
        return new OnboardingException(ErrorCode.SESSION_EXPIRED, message);
    }

    public static OnboardingException interactionStale(String message) {
        return new OnboardingException(ErrorCode.INTERACTION_STALE, message);
    }

    public static OnboardingException upstreamUnavailable(String message) {
        return new OnboardingException(ErrorCode.UPSTREAM_UNAVAILABLE, message);
    }

    /**
     * Go can't do this, and asking again won't change that — a configuration
     * fault such as a journey or test scenario that doesn't exist. Same code
     * as {@link #upstreamUnavailable}, but not retryable, so the client
     * doesn't offer "Try again" (which, at journey start, would create
     * another Go instance each time).
     */
    public static OnboardingException upstreamMisconfigured(String message) {
        return new OnboardingException(ErrorCode.UPSTREAM_UNAVAILABLE, message, null, false);
    }

    public static OnboardingException rateLimited(String message) {
        return new OnboardingException(ErrorCode.RATE_LIMITED, message);
    }

    public static OnboardingException badRequest(String message) {
        return new OnboardingException(ErrorCode.BAD_REQUEST, message);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, String> fields() {
        return fields;
    }

    public boolean retryable() {
        return retryable;
    }
}
