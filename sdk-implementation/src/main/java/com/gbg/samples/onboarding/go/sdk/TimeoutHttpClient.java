package com.gbg.samples.onboarding.go.sdk;

import com.gbg.gocore.utils.HTTPClient;
import com.gbg.gocore.utils.SpeakeasyHTTPClient;
import com.gbg.gocore.utils.Utils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Applies a fixed request timeout to every call the SDK makes.
 *
 * <p>Code-review finding: neither {@link GoSdkAuthService}'s token client
 * nor {@link GoSdkClient}'s {@code Go} instance had any timeout configured
 * — the SDK's default {@code SpeakeasyHTTPClient} wraps a bare
 * {@code HttpClient.newHttpClient()}. A stalled response (a dropped
 * connection, a slow token endpoint during a Keycloak refresh) hangs the
 * calling thread forever rather than failing fast — and since {@code
 * accessToken()} mints while holding a lock (see the double-checked-locking
 * fix in both auth services), a hang there stalls every other request
 * needing the cached token too.
 *
 * <p>{@link HttpRequest}'s own per-request {@code timeout(Duration)} covers
 * the whole request-response cycle, connect included, which is why this
 * sets it per-request via {@link Utils#copy} rather than trying to
 * configure {@code SpeakeasyHTTPClient}'s internal {@code HttpClient}
 * directly — it isn't exposed for that purpose.
 */
class TimeoutHttpClient implements HTTPClient {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HTTPClient delegate;

    TimeoutHttpClient() {
        this(new SpeakeasyHTTPClient());
    }

    TimeoutHttpClient(HTTPClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException, URISyntaxException {
        HttpRequest timedRequest = Utils.copy(request).timeout(REQUEST_TIMEOUT).build();
        return delegate.send(timedRequest);
    }

    @Override
    public void enableDebugLogging(boolean enabled) {
        delegate.enableDebugLogging(enabled);
    }

    @Override
    public boolean isDebugLoggingEnabled() {
        return delegate.isDebugLoggingEnabled();
    }
}
