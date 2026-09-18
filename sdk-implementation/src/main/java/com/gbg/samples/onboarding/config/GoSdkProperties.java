package com.gbg.samples.onboarding.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Go client credentials and endpoint selection — the go-core-sdk sibling of
 * api-implementation's {@code GoProperties}. Same field set, because the
 * two-tenant shape the SDK sits in front of hasn't changed: swapping the
 * transport from hand-rolled HTTP to {@code com.gbg.gocore.Go} doesn't touch
 * how credentials are configured, only how they're used (see
 * {@link com.gbg.samples.onboarding.go.sdk.GoSdkAuthService} and
 * {@link com.gbg.samples.onboarding.go.sdk.GoSdkClient}).
 *
 * Neither {@code clientId} nor {@code clientSecret} may ever reach the
 * browser — they exist only here, server-side (front-end handoff, section 1).
 *
 * {@code mode: mock} (the default) runs against {@link com.gbg.samples.onboarding.go.mock.MockGoClient}'s
 * canned fixtures, so this service runs with no live Go credentials present,
 * mirroring the front-end's own mock-mode requirement. Set {@code mode: live}
 * plus the fields below to proxy the real GBG Go API v2 through the SDK.
 *
 * <h2>Two tenant shapes</h2>
 * The public documented platform and the nonprod <em>fabric</em> tenants
 * disagree on both auth and host layout, so both are configurable:
 *
 * <ul>
 *   <li><b>Public</b> — PingFederate at {@code api.auth.gbgplc.com}, the
 *       {@code client_credentials} grant with {@code scope=gbg.token}, and a
 *       region-prefixed API host ({@code eu.platform.go.gbgplc.com}) that maps
 *       exactly onto {@code Go.builder().serverIndex(0/1/2)} for eu/us/au
 *       (confirmed against the SDK's own {@code Go.SERVERS} array — see
 *       {@code spike/sdk-jar-inspection/FINDINGS.md}, Q7).</li>
 *   <li><b>Fabric nonprod</b> — a Keycloak realm, the {@code password} grant
 *       (client id and secret <em>plus</em> username and password), and an API
 *       host whose region is part of the tenant name. Its token host carries no
 *       region segment while its API host does; that asymmetry is real, which is
 *       why {@link #baseUrl} can be set outright rather than always composed
 *       from {@link #region}, and maps onto {@code Go.builder().serverURL(...)}
 *       rather than {@code serverIndex(...)}.</li>
 * </ul>
 *
 * <b>Known SDK limitation (see sdk-implementation/README.md, "Known gaps"):</b>
 * {@code sdk.tokens().generate()} hardcodes the token endpoint's path to
 * {@code /as/token.oauth2} (verified by reading the generated
 * {@code PostAsTokenOauth2} operation class) and only lets a caller override
 * the <em>host</em> via a {@code serverURL} parameter, not the full path. That
 * matches the public platform's PingFederate host exactly. It is unverified
 * whether the fabric nonprod tenant's real Keycloak token endpoint also lives
 * at that exact path — if it doesn't, {@code GoSdkAuthService} will fail to
 * mint a token under the password grant no matter what {@link #authUrl} is set
 * to, because the SDK ignores everything in it except the scheme+host.
 */
@ConfigurationProperties(prefix = "go")
public record GoSdkProperties(
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

    /**
     * {@link #baseUrl} without a configured override maps onto one of the
     * SDK's built-in {@code Go.SERVERS} indices (0=eu, 1=us, 2=au) — used to
     * prefer {@code Go.builder().serverIndex(...)} over {@code .serverURL(...)}
     * whenever the region is one of the three documented ones and no explicit
     * {@link #baseUrl} override is configured.
     */
    public boolean usesDocumentedRegion() {
        return (baseUrl == null || baseUrl.isBlank())
                && ("eu".equalsIgnoreCase(region) || "us".equalsIgnoreCase(region) || "au".equalsIgnoreCase(region));
    }

    /** 0/1/2 for eu/us/au, matching {@code com.gbg.gocore.Go.SERVERS}. Only meaningful when {@link #usesDocumentedRegion()}. */
    public int serverIndex() {
        return switch (region.toLowerCase(java.util.Locale.ROOT)) {
            case "us" -> 1;
            case "au" -> 2;
            default -> 0;
        };
    }

    /**
     * The scheme+host portion of {@link #authUrl}, for {@code sdk.tokens().generate(request, serverURL)}'s
     * {@code serverURL} override — the SDK appends its own hardcoded
     * {@code /as/token.oauth2} path to whatever host is passed here (see the
     * class javadoc's "Known SDK limitation" note), so passing the full
     * configured {@link #authUrl} (which already includes a path) would double
     * up the path segment. Falls back to the full string if it cannot be
     * parsed as a URL, so a misconfigured value fails loudly against the SDK
     * rather than silently here.
     */
    public String authServerUrl() {
        try {
            java.net.URI uri = java.net.URI.create(authUrl);
            if (uri.getScheme() != null && uri.getHost() != null) {
                String portPart = uri.getPort() == -1 ? "" : ":" + uri.getPort();
                return uri.getScheme() + "://" + uri.getHost() + portPart;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through — return the configured value as-is.
        }
        return authUrl;
    }
}
