package com.gbg.samples.onboarding.api.dto;

/** GET /config — per-app presentation configuration (front-end handoff, section 2). */
public record AppConfigResponse(
        String brand,
        String mark,
        String tagline,
        String accent,
        String accentSoft,
        String helpLine,
        String journeyName,
        String resourceId
) {
}
