package com.gbg.samples.onboarding.api.dto;

import java.util.Map;

public record StartSessionRequest(Map<String, Object> prefill) {
}
