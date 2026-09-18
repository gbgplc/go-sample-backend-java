package com.gbg.samples.onboarding.api.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/** The front end's eight render modes (front-end handoff, section 4). */
public enum ScreenKind {
    INTRO("intro"),
    FORM("form"),
    CHOICE("choice"),
    CAPTURE("capture"),
    UPLOAD("upload"),
    CONSENT("consent"),
    PROCESSING("processing"),
    RESULT("result");

    private final String wire;

    ScreenKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }
}
