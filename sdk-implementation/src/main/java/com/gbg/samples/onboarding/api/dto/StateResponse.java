package com.gbg.samples.onboarding.api.dto;

import java.util.List;

public record StateResponse(JourneyStatus status, Decision decision, List<ModuleRun> moduleRuns) {
}
