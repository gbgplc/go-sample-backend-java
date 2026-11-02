package com.gbg.samples.onboarding.go;

import com.gbg.samples.onboarding.api.dto.Interaction;
import com.gbg.samples.onboarding.api.dto.JourneyStatus;

/**
 * @param testScenario the GBG Go test scenario the journey runs on; null for a
 *                     real run, so always null in mock and live modes.
 */
public record GoStartResult(String instanceId, JourneyStatus status, Interaction interaction, String testScenario) {

    public GoStartResult(String instanceId, JourneyStatus status, Interaction interaction) {
        this(instanceId, status, interaction, null);
    }
}
