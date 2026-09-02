package com.gbg.samples.onboarding.api.dto;

import java.util.Map;

/**
 * One shape for every failure (front-end handoff, section 2). The front end
 * decides its treatment from {@code code}, never by parsing {@code message}.
 */
public record ErrorEnvelope(
        ErrorCode code,
        int http,
        String message,
        Map<String, String> fields,
        boolean retryable
) {
}
