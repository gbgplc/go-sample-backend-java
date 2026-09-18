package com.gbg.samples.onboarding.api.dto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Decision {
    PASS("pass"),
    REFER("refer"),
    FAIL("fail");

    private final String wire;

    Decision(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
