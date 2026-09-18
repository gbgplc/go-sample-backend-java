package com.gbg.samples.onboarding.api.dto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ModuleState {
    PASS("Pass"),
    RUNNING("Running"),
    REVIEW("Review"),
    FAIL("Fail"),
    SKIPPED("Skipped");

    private final String wire;

    ModuleState(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
