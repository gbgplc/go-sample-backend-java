package com.gbg.samples.onboarding.go;

import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.JourneyStatus;

public record GoStartResult(String instanceId, JourneyStatus status, Interaction interaction) {
}
