package com.gbg.samples.onboarding.go.live.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** POST {baseUrl}journey/state/fetch response — see /docs/go-v2/api-reference/endpoint/fetch-journey-state. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoStateResponse(String instanceId, String status, Journey journey, List<Step> steps, GoResult result) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Journey(String id, String name, String version, String startedAt, String endedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(String nodeId, String name, String outcome, String outcomeClassification) {
    }
}
