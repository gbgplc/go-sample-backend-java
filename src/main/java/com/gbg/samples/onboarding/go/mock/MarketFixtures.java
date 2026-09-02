package com.gbg.samples.onboarding.go.mock;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.Map;

@Value
@Builder
public class MarketFixtures {
    String defaultScenarioId;
    @Singular Map<String, Scenario> scenarios;
}
