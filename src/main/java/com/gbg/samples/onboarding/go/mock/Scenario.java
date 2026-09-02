package com.gbg.samples.onboarding.go.mock;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class Scenario {
    String id;
    String label;
    @Singular List<ScenarioStep> steps;
}
