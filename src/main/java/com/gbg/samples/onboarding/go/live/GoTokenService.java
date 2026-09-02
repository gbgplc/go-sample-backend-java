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
 * Exchanges the Go client credentials for a Bearer token (POST
 * /as/token.oauth2, client_credentials grant) and caches it until shortly
 * before it expires. This is the one place {@code go.client-secret} is read.
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

    public synchronized String accessToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiresAt)) {
            return cachedToken;
        }
        return mintToken();
    }

    private String mintToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        form.add("scope", properties.scope());

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
