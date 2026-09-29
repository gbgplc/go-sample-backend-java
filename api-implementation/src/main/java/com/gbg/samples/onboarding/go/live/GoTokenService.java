package com.gbg.samples.onboarding.go.live;

import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.config.GoProperties;
import com.gbg.samples.onboarding.go.live.dto.GoTokenResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;

/**
 * Exchanges the Go client credentials for a Bearer token and caches it until
 * shortly before it expires. This is the one place {@code go.client-secret}
 * (and, under the password grant, {@code go.password}) is read.
 *
 * Two grants are supported, selected by {@code go.grant-type}:
 * <ul>
 *   <li>{@code client_credentials} — the documented public platform. Sends
 *       client id, secret and {@code scope=gbg.token} to PingFederate.</li>
 *   <li>{@code password} — the fabric nonprod tenants, which front Keycloak.
 *       Sends client id, secret, username and password. The scope is passed
 *       through as configured ({@code openid} on the realms seen so far);
 *       {@code gbg.token} is a PingFederate scope and is not valid there.</li>
 * </ul>
 *
 * Keycloak realms tend to issue short-lived tokens — 300 seconds on
 * {@code gbggo4-demo} versus the documented platform's 3600 — so the cache
 * turns over far more often under the password grant. The refresh margin
 * below is deliberately small enough to stay useful at that TTL.
 */
@Component
@ConditionalOnProperty(prefix = "go", name = "mode", havingValue = "live")
public class GoTokenService {

    private static final Logger log = LoggerFactory.getLogger(GoTokenService.class);
    private static final long REFRESH_MARGIN_SECONDS = 30;

    private final RestClient authClient;
    private final GoProperties properties;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiresAt = Instant.EPOCH;

    public GoTokenService(GoProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.authClient = builder.build();
    }

    /**
     * Double-checked rather than a single {@code synchronized} guarding both
     * the cache read and the mint: with one lock around the whole method,
     * every caller — including ones holding a perfectly valid cached token —
     * queued behind whichever thread was minting, and a stalled auth-server
     * response (this client has no HTTP timeout configured either — see
     * {@code RestClient.Builder}) meant every request thread in the pool
     * blocked on that lock indefinitely. {@code cachedToken}/
     * {@code cachedTokenExpiresAt} are already {@code volatile}, so the fast
     * path below is safe unsynchronized; only an actual expiry contends for
     * the lock, and only briefly.
     */
    public String accessToken() {
        String token = cachedToken;
        if (token != null && Instant.now().isBefore(cachedTokenExpiresAt)) {
            return token;
        }
        synchronized (this) {
            if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiresAt)) {
                return cachedToken;
            }
            return mintToken();
        }
    }

    private String mintToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", properties.grantType());
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("scope", properties.scope());
        if (properties.passwordGrant()) {
            form.add("username", properties.username());
            form.add("password", properties.password());
        }

        try {
            GoTokenResponse response = authClient.post()
                    .uri(properties.authUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(GoTokenResponse.class);
            if (response == null || response.access_token() == null) {
                throw OnboardingException.upstreamUnavailable("Could not authenticate with the identity platform.");
            }
            cachedToken = response.access_token();
            cachedTokenExpiresAt = Instant.now().plusSeconds(Math.max(0, response.expires_in() - REFRESH_MARGIN_SECONDS));
            return cachedToken;
        } catch (Exception e) {
            log.error("Failed to mint a Go access token", e);
            throw OnboardingException.upstreamUnavailable("Could not authenticate with the identity platform. Try again shortly.");
        }
    }
}
