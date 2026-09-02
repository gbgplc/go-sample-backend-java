package com.gbg.samples.onboarding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Go client credentials and endpoint selection. Neither {@code clientId} nor
 * {@code clientSecret} may ever reach the browser — they exist only here,
 * server-side (front-end handoff, section 1).
 *
 * {@code mode: mock} (the default) runs against {@link com.gbg.samples.onboarding.go.mock.MockGoClient}'s
 * canned fixtures, so this service runs with no live Go credentials present,
 * mirroring the front-end's own mock-mode requirement. Set {@code mode: live}
 * plus the fields below to proxy the real GBG Go API v2.
 */
@ConfigurationProperties(prefix = "go")
public record GoProperties(
        @DefaultValue("mock") String mode,
        @DefaultValue("eu") String region,
        @DefaultValue("https://api.auth.gbgplc.com/as/token.oauth2") String authUrl,
        @DefaultValue("gbg.token") String scope,
        String clientId,
        String clientSecret
) {
    /** Regional base URL per the API reference — eu/us/au, e.g. https://eu.platform.go.gbgplc.com/v2/captain/. */
    public String baseUrl() {
        return "https://" + region + ".platform.go.gbgplc.com/v2/captain/";
    }

    public boolean live() {
        return "live".equalsIgnoreCase(mode);
    }
}
