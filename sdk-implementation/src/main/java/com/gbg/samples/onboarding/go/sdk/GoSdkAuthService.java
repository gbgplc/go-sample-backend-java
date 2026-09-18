package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.Go;
import com.gbg.gocore.models.ClientCredentialsGrantRequest;
import com.gbg.gocore.models.ClientCredentialsGrantRequestGrantType;
import com.gbg.gocore.models.PasswordGrantRequest;
import com.gbg.gocore.models.PasswordGrantRequestGrantType;
import com.gbg.gocore.models.Scope;
import com.gbg.gocore.models.errors.APIException;
import com.gbg.gocore.models.operations.PostAsTokenOauth2Request;
import com.gbg.gocore.models.operations.PostAsTokenOauth2Response;
import com.gbg.gocore.models.operations.PostAsTokenOauth2ResponseBody;
import com.gbg.samples.onboarding.api.OnboardingException;
import com.gbg.samples.onboarding.config.GoSdkProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Exchanges the Go client credentials for a Bearer token and caches it until
 * shortly before it expires — the go-core-sdk sibling of api-implementation's
 * {@code GoTokenService}. This is the one place {@code go.client-secret}
 * (and, under the password grant, {@code go.password}) is read.
 *
 * <p>Unlike the raw-HTTP version, minting goes through
 * {@code sdk.tokens().generate()} rather than a hand-rolled form-POST — the
 * spike confirmed both grants are modeled as first-class SDK request types
 * ({@link ClientCredentialsGrantRequest}, {@link PasswordGrantRequest}, both
 * implementing {@link PostAsTokenOauth2Request}) with a response shape
 * ({@link PostAsTokenOauth2ResponseBody}: {@code accessToken}/{@code expiresIn}/
 * {@code tokenType}) that matches the old {@code GoTokenResponse} exactly (see
 * {@code spike/sdk-jar-inspection/FINDINGS.md}, Q6). The caching wrapper
 * itself — {@code cachedToken}/{@code cachedTokenExpiresAt}/
 * {@code REFRESH_MARGIN_SECONDS}, synchronized mint-on-demand — is ported
 * near-verbatim, because nothing in the SDK auto-refreshes a token for you.
 *
 * <p><b>Known limitation, unverified against a live fabric tenant</b> (see
 * {@code GoSdkProperties}'s javadoc and this module's README "Known gaps"):
 * {@code tokens().generate(request, serverURL)}'s {@code serverURL} parameter
 * only overrides the scheme+host the SDK talks to — the request path itself is
 * hardcoded to {@code /as/token.oauth2} inside the generated
 * {@code PostAsTokenOauth2} operation class. That is exactly the public
 * platform's PingFederate path, so client_credentials against
 * {@code api.auth.gbgplc.com} works as expected. Whether the fabric nonprod
 * tenant's Keycloak realm also exposes its token endpoint at that literal path
 * is not something static reading of the SDK source can answer — if it
 * doesn't, minting under the password grant will fail with a 404 from the
 * SDK's own client regardless of what {@code go.auth-url} is configured to,
 * and the fix is outside this service (there is no code-level workaround
 * given the SDK's fixed path).
 */
@Component
@ConditionalOnProperty(prefix = "go", name = "mode", havingValue = "live")
public class GoSdkAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoSdkAuthService.class);
    private static final long REFRESH_MARGIN_SECONDS = 30;

    private final GoSdkProperties properties;
    /**
     * A bare SDK client used only to reach {@code tokens()} — token generation
     * takes no {@code customerAccess} security (see {@code Tokens.generate},
     * which unlike {@code Interactions.submit/fetch} accepts no security
     * parameter), so this instance never needs rebuilding the way
     * {@link GoSdkClient}'s does on token rotation.
     */
    private final Go tokenClient;

    private volatile String cachedToken;
    private volatile Instant cachedTokenExpiresAt = Instant.EPOCH;

    public GoSdkAuthService(GoSdkProperties properties) {
        this.properties = properties;
        this.tokenClient = Go.builder().build();
    }

    public synchronized String accessToken() {
        if (cachedToken != null && Instant.now().isBefore(cachedTokenExpiresAt)) {
            return cachedToken;
        }
        return mintToken();
    }

    private String mintToken() {
        PostAsTokenOauth2Request request = buildGrantRequest();
        try {
            PostAsTokenOauth2Response response = tokenClient.tokens()
                    .generate(request, properties.authServerUrl());
            PostAsTokenOauth2ResponseBody body = response.object().orElse(null);
            if (body == null || body.accessToken().isEmpty()) {
                throw OnboardingException.upstreamUnavailable("Could not authenticate with the identity platform.");
            }
            cachedToken = body.accessToken().orElseThrow();
            long expiresIn = body.expiresIn().orElse(0L);
            cachedTokenExpiresAt = Instant.now().plusSeconds(Math.max(0, expiresIn - REFRESH_MARGIN_SECONDS));
            return cachedToken;
        } catch (APIException e) {
            log.error("Failed to mint a Go access token: {} {}", e.code(), e.bodyAsString().orElse(""), e);
            throw OnboardingException.upstreamUnavailable("Could not authenticate with the identity platform. Try again shortly.");
        } catch (OnboardingException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to mint a Go access token", e);
            throw OnboardingException.upstreamUnavailable("Could not authenticate with the identity platform. Try again shortly.");
        }
    }

    /**
     * Branches on {@code go.grant-type} exactly like the raw-HTTP version did,
     * just building an SDK request type instead of a form body.
     *
     * Note: {@link ClientCredentialsGrantRequest#scope()} is a fixed
     * single-value enum ({@link Scope#GBG_TOKEN}, {@code "gbg.token"}) in the
     * generated SDK — there is no way to send a different scope string through
     * it even though {@link GoSdkProperties#scope()} remains configurable for
     * parity with the raw-HTTP field set. That is a real, minor behaviour
     * difference from api-implementation, harmless for the public platform
     * (whose only valid scope is {@code gbg.token} anyway) and irrelevant for
     * the password grant, which never sends a scope on either implementation.
     */
    private PostAsTokenOauth2Request buildGrantRequest() {
        if (properties.passwordGrant()) {
            return new PasswordGrantRequest(
                    properties.clientId(),
                    properties.clientSecret(),
                    properties.username(),
                    properties.password(),
                    PasswordGrantRequestGrantType.PASSWORD);
        }
        return new ClientCredentialsGrantRequest(
                properties.clientId(),
                properties.clientSecret(),
                ClientCredentialsGrantRequestGrantType.CLIENT_CREDENTIALS,
                Scope.GBG_TOKEN);
    }
}
