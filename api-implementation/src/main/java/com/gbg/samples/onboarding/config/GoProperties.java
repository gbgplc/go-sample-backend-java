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
 *
 * <h2>Two tenant shapes</h2>
 * The public documented platform and the nonprod <em>fabric</em> tenants
 * disagree on both auth and host layout, so both are configurable:
 *
 * <ul>
 *   <li><b>Public</b> — PingFederate at {@code api.auth.gbgplc.com}, the
 *       {@code client_credentials} grant with {@code scope=gbg.token}, and a
 *       region-prefixed API host ({@code eu.platform.go.gbgplc.com}).</li>
 *   <li><b>Fabric nonprod</b> — a Keycloak realm, the {@code password} grant
 *       (client id and secret <em>plus</em> username and password), and an API
 *       host whose region is part of the tenant name. Its token host carries no
 *       region segment while its API host does; that asymmetry is real, which is
 *       why {@link #baseUrl} can be set outright rather than always composed
 *       from {@link #region}.</li>
 * </ul>
 *
 * Verified against {@code gbggo4-demo} on 2026-09-07: the password grant against
 * the Keycloak realm returns a Bearer token, and journey start, interaction
 * fetch and interaction submit all succeed against the {@code -eu} API host.
 */
@ConfigurationProperties(prefix = "go")
public record GoProperties(
        @DefaultValue("mock") String mode,
        @DefaultValue("eu") String region,
        @DefaultValue("https://api.auth.gbgplc.com/as/token.oauth2") String authUrl,
        @DefaultValue("gbg.token") String scope,
        /** {@code client_credentials} (public platform) or {@code password} (fabric nonprod). */
        @DefaultValue("client_credentials") String grantType,
        String clientId,
        String clientSecret,
        /** Password grant only; ignored under client_credentials. */
        String username,
        /** Password grant only; ignored under client_credentials. */
        String password,
        /**
         * Full API base URL, trailing slash optional. Leave unset to compose the
         * documented public-platform host from {@link #region}; set it explicitly
         * for a tenant that doesn't follow that pattern.
         */
        String baseUrl
) {
    /** Regional base URL per the API reference — eu/us/au, e.g. https://eu.platform.go.gbgplc.com/v2/captain/. */
    public String baseUrl() {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        }
        return "https://" + region + ".platform.go.gbgplc.com/v2/captain/";
    }

    public boolean live() {
        return "live".equalsIgnoreCase(mode);
    }

    /** True when this tenant authenticates with the password grant rather than client_credentials. */
    public boolean passwordGrant() {
        return "password".equalsIgnoreCase(grantType);
    }
}
