package com.gbg.samples.onboarding.api.dto;

public record StartSessionResponse(String sessionId, JourneyStatus status, Interaction interaction) {
}
