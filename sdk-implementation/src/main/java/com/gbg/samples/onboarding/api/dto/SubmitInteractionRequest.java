package com.gbg.samples.onboarding.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record SubmitInteractionRequest(
        @NotBlank String interactionId,
        Map<String, Object> data
) {
}
