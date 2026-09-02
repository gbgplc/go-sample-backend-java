package com.gbg.samples.onboarding.go.live.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/** The `result` object shared by interaction/fetch and state/fetch responses. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoResult(
        String outcome,
        String status,
        String outcomeClassification,
        List<Map<String, Object>> errors,
        Map<String, Object> data
) {
}
