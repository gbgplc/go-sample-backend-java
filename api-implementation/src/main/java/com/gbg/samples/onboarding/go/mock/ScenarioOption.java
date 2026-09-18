package com.gbg.samples.onboarding.go.mock;

import lombok.Builder;
import lombok.Value;

/** A choice option; {@code branchTo} names the scenario to continue in when this option is picked (mock only). */
@Value
@Builder
public class ScenarioOption {
    String value;
    String label;
    String detail;
    String icon;
    String branchTo;
}
