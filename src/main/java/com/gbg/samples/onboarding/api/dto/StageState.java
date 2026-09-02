package com.gbg.samples.onboarding.api.dto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum StageState {
    DONE("done"),
    ACTIVE("active"),
    UPCOMING("upcoming");

    private final String wire;

    StageState(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
