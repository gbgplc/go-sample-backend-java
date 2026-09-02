package com.gbg.samples.onboarding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Per-app presentation configuration (front-end handoff, section 2, GET
 * /config) — brand values live here, not in the front-end bundle, so a
 * deployment can be re-pointed without a rebuild. One deployment of this
 * service fronts exactly one market; {@code market} just labels which.
 */
@ConfigurationProperties(prefix = "app")
public record AppConfigProperties(
        String market,
        String brand,
        String mark,
        String tagline,
        String accent,
        String accentSoft,
        String helpLine,
        String journeyName,
        String resourceId,
        List<String> corsAllowedOrigins
) {
}
