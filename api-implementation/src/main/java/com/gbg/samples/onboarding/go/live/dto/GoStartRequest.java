package com.gbg.samples.onboarding.go.live.dto;

import java.util.Map;

/** POST {baseUrl}journey/start request body — see /docs/go-v2/api-reference/endpoint/start-journey. */
public record GoStartRequest(String resourceId, Context context) {

    public record Context(Config config, Map<String, Object> subject) {
    }

    public record Config(String delivery) {
    }

    public static GoStartRequest of(String resourceId, Map<String, Object> prefillSubject) {
        Map<String, Object> subject = prefillSubject == null ? Map.of() : prefillSubject;
        return new GoStartRequest(resourceId, new Context(new Config("api"), subject));
    }
}
