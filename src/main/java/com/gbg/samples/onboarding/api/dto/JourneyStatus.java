package com.gbg.samples.onboarding.api.dto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum JourneyStatus {
    IN_PROGRESS("InProgress"),
    PENDING_INPUT("PendingInput"),
    COMPLETED("Completed");

    private final String wire;

    JourneyStatus(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
