package com.gbg.samples.onboarding.api;

import com.gbg.samples.onboarding.api.dto.ErrorCode;

import java.util.Map;

/** Carries an {@link ErrorCode} straight through to the HTTP response as the contract's error envelope. */
public class OnboardingException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, String> fields;
    private final boolean retryableOverride;
    private final boolean hasRetryableOverride;

    public OnboardingException(ErrorCode code, String message) {
        this(code, message, null, false, false);
    }

    public OnboardingException(ErrorCode code, String message, Map<String, String> fields) {
        this(code, message, fields, false, false);
    }

    private OnboardingException(ErrorCode code, String message, Map<String, String> fields,
                                 boolean retryableOverride, boolean hasRetryableOverride) {
        super(message);
        this.code = code;
        this.fields = fields;
        this.retryableOverride = retryableOverride;
        this.hasRetryableOverride = hasRetryableOverride;
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

    public ErrorCode code() {
        return code;
    }

    public Map<String, String> fields() {
        return fields;
    }

    public boolean retryable() {
        return hasRetryableOverride ? retryableOverride : code.defaultRetryable();
    }
}
