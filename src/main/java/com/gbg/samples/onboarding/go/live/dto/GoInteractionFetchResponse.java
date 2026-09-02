package com.gbg.samples.onboarding.go.live.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** POST {baseUrl}journey/interaction/fetch response — see /docs/go-v2/api-reference/endpoint/fetch-interaction. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoInteractionFetchResponse(
        String instanceId,
        Journey journey,
        String interactionId,
        Map<String, Object> interaction,
        boolean processing,
        List<String> outstanding,
        List<String> instructions,
        GoResult result
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Journey(String status) {
    }
}
