package com.gbg.samples.onboarding.go.live.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** GBG Go v2's own error envelope — distinct from, and translated into, this service's front-end-facing one. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GoErrorEnvelope(List<GoErrorItem> errors) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoErrorItem(String code, String name, String problem, String action, String location) {
    }
}
